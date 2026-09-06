import type { ModelInfo } from './types';

export interface ResolveProviderModelsInput {
  provider: string;
  /** Dynamic catalog from useCliModels (may be static fallback when empty). */
  cliModels: ModelInfo[];
  /**
   * True only when the backend returned real catalog entries.
   * When false, cliModels is the static fallback.
   */
  cliCatalogHasEntries?: boolean;
}

/**
 * Single source of truth for the model picker list (OpenCode-only build).
 * The runtime catalog returned by `get_cli_models` is the only source;
 * opencode resolves models and variants itself.
 */
export function resolveProviderModels({
  provider,
  cliModels,
}: ResolveProviderModelsInput): ModelInfo[] {
  void provider;
  return cliModels;
}
