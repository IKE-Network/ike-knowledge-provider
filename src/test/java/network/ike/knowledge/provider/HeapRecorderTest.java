package network.ike.knowledge.provider;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the assembly's heap measurement reads from the heap after each collection: the reasoner's
 * share from the forced collections only, and the most after any collection as its upper bound,
 * since a young collection leaves the old generation's garbage in place (IKE-Network/ike-issues#1324).
 */
class HeapRecorderTest {

    private static final long GB = 1L << 30;
    private static final Instant T0 = Instant.parse("2026-10-27T00:00:00Z");

    private static Instant at(int second) {
        return T0.plusSeconds(second);
    }

    private static Instant[] window(int from, int to) {
        return new Instant[] {at(from), at(to)};
    }

    private static HeapRecorder.Sample sample(int second, long gigabytes) {
        return new HeapRecorder.Sample(at(second), gigabytes * GB);
    }

    @Test
    void theReasonerHeapComesFromTheForcedCollectionsAndTheYoungOnesBoundIt() {
        List<HeapRecorder.Sample> samples = List.of(
                sample(5, 20),      // a young collection before the store is loaded
                sample(10, 14),     // forced: the store loaded
                sample(20, 70),     // young, carrying old garbage
                sample(30, 60),     // forced: after computing inferences
                sample(40, 77),     // young, carrying old garbage
                sample(50, 55),     // forced: after the normal form
                sample(60, 40));    // forced: after writing the results

        Optional<HeapRecorder.Measured> measured = HeapRecorder.measure(samples, window(9, 11),
                List.of(window(29, 31), window(49, 51), window(59, 61)));

        assertThat(measured).hasValueSatisfying(heap -> {
            assertThat(heap.loadedHeap()).isEqualTo(14 * GB);
            assertThat(heap.reasonerHeap()).as("the most after a forced collection, less the store").contains(46 * GB);
            assertThat(heap.reasonerHeapUpperBound()).as("the most after any collection, less the store").contains(63 * GB);
        });
    }

    @Test
    void aCollectionAfterTheLastForcedOneIsNotTheReasoners() {
        List<HeapRecorder.Sample> samples = List.of(sample(10, 14), sample(30, 40), sample(90, 99));

        assertThat(HeapRecorder.measure(samples, window(9, 11), List.<Instant[]>of(window(29, 31))))
                .hasValueSatisfying(heap -> assertThat(heap.reasonerHeapUpperBound()).contains(26 * GB));
    }

    @Test
    void anAssemblyThatSkipsTheReasonerMeasuresOnlyTheLoadedStore() {
        List<HeapRecorder.Sample> samples = List.of(sample(10, 14), sample(30, 40));

        assertThat(HeapRecorder.measure(samples, window(9, 11), List.of())).hasValueSatisfying(heap -> {
            assertThat(heap.loadedHeap()).isEqualTo(14 * GB);
            assertThat(heap.reasonerHeap()).isEmpty();
            assertThat(heap.reasonerHeapUpperBound()).isEmpty();
        });
    }

    @Test
    void aJvmThatIgnoresSystemGcMeasuresNothing() {
        List<HeapRecorder.Sample> samples = List.of(sample(20, 70), sample(40, 77));

        assertThat(HeapRecorder.measure(samples, window(9, 11), List.<Instant[]>of(window(29, 31)))).isEmpty();
    }

    @Test
    void theUpperBoundIsNeverBelowTheForcedReading() {
        // Only the forced collections ran: the bound is the reading itself.
        List<HeapRecorder.Sample> samples = List.of(sample(10, 14), sample(30, 50));

        assertThat(HeapRecorder.measure(samples, window(9, 11), List.<Instant[]>of(window(29, 31)))).hasValueSatisfying(heap -> {
            assertThat(heap.reasonerHeap()).contains(36 * GB);
            assertThat(heap.reasonerHeapUpperBound()).contains(36 * GB);
        });
    }
}
