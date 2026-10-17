package network.ike.knowledge.provider;

import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.entity.changeset.ChangeSetFormat;
import dev.ikm.tinkar.entity.builder.Stamp;
import dev.ikm.tinkar.importer.ImportReport;
import dev.ikm.tinkar.importer.ImportSource;
import dev.ikm.tinkar.importer.KnowledgeImporter;
import dev.ikm.tinkar.importer.StoreWriter;
import dev.ikm.tinkar.schema.Field;
import dev.ikm.tinkar.schema.SemanticChronology;
import dev.ikm.tinkar.schema.TinkarMsg;
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.knowledge.spi.ArtifactInput;
import network.ike.knowledge.spi.AssembleRequest;
import network.ike.knowledge.spi.ViewSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A knowledge base records what it is made of (IKE-Network/ike-issues#1304): each source import
 * writes a source release semantic that travels in the source's delta, and the assembly writes a
 * composition semantic into the knowledge base it exports. The IKE starter set declares the two
 * patterns.
 */
class KnowledgeBaseCompositionTest {

    private static final String RELEASE = "Test release 20261001";

    @Test
    @DisplayName("A source import records its release in its delta; the assembly records its composition")
    void recordsReleaseAndComposition(@TempDir Path work) throws Exception {
        Path release = Files.writeString(work.resolve("test-release.txt"), "one concept");
        Path delta = work.resolve("test-unreasoned-pb.zip");
        Path export = work.resolve("test-reasoned-pb.zip");
        Path starter = Path.of("target", "data", "ike-starter-set-unreasoned-pb.zip");

        network.ike.knowledge.spi.AssembleResult result = new ChronologyStoreAssembler().assemble(new AssembleRequest(work.resolve("store"), true,
                List.of(new ArtifactInput(ArtifactInput.Role.PB, starter)
                                .withCoordinate("network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-SNAPSHOT"),
                        ArtifactInput.source(TestReleaseImporter.NAME, release, Map.of())
                                .withCoordinate("network.ike.test:test-origin:txt:20261001")),
                ViewSpec.empty(), true, Optional.empty(), Optional.empty(), Optional.of(export),
                Optional.empty(), Set.of(), Optional.of(delta)));

        assertThat(result.loads()).as("load summaries").noneMatch(load ->
                load.artifact().contains(KnowledgeBaseComposition.NOT_RECORDED));
        UUID sourceRelease = uuid(KnowledgeBaseComposition.SOURCE_RELEASE_PATTERN_FQN);
        UUID composition = uuid(KnowledgeBaseComposition.COMPOSITION_PATTERN_FQN);

        // The import's record travels in the source delta; the composition does not.
        assertThat(records(delta, sourceRelease)).isEqualTo(1);
        assertThat(records(delta, composition)).isZero();
        // The knowledge base holds it, and one composition.
        assertThat(records(export, sourceRelease)).isEqualTo(1);
        assertThat(records(export, composition)).isEqualTo(1);

        // What the records say: the test release's, as its delta carries it.
        List<List<String>> releases = strings(delta, sourceRelease);
        List<String> test = releases.stream().filter(fields -> fields.contains(RELEASE)).findFirst().orElseThrow();
        assertThat(test).containsExactly(RELEASE, "network.ike.test:test-origin:txt:20261001",
                KnowledgeBaseComposition.checksum(release), test.get(3));
        assertThat(test.get(3)).startsWith(TestReleaseImporter.NAME);

        // The composition: every input in load order, with its checksum, and the classifier.
        List<String> composed = strings(export, composition).getFirst();
        String inputs = composed.getFirst();
        assertThat(inputs.lines()).containsExactly(
                "PB network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-SNAPSHOT "
                        + KnowledgeBaseComposition.checksum(starter),
                "SOURCE network.ike.test:test-origin:txt:20261001 " + KnowledgeBaseComposition.checksum(release));
        assertThat(composed.get(1)).isNotBlank();
    }

    @Test
    @DisplayName("The provider names the patterns the starter set declares")
    void patternsAreTheStarterSets() {
        assertThat(KnowledgeBaseComposition.SOURCE_RELEASE_PATTERN.publicId())
                .isEqualTo(IkePattern.SOURCE_RELEASE_PATTERN.publicId());
        assertThat(KnowledgeBaseComposition.COMPOSITION_PATTERN.publicId())
                .isEqualTo(IkePattern.KNOWLEDGE_BASE_COMPOSITION_PATTERN.publicId());
    }

    /** The string field values of each record of a pattern's latest version, from an export. */
    private static List<List<String>> strings(Path file, UUID pattern) throws IOException {
        List<List<String>> found = new java.util.ArrayList<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Manifest manifest = ChangeSetFormat.manifest(zip).orElseThrow();
            for (ChangeSetFormat.RecordEntry entry : ChangeSetFormat.recordEntries(zip, manifest)) {
                if (!entry.entry().getName().contains(pattern.toString())) {
                    continue;
                }
                try (java.io.InputStream in = ChangeSetFormat.openRecords(zip, entry.entry())) {
                    for (TinkarMsg message = TinkarMsg.parseDelimitedFrom(in); message != null;
                         message = TinkarMsg.parseDelimitedFrom(in)) {
                        SemanticChronology semantic = message.getSemanticChronology();
                        found.add(semantic.getSemanticVersions(semantic.getSemanticVersionsCount() - 1)
                                .getFieldsList().stream()
                                .filter(Field::hasStringValue)
                                .map(Field::getStringValue)
                                .toList());
                    }
                }
            }
        }
        return found;
    }

    private static UUID uuid(String fqn) {
        return dev.ikm.tinkar.common.util.uuid.UuidT5Generator.get(KnowledgeBaseComposition.IKE_SET, fqn);
    }

    /** How many records of a pattern an export holds, as its manifest lists them. */
    private static long records(Path file, UUID pattern) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Manifest manifest = ChangeSetFormat.manifest(zip).orElseThrow();
            return ChangeSetFormat.recordEntries(zip, manifest).stream()
                    .filter(entry -> entry.entry().getName().contains(pattern.toString()))
                    .mapToLong(ChangeSetFormat.RecordEntry::count)
                    .sum();
        }
    }

    /** Writes one concept, as a release would, under a stamp of its own. */
    public static final class TestReleaseImporter implements KnowledgeImporter {

        static final String NAME = "test-release";

        /** Creates the importer (ServiceLoader requirement). */
        public TestReleaseImporter() {
        }

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public String description() {
            return "One concept from a test release";
        }

        @Override
        public ImportReport importInto(ImportSource source, StoreWriter writer) {
            Stamp stamp = Stamp.active("2026-10-01T00:00:00Z", KernelConcept.USER,
                    IkeConcept.DEVELOPMENT_MODULE, KernelConcept.DEVELOPMENT_PATH);
            writer.concept(stamp, PublicIds.of(UUID.fromString("5f1c8a52-7f0e-4c1b-9d3a-2b6e8f4a1c07")));
            return ImportReport.of(NAME, RELEASE, writer.counts().tally(), Map.of(), Duration.ZERO);
        }
    }
}
