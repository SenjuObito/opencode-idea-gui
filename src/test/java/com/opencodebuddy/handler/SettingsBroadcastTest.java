package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.settings.CodemossSettingsService;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SettingsBroadcastTest {

    @Test
    public void testBroadcastToAllCallsJsCallback() {
        List<String> calls = new ArrayList<>();
        HandlerContext context = new HandlerContext(
                null,
                null,
                new CodemossSettingsService(),
                new HandlerContext.JsCallback() {
                    @Override
                    public void callJavaScript(String functionName, String... args) {
                        calls.add(functionName + "(" + String.join(",", args) + ")");
                    }

                    @Override
                    public String escapeJs(String str) {
                        return str;
                    }
                }
        );

        context.broadcastToAll("window.applyUiPreferences", "{\"theme\":\"dark\"}");

        assertEquals(1, calls.size());
        assertEquals("window.applyUiPreferences({\"theme\":\"dark\"})", calls.get(0));
    }

    @Test
    public void testBroadcastToProjectCallsJsCallback() {
        List<String> calls = new ArrayList<>();
        HandlerContext context = new HandlerContext(
                null,
                null,
                new CodemossSettingsService(),
                new HandlerContext.JsCallback() {
                    @Override
                    public void callJavaScript(String functionName, String... args) {
                        calls.add(functionName + "(" + String.join(",", args) + ")");
                    }

                    @Override
                    public String escapeJs(String str) {
                        return str;
                    }
                }
        );

        context.broadcastToProject("window.updateWorkingDirectory", "/test/path");

        assertEquals(1, calls.size());
        assertEquals("window.updateWorkingDirectory(/test/path)", calls.get(0));
    }
}
