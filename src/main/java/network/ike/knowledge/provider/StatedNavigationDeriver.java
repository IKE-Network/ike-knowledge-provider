package network.ike.knowledge.provider;

import dev.ikm.tinkar.common.id.IntIdSet;
import dev.ikm.tinkar.common.id.IntIds;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.DiagnosticText;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.ViewCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculatorWithCache;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.RecordListBuilder;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.SemanticRecord;
import dev.ikm.tinkar.entity.SemanticRecordBuilder;
import dev.ikm.tinkar.entity.SemanticVersionRecord;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.State;
import dev.ikm.tinkar.terms.TinkarTerm;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static dev.ikm.tinkar.common.service.PrimitiveData.SCOPED_PATTERN_PUBLICID_FOR_NID;

/**
 * Derives the stated navigation of every concept from its stated definition and writes
 * it into the open store (IKE-Network/ike-issues#1123).
 *
 * <p>A stated navigation semantic records, for one concept, the concepts its stated
 * definition names as parents and the concepts whose stated definitions name it as a
 * parent. A knowledge file carries such semantics as they stood when the file was
 * written; once a ledger re-declares a concept's definition, the file's navigation is
 * stale, and nothing else rewrites it: the reasoner writes inferred navigation only.
 * Browsers that navigate the stated view, such as Komet's login dialog, then show the
 * old parents.
 *
 * <p>The derivation reads, under the given view, the latest stated definition of every
 * concept and keeps those that are active. A parent is a concept named by a concept
 * reference inside a necessary set or a sufficient set; a concept naming itself, the
 * root's own encoding, is not its own parent. Children are the inverse. Every concept
 * that has an active definition or is named as a parent gets its navigation written:
 * a new version on the concept's existing stated navigation semantic when the latest
 * fields differ, a new semantic with the single-semantic identity of the pattern when
 * the concept has none, and nothing when the fields already agree, so a second
 * derivation over the same store writes nothing. Written versions carry the view's
 * derived-content stamp: the logic coordinate's classifier as author, the view's
 * default module and path, the same identity the classifier's inferred results carry.
 *
 * <p>A concept whose definition is retired, or that has none, is neither defined nor
 * named as a parent: its former parents' children lists drop it, but its own
 * navigation semantic would keep parents that list it nowhere. The derivation therefore
 * also empties the stated navigation of every such concept, and
 * {@link #emptyNavigationOfUndefined(ViewCoordinateRecord, int)} does the same for the
 * inferred pattern once the reasoner has run, since the reasoner writes only the
 * concepts it classified (IKE-Network/ike-issues#1131).
 */
public final class StatedNavigationDeriver {

    private static final System.Logger LOG = System.getLogger(StatedNavigationDeriver.class.getName());

    /**
     * What one derivation did.
     *
     * @param conceptsConsidered the concepts with an active stated definition or named as a parent
     * @param semanticsUpdated   existing stated navigation semantics that received a new version
     * @param semanticsCreated   stated navigation semantics minted for concepts that had none
     * @param semanticsEmptied   stated navigation semantics of concepts with no active definition
     *                           that received an empty version
     */
    public record Summary(int conceptsConsidered, int semanticsUpdated, int semanticsCreated,
                          int semanticsEmptied) {

        /** Whether the derivation wrote anything. */
        public boolean wroteAnything() {
            return semanticsUpdated > 0 || semanticsCreated > 0 || semanticsEmptied > 0;
        }
    }

    private StatedNavigationDeriver() {
    }

    /**
     * Derives and writes the stated navigation of every concept in the open store, and
     * empties the stated navigation of every concept that has no active definition and
     * is not named as a parent by one.
     *
     * @param view the view the stated definitions are read under and whose classifier,
     *             default module, and default path stamp the written versions
     * @return what the derivation did
     */
    public static Summary derive(ViewCoordinateRecord view) {
        ViewCalculator calculator = ViewCalculatorWithCache.getCalculator(view);
        int navigationPatternNid = TinkarTerm.STATED_NAVIGATION_PATTERN.nid();
        Definitions definitions = Definitions.read(calculator, view.logicCoordinate().statedAxiomsPatternNid());
        FieldOrder order = fieldOrder(calculator, navigationPatternNid);
        PublicId navigationPatternId = PrimitiveData.publicId(navigationPatternNid);

        Transaction transaction = Transaction.make("Stated navigation derivation");
        StampEntity<?> stamp = derivedContentStamp(transaction, view);
        int updated = 0;
        int created = 0;
        for (int concept : definitions.concepts()) {
            IntIdSet children = definitions.children(concept);
            IntIdSet parents = definitions.parents(concept);
            ImmutableList<Object> fields = order.fields(children, parents);
            List<Integer> existing = new ArrayList<>();
            PrimitiveData.get().forEachSemanticNidForComponentOfPattern(concept, navigationPatternNid,
                    existing::add);
            if (existing.isEmpty()) {
                transaction.addComponent(mint(concept, navigationPatternId, navigationPatternNid,
                        stamp.nid(), fields));
                created++;
            } else {
                int semanticNid = existing.getFirst();
                if (agrees(calculator.latest(semanticNid), order, children, parents)) {
                    continue;
                }
                SemanticRecord record = calculator.updateFields(semanticNid, fields, stamp.nid());
                transaction.addComponent(record);
                EntityService.get().putEntity(record);
                updated++;
            }
        }
        int emptied = emptyUndefined(calculator, definitions, navigationPatternNid, order, stamp, transaction);
        if (updated > 0 || created > 0 || emptied > 0) {
            transaction.commit();
        } else {
            transaction.cancel();
        }
        Summary summary = new Summary(definitions.concepts().size(), updated, created, emptied);
        LOG.log(System.Logger.Level.INFO, "Stated navigation derived for {0} concepts: {1} semantics updated,"
                        + " {2} created, {3} emptied for concepts without an active definition",
                summary.conceptsConsidered(), summary.semanticsUpdated(), summary.semanticsCreated(),
                summary.semanticsEmptied());
        return summary;
    }

    /**
     * Empties, under the given navigation pattern, the navigation of every concept that
     * has no active stated definition and is not named as a parent by one
     * (IKE-Network/ike-issues#1131): each such navigation semantic whose latest version
     * still names children or parents receives a version with both empty. Run over the
     * inferred navigation pattern after classification, because the reasoner writes only
     * the concepts it classified and a retired concept keeps the inferred parents it had.
     *
     * @param view                 the view the stated definitions are read under and whose
     *                             classifier, default module, and default path stamp the
     *                             written versions
     * @param navigationPatternNid the navigation pattern to sweep
     * @return the number of navigation semantics that received an empty version
     */
    public static int emptyNavigationOfUndefined(ViewCoordinateRecord view, int navigationPatternNid) {
        ViewCalculator calculator = ViewCalculatorWithCache.getCalculator(view);
        Definitions definitions = Definitions.read(calculator, view.logicCoordinate().statedAxiomsPatternNid());
        FieldOrder order = fieldOrder(calculator, navigationPatternNid);
        Transaction transaction = Transaction.make("Navigation of undefined concepts emptied");
        StampEntity<?> stamp = derivedContentStamp(transaction, view);
        int emptied = emptyUndefined(calculator, definitions, navigationPatternNid, order, stamp, transaction);
        if (emptied > 0) {
            transaction.commit();
        } else {
            transaction.cancel();
        }
        LOG.log(System.Logger.Level.INFO, "{0}: navigation emptied for {1} concepts without an active definition",
                PrimitiveData.text(navigationPatternNid), emptied);
        return emptied;
    }

    /**
     * The parents a stated definition names: every concept reference inside a necessary
     * set or a sufficient set, the concept itself excluded.
     */
    static Set<Integer> statedParents(int concept, DiTreeEntity definition) {
        Set<Integer> parents = new TreeSet<>();
        EntityVertex root = definition.root();
        for (EntityVertex set : definition.successors(root)) {
            int setMeaning = set.getMeaningNid();
            if (setMeaning != TinkarTerm.NECESSARY_SET.nid() && setMeaning != TinkarTerm.SUFFICIENT_SET.nid()) {
                continue;
            }
            for (EntityVertex connective : definition.successors(set)) {
                if (connective.getMeaningNid() != TinkarTerm.AND.nid()) {
                    continue;
                }
                for (EntityVertex atom : definition.successors(connective)) {
                    if (atom.getMeaningNid() != TinkarTerm.CONCEPT_REFERENCE.nid()) {
                        continue;
                    }
                    Object reference = atom.propertyFast(TinkarTerm.CONCEPT_REFERENCE);
                    if (reference instanceof ConceptFacade facade && facade.nid() != concept) {
                        parents.add(facade.nid());
                    }
                }
            }
        }
        return parents;
    }

    /**
     * Writes an empty version onto every navigation semantic of the pattern whose concept
     * is neither defined nor named as a parent and whose latest version still names
     * children or parents.
     */
    private static int emptyUndefined(ViewCalculator calculator, Definitions definitions, int navigationPatternNid,
                                      FieldOrder order, StampEntity<?> stamp, Transaction transaction) {
        List<Integer> undefined = new ArrayList<>();
        calculator.forEachSemanticVersionOfPattern(navigationPatternNid,
                (semanticVersion, patternVersion) -> {
                    if (!semanticVersion.active()
                            || definitions.concepts().contains(semanticVersion.referencedComponentNid())) {
                        return;
                    }
                    ImmutableList<Object> fields = semanticVersion.fieldValues();
                    if (((IntIdSet) fields.get(order.childrenIndex())).isEmpty()
                            && ((IntIdSet) fields.get(order.parentsIndex())).isEmpty()) {
                        return;
                    }
                    undefined.add(semanticVersion.nid());
                });
        ImmutableList<Object> empty = order.fields(IntIds.set.empty(), IntIds.set.empty());
        for (int semanticNid : undefined) {
            SemanticRecord record = calculator.updateFields(semanticNid, empty, stamp.nid());
            transaction.addComponent(record);
            EntityService.get().putEntity(record);
        }
        return undefined.size();
    }

    private static StampEntity<?> derivedContentStamp(Transaction transaction, ViewCoordinateRecord view) {
        return transaction.getStamp(State.ACTIVE,
                view.logicCoordinate().classifierNid(),
                view.getDefaultModuleNid(),
                view.getDefaultPathNid());
    }

    private static FieldOrder fieldOrder(ViewCalculator calculator, int navigationPatternNid) {
        Latest<PatternEntityVersion> navigationPattern = calculator.latest(navigationPatternNid);
        if (navigationPattern.isAbsent()) {
            throw new IllegalStateException("The navigation pattern " + DiagnosticText.component(navigationPatternNid)
                    + " has no version under the view");
        }
        return FieldOrder.of(navigationPattern.get());
    }

    private static boolean agrees(Latest<SemanticEntityVersion> latest, FieldOrder order,
                                  IntIdSet children, IntIdSet parents) {
        if (latest.isAbsent() || !latest.get().active()) {
            return false;
        }
        ImmutableList<Object> fields = latest.get().fieldValues();
        return sameMembers((IntIdSet) fields.get(order.childrenIndex()), children)
                && sameMembers((IntIdSet) fields.get(order.parentsIndex()), parents);
    }

    private static boolean sameMembers(IntIdSet left, IntIdSet right) {
        Set<Integer> leftMembers = new HashSet<>();
        for (int nid : left.toArray()) {
            leftMembers.add(nid);
        }
        Set<Integer> rightMembers = new HashSet<>();
        for (int nid : right.toArray()) {
            rightMembers.add(nid);
        }
        return leftMembers.equals(rightMembers);
    }

    /**
     * Mints a navigation semantic for a concept under the pattern's single-semantic
     * identity, with one version at the given stamp, and puts it in the store.
     *
     * @param concept    the concept the semantic navigates
     * @param patternId  the navigation pattern's identity
     * @param patternNid the navigation pattern
     * @param stampNid   the stamp of the one version
     * @param fields     the version's fields, in the pattern's field order
     * @return the record put in the store
     */
    static SemanticRecord mint(int concept, PublicId patternId, int patternNid, int stampNid,
                               ImmutableList<Object> fields) {
        UUID uuid = UuidT5Generator.singleSemanticUuid(patternId, PrimitiveData.publicId(concept));
        int semanticNid = ScopedValue.where(SCOPED_PATTERN_PUBLICID_FOR_NID, patternId)
                .call(() -> PrimitiveData.nid(uuid));
        RecordListBuilder<SemanticVersionRecord> versions = RecordListBuilder.make();
        SemanticRecord record = SemanticRecordBuilder.builder()
                .nid(semanticNid)
                .referencedComponentNid(concept)
                .leastSignificantBits(uuid.getLeastSignificantBits())
                .mostSignificantBits(uuid.getMostSignificantBits())
                .patternNid(patternNid)
                .versions(versions)
                .build();
        versions.add(new SemanticVersionRecord(record, stampNid, fields));
        EntityService.get().putEntity(record);
        return record;
    }

    private static int[] toArray(Set<Integer> nids) {
        if (nids == null) {
            return new int[0];
        }
        int[] array = new int[nids.size()];
        int index = 0;
        for (int nid : nids) {
            array[index++] = nid;
        }
        return array;
    }

    /**
     * The stated definitions under a view: the concepts that have an active definition
     * or are named as a parent by one, with the parents each names and the children
     * that name it.
     *
     * @param concepts         every defined or named concept, in nid order
     * @param parentsByConcept the parents each defined concept names
     * @param childrenByConcept the concepts whose definitions name each parent
     */
    record Definitions(Set<Integer> concepts, Map<Integer, Set<Integer>> parentsByConcept,
                       Map<Integer, Set<Integer>> childrenByConcept) {

        /** Reads the active stated definitions of the pattern under the calculator's view. */
        static Definitions read(ViewCalculator calculator, int statedPatternNid) {
            Map<Integer, Set<Integer>> parentsByConcept = new HashMap<>();
            Map<Integer, Set<Integer>> childrenByConcept = new HashMap<>();
            calculator.forEachSemanticVersionOfPattern(statedPatternNid,
                    (semanticVersion, patternVersion) -> {
                        if (!semanticVersion.active()) {
                            return;
                        }
                        int concept = semanticVersion.referencedComponentNid();
                        Set<Integer> parents = statedParents(concept,
                                (DiTreeEntity) semanticVersion.fieldValues().get(0));
                        parentsByConcept.computeIfAbsent(concept, key -> new TreeSet<>()).addAll(parents);
                        for (int parent : parents) {
                            childrenByConcept.computeIfAbsent(parent, key -> new TreeSet<>()).add(concept);
                        }
                    });
            Set<Integer> concepts = new TreeSet<>(parentsByConcept.keySet());
            concepts.addAll(childrenByConcept.keySet());
            return new Definitions(Collections.unmodifiableSet(concepts), parentsByConcept, childrenByConcept);
        }

        IntIdSet parents(int concept) {
            return IntIds.set.of(toArray(parentsByConcept.get(concept)));
        }

        IntIdSet children(int concept) {
            return IntIds.set.of(toArray(childrenByConcept.get(concept)));
        }
    }

    /** Which field of the navigation pattern holds the children and which the parents. */
    record FieldOrder(int childrenIndex, int parentsIndex) {

        /**
         * Reads the order off the pattern's field meanings: relationship destination
         * for children, relationship origin for parents; the pattern's own declared order
         * of the two fields otherwise.
         */
        static FieldOrder of(PatternEntityVersion pattern) {
            int fieldCount = pattern.fieldDefinitions().size();
            if (fieldCount != 2) {
                throw new IllegalStateException("A navigation pattern carries two fields, not " + fieldCount);
            }
            int children = 0;
            int parents = 1;
            for (int index = 0; index < fieldCount; index++) {
                int meaning = pattern.fieldDefinitions().get(index).meaningNid();
                if (meaning == TinkarTerm.RELATIONSHIP_DESTINATION.nid()) {
                    children = index;
                } else if (meaning == TinkarTerm.RELATIONSHIP_ORIGIN.nid()) {
                    parents = index;
                }
            }
            if (children == parents) {
                throw new IllegalStateException("The navigation pattern's fields do not tell children from parents");
            }
            return new FieldOrder(children, parents);
        }

        ImmutableList<Object> fields(IntIdSet children, IntIdSet parents) {
            Object[] values = new Object[2];
            values[childrenIndex] = children;
            values[parentsIndex] = parents;
            return Lists.immutable.of(values);
        }
    }
}
