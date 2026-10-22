import type {
  ClaudeMessage,
  FileChangeSummary,
  SubagentHistoryResponse,
  TaskEventMap,
  TodoItem,
} from '../../types';
import type { ContextInfo } from '../../hooks';
import type { TabScrollState } from '../../hooks/useScrollBehavior';
import type { PermissionMode, ReasoningEffort } from '../ChatInputBox/types';
import type { TabType } from '../StatusPanel/types';
import type { TabMessageStore } from '../../store/tabMessageStore';

export interface SessionTabData {
  id: string;
  sessionId: string | null;
  title: string;
  isCustomTitle?: boolean;
  isShared?: boolean;
  messages: ClaudeMessage[];
  loading?: boolean;
  streamingActive?: boolean;
  isThinking?: boolean;
  status?: string;
  revertBoundaryId?: string | null;
  isCompacting?: boolean;
  compactingStartTime?: number | null;
  /** Background Agent task_* events owned by this tab. */
  taskEvents?: TaskEventMap;
  /** Authoritative todo snapshot owned by this tab. */
  sseTodos?: TodoItem[] | null;
  /** On-demand subagent transcripts owned by this tab. */
  subagentHistories?: Record<string, SubagentHistoryResponse>;
  /** Edits shown by this tab's StatusPanel. */
  filteredFileChanges?: FileChangeSummary[];
  /** Expanded state and selected page of this tab's StatusPanel. */
  statusPanelExpanded?: boolean;
  statusPanelTab?: TabType | null;
  permissionMode?: PermissionMode;
  selectedModel?: string;
  reasoningEffort?: ReasoningEffort;
  autoOpenFileEnabled?: boolean;
  contextInfo?: ContextInfo | null;
  activeFile?: string;
  selectedLines?: string;
  pendingPermissions?: any[];
  pendingQuestions?: any[];
  turnStartedAt?: number | null;
  usagePercentage?: number;
  usageUsedTokens?: number;
  usageMaxTokens?: number;
  messageBaseIndex?: number;
  opencodeListStart?: number;
  prependedHistoryMessageCount?: number;
  /** Per-tab reader position, restored when this tab is selected again. */
  scrollState?: TabScrollState;
  /**
   * The tab's transcript, reconciled by message id.
   *
   * <p>The store is shared by reference: the tab object is cloned on every
   * update, the store is not, so the transcript survives those clones and
   * keeps the identity of its messages.</p>
   */
  messageStore?: TabMessageStore;
}
