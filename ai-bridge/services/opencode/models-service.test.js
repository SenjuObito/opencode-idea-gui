import test from 'node:test';
import assert from 'node:assert/strict';
import { parseOpenCodeModelsOutput, resolveDefaultModelId } from './models-service.js';

test('parses provider/model lines and dedups', () => {
  const out = 'opencode/big-pickle\nanthropic/claude-fable-5\nopencode/big-pickle\n';
  const models = parseOpenCodeModelsOutput(out);
  assert.deepEqual(models.map((m) => m.id), ['opencode/big-pickle', 'anthropic/claude-fable-5']);
  // UI groups by provider prefix, so the label carries the model name only.
  assert.equal(models[0].label, 'Big-Pickle');
});

test('handles CRLF and ANSI escape sequences (Windows terminals)', () => {
  const out = '\x1b[32mopencode/big-pickle\x1b[0m\r\n\x1b[2manthropic/claude-fable-5\x1b[0m\r\n';
  const models = parseOpenCodeModelsOutput(out);
  assert.deepEqual(models.map((m) => m.id), ['opencode/big-pickle', 'anthropic/claude-fable-5']);
});

test('picks the model token even when the line has extra columns', () => {
  const out = 'default  anthropic/claude-fable-5  200k context\n';
  const models = parseOpenCodeModelsOutput(out);
  assert.deepEqual(models.map((m) => m.id), ['anthropic/claude-fable-5']);
});

test('rejects Windows paths, URLs and UNC-ish tokens', () => {
  const out = [
    'Config loaded from C:/Users/x/.config/opencode/config.json',
    'Docs: https://opencode.ai/docs/models',
    'Share \\\\server\\share\\dir',
    'D:\\tools\\opencode.cmd run',
    'anthropic/claude-fable-5',
  ].join('\r\n');
  const models = parseOpenCodeModelsOutput(out);
  assert.deepEqual(models.map((m) => m.id), ['anthropic/claude-fable-5']);
});

test('returns empty list for empty or unparseable output', () => {
  assert.deepEqual(parseOpenCodeModelsOutput(''), []);
  assert.deepEqual(parseOpenCodeModelsOutput('No providers configured.\nRun `opencode auth login`.'), []);
});

test('resolveDefaultModelId reads the global { providerID, modelID } shape', () => {
  assert.equal(
    resolveDefaultModelId('anthropic', { providerID: 'anthropic', modelID: 'claude-sonnet-5' }),
    'anthropic/claude-sonnet-5',
  );
});

test('resolveDefaultModelId tolerates per-provider map shapes', () => {
  assert.equal(
    resolveDefaultModelId('anthropic', { anthropic: 'claude-opus-5' }),
    'anthropic/claude-opus-5',
  );
  assert.equal(
    resolveDefaultModelId('anthropic', { anthropic: { modelID: 'claude-haiku-5' } }),
    'anthropic/claude-haiku-5',
  );
});

test('resolveDefaultModelId returns null for missing or malformed defaults', () => {
  assert.equal(resolveDefaultModelId('anthropic', null), null);
  assert.equal(resolveDefaultModelId('anthropic', {}), null);
  assert.equal(resolveDefaultModelId('anthropic', { providerID: 'anthropic' }), null);
  assert.equal(resolveDefaultModelId('anthropic', { modelID: 'claude-sonnet-5' }), null);
  assert.equal(resolveDefaultModelId(undefined, { providerID: 'a', modelID: 'b' }), 'a/b');
});
