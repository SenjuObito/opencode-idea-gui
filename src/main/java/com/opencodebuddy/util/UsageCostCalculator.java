package com.opencodebuddy.util;

import com.google.gson.JsonObject;

/**
 * Calculates estimated per-turn usage cost.
 *
 * <p>OpenCode-only build: model pricing is owned by the model providers and is
 * not bundled with the plugin, so an unknown model yields {@code null} (no cost
 * shown) — a per-turn footer should stay silent rather than guess.</p>
 */
public final class UsageCostCalculator {

    private UsageCostCalculator() {
    }

    public static Double calculateTurnCostUsd(String provider, JsonObject turnUsage, String model) {
        // No bundled pricing table: per-turn cost display stays hidden.
        return null;
    }

    private static long readLong(JsonObject json, String... keys) {
        if (json == null) {
            return 0;
        }
        for (String key : keys) {
            if (json.has(key) && !json.get(key).isJsonNull()) {
                return Math.max(0, json.get(key).getAsLong());
            }
        }
        return 0;
    }
}
