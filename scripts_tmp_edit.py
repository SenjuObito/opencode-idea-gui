import io
p='webview/src/App.tsx'
s=io.open(p,encoding='utf-8').read()

subs=[
("""import { ompModeForModelId } from './hooks/providers/cliProviders';
import { useOmpRoles } from './hooks/providers/useCliModels';
import { preloadSlashCommands, forceRefreshPrompts } from './components/ChatInputBox/providers';""",
"""import { preloadSlashCommands, forceRefreshPrompts } from './components/ChatInputBox/providers';"""),
("""import {
  apply1MContextSuffix,
  isValidPermissionMode,
  normalizeClaudeModelId,
  strip1MContextSuffix,
} from './components/ChatInputBox/types';""",
"""import { isValidPermissionMode } from './components/ChatInputBox/types';"""),
("""  const {
    currentProvider, selectedModel, permissionMode,
    selectedAgent, sdkStatusLoading, sdkStatusError, currentSdkInstalled,
    claudeSdkMeetsMinimum,
    currentProviderRef,
    activeProviderConfig, claudeSettingsAlwaysThinkingEnabled,
    reasoningEffort, codexFastMode, dshPreset, streamingEnabledSetting, sendShortcut, autoOpenFileEnabled,
    longContextEnabled,
    usagePercentage, usageUsedTokens, usageMaxTokens,
    setPermissionMode, setCurrentProvider,
    setClaudePermissionMode, setCodexPermissionMode,
    setSelectedClaudeModel, setSelectedCodexModel,
    setSelectedGrokModel, setSelectedKimiModel,
    setSelectedOpenCodeModel, setSelectedPiModel, setSelectedDshModel,
    setSelectedOmpModel, setOmpPermissionMode,
    setLongContextEnabled, setReasoningEffort, setCodexFastMode,
    setProviderConfigVersion, setActiveProviderConfig,
    setClaudeSettingsAlwaysThinkingEnabled, setStreamingEnabledSetting,
    setSendShortcut, setAutoOpenFileEnabled,
    setSdkStatus, setSdkStatusLoaded, setSdkStatusError, retrySdkStatus, setSelectedAgent,
    setUsagePercentage, setUsageUsedTokens, setUsageMaxTokens,
    syncActiveProviderModelMapping,
    handleModeSelect, handleModelSelect, handleProviderSelect,
    handleReasoningChange, handleCodexFastModeChange, handleDshPresetChange, handleAgentSelect, handleToggleThinking,
    handleStreamingEnabledChange, handleSendShortcutChange,
    handleAutoOpenFileEnabledChange, handleLongContextChange,
  } = useModelProviderState({ addToast, t });

  // Dynamic omp model roles (listModels payload; static smol/slow/plan until
  // loaded) — needed by applyHistoryModel's omp mode⇔model unification.
  const ompRoles = useOmpRoles();""",
"""  const {
    currentProvider, selectedModel, permissionMode,
    selectedAgent, sdkStatusLoading, sdkStatusError, currentSdkInstalled,
    currentProviderRef,
    reasoningEffort, streamingEnabledSetting, sendShortcut, autoOpenFileEnabled,
    usagePercentage, usageUsedTokens, usageMaxTokens,
    setPermissionMode, setCurrentProvider,
    setSelectedOpenCodeModel, setOpenCodePermissionMode,
    setReasoningEffort,
    setStreamingEnabledSetting,
    setSendShortcut, setAutoOpenFileEnabled,
    setSdkStatus, setSdkStatusLoaded, setSdkStatusError, retrySdkStatus, setSelectedAgent,
    setUsagePercentage, setUsageUsedTokens, setUsageMaxTokens,
    handleModeSelect, handleModelSelect, handleProviderSelect,
    handleReasoningChange, handleAgentSelect,
    handleStreamingEnabledChange, handleSendShortcutChange,
    handleAutoOpenFileEnabledChange,
  } = useModelProviderState({ addToast, t });"""),
("""    applyHistoryModel: (provider, model, agent) => {
      // Switch provider first when history row differs, then apply model.
      if (provider && provider !== currentProvider) {
        handleProviderSelect(provider);
      }
      if (model) {
        // handleModelSelect reads currentProvider; after provider switch state
        // may not have flushed yet — send bridge + setter for the target provider.
        if (provider === 'codex') {
          setSelectedCodexModel(model);
          sendBridgeEvent('set_model', model);
        } else if (provider === 'grok') {
          setSelectedGrokModel(model);
          sendBridgeEvent('set_model', model);
        } else if (provider === 'kimi') {
          setSelectedKimiModel(model);
          sendBridgeEvent('set_model', model);
        } else if (provider === 'opencode') {
          setSelectedOpenCodeModel(model);
          sendBridgeEvent('set_model', model);
        } else if (provider === 'pi') {
          setSelectedPiModel(model);
          sendBridgeEvent('set_model', model);
        } else if (provider === 'omp') {
          setSelectedOmpModel(model);
          sendBridgeEvent('set_model', model);
          const ompMode = ompModeForModelId(model, ompRoles);
          setOmpPermissionMode(ompMode);
          // Dynamic roles are not in Java's static mode whitelist — set_model
          // above already carries the role; skip set_mode for them.
          if (isValidPermissionMode(ompMode)) {
            sendBridgeEvent('set_mode', ompMode);
          }
        } else if (provider === 'dsh') {
          setSelectedDshModel(model);
          sendBridgeEvent('set_model', model);
        } else {
          // claude (or unrecognized): apply the claude model directly —
          // handleModelSelect reads currentProvider from a stale closure
          // right after a provider switch.
          const normalized = normalizeClaudeModelId(strip1MContextSuffix(model));
          setSelectedClaudeModel(normalized);
          sendBridgeEvent('set_model', apply1MContextSuffix(normalized, longContextEnabled));
        }
      }
      if (agent && provider === 'claude') {
        handleAgentSelect({ id: agent, name: agent, prompt: '' });
      }
    },
  });""",
"""    applyHistoryModel: (_provider, model, _agent) => {
      // OpenCode-only build: the provider is fixed; just apply the model.
      if (model) {
        setSelectedOpenCodeModel(model);
        sendBridgeEvent('set_model', model);
      }
    },
  });"""),
("""    setPermissionMode, setCurrentProvider, setClaudePermissionMode, setCodexPermissionMode,
    setSelectedClaudeModel, setSelectedCodexModel,
    setLongContextEnabled, setReasoningEffort, setCodexFastMode,
    setProviderConfigVersion, setActiveProviderConfig,
    setClaudeSettingsAlwaysThinkingEnabled, setStreamingEnabledSetting,""",
"""    setPermissionMode, setCurrentProvider, setOpenCodePermissionMode,
    setSelectedOpenCodeModel,
    setReasoningEffort, setStreamingEnabledSetting,"""),
("""    findLastAssistantIndex, extractRawBlocks,
    getOrCreateStreamingAssistantIndex, patchAssistantForStreaming,
    syncActiveProviderModelMapping,
    openPermissionDialog, openAskUserQuestionDialog, openPlanApprovalDialog,""",
"""    findLastAssistantIndex, extractRawBlocks,
    getOrCreateStreamingAssistantIndex, patchAssistantForStreaming,
    openPermissionDialog, openAskUserQuestionDialog, openPlanApprovalDialog,"""),
("""    currentProvider, selectedModel, permissionMode, reasoningEffort, selectedAgent, codexFastMode, dshPreset,
    sdkStatusLoading, currentSdkInstalled,""",
"""    currentProvider, selectedModel, permissionMode, reasoningEffort, selectedAgent,
    sdkStatusLoading, currentSdkInstalled,"""),
("""    forceCreateNewSession,
    handleModeSelect,
    longContextEnabled,
    openContextUsageDialog,""",
"""    forceCreateNewSession,
    handleModeSelect,
    openContextUsageDialog,"""),
("""      // /plan - switch to plan mode (Claude only; Codex sends as normal text)
      if (PLAN_COMMANDS.has(command) && currentProvider === 'claude') {""",
"""      // /plan - switch to plan mode
      if (PLAN_COMMANDS.has(command)) {"""),
("""  // Warn once when the installed Claude SDK is below the Fable minimum (0.3.182)
  // and the Fable tier is selected. Old CLIs don't recognize the 'fable' alias
  // and pass it through as a literal model name, which 401s on third-party relays
  // ("model fable" / "No available channel"). `claudeSdkMeetsMinimum` is `undefined`
  // until the backend reports status or when the SDK isn't installed — never warn
  // in those cases to avoid false positives.
  const fableSdkWarningShownRef = useRef(false);
  useEffect(() => {
    if (
      currentProvider === 'claude' &&
      currentSdkInstalled &&
      claudeSdkMeetsMinimum === false &&
      /fable/i.test(selectedModel ?? '') &&
      !fableSdkWarningShownRef.current
    ) {
      fableSdkWarningShownRef.current = true;
      addToast(t('chat.sdkTooLowForFable'), 'warning', {
        label: t('chat.updateSdk'),
        onClick: handleNavigateToSdkSettings,
      });
    }
  }, [currentProvider, currentSdkInstalled, claudeSdkMeetsMinimum, selectedModel, addToast, t, handleNavigateToSdkSettings]);

""", ""),
("""  const handleNavigateToProviderSettings = useCallback(() => {
    setSettingsInitialTab('providers');
    setCurrentView('settings');
  }, [setSettingsInitialTab, setCurrentView]);""",
"""  const handleNavigateToProviderSettings = useCallback(() => {
    setSettingsInitialTab('dependencies');
    setCurrentView('settings');
  }, [setSettingsInitialTab, setCurrentView]);"""),
("""              activeProviderConfig={activeProviderConfig}
              claudeSettingsAlwaysThinkingEnabled={claudeSettingsAlwaysThinkingEnabled}
              reasoningEffort={reasoningEffort}
              codexFastMode={codexFastMode}
              dshPreset={dshPreset}
              streamingEnabledSetting={streamingEnabledSetting}""",
"""              reasoningEffort={reasoningEffort}
              streamingEnabledSetting={streamingEnabledSetting}"""),
("""              autoOpenFileEnabled={autoOpenFileEnabled}
              longContextEnabled={longContextEnabled}
              usagePercentage={usagePercentage}""",
"""              autoOpenFileEnabled={autoOpenFileEnabled}
              usagePercentage={usagePercentage}"""),
("""              onReasoningChange={handleReasoningChange}
              onCodexFastModeChange={handleCodexFastModeChange}
              onDshPresetChange={handleDshPresetChange}
              onToggleThinking={handleToggleThinking}
              onStreamingEnabledChange={handleStreamingEnabledChange}
              onAutoOpenFileEnabledChange={handleAutoOpenFileEnabledChange}
              onLongContextChange={handleLongContextChange}""",
"""              onReasoningChange={handleReasoningChange}
              onStreamingEnabledChange={handleStreamingEnabledChange}
              onAutoOpenFileEnabledChange={handleAutoOpenFileEnabledChange}"""),
]
for old,new in subs:
    if old not in s:
        print('MISS:', repr(old[:90]))
    s=s.replace(old,new)
io.open(p,'w',encoding='utf-8',newline='').write(s)
print('App.tsx done')
