package com.opencodebuddy.tab;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PendingSendTest {

    @Test
    public void markerReachesTheRequestAndBufferedLinesReplayInOrderAfterTheSinkBinds() {
        List<String> requestLines = new ArrayList<>();
        List<String> sinkLines = new ArrayList<>();
        PendingSend pending = new PendingSend("req-1", "tab-1", requestLines::add);

        pending.onLine("[CONTENT_DELTA] \"a\"");
        pending.onLine("[CONTENT_DELTA] \"b\"");
        pending.onLine("[SESSION_ID] ses_new");
        pending.onLine("[CONTENT_DELTA] \"c\"");

        // The marker is the only line the request needs: it triggers the bind.
        assertEquals(List.of("[SESSION_ID] ses_new"), requestLines);
        assertEquals("ses_new", pending.sessionId());

        pending.attach(sinkLines::add);
        assertEquals(
                List.of("[CONTENT_DELTA] \"a\"", "[CONTENT_DELTA] \"b\"", "[CONTENT_DELTA] \"c\""),
                sinkLines);
    }

    @Test
    public void linesAfterTheSinkBindsGoStraightToTheSink() {
        List<String> requestLines = new ArrayList<>();
        List<String> sinkLines = new ArrayList<>();
        PendingSend pending = new PendingSend("req-2", null, requestLines::add);
        pending.onLine("[SESSION_ID] ses_x");
        pending.attach(sinkLines::add);

        pending.onLine("[CONTENT_DELTA] \"live\"");

        assertEquals(List.of("[CONTENT_DELTA] \"live\""), sinkLines);
        assertEquals(List.of("[SESSION_ID] ses_x"), requestLines);
    }

    @Test
    public void aSendThatNeverLearnsItsSessionTimesOut() {
        AtomicBoolean timedOut = new AtomicBoolean(false);
        PendingSend pending = new PendingSend("req-3", null, line -> { });
        pending.setTimeoutAction(() -> timedOut.set(true));

        pending.expireNow();

        assertTrue(pending.isTimedOut());
        assertTrue(timedOut.get());
        assertNull("a timed-out send must stop buffering", pending.sessionId());
    }

    /**
     * The host routes a line that already names a session straight to that
     * session's sink; the marker therefore never reaches the PendingSend. The
     * sink route reports the learned identity here so the bootstrap timeout is
     * disarmed instead of failing the send 30 s into a still-running turn.
     */
    @Test
    public void aSessionLearnedFromTheSinkRouteDisarmsTheBootstrapTimeout() {
        AtomicBoolean timedOut = new AtomicBoolean(false);
        PendingSend pending = new PendingSend("req-5", null, line -> { });
        pending.setTimeoutAction(() -> timedOut.set(true));

        pending.bindSession("ses_known");
        pending.expireNow();

        assertEquals("ses_known", pending.sessionId());
        assertFalse(pending.isTimedOut());
        assertFalse("an attributed send must not fail mid-turn", timedOut.get());
    }

    /**
     * A send that already knows its session must not be failed by the bootstrap
     * deadline, even though its buffer is still registered as a request-id
     * fallback. This is the host-side half of the "indicator disappears
     * mid-turn" regression.
     */
    @Test
    public void aDisabledBootstrapTimeoutNeverExpires() {
        AtomicBoolean timedOut = new AtomicBoolean(false);
        PendingSend pending = new PendingSend("req-6", null, line -> { });
        pending.setTimeoutAction(() -> timedOut.set(true));

        pending.disableBootstrapTimeout();
        pending.expireNow();

        assertFalse(pending.isBootstrapTimeoutEnabled());
        assertFalse(pending.isTimedOut());
        assertFalse(timedOut.get());
    }

    @Test
    public void aCompletedSendStopsAcceptingLines() {
        List<String> sinkLines = new ArrayList<>();
        PendingSend pending = new PendingSend("req-4", null, line -> { });
        pending.onLine("[SESSION_ID] ses_done");
        pending.attach(sinkLines::add);
        pending.complete();

        pending.onLine("[CONTENT_DELTA] \"late\"");

        assertTrue(sinkLines.isEmpty());
    }
}
