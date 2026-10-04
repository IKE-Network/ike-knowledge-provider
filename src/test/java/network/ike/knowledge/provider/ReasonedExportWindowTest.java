package network.ike.knowledge.provider;

import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.TinkarTerm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reasoned export spans all time: a loaded file's versions stamped before the epoch
 * survive the export, so the export's manifest counts every semantic the store holds,
 * not only those with a version since the epoch (IKE-Network/ike-issues#1123).
 */
class ReasonedExportWindowTest {

    @Test
    @DisplayName("The export counts every semantic in the store, pre-epoch versions included")
    void exportCountsEverySemantic() throws Exception {
        Path export = Files.createTempFile("reasoned-export-window", ".zip");
        try {
            CachingService.clearAll();
            PrimitiveData.selectControllerByName("Load Ephemeral Store");
            PrimitiveData.start();
            int semanticsInStore;
            try {
                new LoadEntitiesFromProtobufFile(
                        Path.of("target", "data", "tinkar-starter-data-unreasoned-pb.zip").toFile()).compute();
                StatedNavigationDeriver.derive(Coordinates.View.DefaultView());
                assertTrue(preEpochOnlyStatedNavigationSemantics() > 0,
                        "the base file carries stated navigation whose only version is stamped before the epoch");
                semanticsInStore = semanticsInStore();
                ChronologyStoreAssembler.exportReasonedPb(export);
            } finally {
                PrimitiveData.stop();
            }
            assertEquals(semanticsInStore, manifestSemanticCount(export),
                    "the export's manifest counts every semantic the store holds");
        } finally {
            Files.deleteIfExists(export);
        }
    }

    private static int semanticsInStore() {
        int[] count = new int[1];
        EntityService.get().forEachEntity(entity -> {
            if (entity instanceof SemanticEntity<?>) {
                count[0]++;
            }
        });
        return count[0];
    }

    private static int preEpochOnlyStatedNavigationSemantics() {
        int[] count = new int[1];
        EntityService.get().forEachSemanticOfPattern(TinkarTerm.STATED_NAVIGATION_PATTERN.nid(), semantic -> {
            boolean sinceEpoch = false;
            for (SemanticEntityVersion version : semantic.versions()) {
                if (version.stamp().time() >= 0) {
                    sinceEpoch = true;
                }
            }
            if (!sinceEpoch) {
                count[0]++;
            }
        });
        return count[0];
    }

    private static int manifestSemanticCount(Path export) throws IOException {
        try (ZipFile zip = new ZipFile(export.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
            assertNotNull(entry, "the export carries a manifest");
            try (InputStream in = zip.getInputStream(entry)) {
                String count = new Manifest(in).getMainAttributes().getValue("Semantic-Count");
                assertNotNull(count, "the manifest counts semantics");
                return Integer.parseInt(count);
            }
        }
    }
}
