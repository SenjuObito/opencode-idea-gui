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

    /** The calls one tab's adapter pushed, in order. */
    private static final class RecordingTarget implements SessionCallbackAdapter.JsTarget {
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        private void record(String functionName, String... args) {
            StringBuilder sb = new StringBuilder(functionName);
            for (String arg : args) {
                sb.append(':').append(arg);
            }
            calls.add(sb.toString());
        }

        @Override
        public void callJavaScript(String functionName, String... args) {
            record(functionName, args);
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

            for (String call : targetA.calls) {
                assertTrue("A's event lost its tab id: " + call, call.contains(":tab-a"));
                assertFalse("A's event names the other tab: " + call, call.contains(":tab-b"));
                assertFalse("A's event names the other session: " + call, call.contains("ses_b"));
            }
            for (String call : targetB.calls) {
                assertTrue("B's event lost its tab id: " + call, call.contains(":tab-b"));
                assertFalse("B's event names the other tab: " + call, call.contains(":tab-a"));
                assertFalse("B's event names the other session: " + call, call.contains("ses_a"));
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
            String call = target.calls.get(0);
            assertTrue("the event lost its session id: " + call, call.contains("ses_legacy"));
            assertTrue("the event invented a tab id: " + call, call.endsWith(":"));
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

            String call = target.calls.get(0);
            assertTrue("the event lost its tab id: " + call, call.contains(":tab-later"));
        } finally {
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
