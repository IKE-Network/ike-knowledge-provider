package network.ike.knowledge.provider;

import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.entity.builder.Stamp;
import dev.ikm.tinkar.entity.changeset.ChangeSetFormat;
import dev.ikm.tinkar.importer.ImportReport;
import dev.ikm.tinkar.importer.ImportSource;
import dev.ikm.tinkar.importer.KnowledgeImporter;
import dev.ikm.tinkar.importer.StoreWriter;
import dev.ikm.tinkar.importer.registry.Registry;
import dev.ikm.tinkar.schema.Field;
import dev.ikm.tinkar.schema.SemanticChronology;
import dev.ikm.tinkar.schema.TinkarMsg;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.knowledge.spi.ArtifactInput;
import network.ike.knowledge.spi.AssembleRequest;
import network.ike.knowledge.spi.AssembleResult;
import network.ike.knowledge.spi.ViewSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An assembly registers what the knowledge base is made of (IKE-Network/ike-issues#1304): each
 * source import a release registration that travels in the source delta, the assembly an assembly
 * registration, and each export an export registration, all by the IKE Registrar on the Registry
 * path.
 */
class RegistryAssemblyTest {

    private static final String COORDINATE = "network.ike.test:test-origin:txt:20261001";

    @Test
    @DisplayName("Releases, the assembly and the delta export are registered; the same release twice is one registration")
    void registersReleasesAssemblyAndExports(@TempDir Path work) throws Exception {
        Path first = Files.writeString(work.resolve("release-1.txt"),
                "Test release 20261001\n2026-10-01\n5f1c8a52-7f0e-4c1b-9d3a-2b6e8f4a1c07\n");
        Path second = Files.writeString(work.resolve("release-2.txt"),
                "Test release 20261101\n2026-11-01\n0b4f2c71-3d8e-4a65-9f12-6c7e8d9a0b13\n");
        Path delta = work.resolve("test-unreasoned-pb.zip");
        Path export = work.resolve("test-reasoned-pb.zip");
        Path starter = Path.of("target", "data", "ike-starter-set-unreasoned-pb.zip");

        AssembleResult result = new ChronologyStoreAssembler().assemble(new AssembleRequest(work.resolve("store"), true,
                List.of(new ArtifactInput(ArtifactInput.Role.PB, starter),
                        ArtifactInput.source(TestReleaseImporter.NAME, first, Map.of())
                                .withCoordinate(COORDINATE, "20261001"),
                        // The same release, read the same way again: nothing new.
                        ArtifactInput.source(TestReleaseImporter.NAME, first, Map.of()),
                        ArtifactInput.source(TestReleaseImporter.NAME, second, Map.of())),
                ViewSpec.empty(), true, Optional.empty(), Optional.empty(), Optional.of(export),
                Optional.empty(), Set.of(), Optional.of(delta)));

        assertThat(result.loads()).as("load summaries").noneMatch(load ->
                load.artifact().contains(ChronologyStoreAssembler.NOT_REGISTERED));

        // What the store's manifest is to say about it: what opens it, its layout, and the
        // assembly it holds, by the registration's identity.
        assertThat(result.storeAttributes()).containsOnlyKeys(ChronologyStoreAssembler.OPEN_CONTROLLER,
                ChronologyStoreAssembler.STORE_LAYOUT, ChronologyStoreAssembler.ENTITY_FORMAT,
                ChronologyStoreAssembler.ASSEMBLY_IDENTITY, ChronologyStoreAssembler.STATED_AXIOM_BYTES,
                ChronologyStoreAssembler.LOADED_HEAP_BYTES, ChronologyStoreAssembler.REASONER_HEAP_BYTES);
        // The starter set defines its concepts with stated axioms, so their size is above zero; the heap
        // with it loaded, measured under Flight Recorder, is too, and the reasoner's is not negative.
        assertThat(Long.parseLong(result.storeAttributes().get(ChronologyStoreAssembler.STATED_AXIOM_BYTES))).isPositive();
        assertThat(Long.parseLong(result.storeAttributes().get(ChronologyStoreAssembler.LOADED_HEAP_BYTES))).isPositive();
        assertThat(Long.parseLong(result.storeAttributes().get(ChronologyStoreAssembler.REASONER_HEAP_BYTES))).isNotNegative();
        assertThat(result.storeAttributes().get(ChronologyStoreAssembler.OPEN_CONTROLLER))
                .isEqualTo(System.getProperty(ChronologyStoreAssembler.STORE_CONTROLLER_PROPERTY, ChronologyStoreAssembler.SPINED_ARRAY_CONTROLLER));
        assertThat(result.storeAttributes().get(ChronologyStoreAssembler.ASSEMBLY_IDENTITY))
                .isEqualTo(Registry.assemblyRegistrationUuid(List.of(
                        new Registry.AssemblyInput("PB", "ike-starter-set-unreasoned-pb.zip", Optional.empty(),
                                Registry.checksum(starter)),
                        new Registry.AssemblyInput("SOURCE", COORDINATE, Optional.of("20261001"), Registry.checksum(first)),
                        new Registry.AssemblyInput("SOURCE", "release-1.txt", Optional.empty(), Registry.checksum(first)),
                        new Registry.AssemblyInput("SOURCE", "release-2.txt", Optional.empty(),
                                Registry.checksum(second)))).toString());
        UUID release = KernelPattern.RELEASE_REGISTRATION_PATTERN.publicId().asUuidArray()[0];
        UUID assembly = KernelPattern.ASSEMBLY_REGISTRATION_PATTERN.publicId().asUuidArray()[0];
        UUID exported = KernelPattern.EXPORT_REGISTRATION_PATTERN.publicId().asUuidArray()[0];

        // Two releases, one registration each, carried by the source delta with nothing else of the
        // registry: the assembly and the export are registered after it is written.
        assertThat(records(delta, release)).isEqualTo(2);
        assertThat(records(delta, assembly)).isZero();
        assertThat(records(delta, exported)).isZero();

        // The knowledge base holds both, its assembly, and the delta export it took.
        assertThat(records(export, release)).isEqualTo(2);
        assertThat(records(export, assembly)).isEqualTo(1);
        assertThat(records(export, exported)).isEqualTo(1);

        // What the first release's registration says: one version, though it was imported twice.
        List<List<String>> firstRelease = versions(delta, release).stream()
                .filter(record -> record.getFirst().contains("Test release 20261001"))
                .findFirst().orElseThrow();
        assertThat(firstRelease).hasSize(1);
        List<String> fields = firstRelease.getFirst();
        assertThat(fields).containsSubsequence("Test release 20261001", COORDINATE, "20261001",
                Registry.checksum(first));
        assertThat(fields).anyMatch(value -> value.startsWith(TestReleaseImporter.NAME));

        // The assembly: every input in load order, with its resolved version and checksum.
        String inputs = versions(export, assembly).getFirst().getFirst().getFirst();
        assertThat(inputs.lines()).containsExactly(
                "PB ike-starter-set-unreasoned-pb.zip " + Registry.checksum(starter),
                "SOURCE " + COORDINATE + " 20261001 " + Registry.checksum(first),
                "SOURCE release-1.txt " + Registry.checksum(first),
                "SOURCE release-2.txt " + Registry.checksum(second));

        // The export registration names the delta and its checksum.
        List<String> exportFields = versions(export, exported).getFirst().getFirst();
        assertThat(exportFields).contains("test-unreasoned-pb.zip", Registry.checksum(delta));
    }

    @Test
    @DisplayName("The kernel's registry bindings are the starter set's")
    void bindingsAreTheStarterSets() {
        assertThat(KernelConcept.REGISTRAR.publicId()).isEqualTo(IkeConcept.IKE_REGISTRAR.publicId());
        assertThat(KernelConcept.REGISTRY_PATH.publicId()).isEqualTo(IkeConcept.IKE_REGISTRY_PATH.publicId());
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

    /** Each record of a pattern in an export: its versions, each as its string field values. */
    private static List<List<List<String>>> versions(Path file, UUID pattern) throws IOException {
        List<List<List<String>>> found = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Manifest manifest = ChangeSetFormat.manifest(zip).orElseThrow();
            for (ChangeSetFormat.RecordEntry entry : ChangeSetFormat.recordEntries(zip, manifest)) {
                if (!entry.entry().getName().contains(pattern.toString())) {
                    continue;
                }
                try (InputStream in = ChangeSetFormat.openRecords(zip, entry.entry())) {
                    for (TinkarMsg message = TinkarMsg.parseDelimitedFrom(in); message != null;
                         message = TinkarMsg.parseDelimitedFrom(in)) {
                        SemanticChronology semantic = message.getSemanticChronology();
                        found.add(semantic.getSemanticVersionsList().stream()
                                .map(version -> version.getFieldsList().stream()
                                        .filter(Field::hasStringValue)
                                        .map(Field::getStringValue)
                                        .toList())
                                .toList());
                    }
                }
            }
        }
        return found;
    }

    /**
     * Reads a test release: its name, its date ({@code yyyy-MM-dd}), and the UUID of the one concept
     * it holds, one per line, and writes that concept stamped at the release date.
     */
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
            List<String> lines;
            try {
                lines = Files.readAllLines(source.path());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            Instant date = java.time.LocalDate.parse(lines.get(1)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            StoreWriter.Tally before = writer.counts().tally();
            writer.concept(Stamp.active(date.toEpochMilli(), KernelConcept.USER, IkeConcept.DEVELOPMENT_MODULE,
                    KernelConcept.DEVELOPMENT_PATH), PublicIds.of(UUID.fromString(lines.get(2))));
            return ImportReport.of(NAME, lines.get(0), writer.counts().tally().minus(before), Map.of(), Duration.ZERO)
                    .withReleaseDate(date);
        }
    }
}
