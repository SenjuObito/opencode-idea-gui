package com.opencodebuddy.session;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.tab.TabEvent;
import com.intellij.ui.jcef.JBCefBrowser;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The first send of a tab happens before the daemon has reported a session id.
 * Its snapshot is still the truth about that tab's conversation, so a
 * tab-addressed coalescer must publish it; only a frame that names neither a
 * tab nor a session is unattributable.
 */
public class StreamMessageCoalescerTabAddressingTest {

    private static Application previousApplication;

    @BeforeClass
    public static void installSynchronousApplication() {
        previousApplication = ApplicationManager.getApplication();
        ApplicationManager.setApplication(synchronousApplication());
    }

    @Before
    public void ensureSynchronousApplication() {
        if (ApplicationManager.getApplication() == null) {
            ApplicationManager.setApplication(synchronousApplication());
        }
    }

    @AfterClass
    public static void restoreApplication() {
        ApplicationManager.setApplication(previousApplication);
    }

    /** Runs the coalescer's pooled work inline so the assertions are deterministic. */
    private static Application synchronousApplication() {
        return (Application) Proxy.newProxyInstance(
                Application.class.getClassLoader(),
                new Class<?>[]{Application.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "executeOnPooledThread":
                        case "invokeLater":
                            if (args != null && args.length >= 1 && args[0] instanceof Runnable) {
                                ((Runnable) args[0]).run();
                            }
                            return null;
                        case "isDispatchThread":
                            return Boolean.TRUE;
                        case "isWriteThread":
                        case "isReadAccessAllowed":
                        case "hasWriteAction":
                        case "isDisposed":
                        case "isUnitTestMode":
                        case "isHeadlessEnvironment":
                        case "isInternal":
                        case "isEAP":
                            return Boolean.FALSE;
                        case "getAnyModalityState":
                        case "getDefaultModalityState":
                        case "getCurrentModalityState":
                        case "getNoneModalityState":
                            return ModalityState.NON_MODAL;
                        default:
                            return null;
                    }
                });
    }

    private static final class NoopTarget implements StreamMessageCoalescer.JsCallbackTarget {
        @Override
        public JBCefBrowser getBrowser() {
            return null;
        }

        @Override
        public boolean isDisposed() {
            return false;
        }

        @Override
        public HandlerContext getHandlerContext() {
            return null;
        }
    }

    private static List<OpencodeSession.Message> userMessages(String text) {
        return List.of(new OpencodeSession.Message(OpencodeSession.Message.Type.USER, text));
    }

    @Test
    public void tabAddressedSnapshotIsPublishedBeforeSessionId() {
        List<TabEvent> published = new ArrayList<>();
        StreamMessageCoalescer coalescer = new StreamMessageCoalescer(
                null, "tab-1", published::add, new NoopTarget());
        try {
            coalescer.enqueue(userMessages("first"));
            coalescer.flush(null);

            assertEquals("the pre-session snapshot must not be dropped", 1, published.size());
            TabEvent event = published.get(0);
            assertEquals("message.snapshot", event.getType());
            assertEquals("tab-1", event.getTabId());
            assertTrue("no canonical session id exists yet", event.getSessionId() == null);
        } finally {
            coalescer.dispose();
        }
    }

    @Test
    public void unattributedSnapshotIsDropped() {
        List<TabEvent> published = new ArrayList<>();
        StreamMessageCoalescer coalescer = new StreamMessageCoalescer(
                null, null, published::add, new NoopTarget());
        try {
            coalescer.enqueue(userMessages("first"));
            coalescer.flush(null);

            assertTrue("a frame with neither identity must not reach a tab", published.isEmpty());
        } finally {
            coalescer.dispose();
        }
    }
}
