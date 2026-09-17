import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const bridgeDir = dirname(fileURLToPath(import.meta.url));
const channelManager = join(bridgeDir, 'channel-manager.js');

test('channel-manager invalid provider outputs JSON response without polluting stdout', () => {
  const result = spawnSync(process.execPath, [channelManager, 'invalid-provider'], {
    cwd: bridgeDir,
    input: '',
    encoding: 'utf8',
    timeout: 10_000,
  });

  const response = JSON.parse(result.stdout.trim());
  assert.equal(response.success, false);
  assert.match(response.error, /Invalid provider/);
});
