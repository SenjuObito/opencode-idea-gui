import type { ClaudeMessage } from '../../types';

export interface SessionTabData {
  id: string;
  sessionId: string | null;
  title: string;
  isCustomTitle?: boolean;
  isShared?: boolean;
  messages: ClaudeMessage[];
  loading?: boolean;
  streamingActive?: boolean;
}
