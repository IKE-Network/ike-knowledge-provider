package network.ike.knowledge.provider;

import network.ike.knowledge.spi.Finding;
import network.ike.knowledge.spi.VerifyReport;
import network.ike.knowledge.spi.VerifyRequest;
import network.ike.knowledge.spi.ViewSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The round trip a knowledge base's build runs on its export: the artifact loads into a pristine
 * store and every entity survives the protobuf transform. The IKE starter set is standalone, so
 * it passes both checks with no finding but the load's own report.
 */
class ChronologyStoreVerifierTest {

    @Test
    @DisplayName("A standalone artifact passes PRESENCE and FIT_REFERENCES with one INFO finding")
    void standaloneArtifactPassesTheRoundTrip() {
        VerifyReport report = new ChronologyStoreVerifier().verify(new VerifyRequest(
                Path.of("target", "data", "ike-starter-set-unreasoned-pb.zip"), List.of(),
                Set.of(VerifyRequest.Check.PRESENCE, VerifyRequest.Check.FIT_REFERENCES),
                Optional.empty(), ViewSpec.empty()));

        List<Finding> findings = report.findings();
        assertEquals(1, findings.size(), () -> "findings: " + findings);
        Finding presence = findings.getFirst();
        assertEquals(Finding.Severity.INFO, presence.severity());
        assertEquals(VerifyRequest.Check.PRESENCE, presence.check());
        assertTrue(presence.message().contains("manifest counts exact"), presence.message());
    }
}
