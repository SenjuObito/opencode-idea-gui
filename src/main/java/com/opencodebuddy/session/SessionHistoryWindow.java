package com.opencodebuddy.session;

import com.google.gson.JsonObject;

/**
 * How much of a tab's transcript has been read back from the engine, and where
 * the next older page starts.
 *
 * <p>opencode 2.x pages {@code /api/session/{id}/message} (default 50, max 200,
 * cursor based). A tab therefore holds a <em>window</em> of the transcript, not
 * the whole thing: the newest page on open, plus every page the user asked for.
 * The window lives on the session, which is 1:1 with a tab, so two tabs on the
 * same transcript each page independently and a tab switch cannot move one
 * tab's cursor into another.</p>
 *
 * <p>{@code generation} guards late responses: a reload (or a rebind to another
 * session) resets the window and bumps the counter, so an in-flight page for
 * the previous transcript is dropped instead of being prepended to the new
 * one.</p>
 */
public final class SessionHistoryWindow {

    /** Page size the host requests (server max). */
    public static final int DEFAULT_PAGE_SIZE = 200;

    private String sessionId;
    private String earlierCursor;
    private boolean hasEarlier;
    private int loadedCount;
    private int pageSize = DEFAULT_PAGE_SIZE;
    private int loadedTurns;
    private Integer totalTurns;
    private boolean loading;
    /** Bumped whenever the window was replaced; stale responses carry an old one. */
    private int generation;

    public synchronized int getGeneration() {
        return generation;
    }

    public synchronized int getPageSize() {
        return pageSize;
    }

    public synchronized String getEarlierCursor() {
        return earlierCursor;
    }

    public synchronized boolean hasEarlierPages() {
        return hasEarlier;
    }

    public synchronized boolean isLoading() {
        return loading;
    }

    public synchronized boolean isCurrent(int generation) {
        return generation == this.generation;
    }

    /** A whole new transcript replaced the window (open / reload / reset). */
    public synchronized void reset(String sessionId) {
        this.generation += 1;
        this.sessionId = sessionId;
        this.earlierCursor = null;
        this.hasEarlier = false;
        this.loadedCount = 0;
        this.loadedTurns = 0;
        this.totalTurns = null;
        this.loading = false;
    }

    /** The newest page was just loaded and replaced the transcript. */
    public synchronized void applyFirstPage(
            String sessionId,
            int loadedCount,
            int loadedTurns,
            String cursor,
            boolean hasEarlier,
            int pageSize) {
        reset(sessionId);
        if (pageSize > 0) {
            this.pageSize = pageSize;
        }
        this.loadedCount = Math.max(0, loadedCount);
        this.loadedTurns = Math.max(0, loadedTurns);
        this.earlierCursor = (cursor == null || cursor.isBlank()) ? null : cursor;
        this.hasEarlier = hasEarlier && this.earlierCursor != null;
    }

    /**
     * Claim the right to request the next older page.
     *
     * @return the generation to compare with {@link #isCurrent(int)}, or
     *         {@code null} when there is nothing earlier or a request is
     *         already in flight (a second click must not duplicate the page)
     */
    public synchronized Integer beginEarlier() {
        if (!hasEarlier || earlierCursor == null || loading) {
            return null;
        }
        loading = true;
        return generation;
    }

    /** An older page was prepended. Returns false when the claim is stale. */
    public synchronized boolean finishEarlier(
            int generation,
            int loadedCount,
            int loadedTurns,
            String cursor,
            boolean hasEarlier) {
        if (!isCurrent(generation)) {
            return false;
        }
        loading = false;
        this.loadedCount += Math.max(0, loadedCount);
        this.loadedTurns += Math.max(0, loadedTurns);
        this.earlierCursor = (cursor == null || cursor.isBlank()) ? null : cursor;
        this.hasEarlier = hasEarlier && this.earlierCursor != null;
        return true;
    }

    /** The page request failed: release the claim and leave the cursor alone. */
    public synchronized boolean failEarlier(int generation) {
        if (!isCurrent(generation)) {
            return false;
        }
        loading = false;
        return true;
    }

    public synchronized void setTotalTurns(Integer total) {
        this.totalTurns = (total != null && total >= 0) ? total : null;
    }

    /** The snapshot the webview renders ({@code history.window}). */
    public synchronized JsonObject snapshot(String fallbackSessionId) {
        JsonObject json = new JsonObject();
        String id = sessionId != null ? sessionId : fallbackSessionId;
        json.addProperty("sessionId", id);
        json.addProperty("hasEarlier", hasEarlier);
        json.addProperty("earlierCursor", earlierCursor);
        json.addProperty("loadedCount", loadedCount);
        json.addProperty("pageSize", pageSize);
        json.addProperty("loadedTurns", loadedTurns);
        if (totalTurns != null) {
            json.addProperty("totalTurns", totalTurns);
        } else {
            json.add("totalTurns", com.google.gson.JsonNull.INSTANCE);
        }
        json.addProperty("loading", loading);
        return json;
    }
}
