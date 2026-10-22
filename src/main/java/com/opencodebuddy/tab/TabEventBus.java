package com.opencodebuddy.tab;

/**
 * The bus a tab's events travel through.
 *
 * <p>Everything that leaves the host for the webview goes through a bus: the
 * session callbacks, the stream coalescer, the prompt broker's answers. The bus
 * does not decide anything itself — it hands the event to the sink, which owns
 * the lane and the budget. Keeping the seam explicit is what makes "every
 * session-level event carries its identity" a property of the code rather than
 * a convention.</p>
 */
public final class TabEventBus {

    /** The bridge side of the bus. */
    public interface Sink {
        void publish(TabEvent event);
    }

    private final Sink sink;

    public TabEventBus(Sink sink) {
        this.sink = sink;
    }

    /** Publish an event and return it (handy for tests and logging). */
    public TabEvent publish(TabEvent event) {
        if (event != null && sink != null) {
            sink.publish(event);
        }
        return event;
    }

    /** Build and publish an event in one call. */
    public TabEvent publish(TabEvent.Builder builder) {
        return publish(builder.build());
    }

    /** A sink that drops everything (used where no webview is attached). */
    public static TabEventSink droppingSink() {
        return event -> {
        };
    }
}
