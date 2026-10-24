package network.ike.knowledge.provider;

import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.common.id.impl.NidLayout;
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
import dev.ikm.tinkar.importer.registry.Registry;
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
 * Assembles a knowledge base in the 64-bit Spined Array store, or in a Rocks store when the
 * knowledge base will not fit in the heap the Spined Array store needs, or in the store the
 * controller {@value #STORE_CONTROLLER_PROPERTY} names ({@link #storeController}) — the chronology-store
 * {@link KnowledgeBaseAssembler}: an optional store-seed layer, ordered entity loads
 * inside the bulk-load bracket and source releases written by their knowledge importers, the stated navigation derived from the loaded stated
 * definitions ({@link StatedNavigationDeriver}), and, by default, a full classification
 * whose inferred axiom and navigation semantics make the assembled KB navigable the
 * moment a browser opens it, followed by the emptying of the inferred navigation of
 * every concept whose definition is retired, which the reasoner leaves as it was
 * (IKE-Network/ike-issues#1131). Assembling under a data-source directory (the install
 * directory) makes the result directly selectable. The result reports what only the store's
 * builder knows, for the manifest the goal writes into it: the controller that opens it, its
 * nid layout and entity format, and the assembly registration it holds.
 *
 * <p>The view specification's resolution into coordinate records is the resolver
 * increment (IKE-Network/ike-issues#849); classification currently runs under the
 * engine's default view, matching the headless precedent. The effective view is
 * reported beside the store root.
 */
public final class ChronologyStoreAssembler implements KnowledgeBaseAssembler {

    private static final String DEFAULT_REASONER = "ElkSnomedReasonerService";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ChronologyStoreAssembler.class);

    /**
     * The system property naming the store controller to assemble in, chosen by the size of
     * the inputs when it is unset ({@link #storeController}). A Rocks knowledge base names
     * {@value #ROCKS_CONTROLLER}; measuring one store against another names either
     * (IKE-Network/ike-issues#1301).
     */
    public static final String STORE_CONTROLLER_PROPERTY = "ike.knowledge.storeController";

    /**
     * The store a knowledge base is assembled in when it fits the heap (KEC 2026-10-23): the
     * 64-bit Spined Array store, the fastest way to a protobuf export. The export is the
     * lingua franca, neither 32- nor 64-bit; the store only builds it.
     */
    static final String SPINED_ARRAY_CONTROLLER = "Open 64-bit SpinedArrayStore";

    /** The store a knowledge base too large for the heap is assembled in, holding its records on disk. */
    static final String ROCKS_CONTROLLER = "Open Rocks KB";

    /**
     * Store bytes per uncompressed protobuf byte: SNOMED CT's 0.57 GB export fills a 1.02 GB
     * 64-bit Spined Array store in entity format 3, rounded up for the arrays' own overhead.
     */
    static final double STORE_BYTES_PER_PB_BYTE = 2.0;

    /**
     * The share of the heap the Spined Array store may fill, which holds every record as a
     * byte array on the heap; the rest is for the importers, the classifier and the indexes.
     */
    static final double STORE_SHARE_OF_HEAP = 0.5;

    /** The manifest attribute naming the data-source controller that opens the store. */
    static final String OPEN_CONTROLLER = "Ike-Open-Controller";

    /** The manifest attribute naming the store's nid layout. */
    static final String STORE_LAYOUT = "Ike-Store-Layout";

    /** The manifest attribute holding the entity format the store writes. */
    static final String ENTITY_FORMAT = "Ike-Entity-Format";

    /** The manifest attribute holding the identity of the assembly registration the store holds. */
    static final String ASSEMBLY_IDENTITY = "Ike-Assembly-Identity";

    /** Where a knowledge browser keeps its preferences, relative to the store root. */
    private static final String PREFERENCES = "preferences";

    /**
     * The store to assemble in (KEC 2026-10-23): the one named, when one is; otherwise the
     * 64-bit Spined Array store, unless the inputs' estimated store would fill more than
     * {@link #STORE_SHARE_OF_HEAP} of the heap, when Rocks. The estimate counts the protobuf and
     * change-set inputs; a source release is left out, since its size before import says little
     * about the store's (GUDID's 0.4 GB release becomes a 3.3 GB export).
     *
     * @param inputs  the assembly's inputs
     * @param named   the controller the build names, or null
     * @param maxHeap the heap's limit, bytes
     * @return the controller's name
     */
    static String storeController(List<ArtifactInput> inputs, String named, long maxHeap) {
        if (named != null && !named.isBlank()) {
            return named;
        }
        long estimate = estimatedStoreBytes(inputs);
        long budget = (long) (maxHeap * STORE_SHARE_OF_HEAP);
        if (estimate > budget) {
            LOG.info("Assembling in a Rocks store: the inputs would fill an estimated {} MB of 64-bit Spined Array store, "
                    + "more than half of the {} MB heap", estimate >> 20, maxHeap >> 20);
            return ROCKS_CONTROLLER;
        }
        LOG.info("Assembling in a 64-bit Spined Array store: an estimated {} MB of store, within half of the {} MB heap",
                estimate >> 20, maxHeap >> 20);
        return SPINED_ARRAY_CONTROLLER;
    }

    /**
     * The store bytes the protobuf and change-set inputs are estimated to fill: their
     * uncompressed size times {@link #STORE_BYTES_PER_PB_BYTE}.
     *
     * @param inputs the assembly's inputs
     * @return the estimate, bytes
     */
    static long estimatedStoreBytes(List<ArtifactInput> inputs) {
        long uncompressed = 0;
        for (ArtifactInput input : inputs) {
            if (input.role() == ArtifactInput.Role.PB || input.role() == ArtifactInput.Role.CHANGESET) {
                uncompressed += uncompressedSize(input.path());
            }
        }
        return (long) (uncompressed * STORE_BYTES_PER_PB_BYTE);
    }

    /** The uncompressed size of a zip's entries, or the file's own size when it is not a readable zip. */
    private static long uncompressedSize(Path file) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
            long total = 0;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                long size = entries.nextElement().getSize();
                total += Math.max(0, size);
            }
            return total;
        } catch (IOException e) {
            try {
                return Files.size(file);
            } catch (IOException unreadable) {
                return 0;
            }
        }
    }

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
            // What only this implementation knows about the store, for its manifest.
            java.util.Map<String, String> storeAttributes = new java.util.LinkedHashMap<>();

            CachingService.clearAll();
            ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, storeRoot.toFile());
            String controller = storeController(inputs, System.getProperty(STORE_CONTROLLER_PROPERTY), Runtime.getRuntime().maxMemory());
            PrimitiveData.selectControllerByName(controller);
            PrimitiveData.start();
            storeAttributes.put(OPEN_CONTROLLER, controller);
            try {
                // Loads run inside the bulk-load bracket; each source's importer in a bracket of its own.
                int i = firstLoad;
                boolean searchIndexStale = false;
                // The stamps the source imports wrote under, new or not: what a source delta export carries.
                java.util.Set<Long> sourceStamps = new java.util.HashSet<>();
                // The release registrations, in the order they arrived (IKE-Network/ike-issues#1304).
                List<Long> releases = arrivals(List.of(), Registry.releaseRegistrations());
                // Each input's checksum, by path: a source's is taken for its registration, and reused.
                java.util.Map<Path, String> checksums = new java.util.HashMap<>();
                while (i < inputs.size()) {
                    if (inputs.get(i).role() == ArtifactInput.Role.SOURCE) {
                        ArtifactInput input = inputs.get(i);
                        java.util.Set<Long> before = stampNids();
                        java.util.Set<Long> base = Registry.releaseRegistrations();
                        ImportedSource imported = importSource(input);
                        // What the writer wrote under, and anything the importer wrote around it.
                        java.util.Set<Long> written = new java.util.HashSet<>(imported.stampsWritten());
                        java.util.Set<Long> created = stampNids();
                        created.removeAll(before);
                        written.addAll(created);
                        Optional<Long> registered = Registry.registerRelease(
                                new Registry.Release(artifact(input), input.resolvedVersion(),
                                        checksums.computeIfAbsent(input.path(), Registry::checksum)),
                                imported.importer(), input.options(), imported.report(), written, base);
                        registered.ifPresent(written::add);
                        loads.add(registered.isPresent() ? imported.summary() : new LoadSummary(
                                imported.summary().artifact() + "; " + NOT_REGISTERED, imported.summary().counts()));
                        sourceStamps.addAll(written);
                        releases = arrivals(releases, Registry.releaseRegistrations());
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
                    releases = arrivals(releases, Registry.releaseRegistrations());
                }
                if (searchIndexStale) {
                    recreateSearchIndex();
                }

                // One source's artifact: what its import wrote, before anything is derived from it.
                if (request.sourceDeltaFile().isPresent()) {
                    Path delta = request.sourceDeltaFile().get();
                    EntityCountSummary exported = exportSourceDelta(delta, sourceStamps);
                    Registry.registerExport(delta, "source delta: the versions under the "
                            + sourceStamps.size() + " stamps the source imports wrote under, unclassified",
                            exported.getTotalCount(), java.time.Instant.now());
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
                            KernelPattern.INFERRED_NAVIGATION_PATTERN.nid());
                }

                // The extension's koncepts, read from the whole classified store, so a concept
                // authored under a release's concept keeps its parent's name.
                if (request.konceptsYmlFile().isPresent()) {
                    writeExtensionKoncepts(request.konceptsYmlFile().get(), request.konceptsModules());
                }

                // What the knowledge base is made of, registered in it before it is exported.
                List<Registry.AssemblyInput> assemblyInputs = inputs.stream()
                        .map(input -> new Registry.AssemblyInput(input.role().name(), artifact(input),
                                input.resolvedVersion(),
                                checksums.computeIfAbsent(input.path(), Registry::checksum)))
                        .toList();
                if (Registry.registerAssembly(assemblyInputs, releases, classification.map(ClassificationSummary::service),
                        java.time.Instant.now()).isPresent()) {
                    storeAttributes.put(ASSEMBLY_IDENTITY, Registry.assemblyRegistrationUuid(assemblyInputs).toString());
                }

                // The reasoned-pb export must see the classified store while it is
                // still open: full standalone export, inferred semantics included
                // (IKE-Network/ike-issues#933). Registered after it is written, so the
                // store remembers it; the file itself cannot hold its own checksum.
                if (request.reasonedPbFile().isPresent()) {
                    Path file = request.reasonedPbFile().get();
                    EntityCountSummary exported = exportReasonedPb(file);
                    Registry.registerExport(file, "reasoned: every component, all time, inferred semantics included",
                            exported.getTotalCount(), java.time.Instant.now());
                    reasonedPb = Optional.of(file);
                } else {
                    reasonedPb = Optional.empty();
                }
                // The layout the open store activated, and the entity format it writes.
                storeAttributes.put(STORE_LAYOUT, NidLayout.active().displayName());
                storeAttributes.put(ENTITY_FORMAT, Byte.toString(NidLayout.active().entityFormat()));
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

            return new AssembleResult(loads, classification, Optional.of(report), reasonedPb, storeAttributes);
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
     * wrote under, new or not, unclassified; references to the inputs before them travel by
     * public id.
     */
    private static EntityCountSummary exportSourceDelta(Path file, java.util.Set<Long> sourceStamps) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return new ExportEntitiesToProtobufFile(file.toFile(),
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
    private static ImportedSource importSource(ArtifactInput input) {
        String importerName = input.importer().orElseThrow();
        dev.ikm.tinkar.importer.KnowledgeImporter importer = KnowledgeImporters.named(importerName);
        // Inside the bulk-load bracket, as a load: outside it every write is indexed and the
        // searcher refreshed, one write at a time, which a release of millions of rows cannot
        // afford. The bracket is not nested; an importer that loads a file midway re-enters it.
        ImportReport report;
        StoreWriter writer = new StoreWriter(Calculators.Stamp.DevelopmentLatest());
        EntityService.get().beginLoadPhase();
        try {
            report = importer.importInto(new ImportSource(input.path(), input.options()), writer);
        } finally {
            EntityService.get().endLoadPhase();
        }
        return new ImportedSource(new LoadSummary(report.sourceVersion() + " via " + importerName + " (written "
                + report.written() + ", versioned " + report.versioned() + ", unchanged " + report.unchanged()
                + ", in " + report.elapsed() + ")", new EntityCounts(0, 0, 0, 0)), report, importer,
                writer.stampsWritten());
    }

    /**
     * A source import: its load summary, its importer's report, the importer that ran, and the
     * stamps its writer wrote under.
     */
    private record ImportedSource(LoadSummary summary, ImportReport report,
                                 dev.ikm.tinkar.importer.KnowledgeImporter importer, java.util.Set<Long> stampsWritten) {
    }

    /** The note a source's load summary carries when the store does not declare the registry. */
    static final String NOT_REGISTERED = "not registered: the starter set does not declare the registry";

    /** The input as it was named: its Maven coordinate as written, otherwise its file name. */
    static String artifact(ArtifactInput input) {
        return input.coordinate().orElseGet(() -> String.valueOf(input.path().getFileName()));
    }

    /**
     * The nids a list holds, in order, followed by those of a set it does not, in nid order.
     *
     * @param arrived the nids so far, in arrival order
     * @param now     the nids the store holds now
     * @return the extended arrival order
     */
    static List<Long> arrivals(List<Long> arrived, java.util.Set<Long> now) {
        List<Long> order = new ArrayList<>(arrived);
        now.stream().filter(nid -> !arrived.contains(nid)).sorted().forEach(order::add);
        return order;
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
     * @return what was written
     * @throws IOException if the parent directory cannot be created
     */
    static EntityCountSummary exportReasonedPb(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return new ExportEntitiesToProtobufFile(file.toFile(),
                new TemporalEntityAggregator(Long.MIN_VALUE, Long.MAX_VALUE)).compute();
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
                    KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN,
                    KernelPattern.EL_PLUS_PLUS_INFERRED_AXIOMS_PATTERN);
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
