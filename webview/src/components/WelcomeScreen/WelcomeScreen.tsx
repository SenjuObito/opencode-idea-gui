import { memo } from 'react';
import type { TFunction } from 'i18next';

import { BlinkingLogo } from '../BlinkingLogo';
import { AnimatedText } from '../AnimatedText';

export interface WelcomeScreenProps {
  /** Runtime CLI provider (opencode); welcome logo follows the CLI */
  currentProvider: string;
  t: TFunction;
  /** 点击 welcome 页的 provider 标识时切换供应商 */
  onProviderChange?: (providerId: string) => void;
}

export const WelcomeScreen = memo(function WelcomeScreen({
  currentProvider,
  t,
}: WelcomeScreenProps): React.ReactElement {
  // opencode-only: the claude / codex / grok / kimi / pi labels were removed.
  const providerLabels: Record<string, string> = {
    opencode: t('providers.opencode.label'),
  };

  return (
    <div className="welcome-screen">
      <div className="welcome-logo-container">
        <BlinkingLogo provider={currentProvider} />
      </div>
      <div className="welcome-text-container">
        <AnimatedText text={t('chat.sendMessage', { provider: providerLabels[currentProvider] ?? currentProvider })} />
      </div>
    </div>
  );
});
