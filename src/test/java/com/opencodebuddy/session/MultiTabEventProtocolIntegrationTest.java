package com.opencodebuddy.session;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The multi-tab event protocol, end to end.
 *
 * <p>Two tabs stream at the same time. The daemon's lines reach two adapters
 * (one per tab), and every event they push has to name <em>its own</em> tab
 * and session: the webview routes by tab id, so a missing or foreign tab id is
 * exactly how one conversation's text ends up in another tab.</p>
 *
 * <p>This is the "fake daemon" of the plan's acceptance list: the marker
 * stream is replaced by direct adapter calls, but everything below them — the
 * adapter, the coalescer, the bridge call shape — is the production code.</p>
 */
public class MultiTabEventProtocolIntegrationTest {

    private static Application previousApplication;

    /** The events one tab's adapter published, in order. */
    private static final class RecordingTarget implements com.opencodebuddy.tab.TabEventSink {
        private final List<com.opencodebuddy.tab.TabEvent> calls =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(com.opencodebuddy.tab.TabEvent event) {
            calls.add(event);
        }
    }

    @BeforeClass
    public static void installSynchronousApplication() {
        previousApplication = ApplicationManager.getApplication();
        ApplicationManager.setApplication(synchronousApplication());
    }

    @AfterClass
    public static void restoreApplication() {
        ApplicationManager.setApplication(previousApplication);
    }

    @Before
    public void reset() {
        // Nothing to reset: every test builds its own adapters.
    }

    /**
     * Wait for the throttled deltas to reach the bridge.
     *
     * <p>Deltas are batched in a 33ms window before they are pushed, so the
     * test has to wait for the flush instead of reading the target right
     * away.</p>
     */
    private static void awaitCalls(RecordingTarget target, int atLeast) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (target.calls.size() >= atLeast) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static SessionCallbackAdapter adapter(String sessionId, String tabId, RecordingTarget target) {
        return new SessionCallbackAdapter(
                sessionId,
                null,
                target,
                null,
                () -> true,
                null,
                tabId);
    }

    private static final StreamMessageCoalescer.JsCallbackTarget NO_BROWSER =
            new StreamMessageCoalescer.JsCallbackTarget() {
                @Override
                public com.intellij.ui.jcef.JBCefBrowser getBrowser() {
                    return null;
                }

                @Override
                public boolean isDisposed() {
                    return false;
                }

                @Override
                public com.opencodebuddy.handler.core.HandlerContext getHandlerContext() {
                    return null;
                }
            };

    private static SessionCallbackAdapter streamingAdapter(
            String sessionId, String tabId, RecordingTarget target) {
        StreamMessageCoalescer coalescer = new StreamMessageCoalescer(sessionId, tabId, target, NO_BROWSER);
        return new SessionCallbackAdapter(
                sessionId,
                coalescer,
                target,
                null,
                () -> true,
                null,
                tabId);
    }

    @Test
    public void everyEventNamesItsOwnTabAndSession() {
        RecordingTarget targetA = new RecordingTarget();
        RecordingTarget targetB = new RecordingTarget();
        SessionCallbackAdapter adapterA = adapter("ses_a", "tab-a", targetA);
        SessionCallbackAdapter adapterB = adapter("ses_b", "tab-b", targetB);
        try {
            // Two tabs stream at the same time; the daemon's lines interleave.
            adapterA.onContentDelta("A1");
            adapterB.onContentDelta("B1");
            adapterA.onThinkingDelta("A-think");
            adapterB.onThinkingDelta("B-think");

            awaitCalls(targetA, 2);
            awaitCalls(targetB, 2);

            assertFalse("tab A produced no event at all", targetA.calls.isEmpty());
            assertFalse("tab B produced no event at all", targetB.calls.isEmpty());

            for (com.opencodebuddy.tab.TabEvent event : targetA.calls) {
                assertEquals("A's event lost its tab id: " + event, "tab-a", event.getTabId());
                assertFalse("A's event names the other tab: " + event, "tab-b".equals(event.getTabId()));
                assertFalse("A's event names the other session: " + event, "ses_b".equals(event.getSessionId()));
            }
            for (com.opencodebuddy.tab.TabEvent event : targetB.calls) {
                assertEquals("B's event lost its tab id: " + event, "tab-b", event.getTabId());
                assertFalse("B's event names the other tab: " + event, "tab-a".equals(event.getTabId()));
                assertFalse("B's event names the other session: " + event, "ses_a".equals(event.getSessionId()));
            }
        } finally {
            adapterA.deactivate();
            adapterB.deactivate();
        }
    }

    @Test
    public void aTabWithoutBindingCarriesNoTabIdAndIsNotMisrouted() {
        RecordingTarget target = new RecordingTarget();
        // A session-only adapter (history path before the tab is known) must not
        // invent a tab: the webview falls back to its session-based routing.
        SessionCallbackAdapter adapter = new SessionCallbackAdapter(
                "ses_legacy",
                null,
                target,
                null,
                () -> true,
                null);
        try {
            adapter.onContentDelta("legacy");
            awaitCalls(target, 1);

            assertTrue(target.calls.size() >= 1);
            com.opencodebuddy.tab.TabEvent event = target.calls.get(0);
            assertEquals("the event lost its session id: " + event, "ses_legacy", event.getSessionId());
            assertTrue("the event invented a tab id: " + event, event.getTabId() == null);
        } finally {
            adapter.deactivate();
        }
    }

    @Test
    public void bindingATabStampsItsIdOnLaterEvents() {
        RecordingTarget target = new RecordingTarget();
        SessionCallbackAdapter adapter = adapter("ses_a", "tab-a", target);
        try {
            adapter.setTabId("tab-later");
            adapter.onContentDelta("after-bind");
            awaitCalls(target, 1);

            com.opencodebuddy.tab.TabEvent event = target.calls.get(0);
            assertEquals("the event lost its tab id: " + event, "tab-later", event.getTabId());
        } finally {
            adapter.deactivate();
        }
    }

    @Test
    public void closingOneStreamingTabStopsItsEventsOnly() {
        RecordingTarget targetA = new RecordingTarget();
        RecordingTarget targetB = new RecordingTarget();
        SessionCallbackAdapter adapterA = adapter("ses_a", "tab-a", targetA);
        SessionCallbackAdapter adapterB = adapter("ses_b", "tab-b", targetB);
        try {
            adapterA.onContentDelta("A-before");
            adapterB.onContentDelta("B-before");
            awaitCalls(targetA, 1);
            awaitCalls(targetB, 1);
            int aBefore = targetA.calls.size();

            adapterA.deactivate();
            adapterA.onContentDelta("A-after-close");
            adapterB.onContentDelta("B-after-close");
            awaitCalls(targetB, 2);

            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            assertEquals("a closed tab kept publishing", aBefore, targetA.calls.size());
            boolean sawBAfterClose = false;
            for (com.opencodebuddy.tab.TabEvent event : targetB.calls) {
                if ("content.delta".equals(event.getType())
                        && event.getPayload().get("delta") != null
                        && event.getPayload().get("delta").toString().contains("B-after-close")) {
                    sawBAfterClose = true;
                }
            }
            assertTrue("the surviving tab stopped streaming too", sawBAfterClose);
        } finally {
            adapterA.deactivate();
            adapterB.deactivate();
        }
    }

    @Test
    public void historySnapshotAndStreamingDeltasStayOnTheirOwnTabs() {
        RecordingTarget targetA = new RecordingTarget();
        RecordingTarget targetB = new RecordingTarget();
        SessionCallbackAdapter adapterA = streamingAdapter("ses_a", "tab-a", targetA);
        SessionCallbackAdapter adapterB = streamingAdapter("ses_b", "tab-b", targetB);
        try {
            adapterA.onMessageUpdate(List.of(
                    new OpencodeSession.Message(OpencodeSession.Message.Type.USER, "history")));
            adapterB.onContentDelta("B-live");

            awaitCalls(targetA, 1);
            awaitCalls(targetB, 1);

            for (com.opencodebuddy.tab.TabEvent event : targetA.calls) {
                assertEquals("tab-a", event.getTabId());
                assertFalse("ses_b".equals(event.getSessionId()));
            }
            for (com.opencodebuddy.tab.TabEvent event : targetB.calls) {
                assertEquals("tab-b", event.getTabId());
                assertFalse("ses_a".equals(event.getSessionId()));
            }
        } finally {
            adapterA.deactivate();
            adapterB.deactivate();
        }
    }

    @Test
    public void titleUsageAndRevertEventsCarryTargetTab() {
        RecordingTarget targetA = new RecordingTarget();
        RecordingTarget targetB = new RecordingTarget();
        SessionCallbackAdapter adapterA = adapter("ses_a", "tab-a", targetA);
        SessionCallbackAdapter adapterB = adapter("ses_b", "tab-b", targetB);
        try {
            adapterA.onSessionTitleReceived("ses_a", "A title");
            adapterA.onUsageUpdate(10, 100);
            adapterA.onRevertStateUpdate(true, "msg-a");
            adapterB.onSessionTitleReceived("ses_b", "B title");

            awaitCalls(targetA, 3);
            awaitCalls(targetB, 1);

            assertTrue("title event missing",
                    targetA.calls.stream().anyMatch(e -> "tab.title.changed".equals(e.getType())));
            assertTrue("usage event missing",
                    targetA.calls.stream().anyMatch(e -> "usage.updated".equals(e.getType())));
            assertTrue("revert event missing",
                    targetA.calls.stream().anyMatch(e -> "revert.changed".equals(e.getType())));
            for (com.opencodebuddy.tab.TabEvent event : targetA.calls) {
                assertEquals("tab-a", event.getTabId());
                assertFalse("ses_b".equals(event.getSessionId()));
            }
            for (com.opencodebuddy.tab.TabEvent event : targetB.calls) {
                assertEquals("tab-b", event.getTabId());
                assertFalse("ses_a".equals(event.getSessionId()));
            }
        } finally {
            adapterA.deactivate();
            adapterB.deactivate();
        }
    }

    @Test
    public void messageRemovalCarriesOnlyItsTargetTab() {
        RecordingTarget targetA = new RecordingTarget();
        RecordingTarget targetB = new RecordingTarget();
        SessionCallbackAdapter adapterA = adapter("ses_a", "tab-a", targetA);
        SessionCallbackAdapter adapterB = adapter("ses_b", "tab-b", targetB);
        try {
            adapterB.onMessagesRemoved(List.of("msg-b-1"));

            awaitCalls(targetB, 1);

            assertEquals("tab-b", targetB.calls.get(0).getTabId());
            assertEquals("message.removed", targetB.calls.get(0).getType());
            assertTrue("the untouched tab received a removal", targetA.calls.isEmpty());
        } finally {
            adapterA.deactivate();
            adapterB.deactivate();
        }
    }

    @Test
    public void daemonDisconnectEndsEveryActiveTurnOnItsOwnTab() {
        RecordingTarget targetA = new RecordingTarget();
        RecordingTarget targetB = new RecordingTarget();
        SessionCallbackAdapter adapterA = streamingAdapter("ses_a", "tab-a", targetA);
        SessionCallbackAdapter adapterB = streamingAdapter("ses_b", "tab-b", targetB);
        try {
            adapterA.onStreamStart();
            adapterB.onStreamStart();
            awaitCalls(targetA, 1);
            awaitCalls(targetB, 1);

            adapterA.onStreamEnd();
            adapterB.onStreamEnd();
            awaitCalls(targetA, 2);
            awaitCalls(targetB, 2);

            assertTrue("tab A did not end its turn",
                    targetA.calls.stream().anyMatch(e -> "turn.ended".equals(e.getType())));
            assertTrue("tab B did not end its turn",
                    targetB.calls.stream().anyMatch(e -> "turn.ended".equals(e.getType())));
            for (com.opencodebuddy.tab.TabEvent event : targetA.calls) {
                assertEquals("tab-a", event.getTabId());
            }
            for (com.opencodebuddy.tab.TabEvent event : targetB.calls) {
                assertEquals("tab-b", event.getTabId());
            }
        } finally {
            adapterA.deactivate();
            adapterB.deactivate();
        }
    }

    @Test
    public void aThousandTabLifecyclesDoNotThrow() {
        for (int i = 0; i < 1_000; i++) {
            RecordingTarget target = new RecordingTarget();
            SessionCallbackAdapter adapter = adapter("ses_" + i, "tab-" + i, target);
            adapter.onContentDelta("x");
            adapter.deactivate();
        }
    }

    private static Application synchronousApplication() {
        return (Application) Proxy.newProxyInstance(
                Application.class.getClassLoader(),
                new Class<?>[] {Application.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isDispatchThread":
                            return Boolean.TRUE;
                        case "getApplicationModalityState":
                            return ModalityState.NON_MODAL;
                        case "isEAP":
                            return Boolean.FALSE;
                        case "invokeLater":
                        case "invokeAndWait":
                            if (args != null) {
                                for (Object arg : args) {
                                    if (arg instanceof Runnable runnable) {
                                        runnable.run();
                                        break;
                                    }
                                }
                            }
                            return null;
                        case "getAnyModalityState":
                        case "getDefaultModalityState":
                        case "getNoneModalityState":
                            return ModalityState.NON_MODAL;
                        default:
                            return null;
                    }
                });
    }
}
