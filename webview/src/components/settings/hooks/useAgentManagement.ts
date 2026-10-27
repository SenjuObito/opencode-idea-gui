import { useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import type { AgentConfig } from '../../../types/agent';
import type { ImportPreviewResult, ConflictStrategy } from '../../../types/import';
import { sendWindowCommand } from '../../../utils/bridge';
import { requestAgents, useAgents } from '../../../store/agentStore';

export interface AgentDialogState {
  isOpen: boolean;
  agent: AgentConfig | null;
}

export interface DeleteAgentConfirmState {
  isOpen: boolean;
  agent: AgentConfig | null;
}

export interface ImportPreviewDialogState {
  isOpen: boolean;
  previewData: ImportPreviewResult<AgentConfig> | null;
}

export interface ExportDialogState {
  isOpen: boolean;
}

export interface UseAgentManagementOptions {
  onError?: (message: string) => void;
  onSuccess?: (message: string) => void;
}

export function useAgentManagement(options: UseAgentManagementOptions = {}) {
  const { t } = useTranslation();
  const { onSuccess } = options;

  // Agent list state: the host-pushed catalogue is the single source. There is
  // no local copy, no retry timer, and no setter for the list.
  const { agents: storeAgents, loaded: agentsLoaded } = useAgents();
  const agents = storeAgents as unknown as AgentConfig[];
  const agentsLoading = !agentsLoaded;

  // Agent dialog state
  const [agentDialog, setAgentDialog] = useState<AgentDialogState>({
    isOpen: false,
    agent: null,
  });

  // Agent delete confirmation state
  const [deleteAgentConfirm, setDeleteAgentConfirm] = useState<DeleteAgentConfirmState>({
    isOpen: false,
    agent: null,
  });

  // Import preview dialog state
  const [importPreviewDialog, setImportPreviewDialog] = useState<ImportPreviewDialogState>({
    isOpen: false,
    previewData: null,
  });

  // Export dialog state
  const [exportDialog, setExportDialog] = useState<ExportDialogState>({
    isOpen: false,
  });

  // Ask the host for the catalogue. The host is the only writer of the store.
  const loadAgents = useCallback(() => {
    requestAgents();
  }, []);

  // Open add agent dialog
  const handleAddAgent = useCallback(() => {
    setAgentDialog({ isOpen: true, agent: null });
  }, []);

  // Open edit agent dialog
  const handleEditAgent = useCallback((agent: AgentConfig) => {
    setAgentDialog({ isOpen: true, agent });
  }, []);

  // Close agent dialog
  const handleCloseAgentDialog = useCallback(() => {
    setAgentDialog({ isOpen: false, agent: null });
  }, []);

  // Delete agent
  const handleDeleteAgent = useCallback((agent: AgentConfig) => {
    setDeleteAgentConfirm({ isOpen: true, agent });
  }, []);

  // Save agent
  const handleSaveAgent = useCallback(
    (data: { name: string; prompt: string }) => {
      const isAdding = !agentDialog.agent;

      if (isAdding) {
        // Add new agent
        const newAgent = {
          id: crypto.randomUUID ? crypto.randomUUID() : Date.now().toString(),
          name: data.name,
          prompt: data.prompt,
        };
        sendWindowCommand('add_agent', newAgent);
      } else if (agentDialog.agent) {
        // Update existing agent
        const updateData = {
          id: agentDialog.agent.id,
          updates: {
            name: data.name,
            prompt: data.prompt,
          },
        };
        sendWindowCommand('update_agent', updateData);
      }

      setAgentDialog({ isOpen: false, agent: null });
      // Reload list after agent operation (with timeout protection)
      loadAgents();
    },
    [agentDialog.agent, loadAgents]
  );

  // Confirm agent deletion
  const confirmDeleteAgent = useCallback(() => {
    const agent = deleteAgentConfirm.agent;
    if (!agent) return;

    const data = { id: agent.id };
    sendWindowCommand('delete_agent', data);
    setDeleteAgentConfirm({ isOpen: false, agent: null });
    // Reload list after deletion (with timeout protection)
    loadAgents();
  }, [deleteAgentConfirm.agent, loadAgents]);

  // Cancel agent deletion
  const cancelDeleteAgent = useCallback(() => {
    setDeleteAgentConfirm({ isOpen: false, agent: null });
  }, []);

  // Handle agent operation result (used by window callback)
  const handleAgentOperationResult = useCallback(
    (result: { success: boolean; operation?: string; error?: string }) => {
      if (result.success) {
        const operationMessages: Record<string, string> = {
          add: t('settings.agent.addSuccess'),
          update: t('settings.agent.updateSuccess'),
          delete: t('settings.agent.deleteSuccess'),
        };
        onSuccess?.(operationMessages[result.operation || ''] || t('settings.agent.operationSuccess'));
      }
    },
    [onSuccess, t]
  );

  // Open export dialog
  const handleExportAgents = useCallback(() => {
    setExportDialog({ isOpen: true });
  }, []);

  // Close export dialog
  const handleCloseExportDialog = useCallback(() => {
    setExportDialog({ isOpen: false });
  }, []);

  // Confirm export with selected IDs
  const handleConfirmExport = useCallback((selectedIds: string[]) => {
    const exportData = {
      agentIds: selectedIds,
    };
    sendWindowCommand('export_agents', exportData);
    setExportDialog({ isOpen: false });
  }, []);

  // Import agents from file
  const handleImportAgentsFile = useCallback(() => {
    sendWindowCommand('import_agents_file');
  }, []);

  // Handle import preview result (used by window callback)
  const handleAgentImportPreviewResult = useCallback(
    (previewData: ImportPreviewResult<AgentConfig>) => {
      setImportPreviewDialog({
        isOpen: true,
        previewData,
      });
    },
    []
  );

  // Close import preview dialog
  const handleCloseImportPreview = useCallback(() => {
    setImportPreviewDialog({
      isOpen: false,
      previewData: null,
    });
  }, []);

  // Save imported agents
  const handleSaveImportedAgents = useCallback(
    (selectedIds: string[], strategy: ConflictStrategy) => {
      if (!importPreviewDialog.previewData) return;

      const selectedAgents = importPreviewDialog.previewData.items
        .filter(item => selectedIds.includes(item.data.id))
        .map(item => item.data);

      const importData = {
        agents: selectedAgents,
        strategy,
      };

      sendWindowCommand('save_imported_agents', importData);
      setImportPreviewDialog({ isOpen: false, previewData: null });
    },
    [importPreviewDialog.previewData]
  );

  // Handle import result (used by window callback)
  const handleAgentImportResult = useCallback(
    (result: { success: boolean; imported: number; updated: number; skipped: number; error?: string }) => {
      if (result.success) {
        const message = t('settings.agent.importDialog.importPartialSuccess', {
          imported: result.imported,
          updated: result.updated,
          skipped: result.skipped,
        });
        onSuccess?.(message);
      }
      // Reload agents list
      loadAgents();
    },
    [onSuccess, t, loadAgents]
  );

  return {
    // State
    agents,
    agentsLoading,
    agentDialog,
    deleteAgentConfirm,
    importPreviewDialog,
    exportDialog,

    // Methods
    loadAgents,
    handleAddAgent,
    handleEditAgent,
    handleCloseAgentDialog,
    handleDeleteAgent,
    handleSaveAgent,
    confirmDeleteAgent,
    cancelDeleteAgent,
    handleAgentOperationResult,
    handleExportAgents,
    handleCloseExportDialog,
    handleConfirmExport,
    handleImportAgentsFile,
    handleAgentImportPreviewResult,
    handleCloseImportPreview,
    handleSaveImportedAgents,
    handleAgentImportResult,
  };
}

export type UseAgentManagementReturn = ReturnType<typeof useAgentManagement>;
