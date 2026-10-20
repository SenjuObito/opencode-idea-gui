import type { ClaudeMessage } from '../../types';
import type { ContextInfo } from '../../hooks';
import type { TabScrollState } from '../../hooks/useScrollBehavior';
import type { PermissionMode, ReasoningEffort } from '../ChatInputBox/types';

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
  taskEvents?: Record<string, any>;
  sseTodos?: any[] | null;
  subagentHistories?: Record<string, any>;
  filteredFileChanges?: any[];
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
  draft?: {
    text: string;
    attachments: any[];
    fileTags: any[];
    quotes?: Record<string, { text: string }>;
  };
}
