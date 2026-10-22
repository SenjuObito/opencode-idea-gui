package com.opencodebuddy.tab;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
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
