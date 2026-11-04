package com.opencodebuddy.session;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * The session's message window keeps the assistant the engine is still
 * producing out of the confirmed history. That split is what lets the webview's
 * streaming bubble be the only owner of the draft, so a long-conversation tail
 * cannot produce a second copy of the same reply.
 */
public class SessionStateMessageWindowTest {

    @Test
    public void projectsTheLiveDraftOutOfTheConfirmedWindow() {
        OpencodeSession.Message user = message(OpencodeSession.Message.Type.USER, "hello");
        OpencodeSession.Message live = message(OpencodeSession.Message.Type.ASSISTANT, "partial");
        OpencodeSession.Message toolResult = message(OpencodeSession.Message.Type.USER, "[tool_result]");

        SessionState.MessageWindow window =
                SessionState.projectWindow(List.of(user, live, toolResult), live);

        Assert.assertSame(live, window.live());
        Assert.assertEquals(2, window.confirmed().size());
        Assert.assertSame(user, window.confirmed().get(0));
        Assert.assertSame(toolResult, window.confirmed().get(1));
        Assert.assertFalse(window.confirmed().contains(live));
    }

    @Test
    public void ignoresALiveReferenceThatIsNotInThisSnapshot() {
        OpencodeSession.Message user = message(OpencodeSession.Message.Type.USER, "hello");
        OpencodeSession.Message stale = message(OpencodeSession.Message.Type.ASSISTANT, "old");

        SessionState.MessageWindow window =
                SessionState.projectWindow(List.of(user), stale);

        Assert.assertNull(window.live());
        Assert.assertEquals(List.of(user), window.confirmed());
    }

    @Test
    public void leavesACommittedWindowUntouchedWhenNoDraftIsLive() {
        OpencodeSession.Message user = message(OpencodeSession.Message.Type.USER, "hello");
        OpencodeSession.Message assistant = message(OpencodeSession.Message.Type.ASSISTANT, "done");

        SessionState.MessageWindow window =
                SessionState.projectWindow(List.of(user, assistant), null);

        Assert.assertNull(window.live());
        Assert.assertEquals(List.of(user, assistant), window.confirmed());
    }

    private static OpencodeSession.Message message(OpencodeSession.Message.Type type, String content) {
        return new OpencodeSession.Message(type, content);
    }
}
