// hooks/useSettingsWindowCallbacks.ts
import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type { AgentConfig } from '../../../types/agent';

import type { UiFontConfig, CodeFontConfig } from './useSettingsBasicActions';
import type { AlertType } from '../../AlertDialog';
import type { ToastMessage } from '../../Toast';
import { scheduleBatchedBridgeRequests } from './scheduleBatchedBridgeRequests';
import { hostEventHandler, registerHostEvents, unregisterHostEvents } from '../../../utils/hostEventRegistry';
import { sendWindowCommand } from '../../../utils/bridge';

/**
 * Settings bootstrap commands, ordered by first-paint priority.
 *
 * <p>Settings are window-level: they describe the project, not a tab, so they
 * travel as window commands. Batched on open so CEF/Java is not hit with ~20
 * commands in one frame.</p>
 */
export const SETTINGS_BOOTSTRAP_COMMANDS = [
  // Environment + permissions (visible / used early on basic tab)
  'get_node_path',
  'get_opencode_cli_path',
  'get_working_directory',
  'get_permission_dialog_timeout',
  // Appearance fonts
  'get_editor_font_config',
  'get_ui_font_config',
  'get_code_font_config',
  // Behavior / feature toggles (basic tab sub-views)
  'get_sound_notification_config',
  'get_task_completion_notification_enabled',
  'get_ask_user_question_notification_enabled',
  'get_system_notification_only_when_unfocused',
  'get_ask_user_question_sound_notification_enabled',
  // NOTE: commit prompt / commit AI / prompt-enhancer configs are intentionally
  // NOT bootstrapped here. Their handlers probe CLI availability (spawn processes)
  // on the JCEF UI thread and freeze Settings until complete. Load on tab open.
] as const;

export interface SettingsWindowCallbacksDeps {
  // State setters
  setNodePath: (path: string) => void;
  setSavingNodePath: (saving: boolean) => void;
  setNodeVersion: (version: string | null) => void;
  setMinNodeVersion: (minVersion: number) => void;
  setOpencodeCliPath: (path: string) => void;
  setSavingOpencodeCliPath: (saving: boolean) => void;
  setWorkingDirectory: (dir: string) => void;
  setSavingWorkingDirectory: (saving: boolean) => void;

  setEditorFontConfig: (config: { fontFamily: string; fontSize: number; lineSpacing: number } | undefined) => void;
  setUiFontConfig: (config: UiFontConfig | undefined) => void;
  setCodeFontConfig: (config: CodeFontConfig | undefined) => void;
  setSystemFonts?: (fonts: string[]) => void;
  setIdeTheme: (theme: 'light' | 'dark' | null) => void;
  setLocalSendShortcut: (shortcut: 'enter' | 'cmdEnter') => void;
  // AI feature toggle setters
  setTaskCompletionNotificationEnabled?: (enabled: boolean) => void;
  setAskUserQuestionNotificationEnabled?: (enabled: boolean) => void;
  setSystemNotificationOnlyWhenUnfocused?: (enabled: boolean) => void;
  setAskUserQuestionSoundNotificationEnabled?: (enabled: boolean) => void;
  // Sound notification setters
  setSoundNotificationEnabled?: (enabled: boolean) => void;
  setSoundOnlyWhenUnfocused?: (enabled: boolean) => void;
  setSelectedSound?: (soundId: string) => void;
  setCustomSoundPath?: (path: string) => void;

  // Hook functions
  loadAgents: () => void;
  updateAgents: (agents: AgentConfig[]) => void;
  handleAgentOperationResult: (result: any) => void;
  handleAgentImportPreviewResult: (previewData: any) => void;
  handleAgentImportResult: (result: any) => void;
  cleanupAgentsTimeout: () => void;

  // Callbacks
  showAlert: (type: AlertType, title: string, message: string) => void;
  addToast: (message: string, type?: ToastMessage['type']) => void;

  // Props
  onSendShortcutChangeProp?: (shortcut: 'enter' | 'cmdEnter') => void;
}

/**
 * Registers window callbacks for Java bridge communication in settings view.
 * Handles provider, agent, prompt, config, and theme callbacks.
 */
export function useSettingsWindowCallbacks(deps: SettingsWindowCallbacksDeps) {
  const { t } = useTranslation();

  // Use ref to avoid stale closures - callbacks always read latest deps
  const depsRef = useRef(deps);
  depsRef.current = deps;

  useEffect(() => {
    const d = () => depsRef.current;

    const showError = (message: string) => {
      d().showAlert('error', t('toast.operationFailed'), message);
      d().setSavingNodePath(false);
      d().setSavingOpencodeCliPath(false);
      d().setSavingWorkingDirectory(false);
    };

    const showSwitchSuccess = (message: string) => {
      d().showAlert('success', t('toast.switchSuccess'), message);
    };

    const updateNodePath = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setNodePath(data.path || '');
        d().setNodeVersion(data.version || null);
        if (data.minVersion) {
          d().setMinNodeVersion(data.minVersion);
        }
      } catch (e) {
        console.warn('[SettingsView] Failed to parse updateNodePath JSON, fallback to legacy format:', e);
        d().setNodePath(jsonStr || '');
      }
      d().setSavingNodePath(false);
    };

    const updateOpencodeCliPath = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setOpencodeCliPath(data.path || '');
      } catch (e) {
        console.warn('[SettingsView] Failed to parse updateOpencodeCliPath JSON, fallback to legacy format:', e);
        d().setOpencodeCliPath(jsonStr || '');
      }
      d().setSavingOpencodeCliPath(false);
    };

    const updateWorkingDirectory = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setWorkingDirectory(data.customWorkingDir || '');
        d().setSavingWorkingDirectory(false);
      } catch (error) {
        console.error('[SettingsView] Failed to parse working directory:', error);
        d().setSavingWorkingDirectory(false);
      }
    };

    const showSuccess = (message: string) => {
      d().showAlert('success', t('toast.operationSuccess'), message);
      d().setSavingNodePath(false);
      d().setSavingOpencodeCliPath(false);
      d().setSavingWorkingDirectory(false);
    };

    const showSuccessI18n = (i18nKey: string) => {
      const message = t(i18nKey);
      d().addToast(message, 'success');
    };

    const onEditorFontConfigReceived = (jsonStr: string) => {
      try {
        const config = JSON.parse(jsonStr);
        d().setEditorFontConfig(config);
      } catch (error) {
        console.error('[SettingsView] Failed to parse editor font config:', error);
      }
    };

    const onUiFontConfigReceived = (jsonStr: string) => {
      try {
        const config = JSON.parse(jsonStr);
        d().setUiFontConfig(config);
        hostEventHandler('font.ui.apply')?.(config);
      } catch {
        // Silently ignore malformed UI font config from backend
      }
    };

    const onCodeFontConfigReceived = (jsonStr: string) => {
      try {
        const config = JSON.parse(jsonStr);
        d().setCodeFontConfig(config);
        hostEventHandler('font.code.apply')?.(config);
      } catch {
        // Silently ignore malformed code font config from backend
      }
    };

    const onSystemFontListReceived = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        if (Array.isArray(data.fonts)) {
          d().setSystemFonts?.(data.fonts);
        }
      } catch (error) {
        console.error('[SettingsView] Failed to parse system font list:', error);
      }
    };

    // IDE theme callback
    const previousOnIdeThemeReceived = hostEventHandler('theme.received');
    const onIdeThemeReceived = (jsonStr: string) => {
      try {
        const themeData = JSON.parse(jsonStr);
        const theme = themeData.isDark ? 'dark' : 'light';
        d().setIdeTheme(theme);
        previousOnIdeThemeReceived?.(jsonStr);
      } catch (error) {
        console.error('[SettingsView] Failed to parse IDE theme:', error);
      }
    };

    // Send shortcut configuration callback
    const previousUpdateSendShortcut = hostEventHandler('settings.send.shortcut');
    let updateSendShortcut: ((jsonStr: string) => void) | undefined;
    if (!d().onSendShortcutChangeProp) {
      updateSendShortcut = (jsonStr: string) => {
        try {
          const data = JSON.parse(jsonStr);
          d().setLocalSendShortcut(data.sendShortcut ?? 'enter');
        } catch (error) {
          console.error('[SettingsView] Failed to parse send shortcut config:', error);
        }
      };
    }


    // Task completion notification config callback (opt-in feature, default false)
    const updateTaskCompletionNotificationEnabled = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setTaskCompletionNotificationEnabled?.(data.taskCompletionNotificationEnabled ?? false);
      } catch (error) {
        console.error('[SettingsView] Failed to parse task completion notification config:', error);
      }
    };

    // AskUserQuestion reminder notification config callback (opt-in feature, default false)
    const updateAskUserQuestionNotificationEnabled = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setAskUserQuestionNotificationEnabled?.(data.askUserQuestionNotificationEnabled ?? false);
      } catch (error) {
        console.error('[SettingsView] Failed to parse ask user question notification config:', error);
      }
    };

    // System notification focus gate config callback (default false)
    const updateSystemNotificationOnlyWhenUnfocused = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setSystemNotificationOnlyWhenUnfocused?.(data.systemNotificationOnlyWhenUnfocused ?? false);
      } catch (error) {
        console.error('[SettingsView] Failed to parse system notification focus config:', error);
      }
    };

    // AskUserQuestion reminder sound notification config callback (opt-in feature, default false)
    const updateAskUserQuestionSoundNotificationEnabled = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setAskUserQuestionSoundNotificationEnabled?.(data.askUserQuestionSoundNotificationEnabled ?? false);
      } catch (error) {
        console.error('[SettingsView] Failed to parse ask user question sound notification config:', error);
      }
    };

    // Sound notification config callback
    const updateSoundNotificationConfig = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        if (data.enabled !== undefined) {
          d().setSoundNotificationEnabled?.(data.enabled);
        }
        if (data.onlyWhenUnfocused !== undefined) {
          d().setSoundOnlyWhenUnfocused?.(data.onlyWhenUnfocused);
        }
        if (data.selectedSound !== undefined) {
          d().setSelectedSound?.(data.selectedSound);
        }
        if (data.customSoundPath !== undefined) {
          d().setCustomSoundPath?.(data.customSoundPath);
        }
      } catch (error) {
        console.error('[SettingsView] Failed to parse sound notification config:', error);
      }
    };

    // Agent callbacks
    const previousUpdateAgents = hostEventHandler('agents.updated');
    const updateAgents = (jsonStr: string) => {
      try {
        const agentsList: AgentConfig[] = JSON.parse(jsonStr);
        d().updateAgents(agentsList);
      } catch (error) {
        console.error('[SettingsView] Failed to parse agents:', error);
      }
      previousUpdateAgents?.(jsonStr);
    };

    // 自定义声音"浏览"：宿主 showOpenDialog 选完回填输入框（不自动保存，
    // 保留用户确认语义）。仅设置页挂载期间有效——浏览按钮只在此处出现。
    // 宿主当前没有对应事件；保留占位会重新制造一条死回调，故不注册。

    const agentOperationResult = (jsonStr: string) => {
      try {
        const result = JSON.parse(jsonStr);
        d().handleAgentOperationResult(result);
      } catch (error) {
        console.error('[SettingsView] Failed to parse agent operation result:', error);
      }
    };

    const agentImportPreviewResult = (jsonStr: string) => {
      try {
        const previewData = JSON.parse(jsonStr);
        if (!Array.isArray(previewData?.items) || typeof previewData?.summary !== 'object') {
          console.error('[SettingsView] Invalid agent import preview data structure');
          return;
        }
        d().handleAgentImportPreviewResult(previewData);
      } catch (error) {
        console.error('[SettingsView] Failed to parse agent import preview result:', error);
      }
    };

    const agentImportResult = (jsonStr: string) => {
      try {
        const result = JSON.parse(jsonStr);
        d().handleAgentImportResult(result);
      } catch (error) {
        console.error('[SettingsView] Failed to parse agent import result:', error);
      }
    };

    // Settings is a second callback surface: bind its handlers to the same
    // semantic registry so a settings event never relies on the window path.
    registerHostEvents({
      'toast.operation.error': showError,
      'toast.switch.success': showSwitchSuccess,
      'node.path': updateNodePath,
      'cli.path': updateOpencodeCliPath,
      'project.working.directory': updateWorkingDirectory,
      'toast.operation.success': showSuccess,
      'toast.operation.success.i18n': showSuccessI18n,
      'font.editor.received': onEditorFontConfigReceived,
      'font.ui.received': onUiFontConfigReceived,
      'font.code.received': onCodeFontConfigReceived,
      'font.system.list': onSystemFontListReceived,
      'theme.received': onIdeThemeReceived,
      'settings.send.shortcut': updateSendShortcut,
      'settings.task.completion': updateTaskCompletionNotificationEnabled,
      'settings.question.notification': updateAskUserQuestionNotificationEnabled,
      'settings.system.notification': updateSystemNotificationOnlyWhenUnfocused,
      'settings.question.sound': updateAskUserQuestionSoundNotificationEnabled,
      'settings.sound': updateSoundNotificationConfig,
      'agents.updated': updateAgents,
      'agents.operation': agentOperationResult,
      'agents.import.preview': agentImportPreviewResult,
      'agents.import.result': agentImportResult,
    });

    // Initial data loading for the default (basic) settings surface only.
    // Provider / agent / prompt lists are fetched when their tabs first mount.
    // Bootstrap messages are batched so open-settings does not stampede CEF.
    const bootstrapRequests = scheduleBatchedBridgeRequests({
      messages: SETTINGS_BOOTSTRAP_COMMANDS,
      batchSize: 5,
      batchDelayMs: 16,
      send: (type) => {
        sendWindowCommand(type);
      },
    });

    return () => {
      bootstrapRequests.cancel();
      d().cleanupAgentsTimeout();

      unregisterHostEvents([
        'toast.operation.error', 'toast.switch.success',
        'node.path', 'cli.path', 'project.working.directory',
        'toast.operation.success', 'toast.operation.success.i18n',
        'font.editor.received', 'font.ui.received', 'font.code.received',
        'font.system.list', 'theme.received', 'settings.send.shortcut',
        'settings.task.completion', 'settings.question.notification',
        'settings.system.notification', 'settings.question.sound',
        'settings.sound', 'agents.updated', 'agents.operation',
        'agents.import.preview', 'agents.import.result',
      ]);

      if (previousOnIdeThemeReceived) {
        registerHostEvents({ 'theme.received': previousOnIdeThemeReceived });
      }
      if (previousUpdateSendShortcut) {
        registerHostEvents({ 'settings.send.shortcut': previousUpdateSendShortcut });
      }
      if (previousUpdateAgents) {
        registerHostEvents({ 'agents.updated': previousUpdateAgents });
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [t]);
}
