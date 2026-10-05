package network.ike.knowledge.provider;

import network.ike.foundation.ike.bindings.IkeTerms;
import dev.ikm.tinkar.terms.KernelTerm;
import dev.ikm.tinkar.common.id.IntIdSet;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculatorWithCache;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.builder.ActiveStamp;
import dev.ikm.tinkar.entity.builder.KnowledgeSet;
import dev.ikm.tinkar.entity.builder.Stamp;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stated navigation derivation over the base knowledge file plus a small ledger:
 * new concepts get navigation, a re-parented base concept leaves its old parent's
 * children, untouched navigation is left alone, and a second derivation writes nothing
 * (IKE-Network/ike-issues#1123). The base is the unreasoned IKE starter set, which carries
 * no navigation, so a first derivation gives the base its own before the ledger is written.
 */
class StatedNavigationDeriverTest {

    /** The base concept the ledger re-parents: Gretel, a child of Author. */
    private static final EntityProxy.Concept REPARENTED = IkeTerms.GRETEL;
    /** A base concept the ledger never touches, another leaf under Author: KOMET user. */
    private static final EntityProxy.Concept UNTOUCHED = KernelTerm.KOMET_USER;
    /** After the IKE starter set's own authoring time (1767225600777), so the ledger's versions are latest. */
    private static final long AUTHORING_TIME = 1767312000000L;

    private static KnowledgeSet ledger;
    private static StatedNavigationDeriver.Summary first;
    private static int untouchedVersionsBefore;

    @BeforeAll
    static void loadBaseAndLedger() {
        CachingService.clearAll();
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(
                Path.of("target", "data", "ike-starter-set-unreasoned-pb.zip").toFile()).compute();

        StatedNavigationDeriver.derive(Coordinates.View.DefaultView());
        PublicId reparentedAxioms = EntityService.get().semanticsForComponentOfPattern(REPARENTED.nid(),
                KernelTerm.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid()).findFirst().orElseThrow().publicId();

        ActiveStamp stamp = Stamp.active(AUTHORING_TIME, KernelTerm.USER,
                KernelTerm.SOLOR_OVERLAY_MODULE, KernelTerm.DEVELOPMENT_PATH);
        ledger = KnowledgeSet.of("0a5c7d0e-3b7a-5e8f-9c1d-2f4e6a8b0c1d");
        ledger.concept("Alpha (Test)").at(stamp)
                .synonym("Alpha")
                .isA(IkeTerms.MODEL_CONCEPT);
        ledger.concept("Beta (Test)").at(stamp)
                .synonym("Beta")
                .isA(ledger.conceptRef("Alpha (Test)"));
        ledger.concept("Gretel (User)", REPARENTED.publicId()).at(stamp)
                .statedAxioms(reparentedAxioms,
                        leb -> leb.NecessarySet(leb.And(leb.ConceptAxiom(IkeTerms.MODEL_CONCEPT))));
        ledger.write();

        untouchedVersionsBefore = navigationVersions(UNTOUCHED.nid());
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
        assertEquals(Set.of(IkeTerms.MODEL_CONCEPT.nid()), parents(alpha), "Alpha's parents");
        assertEquals(Set.of(beta), children(alpha), "Alpha's children");
        assertEquals(Set.of(alpha), parents(beta), "Beta's parents");
        assertEquals(Set.of(), children(beta), "Beta's children");
        assertTrue(children(IkeTerms.MODEL_CONCEPT.nid()).contains(alpha),
                "Model concept's children gain Alpha");
        assertTrue(first.semanticsCreated() >= 2, "Alpha and Beta were minted navigation");
    }

    @Test
    @DisplayName("A re-parented base concept leaves its old parent's children and joins the new one's")
    void reparentedConceptMoves() {
        int reparented = REPARENTED.nid();
        assertEquals(Set.of(IkeTerms.MODEL_CONCEPT.nid()), parents(reparented), "the new parent");
        assertFalse(children(KernelTerm.USER.nid()).contains(reparented),
                "Author no longer lists it, so a login dialog reading stated navigation no longer offers it");
        assertTrue(children(IkeTerms.MODEL_CONCEPT.nid()).contains(reparented), "Model concept lists it");
    }

    @Test
    @DisplayName("Navigation that already agrees with the stated definition is left alone")
    void untouchedNavigationKeepsItsVersions() {
        assertEquals(1, untouchedVersionsBefore, "KOMET user's stated navigation came with the base's derivation");
        assertEquals(untouchedVersionsBefore, navigationVersions(UNTOUCHED.nid()),
                "KOMET user's stated navigation gained no version");
        assertEquals(Set.of(KernelTerm.USER.nid()), parents(UNTOUCHED.nid()), "and still names Author");
    }

    @Test
    @DisplayName("A second derivation over the same store writes nothing")
    void secondDerivationWritesNothing() {
        int alphaVersions = navigationVersions(ledger.conceptRef("Alpha (Test)").nid());
        int userVersions = navigationVersions(KernelTerm.USER.nid());
        StatedNavigationDeriver.Summary second = StatedNavigationDeriver.derive(Coordinates.View.DefaultView());
        assertEquals(0, second.semanticsUpdated(), "nothing to update");
        assertEquals(0, second.semanticsCreated(), "nothing to create");
        assertEquals(first.conceptsConsidered(), second.conceptsConsidered(), "the same concepts considered");
        assertEquals(alphaVersions, navigationVersions(ledger.conceptRef("Alpha (Test)").nid()));
        assertEquals(userVersions, navigationVersions(KernelTerm.USER.nid()));
    }

    @Test
    @DisplayName("The root's self-reference is not a parent")
    void rootNamesNoParent() {
        assertEquals(Set.of(), parents(KernelTerm.ROOT_VERTEX.nid()), "the root has no parents");
        assertFalse(children(KernelTerm.ROOT_VERTEX.nid()).isEmpty(), "the root has children");
    }

    private static Set<Integer> parents(int conceptNid) {
        return field(conceptNid, 1);
    }

    private static Set<Integer> children(int conceptNid) {
        return field(conceptNid, 0);
    }

    private static Set<Integer> field(int conceptNid, int index) {
        ViewCalculator view = ViewCalculatorWithCache.getCalculator(Coordinates.View.DefaultView());
        List<SemanticEntity<SemanticEntityVersion>> semantics = navigationSemantics(conceptNid);
        assertEquals(1, semantics.size(), "one stated navigation semantic for "
                + view.getFullyQualifiedNameTextOrNid(conceptNid));
        Latest<SemanticEntityVersion> latest = view.latest(semantics.getFirst().nid());
        assertTrue(latest.isPresent(), "a latest navigation version");
        Set<Integer> members = new HashSet<>();
        for (int nid : ((IntIdSet) latest.get().fieldValues().get(index)).toArray()) {
            members.add(nid);
        }
        return members;
    }

    private static int navigationVersions(int conceptNid) {
        int count = 0;
        for (SemanticEntity<SemanticEntityVersion> semantic : navigationSemantics(conceptNid)) {
            count += semantic.versions().size();
        }
        return count;
    }

    private static List<SemanticEntity<SemanticEntityVersion>> navigationSemantics(int conceptNid) {
        return EntityService.get().semanticsForComponentOfPattern(conceptNid,
                KernelTerm.STATED_NAVIGATION_PATTERN.nid()).toList();
    }
}
