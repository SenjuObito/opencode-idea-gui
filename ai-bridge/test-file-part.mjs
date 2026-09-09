/**
 * 验证脚本：opencode SDK（@opencode-ai/sdk/v2，与插件 ai-bridge 同一客户端路径）
 * 是否支持以 file part 形式发送文件，以及不同 URL 形态落库后的实际 parts 结构。
 *
 * 流程：
 *   1. 创建临时会话
 *   2. 用 noReply: true（只落库、不触发模型推理）发送一条消息，parts 包含：
 *      - text part（用户输入 + @token 文本，模拟 TUI @ 引用）
 *      - file part A：data: URL（内存内容，模拟插件上传附件）
 *      - file part B：file:// URL（磁盘文件，模拟 TUI @ 文件引用）
 *      - file part C：data: URL 的 PNG（1x1 像素，模拟图片附件）
 *   3. GET /session/{id}/message 拉取落库消息，打印每个 part 的关键字段
 *   4. 清理：DELETE 测试会话 + 删除临时文件
 *
 * 运行：node .zcode/skills/opencode-api-verify/scripts/test-file-part.mjs
 * 可选环境变量：OPENCODE_URL（默认 http://127.0.0.1:4096）
 */
import { createOpencodeClient } from '@opencode-ai/sdk/v2';
import { writeFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const BASE_URL = process.env.OPENCODE_URL || 'http://127.0.0.1:4096';
const client = createOpencodeClient({ baseUrl: BASE_URL });

// 1x1 透明 PNG 的 base64（不含 data: 前缀）
const TINY_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';

function summaryPart(part) {
  const s = { id: part.id, type: part.type };
  if (part.type === 'text') {
    s.synthetic = part.synthetic ?? false;
    s.text = part.text.length > 80 ? `${part.text.slice(0, 80)}… (${part.text.length} chars)` : part.text;
  }
  if (part.type === 'file') {
    s.mime = part.mime;
    s.filename = part.filename;
    s.urlProtocol = part.url?.split(':')[0];
    s.urlPreview = part.url?.length > 60 ? `${part.url.slice(0, 60)}… (${part.url.length} chars)` : part.url;
    if (part.source) {
      s.source = {
        type: part.source.type,
        path: part.source.path,
        textSpan: part.source.text
          ? { start: part.source.text.start, end: part.source.text.end, value: part.source.text.value }
          : undefined,
      };
    }
  }
  return s;
}

// ── 0. 健康检查 ────────────────────────────────────────────────────────────
const health = await client.global.health();
if (health.error) {
  console.error('❌ opencode serve 不可用:', BASE_URL, JSON.stringify(health.error));
  process.exit(1);
}
console.log('✅ opencode serve 可用:', JSON.stringify(health.data));

// ── 1. 准备磁盘文件（file:// 引用用） ──────────────────────────────────────
const tmpDir = mkdtempSync(join(tmpdir(), 'oc-filepart-verify-'));
const diskFilePath = join(tmpDir, 'notes.txt');
writeFileSync(diskFilePath, 'Hello from a disk file!\nline2: file:// part verify\n', 'utf-8');
console.log('✅ 临时磁盘文件:', diskFilePath);

// ── 2. 创建会话 ────────────────────────────────────────────────────────────
const created = await client.session.create({ body: {} });
if (created.error || !created.data?.id) {
  console.error('❌ 创建会话失败:', JSON.stringify(created.error ?? created.data));
  process.exit(1);
}
const sessionId = created.data.id;
console.log('✅ 测试会话:', sessionId);

// ── 3. 发送消息（noReply: 只落库不推理） ────────────────────────────────────
const promptBody = {
  parts: [
    {
      type: 'text',
      text: 'Check these files please.\n@notes.txt',
    },
    {
      type: 'file',
      mime: 'text/plain',
      filename: 'memory.txt',
      url: 'data:text/plain;base64,' + Buffer.from('inline content from data: URL\n', 'utf-8').toString('base64'),
    },
    {
      type: 'file',
      mime: 'text/plain',
      filename: 'notes.txt',
      url: 'file://' + diskFilePath,
    },
    {
      type: 'file',
      mime: 'image/png',
      filename: 'pixel.png',
      url: 'data:image/png;base64,' + TINY_PNG_BASE64,
    },
  ],
  noReply: true,
};

const promptResult = await client.session.promptAsync({
  path: { id: sessionId },
  body: promptBody,
});
console.log('\n── prompt_async 结果 ──');
if (promptResult.error) {
  console.error('❌ 发送失败:', JSON.stringify(promptResult.error, null, 2));
} else {
  console.log('✅ HTTP 状态:', promptResult.response?.status ?? '(accepted)');
}

// ── 4. 拉取落库消息 ────────────────────────────────────────────────────────
// noReply 落库是异步的，稍等后拉取
await new Promise((r) => setTimeout(r, 1500));
const messages = await client.session.messages({ path: { id: sessionId } });
console.log('\n── 落库消息 parts（关键字段） ──');
if (messages.error) {
  console.error('❌ 拉取消息失败:', JSON.stringify(messages.error));
} else {
  const entries = messages.data ?? [];
  console.log(`消息条数: ${entries.length}`);
  for (const entry of entries) {
    const info = entry.info ?? {};
    console.log(`\n[message] role=${info.role} id=${info.id}`);
    for (const part of entry.parts ?? []) {
      console.log('  part:', JSON.stringify(summaryPart(part)));
    }
  }
}

// ── 5. 清理 ────────────────────────────────────────────────────────────────
const del = await client.session.remove({ path: { id: sessionId } });
console.log('\n── 清理 ──');
console.log(del.error ? '⚠️ 删除会话失败: ' + JSON.stringify(del.error) : '✅ 测试会话已删除');
rmSync(tmpDir, { recursive: true, force: true });
console.log('✅ 临时文件已清理');
