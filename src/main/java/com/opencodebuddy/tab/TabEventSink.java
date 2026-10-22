package com.opencodebuddy.tab;

/**
 * Where a tab's events go.
 *
 * <p>This is the seam between the session machinery (which knows the fields
 * of an event) and the bridge (which knows how to get it to the renderer). The
 * session side publishes named events; the bridge side decides lane, budget
 * and batching.</p>
 */
public interface TabEventSink {

    /** Publish one event. */
    void publish(TabEvent event);
}
