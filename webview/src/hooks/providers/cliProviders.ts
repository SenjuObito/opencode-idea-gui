import type { PermissionMode } from '../../components/ChatInputBox/types';

/** OpenCode is the only CLI-backed provider in the opencode-only build. */
export const CLI_ONLY_PROVIDERS = new Set(['opencode']);

export function isCliOnlyProvider(providerId: string | null | undefined): boolean {
  return !!providerId && CLI_ONLY_PROVIDERS.has(providerId);
}

/**
 * Plan mode is coerced to default for headless CLI providers unless the host
 * accepts it; OpenCode supports plan natively so the mode is passed through.
 */
export function normalizeCliPermissionMode(mode: PermissionMode, _provider?: string | null): PermissionMode {
  void _provider;
  return mode;
}
