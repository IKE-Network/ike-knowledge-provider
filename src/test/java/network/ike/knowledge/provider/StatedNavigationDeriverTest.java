package network.ike.knowledge.provider;

import dev.ikm.tinkar.common.id.IntIdSet;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculatorWithCache;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.builder.ActiveStamp;
import dev.ikm.tinkar.entity.builder.KnowledgeSet;
import dev.ikm.tinkar.entity.builder.Stamp;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.TinkarTerm;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stated navigation derivation over the base knowledge file plus a small ledger:
 * new concepts get navigation, a re-parented base concept leaves its old parent's
 * children, untouched navigation is left alone, and a second derivation writes nothing
 * (IKE-Network/ike-issues#1123).
 */
class StatedNavigationDeriverTest {

    /** The base concept the ledger re-parents: Order for axiom attachments. */
    private static final UUID REPARENTED = UUID.fromString("abcb0946-20e1-5483-8469-3e8fa0ce20c4");
    /** Its stated axiom semantic in the base. */
    private static final UUID REPARENTED_AXIOMS = UUID.fromString("8dbb8e28-d4c1-5d7f-a003-d99a395d29ed");
    /** A base concept the ledger never touches, a leaf under Author with navigation in the base: KOMET user. */
    private static final UUID UNTOUCHED = UUID.fromString("61c1a544-2acf-58cd-8cc0-9ac581d4227e");
    private static final long AUTHORING_TIME = 1767225600000L;

    private static KnowledgeSet ledger;
    private static StatedNavigationDeriver.Summary first;
    private static int untouchedVersionsBefore;

    @BeforeAll
    static void loadBaseAndLedger() {
        CachingService.clearAll();
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(
                Path.of("target", "data", "tinkar-starter-data-unreasoned-pb.zip").toFile()).compute();

        ActiveStamp stamp = Stamp.active(AUTHORING_TIME, TinkarTerm.USER,
                TinkarTerm.SOLOR_OVERLAY_MODULE, TinkarTerm.DEVELOPMENT_PATH);
        ledger = KnowledgeSet.of("0a5c7d0e-3b7a-5e8f-9c1d-2f4e6a8b0c1d");
        ledger.concept("Alpha (Test)").at(stamp)
                .synonym("Alpha")
                .isA(TinkarTerm.MODEL_CONCEPT);
        ledger.concept("Beta (Test)").at(stamp)
                .synonym("Beta")
                .isA(ledger.conceptRef("Alpha (Test)"));
        ledger.concept("Order for axiom attachments (SOLOR)", PublicIds.of(REPARENTED)).at(stamp)
                .statedAxioms(PublicIds.of(REPARENTED_AXIOMS),
                        leb -> leb.NecessarySet(leb.And(leb.ConceptAxiom(TinkarTerm.MODEL_CONCEPT))));
        ledger.write();

        untouchedVersionsBefore = navigationVersions(PrimitiveData.nid(UNTOUCHED));
        first = StatedNavigationDeriver.derive(Coordinates.View.DefaultView());
    }

    @AfterAll
    static void stop() {
        PrimitiveData.stop();
    }

    @Test
    @DisplayName("A new concept's parents and children follow its stated definition")
    void newConceptsGetNavigation() {
        int alpha = ledger.conceptRef("Alpha (Test)").nid();
        int beta = ledger.conceptRef("Beta (Test)").nid();
        assertEquals(Set.of(TinkarTerm.MODEL_CONCEPT.nid()), parents(alpha), "Alpha's parents");
        assertEquals(Set.of(beta), children(alpha), "Alpha's children");
        assertEquals(Set.of(alpha), parents(beta), "Beta's parents");
        assertEquals(Set.of(), children(beta), "Beta's children");
        assertTrue(children(TinkarTerm.MODEL_CONCEPT.nid()).contains(alpha),
                "Model concept's children gain Alpha");
        assertTrue(first.semanticsCreated() >= 2, "Alpha and Beta were minted navigation");
    }

    @Test
    @DisplayName("A re-parented base concept leaves its old parent's children and joins the new one's")
    void reparentedConceptMoves() {
        int reparented = PrimitiveData.nid(REPARENTED);
        assertEquals(Set.of(TinkarTerm.MODEL_CONCEPT.nid()), parents(reparented), "the new parent");
        assertFalse(children(TinkarTerm.USER.nid()).contains(reparented),
                "Author no longer lists it, so a login dialog reading stated navigation no longer offers it");
        assertTrue(children(TinkarTerm.MODEL_CONCEPT.nid()).contains(reparented), "Model concept lists it");
    }

    @Test
    @DisplayName("Navigation that already agrees with the stated definition is left alone")
    void untouchedNavigationKeepsItsVersions() {
        assertEquals(1, untouchedVersionsBefore, "KOMET user's stated navigation came with the base file");
        assertEquals(untouchedVersionsBefore, navigationVersions(PrimitiveData.nid(UNTOUCHED)),
                "KOMET user's stated navigation gained no version");
        assertEquals(Set.of(TinkarTerm.USER.nid()), parents(PrimitiveData.nid(UNTOUCHED)), "and still names Author");
    }

    @Test
    @DisplayName("A second derivation over the same store writes nothing")
    void secondDerivationWritesNothing() {
        int alphaVersions = navigationVersions(ledger.conceptRef("Alpha (Test)").nid());
        int userVersions = navigationVersions(TinkarTerm.USER.nid());
        StatedNavigationDeriver.Summary second = StatedNavigationDeriver.derive(Coordinates.View.DefaultView());
        assertEquals(0, second.semanticsUpdated(), "nothing to update");
        assertEquals(0, second.semanticsCreated(), "nothing to create");
        assertEquals(first.conceptsConsidered(), second.conceptsConsidered(), "the same concepts considered");
        assertEquals(alphaVersions, navigationVersions(ledger.conceptRef("Alpha (Test)").nid()));
        assertEquals(userVersions, navigationVersions(TinkarTerm.USER.nid()));
    }

    @Test
    @DisplayName("The root's self-reference is not a parent")
    void rootNamesNoParent() {
        assertEquals(Set.of(), parents(TinkarTerm.ROOT_VERTEX.nid()), "the root has no parents");
        assertFalse(children(TinkarTerm.ROOT_VERTEX.nid()).isEmpty(), "the root has children");
    }

    private static Set<Integer> parents(int conceptNid) {
        return field(conceptNid, 1);
    }

    private static Set<Integer> children(int conceptNid) {
        return field(conceptNid, 0);
    }

    private static Set<Integer> field(int conceptNid, int index) {
        ViewCalculator view = ViewCalculatorWithCache.getCalculator(Coordinates.View.DefaultView());
        List<Integer> semantics = navigationSemantics(conceptNid);
        assertEquals(1, semantics.size(), "one stated navigation semantic for "
                + view.getFullyQualifiedNameTextOrNid(conceptNid));
        Latest<SemanticEntityVersion> latest = view.latest(semantics.getFirst());
        assertTrue(latest.isPresent(), "a latest navigation version");
        Set<Integer> members = new HashSet<>();
        for (int nid : ((IntIdSet) latest.get().fieldValues().get(index)).toArray()) {
            members.add(nid);
        }
        return members;
    }

    private static int navigationVersions(int conceptNid) {
        int count = 0;
        for (int semanticNid : navigationSemantics(conceptNid)) {
            count += EntityHandle.get(semanticNid).expectSemantic().versions().size();
        }
        return count;
    }

    private static List<Integer> navigationSemantics(int conceptNid) {
        List<Integer> semantics = new ArrayList<>();
        PrimitiveData.get().forEachSemanticNidForComponentOfPattern(conceptNid,
                TinkarTerm.STATED_NAVIGATION_PATTERN.nid(), semantics::add);
        return semantics;
    }
}
