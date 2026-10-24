package network.ike.knowledge.provider;

import network.ike.knowledge.spi.ArtifactInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store a knowledge base is assembled in (KEC 2026-10-23): the 64-bit Spined Array store
 * when the inputs fit half the heap, Rocks when they do not, and always the one a build names.
 */
class StoreControllerChoiceTest {

    private static final long MB = 1L << 20;

    @Test
    void aKnowledgeBaseThatFitsTheHeapIsAssembledInThe64BitSpinedArrayStore(@TempDir Path dir) throws IOException {
        List<ArtifactInput> inputs = List.of(new ArtifactInput(ArtifactInput.Role.PB, zip(dir.resolve("a-pb.zip"), 4 * MB)));
        assertThat(ChronologyStoreAssembler.storeController(inputs, null, 1024 * MB))
                .isEqualTo(ChronologyStoreAssembler.SPINED_ARRAY_CONTROLLER);
    }

    @Test
    void aKnowledgeBaseTooLargeForHalfTheHeapIsAssembledInRocks(@TempDir Path dir) throws IOException {
        // 4 MB of export is an estimated 8 MB of store: more than half of a 12 MB heap.
        List<ArtifactInput> inputs = List.of(new ArtifactInput(ArtifactInput.Role.PB, zip(dir.resolve("a-pb.zip"), 4 * MB)));
        assertThat(ChronologyStoreAssembler.storeController(inputs, null, 12 * MB))
                .isEqualTo(ChronologyStoreAssembler.ROCKS_CONTROLLER);
    }

    @Test
    void theStoreABuildNamesIsUsedWhateverTheSize(@TempDir Path dir) throws IOException {
        List<ArtifactInput> inputs = List.of(new ArtifactInput(ArtifactInput.Role.PB, zip(dir.resolve("a-pb.zip"), 4 * MB)));
        assertThat(ChronologyStoreAssembler.storeController(inputs, ChronologyStoreAssembler.ROCKS_CONTROLLER, 1024 * MB))
                .isEqualTo(ChronologyStoreAssembler.ROCKS_CONTROLLER);
        assertThat(ChronologyStoreAssembler.storeController(inputs, " ", 1024 * MB))
                .as("a blank name names nothing")
                .isEqualTo(ChronologyStoreAssembler.SPINED_ARRAY_CONTROLLER);
    }

    @Test
    void theEstimateCountsProtobufAndChangeSetInputsUncompressedAndLeavesSourcesOut(@TempDir Path dir) throws IOException {
        Path pb = zip(dir.resolve("a-pb.zip"), 3 * MB);
        Path changeSet = zip(dir.resolve("a-changeset.zip"), MB);
        Path source = Files.write(dir.resolve("release.zip"), new byte[(int) (5 * MB)]);
        long estimate = ChronologyStoreAssembler.estimatedStoreBytes(List.of(
                new ArtifactInput(ArtifactInput.Role.PB, pb),
                new ArtifactInput(ArtifactInput.Role.CHANGESET, changeSet),
                ArtifactInput.source("an-importer", source, Map.of())));
        assertThat(estimate).isEqualTo((long) (4 * MB * ChronologyStoreAssembler.STORE_BYTES_PER_PB_BYTE));
        assertThat(Files.size(pb)).as("the zip itself is small: the estimate reads its entries' sizes").isLessThan(MB);
    }

    /** A zip holding one entry of the given uncompressed size, which compresses to almost nothing. */
    private static Path zip(Path file, long uncompressed) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("entities.pb"));
            OutputStream out = zip;
            byte[] block = new byte[(int) MB];
            for (long written = 0; written < uncompressed; written += block.length) {
                out.write(block, 0, (int) Math.min(block.length, uncompressed - written));
            }
            zip.closeEntry();
        }
        return file;
    }
}
