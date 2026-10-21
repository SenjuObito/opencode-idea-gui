package com.opencodebuddy.tab;

import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serializes every state mutation that belongs to one tab.
 *
 * <p>Commands (JCEF thread), daemon callbacks (reader thread) and style/theme
 * updates (EDT) all reach the same tab. Without a per-tab serial queue the
 * ordering between "tab closed" and "delta arrived" is decided by whichever
 * thread wins, which is how a closed tab used to resurrect a session.</p>
 *
 * <p>The actor owns a queue, not a thread: an idle tab costs nothing, and the
 * shared pool runs whichever actor has work. A closed actor rejects new work
 * and drops anything still queued.</p>
 */
public final class TabSessionActor {

    private static final Logger LOG = Logger.getInstance(TabSessionActor.class);

    private static final Executor SHARED_POOL = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "opencode-tab-actor");
        thread.setDaemon(true);
        return thread;
    });

    private final TabId tabId;
    private final Executor executor;
    private final Deque<Runnable> queue = new ArrayDeque<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger rejected = new AtomicInteger();

    public TabSessionActor(TabId tabId) {
        this(tabId, SHARED_POOL);
    }

    TabSessionActor(TabId tabId, Executor executor) {
        this.tabId = tabId;
        this.executor = executor;
    }

    /**
     * Queue work for this tab. Tasks run in submission order, one at a time.
     *
     * @return {@code false} when the actor is already closed and the task was
     *         rejected
     */
    public boolean submit(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        synchronized (queue) {
            if (closed.get()) {
                rejected.incrementAndGet();
                return false;
            }
            queue.addLast(task);
        }
        scheduleDrain();
        return true;
    }

    public TabId getTabId() {
        return tabId;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public int rejectedCount() {
        return rejected.get();
    }

    /** Reject new work and discard queued work. In-flight work finishes. */
    public void close() {
        int dropped;
        synchronized (queue) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            dropped = queue.size();
            queue.clear();
        }
        if (dropped > 0) {
            LOG.info("[TabSessionActor] closed " + tabId.value() + ", dropped " + dropped + " queued tasks");
        }
    }

    private void scheduleDrain() {
        if (draining.compareAndSet(false, true)) {
            executor.execute(this::drain);
        }
    }

    private void drain() {
        try {
            while (true) {
                Runnable task;
                synchronized (queue) {
                    task = queue.pollFirst();
                }
                if (task == null) {
                    return;
                }
                try {
                    task.run();
                } catch (Throwable t) {
                    // One failing command must not stop the tab (and must not
                    // kill the shared pool worker).
                    LOG.warn("[TabSessionActor] task failed for " + tabId.value(), t);
                }
            }
        } finally {
            draining.set(false);
            // A submit that raced the final poll saw draining=true and did not
            // schedule; re-check so its task is not stranded.
            boolean pending;
            synchronized (queue) {
                pending = !queue.isEmpty();
            }
            if (pending && !closed.get() && draining.compareAndSet(false, true)) {
                executor.execute(this::drain);
            }
        }
    }
}
