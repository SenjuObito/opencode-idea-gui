/**
 * Path utilities for CommonJS modules (e.g. favorites-service.cjs, session-titles-service.cjs).
 */

const fs = require('fs');
const path = require('path');
const os = require('os');

let cachedRealHomeDir = null;

function getRealHomeDir() {
  if (cachedRealHomeDir) {
    return cachedRealHomeDir;
  }
  const rawHome = os.homedir();
  try {
    cachedRealHomeDir = fs.realpathSync(rawHome);
  } catch {
    cachedRealHomeDir = rawHome;
  }
  return cachedRealHomeDir;
}

function getCodemossDir() {
  if (process.env.CODEMOSS_HOME) {
    return process.env.CODEMOSS_HOME;
  }
  return path.join(getRealHomeDir(), '.codemoss');
}

module.exports = {
  getRealHomeDir,
  getCodemossDir,
};
