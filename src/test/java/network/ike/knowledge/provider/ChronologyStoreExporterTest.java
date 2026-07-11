package network.ike.knowledge.provider;

import network.ike.knowledge.spi.ExportRequest;
import network.ike.knowledge.spi.ExportResult;
import network.ike.knowledge.spi.KnowledgeExporter;
import network.ike.knowledge.spi.KnowledgeServices;
import network.ike.knowledge.spi.ViewSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exporter end-to-end against the real ledger source and the real in-memory store:
 * discovery through the actual ServiceLoader registration, replay, protobuf export,
 * koncept extraction from the same materialized store, and the wire round-trip of the
 * result — no mocks anywhere.
 *
 * <p>One test method by design: the engine's store lifecycle is JVM-global (a second
 * start after stop reports SHUTDOWN), which is exactly why production invocations run
 * one fork per goal. The test mirrors that: one lifecycle, everything verified inside
 * it.
 */
class ChronologyStoreExporterTest {

    @Test
    @DisplayName("Discovery, export, koncept extraction, and the result wire round-trip — one real lifecycle")
    void exportEndToEnd(@TempDir Path tempDir) throws Exception {
        KnowledgeExporter exporter = KnowledgeServices.select(KnowledgeExporter.class, Optional.empty());
        assertThat(exporter).isInstanceOf(ChronologyStoreExporter.class);

        Path changeSet = tempDir.resolve("example-changeset.zip");
        Path koncepts = tempDir.resolve("example-koncepts.yml");
        ExportRequest request = new ExportRequest(changeSet, Optional.of(koncepts),
                Optional.of(ExampleLedgerSource.class.getName()), ViewSpec.empty());

        // Through the same codec bridge the forked bootstrap uses.
        ExportResult result = exporter.execute(exporter.requestFromProperties(request.toProperties()));

        assertThat(result.outputFile()).isEqualTo(changeSet);
        assertThat(Files.size(changeSet)).isPositive();
        // Two concepts, one pattern, one declared stamp; descriptions/dialects/axioms as semantics.
        assertThat(result.counts().concepts()).isEqualTo(2);
        assertThat(result.counts().patterns()).isEqualTo(1);
        assertThat(result.counts().stamps()).isEqualTo(1);
        assertThat(result.counts().semantics()).isGreaterThanOrEqualTo(6);

        String yaml = Files.readString(koncepts);
        assertThat(yaml).contains("Example root");
        assertThat(yaml).contains("Example child");
        assertThat(yaml).contains("Example membership pattern");
        // The child's broader identifiers include the in-set parent.
        assertThat(yaml).contains("broader");

        assertThat(ExportResult.fromProperties(exporter.resultToProperties(result))).isEqualTo(result);
    }
}
