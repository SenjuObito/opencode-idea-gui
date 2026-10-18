package com.opencodebuddy.util;

import com.intellij.openapi.diagnostic.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * HTML loader.
 * Handles HTML file loading and local library injection.
 */
public class HtmlLoader {

    private static final Logger LOG = Logger.getInstance(HtmlLoader.class);

    /**
     * Only the document prologue can legally contain the real {@code <html>} /
     * {@code <body>} start tags. Everything past this offset is bundle JS, where
     * the same substrings appear inside string literals and must never be
     * rewritten.
     */
    private static final int ROOT_TAG_SEARCH_LIMIT = 64 * 1024;

    /**
     * Theme-injected page shared by every tab in the IDE.
     *
     * The bundled page is ~9.7 MB. Re-reading and re-injecting it per tab means
     * N copies of a ~19 MB UTF-16 string plus N regex scans over the whole
     * document, all of it competing for the EDT. Only the theme-dependent part
     * is cached here; per-tab bootstrap values are injected afterwards.
     */
    private static volatile String cachedThemedHtml;
    private static volatile String cachedThemedKey;

    private final Class<?> resourceClass;

    public HtmlLoader(Class<?> resourceClass) {
        this.resourceClass = resourceClass;
    }

    /**
     * Load the chat interface HTML.
     * @return the HTML content, or fallback HTML if loading fails
     */
    public String loadChatHtml() {
        String themeKey = currentThemeKey();
        String cached = cachedThemedHtml;
        if (cached != null && themeKey.equals(cachedThemedKey)) {
            LOG.debug("Reusing themed chat HTML from cache (" + themeKey + ")");
            return cached;
        }

        try {
            InputStream is = resourceClass.getResourceAsStream("/html/opencode-chat.html");
            if (is != null) {
                String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                is.close();

                if (html.contains("<!-- LOCAL_LIBRARY_INJECTION_POINT -->")) {
                    html = injectLocalLibraries(html);
                } else {
                    LOG.info("Detected bundled modern frontend assets; no additional library injection needed");
                }

                // Inject the IDE theme into the HTML to prevent flash of unstyled content on initial load
                html = injectIdeTheme(html);

                cachedThemedHtml = html;
                cachedThemedKey = themeKey;
                return html;
            }
        } catch (Exception e) {
            LOG.error("Failed to load opencode-chat.html: " + e.getMessage());
        }

        return generateFallbackHtml();
    }

    /** Drop the shared themed page (theme change invalidates it anyway). */
    public static void invalidateCache() {
        cachedThemedHtml = null;
        cachedThemedKey = null;
    }

    private static String currentThemeKey() {
        try {
            boolean isDark = ThemeConfigService.getIdeThemeConfig().get("isDark").getAsBoolean();
            return (isDark ? "dark" : "light") + "|" + ThemeConfigService.getBackgroundColorHex();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Inject the IDE theme into the HTML.
     *
     * Strategy: add inline style attributes directly on the root tags so the
     * background colour is right on the very first CEF frame, plus a theme
     * variable script in &lt;head&gt;.
     *
     * Deliberately does NOT use {@code String.replaceFirst} with a tag pattern:
     * the bundle is a single ~9.7 MB line, so the regex scans the entire
     * document and can match a {@code <body>} fragment embedded in JS source.
     * Instead the real root tags are located with a bounded linear scan.
     */
    private String injectIdeTheme(String html) {
        try {
            boolean isDark = ThemeConfigService.getIdeThemeConfig().get("isDark").getAsBoolean();
            String theme = isDark ? "dark" : "light";
            // Use the unified color values to ensure consistency with Swing component backgrounds
            String bgColor = ThemeConfigService.getBackgroundColorHex();
            String styleAttr = " style=\"background-color:" + bgColor + ";\"";

            html = addStyleToRootTag(html, "html", styleAttr);
            html = addStyleToRootTag(html, "body", styleAttr);

            // Inject a theme variable script after the <head> tag
            String scriptInjection = "\n    <script>window.__INITIAL_IDE_THEME__ = '" + theme + "';</script>";
            int headIndex = html.indexOf("<head>");
            if (headIndex != -1) {
                int insertPos = headIndex + "<head>".length();
                html = html.substring(0, insertPos) + scriptInjection + html.substring(insertPos);
            }

            LOG.info("Successfully injected IDE theme (inline styles): " + theme + ", background: " + bgColor);
        } catch (Exception e) {
            LOG.error("Failed to inject IDE theme: " + e.getMessage(), e);
        }

        return html;
    }

    /**
     * Insert an attribute into the first real {@code <tag ...>} occurrence.
     * Bounded by {@link #ROOT_TAG_SEARCH_LIMIT} so JS string literals deeper in
     * the bundle can never be rewritten by mistake.
     */
    static String addStyleToRootTag(String html, String tag, String styleAttr) {
        int limit = Math.min(html.length(), ROOT_TAG_SEARCH_LIMIT);
        int tagStart = -1;
        int from = 0;
        while (from < limit) {
            int idx = html.indexOf("<" + tag, from);
            if (idx < 0 || idx >= limit) {
                break;
            }
            int afterName = idx + 1 + tag.length();
            if (afterName < html.length()) {
                char c = html.charAt(afterName);
                // `<html>` / `<html lang=...>` / `<body class=...>` — but not `<htmlx`
                if (c == '>' || c == '/' || Character.isWhitespace(c)) {
                    tagStart = idx;
                    break;
                }
            }
            from = idx + 1;
        }
        if (tagStart < 0) {
            return html;
        }
        int tagEnd = html.indexOf('>', tagStart);
        if (tagEnd < 0) {
            return html;
        }
        // Skip a self-closing form, which cannot carry a background style anyway.
        if (tagEnd > tagStart && html.charAt(tagEnd - 1) == '/') {
            return html;
        }
        return html.substring(0, tagEnd) + styleAttr + html.substring(tagEnd);
    }

    /**
     * Inject per-tab provider/model into the HTML so the WebView can prefer
     * the backend values over the global localStorage snapshot.
     *
     * Without this, every tab in a multi-tab setup hydrates from the same
     * localStorage key ("model-selection-state") and clobbers the per-tab
     * provider the backend already applied to the session — see issue #1353.
     *
     * Both arguments may be null/empty. Null/empty values are injected as
     * empty strings; the frontend treats an empty string as "no backend
     * preference" and falls back to localStorage. Only non-empty values
     * override the global localStorage snapshot.
     */
    public String injectInitialTabState(String html, String provider, String model) {
        try {
            String safeProvider = escapeForSingleQuotedJs(provider == null ? "" : provider);
            String safeModel = escapeForSingleQuotedJs(model == null ? "" : model);
            String scriptInjection = "\n    <script>"
                    + "window.__INITIAL_TAB_PROVIDER__ = '" + safeProvider + "';"
                    + "window.__INITIAL_TAB_MODEL__ = '" + safeModel + "';"
                    + "</script>";
            int headIndex = html.indexOf("<head>");
            if (headIndex != -1) {
                int insertPos = headIndex + "<head>".length();
                return html.substring(0, insertPos) + scriptInjection + html.substring(insertPos);
            }
        } catch (Exception e) {
            LOG.error("Failed to inject initial tab state: " + e.getMessage(), e);
        }
        return html;
    }

    /**
     * Inject locally installed DSH preset ids before the frontend bundle starts.
     */
    public String injectInitialDshPresets(String html, List<String> presetIds) {
        try {
            StringBuilder values = new StringBuilder("[");
            if (presetIds != null) {
                boolean first = true;
                for (String presetId : presetIds) {
                    if (presetId == null || presetId.isBlank()) {
                        continue;
                    }
                    if (!first) {
                        values.append(',');
                    }
                    values.append('\'')
                            .append(escapeForSingleQuotedJs(presetId.trim()))
                            .append('\'');
                    first = false;
                }
            }
            values.append(']');
            String scriptInjection = "\n    <script>window.__INITIAL_DSH_PRESETS__ = "
                    + values + ";</script>";
            int headIndex = html.indexOf("<head>");
            if (headIndex != -1) {
                int insertPos = headIndex + "<head>".length();
                return html.substring(0, insertPos) + scriptInjection + html.substring(insertPos);
            }
        } catch (Exception e) {
            LOG.error("Failed to inject initial DSH presets: " + e.getMessage(), e);
        }
        return html;
    }

    /**
     * Marks the Java-owned page context as unavailable before the frontend bundle executes.
     * The active runtime generation is injected later by {@code WebviewInitializer}, immediately
     * before the page-specific bridge becomes visible.
     */
    public String injectPageContextBootstrap(String html) {
        String scriptInjection = "\n    <script>"
                + "window.__CCG_PAGE_GENERATION__ = undefined;"
                + "window.__CCGUI_PAGE_CONTEXT_READY__ = false;"
                + "window.__CCGUI_PAGE_LOAD_KIND__ = undefined;"
                + "window.__CCGUI_RECOVERY_RELOAD__ = undefined;"
                + "window.__CCGUI_RECOVERY_STATE_APPLIED__ = false;"
                + "</script>";
        int headIndex = html.indexOf("<head>");
        if (headIndex == -1) {
            return html;
        }
        int insertPos = headIndex + "<head>".length();
        return html.substring(0, insertPos) + scriptInjection + html.substring(insertPos);
    }

    private static String escapeForSingleQuotedJs(String value) {
        // Restricted set — provider/model IDs only contain safe chars in
        // practice, but a malicious settings.json provider list could carry
        // arbitrary text. Reject the small set that can break out of the
        // single-quoted literal.
        return value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("\u2028", "\\u2028")   // Line separator — string-literal break in pre-ES2019 JS engines
                .replace("\u2029", "\\u2029");  // Paragraph separator — same risk
    }

    /**
     * Generate fallback HTML.
     */
    public String generateFallbackHtml() {
        return "<!DOCTYPE html>" +
            "<html>" +
            "<head>" +
            "<meta charset=\"UTF-8\">" +
            "<title>OpenCode Buddy（Claude or Codex）</title>" +
            "<style>" +
            "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; " +
            "background: #1e1e1e; color: #fff; display: flex; align-items: center; " +
            "justify-content: center; height: 100vh; margin: 0; }" +
            ".error { text-align: center; padding: 40px; }" +
            "h1 { color: #f85149; }" +
            "</style>" +
            "</head>" +
            "<body>" +
            "<div class=\"error\">" +
            "<h1>Failed to load chat interface</h1>" +
            "<p>Please verify that the HTML resource file exists</p>" +
            "</div>" +
            "</body>" +
            "</html>";
    }

    /**
     * Inject local library file contents into the HTML.
     */
    private String injectLocalLibraries(String html) {
        try {
            String reactJs = loadResourceAsString("/libs/react.production.min.js");
            String reactDomJs = loadResourceAsString("/libs/react-dom.production.min.js");
            String babelJs = loadResourceAsString("/libs/babel.min.js");
            String markedJs = loadResourceAsString("/libs/marked.min.js");
            String codiconCss = loadResourceAsString("/libs/codicon.css");

            String fontBase64 = loadResourceAsBase64("/libs/codicon.ttf");
            codiconCss = codiconCss.replaceAll(
                "url\\(\"\\./codicon\\.ttf\\?[^\"]*\"\\)",
                "url(\"data:font/truetype;base64," + fontBase64 + "\")"
            );

            StringBuilder injectedLibs = new StringBuilder();
            injectedLibs.append("\n    <!-- React and related libraries (local versions) -->\n");
            injectedLibs.append("    <script>/* React 18 */\n").append(reactJs).append("\n    </script>\n");
            injectedLibs.append("    <script>/* ReactDOM 18 */\n").append(reactDomJs).append("\n    </script>\n");
            injectedLibs.append("    <script>/* Babel Standalone */\n").append(babelJs).append("\n    </script>\n");
            injectedLibs.append("    <script>/* Marked */\n").append(markedJs).append("\n    </script>\n");
            injectedLibs.append("    <style>/* VS Code Codicons (with embedded font) */\n").append(codiconCss).append("\n    </style>");

            html = html.replace("<!-- LOCAL_LIBRARY_INJECTION_POINT -->", injectedLibs.toString());

            LOG.info("Successfully injected local libraries (React + ReactDOM + Babel + Codicons)");
        } catch (Exception e) {
            LOG.error("Failed to inject local libraries: " + e.getMessage());
        }

        return html;
    }

    /**
     * Load a resource file as a string.
     */
    private String loadResourceAsString(String resourcePath) throws Exception {
        InputStream is = resourceClass.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new Exception("Resource not found: " + resourcePath);
        }
        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        is.close();
        return content;
    }

    /**
     * Load a resource file as a Base64-encoded string.
     */
    private String loadResourceAsBase64(String resourcePath) throws Exception {
        InputStream is = resourceClass.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new Exception("Resource not found: " + resourcePath);
        }
        byte[] bytes = is.readAllBytes();
        is.close();
        return Base64.getEncoder().encodeToString(bytes);
    }
}
