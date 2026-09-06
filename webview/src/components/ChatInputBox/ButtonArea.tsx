import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ButtonAreaProps, ModelInfo, PermissionMode, ReasoningEffort } from './types';
import { OPENCODE_DEFAULT_MODEL_ID } from './types';
import { ConfigSelect, ModeSelect, ModelConfigSelect } from './selectors';
import { useCliModels } from '../../hooks/providers/useCliModels';
import { useToolbarSelectorCompact } from './hooks/useToolbarSelectorCompact';
import { resolveProviderModels } from './resolveProviderModels';

/**
 * ButtonArea - Bottom toolbar component
 * Contains mode selector, model selector, attachment button, send/stop button
 */
export const ButtonArea = ({
  disabled = false,
  hasInputContent = false,
  isLoading = false,
  selectedModel = OPENCODE_DEFAULT_MODEL_ID,
  permissionMode = 'default',
  currentProvider = 'opencode',
  reasoningEffort = 'high',
  onSubmit,
  onStop,
  onModeSelect,
  onModelSelect,
  onReasoningChange,
  alwaysThinkingEnabled = false,
  onToggleThinking,
  streamingEnabled = true,
  onStreamingEnabledChange,
  selectedAgent,
  onAgentSelect,
  onOpenAgentSettings,
  onAddModel,
}: ButtonAreaProps) => {
  const { t } = useTranslation();
  const { cliModels, cliModelsLoading, cliModelsError, cliDefaultModel, cliCatalogHasEntries, refreshCliModels } = useCliModels(currentProvider);

  // Track changes to custom models in localStorage
  // When localStorage changes, updating this version number triggers useMemo recalculation
  const [customModelsVersion, setCustomModelsVersion] = useState(0);

  // Listen for localStorage changes (cross-tab sync + same-tab custom events)
  useEffect(() => {
    const handleStorageChange = (e: StorageEvent) => {
      if (e.key === 'opencode-custom-models') {
        setCustomModelsVersion(v => v + 1);
      }
    };

    // Listen for custom events (localStorage changes within the same tab)
    const handleCustomStorageChange = (e: CustomEvent<{ key: string }>) => {
      if (e.detail.key === 'opencode-custom-models') {
        setCustomModelsVersion(v => v + 1);
      }
    };

    window.addEventListener('storage', handleStorageChange);
    window.addEventListener('localStorageChange', handleCustomStorageChange as EventListener);

    return () => {
      window.removeEventListener('storage', handleStorageChange);
      window.removeEventListener('localStorageChange', handleCustomStorageChange as EventListener);
    };
  }, []);

  // Model picker list — the runtime catalog from `get_cli_models` is the single
  // source (opencode resolves models and variants itself).
  const availableModels = useMemo(
    () => resolveProviderModels({
      provider: currentProvider,
      cliModels,
      cliCatalogHasEntries,
    }),
    // customModelsVersion intentionally forces re-read of localStorage customs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [currentProvider, customModelsVersion, cliModels, cliCatalogHasEntries],
  );

  // When a dynamic model catalog arrives, ensure selection is a real entry.
  useEffect(() => {
    // Only correct once a *real* catalog arrived. The static fallback list
    // (OPENCODE_MODELS = just "opencode-default") must not clobber the user's
    // choice — especially when ChatScreen remounts after leaving history and
    // briefly shows the fallback before the cache/fetch lands.
    if (!cliCatalogHasEntries) return;
    if (cliModelsLoading) return;
    if (!availableModels.length || !onModelSelect) return;
    const exists = availableModels.some((model) => model.id === selectedModel);
    if (!exists) {
      onModelSelect(cliDefaultModel ?? availableModels[0].id);
    }
  }, [
    availableModels,
    onModelSelect,
    selectedModel,
    cliDefaultModel,
    cliCatalogHasEntries,
    cliModelsLoading,
  ]);

  /**
   * Handle submit button click
   */
  const handleSubmitClick = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    onSubmit?.();
  }, [onSubmit]);

  /**
   * Handle stop button click
   */
  const handleStopClick = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    onStop?.();
  }, [onStop]);

  /**
   * Handle mode selection
   */
  const handleModeSelect = useCallback((mode: PermissionMode) => {
    onModeSelect?.(mode);
  }, [onModeSelect]);

  /**
   * Handle model selection
   */
  const handleModelSelect = useCallback((modelId: string) => {
    onModelSelect?.(modelId);
  }, [onModelSelect]);

  /**
   * Handle reasoning depth selection
   */
  const handleReasoningChange = useCallback((effort: ReasoningEffort) => {
    onReasoningChange?.(effort);
  }, [onReasoningChange]);

  // Collapse selector labels when left cluster is about to hit the send cluster (10px).
  const buttonAreaRef = useRef<HTMLDivElement>(null);
  const buttonAreaLeftRef = useRef<HTMLDivElement>(null);
  const buttonAreaRightRef = useRef<HTMLDivElement>(null);
  const selectorContentKey = [
    currentProvider,
    selectedModel,
    permissionMode,
    reasoningEffort,
    selectedAgent?.id ?? '',
    cliModelsLoading ? 'loading' : 'ready',
  ].join('|');
  const selectorsCompact = useToolbarSelectorCompact(
    buttonAreaRef,
    buttonAreaLeftRef,
    buttonAreaRightRef,
    selectorContentKey,
  );

  return (
    <div
      ref={buttonAreaRef}
      className={`button-area${selectorsCompact ? ' button-area--compact' : ''}`}
      data-provider={currentProvider}
    >
      {/* Left side: selectors */}
      <div ref={buttonAreaLeftRef} className="button-area-left">
        <ConfigSelect
          alwaysThinkingEnabled={alwaysThinkingEnabled}
          onToggleThinking={onToggleThinking}
          streamingEnabled={streamingEnabled}
          onStreamingEnabledChange={onStreamingEnabledChange}
          selectedAgent={selectedAgent}
          onAgentSelect={onAgentSelect}
          onOpenAgentSettings={onOpenAgentSettings}
        />
        <ModeSelect value={permissionMode} onChange={handleModeSelect} />
        <ModelConfigSelect
          selectedModel={selectedModel}
          onModelSelect={handleModelSelect}
          models={availableModels as ModelInfo[]}
          currentProvider={currentProvider}
          loading={cliModelsLoading}
          error={cliModelsError}
          onRetry={() => refreshCliModels(currentProvider)}
          onAddModel={onAddModel}
          reasoningEffort={reasoningEffort}
          onReasoningChange={handleReasoningChange}
        />
      </div>

      {/* Right side: tool buttons */}
      <div ref={buttonAreaRightRef} className="button-area-right">
        <div className="button-divider" />

        {/* Send/Stop button */}
        {isLoading ? (
          <button
            className="submit-button stop-button"
            onClick={handleStopClick}
            title={t('chat.stopGeneration')}
          >
            <span className="codicon codicon-debug-stop" />
          </button>
        ) : (
          <button
            className="submit-button"
            onClick={handleSubmitClick}
            disabled={disabled || !hasInputContent}
            title={t('chat.sendMessageEnter')}
          >
            <span className="codicon codicon-send" />
          </button>
        )}
      </div>
    </div>
  );
};

export default ButtonArea;
