package com.opencodebuddy.ui.toolwindow;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Backwards-compatible name for {@link BridgeScheduler}.
 *
 * <p>The window (and older tests) still construct a {@code WebviewEventQueue};
 * scheduling now lives in the per-tab fair scheduler. Kept as a thin subclass
 * so the public surface did not have to change in the same commit as the
 * scheduling rewrite.</p>
 */
final class WebviewEventQueue<T> extends BridgeScheduler<T> {

    WebviewEventQueue(
            Supplier<T> browserSupplier,
            BooleanSupplier disposedSupplier,
            BiConsumer<T, String> scriptExecutor
    ) {
        super(browserSupplier, disposedSupplier, scriptExecutor);
    }

    WebviewEventQueue(
            Supplier<T> browserSupplier,
            BooleanSupplier disposedSupplier,
            Consumer<Runnable> scheduler,
            BiConsumer<T, String> scriptExecutor
    ) {
        super(browserSupplier, disposedSupplier, scheduler, scriptExecutor);
    }
}
