package com.opencodebuddy.util;

import com.opencodebuddy.settings.OpenCodeBuddySettingsService;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsListener;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.FontPreferences;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ide.ui.UISettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import javax.swing.UIManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Font configuration service.
 * Retrieves the editor font configuration from IDEA and provides it to the Webview.
 */
public class FontConfigService {

    private static final Logger LOG = Logger.getInstance(FontConfigService.class);
    public static final String UI_FONT_MODE_FOLLOW_EDITOR = "followEditor";
    public static final String UI_FONT_MODE_NAMED = "named";
    public static final String UI_FONT_MODE_CUSTOM_FILE = "customFile";
    public static final String UI_FONT_WARNING_CUSTOM_UNAVAILABLE = "fontUnavailable";

    private static final String UI_FONT_CUSTOM_FAMILY = "OpenCode Buddy UI Custom";
    private static final String CODE_FONT_CUSTOM_FAMILY = "OpenCode Buddy Code Custom";

    /**
     * Get the IDEA editor font configuration.
     *
     * @return a JsonObject containing the font configuration
     */
    public static JsonObject getEditorFontConfig() {
        JsonObject config = new JsonObject();

        try {
            EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();

            if (scheme != null) {
                // Get font preferences, including the primary font and fallback fonts
                FontPreferences fontPreferences = scheme.getFontPreferences();

                // Primary font name
                String fontName = scheme.getEditorFontName();
                int fontSize = scheme.getEditorFontSize();
                float lineSpacing = scheme.getLineSpacing();

                config.addProperty("fontFamily", fontName);
                config.addProperty("fontSize", fontSize);
                config.addProperty("lineSpacing", lineSpacing);

                // Get the fallback font list
                List<String> effectiveFontFamilies = fontPreferences.getEffectiveFontFamilies();
                JsonArray fallbackFonts = new JsonArray();

                // Skip the first entry (primary font), add the remaining fallback fonts
                for (int i = 1; i < effectiveFontFamilies.size(); i++) {
                    fallbackFonts.add(effectiveFontFamilies.get(i));
                }
                config.add("fallbackFonts", fallbackFonts);

                LOG.info("[FontConfig] Retrieved IDEA font config: fontFamily=" + fontName
                        + ", fontSize=" + fontSize
                        + ", lineSpacing=" + lineSpacing
                        + ", fallbackFonts=" + fallbackFonts);
            } else {
                // Use default values
                config.addProperty("fontFamily", "JetBrains Mono");
                config.addProperty("fontSize", 14);
                config.addProperty("lineSpacing", 1.2f);
                config.add("fallbackFonts", new JsonArray());
                LOG.warn("[FontConfig] Could not get EditorColorsScheme, using defaults");
            }
        } catch (Exception e) {
            // Fall back to default values on exception
            config.addProperty("fontFamily", "JetBrains Mono");
            config.addProperty("fontSize", 14);
            config.addProperty("lineSpacing", 1.2f);
            config.add("fallbackFonts", new JsonArray());
            LOG.error("[FontConfig] Failed to get font config: " + e.getMessage(), e);
        }

        return config;
    }

    /**
     * Get the IDEA UI font configuration.
     *
     * @return a JsonObject containing the UI font configuration
     */
    public static JsonObject getUiSourceFontConfig() {
        JsonObject config = new JsonObject();

        try {
            // Read the UI font statically (no Swing component construction) so this stays
            // safe to call off the EDT. UIManager.getFont returning null is virtually
            // impossible in a running IDE; the ternaries below fall back to a sane default
            // if it ever does.
            java.awt.Font uiFont = UIManager.getFont("Label.font");

            String fontName = uiFont != null ? uiFont.getFamily() : "Dialog";
            int fontSize = uiFont != null ? uiFont.getSize() : 13;

            config.addProperty("fontFamily", fontName);
            config.addProperty("fontSize", fontSize);
            config.addProperty("lineSpacing", 1.0f);
            config.add("fallbackFonts", new JsonArray());

            LOG.info("[FontConfig] Retrieved IDEA UI font config: fontFamily=" + fontName
                    + ", fontSize=" + fontSize);
        } catch (Exception e) {
            config.addProperty("fontFamily", "Inter");
            config.addProperty("fontSize", 13);
            config.addProperty("lineSpacing", 1.0f);
            config.add("fallbackFonts", new JsonArray());
            LOG.warn("[FontConfig] Failed to get UI font config, using defaults: " + e.getMessage());
        }

        return config;
    }

    /**
     * Resolve the effective UI font config using persisted user preference and current editor typography.
     *
     * @param persistedConfig persisted UI font configuration
     * @param uiFontConfig raw IDEA UI typography
     * @return effective UI font payload for frontend application
     */
    public static JsonObject resolveUiFontConfig(JsonObject persistedConfig, JsonObject uiFontConfig) {
        return resolveFontConfig(
                persistedConfig,
                uiFontConfig,
                UI_FONT_CUSTOM_FAMILY,
                "IDEA UI font"
        );
    }

    /**
     * Resolve the effective code font config using persisted user preference and current editor typography.
     *
     * @param persistedConfig persisted code font configuration
     * @param editorFontConfig raw IDEA editor typography
     * @return effective code font payload for frontend application
     */
    public static JsonObject resolveCodeFontConfig(JsonObject persistedConfig, JsonObject editorFontConfig) {
        return resolveFontConfig(
                persistedConfig,
                editorFontConfig,
                CODE_FONT_CUSTOM_FAMILY,
                "editor font"
        );
    }

    /**
     * Resolve the effective UI font config using persisted settings and live editor typography.
     *
     * @param settingsService settings facade
     * @return effective UI font payload
     */
    public static JsonObject getResolvedUiFontConfig(OpenCodeBuddySettingsService settingsService) {
        try {
            JsonObject persistedConfig = settingsService.getUiFontConfig();
            return resolveUiFontConfig(persistedConfig, getUiSourceFontConfig());
        } catch (Exception e) {
            LOG.error("[FontConfig] Failed to resolve UI font config: " + e.getMessage(), e);
            return resolveUiFontConfig(null, getUiSourceFontConfig());
        }
    }

    /**
     * Resolve the effective code font config and serialize it for the frontend.
     *
     * @param settingsService settings facade
     * @return effective code font payload
     */
    public static JsonObject getResolvedCodeFontConfig(OpenCodeBuddySettingsService settingsService) {
        try {
            JsonObject persistedConfig = settingsService.getCodeFontConfig();
            return resolveCodeFontConfig(persistedConfig, getEditorFontConfig());
        } catch (Exception e) {
            LOG.error("[FontConfig] Failed to resolve code font config: " + e.getMessage(), e);
            return resolveCodeFontConfig(null, getEditorFontConfig());
        }
    }

    /**
     * Resolve the effective UI font config and serialize it for JavaScript injection.
     *
     * @param settingsService settings facade
     * @return serialized effective UI font payload
     */
    public static String getResolvedUiFontConfigJson(OpenCodeBuddySettingsService settingsService) {
        return getResolvedUiFontConfig(settingsService).toString();
    }

    /**
     * Resolve the effective code font config and serialize it for JavaScript injection.
     *
     * @param settingsService settings facade
     * @return serialized effective code font payload
     */
    public static String getResolvedCodeFontConfigJson(OpenCodeBuddySettingsService settingsService) {
        return getResolvedCodeFontConfig(settingsService).toString();
    }

    /**
     * Validate a custom UI font file path.
     *
     * @param path custom font path
     * @return validation result
     */
    public static ValidationResult validateCustomUiFontFile(String path) {
        if (path == null || path.trim().isEmpty()) {
            return new ValidationResult(false, "Font path is empty", null);
        }

        String normalizedPath = path.trim();
        String lowerPath = normalizedPath.toLowerCase();
        if (!lowerPath.endsWith(".ttf") && !lowerPath.endsWith(".otf")) {
            return new ValidationResult(false, "Only TTF and OTF font files are supported", null);
        }

        File file = new File(normalizedPath);
        if (!file.exists() || !file.isFile()) {
            return new ValidationResult(false, "Font file does not exist", null);
        }

        // Resolve symlinks and relative segments to prevent path traversal
        String canonicalPath;
        try {
            canonicalPath = file.getCanonicalPath();
        } catch (Exception e) {
            return new ValidationResult(false, "Cannot resolve font file path", null);
        }
        String canonicalLower = canonicalPath.toLowerCase();
        if (!canonicalLower.endsWith(".ttf") && !canonicalLower.endsWith(".otf")) {
            return new ValidationResult(false, "Only TTF and OTF font files are supported", null);
        }

        File canonicalFile = new File(canonicalPath);
        if (!canonicalFile.canRead()) {
            return new ValidationResult(false, "Font file is not readable", null);
        }
        if (canonicalFile.length() <= 0) {
            return new ValidationResult(false, "Font file is empty", null);
        }
        if (!looksLikeFontFile(canonicalFile)) {
            return new ValidationResult(false, "File does not appear to be a valid font file", null);
        }

        return new ValidationResult(true, null, null);
    }

    /**
     * Lightweight magic-number sniff for common font formats.
     * Avoids the cost of {@code Font.createFont} full parsing while still rejecting
     * obviously bogus files (random binaries, text files renamed to .ttf, etc.).
     */
    private static boolean looksLikeFontFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[4];
            if (fis.read(header) < 4) {
                return false;
            }
            int magic = ((header[0] & 0xFF) << 24)
                      | ((header[1] & 0xFF) << 16)
                      | ((header[2] & 0xFF) << 8)
                      | (header[3] & 0xFF);
            return magic == 0x00010000  // TTF (TrueType)
                || magic == 0x4F54544F  // 'OTTO' (OpenType with CFF)
                || magic == 0x774F4646  // 'wOFF' (WOFF)
                || magic == 0x774F4632  // 'wOF2' (WOFF2)
                || magic == 0x74746366  // 'ttcf' (TrueType Collection)
                || magic == 0x74727565; // 'true' (legacy Apple TrueType)
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Get the font configuration as a JSON string.
     *
     * @return the JSON string
     */
    public static String getEditorFontConfigJson() {
        return getEditorFontConfig().toString();
    }

    /**
     * Get the editor font name.
     *
     * @return the font name
     */
    public static String getEditorFontName() {
        try {
            EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
            if (scheme != null) {
                return scheme.getEditorFontName();
            }
        } catch (Exception e) {
            LOG.error("[FontConfig] Failed to get font name: " + e.getMessage());
        }
        return "JetBrains Mono";
    }

    /**
     * Get the editor font size.
     *
     * @return the font size
     */
    public static int getEditorFontSize() {
        try {
            EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
            if (scheme != null) {
                return scheme.getEditorFontSize();
            }
        } catch (Exception e) {
            LOG.error("[FontConfig] Failed to get font size: " + e.getMessage());
        }
        return 14;
    }

    /**
     * Get the editor line spacing.
     *
     * @return the line spacing
     */
    public static float getEditorLineSpacing() {
        try {
            EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
            if (scheme != null) {
                return scheme.getLineSpacing();
            }
        } catch (Exception e) {
            LOG.error("[FontConfig] Failed to get line spacing: " + e.getMessage());
        }
        return 1.2f;
    }

    public static List<String> getSystemFontFamilies() {
        try {
            String[] names = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
            List<String> list = new ArrayList<>();
            for (String name : names) {
                if (name != null && !name.trim().isEmpty() && !name.startsWith(".")) {
                    list.add(name.trim());
                }
            }
            Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
            return list;
        } catch (Exception e) {
            LOG.warn("[FontConfig] Failed to get system font family names: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    public static JsonObject getSystemFontListJson() {
        JsonObject result = new JsonObject();
        JsonArray array = new JsonArray();
        for (String font : getSystemFontFamilies()) {
            array.add(font);
        }
        result.add("fonts", array);
        result.addProperty("source", "host");
        return result;
    }

    private static JsonObject resolveFontConfig(
            JsonObject persistedConfig,
            JsonObject sourceFontConfig,
            String customFamily,
            String unavailableSourceLabel
    ) {
        // Defensive only: both callers (resolveUiFontConfig / resolveCodeFontConfig) always pass a
        // non-null source, so this editor-font fallback is never hit in practice. It is kept as a
        // last-resort default rather than the UI source to avoid an NPE if a future caller passes null.
        JsonObject normalizedSourceConfig = sourceFontConfig != null ? sourceFontConfig.deepCopy() : getEditorFontConfig();
        JsonObject normalizedPersistedConfig = normalizePersistedFontConfig(persistedConfig);

        String requestedMode = normalizedPersistedConfig.get("mode").getAsString();
        String sourceFontFamily = normalizedSourceConfig.get("fontFamily").getAsString();

        JsonObject resolvedConfig = new JsonObject();
        resolvedConfig.addProperty("mode", requestedMode);
        if (normalizedPersistedConfig.has("customFontPath")) {
            resolvedConfig.addProperty("customFontPath", normalizedPersistedConfig.get("customFontPath").getAsString());
        }
        if (normalizedPersistedConfig.has("fontFamily")) {
            resolvedConfig.addProperty("fontFamily", normalizedPersistedConfig.get("fontFamily").getAsString());
        }
        resolvedConfig.addProperty("fontSize", normalizedSourceConfig.get("fontSize").getAsInt());
        resolvedConfig.addProperty("lineSpacing", normalizedSourceConfig.get("lineSpacing").getAsFloat());
        resolvedConfig.add("fallbackFonts", normalizedSourceConfig.getAsJsonArray("fallbackFonts"));

        String effectiveMode = UI_FONT_MODE_FOLLOW_EDITOR;
        String resolvedFontFamily = sourceFontFamily;
        String resolvedDisplayName = sourceFontFamily;
        String fontUrl = null;
        String fontFormat = null;
        String warning = null;
        String warningCode = null;

        if (UI_FONT_MODE_NAMED.equals(requestedMode)) {
            String namedFamily = normalizedPersistedConfig.has("fontFamily")
                    ? normalizedPersistedConfig.get("fontFamily").getAsString()
                    : null;
            if (namedFamily != null && !namedFamily.trim().isEmpty()) {
                resolvedFontFamily = namedFamily.trim();
                resolvedDisplayName = namedFamily.trim();
                effectiveMode = UI_FONT_MODE_NAMED;
            }
        } else if (UI_FONT_MODE_CUSTOM_FILE.equals(requestedMode)) {
            String customFontPath = normalizedPersistedConfig.has("customFontPath")
                    ? normalizedPersistedConfig.get("customFontPath").getAsString()
                    : null;
            ValidationResult validation = validateCustomUiFontFile(customFontPath);
            if (validation.valid()) {
                try {
                    UiFontResourceService.FontResource resource =
                            UiFontResourceService.registerFontFile(new File(customFontPath));
                    resolvedFontFamily = customFamily;
                    resolvedDisplayName = validation.familyName() != null
                            ? validation.familyName()
                            : extractFileName(customFontPath);
                    fontUrl = resource.url();
                    fontFormat = resource.fontFormat();
                    effectiveMode = UI_FONT_MODE_CUSTOM_FILE;
                } catch (Exception e) {
                    warning = "Font unavailable, currently using " + unavailableSourceLabel;
                    warningCode = UI_FONT_WARNING_CUSTOM_UNAVAILABLE;
                    LOG.warn("[FontConfig] Failed to read custom font " + customFontPath + ": " + e.getMessage(), e);
                }
            } else {
                warning = "Font unavailable, currently using " + unavailableSourceLabel;
                warningCode = UI_FONT_WARNING_CUSTOM_UNAVAILABLE;
            }
        }

        resolvedConfig.addProperty("effectiveMode", effectiveMode);
        resolvedConfig.addProperty("fontFamily", resolvedFontFamily);
        resolvedConfig.addProperty("displayName", resolvedDisplayName);
        if (fontUrl != null) {
            resolvedConfig.addProperty("fontUrl", fontUrl);
            resolvedConfig.addProperty("fontFormat", fontFormat);
        }
        if (warning != null) {
            resolvedConfig.addProperty("warning", warning);
        }
        if (warningCode != null) {
            resolvedConfig.addProperty("warningCode", warningCode);
        }

        return resolvedConfig;
    }

    private static JsonObject normalizePersistedFontConfig(JsonObject persistedConfig) {
        JsonObject normalized = new JsonObject();
        String mode = persistedConfig != null
                && persistedConfig.has("mode")
                && !persistedConfig.get("mode").isJsonNull()
                ? persistedConfig.get("mode").getAsString()
                : UI_FONT_MODE_FOLLOW_EDITOR;
        if (!UI_FONT_MODE_FOLLOW_EDITOR.equals(mode)
                && !UI_FONT_MODE_NAMED.equals(mode)
                && !UI_FONT_MODE_CUSTOM_FILE.equals(mode)) {
            mode = UI_FONT_MODE_FOLLOW_EDITOR;
        }

        if (UI_FONT_MODE_NAMED.equals(mode)) {
            String namedFamily = persistedConfig != null
                    && persistedConfig.has("fontFamily")
                    && !persistedConfig.get("fontFamily").isJsonNull()
                    ? persistedConfig.get("fontFamily").getAsString().trim()
                    : "";
            if (namedFamily.isEmpty()) {
                mode = UI_FONT_MODE_FOLLOW_EDITOR;
            } else {
                normalized.addProperty("fontFamily", namedFamily);
            }
        } else if (UI_FONT_MODE_CUSTOM_FILE.equals(mode)
                && persistedConfig != null
                && persistedConfig.has("customFontPath")
                && !persistedConfig.get("customFontPath").isJsonNull()
                && !persistedConfig.get("customFontPath").getAsString().trim().isEmpty()) {
            normalized.addProperty("customFontPath", persistedConfig.get("customFontPath").getAsString().trim());
        }

        normalized.addProperty("mode", mode);
        return normalized;
    }

    private static String extractFileName(String path) {
        return new File(path).getName();
    }

    // ======================= IDE 字体变化监听 =======================
    //
    // 用户在 Settings → Editor → Font / Appearance 里改字体时，IDE 不会主动
    // 通知 webview：旧实现只在打开设置页或切换页签时重新拉取，于是「改了字体
    // 但聊天窗口没变」。这里按 ThemeConfigService 的既有范式，在应用级订阅
    // 编辑器配色方案与 UI 设置的变化，合并后通知所有注册方重推字体快照。
    //
    // 监听器随 IDE 生命周期存活（应用级，不按 project 反复注册）；调用方拿到
    // handle 后必须在 dispose 时注销，否则会向已释放的 webview 推送。

    /** 字体变化回调。实现方自行读取最新的 editor / ui / code 字体配置。 */
    public interface FontChangeCallback {
        void onFontChanged();
    }

    /** 供注销使用的句柄（与 ThemeConfigService.RegisteredCallback 同构）。 */
    public static final class RegisteredFontCallback {
        private final long id;
        private final FontChangeCallback callback;

        RegisteredFontCallback(long id, FontChangeCallback callback) {
            this.id = id;
            this.callback = callback;
        }

        long getId() {
            return id;
        }

        public FontChangeCallback getCallback() {
            return callback;
        }
    }

    private static final CopyOnWriteArraySet<RegisteredFontCallback> fontChangeCallbacks =
            new CopyOnWriteArraySet<>();
    private static final AtomicLong fontCallbackIdSeq = new AtomicLong(0);
    private static volatile boolean fontListenerRegistered = false;
    /** 代际号：同一批变化只让最后一次回调生效（字体面板会连续触发多次）。 */
    private static final AtomicLong fontChangeGeneration = new AtomicLong(0);

    /**
     * 注册字体变化回调。
     *
     * @param callback     变化时调用
     * @param returnHandle {@code true} 返回可在 dispose 时注销的句柄
     * @return 句柄，或 {@code null}（returnHandle=false 时）
     */
    public static RegisteredFontCallback registerFontChangeListener(
            FontChangeCallback callback, boolean returnHandle) {
        if (callback == null) {
            return null;
        }
        ensureFontListenerRegistered();
        RegisteredFontCallback handle =
                new RegisteredFontCallback(fontCallbackIdSeq.incrementAndGet(), callback);
        fontChangeCallbacks.add(handle);
        LOG.info("[FontConfig] Font change callback registered (id=" + handle.getId()
                + ", total=" + fontChangeCallbacks.size() + ")");
        return returnHandle ? handle : null;
    }

    /** 注销字体变化回调（窗口 / handler 释放时调用）。 */
    public static void unregisterFontChangeListener(RegisteredFontCallback handle) {
        if (handle == null) {
            return;
        }
        if (fontChangeCallbacks.remove(handle)) {
            LOG.info("[FontConfig] Font change callback unregistered (id=" + handle.getId()
                    + ", remaining=" + fontChangeCallbacks.size() + ")");
        }
    }

    private static void ensureFontListenerRegistered() {
        if (fontListenerRegistered) {
            return;
        }
        synchronized (FontConfigService.class) {
            if (fontListenerRegistered) {
                return;
            }
            fontListenerRegistered = true;
            try {
                Application application = ApplicationManager.getApplication();
                // 编辑器字体 / 配色方案变化（Settings → Editor → Font）。
                application.getMessageBus().connect().subscribe(
                        EditorColorsManager.TOPIC,
                        (EditorColorsListener) scheme -> scheduleFontChangedNotify("editor colors scheme changed"));
                // UI 字体变化（Settings → Appearance → Use custom font）。
                UISettings.getInstance().addUISettingsListener(
                        settings -> scheduleFontChangedNotify("UI settings changed"),
                        application);
                LOG.info("[FontConfig] Font change listener registered successfully (Application level)");
            } catch (Exception e) {
                fontListenerRegistered = false;
                LOG.error("[FontConfig] Failed to register font change listener: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 合并同一批变化后通知：字体面板一次操作会连发多个事件，
     * 只有最后一次排队到 EDT 的通知真正执行。
     */
    private static void scheduleFontChangedNotify(String reason) {
        long generation = fontChangeGeneration.incrementAndGet();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (generation != fontChangeGeneration.get()) {
                return;
            }
            LOG.info("[FontConfig] Font change detected (" + reason + "), notifying "
                    + fontChangeCallbacks.size() + " callback(s)");
            for (RegisteredFontCallback handle : fontChangeCallbacks) {
                try {
                    handle.getCallback().onFontChanged();
                } catch (Exception e) {
                    LOG.warn("[FontConfig] Font change callback failed (id=" + handle.getId()
                            + "): " + e.getMessage(), e);
                }
            }
        });
    }

    public record ValidationResult(boolean valid, String errorMessage, String familyName) {}
}
