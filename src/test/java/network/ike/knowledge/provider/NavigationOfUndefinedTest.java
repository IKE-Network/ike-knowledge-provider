package network.ike.knowledge.provider;

import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.common.id.LongIdSet;
import dev.ikm.tinkar.common.id.LongIds;
import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.ViewCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculatorWithCache;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.SemanticRecord;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkeConcept;
import org.eclipse.collections.api.factory.Lists;
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
 * The navigation of a concept whose definition is retired is emptied, in the stated
 * pattern by the derivation and in the inferred pattern by the sweep that follows
 * classification; a concept that keeps its definition is untouched, and a second sweep
 * writes nothing (IKE-Network/ike-issues#1131).
 */
class NavigationOfUndefinedTest {

    /** The base concept whose definition the test retires: Gretel, a child of Author. */
    private static final EntityProxy.Concept RETIRED = IkeConcept.GRETEL;
    /** A base concept that keeps its definition, another child of Author: KOMET user. */
    private static final EntityProxy.Concept KEPT = KernelConcept.KOMET_USER;

    /** The default view, resolved once the store is open: its coordinates carry nids. */
    private static ViewCoordinateRecord VIEW;

    private static Set<Long> statedParentsBefore;
    private static StatedNavigationDeriver.Summary first;
    private static int inferredEmptied;

    @BeforeAll
    static void loadRetireAndSweep() {
        CachingService.clearAll();
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(
                Path.of("target", "data", "ike-starter-set-unreasoned-pb.zip").toFile()).compute();
        VIEW = Coordinates.View.DefaultView();

        // The unreasoned IKE starter set carries no navigation: a first derivation gives
        // the base its own, as an import would before anything is retired.
        StatedNavigationDeriver.derive(VIEW);
        long retired = RETIRED.nid();
        statedParentsBefore = parents(retired, KernelPattern.STATED_NAVIGATION_PATTERN.nid());
        retireDefinition(EntityService.get().semanticsForComponentOfPattern(retired,
                KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid()).findFirst().orElseThrow().nid());
        first = StatedNavigationDeriver.derive(VIEW);

        // The inferred navigation a reasoned file, or an earlier classification, leaves
        // behind for the concept: the reasoner will not touch it, having nothing to
        // classify.
        long inferredPattern = KernelPattern.INFERRED_NAVIGATION_PATTERN.nid();
        assertEquals(0, navigationSemantics(retired, inferredPattern).size(),
                "the unreasoned base carries no inferred navigation for the concept");
        Transaction transaction = Transaction.make("Stale inferred navigation");
        StampEntity<?> stamp = transaction.getStamp(State.ACTIVE, KernelConcept.USER.nid(),
                KernelConcept.SOLOR_OVERLAY_MODULE.nid(), KernelConcept.DEVELOPMENT_PATH.nid());
        SemanticRecord stale = StatedNavigationDeriver.mint(retired, PrimitiveData.publicId(inferredPattern),
                inferredPattern, stamp.nid(),
                Lists.immutable.of(LongIds.set.empty(), LongIds.set.of(KernelConcept.USER.nid())));
        transaction.addComponent(stale);
        transaction.commit();
        assertEquals(Set.of(KernelConcept.USER.nid()), parents(retired, inferredPattern), "stale parents in place");

        inferredEmptied = StatedNavigationDeriver.emptyNavigationOfUndefined(VIEW, inferredPattern);
    }

    @AfterAll
    static void stop() {
        PrimitiveData.stop();
    }

    @Test
    @DisplayName("A retired definition empties the concept's stated navigation")
    void retiredDefinitionEmptiesStatedNavigation() {
        long retired = RETIRED.nid();
        assertEquals(Set.of(KernelConcept.USER.nid()), statedParentsBefore, "the base's derivation filed it under Author");
        assertEquals(Set.of(), parents(retired, KernelPattern.STATED_NAVIGATION_PATTERN.nid()), "no parents now");
        assertEquals(Set.of(), children(retired, KernelPattern.STATED_NAVIGATION_PATTERN.nid()), "no children");
        assertTrue(first.semanticsEmptied() >= 1, "the derivation reports the emptied semantic");
        assertTrue(first.wroteAnything());
    }

    @Test
    @DisplayName("The former parent no longer lists the concept")
    void formerParentDropsIt() {
        assertFalse(children(KernelConcept.USER.nid(), KernelPattern.STATED_NAVIGATION_PATTERN.nid())
                .contains(RETIRED.nid()), "Author's children drop it");
    }

    @Test
    @DisplayName("The sweep after classification empties the concept's inferred navigation")
    void inferredNavigationEmptiedAfterClassification() {
        assertEquals(1, inferredEmptied, "one stale inferred navigation semantic emptied");
        long retired = RETIRED.nid();
        assertEquals(Set.of(), parents(retired, KernelPattern.INFERRED_NAVIGATION_PATTERN.nid()));
        assertEquals(Set.of(), children(retired, KernelPattern.INFERRED_NAVIGATION_PATTERN.nid()));
    }

    @Test
    @DisplayName("A concept that keeps its definition is untouched")
    void definedConceptUntouched() {
        long kept = KEPT.nid();
        assertEquals(Set.of(KernelConcept.USER.nid()), parents(kept, KernelPattern.STATED_NAVIGATION_PATTERN.nid()));
        assertEquals(1, navigationVersions(kept, KernelPattern.STATED_NAVIGATION_PATTERN.nid()),
                "the one version the base's derivation wrote");
    }

    @Test
    @DisplayName("A second sweep and a second derivation write nothing")
    void secondSweepWritesNothing() {
        assertEquals(0, StatedNavigationDeriver.emptyNavigationOfUndefined(VIEW,
                KernelPattern.INFERRED_NAVIGATION_PATTERN.nid()), "nothing left to empty");
        StatedNavigationDeriver.Summary second = StatedNavigationDeriver.derive(VIEW);
        assertEquals(0, second.semanticsEmptied(), "nothing left to empty in the stated pattern");
        assertEquals(0, second.semanticsUpdated());
        assertEquals(0, second.semanticsCreated());
        assertFalse(second.wroteAnything());
    }

    /** Retires the definition the way a ledger would: an inactive version restating its fields. */
    private static void retireDefinition(long axiomsNid) {
        ViewCalculator view = ViewCalculatorWithCache.getCalculator(VIEW);
        Latest<SemanticEntityVersion> latest = view.latest(axiomsNid);
        assertTrue(latest.isPresent() && latest.get().active(), "an active definition to retire");
        Transaction transaction = Transaction.make("Retire a base definition");
        StampEntity<?> stamp = transaction.getStamp(State.INACTIVE, KernelConcept.USER.nid(),
                KernelConcept.SOLOR_OVERLAY_MODULE.nid(), KernelConcept.DEVELOPMENT_PATH.nid());
        SemanticRecord record = view.updateFields(axiomsNid, latest.get().fieldValues(), stamp.nid());
        transaction.addComponent(record);
        EntityService.get().putEntity(record);
        transaction.commit();
    }

    private static Set<Long> parents(long conceptNid, long patternNid) {
        return field(conceptNid, patternNid, 1);
    }

    private static Set<Long> children(long conceptNid, long patternNid) {
        return field(conceptNid, patternNid, 0);
    }

    private static Set<Long> field(long conceptNid, long patternNid, int index) {
        ViewCalculator view = ViewCalculatorWithCache.getCalculator(VIEW);
        List<SemanticEntity<SemanticEntityVersion>> semantics = navigationSemantics(conceptNid, patternNid);
        assertEquals(1, semantics.size(), "one navigation semantic for "
                + view.getFullyQualifiedNameTextOrNid(conceptNid) + " under " + PrimitiveData.text(patternNid));
        Latest<SemanticEntityVersion> latest = view.latest(semantics.getFirst().nid());
        assertTrue(latest.isPresent(), "a latest navigation version");
        Set<Long> members = new HashSet<>();
        for (long nid : ((LongIdSet) latest.get().fieldValues().get(index)).toArray()) {
            members.add(nid);
        }
        return members;
    }

    private static int navigationVersions(long conceptNid, long patternNid) {
        int count = 0;
        for (SemanticEntity<SemanticEntityVersion> semantic : navigationSemantics(conceptNid, patternNid)) {
            count += semantic.versions().size();
        }
        return count;
    }

    private static List<SemanticEntity<SemanticEntityVersion>> navigationSemantics(long conceptNid, long patternNid) {
        return EntityService.get().semanticsForComponentOfPattern(conceptNid, patternNid).toList();
    }
}
