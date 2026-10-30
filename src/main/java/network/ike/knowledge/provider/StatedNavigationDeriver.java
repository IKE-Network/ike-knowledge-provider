package network.ike.knowledge.provider;

import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.common.id.LongIdSet;
import dev.ikm.tinkar.common.id.LongIds;
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
import dev.ikm.tinkar.entity.SemanticEntity;
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
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.primitive.LongLists;
import org.eclipse.collections.api.factory.primitive.LongObjectMaps;
import org.eclipse.collections.api.factory.primitive.LongSets;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.primitive.ImmutableLongList;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.api.map.primitive.ImmutableLongObjectMap;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.api.set.primitive.ImmutableLongSet;
import org.eclipse.collections.api.set.primitive.MutableLongSet;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * {@link #emptyNavigationOfUndefined(ViewCoordinateRecord, long)} does the same for the
 * inferred pattern once the reasoner has run, since the reasoner writes only the
 * concepts it classified (IKE-Network/ike-issues#1131).
 */
public final class StatedNavigationDeriver {

    private static final Logger LOG = LoggerFactory.getLogger(StatedNavigationDeriver.class);

    /** No semantic: nid 0 names nothing. */
    private static final long NO_SEMANTIC = 0;

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
        long navigationPatternNid = KernelPattern.STATED_NAVIGATION_PATTERN.nid();
        Definitions definitions = Definitions.read(calculator, view.logicCoordinate().statedAxiomsPatternNid());
        FieldOrder order = fieldOrder(calculator, navigationPatternNid);
        PublicId navigationPatternId = PrimitiveData.publicId(navigationPatternNid);

        // Each concept's navigation follows from the two maps alone, so the concepts are read
        // and written in parallel. Only minting is ordered: a concept without navigation gets
        // its semantic's nid in concept order, so a store's nids, and the exports that follow
        // them, are the same from one assembly to the next.
        ImmutableLongList concepts = definitions.concepts();
        ImmutableLongList existing = LongLists.immutable.of(IntStream.range(0, concepts.size()).parallel()
                .mapToLong(index -> navigationSemanticNid(concepts.get(index), navigationPatternNid))
                .toArray());
        mintInConceptOrder(concepts, existing, navigationPatternId);

        Transaction transaction = Transaction.make("Stated navigation derivation");
        StampEntity<?> stamp = derivedContentStamp(transaction, view);
        AtomicInteger updated = new AtomicInteger();
        AtomicInteger created = new AtomicInteger();
        IntStream.range(0, concepts.size()).parallel().forEach(index -> {
            long concept = concepts.get(index);
            LongIdSet children = definitions.children(concept);
            LongIdSet parents = definitions.parents(concept);
            ImmutableList<Object> fields = order.fields(children, parents);
            long semanticNid = existing.get(index);
            if (semanticNid == NO_SEMANTIC) {
                transaction.addComponent(mint(concept, navigationPatternId, navigationPatternNid,
                        stamp.nid(), fields));
                created.incrementAndGet();
            } else if (!agrees(calculator.latest(semanticNid), order, children, parents)) {
                SemanticRecord record = calculator.updateFields(semanticNid, fields, stamp.nid());
                transaction.addComponent(record);
                EntityService.get().putEntity(record);
                updated.incrementAndGet();
            }
        });
        int emptied = emptyUndefined(calculator, definitions, navigationPatternNid, order, stamp, transaction);
        if (updated.get() > 0 || created.get() > 0 || emptied > 0) {
            transaction.commit();
        } else {
            transaction.cancel();
        }
        Summary summary = new Summary(concepts.size(), updated.get(), created.get(), emptied);
        LOG.info("Stated navigation derived for {} concepts: {} semantics updated,"
                        + " {} created, {} emptied for concepts without an active definition",
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
    public static int emptyNavigationOfUndefined(ViewCoordinateRecord view, long navigationPatternNid) {
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
        LOG.info("{}: navigation emptied for {} concepts without an active definition",
                PrimitiveData.text(navigationPatternNid), emptied);
        return emptied;
    }

    /**
     * The parents a stated definition names: every concept reference inside a necessary
     * set or a sufficient set, and, for a role concept, every super role its property set
     * names — the classifier places a role concept under the concepts of its super roles,
     * so the stated view does too. The concept itself is excluded, and so are a property
     * set's transitive and reflexive markers, which are characteristics of the role, not
     * roles above it.
     */
    static MutableLongSet statedParents(long concept, DiTreeEntity definition) {
        MutableLongSet parents = LongSets.mutable.empty();
        EntityVertex root = definition.root();
        for (EntityVertex set : definition.successors(root)) {
            long setMeaning = set.getMeaningNid();
            boolean classSet = setMeaning == KernelConcept.NECESSARY_SET.nid()
                    || setMeaning == KernelConcept.SUFFICIENT_SET.nid();
            boolean propertySet = setMeaning == KernelConcept.PROPERTY_SET.nid()
                    || setMeaning == KernelConcept.DATA_PROPERTY_SET.nid()
                    || setMeaning == KernelConcept.INTERVAL_PROPERTY_SET.nid();
            if (!classSet && !propertySet) {
                continue;
            }
            for (EntityVertex connective : definition.successors(set)) {
                if (connective.getMeaningNid() != KernelConcept.AND.nid()) {
                    continue;
                }
                for (EntityVertex atom : definition.successors(connective)) {
                    if (atom.getMeaningNid() != KernelConcept.CONCEPT_REFERENCE.nid()) {
                        continue;
                    }
                    Object reference = atom.propertyFast(KernelConcept.CONCEPT_REFERENCE);
                    if (reference instanceof ConceptFacade facade && facade.nid() != concept
                            && !(propertySet && roleCharacteristic(facade.nid()))) {
                        parents.add(facade.nid());
                    }
                }
            }
        }
        return parents;
    }

    /** Whether a property set's concept reference marks the role transitive or reflexive. */
    private static boolean roleCharacteristic(long nid) {
        return nid == KernelConcept.TRANSITIVE_PROPERTY.nid() || nid == KernelConcept.REFLEXIVE_PROPERTY.nid();
    }

    /**
     * Writes an empty version onto every navigation semantic of the pattern whose concept
     * is neither defined nor named as a parent and whose latest version still names
     * children or parents.
     */
    private static int emptyUndefined(ViewCalculator calculator, Definitions definitions, long navigationPatternNid,
                                      FieldOrder order, StampEntity<?> stamp, Transaction transaction) {
        MutableLongList undefined = LongLists.mutable.empty();
        calculator.forEachSemanticVersionOfPattern(navigationPatternNid,
                (semanticVersion, patternVersion) -> {
                    if (!semanticVersion.active()
                            || definitions.considers(semanticVersion.referencedComponentNid())) {
                        return;
                    }
                    ImmutableList<Object> fields = semanticVersion.fieldValues();
                    if (((LongIdSet) fields.get(order.childrenIndex())).isEmpty()
                            && ((LongIdSet) fields.get(order.parentsIndex())).isEmpty()) {
                        return;
                    }
                    undefined.add(semanticVersion.nid());
                });
        ImmutableList<Object> empty = order.fields(LongIds.set.empty(), LongIds.set.empty());
        undefined.forEach(semanticNid -> {
            SemanticRecord record = calculator.updateFields(semanticNid, empty, stamp.nid());
            transaction.addComponent(record);
            EntityService.get().putEntity(record);
        });
        return undefined.size();
    }

    private static StampEntity<?> derivedContentStamp(Transaction transaction, ViewCoordinateRecord view) {
        return transaction.getStamp(State.ACTIVE,
                view.logicCoordinate().classifierNid(),
                view.getDefaultModuleNid(),
                view.getDefaultPathNid());
    }

    private static FieldOrder fieldOrder(ViewCalculator calculator, long navigationPatternNid) {
        Latest<PatternEntityVersion> navigationPattern = calculator.latest(navigationPatternNid);
        if (navigationPattern.isAbsent()) {
            throw new IllegalStateException("The navigation pattern " + DiagnosticText.component(navigationPatternNid)
                    + " has no version under the view");
        }
        return FieldOrder.of(navigationPattern.get());
    }

    private static boolean agrees(Latest<SemanticEntityVersion> latest, FieldOrder order,
                                  LongIdSet children, LongIdSet parents) {
        if (latest.isAbsent() || !latest.get().active()) {
            return false;
        }
        ImmutableList<Object> fields = latest.get().fieldValues();
        return sameMembers((LongIdSet) fields.get(order.childrenIndex()), children)
                && sameMembers((LongIdSet) fields.get(order.parentsIndex()), parents);
    }

    private static boolean sameMembers(LongIdSet left, LongIdSet right) {
        return LongSets.immutable.of(left.toArray()).equals(LongSets.immutable.of(right.toArray()));
    }

    /** The nid of the concept's semantic of the navigation pattern, or {@link #NO_SEMANTIC}. */
    private static long navigationSemanticNid(long concept, long navigationPatternNid) {
        return EntityService.get().semanticsForComponentOfPattern(concept, navigationPatternNid).findFirst()
                .map(SemanticEntity::nid).orElse(NO_SEMANTIC);
    }

    /**
     * Mints, in concept order, the nid of the navigation semantic of every concept that has
     * none, so the parallel pass that writes them finds each nid already assigned. Their UUIDs,
     * which take a name-based hash each, are computed in parallel; only the minting is ordered.
     */
    private static void mintInConceptOrder(ImmutableLongList concepts, ImmutableLongList existing,
                                           PublicId navigationPatternId) {
        List<UUID> uuids = IntStream.range(0, concepts.size()).parallel()
                .filter(index -> existing.get(index) == NO_SEMANTIC)
                .mapToObj(index -> UuidT5Generator.singleSemanticUuid(navigationPatternId,
                        PrimitiveData.publicId(concepts.get(index))))
                .toList();
        ScopedValue.where(SCOPED_PATTERN_PUBLICID_FOR_NID, navigationPatternId)
                .run(() -> uuids.forEach(uuid -> PrimitiveData.nid(uuid)));
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
    static SemanticRecord mint(long concept, PublicId patternId, long patternNid, long stampNid,
                               ImmutableList<Object> fields) {
        UUID uuid = UuidT5Generator.singleSemanticUuid(patternId, PrimitiveData.publicId(concept));
        long semanticNid = ScopedValue.where(SCOPED_PATTERN_PUBLICID_FOR_NID, patternId)
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

    /**
     * The stated definitions under a view: the concepts that have an active definition
     * or are named as a parent by one, with the parents each names and the children
     * that name it.
     *
     * <p>The maps are built on one thread and frozen before the derivation reads them from many.
     *
     * @param concepts          every defined or named concept, in nid order
     * @param parentsByConcept  the parents each defined concept names
     * @param childrenByConcept the concepts whose definitions name each parent
     */
    record Definitions(ImmutableLongList concepts, ImmutableLongObjectMap<ImmutableLongSet> parentsByConcept,
                       ImmutableLongObjectMap<ImmutableLongSet> childrenByConcept) {

        /** Reads the active stated definitions of the pattern under the calculator's view. */
        static Definitions read(ViewCalculator calculator, long statedPatternNid) {
            MutableLongObjectMap<MutableLongSet> parentsByConcept = LongObjectMaps.mutable.empty();
            MutableLongObjectMap<MutableLongSet> childrenByConcept = LongObjectMaps.mutable.empty();
            calculator.forEachSemanticVersionOfPattern(statedPatternNid,
                    (semanticVersion, patternVersion) -> {
                        if (!semanticVersion.active()) {
                            return;
                        }
                        long concept = semanticVersion.referencedComponentNid();
                        MutableLongSet parents = statedParents(concept,
                                (DiTreeEntity) semanticVersion.fieldValues().get(0));
                        parentsByConcept.getIfAbsentPut(concept, LongSets.mutable::empty).addAll(parents);
                        parents.forEach(parent ->
                                childrenByConcept.getIfAbsentPut(parent, LongSets.mutable::empty).add(concept));
                    });
            MutableLongSet concepts = LongSets.mutable.withAll(parentsByConcept.keySet());
            concepts.addAll(childrenByConcept.keySet());
            return new Definitions(LongLists.immutable.of(concepts.toSortedArray()),
                    frozen(parentsByConcept), frozen(childrenByConcept));
        }

        private static ImmutableLongObjectMap<ImmutableLongSet> frozen(MutableLongObjectMap<MutableLongSet> map) {
            MutableLongObjectMap<ImmutableLongSet> frozen = LongObjectMaps.mutable.ofInitialCapacity(map.size());
            map.forEachKeyValue((key, set) -> frozen.put(key, set.toImmutable()));
            return frozen.toImmutable();
        }

        /** Whether the concept has an active definition or is named as a parent by one. */
        boolean considers(long concept) {
            return parentsByConcept.containsKey(concept) || childrenByConcept.containsKey(concept);
        }

        LongIdSet parents(long concept) {
            return idSet(parentsByConcept.get(concept));
        }

        LongIdSet children(long concept) {
            return idSet(childrenByConcept.get(concept));
        }

        private static LongIdSet idSet(ImmutableLongSet nids) {
            return nids == null ? LongIds.set.empty() : LongIds.set.of(nids.toSortedArray());
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
                long meaning = pattern.fieldDefinitions().get(index).meaningNid();
                if (meaning == KernelConcept.RELATIONSHIP_DESTINATION.nid()) {
                    children = index;
                } else if (meaning == KernelConcept.RELATIONSHIP_ORIGIN.nid()) {
                    parents = index;
                }
            }
            if (children == parents) {
                throw new IllegalStateException("The navigation pattern's fields do not tell children from parents");
            }
            return new FieldOrder(children, parents);
        }

        ImmutableList<Object> fields(LongIdSet children, LongIdSet parents) {
            Object[] values = new Object[2];
            values[childrenIndex] = children;
            values[parentsIndex] = parents;
            return Lists.immutable.of(values);
        }
    }
}
