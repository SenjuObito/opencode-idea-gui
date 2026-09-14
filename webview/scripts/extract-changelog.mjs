#!/usr/bin/env node

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const projectRoot = path.resolve(__dirname, '../..');
const changelogPath = path.join(projectRoot, 'CHANGELOG.md');

if (!fs.existsSync(changelogPath)) {
  console.error('Error: CHANGELOG.md not found at', changelogPath);
  process.exit(1);
}

const content = fs.readFileSync(changelogPath, 'utf8');

/**
 * Parse CHANGELOG.md into structured entries.
 * Handles three format eras:
 * - Newer (v0.1.7+): Bilingual with English and Chinese markers plus emoji section headers
 * - Mid (v0.1.4-v0.1.6): Bilingual with English and Chinese markers plus checkbox items
 * - Older (< v0.1.4): Chinese-only entries with checkbox items or plain text
 */
function parseChangelog(raw) {
  const entries = [];

  // Match both:
  // 1. ##### **2026年8月26日（v0.5.4）**
  // 2. ## 1.0.0-opencode.1 (2026-09-06) or ## [1.0.0] - 2026-09-06
  const headerRegex = /^(?:#{5}\s+\*\*(.+?)\*\*|##\s+(?:\[?v?([\w.-]+)\]?)\s*(?:\(([\d-]+)\)|-\s*([\d-]+))?)/gm;
  const headers = [];
  let match;

  while ((match = headerRegex.exec(raw)) !== null) {
    if (match[1]) {
      headers.push({
        type: 'bold',
        rawMatch: match[1].trim(),
        index: match.index,
        endIndex: match.index + match[0].length,
      });
    } else if (match[2]) {
      const ver = match[2].trim();
      if (!/^unreleased$/i.test(ver) && !/^format$/i.test(ver)) {
        headers.push({
          type: 'h2',
          version: ver.replace(/^v/, ''),
          date: (match[3] || match[4] || '').trim(),
          index: match.index,
          endIndex: match.index + match[0].length,
        });
      }
    }
  }

  for (let i = 0; i < headers.length; i++) {
    const header = headers[i];
    const nextIndex = i + 1 < headers.length ? headers[i + 1].index : raw.length;
    const sectionContent = raw.substring(header.endIndex, nextIndex).trim();

    let version = '';
    let date = '';

    if (header.type === 'h2') {
      version = header.version;
      date = header.date;
    } else {
      const versionMatch = header.rawMatch.match(/[（(]v?(\d+\.\d+(?:\.\d+)?(?:-[a-zA-Z0-9.]+)?)[）)]/);
      if (!versionMatch) continue;

      version = versionMatch[1];

      const fullDateMatch = header.rawMatch.match(/(\d{4})年(\d{1,2})月(\d{1,2})日/);
      if (fullDateMatch) {
        date = `${fullDateMatch[1]}-${fullDateMatch[2].padStart(2, '0')}-${fullDateMatch[3].padStart(2, '0')}`;
      } else {
        const partialDateMatch = header.rawMatch.match(/(\d{1,2})月(\d{1,2})日/);
        if (partialDateMatch) {
          date = `2025-${partialDateMatch[1].padStart(2, '0')}-${partialDateMatch[2].padStart(2, '0')}`;
        }
      }
    }

    const { en, zh } = splitBilingual(sectionContent);
    entries.push({ version, date, content: { en: en || zh, zh: zh || en } });
  }

  return entries;
}

/**
 * Split section content into English and Chinese parts.
 */
function splitBilingual(text) {
  // Try to find the English and Chinese section markers
  // Patterns: "English:" and the localized Chinese marker with either colon form
  const enMarkerRegex = /^English\s*[:：]/im;
  const zhMarkerRegex = /^中文\s*[:：]/im;

  const enMatch = enMarkerRegex.exec(text);
  const zhMatch = zhMarkerRegex.exec(text);

  if (enMatch && zhMatch) {
    // Both markers found - extract each section
    let enContent, zhContent;

    if (enMatch.index < zhMatch.index) {
      // English first, then Chinese
      enContent = text.substring(enMatch.index + enMatch[0].length, zhMatch.index).trim();
      zhContent = text.substring(zhMatch.index + zhMatch[0].length).trim();
    } else {
      // Chinese first, then English
      zhContent = text.substring(zhMatch.index + zhMatch[0].length, enMatch.index).trim();
      enContent = text.substring(enMatch.index + enMatch[0].length).trim();
    }

    return {
      en: cleanContent(enContent),
      zh: cleanContent(zhContent),
    };
  }

  // No bilingual markers - treat as Chinese content (older format)
  const cleaned = cleanContent(text);
  return { en: '', zh: cleaned };
}

/**
 * Clean up changelog content:
 * - Remove [x] checkbox markers
 * - Remove <img> tags
 * - Remove trailing ---
 * - Trim whitespace
 */
function cleanContent(text) {
  return text
    // Remove image tags
    .replace(/<img[^>]*>/g, '')
    // Remove [x] checkboxes
    .replace(/\[x\]\s*/g, '')
    // Remove horizontal rules
    .replace(/^---+\s*$/gm, '')
    // Trim each line
    .split('\n')
    .map(line => line.trimEnd())
    .join('\n')
    .trim();
}

/**
 * Escape string for use in TypeScript template literal or string.
 */
function escapeForTS(str) {
  return str
    .replace(/\\/g, '\\\\')
    .replace(/`/g, '\\`')
    .replace(/\$/g, '\\$');
}

// Parse
const entries = parseChangelog(content);
console.log(`Parsed ${entries.length} changelog entries`);

// Generate TypeScript file
const versionDir = path.join(__dirname, '../src/version');
if (!fs.existsSync(versionDir)) {
  fs.mkdirSync(versionDir, { recursive: true });
}

const entriesCode = entries.map(entry => {
  return `  {
    version: '${entry.version}',
    date: '${entry.date}',
    content: {
      en: \`${escapeForTS(entry.content.en)}\`,
      zh: \`${escapeForTS(entry.content.zh)}\`,
    },
  }`;
}).join(',\n');

const tsContent = `// Auto-generated changelog file
// This file is automatically generated during the build process from CHANGELOG.md
// Do not edit manually

export interface ChangelogEntry {
  version: string;
  date: string;
  content: {
    en: string;
    zh: string;
  };
}

export const CHANGELOG_DATA: ChangelogEntry[] = [
${entriesCode},
];
`;

const outputPath = path.join(versionDir, 'changelog.ts');

// Only write if content actually changed to avoid unnecessary git diffs
if (fs.existsSync(outputPath) && fs.readFileSync(outputPath, 'utf8') === tsContent) {
  console.log(`Changelog file unchanged, skipping write: ${outputPath}`);
} else {
  fs.writeFileSync(outputPath, tsContent);
  console.log(`Changelog file updated: ${outputPath}`);
}
