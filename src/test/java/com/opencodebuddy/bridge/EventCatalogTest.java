package com.opencodebuddy.bridge;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The semantic event catalog.
 *
 * <p>Three things have to hold: every event the host can push has a semantic
 * name, the name is stable, and the webview resolves the same names. The last
 * one is checked against the webview's own table, so the two sides cannot
 * drift apart silently.</p>
 */
public class EventCatalogTest {

    private static Path projectFile(String relative) {
        Path path = Path.of(relative);
        return Files.exists(path) ? path : null;
    }

    @Test
    public void everyEventTheSessionAdapterPushesHasASemanticName() throws IOException {
        Path adapter = projectFile("src/main/java/com/opencodebuddy/session/SessionCallbackAdapter.java");
        assertNotNull("the adapter source must be readable from the test", adapter);

        Pattern call = Pattern.compile("callJavaScript\\(\"([A-Za-z0-9_.]+)\"");
        Set<String> functions = new LinkedHashSet<>();
        Matcher matcher = call.matcher(Files.readString(adapter, StandardCharsets.UTF_8));
        while (matcher.find()) {
            functions.add(matcher.group(1));
        }
        assertFalse("the adapter pushes no event at all", functions.isEmpty());

        for (String function : functions) {
            String type = EventCatalog.semanticType(function);
            assertFalse("the event has no semantic name: " + function,
                    type.equals(function) && !EventCatalog.catalog().containsValue(function));
        }
    }

    @Test
    public void theWebviewResolvesTheSameNames() throws IOException {
        Path table = projectFile("webview/src/utils/semanticEvents.ts");
        assertNotNull("the webview catalog must be readable from the test", table);

        Map<String, String> webview = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("'([a-z][a-z0-9.]*)':\\s*'([A-Za-z0-9_]+)'")
                .matcher(Files.readString(table, StandardCharsets.UTF_8));
        while (matcher.find()) {
            webview.put(matcher.group(1), matcher.group(2));
        }
        assertFalse("the webview catalog is empty", webview.isEmpty());

        assertEquals("the host and the webview disagree about the catalog",
                EventCatalog.catalog(), webview);
    }

    @Test
    public void semanticNamesAreStable() {
        assertEquals("content.delta", EventCatalog.semanticType("onContentDelta"));
        assertEquals("message.snapshot", EventCatalog.semanticType("updateMessages"));
        assertEquals("turn.ended", EventCatalog.semanticType("onStreamEnd"));
        assertEquals("permission.requested", EventCatalog.semanticType("showPermissionDialog"));
        // The host sends both forms for some calls.
        assertEquals("todos.updated", EventCatalog.semanticType("window.onTodoUpdated"));
        // An event outside the catalog keeps its name.
        assertEquals("somethingNew", EventCatalog.semanticType("somethingNew"));
    }

    @Test
    public void theCatalogCanBeLookedUpBothWays() {
        assertEquals("onContentDelta", EventCatalog.functionName("content.delta"));
        assertEquals(null, EventCatalog.functionName("does.not.exist"));
        assertTrue(EventCatalog.size() >= 30);
    }
}
