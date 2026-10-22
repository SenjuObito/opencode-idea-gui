package com.opencodebuddy.session;

import com.opencodebuddy.tab.TabEvent;
import com.opencodebuddy.tab.TabEventSink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records the named events a session publishes.
 *
 * <p>The rendered form mirrors the wire format: {@code type:sessionId:field}
 * — enough for the isolation assertions without teaching the tests about the
 * event object.</p>
 */
public final class RecordingEventSink implements TabEventSink {

    public final List<TabEvent> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void publish(TabEvent event) {
        events.add(event);
    }

    /** The events rendered as {@code type:sessionId:payload}. */
    public List<String> calls() {
        List<String> rendered = new ArrayList<>();
        synchronized (events) {
            for (TabEvent event : events) {
                rendered.add(render(event));
            }
        }
        return rendered;
    }

    private static String render(TabEvent event) {
        StringBuilder sb = new StringBuilder(event.getType());
        sb.append(':').append(event.getSessionId() == null ? "" : event.getSessionId());
        for (Object value : event.getPayload().values()) {
            sb.append(':').append(value);
        }
        return sb.toString();
    }
}
