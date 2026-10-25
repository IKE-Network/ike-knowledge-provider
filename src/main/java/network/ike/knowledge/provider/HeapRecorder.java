package network.ike.knowledge.provider;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The heap an assembly measures of itself under Flight Recorder, so the store can say up front what
 * a program opening it needs (the IKE Dashboard design, decision 10; IKE-Network/ike-issues#1324). It
 * records only {@code jdk.GCHeapSummary}, and forces a full collection with the store loaded, just
 * before the reasoner runs, and after each of the reasoner's stages while it still holds its model. The
 * live heap after the first is what the store needs open; the most after any collection while the
 * reasoner ran, less that, is what running the reasoner adds.
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
    private Instant[] reasoning;

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
        reasoning = collect();
    }

    private static Instant[] collect() {
        Instant before = Instant.now().minus(SLACK);
        System.gc();
        return new Instant[] {before, Instant.now().plus(SLACK)};
    }

    /**
     * What was measured.
     *
     * @param loadedHeap   the live heap with the store loaded, bytes
     * @param reasonerHeap what running the reasoner added to it, bytes, when the reasoner ran
     */
    record Measured(long loadedHeap, Optional<Long> reasonerHeap) {
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
            List<RecordedEvent> events = RecordingFile.readAllEvents(file).stream()
                    .filter(event -> event.getEventType().getName().equals(HEAP_SUMMARY))
                    .filter(event -> "After GC".equals(event.getString("when")))
                    .toList();
            Optional<Long> loadedHeap = Optional.ofNullable(loaded).flatMap(window -> lastWithin(events, window));
            if (loadedHeap.isEmpty()) {
                return Optional.empty();
            }
            // The peak is the forced collection's, or any collection's between the two marks above it.
            Optional<Long> peak = Optional.ofNullable(reasoning).flatMap(window -> events.stream()
                    .filter(event -> event.getStartTime().isAfter(loaded[1]) && !event.getStartTime().isAfter(window[1]))
                    .map(event -> event.getLong("heapUsed")).max(Long::compare));
            return Optional.of(new Measured(loadedHeap.get(), peak.map(used -> Math.max(0, used - loadedHeap.get()))));
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

    private static Optional<Long> lastWithin(List<RecordedEvent> events, Instant[] window) {
        return events.stream()
                .filter(event -> !event.getStartTime().isBefore(window[0]) && !event.getStartTime().isAfter(window[1]))
                .reduce((first, last) -> last)
                .map(event -> event.getLong("heapUsed"));
    }

    @Override
    public void close() {
        recording.close();
    }
}
