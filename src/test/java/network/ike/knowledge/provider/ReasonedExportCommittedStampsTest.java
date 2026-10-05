package network.ike.knowledge.provider;

import dev.ikm.tinkar.schema.StampChronology;
import dev.ikm.tinkar.schema.StampVersion;
import dev.ikm.tinkar.schema.TinkarMsg;
import dev.ikm.tinkar.terms.KernelTerm;
import network.ike.knowledge.spi.ArtifactInput;
import network.ike.knowledge.spi.AssembleRequest;
import network.ike.knowledge.spi.ViewSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reasoned export is a release of committed knowledge: no stamp it writes holds a
 * version at {@link Long#MAX_VALUE}. The classifier commits its stamps in a transaction,
 * and a committed stamp keeps the uncommitted version its commit superseded — in the
 * store, where it belongs, but not in the export.
 */
class ReasonedExportCommittedStampsTest {

    @Test
    @DisplayName("A reasoned export holds no stamp version at Long.MAX_VALUE")
    void reasonedExportHoldsNoUncommittedStampVersion() throws Exception {
        Path work = Files.createTempDirectory("reasoned-export-committed");
        try {
            Path storeRoot = work.resolve("store");
            Path export = work.resolve("ike-kb-reasoned-pb.zip");
            new ChronologyStoreAssembler().assemble(new AssembleRequest(storeRoot, true,
                    List.of(new ArtifactInput(ArtifactInput.Role.PB,
                            Path.of("target", "data", "ike-starter-set-unreasoned-pb.zip"))),
                    ViewSpec.empty(), true, Optional.empty(), Optional.empty(), Optional.of(export)));

            List<StampChronology> stamps = stampChronologies(export);
            List<String> classifier = KernelTerm.SNOROCKET_CLASSIFIER.asUuidList().stream()
                    .map(UUID::toString).toList();
            assertTrue(stamps.stream().anyMatch(stamp -> classifier.containsAll(
                            stamp.getFirstStampVersion().getAuthorPublicId().getUuidsList())),
                    "the export carries the classifier's stamps");
            for (StampChronology stamp : stamps) {
                for (StampVersion version : versions(stamp)) {
                    assertNotEquals(Long.MAX_VALUE, version.getTime(),
                            "stamp " + stamp.getPublicId().getUuidsList() + " has an uncommitted version");
                }
            }
        } finally {
            ChronologyStoreAssembler.deleteRecursively(work);
        }
    }

    private static List<StampVersion> versions(StampChronology stamp) {
        List<StampVersion> versions = new ArrayList<>();
        if (stamp.hasFirstStampVersion()) {
            versions.add(stamp.getFirstStampVersion());
        }
        if (stamp.hasSecondStampVersion()) {
            versions.add(stamp.getSecondStampVersion());
        }
        return versions;
    }

    private static List<StampChronology> stampChronologies(Path export) throws IOException {
        List<StampChronology> stamps = new ArrayList<>();
        try (ZipFile zip = new ZipFile(export.toFile())) {
            ZipEntry data = zip.stream()
                    .filter(entry -> !entry.getName().startsWith("META-INF/"))
                    .findFirst()
                    .orElseThrow();
            try (InputStream in = zip.getInputStream(data)) {
                TinkarMsg message = TinkarMsg.parseDelimitedFrom(in);
                while (message != null) {
                    if (message.hasStampChronology()) {
                        stamps.add(message.getStampChronology());
                    }
                    message = TinkarMsg.parseDelimitedFrom(in);
                }
            }
        }
        assertFalse(stamps.isEmpty(), "the export carries stamps");
        return stamps;
    }
}
