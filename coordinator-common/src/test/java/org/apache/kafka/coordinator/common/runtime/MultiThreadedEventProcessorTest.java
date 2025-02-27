/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.coordinator.common.runtime;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.common.utils.Time;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.apache.kafka.test.TestUtils.DEFAULT_MAX_WAIT_MS;
import static org.apache.kafka.test.TestUtils.waitForCondition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Timeout(value = 60)
public class MultiThreadedEventProcessorTest {
    private static class DelayEventAccumulator extends EventAccumulator<TopicPartition, CoordinatorEvent> {
        private final Time time;
        private final long takeDelayMs;

        public DelayEventAccumulator(Time time, long takeDelayMs) {
            this.time = time;
            this.takeDelayMs = takeDelayMs;
        }

        @Override
        public CoordinatorEvent poll(long timeout, TimeUnit unit) {
            CoordinatorEvent event = super.poll(timeout, unit);
            time.sleep(takeDelayMs);
            return event;
        }
    }

    private static class FutureEvent<T> implements CoordinatorEvent {
        private final TopicPartition key;
        private final CompletableFuture<T> future;
        private final Supplier<T> supplier;
        private final boolean block;
        private final CountDownLatch latch;
        private final CountDownLatch executed;
        private final long createdTimeMs;

        FutureEvent(
            TopicPartition key,
            Supplier<T> supplier
        ) {
            this(key, supplier, false, 0L);
        }

        FutureEvent(
            TopicPartition key,
            Supplier<T> supplier,
            boolean block
        ) {
            this(key, supplier, block, 0L);
        }

        FutureEvent(
            TopicPartition key,
            Supplier<T> supplier,
            boolean block,
            long createdTimeMs
        ) {
            this.key = key;
            this.future = new CompletableFuture<>();
            this.supplier = supplier;
            this.block = block;
            this.latch = new CountDownLatch(1);
            this.executed = new CountDownLatch(1);
            this.createdTimeMs = createdTimeMs;
        }

        @Override
        public void run() {
            T result = supplier.get();
            executed.countDown();

            if (block) {
                try {
                    latch.await();
                } catch (InterruptedException ex) {
                    // ignore
                }
            }

            future.complete(result);
        }

        @Override
        public void complete(Throwable ex) {
            future.completeExceptionally(ex);
        }

        @Override
        public long createdTimeMs() {
            return createdTimeMs;
        }

        @Override
        public TopicPartition key() {
            return key;
        }

        public CompletableFuture<T> future() {
            return future;
        }

        public void release() {
            latch.countDown();
        }

        public boolean awaitExecution(long timeout, TimeUnit unit) throws InterruptedException {
            return executed.await(timeout, unit);
        }

        @Override
        public String toString() {
            return "FutureEvent(key=" + key + ")";
        }
    }

    @Test
    public void testCreateAndClose() throws Exception {
        CoordinatorEventProcessor eventProcessor = new MultiThreadedEventProcessor(
            new LogContext(),
            "event-processor-",
            2,
            Time.SYSTEM,
            mock(CoordinatorRuntimeMetrics.class)
        );
        eventProcessor.close();
    }

    @Test
    public void testEventsAreProcessed() throws Exception {
        try (CoordinatorEventProcessor eventProcessor = new MultiThreadedEventProcessor(
            new LogContext(),
            "event-processor-",
            2,
            Time.SYSTEM,
            mock(CoordinatorRuntimeMetrics.class)
        )) {
            AtomicInteger numEventsExecuted = new AtomicInteger(0);

            List<FutureEvent<Integer>> events = Arrays.asList(
                new FutureEvent<>(new TopicPartition("foo", 0), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 1), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 2), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 0), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 1), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 2), numEventsExecuted::incrementAndGet)
            );

            events.forEach(eventProcessor::enqueueLast);

            CompletableFuture.allOf(events
                .stream()
                .map(FutureEvent::future)
                .toArray(CompletableFuture[]::new)
            ).get(10, TimeUnit.SECONDS);

            events.forEach(event -> {
                assertTrue(event.future.isDone());
                assertFalse(event.future.isCompletedExceptionally());
            });

            assertEquals(events.size(), numEventsExecuted.get());
        }
    }

    @Test
    public void testProcessingGuarantees() throws Exception {
        try (CoordinatorEventProcessor eventProcessor = new MultiThreadedEventProcessor(
            new LogContext(),
            "event-processor-",
            2,
            Time.SYSTEM,
            mock(CoordinatorRuntimeMetrics.class)
        )) {
            AtomicInteger numEventsExecuted = new AtomicInteger(0);

            List<FutureEvent<Integer>> events = Arrays.asList(
                new FutureEvent<>(new TopicPartition("foo", 0), numEventsExecuted::incrementAndGet, true), // Event 0
                new FutureEvent<>(new TopicPartition("foo", 1), numEventsExecuted::incrementAndGet, true), // Event 1
                new FutureEvent<>(new TopicPartition("foo", 0), numEventsExecuted::incrementAndGet, true), // Event 2
                new FutureEvent<>(new TopicPartition("foo", 1), numEventsExecuted::incrementAndGet, true), // Event 3
                new FutureEvent<>(new TopicPartition("foo", 0), numEventsExecuted::incrementAndGet, true), // Event 4
                new FutureEvent<>(new TopicPartition("foo", 1), numEventsExecuted::incrementAndGet, true)  // Event 5
            );

            events.forEach(eventProcessor::enqueueLast);

            // Events 0 and 1 are executed.
            assertTrue(events.get(0).awaitExecution(5, TimeUnit.SECONDS));
            assertTrue(events.get(1).awaitExecution(5, TimeUnit.SECONDS));

            // Release event 0.
            events.get(0).release();

            // Event 0 is completed.
            int result = events.get(0).future.get(5, TimeUnit.SECONDS);
            assertTrue(result == 1 || result == 2, "Expected 1 or 2 but was " + result);

            // Event 2 is executed.
            assertTrue(events.get(2).awaitExecution(5, TimeUnit.SECONDS));

            // Release event 2.
            events.get(2).release();

            // Event 2 is completed.
            assertEquals(3, events.get(2).future.get(5, TimeUnit.SECONDS));

            // Event 4 is executed.
            assertTrue(events.get(4).awaitExecution(5, TimeUnit.SECONDS));

            // Release event 1.
            events.get(1).release();

            // Event 1 is completed.
            result = events.get(1).future.get(5, TimeUnit.SECONDS);
            assertTrue(result == 1 || result == 2, "Expected 1 or 2 but was " + result);

            // Event 3 is executed.
            assertTrue(events.get(3).awaitExecution(5, TimeUnit.SECONDS));

            // Release event 4.
            events.get(4).release();

            // Event 4 is completed.
            assertEquals(4, events.get(4).future.get(5, TimeUnit.SECONDS));

            // Release event 3.
            events.get(3).release();

            // Event 3 is completed.
            assertEquals(5, events.get(3).future.get(5, TimeUnit.SECONDS));

            // Event 5 is executed.
            assertTrue(events.get(5).awaitExecution(5, TimeUnit.SECONDS));

            // Release event 5.
            events.get(5).release();

            // Event 5 is completed.
            assertEquals(6, events.get(5).future.get(5, TimeUnit.SECONDS));

            events.forEach(event -> {
                assertTrue(event.future.isDone());
                assertFalse(event.future.isCompletedExceptionally());
            });

            assertEquals(events.size(), numEventsExecuted.get());
        }
    }

    @Test
    public void testEventsAreRejectedWhenClosed() throws Exception {
        CoordinatorEventProcessor eventProcessor = new MultiThreadedEventProcessor(
            new LogContext(),
            "event-processor-",
            2,
            Time.SYSTEM,
            mock(CoordinatorRuntimeMetrics.class)
        );

        eventProcessor.close();

        assertThrows(RejectedExecutionException.class,
            () -> eventProcessor.enqueueLast(new FutureEvent<>(new TopicPartition("foo", 0), () -> 0)));
    }

    @Test
    public void testEventsAreDrainedWhenClosed() throws Exception {
        try (MultiThreadedEventProcessor eventProcessor = new MultiThreadedEventProcessor(
            new LogContext(),
            "event-processor-",
            1, // Use a single thread to block event in the processor.
            Time.SYSTEM,
            mock(CoordinatorRuntimeMetrics.class)
        )) {
            AtomicInteger numEventsExecuted = new AtomicInteger(0);

            // Special event which blocks until the latch is released.
            FutureEvent<Integer> blockingEvent = new FutureEvent<>(
                new TopicPartition("foo", 0),
                numEventsExecuted::incrementAndGet,
                true
            );

            List<FutureEvent<Integer>> events = Arrays.asList(
                new FutureEvent<>(new TopicPartition("foo", 0), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 0), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 0), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 0), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 0), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 0), numEventsExecuted::incrementAndGet)
            );

            // Enqueue the blocking event.
            eventProcessor.enqueueLast(blockingEvent);

            // Ensure that the blocking event is executed.
            waitForCondition(() -> numEventsExecuted.get() > 0,
                "Blocking event not executed.");

            // Enqueue the other events.
            events.forEach(eventProcessor::enqueueLast);

            // Events should not be completed.
            events.forEach(event -> assertFalse(event.future.isDone()));

            // Initiate the shutting down.
            eventProcessor.beginShutdown();

            // Enqueuing a new event is rejected.
            assertThrows(RejectedExecutionException.class,
                () -> eventProcessor.enqueueLast(blockingEvent));

            // Release the blocking event to unblock the thread.
            blockingEvent.release();

            // The blocking event should be completed.
            blockingEvent.future.get(DEFAULT_MAX_WAIT_MS, TimeUnit.SECONDS);
            assertTrue(blockingEvent.future.isDone());
            assertFalse(blockingEvent.future.isCompletedExceptionally());

            // The other events should be failed.
            events.forEach(event -> {
                Throwable t = assertThrows(
                    ExecutionException.class,
                    () -> event.future.get(DEFAULT_MAX_WAIT_MS, TimeUnit.SECONDS)
                );
                assertEquals(RejectedExecutionException.class, t.getCause().getClass());
            });

            // The other events should not have been processed.
            assertEquals(1, numEventsExecuted.get());
        }
    }

    @Test
    public void testMetrics() throws Exception {
        CoordinatorRuntimeMetrics mockRuntimeMetrics = mock(CoordinatorRuntimeMetrics.class);
        Time mockTime = new MockTime();
        AtomicInteger numEventsExecuted = new AtomicInteger(0);

        // Special event which blocks until the latch is released.
        FutureEvent<Integer> blockingEvent = new FutureEvent<>(
            new TopicPartition("foo", 0), () -> {
                mockTime.sleep(4000L);
                return numEventsExecuted.incrementAndGet();
            },
            true,
            mockTime.milliseconds()
        );

        try (MultiThreadedEventProcessor eventProcessor = new MultiThreadedEventProcessor(
            new LogContext(),
            "event-processor-",
            1, // Use a single thread to block event in the processor.
            mockTime,
            mockRuntimeMetrics,
            new DelayEventAccumulator(mockTime, 500L)
        )) {
            // Enqueue the blocking event.
            eventProcessor.enqueueLast(blockingEvent);

            // Ensure that the blocking event is executed.
            waitForCondition(() -> numEventsExecuted.get() > 0,
                "Blocking event not executed.");

            // Enqueue the other event.
            FutureEvent<Integer> otherEvent = new FutureEvent<>(
                new TopicPartition("foo", 0), () -> {
                mockTime.sleep(5000L);
                return numEventsExecuted.incrementAndGet();
            },
                false,
                mockTime.milliseconds()
            );

            eventProcessor.enqueueLast(otherEvent);

            // Pass the time.
            mockTime.sleep(3000L);

            // Events should not be completed.
            assertFalse(otherEvent.future.isDone());

            // Release the blocking event to unblock the thread.
            blockingEvent.release();

            // The blocking event should be completed.
            blockingEvent.future.get(DEFAULT_MAX_WAIT_MS, TimeUnit.SECONDS);
            assertTrue(blockingEvent.future.isDone());
            assertFalse(blockingEvent.future.isCompletedExceptionally());

            // The other event should also be completed.
            otherEvent.future.get(DEFAULT_MAX_WAIT_MS, TimeUnit.SECONDS);
            assertTrue(otherEvent.future.isDone());
            assertFalse(otherEvent.future.isCompletedExceptionally());
            assertEquals(2, numEventsExecuted.get());

            // e1 poll time = 500
            // e1 processing time = 4000
            // e2 enqueue time = 3000
            // e2 poll time = 500
            // e2 processing time = 5000

            // e1 poll time
            verify(mockRuntimeMetrics, times(1)).recordEventQueueTime(500L);
            // e1 processing time + e2 enqueue time
            verify(mockRuntimeMetrics, times(1)).recordEventProcessingTime(7000L);

            // Second event (e2)

            // e1, e2 poll time
            verify(mockRuntimeMetrics, times(2)).recordThreadIdleTime(500.0);
            // event queue time = e2 enqueue time + e2 poll time
            verify(mockRuntimeMetrics, times(1)).recordEventQueueTime(3500L);
        }
    }

    @Test
    public void testRecordThreadIdleRatio() throws Exception {
        CoordinatorRuntimeMetrics mockRuntimeMetrics = mock(CoordinatorRuntimeMetrics.class);
        Time time = new MockTime();

        try (CoordinatorEventProcessor eventProcessor = new MultiThreadedEventProcessor(
            new LogContext(),
            "event-processor-",
            1,
            time,
            mockRuntimeMetrics,
            new DelayEventAccumulator(time, 100L)
        )) {
            List<Double> recordedIdleTimesMs = new ArrayList<>();
            AtomicInteger numEventsExecuted = new AtomicInteger(0);
            ArgumentCaptor<Double> idleTimeCaptured = ArgumentCaptor.forClass(Double.class);
            doAnswer(invocation -> {
                double threadIdleTime = idleTimeCaptured.getValue();
                assertEquals(100.0, threadIdleTime);

                // No synchronization required as the test uses a single event processor thread.
                recordedIdleTimesMs.add(threadIdleTime);
                return null;
            }).when(mockRuntimeMetrics).recordThreadIdleTime(idleTimeCaptured.capture());

            List<FutureEvent<Integer>> events = Arrays.asList(
                new FutureEvent<>(new TopicPartition("foo", 0), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 1), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 2), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 0), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 1), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 2), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 2), numEventsExecuted::incrementAndGet),
                new FutureEvent<>(new TopicPartition("foo", 2), numEventsExecuted::incrementAndGet)
            );

            long startMs = time.milliseconds();
            events.forEach(eventProcessor::enqueueLast);

            CompletableFuture.allOf(events
                .stream()
                .map(FutureEvent::future)
                .toArray(CompletableFuture[]::new)
            ).get(10, TimeUnit.SECONDS);
            events.forEach(event -> {
                assertTrue(event.future.isDone());
                assertFalse(event.future.isCompletedExceptionally());
            });

            assertEquals(events.size(), numEventsExecuted.get());
            verify(mockRuntimeMetrics, times(8)).recordThreadIdleTime(anyDouble());
            assertEquals(8, recordedIdleTimesMs.size());

            long diff = time.milliseconds() - startMs;
            double sum = recordedIdleTimesMs.stream().mapToDouble(Double::doubleValue).sum();
            double idleRatio = sum / diff;

            assertEquals(1.0, idleRatio, "idle ratio should be 1.0 but was: " + idleRatio);
        }
    }
}
