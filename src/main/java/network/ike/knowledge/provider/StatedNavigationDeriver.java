package network.ike.knowledge.provider;

import dev.ikm.tinkar.common.id.IntIdSet;
import dev.ikm.tinkar.common.id.IntIds;
import dev.ikm.tinkar.common.id.PublicId;
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
 */
public final class StatedNavigationDeriver {

    private static final System.Logger LOG = System.getLogger(StatedNavigationDeriver.class.getName());

    /**
     * What one derivation did.
     *
     * @param conceptsConsidered the concepts with an active stated definition or named as a parent
     * @param semanticsUpdated   existing stated navigation semantics that received a new version
     * @param semanticsCreated   stated navigation semantics minted for concepts that had none
     */
    public record Summary(int conceptsConsidered, int semanticsUpdated, int semanticsCreated) {

        /** Whether the derivation wrote anything. */
        public boolean wroteAnything() {
            return semanticsUpdated > 0 || semanticsCreated > 0;
        }
    }

    private StatedNavigationDeriver() {
    }

    /**
     * Derives and writes the stated navigation of every concept in the open store.
     *
     * @param view the view the stated definitions are read under and whose classifier,
     *             default module, and default path stamp the written versions
     * @return what the derivation did
     */
    public static Summary derive(ViewCoordinateRecord view) {
        ViewCalculator calculator = ViewCalculatorWithCache.getCalculator(view);
        int statedPatternNid = view.logicCoordinate().statedAxiomsPatternNid();
        int navigationPatternNid = TinkarTerm.STATED_NAVIGATION_PATTERN.nid();

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

        Latest<PatternEntityVersion> navigationPattern = calculator.latest(navigationPatternNid);
        if (navigationPattern.isAbsent()) {
            throw new IllegalStateException("The stated navigation pattern has no version under the view");
        }
        FieldOrder order = FieldOrder.of(navigationPattern.get());
        PublicId navigationPatternId = PrimitiveData.publicId(navigationPatternNid);

        Transaction transaction = Transaction.make("Stated navigation derivation");
        StampEntity<?> stamp = transaction.getStamp(State.ACTIVE,
                view.logicCoordinate().classifierNid(),
                view.getDefaultModuleNid(),
                view.getDefaultPathNid());
        int updated = 0;
        int created = 0;
        for (int concept : concepts) {
            IntIdSet children = IntIds.set.of(toArray(childrenByConcept.get(concept)));
            IntIdSet parents = IntIds.set.of(toArray(parentsByConcept.get(concept)));
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
        if (updated > 0 || created > 0) {
            transaction.commit();
        } else {
            transaction.cancel();
        }
        Summary summary = new Summary(concepts.size(), updated, created);
        LOG.log(System.Logger.Level.INFO, "Stated navigation derived for {0} concepts: {1} semantics updated, {2} created",
                summary.conceptsConsidered(), summary.semanticsUpdated(), summary.semanticsCreated());
        return summary;
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

    private static SemanticRecord mint(int concept, PublicId patternId, int patternNid, int stampNid,
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
