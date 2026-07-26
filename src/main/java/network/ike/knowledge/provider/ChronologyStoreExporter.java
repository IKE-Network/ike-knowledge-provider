package network.ike.knowledge.provider;

import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.EntityCountSummary;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.aggregator.TemporalEntityAggregator;
import dev.ikm.tinkar.entity.builder.KnowledgeSet;
import dev.ikm.tinkar.entity.builder.KnowledgeSetSource;
import dev.ikm.tinkar.entity.builder.KonceptExtractor;
import dev.ikm.tinkar.entity.export.ExportEntitiesToProtobufFile;
import network.ike.knowledge.spi.EntityCounts;
import network.ike.knowledge.spi.ExportRequest;
import network.ike.knowledge.spi.ExportResult;
import network.ike.knowledge.spi.KnowledgeExporter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;

/**
 * Exports a knowledge set's change-set artifact from its ledger source — the
 * chronology-store {@link KnowledgeExporter}: discovers the {@link KnowledgeSetSource},
 * replays the composed session into a fresh in-memory store, exports the store by real
 * time (which excludes the store's startup sentinel stamp), and optionally extracts the
 * koncept definitions from the same materialized store — one materialization, both
 * artifacts.
 *
 * <p>The view specification's export dimensions are honored by the resolver increment
 * (IKE-Network/ike-issues#849); this provider currently exports the whole set, matching
 * the behavior the deprecated seam main established.
 */
public final class ChronologyStoreExporter implements KnowledgeExporter {

    /**
     * The identity of rich-surface-starter-knowledge's "Prose element pattern
     * (RichSurfaceTerms)" — the pattern every curated {@code koncept-narrative}
     * semantic is written against (see {@code NarrativeContentSet} in
     * ike-starter-set). Hardcoded here, not in tinkar-core's generic
     * {@link KonceptExtractor}: this is an IKE-ecosystem-specific convention,
     * so the reference belongs at this ecosystem-specific layer
     * (IKE-Network/ike-issues#879).
     */
    private static final UUID NARRATIVE_PATTERN_UUID =
            UUID.fromString("89b831a1-e773-5f83-87a6-2cfc8e107fb0");

    /** Creates the provider (ServiceLoader requirement). */
    public ChronologyStoreExporter() {
    }

    @Override
    public ExportResult export(ExportRequest request) {
        try {
            Files.createDirectories(request.outputFile().toAbsolutePath().getParent());
            Path storeRoot = Files.createTempDirectory("ike-knowledge-export");

            CachingService.clearAll();
            ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, storeRoot.toFile());
            PrimitiveData.selectControllerByName("Load Ephemeral Store");
            PrimitiveData.start();
            try {
                KnowledgeSet knowledgeSet = selectSource(request.sourceClass()).compose();
                knowledgeSet.write();

                EntityCountSummary summary = new ExportEntitiesToProtobufFile(
                        request.outputFile().toFile(),
                        new TemporalEntityAggregator(0L, Long.MAX_VALUE)).compute();

                Optional<Path> konceptsYml = request.konceptsYmlFile();
                if (konceptsYml.isPresent()) {
                    Files.createDirectories(konceptsYml.get().toAbsolutePath().getParent());
                    int narrativePatternNid = EntityService.get()
                            .nidForPublicId(PublicIds.of(NARRATIVE_PATTERN_UUID));
                    Files.writeString(konceptsYml.get(), KonceptExtractor.extractYaml(narrativePatternNid));
                }

                return new ExportResult(request.outputFile(),
                        new EntityCounts(summary.conceptCount(), summary.semanticCount(),
                                summary.patternCount(), summary.stampCount()),
                        konceptsYml);
            } finally {
                PrimitiveData.stop();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Change-set export failed", e);
        }
    }

    /**
     * Selects the ledger source: the named class when given, otherwise exactly one
     * discoverable {@link KnowledgeSetSource}.
     *
     * @param sourceClassName the source class name, when several are discoverable
     * @return the selected source
     * @throws IllegalStateException if no source or several sources are discoverable
     *                               without a selection, or the named class cannot be
     *                               loaded or instantiated
     */
    private static KnowledgeSetSource selectSource(Optional<String> sourceClassName) {
        if (sourceClassName.isPresent()) {
            try {
                return (KnowledgeSetSource) Class
                        .forName(sourceClassName.get(), true,
                                Thread.currentThread().getContextClassLoader())
                        .getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | ClassCastException e) {
                throw new IllegalStateException("Cannot instantiate the named ledger source "
                        + sourceClassName.get(), e);
            }
        }
        List<KnowledgeSetSource> found = new ArrayList<>();
        ServiceLoader.load(KnowledgeSetSource.class, Thread.currentThread().getContextClassLoader())
                .forEach(found::add);
        if (found.size() != 1) {
            throw new IllegalStateException("Expected exactly one KnowledgeSetSource on the classpath, found "
                    + found.size() + (found.isEmpty() ? "" : ": "
                    + found.stream().map(source -> source.getClass().getName()).toList())
                    + " — name the source class in the export request to select one");
        }
        return found.getFirst();
    }
}
