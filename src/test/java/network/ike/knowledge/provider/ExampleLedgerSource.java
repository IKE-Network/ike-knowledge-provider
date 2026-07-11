package network.ike.knowledge.provider;

import dev.ikm.tinkar.entity.builder.ActiveStamp;
import dev.ikm.tinkar.entity.builder.KnowledgeSet;
import dev.ikm.tinkar.entity.builder.KnowledgeSetSource;
import dev.ikm.tinkar.entity.builder.Stamp;
import dev.ikm.tinkar.terms.TinkarTerm;

/**
 * A real, minimal ledger source for the provider's end-to-end tests (the house rule:
 * no mocks — the export path runs against a real composed session in a real store).
 * Two concepts and one membership pattern under one declared stamp.
 */
public final class ExampleLedgerSource implements KnowledgeSetSource {

    /** The test set's identity namespace. */
    static final KnowledgeSet EXAMPLE_SET =
            KnowledgeSet.of("7b6a5c4d-3e2f-5a1b-8c9d-0e1f2a3b4c5d");

    /** Creates the source (ServiceLoader requirement). */
    public ExampleLedgerSource() {
    }

    @Override
    public KnowledgeSet compose() {
        ActiveStamp inception = Stamp.active("2026-07-11T00:00:00Z",
                TinkarTerm.USER, TinkarTerm.DEVELOPMENT_MODULE, TinkarTerm.DEVELOPMENT_PATH);

        EXAMPLE_SET.concept("Example root (Example)").at(inception)
                .synonym("Example root")
                .definition("Root concept of the provider's end-to-end test set.")
                .isA(TinkarTerm.MODEL_CONCEPT);

        EXAMPLE_SET.concept("Example child (Example)").at(inception)
                .synonym("Example child")
                .isA(EXAMPLE_SET.conceptRef("Example root (Example)"));

        EXAMPLE_SET.pattern("Example membership pattern (Example)").at(inception)
                .meaning(TinkarTerm.MODEL_CONCEPT).purpose(TinkarTerm.MEMBERSHIP_SEMANTIC)
                .synonym("Example membership");

        return EXAMPLE_SET;
    }
}
