package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.HandlerContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link CliModelsHandler}.
 */
public class CliModelsHandlerTest {

    private static final class RecordingJsCallback implements HandlerContext.JsCallback {
        final List<JsonObject> pushed = new ArrayList<>();

        @Override
        public void callJavaScript(String functionName, String... args) {
            if ("window.setCliModels".equals(functionName) && args.length > 0) {
                pushed.add(JsonParser.parseString(args[0]).getAsJsonObject());
            }
        }

        @Override
        public String escapeJs(String str) {
            return str == null ? "" : str.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
        }
    }

    @Test
    public void getSupportedTypesReturnsGetCliModels() {
        CliModelsHandler handler = new CliModelsHandler(new HandlerContext(null, null, null, new RecordingJsCallback()));
        String[] types = handler.getSupportedTypes();
        assertEquals(1, types.length);
        assertEquals("get_cli_models", types[0]);
    }

    @Test
    public void handlesUnsupportedProviderWithErrorMessage() throws Exception {
        RecordingJsCallback js = new RecordingJsCallback();
        CliModelsHandler handler = new CliModelsHandler(new HandlerContext(null, null, null, js));

        boolean handled = handler.handle("get_cli_models", "unsupported_provider");
        assertTrue(handled);
        assertEquals(1, js.pushed.size());
        JsonObject response = js.pushed.get(0);
        assertFalse(response.get("success").getAsBoolean());
        assertTrue(response.get("error").getAsString().contains("Unsupported CLI provider"));
    }

    @Test
    public void ignoresNonMatchingType() {
        RecordingJsCallback js = new RecordingJsCallback();
        CliModelsHandler handler = new CliModelsHandler(new HandlerContext(null, null, null, js));

        boolean handled = handler.handle("other_type", "opencode");
        assertFalse(handled);
        assertEquals(0, js.pushed.size());
    }
}
