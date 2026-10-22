package com.opencodebuddy.tab;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TabCommandRouterTest {

    private static TabCommand cmd(String tabId, String type, String requestId) {
        return new TabCommand(TabId.of(tabId), type, requestId, new JsonObject());
    }

    @Test
    public void onlyRegisteredTypesAreTabCommands() {
        TabCommandRouter router = new TabCommandRouter(new TabRegistry());
        router.register("tab.title.set", command -> { });

        assertTrue(router.handles("tab.title.set"));
        assertFalse(router.handles("tab.loading.changed"));
        assertFalse(router.handles("tab.status.changed"));
        assertFalse(router.handles(null));
    }

    @Test
    public void routesToTheNamedTabOnItsActor() throws Exception {
        TabRegistry registry = new TabRegistry();
        registry.create(TabId.of("tab-a"), "/repo");
        registry.create(TabId.of("tab-b"), "/repo");
        TabCommandRouter router = new TabCommandRouter(registry);
        List<String> handled = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        router.register("session.interrupt", command -> {
            handled.add(command.tabId().value());
            done.countDown();
        });

        assertEquals(TabCommandRouter.Result.ROUTED, router.route(cmd("tab-b", "session.interrupt", "req-1")));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("tab-b"), handled);
    }

    @Test
    public void replaysOfTheSameRequestIdRunOnce() throws Exception {
        TabRegistry registry = new TabRegistry();
        registry.create(TabId.of("tab-a"), "/repo");
        TabCommandRouter router = new TabCommandRouter(registry);
        List<String> handled = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        router.register("tab.title.set", command -> {
            handled.add(command.requestId());
            done.countDown();
        });

        assertEquals(TabCommandRouter.Result.ROUTED, router.route(cmd("tab-a", "tab.title.set", "req-9")));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(TabCommandRouter.Result.DUPLICATE,
                router.route(cmd("tab-a", "tab.title.set", "req-9")));
        assertEquals(1, handled.size());
    }

    @Test
    public void neverFallsBackToTheActiveTab() {
        TabRegistry registry = new TabRegistry();
        registry.create(TabId.of("tab-a"), "/repo");
        TabCommandRouter router = new TabCommandRouter(registry);
        router.register("tab.activate", command -> {
            throw new AssertionError("must not run");
        });

        assertEquals(TabCommandRouter.Result.UNKNOWN_TAB,
                router.route(cmd("tab-missing", "tab.activate", "req-2")));
    }

    @Test
    public void aRejectedRequestIdCanBeRetried() throws Exception {
        TabRegistry registry = new TabRegistry();
        TabCommandRouter router = new TabCommandRouter(registry);
        CountDownLatch done = new CountDownLatch(1);
        router.register("tab.activate", command -> done.countDown());

        assertEquals(TabCommandRouter.Result.UNKNOWN_TAB,
                router.route(cmd("tab-late", "tab.activate", "req-late")));

        registry.create(TabId.of("tab-late"), "/repo");
        assertEquals(TabCommandRouter.Result.ROUTED,
                router.route(cmd("tab-late", "tab.activate", "req-late")));
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void unknownCommandIsReportedWithoutRunningAnything() {
        TabRegistry registry = new TabRegistry();
        registry.create(TabId.of("tab-a"), "/repo");
        TabCommandRouter router = new TabCommandRouter(registry);

        assertEquals(TabCommandRouter.Result.UNKNOWN_COMMAND,
                router.route(cmd("tab-a", "does.not.exist", "req-3")));
    }

    @Test
    public void createRunsInlineSoLaterCommandsSeeTheTab() {
        TabRegistry registry = new TabRegistry();
        TabCommandRouter router = new TabCommandRouter(registry);
        router.register("tab.create", command ->
                registry.create(command.tabId(), command.string("cwd")));

        JsonObject payload = new JsonObject();
        payload.addProperty("cwd", "/workspace");
        assertEquals(TabCommandRouter.Result.ROUTED, router.route(
                new TabCommand(TabId.of("tab-new"), "tab.create", "req-4", payload)));
        assertEquals("/workspace", registry.get(TabId.of("tab-new")).getCwd());
    }

    @Test
    public void closedTabActorReturnsClosed() {
        TabRegistry registry = new TabRegistry();
        TabRecord record = registry.create(TabId.of("tab-a"), "/repo");
        TabCommandRouter router = new TabCommandRouter(registry);
        router.register("tab.activate", command -> { });
        record.getActor().close();

        assertEquals(TabCommandRouter.Result.CLOSED,
                router.route(cmd("tab-a", "tab.activate", "req-5")));
        assertFalse(record.getActor().submit(() -> { }));
    }

    @Test
    public void remembersOnlyTheMostRecent2048RequestIds() {
        TabRegistry registry = new TabRegistry();
        registry.create(TabId.of("tab-a"), "/repo");
        TabCommandRouter router = new TabCommandRouter(registry);
        router.register("tab.title.set", command -> { });

        for (int i = 0; i < 2_048; i++) {
            assertEquals(TabCommandRouter.Result.ROUTED,
                    router.route(cmd("tab-a", "tab.title.set", "req-" + i)));
        }
        assertEquals(2_048, router.rememberedRequestIdCount());

        // The oldest id fell out of the window when the 2049th distinct id
        // arrived, so replaying it is treated as new; the newest id is still
        // remembered.
        assertEquals(TabCommandRouter.Result.ROUTED,
                router.route(cmd("tab-a", "tab.title.set", "req-2048")));
        assertEquals(TabCommandRouter.Result.ROUTED,
                router.route(cmd("tab-a", "tab.title.set", "req-0")));
        assertEquals(TabCommandRouter.Result.DUPLICATE,
                router.route(cmd("tab-a", "tab.title.set", "req-2048")));
        assertEquals(2_048, router.rememberedRequestIdCount());
    }
}
