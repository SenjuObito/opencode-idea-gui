import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const cwd = process.cwd();
const distFile = path.resolve(cwd, 'dist/index.html');
const targetFiles = [
  path.resolve(cwd, '../src/main/resources/html/opencode-chat.html'),
  path.resolve(cwd, '../../opencode-vscode-plugin/dist/webview/index.html'),
  path.resolve(cwd, '../../vs-open-code-gui/dist/webview/index.html'),
];

const main = async () => {
  const html = await readFile(distFile, 'utf-8');
  for (const targetFile of targetFiles) {
    try {
      await mkdir(path.dirname(targetFile), { recursive: true });
      await writeFile(targetFile, html, 'utf-8');
      console.log(`[copy-dist] 已同步 ${distFile} -> ${targetFile}`);
    } catch {
      // Ignore if directory doesn't exist
    }
  }
};

main().catch((error) => {
  console.error('[copy-dist] 复制构建产物失败', error);
  process.exit(1);
});

