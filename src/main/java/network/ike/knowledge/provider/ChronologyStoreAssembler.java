package network.ike.knowledge.provider;

import dev.ikm.tinkar.terms.KernelTerm;
import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.EntityCountSummary;
import dev.ikm.tinkar.common.service.PluggableService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.SearchService;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceLifecycleManager;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.common.service.TrackingCallable;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.aggregator.TemporalEntityAggregator;
import dev.ikm.tinkar.entity.export.ExportEntitiesToProtobufFile;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.importer.ImportReport;
import dev.ikm.tinkar.importer.ImportSource;
import dev.ikm.tinkar.importer.KnowledgeImporters;
import dev.ikm.tinkar.importer.StoreWriter;
import dev.ikm.tinkar.reasoner.service.ClassifierResults;
import dev.ikm.tinkar.reasoner.service.ReasonerService;
import network.ike.knowledge.spi.ArtifactInput;
import network.ike.knowledge.spi.AssembleRequest;
import network.ike.knowledge.spi.AssembleResult;
import network.ike.knowledge.spi.ClassificationSummary;
import network.ike.knowledge.spi.EntityCounts;
import network.ike.knowledge.spi.KnowledgeBaseAssembler;
import network.ike.knowledge.spi.LoadSummary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Assembles a knowledge base in a Spined Array store — the chronology-store
 * {@link KnowledgeBaseAssembler}: an optional store-seed layer, ordered entity loads
 * inside the bulk-load bracket and source releases written by their knowledge importers, the stated navigation derived from the loaded stated
 * definitions ({@link StatedNavigationDeriver}), and, by default, a full classification
 * whose inferred axiom and navigation semantics make the assembled KB navigable the
 * moment a browser opens it, followed by the emptying of the inferred navigation of
 * every concept whose definition is retired, which the reasoner leaves as it was
 * (IKE-Network/ike-issues#1131). Assembling under a data-source directory (the install
 * directory) makes the result directly selectable.
 *
 * <p>The view specification's resolution into coordinate records is the resolver
 * increment (IKE-Network/ike-issues#849); classification currently runs under the
 * engine's default view, matching the headless precedent. The effective view is
 * reported beside the store root.
 */
public final class ChronologyStoreAssembler implements KnowledgeBaseAssembler {

    private static final String DEFAULT_REASONER = "ElkSnomedReasonerService";

    /** Where a knowledge browser keeps its preferences, relative to the store root. */
    private static final String PREFERENCES = "preferences";

    /** Creates the provider (ServiceLoader requirement). */
    public ChronologyStoreAssembler() {
    }

    @Override
    public AssembleResult assemble(AssembleRequest request) {
        try {
            Path storeRoot = request.storeRoot();
            if (request.cleanStart() && Files.exists(storeRoot)) {
                deleteRecursively(storeRoot);
            }
            Files.createDirectories(storeRoot);

            List<ArtifactInput> inputs = request.inputs();
            int firstLoad = 0;
            if (inputs.getFirst().role() == ArtifactInput.Role.STORE_SEED) {
                unzipInto(inputs.getFirst().path(), storeRoot);
                firstLoad = 1;
            }

            List<LoadSummary> loads = new ArrayList<>();
            Optional<ClassificationSummary> classification;
            Optional<Path> reasonedPb;

            CachingService.clearAll();
            ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, storeRoot.toFile());
            PrimitiveData.selectControllerByName("Open SpinedArrayStore");
            PrimitiveData.start();
            try {
                // Loads run inside the bulk-load bracket; each source's importer in a bracket of its own.
                int i = firstLoad;
                boolean searchIndexStale = false;
                // The stamps the source imports create: what a source delta export carries.
                java.util.Set<Long> sourceStamps = new java.util.HashSet<>();
                while (i < inputs.size()) {
                    if (inputs.get(i).role() == ArtifactInput.Role.SOURCE) {
                        java.util.Set<Long> before = request.sourceDeltaFile().isPresent() ? stampNids() : java.util.Set.of();
                        loads.add(importSource(inputs.get(i)));
                        if (request.sourceDeltaFile().isPresent()) {
                            java.util.Set<Long> after = stampNids();
                            after.removeAll(before);
                            sourceStamps.addAll(after);
                        }
                        // Past the live-index threshold the load phase stops indexing; the next
                        // bracket resets the policy, so the need is remembered here.
                        searchIndexStale |= EntityService.get().loadPhaseSearchPolicy().recreateRequired();
                        i++;
                        continue;
                    }
                    EntityService.get().beginLoadPhase();
                    try {
                        for (; i < inputs.size() && inputs.get(i).role() != ArtifactInput.Role.SOURCE; i++) {
                            ArtifactInput input = inputs.get(i);
                            EntityCountSummary summary =
                                    new LoadEntitiesFromProtobufFile(input.path().toFile()).compute();
                            loads.add(new LoadSummary(input.path().getFileName().toString(),
                                    new EntityCounts(summary.conceptCount(), summary.semanticCount(),
                                            summary.patternCount(), summary.stampCount())));
                        }
                    } finally {
                        EntityService.get().endLoadPhase();
                    }
                }
                if (searchIndexStale) {
                    recreateSearchIndex();
                }

                // One source's artifact: what its import wrote, before anything is derived from it.
                if (request.sourceDeltaFile().isPresent()) {
                    exportSourceDelta(request.sourceDeltaFile().get(), sourceStamps);
                }

                // The loaded files carry stated navigation as it stood when they were
                // written; a ledger that re-declares definitions leaves it stale, and the
                // reasoner writes inferred navigation only (IKE-Network/ike-issues#1123).
                StatedNavigationDeriver.derive(Coordinates.View.DefaultView());

                classification = request.classify()
                        ? Optional.of(classify(request.reasonerService().orElse(DEFAULT_REASONER)))
                        : Optional.empty();
                if (classification.isPresent()) {
                    // The reasoner writes the concepts it classified; a concept whose
                    // definition is retired keeps the inferred parents it had
                    // (IKE-Network/ike-issues#1131).
                    StatedNavigationDeriver.emptyNavigationOfUndefined(Coordinates.View.DefaultView(),
                            KernelTerm.INFERRED_NAVIGATION_PATTERN.nid());
                }

                // The extension's koncepts, read from the whole classified store, so a concept
                // authored under a release's concept keeps its parent's name.
                if (request.konceptsYmlFile().isPresent()) {
                    writeExtensionKoncepts(request.konceptsYmlFile().get(), request.konceptsModules());
                }

                // The reasoned-pb export must see the classified store while it is
                // still open: full standalone export, inferred semantics included
                // (IKE-Network/ike-issues#933).
                reasonedPb = request.reasonedPbFile().isPresent()
                        ? Optional.of(exportReasonedPb(request.reasonedPbFile().get()))
                        : Optional.empty();
            } finally {
                PrimitiveData.stop();
            }

            Path report = writeEffectiveViewReport(request);

            if (request.installDirectory().isPresent()) {
                Path install = request.installDirectory().get();
                // The browser's preferences (its windows, and the view and edit coordinates a
                // person chose) belong to the person, not the build: kept across the install.
                // Captured change sets the goal has harvested before assembly; the replaced store
                // starts with none.
                Path preferences = install.resolve(PREFERENCES);
                Path keptPreferences = null;
                if (Files.isDirectory(preferences)) {
                    keptPreferences = Files.createTempDirectory("kb-preferences-");
                    copyRecursively(preferences, keptPreferences);
                }
                if (Files.exists(install)) {
                    deleteRecursively(install);
                }
                copyRecursively(storeRoot, install);
                if (keptPreferences != null) {
                    copyRecursively(keptPreferences, install.resolve(PREFERENCES));
                    deleteRecursively(keptPreferences);
                }
            }

            return new AssembleResult(loads, classification, Optional.of(report), reasonedPb);
        } catch (IOException e) {
            throw new UncheckedIOException("Knowledge-base assembly failed", e);
        }
    }

    /** Every stamp in the store, by nid. */
    private static java.util.Set<Long> stampNids() {
        java.util.Set<Long> nids = java.util.concurrent.ConcurrentHashMap.newKeySet();
        EntityService.get().forEachStampEntity(stamp -> nids.add(stamp.nid()));
        return new java.util.HashSet<>(nids);
    }

    /**
     * Exports what the source imports wrote, and only that: every version under a stamp they
     * created, unclassified; references to the inputs before them travel by public id.
     */
    private static void exportSourceDelta(Path file, java.util.Set<Long> sourceStamps) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        new ExportEntitiesToProtobufFile(file.toFile(),
                new TemporalEntityAggregator(Long.MIN_VALUE, Long.MAX_VALUE), sourceStamps::contains).compute();
    }

    /**
     * Writes the koncepts YAML of the store's extension: the concepts and patterns with a
     * version in one of the extension's modules.
     *
     * @param file        the YAML file
     * @param moduleUuids the extension's modules, by UUID
     */
    private static void writeExtensionKoncepts(Path file, java.util.Set<String> moduleUuids) throws IOException {
        java.util.Set<Long> moduleNids = new java.util.HashSet<>();
        for (String uuid : moduleUuids) {
            moduleNids.add(PrimitiveData.nid(dev.ikm.tinkar.common.id.PublicIds.of(java.util.UUID.fromString(uuid))));
        }
        java.util.function.LongPredicate inExtension = nid -> dev.ikm.tinkar.entity.EntityHandle.get(nid).entity()
                .map(entity -> entity.versions().anySatisfy(version ->
                        moduleNids.contains(((dev.ikm.tinkar.entity.EntityVersion) version).stamp().moduleNid())))
                .orElse(false);
        long narrativePatternNid = EntityService.get().nidForPublicId(
                dev.ikm.tinkar.common.id.PublicIds.of(ChronologyStoreExporter.NARRATIVE_PATTERN_UUID));
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, dev.ikm.tinkar.entity.builder.KonceptExtractor.extractYaml(narrativePatternNid, inExtension));
    }

    /**
     * Rebuilds the search index from the store. A source import writes far more semantics than
     * the load phase indexes live, and unlike a protobuf load it does not rebuild the index
     * itself: without this, a browser's search finds only the first few thousand descriptions.
     */
    private static void recreateSearchIndex() {
        Optional<SearchService> search = ServiceLifecycleManager.get().getRunningService(SearchService.class);
        if (search.isEmpty()) {
            return;
        }
        try {
            search.get().recreateIndex().get();
        } catch (Exception e) {
            throw new IllegalStateException("Rebuilding the search index after source imports failed", e);
        }
    }

    /**
     * Writes a source release into the open store with the knowledge importer it names,
     * found on the classpath ({@code dev.ikm.tinkar:importer-api}), on the development view.
     * An importer counts what it wrote, not entities by kind, so the summary names the
     * release, the importer and its counts, and carries no entity counts.
     */
    private static LoadSummary importSource(ArtifactInput input) {
        String importerName = input.importer().orElseThrow();
        // Inside the bulk-load bracket, as a load: outside it every write is indexed and the
        // searcher refreshed, one write at a time, which a release of millions of rows cannot
        // afford. The bracket is not nested; an importer that loads a file midway re-enters it.
        ImportReport report;
        EntityService.get().beginLoadPhase();
        try {
            report = KnowledgeImporters.named(importerName).importInto(
                    new ImportSource(input.path(), input.options()),
                    new StoreWriter(Calculators.Stamp.DevelopmentLatest()));
        } finally {
            EntityService.get().endLoadPhase();
        }
        return new LoadSummary(report.sourceVersion() + " via " + importerName + " (written " + report.written()
                + ", versioned " + report.versioned() + ", unchanged " + report.unchanged() + ", in "
                + report.elapsed() + ")", new EntityCounts(0, 0, 0, 0));
    }

    /**
     * Exports the open, classified store as a full standalone reasoned protobuf — the
     * {@code reasoned-pb} classifier form: every entity across all time, inferred
     * semantics included, via the same temporal-aggregator export Komet's own export
     * controller drives (IKE-Network/ike-issues#933). All time starts at the earliest
     * representable instant, not at the epoch: a loaded file's versions stamped before
     * the epoch are part of the store and belong in its export
     * (IKE-Network/ike-issues#1123).
     *
     * @param file the export file to write
     * @return the written file
     * @throws IOException if the parent directory cannot be created
     */
    static Path exportReasonedPb(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        new ExportEntitiesToProtobufFile(file.toFile(),
                new TemporalEntityAggregator(Long.MIN_VALUE, Long.MAX_VALUE)).compute();
        return file;
    }

    /**
     * Runs the full classification sequence and returns its summary. Derived semantics
     * (inferred axioms and inferred navigation) are written into the open store.
     */
    private static ClassificationSummary classify(String reasonerName) {
        List<ReasonerService> found = new ArrayList<>();
        PluggableService.load(ReasonerService.class).forEach(found::add);
        ReasonerService reasoner = found.stream()
                .filter(candidate -> candidate.getClass().getSimpleName().equals(reasonerName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Reasoner service \"" + reasonerName
                        + "\" is not on the classpath — found "
                        + found.stream().map(candidate -> candidate.getClass().getSimpleName()).toList()));

        long started = System.currentTimeMillis();
        TrackingCallable<Void> progress = new TrackingCallable<>() {
            @Override
            protected Void compute() {
                return null;
            }
        };
        try {
            reasoner.init(Calculators.View.Default(),
                    KernelTerm.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN,
                    KernelTerm.EL_PLUS_PLUS_INFERRED_AXIOMS_PATTERN);
            reasoner.extractData(progress);
            reasoner.loadData(progress);
            reasoner.computeInferences(progress);
            reasoner.buildNecessaryNormalForm(progress);
            ClassifierResults results = reasoner.writeInferredResults(progress);
            return new ClassificationSummary(reasonerName,
                    results.getClassificationConceptSet().size(),
                    results.getConceptsWithInferredChanges().size(),
                    results.getConceptsWithNavigationChanges().size(),
                    System.currentTimeMillis() - started);
        } catch (Exception e) {
            throw new IllegalStateException("Classification failed in " + reasonerName, e);
        }
    }

    /**
     * Reports the view the assembly ran under — the stated dimensions plus the marker
     * that unresolved dimensions took the engine's default view. Replaced by the fully
     * resolved report when the resolver increment lands.
     */
    private static Path writeEffectiveViewReport(AssembleRequest request) throws IOException {
        Path report = request.storeRoot().toAbsolutePath().getParent()
                .resolve(request.storeRoot().getFileName() + "-effective-view.properties");
        Properties properties = request.view().toProperties("view.");
        properties.setProperty("defaults", "engine default view (resolver: ike-issues#849)");
        try (var out = Files.newOutputStream(report)) {
            properties.store(out, "Effective view of the knowledge-base assembly");
        }
        return report;
    }

    static void unzipInto(Path zip, Path targetRoot) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry = in.getNextEntry();
            while (entry != null) {
                Path target = targetRoot.resolve(entry.getName()).normalize();
                if (!target.startsWith(targetRoot)) {
                    throw new IOException("Store seed entry escapes the store root: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                entry = in.getNextEntry();
            }
        }
    }

    static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

}
