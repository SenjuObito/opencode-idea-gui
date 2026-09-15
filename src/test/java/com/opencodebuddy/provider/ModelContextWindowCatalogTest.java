package com.opencodebuddy.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelContextWindowCatalogTest {

    private static JsonObject payload(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void resolvesQualifiedAndBareModelIds() {
        ModelContextWindowCatalog catalog = ModelContextWindowCatalog.createForTests();
        assertTrue(catalog.updateFromModelsPayload(payload("""
                {
                  "success": true,
                  "provider": "opencode",
                  "models": [
                    {"id": "deepseek/deepseek-v4-pro", "label": "Deepseek-V4-Pro", "contextWindow": 1000000},
                    {"id": "opencode/big-pickle", "label": "Big-Pickle", "contextWindow": 200000}
                  ]
                }
                """)));

        assertEquals(1_000_000, catalog.getContextWindow("deepseek/deepseek-v4-pro").orElseThrow());
        assertEquals(1_000_000, catalog.getContextWindow("deepseek-v4-pro").orElseThrow());
        assertEquals(200_000, catalog.getContextWindow("opencode/big-pickle").orElseThrow());
    }

    @Test
    public void ignoresEntriesWithoutAUsableContextWindow() {
        ModelContextWindowCatalog catalog = ModelContextWindowCatalog.createForTests();
        assertFalse(catalog.updateFromModelsPayload(payload("""
                {
                  "models": [
                    {"id": "no-limit", "label": "No Limit"},
                    {"id": "zero", "contextWindow": 0},
                    {"id": "negative", "contextWindow": -1},
                    {"id": "textual", "contextWindow": "nope"},
                    {"id": "fractional", "contextWindow": 1.5},
                    {"label": "no id", "contextWindow": 1000000}
                  ]
                }
                """)));

        assertFalse(catalog.getContextWindow("no-limit").isPresent());
        assertFalse(catalog.getContextWindow("zero").isPresent());
        assertFalse(catalog.getContextWindow("negative").isPresent());
        assertFalse(catalog.getContextWindow("textual").isPresent());
        assertFalse(catalog.getContextWindow("fractional").isPresent());
        assertFalse(catalog.getContextWindow("no id").isPresent());
    }

    @Test
    public void answersEmptyForMissingOrBlankIds() {
        ModelContextWindowCatalog catalog = ModelContextWindowCatalog.createForTests();
        catalog.updateFromModelsPayload(payload(
                "{\"models\": [{\"id\": \"m\", \"contextWindow\": 1000000}]}"));

        assertFalse(catalog.getContextWindow(null).isPresent());
        assertFalse(catalog.getContextWindow("").isPresent());
        assertFalse(catalog.getContextWindow("   ").isPresent());
        assertEquals(1_000_000, catalog.getContextWindow("  m  ").orElseThrow());
        // A trailing slash has no bare id to fall back to.
        assertFalse(catalog.getContextWindow("vendor/").isPresent());
    }

    @Test
    public void reportsOnlyRealChanges() {
        ModelContextWindowCatalog catalog = ModelContextWindowCatalog.createForTests();
        JsonObject first = payload(
                "{\"models\": [{\"id\": \"vendor/m\", \"contextWindow\": 200000}]}");

        assertTrue(catalog.updateFromModelsPayload(first));
        // Same payload again teaches nothing new.
        assertFalse(catalog.updateFromModelsPayload(first));

        JsonObject corrected = payload(
                "{\"models\": [{\"id\": \"vendor/m\", \"contextWindow\": 1000000}]}");
        assertTrue(catalog.updateFromModelsPayload(corrected));
        assertEquals(1_000_000, catalog.getContextWindow("vendor/m").orElseThrow());
    }

    @Test
    public void keepsEarlierKnowledgeWhenALaterPayloadHasNoLimits() {
        ModelContextWindowCatalog catalog = ModelContextWindowCatalog.createForTests();
        catalog.updateFromModelsPayload(payload(
                "{\"models\": [{\"id\": \"vendor/m\", \"contextWindow\": 1000000}]}"));

        // The legacy `opencode models` fallback carries no window — it must not
        // erase what the richer SDK payload already taught us.
        assertFalse(catalog.updateFromModelsPayload(payload(
                "{\"models\": [{\"id\": \"vendor/m\", \"label\": \"M\"}]}")));
        assertEquals(1_000_000, catalog.getContextWindow("vendor/m").orElseThrow());
    }

    @Test
    public void toleratesMalformedPayloads() {
        ModelContextWindowCatalog catalog = ModelContextWindowCatalog.createForTests();

        assertFalse(catalog.updateFromModelsPayload(null));
        assertFalse(catalog.updateFromModelsPayload(payload("{}")));
        assertFalse(catalog.updateFromModelsPayload(payload("{\"models\": {}}")));
        assertFalse(catalog.updateFromModelsPayload(payload("{\"models\": [null, 1, \"x\"]}")));
    }

    @Test
    public void doesNotResolveExplicitCapacitySuffixes() {
        ModelContextWindowCatalog catalog = ModelContextWindowCatalog.createForTests();
        catalog.updateFromModelsPayload(payload(
                "{\"models\": [{\"id\": \"vendor/m\", \"contextWindow\": 200000}]}"));

        // `[1m]` is an explicit user choice; the suffix parser must win, so the
        // catalog has to stay out of the way for suffixed ids.
        assertFalse(catalog.getContextWindow("vendor/m[1m]").isPresent());
    }
}
