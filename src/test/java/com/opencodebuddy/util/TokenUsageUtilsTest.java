package com.opencodebuddy.util;

import com.opencodebuddy.session.ClaudeSession;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for provider-aware token extraction and context-snapshot lifecycle.
 */
public class TokenUsageUtilsTest {

    /**
     * Verifies Claude context usage includes all input-side cache categories but excludes output.
     */
    @Test
    public void contextTokensExcludeOutputTokens() {
        JsonObject usage = new JsonObject();
        usage.addProperty("input_tokens", 180000);
        usage.addProperty("cache_creation_input_tokens", 12000);
        usage.addProperty("cache_read_input_tokens", 160000);
        usage.addProperty("output_tokens", 2400);

        assertEquals(352000, TokenUsageUtils.extractContextTokens(usage, "claude"));
    }

    /**
     * Verifies Codex context usage uses the provider's cache-inclusive input count only.
     */
    @Test
    public void codexContextTokensUseInputOnly() {
        JsonObject usage = new JsonObject();
        usage.addProperty("input_tokens", 180000);
        usage.addProperty("output_tokens", 2400);
        usage.addProperty("cached_input_tokens", 160000);

        assertEquals(180000, TokenUsageUtils.extractContextTokens(usage, "codex"));
    }

    /**
     * Verifies Codex lookup prefers the root current-context snapshot over nested historical usage.
     */
    @Test
    public void codexPrefersTopLevelContextUsageOverNestedHistoricalUsage() {
        JsonObject nestedUsage = new JsonObject();
        nestedUsage.addProperty("input_tokens", 22496533);
        JsonObject message = new JsonObject();
        message.add("usage", nestedUsage);

        JsonObject currentUsage = new JsonObject();
        currentUsage.addProperty("input_tokens", 127886);
        JsonObject raw = new JsonObject();
        raw.add("message", message);
        raw.add("usage", currentUsage);

        ClaudeSession.Message assistant = new ClaudeSession.Message(
                ClaudeSession.Message.Type.ASSISTANT,
                "",
                raw
        );

        assertEquals(
                127886,
                TokenUsageUtils.findLastUsageFromSessionMessages(List.of(assistant), "codex")
                        .get("input_tokens").getAsInt()
        );
        assertEquals(
                22496533,
                TokenUsageUtils.findLastUsageFromSessionMessages(List.of(assistant))
                        .get("input_tokens").getAsInt()
        );
    }

    /**
     * Verifies a provider-reported context window overrides static configuration while malformed
     * or missing metadata retains the supplied fallback.
     */
    @Test
    public void extractMaxTokensPrefersTrustedProviderWindow() {
        JsonObject usage = new JsonObject();
        usage.addProperty("model_context_window", 258400);

        assertEquals(258400, TokenUsageUtils.extractMaxTokens(usage, 1_050_000));
        usage.addProperty("model_context_window", -1);
        assertEquals(1_050_000, TokenUsageUtils.extractMaxTokens(usage, 1_050_000));
        assertEquals(0, TokenUsageUtils.extractMaxTokens(null, -1));
    }

    /**
     * Verifies selection changes remove both supported context usage locations without
     * deleting historical per-turn usage or cost metadata.
     */
    @Test
    public void clearContextUsagePreservesTurnAccounting() {
        JsonObject raw = new JsonObject();
        raw.add("usage", usage(12000));
        raw.add("turnUsage", usage(345));
        raw.addProperty("turnCostUsd", 0.42);
        JsonObject nestedMessage = new JsonObject();
        nestedMessage.add("usage", usage(9000));
        raw.add("message", nestedMessage);

        ClaudeSession.Message assistant = new ClaudeSession.Message(
                ClaudeSession.Message.Type.ASSISTANT, "answer", raw);

        TokenUsageUtils.clearContextUsageFromSessionMessages(List.of(assistant));

        assertFalse(raw.has("usage"));
        assertFalse(nestedMessage.has("usage"));
        assertTrue(raw.has("turnUsage"));
        assertTrue(raw.has("turnCostUsd"));
    }

    /**
     * Verifies OpenCode nested tokens shape correctly calculates context tokens including cache.
     */
    @Test
    public void opencodeContextTokensIncludeNestedCache() {
        JsonObject usage = new JsonObject();
        JsonObject tokens = new JsonObject();
        tokens.addProperty("input", 350);
        tokens.addProperty("output", 120);
        tokens.addProperty("reasoning", 0);
        JsonObject cache = new JsonObject();
        cache.addProperty("read", 45000);
        cache.addProperty("write", 2000);
        tokens.add("cache", cache);
        usage.add("tokens", tokens);

        assertEquals(47350, TokenUsageUtils.extractContextTokens(usage, "opencode"));
    }

    /**
     * Verifies findLastUsageFromSessionMessages finds OpenCode turnUsage and tokens from raw message.
     */
    @Test
    public void opencodeFindLastUsagePrefersTurnUsageAndTokens() {
        JsonObject raw1 = new JsonObject();
        JsonObject tokens = new JsonObject();
        tokens.addProperty("input", 150);
        JsonObject cache1 = new JsonObject();
        cache1.addProperty("read", 5000);
        tokens.add("cache", cache1);
        raw1.add("tokens", tokens);

        ClaudeSession.Message assistant1 = new ClaudeSession.Message(
                ClaudeSession.Message.Type.ASSISTANT, "first", raw1);

        JsonObject raw2 = new JsonObject();
        JsonObject turnUsage = new JsonObject();
        turnUsage.addProperty("input_tokens", 250);
        turnUsage.addProperty("cache_read_input_tokens", 18000);
        turnUsage.addProperty("cache_creation_input_tokens", 800);
        raw2.add("turnUsage", turnUsage);

        ClaudeSession.Message assistant2 = new ClaudeSession.Message(
                ClaudeSession.Message.Type.ASSISTANT, "second", raw2);

        JsonObject found = TokenUsageUtils.findLastUsageFromSessionMessages(
                List.of(assistant1, assistant2), "opencode");

        assertEquals(turnUsage, found);
        assertEquals(19050, TokenUsageUtils.extractContextTokens(found, "opencode"));
    }

    private static JsonObject usage(int inputTokens) {
        JsonObject usage = new JsonObject();
        usage.addProperty("input_tokens", inputTokens);
        return usage;
    }
}
