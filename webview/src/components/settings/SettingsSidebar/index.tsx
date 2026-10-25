import styles from './style.module.less';
import { useTranslation } from 'react-i18next';

export type SettingsTab = 'basic' | 'providers' | 'prompts' | 'usage' | 'mcp' | 'agents' | 'skills' | 'other' | 'community';

interface SidebarItem {
  key: SettingsTab;
  icon: string;
  labelKey: string; // Changed to i18n translation key
}

const sidebarItems: SidebarItem[] = [
  { key: 'basic', icon: 'codicon-settings-gear', labelKey: 'settings.basic.title' },
  { key: 'providers', icon: 'codicon-vm-connect', labelKey: 'settings.providers' },
  // 命令（提示词）暂时下线：入口先隐藏，PromptSection 与 i18n 文案保留，
  // 重新放开只需恢复本行。
  // { key: 'prompts', icon: 'codicon-notebook', labelKey: 'settings.prompts' },
  { key: 'usage', icon: 'codicon-graph', labelKey: 'settings.usage' },
  { key: 'mcp', icon: 'codicon-server', labelKey: 'settings.mcp' },
  // { key: 'agents', icon: 'codicon-robot', labelKey: 'settings.agents' },
  { key: 'skills', icon: 'codicon-book', labelKey: 'settings.skills' },
  { key: 'other', icon: 'codicon-ellipsis', labelKey: 'settings.other.title' },
  { key: 'community', icon: 'codicon-feedback', labelKey: 'settings.community' },
];

interface SettingsSidebarProps {
  currentTab: SettingsTab;
  onTabChange: (tab: SettingsTab) => void;
  isCollapsed?: boolean;
  onToggleCollapse?: () => void;
}

const SettingsSidebar = ({
  currentTab,
  onTabChange,
}: SettingsSidebarProps) => {
  const { t } = useTranslation();

  return (
    <div className={styles.sidebar}>
      <div className={styles.sidebarItems}>
        {sidebarItems.map((item) => {
          const label = t(item.labelKey);
          return (
            <div
              key={item.key}
              className={`${styles.sidebarItem} ${currentTab === item.key ? styles.active : ''}`}
              onClick={() => onTabChange(item.key)}
              title={label}
              aria-label={label}
              role="button"
              tabIndex={0}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  onTabChange(item.key);
                }
              }}
            >
              <span className={`codicon ${item.icon}`} />
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default SettingsSidebar;
