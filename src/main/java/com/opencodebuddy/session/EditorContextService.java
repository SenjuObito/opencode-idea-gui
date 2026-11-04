package com.opencodebuddy.session;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

/**
 * Project-level holder of the current editor context snapshot.
 *
 * <p>There is one focused editor per project, so there is one snapshot. The
 * (window-level) {@code EditorContextTracker} is its only writer: it resolves
 * the file, applies the .gitignore filter and the end-of-line rule once, and
 * publishes the result. Readers — the context chip and the prompt assembly —
 * never touch the editor themselves, so both always see the same value even
 * after focus moved into the chat tool window.</p>
 */
@Service(Service.Level.PROJECT)
public final class EditorContextService {

    private volatile EditorContextSnapshot snapshot = EditorContextSnapshot.EMPTY;

    public static EditorContextService getInstance(Project project) {
        return project == null ? null : project.getService(EditorContextService.class);
    }

    public EditorContextSnapshot getSnapshot() {
        return snapshot;
    }

    public void update(EditorContextSnapshot next) {
        snapshot = next != null ? next : EditorContextSnapshot.EMPTY;
    }
}
