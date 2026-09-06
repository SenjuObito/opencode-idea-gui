import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { ToastContainer } from '../Toast';

// Import split-out components
import SettingsHeader from './SettingsHeader';
import SettingsSidebar, { type SettingsTab } from './SettingsSidebar';
import BasicConfigSection from './BasicConfigSection';
import DependencySection from './DependencySection';
import UsageSection from './UsageSection';
import PlaceholderSection from './PlaceholderSection';
import CommunitySection from './CommunitySection';
import AgentSection from './AgentSection';
import PromptSection from './PromptSection';
import CommitSection from './CommitSection';
import OtherSettingsSection from './OtherSettingsSection';
import { SkillsSettingsSection } from '../skills';
import SettingsDialogs from './SettingsDialogs';
import { setNewSessionConfirmEnabled as persistNewSessionConfirmEnabled } from '../../utils/skipNewSessionConfirm';

// Import custom hooks
import {
  useAgentManagement,
  useSettingsWindowCallbacks,
  useSettingsPageState,
  useSettingsThemeSync,
  useSettingsBasicActions,
} from './hooks';

import styles from './style.module.less';

interface SettingsViewProps {
  onClose: () => void;
  initialTab?: SettingsTab;
  currentProvider: string;
  // Streaming configuration (passed from App.tsx for state sync)
  streamingEnabled?: boolean;
  onStreamingEnabledChange?: (enabled: boolean) => void;
  // Send shortcut configuration (passed from App.tsx for state sync)
  sendShortcut?: 'enter' | 'cmdEnter';
  onSendShortcutChange?: (shortcut: 'enter' | 'cmdEnter') => void;
  // Auto open file configuration (passed from App.tsx for state sync)
  autoOpenFileEnabled?: boolean;
  onAutoOpenFileEnabledChange?: (enabled: boolean) => void;
  // Permission dialog timeout configuration (passed from App.tsx for state sync)
  permissionDialogTimeoutSeconds?: number;
  onPermissionDialogTimeoutChange?: (seconds: number) => void;
}

const SettingsView = ({
  onClose,
  initialTab,
  currentProvider,
  streamingEnabled: streamingEnabledProp,
  onStreamingEnabledChange: onStreamingEnabledChangeProp,
  sendShortcut: sendShortcutProp,
  onSendShortcutChange: onSendShortcutChangeProp,
  autoOpenFileEnabled: autoOpenFileEnabledProp,
  onAutoOpenFileEnabledChange: onAutoOpenFileEnabledChangeProp,
  permissionDialogTimeoutSeconds: permissionDialogTimeoutSecondsProp,
  onPermissionDialogTimeoutChange: onPermissionDialogTimeoutChangeProp,
}: SettingsViewProps) => {
  const { t } = useTranslation();

  // Page state: tabs, toasts, sidebar collapse, alert dialog
  const {
    currentTab,
    toasts,
    alertDialog,
    isCollapsed,
    handleTabChange,
    toggleManualCollapse,
    showAlert,
    closeAlert,
    addToast,
    dismissToast,
  } = useSettingsPageState({ initialTab });

  // Theme sync: theme preference, IDE theme, font size, chat colors
  const {
    themePreference,
    setThemePreference,
    setIdeTheme,
    fontSizeLevel,
    setFontSizeLevel,
    chatBgColor,
    setChatBgColor,
    userMsgColor,
    setUserMsgColor,
    chatBarColor,
    setChatBarColor,
    diffTheme,
    setDiffTheme,
  } = useSettingsThemeSync();

  // Basic settings actions: node path, working dir, streaming, shortcuts, sound, commit prompt, etc.
  const {
    nodePath,
    setNodePath,
    nodeVersion,
    setNodeVersion,
    minNodeVersion,
    setMinNodeVersion,
    savingNodePath,
    setSavingNodePath,
    opencodeCliPath,
    setOpencodeCliPath,
    savingOpencodeCliPath,
    setSavingOpencodeCliPath,
    workingDirectory,
    setWorkingDirectory,
    savingWorkingDirectory,
    setSavingWorkingDirectory,
    editorFontConfig,
    setEditorFontConfig,
    uiFontConfig,
    setUiFontConfig,
    codeFontConfig,
    setCodeFontConfig,
    setLocalStreamingEnabled,
    streamingEnabled,
    setLocalSendShortcut,
    sendShortcut,
    autoOpenFileEnabled,
    commitPrompt,
    setCommitPrompt,
    savingCommitPrompt,
    setSavingCommitPrompt,
    soundNotificationEnabled,
    setSoundNotificationEnabled,
    soundOnlyWhenUnfocused,
    setSoundOnlyWhenUnfocused,
    selectedSound,
    setSelectedSound,
    customSoundPath,
    setCustomSoundPath,
    diffExpandedByDefault,
    setDiffExpandedByDefault,
    historyCompletionEnabled,
    setHistoryCompletionEnabled,
    skipNewSessionConfirm,
    setSkipNewSessionConfirm,
    handleSaveNodePath,
    handleSaveOpencodeCliPath,
    handleSaveWorkingDirectory,
    handleUiFontSelectionChange,
    handleSaveUiFontCustomPath,
    handleBrowseUiFontFile,
    handleCodeFontSelectionChange,
    handleSaveCodeFontCustomPath,
    handleBrowseCodeFontFile,
    handleStreamingEnabledChange,
    handleSendShortcutChange,
    handleAutoOpenFileEnabledChange,
    handleSoundNotificationEnabledChange,
    handleSoundOnlyWhenUnfocusedChange,
    handleSelectedSoundChange,
    handleCustomSoundPathChange,
    handleSaveCustomSoundPath,
    handleTestSound,
    handleBrowseSound,
    handleSaveCommitPrompt,
    projectCommitPrompt,
    setProjectCommitPrompt,
    savingProjectCommitPrompt,
    setSavingProjectCommitPrompt,
    handleSaveProjectCommitPrompt,
    commitGenerationEnabled,
    setCommitGenerationEnabled,
    handleCommitGenerationEnabledChange,
    aiTitleGenerationEnabled,
    setAiTitleGenerationEnabled,
    handleAiTitleGenerationEnabledChange,
    statusBarWidgetEnabled,
    setStatusBarWidgetEnabled,
    handleStatusBarWidgetEnabledChange,
    taskCompletionNotificationEnabled,
    setTaskCompletionNotificationEnabled,
    handleTaskCompletionNotificationEnabledChange,
    askUserQuestionNotificationEnabled,
    setAskUserQuestionNotificationEnabled,
    handleAskUserQuestionNotificationEnabledChange,
    detailedOutputEnabled,
    handleDetailedOutputEnabledChange,
    systemNotificationOnlyWhenUnfocused,
    setSystemNotificationOnlyWhenUnfocused,
    handleSystemNotificationOnlyWhenUnfocusedChange,
    askUserQuestionSoundNotificationEnabled,
    setAskUserQuestionSoundNotificationEnabled,
    handleAskUserQuestionSoundNotificationEnabledChange,
    permissionDialogTimeoutSeconds,
    handlePermissionDialogTimeoutChange,
    commitAiConfig,
    setCommitAiConfig,
    handleCommitAiProviderChange,
    handleCommitAiModelChange,
    handleCommitAiResetToDefault,
  } = useSettingsBasicActions({
    streamingEnabledProp,
    onStreamingEnabledChangeProp,
    sendShortcutProp,
    onSendShortcutChangeProp,
    autoOpenFileEnabledProp,
    onAutoOpenFileEnabledChangeProp,
    permissionDialogTimeoutSecondsProp,
    onPermissionDialogTimeoutChangeProp,
    currentProvider,
  });

  // Use agent management hook
  const {
    agents,
    agentsLoading,
    agentDialog,
    deleteAgentConfirm,
    importPreviewDialog: agentImportPreviewDialog,
    exportDialog: agentExportDialog,
    loadAgents,
    updateAgents,
    cleanupAgentsTimeout,
    handleAddAgent,
    handleEditAgent,
    handleCloseAgentDialog,
    handleDeleteAgent,
    handleSaveAgent,
    confirmDeleteAgent,
    cancelDeleteAgent,
    handleAgentOperationResult,
    handleExportAgents,
    handleCloseExportDialog: handleCloseAgentExportDialog,
    handleConfirmExport: handleConfirmAgentExport,
    handleImportAgentsFile,
    handleAgentImportPreviewResult,
    handleCloseImportPreview: handleCloseAgentImportPreview,
    handleSaveImportedAgents,
    handleAgentImportResult,
  } = useAgentManagement({
    onSuccess: (msg) => addToast(msg, 'success'),
  });

  // Note: Prompt management is now handled internally by PromptSection component

  // Load heavy list data only when the corresponding tab is first opened.
  const loadedListTabsRef = useRef(new Set<SettingsTab>());
  useEffect(() => {
    if (currentTab === 'agents' && !loadedListTabsRef.current.has('agents')) {
      loadedListTabsRef.current.add('agents');
      loadAgents();
    }
    if (currentTab === 'commit' && !loadedListTabsRef.current.has('commit')) {
      loadedListTabsRef.current.add('commit');
      window.sendToJava?.('get_commit_prompt:');
      window.sendToJava?.('get_commit_ai_config:');
    }
  }, [currentTab, loadAgents]);

  // Register window callbacks for Java bridge communication
  useSettingsWindowCallbacks({
    setNodePath,
    setNodeVersion,
    setMinNodeVersion,
    setSavingNodePath,
    setOpencodeCliPath,
    setSavingOpencodeCliPath,
    setWorkingDirectory,
    setSavingWorkingDirectory,
    setCommitPrompt,
    setSavingCommitPrompt,
    setCommitAiConfig,
    setProjectCommitPrompt,
    setSavingProjectCommitPrompt,
    setEditorFontConfig,
    setUiFontConfig,
    setCodeFontConfig,
    setIdeTheme,
    setLocalStreamingEnabled,
    setLocalSendShortcut,
    loadAgents,
    updateAgents,
    handleAgentOperationResult,
    handleAgentImportPreviewResult,
    handleAgentImportResult,
    cleanupAgentsTimeout,
    showAlert,
    addToast,
    onStreamingEnabledChangeProp,
    onSendShortcutChangeProp,
    setSoundNotificationEnabled,
    setSoundOnlyWhenUnfocused,
    setSelectedSound,
    setCustomSoundPath,
    setCommitGenerationEnabled,
    setAiTitleGenerationEnabled,
    setStatusBarWidgetEnabled,
    setTaskCompletionNotificationEnabled,
    setAskUserQuestionNotificationEnabled,
    setSystemNotificationOnlyWhenUnfocused,
    setAskUserQuestionSoundNotificationEnabled,
  });

  // Save agent (wrapper function with validation logic)
  const handleSaveAgentFromDialog = (data: { name: string; prompt: string }) => {
    handleSaveAgent(data);
  };

  return (
    <div className={styles.settingsPage}>
      {/* Top header bar */}
      <SettingsHeader onClose={onClose} />

      {/* Main content */}
      <div className={styles.settingsMain}>
        {/* Sidebar */}
        <SettingsSidebar
          currentTab={currentTab}
          onTabChange={handleTabChange}
          isCollapsed={isCollapsed}
          onToggleCollapse={toggleManualCollapse}
        />

        {/* Content area — mount only the active tab.
            Previously every tab stayed mounted under display:none, which made
            Settings open cost ~all sections (MCP/Skills/TokenTracker/…) at once. */}
        <div className={styles.settingsContent}>
          {currentTab === 'basic' && (
            <BasicConfigSection
              theme={themePreference}
              onThemeChange={setThemePreference}
              fontSizeLevel={fontSizeLevel}
              onFontSizeLevelChange={setFontSizeLevel}
              nodePath={nodePath}
              onNodePathChange={setNodePath}
              onSaveNodePath={handleSaveNodePath}
              savingNodePath={savingNodePath}
              nodeVersion={nodeVersion}
              minNodeVersion={minNodeVersion}
              opencodeCliPath={opencodeCliPath}
              onOpencodeCliPathChange={setOpencodeCliPath}
              onSaveOpencodeCliPath={handleSaveOpencodeCliPath}
              savingOpencodeCliPath={savingOpencodeCliPath}
              workingDirectory={workingDirectory}
              onWorkingDirectoryChange={setWorkingDirectory}
              onSaveWorkingDirectory={handleSaveWorkingDirectory}
              savingWorkingDirectory={savingWorkingDirectory}
              editorFontConfig={editorFontConfig}
              uiFontConfig={uiFontConfig}
              codeFontConfig={codeFontConfig}
              onUiFontSelectionChange={handleUiFontSelectionChange}
              onSaveUiFontCustomPath={handleSaveUiFontCustomPath}
              onBrowseUiFontFile={handleBrowseUiFontFile}
              onCodeFontSelectionChange={handleCodeFontSelectionChange}
              onSaveCodeFontCustomPath={handleSaveCodeFontCustomPath}
              onBrowseCodeFontFile={handleBrowseCodeFontFile}
              streamingEnabled={streamingEnabled}
              onStreamingEnabledChange={handleStreamingEnabledChange}
              sendShortcut={sendShortcut}
              onSendShortcutChange={handleSendShortcutChange}
              autoOpenFileEnabled={autoOpenFileEnabled}
              onAutoOpenFileEnabledChange={handleAutoOpenFileEnabledChange}
              chatBgColor={chatBgColor}
              onChatBgColorChange={setChatBgColor}
              userMsgColor={userMsgColor}
              onUserMsgColorChange={setUserMsgColor}
              chatBarColor={chatBarColor}
              onChatBarColorChange={setChatBarColor}
              diffTheme={diffTheme}
              onDiffThemeChange={setDiffTheme}
              diffExpandedByDefault={diffExpandedByDefault}
              onDiffExpandedByDefaultChange={setDiffExpandedByDefault}
              commitGenerationEnabled={commitGenerationEnabled}
              onCommitGenerationEnabledChange={(enabled) => {
                handleCommitGenerationEnabledChange(enabled);
                addToast(t('toast.restartRequired'), 'warning');
              }}
              statusBarWidgetEnabled={statusBarWidgetEnabled}
              onStatusBarWidgetEnabledChange={(enabled) => {
                handleStatusBarWidgetEnabledChange(enabled);
                addToast(t('toast.restartRequired'), 'warning');
              }}
              aiTitleGenerationEnabled={aiTitleGenerationEnabled}
              onAiTitleGenerationEnabledChange={handleAiTitleGenerationEnabledChange}
              newSessionConfirmEnabled={!skipNewSessionConfirm}
              onNewSessionConfirmEnabledChange={(enabled) => {
                // Optimistic local update so the toggle reflects instantly even if
                // the CustomEvent loops back. persistNewSessionConfirmEnabled writes
                // to localStorage and dispatches the sync event for other surfaces.
                setSkipNewSessionConfirm(!enabled);
                persistNewSessionConfirmEnabled(enabled);
              }}
              soundNotificationEnabled={soundNotificationEnabled}
              onSoundNotificationEnabledChange={handleSoundNotificationEnabledChange}
              soundOnlyWhenUnfocused={soundOnlyWhenUnfocused}
              onSoundOnlyWhenUnfocusedChange={handleSoundOnlyWhenUnfocusedChange}
              selectedSound={selectedSound}
              onSelectedSoundChange={handleSelectedSoundChange}
              customSoundPath={customSoundPath}
              onCustomSoundPathChange={handleCustomSoundPathChange}
              onSaveCustomSoundPath={handleSaveCustomSoundPath}
              onTestSound={handleTestSound}
              onBrowseSound={handleBrowseSound}
              taskCompletionNotificationEnabled={taskCompletionNotificationEnabled}
              onTaskCompletionNotificationEnabledChange={handleTaskCompletionNotificationEnabledChange}
              askUserQuestionNotificationEnabled={askUserQuestionNotificationEnabled}
              onAskUserQuestionNotificationEnabledChange={handleAskUserQuestionNotificationEnabledChange}
              detailedOutputEnabled={detailedOutputEnabled}
              onDetailedOutputEnabledChange={handleDetailedOutputEnabledChange}
              systemNotificationOnlyWhenUnfocused={systemNotificationOnlyWhenUnfocused}
              onSystemNotificationOnlyWhenUnfocusedChange={handleSystemNotificationOnlyWhenUnfocusedChange}
              askUserQuestionSoundNotificationEnabled={askUserQuestionSoundNotificationEnabled}
              onAskUserQuestionSoundNotificationEnabledChange={handleAskUserQuestionSoundNotificationEnabledChange}
              permissionDialogTimeoutSeconds={permissionDialogTimeoutSeconds}
              onPermissionDialogTimeoutChange={handlePermissionDialogTimeoutChange}
            />
          )}

          {currentTab === 'dependencies' && (
            <DependencySection addToast={addToast} isActive />
          )}

          {currentTab === 'usage' && <UsageSection />}

          {currentTab === 'mcp' && (
            <PlaceholderSection type="mcp" currentProvider={currentProvider} />
          )}

          {currentTab === 'commit' && (
            <CommitSection
              commitAiConfig={commitAiConfig}
              onCommitAiProviderChange={handleCommitAiProviderChange}
              onCommitAiModelChange={handleCommitAiModelChange}
              onCommitAiResetToDefault={handleCommitAiResetToDefault}
              commitPrompt={commitPrompt}
              projectCommitPrompt={projectCommitPrompt}
              onCommitPromptChange={setCommitPrompt}
              onProjectCommitPromptChange={setProjectCommitPrompt}
              onSaveCommitPrompt={handleSaveCommitPrompt}
              onSaveProjectCommitPrompt={handleSaveProjectCommitPrompt}
              savingCommitPrompt={savingCommitPrompt}
              savingProjectCommitPrompt={savingProjectCommitPrompt}
            />
          )}

          {currentTab === 'agents' && (
            <AgentSection
              agents={agents}
              loading={agentsLoading}
              onAdd={handleAddAgent}
              onEdit={handleEditAgent}
              onDelete={handleDeleteAgent}
              onExport={handleExportAgents}
              onImport={handleImportAgentsFile}
            />
          )}

          {currentTab === 'prompts' && (
            <PromptSection
              currentProvider={currentProvider}
              onSuccess={(msg) => addToast(msg, 'success')}
            />
          )}

          {currentTab === 'skills' && (
            <SkillsSettingsSection currentProvider={currentProvider} />
          )}

          {currentTab === 'other' && (
            <OtherSettingsSection
              historyCompletionEnabled={historyCompletionEnabled}
              onHistoryCompletionEnabledChange={(enabled) => {
                setHistoryCompletionEnabled(enabled);
                localStorage.setItem('historyCompletionEnabled', enabled.toString());
                // Dispatch custom event for same-tab sync (localStorage 'storage' event only fires for cross-tab)
                window.dispatchEvent(new CustomEvent('historyCompletionChanged', { detail: { enabled } }));
              }}
            />
          )}

          {currentTab === 'community' && (
            <CommunitySection addToast={addToast} />
          )}
        </div>
      </div>

      {/* All dialogs (alert, agent) */}
      <SettingsDialogs
        alertDialog={alertDialog}
        onCloseAlert={closeAlert}
        agentDialog={agentDialog}
        deleteAgentConfirm={deleteAgentConfirm}
        onCloseAgentDialog={handleCloseAgentDialog}
        onSaveAgent={handleSaveAgentFromDialog}
        onConfirmDeleteAgent={confirmDeleteAgent}
        onCancelDeleteAgent={cancelDeleteAgent}
        agentExportDialog={agentExportDialog}
        agentImportPreviewDialog={agentImportPreviewDialog}
        agents={agents}
        onCloseAgentExportDialog={handleCloseAgentExportDialog}
        onConfirmAgentExport={handleConfirmAgentExport}
        onCloseAgentImportPreview={handleCloseAgentImportPreview}
        onSaveImportedAgents={handleSaveImportedAgents}
      />

      {/* Toast notifications */}
      <ToastContainer messages={toasts} onDismiss={dismissToast} />
    </div>
  );
};

export default SettingsView;
