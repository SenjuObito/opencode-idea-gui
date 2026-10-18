import { describe, it, expect } from 'vitest';
import {
  normalizeToolName,
  isToolName,
  AGENT_TOOL_NAMES,
  FILE_MODIFY_TOOL_NAMES,
  EDIT_TOOL_NAMES,
} from './toolConstants';

describe('normalizeToolName', () => {
  it('lowercases standard names', () => {
    expect(normalizeToolName('Edit')).toBe('edit');
    expect(normalizeToolName('Write')).toBe('write');
  });


  it('keeps camelCase tools as concatenated lower (TaskCreate)', () => {
    expect(normalizeToolName('TaskCreate')).toBe('taskcreate');
  });

  it('strips mcp prefix', () => {
    expect(normalizeToolName('mcp__server__Edit')).toBe('edit');
  });
});

describe('FILE_MODIFY_TOOL_NAMES', () => {

  it('still recognizes classic Edit/Write', () => {
    expect(isToolName('Edit', FILE_MODIFY_TOOL_NAMES)).toBe(true);
    expect(isToolName('Write', FILE_MODIFY_TOOL_NAMES)).toBe(true);
  });
});

describe('EDIT_TOOL_NAMES', () => {
  it('recognizes edit/write tool names', () => {
    expect(isToolName('Edit', EDIT_TOOL_NAMES)).toBe(true);
    expect(isToolName('write_to_file', EDIT_TOOL_NAMES)).toBe(true);
  });
});

describe('AGENT_TOOL_NAMES', () => {
  it('accepts the v1 task / agent names', () => {
    expect(isToolName('task', AGENT_TOOL_NAMES)).toBe(true);
    expect(isToolName('Agent', AGENT_TOOL_NAMES)).toBe(true);
    expect(isToolName('spawn_agent', AGENT_TOOL_NAMES)).toBe(true);
  });

  it('accepts the opencode v2 `subagent` rename', () => {
    // v2 renamed the `task` tool to `subagent`. Both must resolve so the
    // StatusPanel list and the inline agent cards work on v1 and v2 without
    // an engine branch at this layer.
    expect(isToolName('subagent', AGENT_TOOL_NAMES)).toBe(true);
    expect(isToolName('Subagent', AGENT_TOOL_NAMES)).toBe(true);
  });
});

