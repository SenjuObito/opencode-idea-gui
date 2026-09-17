/**
 * Shared CLI binary path resolution for the opencode CLI.
 *
 * Priority:
 * 1. Explicit env overrides
 * 2. PATH lookup (`which` / `where`)
 * 3. Common home install candidates
 * 4. Bare binary name fallback
 *
 * Windows note: npm global installs create three shims (`pi`, `pi.cmd`, `pi.ps1`).
 * `where pi` often lists the extensionless bash wrapper first. Node's
 * `spawn()` cannot CreateProcess that file (ENOENT). Prefer `.cmd` / `.exe`
 * and shell-spawn `.cmd`/`.bat` (see `isWindowsCmdShim`).
 */

import { existsSync } from 'fs';
import { homedir } from 'os';
import { join, isAbsolute } from 'path';
import { execFileSync, execSync } from 'child_process';

/** Extensions Node can CreateProcess on Windows (with shell for .cmd/.bat). */
const WINDOWS_SPAWNABLE_EXT = /\.(cmd|bat|exe)$/i;
/** Prefer real PE binaries, then cmd shims, over extensionless npm wrappers. */
const WINDOWS_SPAWNABLE_PRIORITY = ['.exe', '.cmd', '.bat'];

/**
 * Windows npm global installs only ship a `.cmd` / `.bat` shim (no `.exe`),
 * and Node cannot spawn those without `shell: true`.
 * @param {string} bin - resolved binary path or bare name
 * @returns {boolean}
 */
export function isWindowsCmdShim(bin) {
  return process.platform === 'win32' && /\.(cmd|bat)$/i.test(String(bin || ''));
}

/**
 * Whether the resolved binary must be spawned through a shell on Windows.
 *
 * True for `.cmd`/`.bat` shims AND for bare command names (no path, no
 * extension). Node's `spawn()` without a shell cannot resolve a bare name via
 * PATHEXT, so on Windows we must hand it to `cmd /c` to let PATHEXT pick the
 * real `.exe`/`.cmd`. Absolute/relative paths with an extension (`.exe`, `.com`,
 * …) are left to `CreateProcess` directly.
 *
 * @param {string} bin
 * @returns {boolean}
 */
export function needsShellOnWindows(bin) {
  if (process.platform !== 'win32') return false;
  const s = String(bin || '');
  if (/\.(cmd|bat)$/i.test(s)) return true;
  const looksLikePath = /[\\/]/.test(s) || /^[A-Za-z]:/.test(s);
  const hasExt = /\.[a-z0-9]+$/i.test(s);
  return !looksLikePath && !hasExt;
}

/**
 * Pick the best match from `where` output lines on Windows.
 * Prefer `.exe` / `.cmd` / `.bat` over extensionless npm bash shims.
 *
 * @param {string[]|null|undefined} matches
 * @returns {string|null}
 */
export function selectWindowsWhereMatch(matches) {
  const lines = (Array.isArray(matches) ? matches : [])
    .map((line) => String(line || '').trim())
    .filter(Boolean);
  if (lines.length === 0) return null;

  for (const ext of WINDOWS_SPAWNABLE_PRIORITY) {
    const hit = lines.find((line) => line.toLowerCase().endsWith(ext));
    if (hit) return hit;
  }
  return lines[0];
}

/**
 * If `bin` is an absolute/relative path without a spawnable Windows extension
 * and a sibling `.exe`/`.cmd`/`.bat` exists, return that sibling.
 *
 * Bare names (`pi`) are left unchanged so PATH+PATHEXT still apply at spawn.
 *
 * @param {string} bin
 * @param {(path: string) => boolean} [existsFn]
 * @param {boolean} [forceWindows] - test hook; defaults to process.platform === 'win32'
 * @returns {string}
 */
export function resolveWindowsSpawnableBin(
  bin,
  existsFn = pathExists,
  forceWindows = process.platform === 'win32',
) {
  if (!forceWindows || typeof bin !== 'string') return bin;
  const trimmed = bin.trim();
  if (!trimmed) return bin;
  if (WINDOWS_SPAWNABLE_EXT.test(trimmed)) return trimmed;

  // Bare command names: let PATHEXT / shell resolve; do not invent a path.
  const looksLikePath = isAbsolute(trimmed)
    || trimmed.includes('/')
    || trimmed.includes('\\')
    || /^[A-Za-z]:/.test(trimmed);
  if (!looksLikePath) return trimmed;

  for (const ext of WINDOWS_SPAWNABLE_PRIORITY) {
    const candidate = `${trimmed}${ext}`;
    if (existsFn(candidate)) return candidate;
  }
  return trimmed;
}

function firstNonEmpty(...values) {
  for (const value of values) {
    if (typeof value === 'string') {
      const trimmed = value.trim();
      if (trimmed) return trimmed;
    }
  }
  return null;
}

function pathExists(candidate) {
  try {
    return typeof candidate === 'string' && candidate.length > 0 && existsSync(candidate);
  } catch {
    return false;
  }
}

function whichOnPath(binaryName) {
  try {
    if (process.platform === 'win32') {
      // Prefer execFile so the binary name is not re-parsed by a shell.
      // `where` lists every PATHEXT match; the extensionless npm shim is often first
      // and cannot be spawned — selectWindowsWhereMatch prefers .cmd/.exe.
      let output;
      try {
        output = execFileSync('where.exe', [binaryName], {
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'ignore'],
          env: process.env,
          windowsHide: true,
        });
      } catch {
        // Fallback for systems where where.exe is not on PATH of the IDE process.
        output = execSync(`where ${binaryName}`, {
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'ignore'],
          env: process.env,
          windowsHide: true,
        });
      }
      const lines = String(output || '').split(/\r?\n/);
      return selectWindowsWhereMatch(lines);
    }

    const output = execFileSync('which', [binaryName], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
      env: process.env,
    });
    const first = String(output || '')
      .split(/\r?\n/)
      .map((line) => line.trim())
      .find(Boolean);
    return first || null;
  } catch {
    return null;
  }
}

function resolveDirectoryToBinary(dirPath, binaryName) {
  if (!dirPath || typeof dirPath !== 'string') return null;
  const trimmed = dirPath.trim();
  if (!trimmed) return null;
  const win = process.platform === 'win32';
  const exes = win
    ? [`${binaryName}.cmd`, `${binaryName}.bat`, `${binaryName}.exe`, binaryName]
    : [binaryName];
  try {
    if (existsSync(trimmed)) {
      const st = fsStatSync(trimmed);
      if (st && st.isDirectory()) {
        for (const exe of exes) {
          const candidate = join(trimmed, exe);
          if (existsSync(candidate)) {
            console.error(`[cli-path] Found binary in configured directory: ${candidate}`);
            return candidate;
          }
        }
      }
    }
  } catch {
    // ignore filesystem errors
  }
  return null;
}

function fsStatSync(p) {
  try {
    return importFsStat(p);
  } catch {
    return null;
  }
}

function importFsStat(p) {
  try {
    const { statSync } = require('fs');
    return statSync(p);
  } catch {
    try {
      return existsSync(p) ? { isDirectory: () => false } : null;
    } catch {
      return null;
    }
  }
}

/**
 * @param {object} options
 * @param {string} options.binaryName - e.g. "grok" | "kimi" | "opencode"
 * @param {string[]} [options.envKeys] - env var names for path override
 * @param {string[]} [options.homeCandidates] - absolute-ish candidates under $HOME
 *   (use `{home}` placeholder or pass full relative segments)
 * @returns {string}
 */
export function resolveCliPath({ binaryName, envKeys = [], homeCandidates = [] }) {
  const win = process.platform === 'win32';
  console.error(`[cli-path] Resolving binary '${binaryName}' on platform '${process.platform}'...`);

  // npm global installs on Windows ship `.cmd` shims, not `.exe`.
  const exeNames = win
    ? [`${binaryName}.cmd`, `${binaryName}.bat`, `${binaryName}.exe`, binaryName]
    : [binaryName];

  const envOverride = firstNonEmpty(...envKeys.map((key) => process.env[key]));
  if (envOverride) {
    console.error(`[cli-path] Found env override for ${binaryName}: ${envOverride}`);
    // If override is a directory, look for binary inside it
    const fromDir = resolveDirectoryToBinary(envOverride, binaryName);
    if (fromDir) {
      return resolveWindowsSpawnableBin(fromDir);
    }
    return resolveWindowsSpawnableBin(envOverride);
  }

  // `where <name>` (no extension) honors PATHEXT; we then prefer .cmd/.exe.
  const fromPath = whichOnPath(binaryName);
  if (fromPath) {
    console.error(`[cli-path] Found ${binaryName} on PATH: ${fromPath}`);
    return resolveWindowsSpawnableBin(fromPath);
  }

  const home = homedir();
  for (const template of homeCandidates) {
    for (const exeName of exeNames) {
      const resolved = template
        .replace('{home}', home)
        .replace('{bin}', exeName)
        .replace('{name}', binaryName);
      if (pathExists(resolved)) {
        console.error(`[cli-path] Found ${binaryName} in candidate path: ${resolved}`);
        return resolveWindowsSpawnableBin(resolved);
      }
    }
  }

  // Extra Windows scans: NVM-windows and FNM version directories
  if (win && home) {
    const extraDirs = [];
    const appData = process.env.APPDATA || join(home, 'AppData', 'Roaming');
    const localAppData = process.env.LOCALAPPDATA || join(home, 'AppData', 'Local');
    
    // NVM Windows: %APPDATA%\nvm\v*\
    const nvmHome = process.env.NVM_HOME || join(appData, 'nvm');
    if (pathExists(nvmHome)) {
      try {
        const { readdirSync } = require('fs');
        const entries = readdirSync(nvmHome, { withFileTypes: true });
        for (const entry of entries) {
          if (entry.isDirectory() && entry.name.startsWith('v')) {
            extraDirs.push(join(nvmHome, entry.name));
          }
        }
      } catch {
        // ignore
      }
    }

    for (const dir of extraDirs) {
      const hit = resolveDirectoryToBinary(dir, binaryName);
      if (hit) {
        console.error(`[cli-path] Found ${binaryName} in version manager path: ${hit}`);
        return resolveWindowsSpawnableBin(hit);
      }
    }
  }

  console.error(`[cli-path] ${binaryName} not found in specific paths; falling back to bare name '${binaryName}'`);
  return binaryName;
}

/**
 * Prepend extra bin dirs to PATH when missing (IDE PATH is often sparse).
 * @param {NodeJS.ProcessEnv} env
 * @param {string[]} binDirs
 */
export function enrichPathWithBinDirs(env, binDirs = []) {
  const pathKey = process.platform === 'win32' ? 'Path' : 'PATH';
  const sep = process.platform === 'win32' ? ';' : ':';
  let current = env[pathKey] || env.PATH || '';
  const parts = current ? current.split(sep) : [];
  for (const dir of binDirs) {
    if (dir && !parts.includes(dir)) {
      parts.unshift(dir);
    }
  }
  env[pathKey] = parts.join(sep);
  if (pathKey !== 'PATH') {
    env.PATH = env[pathKey];
  }
}

/**
 * Common user-level CLI install dirs (IDE PATH is often sparse / no login shell).
 * Used both for binary resolution and spawn PATH enrichment.
 */
export function commonCliBinDirs(home = homedir()) {
  const dirs = [];
  if (!home) return dirs;
  dirs.push(
    join(home, '.opencode', 'bin'),
    join(home, '.local', 'share', 'opencode', 'bin'),
    join(home, '.local', 'bin'),
    join(home, '.cargo', 'bin'),
  );
  if (process.platform === 'win32') {
    // npm global bin dir on Windows (e.g. C:\Users\<user>\AppData\Roaming\npm).
    const appData = process.env.APPDATA || join(home, 'AppData', 'Roaming');
    dirs.push(join(appData, 'npm'));
    // pnpm global bin dir on Windows (e.g. C:\Users\<user>\AppData\Local\pnpm).
    const localAppData = process.env.LOCALAPPDATA || join(home, 'AppData', 'Local');
    dirs.push(join(localAppData, 'pnpm'));
    // Scoop shims (e.g. C:\Users\<user>\scoop\shims).
    dirs.push(join(home, 'scoop', 'shims'));
  } else {
    // pnpm global bin dir on macOS/Linux (e.g. ~/.local/share/pnpm).
    dirs.push(join(home, '.local', 'share', 'pnpm'));
  }
  return dirs;
}

export function resolveOpenCodeCliPath() {
  return resolveCliPath({
    binaryName: 'opencode',
    envKeys: ['OPENCODE_BIN', 'OPENCODE_PATH', 'OPENCODE_CLI_PATH'],
    homeCandidates: [
      '{home}/.opencode/bin/{bin}',
      '{home}/.local/bin/{bin}',
      '{home}/.local/share/opencode/bin/{bin}',
    ],
  });
}
