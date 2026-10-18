import { renderHook } from '@testing-library/react';
import { vi, describe, it, expect } from 'vitest';
import { useResetAttachmentsOnSessionChange } from './useResetAttachmentsOnSessionChange.js';

describe('useResetAttachmentsOnSessionChange', () => {
  it('does not clear on the initial mount', () => {
    const clear = vi.fn();
    renderHook(() =>
      useResetAttachmentsOnSessionChange({
        currentSessionId: 'a',
        isControlled: false,
        clearInternalAttachments: clear,
      })
    );

    expect(clear).not.toHaveBeenCalled();
  });

  it('clears attachments when the session id changes (uncontrolled)', () => {
    const clear = vi.fn();
    const { rerender } = renderHook(
      ({ id }: { id: string | null }) =>
        useResetAttachmentsOnSessionChange({
          currentSessionId: id,
          isControlled: false,
          clearInternalAttachments: clear,
        }),
      { initialProps: { id: 'a' as string | null } }
    );

    rerender({ id: 'b' });

    expect(clear).toHaveBeenCalledTimes(1);
  });

  it('does NOT clear in controlled mode', () => {
    const clear = vi.fn();
    const { rerender } = renderHook(
      ({ id }: { id: string | null }) =>
        useResetAttachmentsOnSessionChange({
          currentSessionId: id,
          isControlled: true,
          clearInternalAttachments: clear,
        }),
      { initialProps: { id: 'a' as string | null } }
    );

    rerender({ id: 'b' });

    expect(clear).not.toHaveBeenCalled();
  });

  it('does nothing when the session id is unchanged across rerenders', () => {
    const clear = vi.fn();
    const { rerender } = renderHook(
      ({ id }: { id: string | null }) =>
        useResetAttachmentsOnSessionChange({
          currentSessionId: id,
          isControlled: false,
          clearInternalAttachments: clear,
        }),
      { initialProps: { id: 'a' as string | null } }
    );

    rerender({ id: 'a' });

    expect(clear).not.toHaveBeenCalled();
  });

  it('does NOT clear when only the active tab changes', () => {
    const clear = vi.fn();
    let activeTabId = 'tab-1';
    (window as unknown as Record<string, unknown>).__sessionTabsStore = {
      getActiveTabId: () => activeTabId,
    };

    try {
      const { rerender } = renderHook(
        ({ id }: { id: string | null }) =>
          useResetAttachmentsOnSessionChange({
            currentSessionId: id,
            isControlled: false,
            clearInternalAttachments: clear,
          }),
        { initialProps: { id: 'a' as string | null } }
      );

      // Switching tabs pairs a session change with a tab change; the tab
      // switcher restores that tab's attachments right after, so clearing here
      // would immediately wipe them.
      activeTabId = 'tab-2';
      rerender({ id: 'b' });

      expect(clear).not.toHaveBeenCalled();
    } finally {
      delete (window as unknown as Record<string, unknown>).__sessionTabsStore;
    }
  });

  it('still clears when the session changes inside the same tab', () => {
    const clear = vi.fn();
    (window as unknown as Record<string, unknown>).__sessionTabsStore = {
      getActiveTabId: () => 'tab-1',
    };

    try {
      const { rerender } = renderHook(
        ({ id }: { id: string | null }) =>
          useResetAttachmentsOnSessionChange({
            currentSessionId: id,
            isControlled: false,
            clearInternalAttachments: clear,
          }),
        { initialProps: { id: 'a' as string | null } }
      );

      // Opening a different session from the history list reuses the same tab;
      // that draft belonged to the previous conversation and must not carry over.
      rerender({ id: 'b' });

      expect(clear).toHaveBeenCalledTimes(1);
    } finally {
      delete (window as unknown as Record<string, unknown>).__sessionTabsStore;
    }
  });
});
