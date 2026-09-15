package com.opencodebuddy.ui.toolwindow;

import com.opencodebuddy.settings.TabStateService;

/**
 * 决定某个标签页当前会话是否该从服务端同步。
 *
 * 工具窗口打开时必定是全新会话，所以这里不再有「按落盘状态在开屏时恢复历史」的
 * 分支 —— 判据只剩「已绑定会话」+「前端已就绪」。
 */
final class TabSessionRestorePolicy {

    private TabSessionRestorePolicy() {
    }

    static boolean shouldLoadHistory(TabStateService.TabSessionState savedState) {
        return savedState != null && isNonEmpty(savedState.sessionId);
    }

    static boolean shouldStartHistoryLoad(TabStateService.TabSessionState savedState, boolean frontendReady) {
        return frontendReady && shouldLoadHistory(savedState);
    }

    private static boolean isNonEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
