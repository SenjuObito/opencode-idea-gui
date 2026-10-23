/**
 * The semantic event catalog, webview side.
 *
 * <p>The host names an event semantically ({@code content.delta},
 * {@code message.snapshot}, {@code turn.ended}); this table says which handler
 * answers it. A type that is not listed falls back to the legacy
 * {@code window.<fn>} lookup, so the protocol can be completed step by step
 * without a flag day.</p>
 *
 * <p>Keep this table in sync with {@code com.opencodebuddy.bridge.EventCatalog}
 * — the Java test {@code EventCatalogTest} asserts that both sides agree.</p>
 */
export const SEMANTIC_HANDLERS: Record<string, string> = {
  // ── lifecycle ──────────────────────────────────────────────────────
  'tab.resync': 'onTabResync',
  'session.bound': 'setSessionId',
  'session.loaded': 'historyLoadComplete',
  'session.cleared': 'clearMessages',
  'command.rejected': 'commandRejected',
  'attachment.failed': 'onAttachmentFailed',

  // ── turn & message ─────────────────────────────────────────────────
  'turn.started': 'onStreamStart',
  'turn.ended': 'onStreamEnd',
  'turn.settled': 'onTurnEnd',
  'turn.retry': 'updateRetryStatus',
  'turn.heartbeat': 'onStreamingHeartbeat',
  'content.delta': 'onContentDelta',
  'thinking.delta': 'onThinkingDelta',
  'thinking.status': 'showThinkingStatus',
  'message.snapshot': 'updateMessages',
  'message.tail': 'updateMessageTail',
  'message.prepended': 'updateMessagesPrepend',
  'message.added': 'addUserMessage',
  'message.history': 'addHistoryMessage',
  'message.removed': 'onMessagesRemoved',
  'message.uuid.patched': 'patchMessageUuid',
  'message.block.reset': 'onBlockReset',

  // ── status & usage ─────────────────────────────────────────────────
  'status.updated': 'updateStatus',
  'session.loading': 'showLoading',
  'session.summary': 'showSummary',
  'usage.updated': 'onUsageUpdate',

  // ── tab & settings ─────────────────────────────────────────────────
  'tab.title.changed': 'updateSessionTitle',
  'settings.mode': 'onModeChanged',

  // ── prompts ────────────────────────────────────────────────────────
  'permission.requested': 'showPermissionDialog',
  'question.requested': 'showAskUserQuestionDialog',
  'question.answered': 'onQuestionAnswered',
  'plan.requested': 'showPlanApprovalDialog',
  'prompt.closed': 'forceClosePermissionDialog',
  'prompt.question.closed': 'forceCloseAskUserQuestionDialog',
  'prompt.plan.closed': 'forceClosePlanApprovalDialog',

  // ── status panel ───────────────────────────────────────────────────
  'todos.updated': 'onTodoUpdated',
  'task.event': 'onTaskEvent',
  'subagent.history': 'onSubagentHistoryLoaded',
  'revert.changed': 'onRevertStateUpdate',

  // ── webview 回调事件（A3 全量迁移） ─────────────────────────────
  'agents.import.preview': 'agentImportPreviewResult',
  'agents.import.result': 'agentImportResult',
  'agents.operation': 'agentOperationResult',
  'tab.state': 'applyBackendTabState',
  'font.idea': 'applyIdeaFontConfig',
  'settings.pinned.models': 'applyPinnedModels',
  'settings.ui.preferences': 'applyUiPreferences',
  'mcp.toggled': 'mcpServerToggled',
  'node.process.killed': 'nodeProcessKillResult',
  'clipboard.read': 'onClipboardRead',
  'commands.deleted': 'onCommandsDeleted',
  'commands.listed': 'onCommandsList',
  'commands.detail': 'onCommandsRead',
  'commands.saved': 'onCommandsSaved',
  'session.compact.failed': 'onCompactError',
  'session.compact.done': 'onCompactSuccess',
  'context.usage.error': 'onContextUsageError',
  'clipboard.copied': 'onCopyToClipboardResult',
  'font.editor.received': 'onEditorFontConfigReceived',
  'files.listed': 'onFileListResult',
  'files.path.resolved': 'onFilePathResolved',
  'session.fork.bound': 'onForkBound',
  'session.fork.failed': 'onForkError',
  'session.fork.done': 'onForkSuccess',
  'theme.changed': 'onIdeThemeChanged',
  'theme.received': 'onIdeThemeReceived',
  'settings.mode.received': 'onModeReceived',
  'settings.model.confirmed': 'onModelConfirmed',
  'permission.denied': 'onPermissionDenied',
  'session.revert.failed': 'onRevertError',
  'session.hydrated': 'onSessionHydrated',
  'session.share.failed': 'onShareError',
  'session.share.done': 'onShareSuccess',
  'font.system.list': 'onSystemFontListReceived',
  'tab.activated': 'onTabActivated',
  'tokentracker.response': 'onTokenTrackerResponse',
  'files.undo.all': 'onUndoAllFileResult',
  'files.undo': 'onUndoFileResult',
  'session.unshare.failed': 'onUnshareError',
  'session.unshare.done': 'onUnshareSuccess',
  'skills.delete': 'skillDeleteResult',
  'skills.import': 'skillImportResult',
  'skills.toggle': 'skillToggleResult',
  'agents.updated': 'updateAgents',
  'cli.status': 'updateCliStatus',
  'copilot.import.preview': 'updateCopilotImportPreview',
  'daemon.status': 'updateDaemonStatus',
  'linkify.capabilities': 'updateLinkifyCapabilities',
  'mcp.marketplace.entries': 'updateMcpMarketplaceEntries',
  'mcp.marketplace.sources': 'updateMcpMarketplaceSources',
  'mcp.status': 'updateMcpServerStatus',
  'mcp.tools': 'updateMcpServerTools',
  'mcp.servers': 'updateMcpServers',
  'node.path': 'updateNodePath',
  'node.processes': 'updateNodeProcesses',
  'cli.path': 'updateOpencodeCliPath',
  'skills.updated': 'updateSkills',
  'slash.commands': 'updateSlashCommands',
  'settings.sound': 'updateSoundNotificationConfig',
  'project.working.directory': 'updateWorkingDirectory',
};

/**
 * The handler name for a semantic event type.
 *
 * @returns the window handler, or {@code null} when the type is unknown
 */
export function handlerForSemanticType(type: string): string | null {
  if (!type) return null;
  return SEMANTIC_HANDLERS[type] ?? null;
}
