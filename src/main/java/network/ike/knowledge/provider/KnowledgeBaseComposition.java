package network.ike.knowledge.provider;

import dev.ikm.tinkar.common.id.LongIdList;
import dev.ikm.tinkar.common.id.LongIdSet;
import dev.ikm.tinkar.common.id.LongIds;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.builder.Stamp;
import dev.ikm.tinkar.importer.ImportReport;
import dev.ikm.tinkar.importer.KnowledgeImporter;
import dev.ikm.tinkar.importer.StoreWriter;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.knowledge.spi.ArtifactInput;
import org.eclipse.collections.api.factory.Lists;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Records what a knowledge base is made of, inside the knowledge base (IKE-Network/ike-issues#1304).
 *
 * <p>Each source import writes a <em>source release</em> semantic: the release, the artifact it
 * came from and its checksum, the importer, the modules it wrote, and the source releases it was
 * layered on. It is written under a stamp of the source's own, so it travels inside the source's
 * delta export into every assembly that loads it. One semantic per importer: a later release is a
 * new version of the same semantic, and the stamp history says which release was loaded.
 *
 * <p>An assembly then writes one <em>composition</em> semantic: its ordered inputs with their
 * checksums, the source releases the store holds in the order they arrived, and the classifier.
 * Each assembly is a new version of it.
 *
 * <p>The two patterns belong to the IKE starter set, which declares them by these fully qualified
 * names, so their identities are the starter set's derivations. A store whose starter set predates
 * them gets no records, and the assembly says so in its load summaries.
 */
final class KnowledgeBaseComposition {

    /** The IKE starter set's identity namespace: {@code network.ike.foundation.ike.terms.Ike.SET}. */
    static final UUID IKE_SET = UUID.fromString("d890e06f-ec35-429a-b541-d0ead19695e2");

    /** The source release pattern's fully qualified name in the starter set. */
    static final String SOURCE_RELEASE_PATTERN_FQN = "Source release pattern (IkeFoundation)";

    /** The composition pattern's fully qualified name in the starter set. */
    static final String COMPOSITION_PATTERN_FQN = "Knowledge base composition pattern (IkeFoundation)";

    /**
     * Fields: release name, release date, artifact, origin checksum, importer, modules written,
     * base (the source releases already in the store).
     */
    static final EntityProxy.Pattern SOURCE_RELEASE_PATTERN = EntityProxy.Pattern.make("Source release pattern",
            PublicIds.of(UuidT5Generator.get(IKE_SET, SOURCE_RELEASE_PATTERN_FQN)));

    /** Fields: inputs, source releases, classifier, assembled. */
    static final EntityProxy.Pattern COMPOSITION_PATTERN = EntityProxy.Pattern.make("Knowledge base composition pattern",
            PublicIds.of(UuidT5Generator.get(IKE_SET, COMPOSITION_PATTERN_FQN)));

    /** The note a load summary carries when the store's starter set has no source release pattern. */
    static final String NOT_RECORDED = "not recorded: the starter set has no source release pattern";

    private KnowledgeBaseComposition() {
    }

    /**
     * Whether the store declares a pattern.
     *
     * @param pattern the pattern
     * @return true when the store holds it
     */
    static boolean declared(EntityProxy.Pattern pattern) {
        return PrimitiveData.get().hasPublicId(pattern.publicId())
                && EntityHandle.get(PrimitiveData.nid(pattern.publicId())).entity().isPresent();
    }

    /**
     * The identity of an importer's source release semantic.
     *
     * @param importerName the importer's name
     * @return its public id
     */
    static UUID sourceReleaseUuid(String importerName) {
        return UuidT5Generator.get(IKE_SET, "Source release: " + importerName);
    }

    /** The identity of the composition semantic. */
    static UUID compositionUuid() {
        return UuidT5Generator.get(IKE_SET, "Knowledge base composition");
    }

    /**
     * Writes a source's release semantic under a stamp of its own: the author, module and path of
     * the stamps the import created most often, at the latest time among them, so a rebuild of the
     * same release writes the same stamp and nothing new.
     *
     * @param input    the source input
     * @param report   what the import reported
     * @param importer the importer that ran
     * @param created  the stamps the import created, by nid
     * @param base     the source release semantics the store held before the import, by nid
     * @return the stamp the semantic was written under, by nid; empty when the import created no
     *         stamps, or the store has no source release pattern
     */
    static Optional<Long> writeSourceRelease(ArtifactInput input, ImportReport report, KnowledgeImporter importer,
                                             Set<Long> created, LongIdSet base) {
        if (created.isEmpty() || !declared(SOURCE_RELEASE_PATTERN)) {
            return Optional.empty();
        }
        List<StampEntity<?>> stamps = created.stream()
                .<StampEntity<?>>map(nid -> EntityService.get().getStampFast(nid))
                .toList();
        long time = stamps.stream().mapToLong(StampEntity::time).filter(t -> t != Long.MAX_VALUE).max()
                .orElseThrow();
        long author = mostFrequent(stamps, StampEntity::authorNid);
        long module = mostFrequent(stamps, StampEntity::moduleNid);
        long path = mostFrequent(stamps, StampEntity::pathNid);
        LongIdSet modules = LongIds.set.of(stamps.stream().mapToLong(StampEntity::moduleNid).distinct().toArray());

        Stamp stamp = Stamp.active(time, EntityProxy.Concept.make(author), EntityProxy.Concept.make(module),
                EntityProxy.Concept.make(path));
        StoreWriter writer = new StoreWriter(Calculators.Stamp.DevelopmentLatest());
        writer.semantic(stamp, PublicIds.of(sourceReleaseUuid(report.importer())), SOURCE_RELEASE_PATTERN, module,
                Lists.immutable.of(report.sourceVersion(), Instant.ofEpochMilli(time), artifact(input),
                        checksum(input.path()), importerVersion(importer), modules, base));
        return Optional.of(writer.stamp(stamp));
    }

    /**
     * Writes the composition semantic: one new version per assembly.
     *
     * @param inputs         the assembly's inputs, in load order
     * @param sourceReleases the source release semantics, in the order they arrived, by nid
     * @param classifier     the classifier that ran, if any
     * @return true when written; false when the store has no composition pattern
     */
    static boolean writeComposition(List<ArtifactInput> inputs, LongIdList sourceReleases,
                                    Optional<String> classifier) {
        if (!declared(COMPOSITION_PATTERN)) {
            return false;
        }
        long patternNid = PrimitiveData.nid(COMPOSITION_PATTERN.publicId());
        // The module the starter set declared the pattern in: the assembly has none of its own.
        long module = ((dev.ikm.tinkar.entity.EntityVersion) EntityHandle.get(patternNid).entity().orElseThrow()
                .versions().getFirst()).stamp().moduleNid();
        Instant assembled = Instant.now();
        Stamp stamp = Stamp.active(assembled.toEpochMilli(), KernelConcept.USER, EntityProxy.Concept.make(module),
                KernelConcept.DEVELOPMENT_PATH);
        String described = inputs.stream()
                .map(input -> input.role() + " " + artifact(input) + " " + checksum(input.path()))
                .collect(Collectors.joining("\n"));
        new StoreWriter(Calculators.Stamp.DevelopmentLatest()).recordSemantic(stamp,
                PublicIds.of(compositionUuid()), COMPOSITION_PATTERN, patternNid,
                Lists.immutable.of(described, sourceReleases, classifier.orElse(""), assembled));
        return true;
    }

    /**
     * The source release semantics in the store, by nid.
     *
     * @return their nids; empty when the store has no source release pattern
     */
    static Set<Long> sourceReleases() {
        if (!declared(SOURCE_RELEASE_PATTERN)) {
            return Set.of();
        }
        Set<Long> nids = java.util.concurrent.ConcurrentHashMap.newKeySet();
        EntityService.get().forEachSemanticOfPattern(PrimitiveData.nid(SOURCE_RELEASE_PATTERN.publicId()),
                semantic -> nids.add(semantic.nid()));
        return new LinkedHashSet<>(nids);
    }

    /** The Maven coordinate the input was resolved from, or its file name. */
    static String artifact(ArtifactInput input) {
        return input.coordinate().orElseGet(() -> String.valueOf(input.path().getFileName()));
    }

    /**
     * The SHA-256 of a file, or of a directory's files in path order (each relative path, then its
     * content), as {@code sha256:<hex>}.
     *
     * @param path the file or directory
     * @return its checksum
     */
    static String checksum(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (Files.isDirectory(path)) {
                List<Path> files;
                try (Stream<Path> walk = Files.walk(path)) {
                    files = walk.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList();
                }
                for (Path file : files) {
                    digest.update(path.relativize(file).toString().replace('\\', '/').getBytes(
                            java.nio.charset.StandardCharsets.UTF_8));
                    digestFile(digest, file);
                }
            } else {
                digestFile(digest, path);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot checksum " + path, e);
        }
    }

    private static void digestFile(MessageDigest digest, Path file) throws IOException {
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
    }

    /**
     * The importer's name and version: its jar's implementation version, else the jar's file name,
     * else its name alone.
     */
    static String importerVersion(KnowledgeImporter importer) {
        String version = importer.getClass().getPackage().getImplementationVersion();
        if (version != null) {
            return importer.name() + " " + version;
        }
        try {
            Path location = Path.of(importer.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            return importer.name() + " (" + location.getFileName() + ")";
        } catch (Exception e) {
            return importer.name();
        }
    }

    private static long mostFrequent(List<StampEntity<?>> stamps, java.util.function.ToLongFunction<StampEntity<?>> of) {
        Map<Long, Long> counts = stamps.stream()
                .collect(Collectors.groupingBy(stamp -> of.applyAsLong(stamp), Collectors.counting()));
        // The most frequent; on a tie, the lowest nid, so the choice does not depend on map order.
        long chosen = 0;
        long most = -1;
        for (Map.Entry<Long, Long> entry : counts.entrySet()) {
            if (entry.getValue() > most || (entry.getValue() == most && entry.getKey() < chosen)) {
                chosen = entry.getKey();
                most = entry.getValue();
            }
        }
        return chosen;
    }

    /**
     * The nids a list holds, in order, followed by those of a set it does not, in their order.
     *
     * @param arrived the nids so far, in arrival order
     * @param now     the nids the store holds now
     * @return the extended arrival order
     */
    static List<Long> arrivals(List<Long> arrived, Set<Long> now) {
        List<Long> order = new ArrayList<>(arrived);
        now.stream().filter(nid -> !arrived.contains(nid)).sorted().forEach(order::add);
        return order;
    }
}
