package com.opencodebuddy.session;

/**
 * The editor context at one point in time: the file the user is looking at and,
 * when they selected something, the line range of that selection.
 *
 * <p>This is the single representation of "the current editor context". The
 * context chip in the webview and the prompt sent to opencode are both derived
 * from it, so they cannot disagree about the file or the line range.</p>
 */
public record EditorContextSnapshot(
        String file,
        boolean hasSelection,
        int startLine,
        int endLine
) {

    /** No file is open (or the active file is filtered out). */
    public static final EditorContextSnapshot EMPTY =
            new EditorContextSnapshot(null, false, 0, 0);

    public boolean hasFile() {
        return file != null && !file.isBlank();
    }

    /** A file with no selection: only the file is referenced. */
    public static EditorContextSnapshot file(String file) {
        return new EditorContextSnapshot(file, false, 0, 0);
    }

    /** A file plus the selected line range (1-based, inclusive). */
    public static EditorContextSnapshot selection(String file, int startLine, int endLine) {
        return new EditorContextSnapshot(file, true, startLine, endLine);
    }
}
