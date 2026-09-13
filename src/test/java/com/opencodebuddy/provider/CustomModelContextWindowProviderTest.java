package com.opencodebuddy.provider;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class CustomModelContextWindowProviderTest {

    @Test
    public void shouldResolveOnlyWholeKOpencodeContextWindowsByExactModelId() throws Exception {
        Path config = Files.createTempFile("custom-model-context", ".json");
        Files.writeString(config, """
                {
                  "customModelContextWindows": {
                    "other": {
                      "shared-model": 500000,
                      "invalid-model": -1
                    },
                    "opencode": {
                      "shared-model": 1000000,
                      "partial-k-model": 500500,
                      "sub-k-model": 500,
                      "fractional-model": 12.5
                    }
                  }
                }
                """);

        CustomModelContextWindowProvider provider = CustomModelContextWindowProvider.createForTests(config);

        assertEquals(500_000, provider.getContextWindow("other", "shared-model").orElseThrow());
        assertEquals(1_000_000, provider.getContextWindow("opencode", "shared-model").orElseThrow());
        assertFalse(provider.getContextWindow("opencode", "shared-model[1m]").isPresent());
        assertFalse(provider.getContextWindow("other", "invalid-model").isPresent());
        assertFalse(provider.getContextWindow("opencode", "partial-k-model").isPresent());
        assertFalse(provider.getContextWindow("opencode", "sub-k-model").isPresent());
        assertFalse(provider.getContextWindow("opencode", "fractional-model").isPresent());
        assertFalse(provider.getContextWindow("opencode", "missing-model").isPresent());
    }
}
