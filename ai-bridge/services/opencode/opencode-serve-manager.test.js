import test from 'node:test';
import assert from 'node:assert/strict';
import * as http from 'node:http';
import {
  getServerUrl,
  getServeProcess,
  isRunning,
  findBinary,
  start,
  stop,
} from './opencode-serve-manager.js';

test('opencode-serve-manager initial state', () => {
  assert.equal(typeof getServerUrl(), 'object'); // null or string
  assert.equal(typeof isRunning(), 'boolean');
  assert.equal(getServeProcess(), null);
});

test('opencode-serve-manager findBinary resolves binary or null', async () => {
  const bin = await findBinary();
  assert.ok(bin === null || typeof bin === 'string');
});

test('opencode-serve-manager start reuses local running HTTP server', async () => {
  // Start a mock HTTP server on an ephemeral port
  const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;

  try {
    const url = await start(port);
    assert.equal(url, `http://localhost:${port}`);
    assert.equal(getServerUrl(), `http://localhost:${port}`);
    assert.equal(getServeProcess(), null); // Reused server does not create a child process
    assert.equal(isRunning(), true); // isRunning should be true even when reused (_process is null)

    // Second call should return immediately without spawning
    const url2 = await start(port);
    assert.equal(url2, url);
    assert.equal(isRunning(), true);
  } finally {
    await stop();
    assert.equal(isRunning(), false);
    assert.equal(getServerUrl(), null);
    await new Promise((resolve) => server.close(resolve));
  }
});

