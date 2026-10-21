package com.opencodebuddy.tab;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TabSessionActorTest {

    @Test
    public void preservesPerTabSubmissionOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        TabSessionActor actor = new TabSessionActor(TabId.of("tab-a"), pool);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(200);

        for (int i = 0; i < 200; i++) {
            int value = i;
            actor.submit(() -> {
                order.add(value);
                done.countDown();
            });
        }

        assertTrue(done.await(5, TimeUnit.SECONDS));
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            expected.add(i);
        }
        assertEquals(expected, order);
        pool.shutdownNow();
    }

    @Test
    public void closeRejectsNewWorkAndDropsQueuedWork() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        TabSessionActor actor = new TabSessionActor(TabId.of("tab-a"), pool);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> ran = Collections.synchronizedList(new ArrayList<>());

        actor.submit(() -> {
            running.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ran.add("first");
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));

        actor.submit(() -> ran.add("second"));
        actor.close();
        release.countDown();

        assertFalse(actor.submit(() -> ran.add("third")));
        assertTrue(actor.isClosed());
        assertEquals(1, actor.rejectedCount());
        Thread.sleep(100);
        assertEquals(List.of("first"), ran);
        pool.shutdownNow();
    }

    @Test
    public void oneFailingTaskDoesNotStopLaterTasks() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        TabSessionActor actor = new TabSessionActor(TabId.of("tab-a"), pool);
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(2);

        actor.submit(() -> {
            throw new IllegalStateException("boom");
        });
        actor.submit(() -> {
            ran.add("after");
            done.countDown();
        });
        actor.submit(() -> {
            ran.add("and-after");
            done.countDown();
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("after", "and-after"), ran);
        pool.shutdownNow();
    }
}
