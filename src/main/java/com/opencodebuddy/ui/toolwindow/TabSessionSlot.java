package com.opencodebuddy.ui.toolwindow;

import com.intellij.openapi.util.Key;

import javax.swing.JPanel;

/**
 * Metadata and UI container slot for a tab in the tool window.
 * Associates a tab with its Core session and light-weight container panel.
 */
public class TabSessionSlot {

    public static final Key<TabSessionSlot> KEY = Key.create("com.opencodebuddy.TabSessionSlot");

    private final JPanel containerPanel;
    private String tabName;
    private String sessionId;
    private String provider;
    private String model;

    public TabSessionSlot(String tabName, JPanel containerPanel) {
        this.tabName = tabName;
        this.containerPanel = containerPanel;
    }

    public JPanel getContainerPanel() {
        return containerPanel;
    }

    public String getTabName() {
        return tabName;
    }

    public void setTabName(String tabName) {
        this.tabName = tabName;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
