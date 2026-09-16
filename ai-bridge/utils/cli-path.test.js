import test from 'node:test';
import assert from 'node:assert/strict';
import {
  isWindowsCmdShim,
  needsShellOnWindows,
  selectWindowsWhereMatch,
  resolveWindowsSpawnableBin,
  commonCliBinDirs,
} from './cli-path.js';

test('isWindowsCmdShim detects .cmd/.bat only on win32-style paths', () => {
  // Function gates on process.platform; we only assert the regex half via
  // known Windows-like paths when running on Windows, and always assert
  // non-matching extensions return false on any platform.
  assert.equal(isWindowsCmdShim('opencode.exe'), false);
  assert.equal(isWindowsCmdShim('pi'), false);
  assert.equal(isWindowsCmdShim('C:\\Users\\a\\AppData\\Roaming\\npm\\pi'), false);
  if (process.platform === 'win32') {
    assert.equal(isWindowsCmdShim('C:\\Users\\a\\AppData\\Roaming\\npm\\pi.cmd'), true);
    assert.equal(isWindowsCmdShim('opencode.bat'), true);
  } else {
    // Non-Windows: always false even for .cmd paths
    assert.equal(isWindowsCmdShim('C:\\Users\\a\\AppData\\Roaming\\npm\\pi.cmd'), false);
  }
});

test('selectWindowsWhereMatch prefers .cmd over extensionless npm shim', () => {
  const chosen = selectWindowsWhereMatch([
    'C:\\Users\\83429\\AppData\\Roaming\\npm\\pi',
    'C:\\Users\\83429\\AppData\\Roaming\\npm\\pi.cmd',
  ]);
  assert.equal(chosen, 'C:\\Users\\83429\\AppData\\Roaming\\npm\\pi.cmd');
});

test('selectWindowsWhereMatch prefers .exe when present', () => {
  const chosen = selectWindowsWhereMatch([
    'D:\\develop\\node-v24.13.1-win-x64\\opencode',
    'D:\\develop\\node-v24.13.1-win-x64\\opencode.exe',
  ]);
  assert.equal(chosen, 'D:\\develop\\node-v24.13.1-win-x64\\opencode.exe');
});

test('selectWindowsWhereMatch prefers .cmd over .ps1-only noise and keeps first good match', () => {
  const chosen = selectWindowsWhereMatch([
    'D:\\software\\nvm4w\\nodejs\\opencode',
    'D:\\software\\nvm4w\\nodejs\\opencode.ps1',
    'D:\\software\\nvm4w\\nodejs\\opencode.cmd',
  ]);
  assert.equal(chosen, 'D:\\software\\nvm4w\\nodejs\\opencode.cmd');
});

test('selectWindowsWhereMatch falls back to first line when no spawnable extension', () => {
  const chosen = selectWindowsWhereMatch([
    'C:\\tools\\pi',
    'C:\\other\\pi',
  ]);
  assert.equal(chosen, 'C:\\tools\\pi');
});

test('selectWindowsWhereMatch ignores blanks', () => {
  assert.equal(selectWindowsWhereMatch(['', '  ', 'C:\\x\\pi.cmd']), 'C:\\x\\pi.cmd');
  assert.equal(selectWindowsWhereMatch([]), null);
  assert.equal(selectWindowsWhereMatch(null), null);
});

test('resolveWindowsSpawnableBin upgrades extensionless path when sibling .cmd exists', () => {
  const exists = (p) => p === 'C:\\Users\\a\\AppData\\Roaming\\npm\\pi.cmd';
  const resolved = resolveWindowsSpawnableBin(
    'C:\\Users\\a\\AppData\\Roaming\\npm\\pi',
    exists,
    true, // force Windows behavior for cross-platform unit tests
  );
  assert.equal(resolved, 'C:\\Users\\a\\AppData\\Roaming\\npm\\pi.cmd');
});

test('resolveWindowsSpawnableBin prefers .exe over .cmd when both exist', () => {
  const exists = (p) =>
    p === 'D:\\node\\opencode.cmd' || p === 'D:\\node\\opencode.exe';
  const resolved = resolveWindowsSpawnableBin('D:\\node\\opencode', exists, true);
  assert.equal(resolved, 'D:\\node\\opencode.exe');
});

test('resolveWindowsSpawnableBin leaves .cmd paths unchanged', () => {
  const resolved = resolveWindowsSpawnableBin(
    'C:\\npm\\pi.cmd',
    () => false,
    true,
  );
  assert.equal(resolved, 'C:\\npm\\pi.cmd');
});

test('resolveWindowsSpawnableBin leaves bare names unchanged', () => {
  // Bare names rely on PATHEXT at spawn time; do not invent a path.
  const resolved = resolveWindowsSpawnableBin('pi', () => true, true);
  assert.equal(resolved, 'pi');
});

test('resolveWindowsSpawnableBin no-ops when forceWindows is false', () => {
  const exists = (p) => p === '/home/u/.local/bin/pi.cmd';
  const resolved = resolveWindowsSpawnableBin('/home/u/.local/bin/pi', exists, false);
  assert.equal(resolved, '/home/u/.local/bin/pi');
});

test('resolveWindowsSpawnableBin handles paths with spaces', () => {
  const base = 'C:\\Program Files\\nodejs\\opencode';
  const exists = (p) => p === `${base}.cmd`;
  const resolved = resolveWindowsSpawnableBin(base, exists, true);
  assert.equal(resolved, `${base}.cmd`);
});

// The resolveOmpCliPath / quoteCmdArg / decodeCliOutput / resolveCliSpawn /
// commonCliBinDirs tests were removed together with those helpers: cli-path.js
// is now opencode-only (no OMP/Pi providers, no cmd-shim spawn wrapper), so
// importing them only produced "does not provide an export named …" errors.

test('needsShellOnWindows forces a shell for .cmd/.bat and bare names on win32', () => {
  const orig = Object.getOwnPropertyDescriptor(process, 'platform');
  Object.defineProperty(process, 'platform', { value: 'win32', configurable: true });
  try {
    assert.equal(needsShellOnWindows('opencode.cmd'), true);
    assert.equal(needsShellOnWindows('opencode.bat'), true);
    // Bare command name: Node cannot resolve via PATHEXT without a shell.
    assert.equal(needsShellOnWindows('opencode'), true);
    // Absolute paths and extensions are left to CreateProcess directly.
    assert.equal(needsShellOnWindows('C:\\x\\opencode'), false);
    assert.equal(needsShellOnWindows('C:\\x\\opencode.exe'), false);
  } finally {
    Object.defineProperty(process, 'platform', orig);
  }
});

test('needsShellOnWindows is always false off win32', () => {
  const orig = Object.getOwnPropertyDescriptor(process, 'platform');
  Object.defineProperty(process, 'platform', { value: 'linux', configurable: true });
  try {
    assert.equal(needsShellOnWindows('opencode'), false);
    assert.equal(needsShellOnWindows('opencode.cmd'), false);
  } finally {
    Object.defineProperty(process, 'platform', orig);
  }
});

test('commonCliBinDirs covers pnpm global and Scoop on Windows', () => {
  const orig = Object.getOwnPropertyDescriptor(process, 'platform');
  Object.defineProperty(process, 'platform', { value: 'win32', configurable: true });
  try {
    const dirs = commonCliBinDirs('C:\\Users\\test');
    assert.ok(dirs.some((d) => /pnpm$/i.test(d)), 'should include pnpm global dir');
    assert.ok(dirs.some((d) => /scoop[\\/]shims$/i.test(d)), 'should include Scoop shims');
    assert.ok(dirs.some((d) => /Roaming[\\/]npm$/i.test(d)), 'should include npm global dir');
  } finally {
    Object.defineProperty(process, 'platform', orig);
  }
});

test('commonCliBinDirs covers pnpm global on POSIX', () => {
  const orig = Object.getOwnPropertyDescriptor(process, 'platform');
  Object.defineProperty(process, 'platform', { value: 'linux', configurable: true });
  try {
    const dirs = commonCliBinDirs('/home/test');
    assert.ok(
      dirs.some((d) => /\.local[\\/]share[\\/]pnpm$/.test(d)),
      'should include ~/.local/share/pnpm',
    );
  } finally {
    Object.defineProperty(process, 'platform', orig);
  }
});
