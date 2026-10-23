package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.BaseMessageHandler;
import com.opencodebuddy.handler.core.HandlerContext;
import com.opencodebuddy.handler.provider.ModelProviderHandler;

import com.opencodebuddy.util.LanguageConfigService;
import com.opencodebuddy.util.ThemeConfigService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Settings and usage statistics message handler.
 * Delegates to focused sub-handlers for each concern.
 */
public class SettingsHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(SettingsHandler.class);
    private final Gson gson = new Gson();

    private final SoundSettingsHandler soundSettingsHandler;
    private final UsagePushService usagePushService;
    private final PermissionModeHandler permissionModeHandler;
    private final ModelProviderHandler modelProviderHandler;
    private final NodePathHandler nodePathHandler;
    private final OpenCodeCliPathHandler opencodeCliPathHandler;
    private final ProjectConfigHandler projectConfigHandler;
    // Handle for the theme-change callback registered with ThemeConfigService.
    // Kept so it can be cleanly unregistered when the owning window is disposed,
    // preventing notifications to disposed webviews (issue #1586).
    private ThemeConfigService.RegisteredCallback themeCallbackHandle;
    private final TokenTrackerHandler tokenTrackerHandler;

    private static final String[] SUPPORTED_TYPES = {
        "get_mode",
        "settings.mode.get",
        "settings.mode",
        "settings.model",
        "settings.reasoning",
        "get_node_path",
        "set_node_path",
        "get_opencode_cli_path",
        "set_opencode_cli_path",
        // TokenTracker local usage dashboard (vendored tokentracker-cli server)
        "tt_detect_cli",
        "tt_install_cli",
        "tt_ensure_server",
        "tt_proxy",
        "get_working_directory",
        "set_working_directory",
        "get_editor_font_config",
        "settings.font.editor.get",
        "get_system_font_list",
        "get_ui_font_config",
        "settings.font.ui.get",
        "set_ui_font_config",
        "browse_ui_font_file",
        "get_code_font_config",
        "settings.font.code.get",
        "set_code_font_config",
        "browse_code_font_file",
        "get_streaming_enabled",
        "set_streaming_enabled",
        "get_codex_sandbox_mode",
        "set_codex_sandbox_mode",
        "get_send_shortcut",
        "set_send_shortcut",
        "settings.sendShortcut",
        "get_auto_open_file_enabled",
        "set_auto_open_file_enabled",
        "settings.autoOpenFileEnabled",
        "get_permission_dialog_timeout",
        "set_permission_dialog_timeout",
        "get_commit_generation_enabled",
        "set_commit_generation_enabled",
        "get_status_bar_widget_enabled",
        "set_status_bar_widget_enabled",

        "get_task_completion_notification_enabled",
        "set_task_completion_notification_enabled",
        "get_ask_user_question_notification_enabled",
        "set_ask_user_question_notification_enabled",
        "get_system_notification_only_when_unfocused",
        "set_system_notification_only_when_unfocused",
        "get_ask_user_question_sound_notification_enabled",
        "set_ask_user_question_sound_notification_enabled",
        "get_ide_theme",
        "settings.theme.get",
        "get_commit_prompt",
        "set_commit_prompt",
        "get_commit_ai_config",
        "set_commit_ai_config",
        "get_prompt_enhancer_config",
        "set_prompt_enhancer_config",
        "get_project_commit_prompt",
        "set_project_commit_prompt",
        // Sound notification configuration
        "get_sound_notification_config",
        "set_sound_notification_enabled",
        "set_sound_only_when_unfocused",
        "set_selected_sound",
        "set_custom_sound_path",
        "test_sound",
        "browse_sound_file",
        // User language preference
        "set_user_language",
        "get_user_language",
        "clear_user_language",
        "set_provider",
        "settings.provider",
        "get_ui_preferences",
        "set_ui_preferences",
        "settings.uiPreferences.get",
        "settings.uiPreferences",
        "get_pinned_models",
        "set_pinned_models",
        "settings.pinnedModels.get",
        "settings.pinnedModels"
    };

    public SettingsHandler(HandlerContext context) {
        super(context);
        this.soundSettingsHandler = new SoundSettingsHandler(context);
        this.usagePushService = new UsagePushService(context);
        this.permissionModeHandler = new PermissionModeHandler(context);
        this.modelProviderHandler = new ModelProviderHandler(context, usagePushService);
        this.nodePathHandler = new NodePathHandler(context);
        this.opencodeCliPathHandler = new OpenCodeCliPathHandler(context);
        this.projectConfigHandler = new ProjectConfigHandler(context);
        this.tokenTrackerHandler = new TokenTrackerHandler(context);
        // Register theme change listener to automatically notify frontend when IDE theme changes
        registerThemeChangeListener();
    }

    /**
     * Register theme change listener.
     * Uses the multi-callback API so that every open OpencodeBuddyChatWindow receives
     * theme change notifications. The returned handle is stored for clean
     * unregistration in {@link #dispose()}.
     */
    private void registerThemeChangeListener() {
        themeCallbackHandle = ThemeConfigService.registerThemeChangeListener(themeConfig -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                callJavaScript("window.onIdeThemeChanged", escapeJs(themeConfig.toString()));
            });
        }, true);
    }

    /**
     * Unregister the theme change callback to prevent notifications to a disposed webview.
     * Should be called when the owning OpencodeBuddyChatWindow is disposed.
     */
    public void dispose() {
        if (themeCallbackHandle != null) {
            ThemeConfigService.unregisterThemeChangeListener(themeCallbackHandle);
            themeCallbackHandle = null;
        }
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            // Permission mode
            case "get_mode":
            case "settings.mode.get":
                permissionModeHandler.handleGetMode();
                return true;
            case "settings.mode":
                permissionModeHandler.handleSetMode(content);
                return true;
            // Model
            case "settings.model":
                modelProviderHandler.handleSetModel(content);
                return true;
            case "settings.reasoning":
                modelProviderHandler.handleSetReasoningEffort(content);
                return true;
            // Node path
            case "get_node_path":
                nodePathHandler.handleGetNodePath();
                return true;
            case "set_node_path":
                nodePathHandler.handleSetNodePath(content);
                return true;
            // Claude CLI path
            case "get_opencode_cli_path":
                opencodeCliPathHandler.handleGetOpencodeCliPath();
                return true;
            case "set_opencode_cli_path":
                opencodeCliPathHandler.handleSetOpencodeCliPath(content);
                return true;
            // TokenTracker local usage dashboard
            case "tt_detect_cli":
                tokenTrackerHandler.handleDetectCli(content);
                return true;
            case "tt_install_cli":
                tokenTrackerHandler.handleInstallCli(content);
                return true;
            case "tt_ensure_server":
                tokenTrackerHandler.handleEnsureServer(content);
                return true;
            case "tt_proxy":
                tokenTrackerHandler.handleProxy(content);
                return true;
            case "get_working_directory":
                projectConfigHandler.handleGetWorkingDirectory();
                return true;
            case "set_working_directory":
                projectConfigHandler.handleSetWorkingDirectory(content);
                return true;
            case "get_editor_font_config":
            case "settings.font.editor.get":
                projectConfigHandler.handleGetEditorFontConfig();
                return true;
            case "get_system_font_list":
                projectConfigHandler.handleGetSystemFontList();
                return true;
            case "get_ui_font_config":
            case "settings.font.ui.get":
                projectConfigHandler.handleGetUiFontConfig();
                return true;
            case "set_ui_font_config":
                projectConfigHandler.handleSetUiFontConfig(content);
                return true;
            case "browse_ui_font_file":
                projectConfigHandler.handleBrowseUiFontFile();
                return true;
            case "get_code_font_config":
            case "settings.font.code.get":
                projectConfigHandler.handleGetCodeFontConfig();
                return true;
            case "set_code_font_config":
                projectConfigHandler.handleSetCodeFontConfig(content);
                return true;
            case "browse_code_font_file":
                projectConfigHandler.handleBrowseCodeFontFile();
                return true;
            case "get_streaming_enabled":
                projectConfigHandler.handleGetStreamingEnabled();
                return true;
            case "set_streaming_enabled":
                projectConfigHandler.handleSetStreamingEnabled(content);
                return true;
            case "get_send_shortcut":
                projectConfigHandler.handleGetSendShortcut();
                return true;
            case "set_send_shortcut":
            case "settings.sendShortcut":
                projectConfigHandler.handleSetSendShortcut(content);
                return true;
            case "get_auto_open_file_enabled":
                projectConfigHandler.handleGetAutoOpenFileEnabled();
                return true;
            case "set_auto_open_file_enabled":
            case "settings.autoOpenFileEnabled":
                projectConfigHandler.handleSetAutoOpenFileEnabled(content);
                return true;
            case "get_permission_dialog_timeout":
                projectConfigHandler.handleGetPermissionDialogTimeout();
                return true;
            case "set_permission_dialog_timeout":
                projectConfigHandler.handleSetPermissionDialogTimeout(content);
                return true;
            case "get_commit_generation_enabled":
                projectConfigHandler.handleGetCommitGenerationEnabled();
                return true;
            case "set_commit_generation_enabled":
                projectConfigHandler.handleSetCommitGenerationEnabled(content);
                return true;
            case "get_status_bar_widget_enabled":
                projectConfigHandler.handleGetStatusBarWidgetEnabled();
                return true;
            case "set_status_bar_widget_enabled":
                projectConfigHandler.handleSetStatusBarWidgetEnabled(content);
                return true;

            case "get_task_completion_notification_enabled":
                projectConfigHandler.handleGetTaskCompletionNotificationEnabled();
                return true;
            case "set_task_completion_notification_enabled":
                projectConfigHandler.handleSetTaskCompletionNotificationEnabled(content);
                return true;
            case "get_ask_user_question_notification_enabled":
                projectConfigHandler.handleGetAskUserQuestionNotificationEnabled();
                return true;
            case "set_ask_user_question_notification_enabled":
                projectConfigHandler.handleSetAskUserQuestionNotificationEnabled(content);
                return true;
            case "get_system_notification_only_when_unfocused":
                projectConfigHandler.handleGetSystemNotificationOnlyWhenUnfocused();
                return true;
            case "set_system_notification_only_when_unfocused":
                projectConfigHandler.handleSetSystemNotificationOnlyWhenUnfocused(content);
                return true;
            case "get_ask_user_question_sound_notification_enabled":
                projectConfigHandler.handleGetAskUserQuestionSoundNotificationEnabled();
                return true;
            case "set_ask_user_question_sound_notification_enabled":
                projectConfigHandler.handleSetAskUserQuestionSoundNotificationEnabled(content);
                return true;
            case "get_ai_title_generation_enabled":
                projectConfigHandler.handleGetAiTitleGenerationEnabled();
                return true;
            case "set_ai_title_generation_enabled":
                projectConfigHandler.handleSetAiTitleGenerationEnabled(content);
                return true;
            case "get_ide_theme":
            case "settings.theme.get":
                projectConfigHandler.handleGetIdeTheme();
                return true;
            case "get_commit_prompt":
                projectConfigHandler.handleGetCommitPrompt();
                return true;
            case "set_commit_prompt":
                projectConfigHandler.handleSetCommitPrompt(content);
                return true;
            case "get_commit_ai_config":
                projectConfigHandler.handleGetCommitAiConfig();
                return true;
            case "set_commit_ai_config":
                projectConfigHandler.handleSetCommitAiConfig(content);
                return true;
            case "get_prompt_enhancer_config":
                projectConfigHandler.handleGetPromptEnhancerConfig();
                return true;
            case "set_prompt_enhancer_config":
                projectConfigHandler.handleSetPromptEnhancerConfig(content);
                return true;
            case "get_project_commit_prompt":
                projectConfigHandler.handleGetProjectCommitPrompt();
                return true;
            case "set_project_commit_prompt":
                projectConfigHandler.handleSetProjectCommitPrompt(content);
                return true;
            // Input history
            // Sound notification configuration
            case "get_sound_notification_config":
                soundSettingsHandler.handleGetSoundNotificationConfig();
                return true;
            case "set_sound_notification_enabled":
                soundSettingsHandler.handleSetSoundNotificationEnabled(content);
                return true;
            case "set_sound_only_when_unfocused":
                soundSettingsHandler.handleSetSoundOnlyWhenUnfocused(content);
                return true;
            case "set_selected_sound":
                soundSettingsHandler.handleSetSelectedSound(content);
                return true;
            case "set_custom_sound_path":
                soundSettingsHandler.handleSetCustomSoundPath(content);
                return true;
            case "test_sound":
                soundSettingsHandler.handleTestSound(content);
                return true;
            case "browse_sound_file":
                soundSettingsHandler.handleBrowseSoundFile();
                return true;
            // User language preference
            case "set_user_language":
                handleSetUserLanguage(content);
                return true;
            case "get_user_language":
                handleGetUserLanguage();
                return true;
            case "clear_user_language":
                handleClearUserLanguage();
                return true;
            case "set_provider":
            case "settings.provider":
                if (content != null && !content.isEmpty()) {
                    String provider = content;
                    if (content.trim().startsWith("{")) {
                        try {
                            JsonObject obj = gson.fromJson(content, JsonObject.class);
                            if (obj != null && obj.has("provider") && !obj.get("provider").isJsonNull()) {
                                provider = obj.get("provider").getAsString();
                            }
                        } catch (Exception ignored) {
                            // Keep the legacy raw-provider path.
                        }
                    }
                    context.setCurrentProvider(provider);
                }
                return true;
            case "get_ui_preferences":
            case "settings.uiPreferences.get":
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        JsonObject prefs = context.getSettingsService().getUiPreferences();
                        callJavaScript("window.applyUiPreferences", escapeJs(prefs.toString()));
                    } catch (Exception e) {
                        LOG.warn("[SettingsHandler] Failed to get ui_preferences: " + e.getMessage());
                        callJavaScript("window.applyUiPreferences", "{}");
                    }
                });
                return true;
            case "set_ui_preferences":
            case "settings.uiPreferences":
                try {
                    JsonObject patch = gson.fromJson(content, JsonObject.class);
                    context.getSettingsService().setUiPreferences(patch);
                    JsonObject currentPrefs = context.getSettingsService().getUiPreferences();
                    ApplicationManager.getApplication().invokeLater(() -> {
                        broadcastToAll("window.applyUiPreferences", escapeJs(currentPrefs.toString()));
                    });
                } catch (Exception e) {
                    LOG.warn("[SettingsHandler] Failed to set ui_preferences: " + e.getMessage());
                }
                return true;
            case "get_pinned_models":
            case "settings.pinnedModels.get":
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        JsonObject pinned = context.getSettingsService().getPinnedModels();
                        callJavaScript("window.applyPinnedModels", escapeJs(pinned.toString()));
                    } catch (Exception e) {
                        LOG.warn("[SettingsHandler] Failed to get pinned_models: " + e.getMessage());
                        callJavaScript("window.applyPinnedModels", "{}");
                    }
                });
                return true;
            case "set_pinned_models":
            case "settings.pinnedModels":
                try {
                    JsonObject patch = gson.fromJson(content, JsonObject.class);
                    context.getSettingsService().setPinnedModels(patch);
                    JsonObject currentPinned = context.getSettingsService().getPinnedModels();
                    ApplicationManager.getApplication().invokeLater(() -> {
                        broadcastToAll("window.applyPinnedModels", escapeJs(currentPinned.toString()));
                    });
                } catch (Exception e) {
                    LOG.warn("[SettingsHandler] Failed to set pinned_models: " + e.getMessage());
                }
                return true;
            default:
                return false;
        }
    }

    /**
     * Handle set_user_language: save user's manual language preference.
     * On failure, push the authoritative config back so the webview can roll
     * back its optimistic UI update.
     */
    private void handleSetUserLanguage(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String language = json.has("language") && !json.get("language").isJsonNull()
                    ? json.get("language").getAsString() : null;
            if (language == null || language.isEmpty()) {
                LOG.warn("[SettingsHandler] set_user_language rejected: empty language");
                pushLanguageConfig();
                return;
            }
            LanguageConfigService.setUserLanguage(context.getSettingsService(), language);
            LOG.info("[SettingsHandler] Saved user language preference: " + language);
            pushLanguageConfig();
        } catch (Exception e) {
            LOG.error("[SettingsHandler] Failed to save user language: " + e.getMessage(), e);
            pushLanguageConfig();
        }
    }

    /**
     * Handle get_user_language: return user's saved language preference.
     */
    private void handleGetUserLanguage() {
        String userLanguage = LanguageConfigService.getUserLanguage(context.getSettingsService());
        JsonObject response = new JsonObject();
        response.addProperty("language", userLanguage != null ? userLanguage : "");
        response.addProperty("manuallySet", userLanguage != null);
    }

    /**
     * Handle clear_user_language: clear user's manual language preference.
     * Pushes the authoritative config on both success and failure so the
     * webview always reflects the persisted state.
     */
    private void handleClearUserLanguage() {
        try {
            LanguageConfigService.clearUserLanguage(context.getSettingsService());
            LOG.info("[SettingsHandler] Cleared user language preference");
        } catch (Exception e) {
            LOG.error("[SettingsHandler] Failed to clear user language: " + e.getMessage(), e);
        } finally {
            pushLanguageConfig();
        }
    }

    private void pushLanguageConfig() {
        JsonObject languageConfig = LanguageConfigService.getLanguageConfig(context.getSettingsService());
        broadcastToAll("window.applyIdeaLanguageConfig", escapeJs(languageConfig.toString()));
    }

    /**
     * Expose getModelContextLimit for callers that previously used the static method on SettingsHandler.
     */
    public static int getModelContextLimit(String model) {
        return ModelProviderHandler.getModelContextLimit(model);
    }

    public static int getModelContextLimit(String provider, String model) {
        return ModelProviderHandler.getModelContextLimit(provider, model);
    }
}
