package network.ike.knowledge.provider;

import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.SearchService;
import dev.ikm.tinkar.common.service.ServiceLifecycleManager;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.KernelPattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text index claims the patterns whose semantics carry text and declines the rest, so a
 * store passes navigation and logical definitions by before the index buffers a delete for them
 * or a load phase counts them against its live-index threshold. The patterns are the IKE starter
 * set's own kernel patterns.
 */
class IndexedPatternsTest {

    private static SearchService search;

    @BeforeAll
    static void loadStarterSet() {
        CachingService.clearAll();
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(
                Path.of("target", "data", "ike-starter-set-unreasoned-pb.zip").toFile()).compute();
        search = ServiceLifecycleManager.get().getRunningService(SearchService.class).orElseThrow();
    }

    @AfterAll
    static void stop() {
        PrimitiveData.stop();
    }

    @Test
    @DisplayName("Descriptions and identifiers carry text, so the index claims their patterns")
    void textPatternsAreIndexed() {
        assertTrue(search.indexesPattern(KernelPattern.DESCRIPTION_PATTERN.nid()));
        assertTrue(search.indexesPattern(KernelPattern.IDENTIFIER_PATTERN.nid()));
    }

    @Test
    @DisplayName("Navigation and logical definitions carry none, so the index declines their patterns")
    void textFreePatternsAreDeclined() {
        assertFalse(search.indexesPattern(KernelPattern.STATED_NAVIGATION_PATTERN.nid()));
        assertFalse(search.indexesPattern(KernelPattern.INFERRED_NAVIGATION_PATTERN.nid()));
        assertFalse(search.indexesPattern(KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid()));
        assertFalse(search.indexesPattern(KernelPattern.EL_PLUS_PLUS_INFERRED_AXIOMS_PATTERN.nid()));
    }

    @Test
    @DisplayName("A written pattern is decided again, to the same answer while its fields are the same")
    void aWrittenPatternIsDecidedAgain() {
        long navigation = KernelPattern.STATED_NAVIGATION_PATTERN.nid();
        long description = KernelPattern.DESCRIPTION_PATTERN.nid();
        search.patternWritten(navigation);
        search.patternWritten(description);
        assertFalse(search.indexesPattern(navigation));
        assertTrue(search.indexesPattern(description));
    }
}
