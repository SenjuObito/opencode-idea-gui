package com.opencodebuddy.session;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers the text the user typed for native opencode slash commands.
 *
 * <p>opencode expands a command before persisting it: the user message that
 * comes back from the server contains the whole command template and no
 * command name. A history reload therefore replaced a `/review` bubble with
 * pages of reviewer prompt. The send path records {@code "/review <args>"}
 * here, keyed by session and send time; the history converter matches those
 * entries back to the server's user messages (see
 * {@link OpenCodeMessageConverter#matchCommandTexts}).</p>
 *
 * <p>Only local data: the text is the user's own input and never leaves the
 * IDE through this store.</p>
 */
@Service(Service.Level.APP)
@State(name = "OpenCodeCommandTextStore", storages = @Storage("opencode-command-text.xml"))
public final class SessionCommandTextStore
        implements PersistentStateComponent<SessionCommandTextStore.StoreState> {

    /** Older entries per session are dropped first. */
    static final int MAX_ENTRIES_PER_SESSION = 200;

    /** Least-recently-inserted sessions are dropped first. */
    static final int MAX_SESSIONS = 200;

    /** One recorded command send. */
    public static final class Entry {
        public long sentAt;
        public String text = "";

        public Entry() {
        }

        public Entry(long sentAt, String text) {
            this.sentAt = sentAt;
            this.text = text != null ? text : "";
        }
    }

    /** The persisted shape: session id → command sends, oldest first. */
    public static final class StoreState {
        public Map<String, List<Entry>> sessions = new LinkedHashMap<>();
    }

    private StoreState myState = new StoreState();

    public static SessionCommandTextStore getInstance() {
        return ApplicationManager.getApplication().getService(SessionCommandTextStore.class);
    }

    /**
     * The application service, or {@code null} when there is no application
     * (plain unit tests). Callers treat a missing store as "nothing recorded".
     */
    public static @Nullable SessionCommandTextStore getInstanceOrNull() {
        Application application = ApplicationManager.getApplication();
        return application != null ? application.getService(SessionCommandTextStore.class) : null;
    }

    @Override
    public @Nullable StoreState getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull StoreState state) {
        myState = state != null ? state : new StoreState();
        if (myState.sessions == null) {
            myState.sessions = new LinkedHashMap<>();
        }
    }

    /**
     * Record one command send.
     *
     * @param sessionId the opencode session the command was sent to
     * @param sentAt    wall-clock time the send started (ms since epoch)
     * @param text      exactly what the user typed, e.g. {@code "/review main"}
     */
    public synchronized void record(String sessionId, long sentAt, String text) {
        if (sessionId == null || sessionId.isBlank() || text == null || text.isBlank()) {
            return;
        }
        List<Entry> entries = myState.sessions.computeIfAbsent(sessionId, key -> new ArrayList<>());
        entries.add(new Entry(sentAt, text));
        while (entries.size() > MAX_ENTRIES_PER_SESSION) {
            entries.remove(0);
        }
        while (myState.sessions.size() > MAX_SESSIONS) {
            String victim = null;
            for (String key : myState.sessions.keySet()) {
                if (!key.equals(sessionId)) {
                    victim = key;
                    break;
                }
            }
            if (victim == null) {
                break;
            }
            myState.sessions.remove(victim);
        }
    }

    /** A snapshot of the recorded sends for one session, oldest first. */
    public synchronized List<Entry> entriesFor(String sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        List<Entry> entries = myState.sessions.get(sessionId);
        return entries != null ? new ArrayList<>(entries) : List.of();
    }

    /** Forget one session (e.g. after a full reset). */
    public synchronized void clear(String sessionId) {
        if (sessionId != null) {
            myState.sessions.remove(sessionId);
        }
    }
}
