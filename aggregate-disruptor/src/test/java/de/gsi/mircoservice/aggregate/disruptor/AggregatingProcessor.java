package de.gsi.mircoservice.aggregate.disruptor;

import com.lmax.disruptor.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Dispatches aggregation workers upon seeing new values for a specified event field.
 * Each aggregation worker then assembles all events for this value and optionally publishes back an aggregated events.
 * If the aggregation is not completed within a configurable timeout, a partial AggregationEvent is published.
 *
 * TODO: build api with lambdas
 *      (aggregateType, input Type => (input, aggregate) -> add input to the aggregate if valid, timeout, () -> TimeoutEvent)
 * TODO: handle duplicate events (now they restart the aggregation)
 * TODO: base timeout on real time, event time or ingestion time?
 */
public class AggregatingProcessor implements EventHandler<TestEventSource.IngestedEvent> {
    private static final int N_WORKERS = 4;
    private static final long TIMEOUT = 100;
    public AggregationWorker[] workers;
    private final RingBuffer<TestEventSource.IngestedEvent> rb;

    public AggregatingProcessor(final RingBuffer<TestEventSource.IngestedEvent> ringBuffer) {
        rb = ringBuffer;
        workers = new AggregationWorker[N_WORKERS];
        for (int i = 0; i< N_WORKERS; i++) {
            workers[i] = new AggregationWorker();
        }
    }

    public void onEvent(final TestEventSource.IngestedEvent event, final long nextSequence, final boolean b) {
        if (!(event.payload instanceof TestEventSource.Event)) {
            return;
        }
        final long eventBpcts = ((TestEventSource.Event) event.payload).bpcts;
        final boolean alreadyScheduled = Arrays.stream(workers).filter(w -> w.bpcts == eventBpcts).findFirst().isPresent();
        if (alreadyScheduled) {
            return;
        }
        final Optional<AggregationWorker> freeWorker = Arrays.stream(workers).filter(w -> w.bpcts == -1).findFirst();
        if (freeWorker.isPresent()) {
            System.out.println("dispatching bpcts: "+ eventBpcts);
            freeWorker.get().bpcts = eventBpcts;
            freeWorker.get().aggStart = event.ingestionTime;
            return;
        }
        System.out.println("no free worker for bpcts: " + eventBpcts);
        throw new IllegalStateException("No free workers, todo: implement strategy"); // timeout oldest aggregation?
    }

    public class AggregationWorker implements EventHandler<TestEventSource.IngestedEvent> {
        private long bpcts = -1;
        private long aggStart = -1;
        private List<TestEventSource.IngestedEvent> payloads = new ArrayList<>();

        @Override
        public void onEvent(final TestEventSource.IngestedEvent event, final long sequence, final boolean endOfBatch) throws Exception {
            if (bpcts != -1 && event.ingestionTime > aggStart + TIMEOUT * 1000000) {
                rb.publishEvent(((event1, sequence1, arg0) -> {
                    event1.ingestionTime = System.nanoTime();
                    event1.payload = "aggregation timed out for bpcts: " + bpcts + " -> " + payloads;
                }), payloads);
                bpcts = -1;
                payloads = new ArrayList<>();
                return;
            }
            if (bpcts == -1 || event.payload instanceof TestEventSource.Event && ((TestEventSource.Event) event.payload).bpcts != bpcts) {
                return; // skip irrelevant events
            }
            this.payloads.add(event);
            if (payloads.size() == 3) {
                rb.publishEvent(((event1, sequence1, arg0) -> {
                    event1.ingestionTime = System.nanoTime();
                    event1.payload = payloads;
                }), payloads);
                bpcts = -1;
                payloads = new ArrayList<>();
            }
        }
    }
}
