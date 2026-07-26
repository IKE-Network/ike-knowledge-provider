package network.ike.knowledge.provider;

import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.EntityCountSummary;
import dev.ikm.tinkar.common.service.PluggableService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.common.service.TrackingCallable;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.aggregator.TemporalEntityAggregator;
import dev.ikm.tinkar.entity.export.ExportEntitiesToProtobufFile;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.reasoner.service.ClassifierResults;
import dev.ikm.tinkar.reasoner.service.ReasonerService;
import dev.ikm.tinkar.terms.TinkarTerm;
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
 * inside the bulk-load bracket, and, by default, a full classification whose inferred
 * axiom and navigation semantics make the assembled KB navigable the moment a browser
 * opens it. Assembling under a data-source directory (the install directory) makes the
 * result directly selectable.
 *
 * <p>The view specification's resolution into coordinate records is the resolver
 * increment (IKE-Network/ike-issues#849); classification currently runs under the
 * engine's default view, matching the headless precedent. The effective view is
 * reported beside the store root.
 */
public final class ChronologyStoreAssembler implements KnowledgeBaseAssembler {

    private static final String DEFAULT_REASONER = "ElkSnomedReasonerService";

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
                EntityService.get().beginLoadPhase();
                try {
                    for (int i = firstLoad; i < inputs.size(); i++) {
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

                classification = request.classify()
                        ? Optional.of(classify(request.reasonerService().orElse(DEFAULT_REASONER)))
                        : Optional.empty();

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
                if (Files.exists(install)) {
                    deleteRecursively(install);
                }
                copyRecursively(storeRoot, install);
            }

            return new AssembleResult(loads, classification, Optional.of(report), reasonedPb);
        } catch (IOException e) {
            throw new UncheckedIOException("Knowledge-base assembly failed", e);
        }
    }

    /**
     * Exports the open, classified store as a full standalone reasoned protobuf — the
     * {@code reasoned-pb} classifier form: every entity across all time, inferred
     * semantics included, via the same temporal-aggregator export Komet's own export
     * controller drives (IKE-Network/ike-issues#933).
     *
     * @param file the export file to write
     * @return the written file
     * @throws IOException if the parent directory cannot be created
     */
    private static Path exportReasonedPb(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        new ExportEntitiesToProtobufFile(file.toFile(),
                new TemporalEntityAggregator(0L, Long.MAX_VALUE)).compute();
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
                    TinkarTerm.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN,
                    TinkarTerm.EL_PLUS_PLUS_INFERRED_AXIOMS_PATTERN);
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

    private static void unzipInto(Path zip, Path targetRoot) throws IOException {
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

    private static void deleteRecursively(Path root) throws IOException {
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
