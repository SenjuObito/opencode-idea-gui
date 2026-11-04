package com.opencodebuddy.tab;

/**
 * Per-tab selections. These are intentionally not window globals: changing the
 * model in tab A must never affect tab B, including while A is still waiting
 * for its first canonical session id.
 */
public final class TabSettings {

    public enum TitleSource {
        AUTO,
        USER
    }

    private volatile String provider = "opencode";
    private volatile String model;
    private volatile String permissionMode = "default";
    private volatile String reasoningEffort = "default";
    /**
     * Snapshot of the global "auto open file" setting at the moment this tab
     * was created. The tab owns the value from then on: toggling it in the tab
     * changes only this tab, and later changes to the global setting do not
     * reach it.
     */
    private volatile boolean autoOpenFileEnabled;
    private volatile String title;
    private volatile TitleSource titleSource = TitleSource.AUTO;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        if (provider != null && !provider.isBlank()) {
            this.provider = provider;
        }
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        if (model != null && !model.isBlank()) {
            this.model = model;
        }
    }

    public String getPermissionMode() {
        return permissionMode;
    }

    public void setPermissionMode(String permissionMode) {
        if (permissionMode != null && !permissionMode.isBlank()) {
            this.permissionMode = permissionMode;
        }
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            this.reasoningEffort = reasoningEffort;
        }
    }

    public boolean isAutoOpenFileEnabled() {
        return autoOpenFileEnabled;
    }

    public void setAutoOpenFileEnabled(boolean autoOpenFileEnabled) {
        this.autoOpenFileEnabled = autoOpenFileEnabled;
    }

    public String getTitle() {
        return title;
    }

    public TitleSource getTitleSource() {
        return titleSource;
    }

    /** Apply an auto title. A user-edited title wins until explicitly changed. */
    public synchronized void applyAutoTitle(String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        if (titleSource == TitleSource.USER) {
            return;
        }
        this.title = title;
        this.titleSource = TitleSource.AUTO;
    }

    /** Apply a user title and make it authoritative over later auto titles. */
    public synchronized void applyUserTitle(String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        this.title = title;
        this.titleSource = TitleSource.USER;
    }
}
