package network.ike.knowledge.provider;

import dev.ikm.tinkar.terms.KernelTerm;
import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.builder.ActiveStamp;
import dev.ikm.tinkar.entity.builder.KnowledgeSet;
import dev.ikm.tinkar.entity.builder.Stamp;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import network.ike.foundation.ike.bindings.IkeTerms;
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
 * The reasoned export spans all time: versions stamped before the epoch survive the
 * export, so the export's manifest counts every semantic the store holds, not only those
 * with a version since the epoch (IKE-Network/ike-issues#1123). The IKE starter set is
 * stamped after the epoch, so the test authors a small concept before it.
 */
class ReasonedExportWindowTest {

    /** A day before the epoch. */
    private static final long PRE_EPOCH_TIME = -86_400_000L;

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
                        Path.of("target", "data", "ike-starter-set-unreasoned-pb.zip").toFile()).compute();
                ActiveStamp preEpoch = Stamp.active(PRE_EPOCH_TIME, KernelTerm.USER,
                        KernelTerm.SOLOR_OVERLAY_MODULE, KernelTerm.DEVELOPMENT_PATH);
                KnowledgeSet ledger = KnowledgeSet.of("5d3f2b1a-7c9e-5a4b-8d6f-1e2c3b4a5d6e");
                ledger.concept("Before the epoch (Test)").at(preEpoch)
                        .synonym("Before the epoch")
                        .isA(IkeTerms.MODEL_CONCEPT);
                ledger.write();
                StatedNavigationDeriver.derive(Coordinates.View.DefaultView());
                assertTrue(preEpochOnlySemantics() > 0,
                        "the store holds semantics whose only versions are stamped before the epoch");
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

    private static int preEpochOnlySemantics() {
        int[] count = new int[1];
        EntityService.get().forEachSemanticEntity(semantic -> {
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
