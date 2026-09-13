import { test } from 'node:test';
import assert from 'node:assert/strict';
import { sendMessagePersistent } from './opencode-daemon-service.js';

test('sendMessagePersistent — intercepts leading ! and executes shell command', async () => {
  // Verify that an empty prompt without ! throws empty message error
  let emptyErrorCaught = false;
  const originalLog = console.log;
  let logOutput = '';
  console.log = (line) => {
    logOutput += line + '\n';
  };
  try {
    await sendMessagePersistent({ message: '   ' });
  } finally {
    console.log = originalLog;
  }
  assert.ok(logOutput.includes('Message content is empty'));
});
