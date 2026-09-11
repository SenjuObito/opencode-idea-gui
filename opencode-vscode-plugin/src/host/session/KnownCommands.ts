/**
 * Known OpenCode slash commands definition and validation.
 *
 * Slash commands that match this set are dispatched to OpenCode's
 * /session/{id}/command endpoint. Unmatched slash-prefixed text (such as
 * /Users/... or /etc/...) is treated as standard user message content.
 */

export const KNOWN_OPENCODE_COMMANDS = new Set<string>([
	'commit',
	'review',
	'init',
	'undo',
	'redo',
	'clear',
	'help',
	'test',
	'compact',
	'editor',
	'context',
	'plan',
	'resume',
	'skills',
	'mcp',
	'status',
	'exit',
	'quit',
	'doctor',
	'version',
	'config',
	'login',
	'logout',
	'export',
	'share',
	'unshare',
]);

/**
 * Check if the given command name (case-insensitive, without leading '/') is a recognized OpenCode slash command.
 */
export function isKnownSlashCommand(command: string | null | undefined): boolean {
	if (!command) {
		return false;
	}
	const normalized = command.trim().toLowerCase();
	return KNOWN_OPENCODE_COMMANDS.has(normalized);
}
