import type { ReactElement } from 'react';
import styles from './AeroOpenCodeLogo.module.less';

export interface AeroOpenCodeLogoProps {
  size?: number;
}

export const AeroOpenCodeLogo = ({ size = 64 }: AeroOpenCodeLogoProps): ReactElement => {
  const svgSize = Math.max(28, Math.round(size * 0.58));

  return (
    <div className={styles.wrapper} style={{ width: size, height: size }}>
      <div className={styles.ambientAura} />
      <div className={styles.orbitHalo} />
      <div className={styles.glassBadge}>
        <div className={styles.specularGlint} />
        <svg
          width={svgSize}
          height={svgSize}
          viewBox="0 0 44 44"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          className={styles.svgMark}
        >
          <defs>
            <linearGradient id="aero-ob-hologram" x1="16.5" y1="19" x2="27.5" y2="30.5" gradientUnits="userSpaceOnUse">
              <stop offset="0%" stopColor="#38bdf8" />
              <stop offset="50%" stopColor="#818cf8" />
              <stop offset="100%" stopColor="#c084fc" />
            </linearGradient>
            <linearGradient id="aero-ob-frame-edge" x1="11" y1="8" x2="33" y2="36" gradientUnits="userSpaceOnUse">
              <stop offset="0%" stopColor="#00f2fe" stopOpacity="0.8" />
              <stop offset="100%" stopColor="#6366f1" stopOpacity="0.3" />
            </linearGradient>
            <filter id="aero-ob-glow" x="14" y="16" width="16" height="18" filterUnits="userSpaceOnUse">
              <feGaussianBlur stdDeviation="1.5" result="blur" />
              <feComposite in="SourceGraphic" in2="blur" operator="over" />
            </filter>
          </defs>

          {/* Authentic OpenCode Vertical Frame with Micro-Curvature */}
          <rect x="11" y="8" width="22" height="28" rx="2.8" fill="#f8fafc" />
          <rect x="11" y="8" width="22" height="28" rx="2.8" stroke="url(#aero-ob-frame-edge)" strokeWidth="0.8" />

          {/* Inner Hollow Void */}
          <rect x="16.5" y="13.5" width="11" height="17" rx="1.2" fill="#090a0f" />

          {/* Multi-Spectrum Holographic Core */}
          <rect x="16.5" y="19" width="11" height="11.5" rx="1.2" fill="url(#aero-ob-hologram)" filter="url(#aero-ob-glow)" />
        </svg>
      </div>
    </div>
  );
};
