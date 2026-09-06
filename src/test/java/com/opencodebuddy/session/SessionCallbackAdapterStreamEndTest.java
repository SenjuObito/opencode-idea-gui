package com.opencodebuddy.session;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import static org.junit.Assert.*;

/**
 * Tests for the dual-path onStreamEnd delivery mechanism.
 *
 * <p>The actual SessionCallbackAdapter depends on IntelliJ's Alarm and
 * ApplicationManager, so these tests verify the core ordering/idempotency
 * contract using a simulated flush callback + fallback sequence.
 */
public class SessionCallbackAdapterStreamEndTest {

    /**
     * Records callJavaScript invocations for assertion.
     */
    private static final class RecordingJsTarget implements SessionCallbackAdapter.JsTarget {
        final List<String> calls = new ArrayList<>();

        @Override
        public void callJavaScript(String functionName, String... args) {
            StringBuilder sb = new StringBuilder(functionName);
            for (String arg : args) {
                sb.append(':').append(arg);
            }
            calls.add(sb.toString());
        }
    }

    /**
     * Simulates the dual-path dispatch logic extracted from onStreamEnd().
     * This mirrors the actual implementation's control flow without needing
     * IntelliJ Alarm/invokeLater.
     */
    private static final class DualPathSimulator {
        private volatile boolean streamEndSignalSent = false;
        private final RecordingJsTarget jsTarget;

        DualPathSimulator(RecordingJsTarget jsTarget) {
            this.jsTarget = jsTarget;
        }

        /** Simulate the flush callback path (primary). */
        void simulateFlushCallback(long sequence) {
            if (streamEndSignalSent) {
                return;
            }
            streamEndSignalSent = true;
            jsTarget.callJavaScript("onStreamEnd", String.valueOf(sequence));
            jsTarget.callJavaScript("showLoading", "false");
        }

        /** Simulate the fallback alarm path. */
        void simulateFallback() {
            if (streamEndSignalSent) {
                return;
            }
            streamEndSignalSent = true;
            jsTarget.callJavaScript("onStreamEnd", String.valueOf(-1));
            jsTarget.callJavaScript("showLoading", "false");
        }

        /** Reset for a new onStreamEnd call. */
        void reset() {
            streamEndSignalSent = false;
        }

        boolean isStreamEndSent() {
            return streamEndSignalSent;
        }
    }

    @Test
    public void primaryPathSendsStreamEndWithSequence() {
        RecordingJsTarget jsTarget = new RecordingJsTarget();
        DualPathSimulator sim = new DualPathSimulator(jsTarget);

        sim.reset();
        sim.simulateFlushCallback(42);

        assertTrue(sim.isStreamEndSent());
        assertEquals(2, jsTarget.calls.size());
        assertEquals("onStreamEnd:42", jsTarget.calls.get(0));
        assertEquals("showLoading:false", jsTarget.calls.get(1));
    }

    @Test
    public void fallbackPathSendsStreamEndWithNegativeSequence() {
        RecordingJsTarget jsTarget = new RecordingJsTarget();
        DualPathSimulator sim = new DualPathSimulator(jsTarget);

        sim.reset();
        sim.simulateFallback();

        assertTrue(sim.isStreamEndSent());
        assertEquals(2, jsTarget.calls.size());
        assertEquals("onStreamEnd:-1", jsTarget.calls.get(0));
        assertEquals("showLoading:false", jsTarget.calls.get(1));
    }

    @Test
    public void primaryPathBlocksFallback() {
        RecordingJsTarget jsTarget = new RecordingJsTarget();
        DualPathSimulator sim = new DualPathSimulator(jsTarget);

        sim.reset();
        // Primary fires first
        sim.simulateFlushCallback(42);
        // Fallback fires after — should be no-op
        sim.simulateFallback();

        assertEquals(2, jsTarget.calls.size()); // Only primary's calls
        assertEquals("onStreamEnd:42", jsTarget.calls.get(0));
    }

    @Test
    public void fallbackBlocksPrimary() {
        RecordingJsTarget jsTarget = new RecordingJsTarget();
        DualPathSimulator sim = new DualPathSimulator(jsTarget);

        sim.reset();
        // Fallback fires first (primary flush failed)
        sim.simulateFallback();
        // Primary fires late — should be no-op
        sim.simulateFlushCallback(42);

        assertEquals(2, jsTarget.calls.size()); // Only fallback's calls
        assertEquals("onStreamEnd:-1", jsTarget.calls.get(0));
    }

    @Test
    public void resetAllowsNextTurn() {
        RecordingJsTarget jsTarget = new RecordingJsTarget();
        DualPathSimulator sim = new DualPathSimulator(jsTarget);

        // First turn
        sim.reset();
        sim.simulateFlushCallback(10);
        assertEquals(2, jsTarget.calls.size());

        // Second turn — reset allows new delivery
        sim.reset();
        assertFalse(sim.isStreamEndSent());
        sim.simulateFlushCallback(20);
        assertEquals(4, jsTarget.calls.size());
        assertEquals("onStreamEnd:20", jsTarget.calls.get(2));
    }

    /**
     * Verify the flush LongConsumer callback contract:
     * when StreamMessageCoalescer.flush() invokes the callback with a
     * sequence number, the onStreamEnd signal uses that sequence.
     */
    @Test
    public void flushCallbackPassesSequenceToOnStreamEnd() {
        RecordingJsTarget jsTarget = new RecordingJsTarget();

        // Simulate what happens when flush(callback) is called:
        // The callback receives the sequence from the coalescer.
        final AtomicLong capturedSequence = new AtomicLong(-999);
        LongConsumer flushCallback = seq -> {
            capturedSequence.set(seq);
            jsTarget.callJavaScript("onStreamEnd", String.valueOf(seq));
        };

        flushCallback.accept(77);

        assertEquals(77, capturedSequence.get());
        assertEquals(1, jsTarget.calls.size());
        assertEquals("onStreamEnd:77", jsTarget.calls.get(0));
    }

    /**
     * Regression: block boundaries now fire mid-response (one per content-block
     * edge), so deltas still buffered in the delta throttlers when onBlockReset
     * runs belong to the ending block. The adapter must flush them to the
     * frontend BEFORE resetting; a bare reset() silently drops the buffered tail
     * and forces the frontend onto updateMessages snapshot rendering (visible as
     * thinking text jumping in chunks instead of streaming).
     *
     * <p>Exercised through the real SessionCallbackAdapter with test-friendly
     * collaborators; the throttlers' default constructor flushes synchronously
     * via flushNow(), so no IntelliJ Application is needed — the headless
     * invokeLater in onBlockReset happens after the ordering under test.
     */
    @Test
    public void blockResetFlushesBufferedDeltasBeforeClearing() throws Exception {
        RecordingJsTarget jsTarget = new RecordingJsTarget();
        SessionCallbackAdapter adapter = new SessionCallbackAdapter(
                null,
                jsTarget,
                null,
                () -> true,
                null
        );

        // Deltas arrive and sit in the throttlers' 33ms window...
        adapter.onContentDelta("text-tail");
        adapter.onThinkingDelta("thinking-tail");

        // onBlockReset dispatches its JS notification via invokeLater, which has
        // no Application in headless tests. The flush-before-reset ordering under
        // test completes before that call, so a benign proxy stub suffices.
        // Not restored afterwards: setApplication(null, ...) is rejected by the
        // platform's @NotNull contract, and each Gradle test fork owns its JVM.
        ApplicationManager.setApplication(invokeLaterInlineApplication());
        adapter.onBlockReset();

        assertTrue(jsTarget.calls.contains("onContentDelta:text-tail"));
        assertTrue(jsTarget.calls.contains("onThinkingDelta:thinking-tail"));
        adapter.deactivate();
    }

    /**
     * Headless Application stub whose invokeLater runs the runnable inline — the
     * adapter only needs a non-null application for its EDT dispatch.
     */
    private static @NotNull Application invokeLaterInlineApplication() {
        return (Application) Proxy.newProxyInstance(
                Application.class.getClassLoader(),
                new Class<?>[] { Application.class },
                (proxy, method, args) -> {
                    if ("invokeLater".equals(method.getName()) && args != null && args.length >= 1) {
                        ((Runnable) args[0]).run();
                        return null;
                    }
                    if (method.getName().equals("isDispatchThread")) {
                        return Boolean.TRUE;
                    }
                    // Unimplemented platform calls are irrelevant to this test;
                    // returning defaults keeps the stub minimal.
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return Boolean.FALSE;
                    }
                    return null;
                }
        );
    }
}

