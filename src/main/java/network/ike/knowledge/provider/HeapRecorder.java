package network.ike.knowledge.provider;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The heap an assembly measures of itself under Flight Recorder, so the store can say up front what
 * a program opening it needs (the IKE Dashboard design, decision 10; IKE-Network/ike-issues#1324). It
 * records only {@code jdk.GCHeapSummary}, and forces a full collection with the store loaded, just
 * before the reasoner runs, and after each of the reasoner's stages while it still holds its model. The
 * live heap after the first is what the store needs open; the most after the forced collections while
 * the reasoner ran, less that, is what running the reasoner adds.
 *
 * <p>The collections the JVM runs on its own while the reasoner works are young ones: they leave the
 * old generation's garbage in place, so how much of it a reading includes depends on {@code -Xmx} and
 * on when the collector happened to run. The most after any of them is kept apart, as an upper bound
 * that also covers a peak inside a stage, which the forced collections between stages cannot see.
 *
 * <p>A JVM that ignores {@code System.gc()} ({@code -XX:+DisableExplicitGC}) records no collection at
 * those points, and the measurement says nothing rather than something wrong.
 */
final class HeapRecorder implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(HeapRecorder.class);
    private static final String HEAP_SUMMARY = "jdk.GCHeapSummary";
    /** Recorder time and wall time can differ slightly: a collection's window is widened by this. */
    private static final java.time.Duration SLACK = java.time.Duration.ofMillis(50);

    private final Recording recording;
    private Instant[] loaded;
    private final List<Instant[]> reasoning = new ArrayList<>();

    private HeapRecorder(Recording recording) {
        this.recording = recording;
    }

    /**
     * Starts recording.
     *
     * @return the recorder, or empty when Flight Recorder is not available in this JVM
     */
    static Optional<HeapRecorder> start() {
        try {
            Recording recording = new Recording();
            recording.setName("ike-assembly-heap");
            recording.enable(HEAP_SUMMARY);
            recording.setToDisk(true);
            recording.start();
            return Optional.of(new HeapRecorder(recording));
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("No heap measurement: Flight Recorder is not available ({})", e.toString());
            return Optional.empty();
        }
    }

    /** Collects, and marks the live heap after it as the store's, loaded: called just before the reasoner runs. */
    void storeLoaded() {
        loaded = collect();
    }

    /** Collects while the reasoner holds its model: called after each of its stages. */
    void reasonerHolding() {
        reasoning.add(collect());
    }

    private static Instant[] collect() {
        Instant before = Instant.now().minus(SLACK);
        System.gc();
        return new Instant[] {before, Instant.now().plus(SLACK)};
    }

    /**
     * What was measured.
     *
     * @param loadedHeap             the live heap with the store loaded, bytes
     * @param reasonerHeap           what running the reasoner added to it, from the forced collections,
     *                               bytes, when the reasoner ran
     * @param reasonerHeapUpperBound what it added at most, from any collection while it ran, bytes, when
     *                               the reasoner ran; never less than {@code reasonerHeap}
     */
    record Measured(long loadedHeap, Optional<Long> reasonerHeap, Optional<Long> reasonerHeapUpperBound) {
    }

    /**
     * The heap in use after one collection.
     *
     * @param at   when the collection started
     * @param used the heap in use after it, bytes
     */
    record Sample(Instant at, long used) {
    }

    /**
     * Stops recording and reads what it measured.
     *
     * @return the measurement, or empty when the store-loaded collection left no record
     */
    Optional<Measured> measured() {
        Path file = null;
        try {
            recording.stop();
            file = Files.createTempFile("ike-assembly-heap-", ".jfr");
            recording.dump(file);
            List<Sample> samples = RecordingFile.readAllEvents(file).stream()
                    .filter(event -> event.getEventType().getName().equals(HEAP_SUMMARY))
                    .filter(event -> "After GC".equals(event.getString("when")))
                    .map(event -> new Sample(event.getStartTime(), event.getLong("heapUsed")))
                    .toList();
            return loaded == null ? Optional.empty() : measure(samples, loaded, reasoning);
        } catch (IOException | RuntimeException e) {
            LOG.warn("No heap measurement: the recording cannot be read ({})", e.toString());
            return Optional.empty();
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    LOG.debug("Cannot delete {}", file, e);
                }
            }
        }
    }

    /**
     * Reads the measurement from the heap after each collection.
     *
     * @param samples   the heap after each collection, in the order they ran
     * @param loaded    the window of the forced collection with the store loaded
     * @param reasoning the windows of the forced collections while the reasoner held its model
     * @return the measurement, or empty when the store-loaded collection left no sample
     */
    static Optional<Measured> measure(List<Sample> samples, Instant[] loaded, List<Instant[]> reasoning) {
        Optional<Long> loadedHeap = lastWithin(samples, loaded);
        if (loadedHeap.isEmpty()) {
            return Optional.empty();
        }
        long base = loadedHeap.get();
        Optional<Long> forced = reasoning.stream()
                .map(window -> lastWithin(samples, window))
                .flatMap(Optional::stream)
                .max(Long::compare);
        Optional<Long> any = reasoning.isEmpty() ? Optional.empty() : samples.stream()
                .filter(sample -> sample.at().isAfter(loaded[1])
                        && !sample.at().isAfter(reasoning.getLast()[1]))
                .map(Sample::used)
                .max(Long::compare);
        Optional<Long> reasonerHeap = forced.map(used -> Math.max(0, used - base));
        Optional<Long> upperBound = any.map(used -> Math.max(0, used - base))
                .map(bound -> Math.max(bound, reasonerHeap.orElse(0L)));
        return Optional.of(new Measured(base, reasonerHeap, upperBound));
    }

    private static Optional<Long> lastWithin(List<Sample> samples, Instant[] window) {
        return samples.stream()
                .filter(sample -> !sample.at().isBefore(window[0]) && !sample.at().isAfter(window[1]))
                .reduce((first, last) -> last)
                .map(Sample::used);
    }

    @Override
    public void close() {
        recording.close();
    }
}
