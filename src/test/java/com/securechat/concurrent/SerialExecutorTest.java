package com.securechat.concurrent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerialExecutorTest {
    @Test
    void preservesOrderAndPreventsSameClientOverlap() throws Exception {
        List<Integer> completionOrder = new ArrayList<>();
        AtomicInteger activeTasks = new AtomicInteger();
        AtomicInteger maximumOverlap = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(100);

        try (ChatWorkerPool pool = new ChatWorkerPool(4);
             SerialExecutor serial = new SerialExecutor(pool)) {
            for (int index = 0; index < 100; index++) {
                int taskNumber = index;
                serial.execute(() -> {
                    int active = activeTasks.incrementAndGet();
                    maximumOverlap.accumulateAndGet(active, Math::max);
                    completionOrder.add(taskNumber);
                    activeTasks.decrementAndGet();
                    completed.countDown();
                });
            }

            assertTrue(completed.await(5, TimeUnit.SECONDS));
        }

        assertEquals(1, maximumOverlap.get());
        assertEquals(
                java.util.stream.IntStream.range(0, 100).boxed().toList(),
                completionOrder
        );
    }
}
