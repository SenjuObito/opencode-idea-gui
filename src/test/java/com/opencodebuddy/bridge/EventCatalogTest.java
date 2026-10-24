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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

        // The adapter publishes named events; every one of them has to be in
        // the catalog, otherwise the webview has no handler for it.
        Pattern call = Pattern.compile("publish\\(\"([a-z][a-z0-9.]*)\"");
        Set<String> types = new LinkedHashSet<>();
        Matcher matcher = call.matcher(Files.readString(adapter, StandardCharsets.UTF_8));
        while (matcher.find()) {
            types.add(matcher.group(1));
        }
        assertFalse("the adapter pushes no event at all", types.isEmpty());

        for (String type : types) {
            assertTrue("the event is not in the catalog: " + type,
                    EventCatalog.catalog().containsKey(type));
        }
    }

    @Test
    public void everyEventTheCoalescerPushesHasASemanticName() throws IOException {
        Path coalescer = projectFile("src/main/java/com/opencodebuddy/session/StreamMessageCoalescer.java");
        assertNotNull("the coalescer source must be readable from the test", coalescer);

        Pattern call = Pattern.compile("publish\\(\"([a-z][a-z0-9.]*)\"");
        Set<String> types = new LinkedHashSet<>();
        Matcher matcher = call.matcher(Files.readString(coalescer, StandardCharsets.UTF_8));
        while (matcher.find()) {
            types.add(matcher.group(1));
        }
        assertFalse("the coalescer pushes no event at all", types.isEmpty());

        for (String type : types) {
            assertTrue("the event is not in the catalog: " + type,
                    EventCatalog.catalog().containsKey(type));
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
    public void everyHostCallJavaScriptHasASemanticName() throws IOException {
        Pattern call = Pattern.compile("callJavaScript\\(\"((?:window\\.)?[A-Za-z_$][A-Za-z0-9_$.]*)\"");
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : (Iterable<Path>) files.filter(path -> path.toString().endsWith(".java"))::iterator) {
                Matcher matcher = call.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    String functionName = matcher.group(1).replaceFirst("^window\\.", "");
                    if (!EventCatalog.catalog().containsValue(functionName)) {
                        missing.add(functionName);
                    }
                }
            }
        }
        assertTrue("these host calls still address a window function outside the catalog: " + missing,
                missing.isEmpty());
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
