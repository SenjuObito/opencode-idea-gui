package com.opencodebuddy.handler.provider;

import com.opencodebuddy.provider.CustomModelContextWindowProvider;
import com.opencodebuddy.provider.ModelContextWindowCatalog;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

/**
 * Pins the context-window resolution order: explicit user configuration beats
 * live provider metadata, which beats the hardcoded table.
 */
public class ModelProviderHandlerContextLimitTest {

    private static final String PROVIDER = "opencode";

    private ModelContextWindowCatalog previousCatalog;

    @Before
    public void setUp() throws Exception {
        previousCatalog = ModelContextWindowCatalog.getInstance();

        ModelContextWindowCatalog catalog = ModelContextWindowCatalog.createForTests();
        catalog.updateFromModelsPayload(JsonParser.parseString("""
                {
                  "models": [
                    {"id": "deepseek/deepseek-v4-pro", "contextWindow": 1000000},
                    {"id": "vendor/m", "contextWindow": 200000}
                  ]
                }
                """).getAsJsonObject());
        ModelContextWindowCatalog.setInstanceForTests(catalog);

        // No user override unless a test installs one.
        Path missing = Files.createTempDirectory("no-custom-windows").resolve("config.json");
        CustomModelContextWindowProvider.setInstanceForTests(
                CustomModelContextWindowProvider.createForTests(missing));
    }

    @After
    public void tearDown() {
        ModelContextWindowCatalog.setInstanceForTests(previousCatalog);
        CustomModelContextWindowProvider.setInstanceForTests(null);
    }

    @Test
    public void catalogBeatsTheHardcodedFallbackForModelsTheTableNeverHeardOf() {
        // Not in MODEL_CONTEXT_LIMITS — previously always the 200k default.
        assertEquals(1_000_000,
                ModelProviderHandler.getModelContextLimit(PROVIDER, "deepseek/deepseek-v4-pro"));
        assertEquals(1_000_000,
                ModelProviderHandler.getModelContextLimit(PROVIDER, "deepseek-v4-pro"));
    }

    @Test
    public void catalogBeatsTheHardcodedTableForModelsBothKnow() {
        // "gpt-5" is pinned at 400k in the table; live metadata says otherwise.
        ModelContextWindowCatalog catalog = ModelContextWindowCatalog.getInstance();
        catalog.updateFromModelsPayload(JsonParser.parseString(
                "{\"models\": [{\"id\": \"openai/gpt-5\", \"contextWindow\": 500000}]}")
                .getAsJsonObject());

        assertEquals(500_000, ModelProviderHandler.getModelContextLimit(PROVIDER, "openai/gpt-5"));
    }

    @Test
    public void explicitCapacitySuffixBeatsTheCatalog() {
        assertEquals(1_000_000, ModelProviderHandler.getModelContextLimit(PROVIDER, "vendor/m[1m]"));
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit(PROVIDER, "vendor/m[200k]"));
        // The bare id still resolves through the catalog.
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit(PROVIDER, "vendor/m"));
    }

    @Test
    public void userConfigurationBeatsTheCatalog() throws Exception {
        Path config = Files.createTempFile("custom-model-context", ".json");
        Files.writeString(config, """
                {"customModelContextWindows": {"opencode": {"vendor/m": 800000}}}
                """);
        CustomModelContextWindowProvider.setInstanceForTests(
                CustomModelContextWindowProvider.createForTests(config));

        assertEquals(800_000, ModelProviderHandler.getModelContextLimit(PROVIDER, "vendor/m"));
        // Models the user did not pin still come from the catalog.
        assertEquals(1_000_000, ModelProviderHandler.getModelContextLimit(PROVIDER, "deepseek-v4-pro"));
    }

    @Test
    public void unknownModelsKeepTheHistorical200kDefault() {
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit(PROVIDER, "nobody/knows-this"));
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit(PROVIDER, null));
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit(PROVIDER, ""));
    }

    @Test
    public void coldCatalogFallsBackToTheHardcodedTable() {
        ModelContextWindowCatalog.setInstanceForTests(ModelContextWindowCatalog.createForTests());

        assertEquals(400_000, ModelProviderHandler.getModelContextLimit(PROVIDER, "gpt-5"));
        assertEquals(256_000, ModelProviderHandler.getModelContextLimit(PROVIDER, "anthropic/qwen3-coder"));
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit(PROVIDER, "deepseek-v4-pro"));
    }
}
