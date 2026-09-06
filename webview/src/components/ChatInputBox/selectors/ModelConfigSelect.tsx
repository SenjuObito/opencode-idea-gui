import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useDropdownPosition } from '../../../hooks/useDropdownPosition';
import { ProviderModelIcon } from '../../shared/ProviderModelIcon';
import {
  MODEL_ID_TO_MAPPING_KEY,
  resolveModelDisplayLabel,
  resolveModelIdForIcon,
} from '../modelLabelUtils';
import { useReasoningEffortGuard } from '../reasoningUtils';
import {
  AVAILABLE_MODELS,
  REASONING_LEVELS,
  type ModelInfo,
  type ReasoningEffort,
} from '../types';
import { ModelSelect } from './ModelSelect';
import { ReasoningSelect } from './ReasoningSelect';

const WRAPPER_STYLE: React.CSSProperties = { position: 'relative', display: 'inline-block' };
const CHEVRON_ICON_STYLE: React.CSSProperties = { fontSize: '10px', marginLeft: '2px' };
const DROPDOWN_STYLE: React.CSSProperties = {
  position: 'absolute',
  bottom: '100%',
  marginBottom: '4px',
  zIndex: 10000,
  minWidth: '220px',
  maxWidth: 'calc(100vw - 16px)',
  overflow: 'visible',
};
const OPTION_RELATIVE_STYLE: React.CSSProperties = { position: 'relative', overflow: 'visible' };
const OPTION_LABEL_STYLE: React.CSSProperties = { flex: 1, minWidth: 0 };
const OPTION_VALUE_STYLE: React.CSSProperties = {
  marginLeft: 'auto',
  display: 'flex',
  alignItems: 'center',
  gap: 4,
  color: 'var(--text-secondary)',
  flexShrink: 0,
};
const ARROW_ICON_STYLE: React.CSSProperties = { fontSize: '12px' };

/**
 * Delay before switching an already-open fly-out. The model list sits above
 * the parent rows, so the pointer has to cross "Effort" to reach it; without
 * a grace period those rows steal the submenu on the way.
 */
export const SUBMENU_HOVER_DELAY_MS = 200;

type ActiveSubmenu = 'none' | 'model' | 'effort';

interface ModelConfigSelectProps {
  selectedModel: string;
  onModelSelect: (modelId: string) => void;
  models?: ModelInfo[];
  currentProvider?: string;
  loading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  onAddModel?: () => void;
  reasoningEffort?: ReasoningEffort;
  onReasoningChange?: (effort: ReasoningEffort) => void;
}

function getReasoningLabel(
  t: (key: string, options?: { defaultValue?: string }) => string,
  effort: ReasoningEffort,
): string {
  const fallback = REASONING_LEVELS.find((level) => level.id === effort)?.label || effort;
  return t(`reasoning.${effort}.label`, { defaultValue: fallback });
}

/**
 * Nested model-settings selector: one summary trigger, fly-out submenus for
 * model / reasoning effort (opencode model variant).
 */
export const ModelConfigSelect = ({
  selectedModel,
  onModelSelect,
  models = AVAILABLE_MODELS,
  currentProvider = 'opencode',
  loading = false,
  error = null,
  onRetry,
  onAddModel,
  reasoningEffort = 'high',
  onReasoningChange,
}: ModelConfigSelectProps) => {
  const { t } = useTranslation();
  const [isOpen, setIsOpen] = useState(false);
  const [activeSubmenu, setActiveSubmenu] = useState<ActiveSubmenu>('none');
  const activeSubmenuRef = useRef<ActiveSubmenu>(activeSubmenu);
  activeSubmenuRef.current = activeSubmenu;
  const hoverTimerRef = useRef<number | undefined>(undefined);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const modelTriggerRef = useRef<HTMLDivElement>(null);
  const effortTriggerRef = useRef<HTMLDivElement>(null);

  const clearHoverTimer = useCallback(() => {
    if (hoverTimerRef.current !== undefined) {
      window.clearTimeout(hoverTimerRef.current);
      hoverTimerRef.current = undefined;
    }
  }, []);

  const openSubmenu = useCallback((submenu: ActiveSubmenu) => {
    clearHoverTimer();
    setActiveSubmenu(submenu);
  }, [clearHoverTimer]);

  const scheduleSubmenu = useCallback((submenu: ActiveSubmenu) => {
    if (activeSubmenuRef.current === submenu) {
      clearHoverTimer();
      return;
    }
    // First open can be immediate; only switching between fly-outs is delayed.
    if (activeSubmenuRef.current === 'none') {
      openSubmenu(submenu);
      return;
    }
    clearHoverTimer();
    hoverTimerRef.current = window.setTimeout(() => {
      hoverTimerRef.current = undefined;
      setActiveSubmenu(submenu);
    }, SUBMENU_HOVER_DELAY_MS);
  }, [clearHoverTimer, openSubmenu]);

  const triggerRefFor = (submenu: ActiveSubmenu) => {
    if (submenu === 'model') return modelTriggerRef.current;
    if (submenu === 'effort') return effortTriggerRef.current;
    return null;
  };

  /**
   * Fly-outs stop mouseenter from bubbling. If the pointer crossed another
   * row on the way, that row armed a delayed switch — arriving inside the
   * already-open fly-out must cancel it.
   */
  const retainActiveSubmenu = useCallback((event: React.MouseEvent) => {
    const current = activeSubmenuRef.current;
    if (current === 'none') return;
    const trigger = triggerRefFor(current);
    if (trigger?.contains(event.target as Node)) {
      clearHoverTimer();
    }
  }, [clearHoverTimer]);

  const { positionedStyle: mainPositionedStyle, recalculate: mainRecalculate } = useDropdownPosition({
    buttonRef,
    dropdownRef,
    preferredAlignment: 'right',
    minWidth: 220,
  });

  const handleReasoningChange = useCallback((effort: ReasoningEffort) => {
    onReasoningChange?.(effort);
  }, [onReasoningChange]);

  const { isVisible: showEffort, currentLevel } = useReasoningEffortGuard(
    reasoningEffort,
    handleReasoningChange,
    selectedModel,
    currentProvider,
  );

  const currentModel = models.find((model) => model.id === selectedModel)
    || (selectedModel
      ? { id: selectedModel, label: selectedModel } as ModelInfo
      : models[0]);
  const showEffortRow = showEffort && !!onReasoningChange;
  const hasLeadingRows = showEffortRow;

  const modelLabel = currentModel
    ? resolveModelDisplayLabel(currentModel, { t })
    : selectedModel;

  const effortLabel = currentLevel ? getReasoningLabel(t, currentLevel.id) : '';

  const summaryParts = [
    modelLabel,
    showEffortRow ? effortLabel : '',
  ].filter(Boolean);
  const summaryText = summaryParts.join(' ');

  const closeMenu = useCallback(() => {
    clearHoverTimer();
    setIsOpen(false);
    setActiveSubmenu('none');
  }, [clearHoverTimer]);

  const handleToggle = useCallback((event: React.MouseEvent) => {
    event.stopPropagation();
    const nextOpen = !isOpen;
    setIsOpen(nextOpen);
    clearHoverTimer();
    setActiveSubmenu('none');
    if (nextOpen) {
      mainRecalculate();
    }
  }, [clearHoverTimer, isOpen, mainRecalculate]);

  useEffect(() => {
    if (!isOpen) return;

    const handleClickOutside = (event: MouseEvent) => {
      if (
        dropdownRef.current
        && !dropdownRef.current.contains(event.target as Node)
        && buttonRef.current
        && !buttonRef.current.contains(event.target as Node)
      ) {
        closeMenu();
      }
    };

    const timer = setTimeout(() => {
      document.addEventListener('mousedown', handleClickOutside);
    }, 0);

    return () => {
      clearTimeout(timer);
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [closeMenu, isOpen]);

  useLayoutEffect(() => {
    if (isOpen) {
      mainRecalculate();
    }
  }, [isOpen, mainRecalculate, showEffortRow]);

  useEffect(() => () => clearHoverTimer(), [clearHoverTimer]);

  return (
    <div style={WRAPPER_STYLE}>
      <button
        ref={buttonRef}
        type="button"
        className="selector-button model-config-button"
        onClick={handleToggle}
        title={summaryText}
        aria-label={t('modelConfig.title', { defaultValue: 'Model settings' })}
        data-testid="model-config-trigger"
      >
        {currentModel && (
          <ProviderModelIcon
            providerId={currentProvider}
            modelId={resolveModelIdForIcon(currentModel.id, {}, MODEL_ID_TO_MAPPING_KEY)}
            size={12}
            colored
          />
        )}
        <span className="selector-button-text model-config-summary-text">{summaryText}</span>
        <span className={`codicon codicon-chevron-${isOpen ? 'up' : 'down'}`} style={CHEVRON_ICON_STYLE} />
      </button>

      {isOpen && (
        <div
          ref={dropdownRef}
          className="selector-dropdown model-config-dropdown"
          data-testid="model-config-dropdown"
          style={{ ...DROPDOWN_STYLE, ...mainPositionedStyle }}
          onMouseOverCapture={retainActiveSubmenu}
        >
          {showEffortRow && (
            <div
              ref={effortTriggerRef}
              className={`selector-option${activeSubmenu === 'effort' ? ' selected' : ''}`}
              data-testid="model-config-option-effort"
              onMouseEnter={() => scheduleSubmenu('effort')}
              onClick={(event) => {
                event.stopPropagation();
                openSubmenu('effort');
              }}
              style={OPTION_RELATIVE_STYLE}
            >
              <span style={OPTION_LABEL_STYLE}>{t('modelConfig.effort', { defaultValue: 'Effort' })}</span>
              <div style={OPTION_VALUE_STYLE}>
                <span>{effortLabel}</span>
                <span className="codicon codicon-chevron-right" style={ARROW_ICON_STYLE} />
              </div>
              {activeSubmenu === 'effort' && (
                <ReasoningSelect
                  value={reasoningEffort}
                  onChange={handleReasoningChange}
                  selectedModel={selectedModel}
                  currentProvider={currentProvider}
                  embedded
                  triggerRef={effortTriggerRef}
                  onClose={closeMenu}
                />
              )}
            </div>
          )}

          {hasLeadingRows && <div className="selector-divider" />}

          <div
            ref={modelTriggerRef}
            className={`selector-option${activeSubmenu === 'model' ? ' selected' : ''}`}
            data-testid="model-config-option-model"
            onMouseEnter={() => scheduleSubmenu('model')}
            onClick={(event) => {
              event.stopPropagation();
              openSubmenu('model');
            }}
            style={OPTION_RELATIVE_STYLE}
          >
            <span style={OPTION_LABEL_STYLE}>{t('modelConfig.model', { defaultValue: 'Model' })}</span>
            <div style={OPTION_VALUE_STYLE}>
              <span className="model-config-option-value-text">{modelLabel}</span>
              <span className="codicon codicon-chevron-right" style={ARROW_ICON_STYLE} />
            </div>
            {activeSubmenu === 'model' && (
              <ModelSelect
                value={selectedModel}
                onChange={onModelSelect}
                models={models}
                currentProvider={currentProvider}
                loading={loading}
                error={error}
                onRetry={onRetry}
                onAddModel={onAddModel}
                embedded
                triggerRef={modelTriggerRef}
                onClose={closeMenu}
              />
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default ModelConfigSelect;
