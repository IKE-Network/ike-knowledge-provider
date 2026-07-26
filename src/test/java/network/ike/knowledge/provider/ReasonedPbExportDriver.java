package network.ike.knowledge.provider;

import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.EntityCountSummary;
import dev.ikm.tinkar.common.service.PluggableService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.common.service.TrackingCallable;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.entity.aggregator.TemporalEntityAggregator;
import dev.ikm.tinkar.entity.export.ExportEntitiesToProtobufFile;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.reasoner.service.ClassifierResults;
import dev.ikm.tinkar.reasoner.service.ReasonerService;
import dev.ikm.tinkar.terms.TinkarTerm;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THROWAWAY bridge/diagnostic for IKE-Network/ike-issues#933 — produces a genuine
 * {@code reasoned-pb} of the IKE starter set by doing everything in ONE process on a
 * fresh SpinedArray store: load the unreasoned change set, run the ELK reasoner
 * (SpinedArray supports the reasoner's parallel iteration), then export. Every entity
 * carries its own public id, so {@code EntityProvider.publicId(nid)} resolves each
 * exported entity — and each referenced entity — directly from the retrieved entity;
 * the store's nid→publicId identity-map fallback is reached only for a reference to a
 * component that has no entity at all. If export throws there, the failing reference is
 * a genuine dangling reference, not a store limitation. Not committed.
 *
 * <p>Runtime parameters:
 * <ul>
 *   <li>{@code -Dike.reasonedExport.changesetZip} — the unreasoned change set to load.</li>
 *   <li>{@code -Dike.reasonedExport.outputFile} — target reasoned-pb zip (required).</li>
 * </ul>
 */
final class ReasonedPbExportDriver {

    private static final String DEFAULT_REASONER = "ElkSnomedReasonerService";

    @Test
    void exportReasonedPbInProcess() throws Exception {
        String changesetProp = System.getProperty("ike.reasonedExport.changesetZip");
        String outputProp = System.getProperty("ike.reasonedExport.outputFile");
        assertThat(outputProp).as("-Dike.reasonedExport.outputFile is required").isNotBlank();
        assertThat(changesetProp).as("-Dike.reasonedExport.changesetZip is required").isNotBlank();

        File changesetZip = Path.of(changesetProp).toFile();
        assertThat(changesetZip).as("change set to load").exists();

        File outputFile = Path.of(outputProp).toFile();
        Files.createDirectories(outputFile.toPath().toAbsolutePath().getParent());

        Path storeRoot = Files.createTempDirectory("ike-reasoned-inproc");
        CachingService.clearAll();
        ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, storeRoot.toFile());
        PrimitiveData.selectControllerByName("Open SpinedArrayStore");
        PrimitiveData.start();
        try {
            EntityCountSummary loaded = new LoadEntitiesFromProtobufFile(changesetZip).compute();
            System.out.println("loaded (unreasoned) — " + loaded.getTotalCount() + " entities");

            ClassifierResults classification = classify(DEFAULT_REASONER);
            System.out.println("classified — " + classification.getClassificationConceptSet().size()
                    + " concepts, " + classification.getConceptsWithInferredChanges().size()
                    + " inferred changes");

            EntityCountSummary summary = new ExportEntitiesToProtobufFile(
                    outputFile,
                    new TemporalEntityAggregator(0L, Long.MAX_VALUE)).compute();
            System.out.println("reasoned-pb export → " + outputFile.getAbsolutePath()
                    + " — " + summary.getTotalCount() + " entities ("
                    + summary.conceptCount() + " concepts, " + summary.semanticCount() + " semantics, "
                    + summary.patternCount() + " patterns, " + summary.stampCount() + " stamps)");
            assertThat(outputFile).exists();
            assertThat(Files.size(outputFile.toPath())).isPositive();
            assertThat(summary.getTotalCount()).isGreaterThan(loaded.getTotalCount());
        } finally {
            PrimitiveData.stop();
        }
    }

    /** Round-trip: load the produced reasoned-pb into a fresh store and confirm the counts. */
    @Test
    void verifyReasonedPbRoundTrip() throws Exception {
        String outputProp = System.getProperty("ike.reasonedExport.outputFile");
        assertThat(outputProp).as("-Dike.reasonedExport.outputFile is required").isNotBlank();
        File pb = Path.of(outputProp).toFile();
        assertThat(pb).as("reasoned-pb to verify").exists();

        Path storeRoot = Files.createTempDirectory("ike-roundtrip");
        CachingService.clearAll();
        ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, storeRoot.toFile());
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        try {
            EntityCountSummary loaded = new LoadEntitiesFromProtobufFile(pb).compute();
            System.out.println("round-trip loaded — " + loaded.getTotalCount() + " entities ("
                    + loaded.conceptCount() + " concepts, " + loaded.semanticCount() + " semantics, "
                    + loaded.patternCount() + " patterns, " + loaded.stampCount() + " stamps)");
            // the reasoned export's content must re-read intact (LoadEntitiesFromProtobufFile
            // throws on a manifest/content count mismatch — the wholeness check); concepts and
            // patterns are unchanged by classification, semantics grow by the inferred content.
            assertThat(loaded.conceptCount()).isEqualTo(492L);
            assertThat(loaded.patternCount()).isEqualTo(35L);
            assertThat(loaded.semanticCount()).isGreaterThan(4166L);
            assertThat(loaded.getTotalCount()).isGreaterThan(4695L);
        } finally {
            PrimitiveData.stop();
        }
    }

    /**
     * Diagnostic: load the change set into an ephemeral store (which resolves the minted
     * public id of a referenced-but-absent component) and report every semantic whose
     * referenced component, pattern, or component-valued field has no entity — naming the
     * dangling target's public id so the ledger defect can be fixed (#933).
     */
    @Test
    void findDanglingReferences() throws Exception {
        String changesetProp = System.getProperty("ike.reasonedExport.changesetZip");
        assertThat(changesetProp).as("-Dike.reasonedExport.changesetZip is required").isNotBlank();
        File changesetZip = Path.of(changesetProp).toFile();
        assertThat(changesetZip).exists();

        Path storeRoot = Files.createTempDirectory("ike-dangling");
        CachingService.clearAll();
        ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, storeRoot.toFile());
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        try {
            new LoadEntitiesFromProtobufFile(changesetZip).compute();
            List<String> reports = new ArrayList<>();
            PrimitiveData.get().forEachSemanticNid(semanticNid -> {
                if (EntityService.get().getEntityFast(semanticNid) instanceof SemanticEntity<?> sem) {
                    reportIfAbsent(sem, "referencedComponent", sem.referencedComponentNid(), reports);
                    reportIfAbsent(sem, "pattern", sem.patternNid(), reports);
                    for (SemanticEntityVersion version : sem.versions()) {
                        int i = 0;
                        for (Object field : version.fieldValues()) {
                            if (field instanceof EntityFacade facade) {
                                reportIfAbsent(sem, "field[" + i + "]", facade.nid(), reports);
                            }
                            i++;
                        }
                    }
                }
            });
            reports.stream().distinct().sorted().forEach(System.out::println);
            System.out.println("=== total dangling references: " + reports.size() + " ===");
        } finally {
            PrimitiveData.stop();
        }
    }

    private static void reportIfAbsent(SemanticEntity<?> sem, String label, int refNid, List<String> reports) {
        if (EntityService.get().getEntityFast(refNid) != null) {
            return;
        }
        reports.add("DANGLING " + label + " -> absent component publicId=" + safePid(refNid)
                + " (semantic " + sem.publicId().idString()
                + ", pattern=\"" + safeText(sem.patternNid()) + "\""
                + ", referencedComponent=\"" + safeText(sem.referencedComponentNid()) + "\")");
    }

    private static String safePid(int nid) {
        try {
            return PrimitiveData.publicId(nid).idString();
        } catch (RuntimeException e) {
            return "nid=" + nid;
        }
    }

    private static String safeText(int nid) {
        try {
            return PrimitiveData.text(nid);
        } catch (RuntimeException e) {
            return "nid=" + nid;
        }
    }

    /** Mirrors {@code ChronologyStoreAssembler.classify}, run against the open store. */
    private static ClassifierResults classify(String reasonerName) {
        List<ReasonerService> found = new ArrayList<>();
        PluggableService.load(ReasonerService.class).forEach(found::add);
        ReasonerService reasoner = found.stream()
                .filter(candidate -> candidate.getClass().getSimpleName().equals(reasonerName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Reasoner service \"" + reasonerName
                        + "\" is not on the classpath — found "
                        + found.stream().map(candidate -> candidate.getClass().getSimpleName()).toList()));

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
            return reasoner.writeInferredResults(progress);
        } catch (Exception e) {
            throw new IllegalStateException("Classification failed in " + reasonerName, e);
        }
    }
}
