package com.opencodebuddy.settings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shared utility for provider ordering operations.
 * Used by both ProviderManager and CodexProviderManager.
 */
public final class ProviderOrderHelper {

    private ProviderOrderHelper() {}

    /**
     * Get provider order from config section, or return default order based on keys.
     * @param section the config section (e.g. "claude" or "codex" JsonObject)
     * @param providerKeys the set of provider keys
     * @return ordered list of provider IDs
     */
    public static List<String> getProviderOrder(JsonObject section, Set<String> providerKeys) {
        List<String> order = new ArrayList<>();

        if (section.has("providerOrder") && section.get("providerOrder").isJsonArray()) {
            JsonArray savedOrder = section.getAsJsonArray("providerOrder");
            for (JsonElement e : savedOrder) {
                String id = e.getAsString();
                if (providerKeys.contains(id)) {
                    order.add(id);
                }
            }
        }

        // Add any providers not in the saved order
        for (String key : providerKeys) {
            if (!order.contains(key)) {
                order.add(key);
            }
        }

        return order;
    }

    /**
     * Save provider order into a config section.
     * @param section the config section (e.g. "claude" or "codex" JsonObject)
     * @param orderedIds the ordered list of provider IDs
     */
    public static void setProviderOrder(JsonObject section, List<String> orderedIds) {
        JsonArray orderArray = new JsonArray();
        for (String id : orderedIds) {
            orderArray.add(id);
        }
        section.add("providerOrder", orderArray);
    }

    /**
     * Remove a provider ID from the providerOrder array in a config section.
     * @param section the config section (e.g. "claude" or "codex" JsonObject)
     * @param idToRemove the provider ID to remove
     */
    public static void removeFromOrder(JsonObject section, String idToRemove) {
        if (!section.has("providerOrder") || !section.get("providerOrder").isJsonArray()) {
            return;
        }
        JsonArray oldOrder = section.getAsJsonArray("providerOrder");
        JsonArray newOrder = new JsonArray();
        for (JsonElement e : oldOrder) {
            if (!e.getAsString().equals(idToRemove)) {
                newOrder.add(e);
            }
        }
        section.add("providerOrder", newOrder);
    }
}
