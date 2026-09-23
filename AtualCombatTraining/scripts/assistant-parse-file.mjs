#!/usr/bin/env node
/**
 * AI 设计助手 · 单文件解析验证脚本（FP-42）
 *
 * 用途：拿一份真实材料上传，查看提取与摘要效果（office/pdf 等格式的实测入口）。
 * 用法：
 *   node scripts/assistant-parse-file.mjs "E:/File/某材料.pptx"
 *   FILE=... SESSION_ID=已有会话 BASE=http://localhost:8082 node scripts/assistant-parse-file.mjs
 *
 * 输出：两态 parse-file 事件（files[].status 0→1）、提取字数、摘要预览、材料是否进入 artifact。
 */

import { readFile } from 'node:fs/promises';
import { basename } from 'node:path';

const BASE = process.env.BASE || 'http://localhost:8082';
const WS_BASE = process.env.WS_BASE || BASE.replace(/^http/, 'ws');
const FILE = process.env.FILE || process.argv[2];
const SESSION_ID = process.env.SESSION_ID || '';
const TIMEOUT_MS = Number(process.env.TIMEOUT_MS || 120000);

if (!FILE) {
  console.error('用法：node scripts/assistant-parse-file.mjs <文件路径>');
  process.exit(2);
}

const failures = [];
const check = (ok, message) => {
  console.log(`  ${ok ? '[OK]' : '[FAIL]'} ${message}`);
  if (!ok) failures.push(message);
};

async function api(method, path, body) {
  const resp = await fetch(BASE + path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await resp.text();
  const json = text ? JSON.parse(text) : null;
  if (json && json.code !== 0) {
    throw new Error(`${method} ${path} 失败 code=${json.code} message=${json.message}`);
  }
  return json ? json.data : null;
}

function openWs(sessionId) {
  const frames = [];
  const ws = new WebSocket(`${WS_BASE}/api/act/ws/chat?sessionId=${encodeURIComponent(sessionId)}`);
  ws.addEventListener('message', (event) => {
    const frame = JSON.parse(event.data);
    if (frame.type === 'ping') {
      ws.send(JSON.stringify({ type: 'pong' }));
      return;
    }
    frames.push(frame);
  });
  return {
    frames,
    close: () => ws.close(),
    ready: new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve);
      ws.addEventListener('error', () => reject(new Error('WS 连接失败')));
    }),
  };
}

/** 解析帧里的 data part（一个 part 一个 bizType JSON） */
function bizEvents(frames, after) {
  const result = [];
  let current = null;
  for (let i = after; i < frames.length; i++) {
    const frame = frames[i];
    if (frame.event === 'part-start' && frame.data.contentType === 'data') {
      current = { index: frame.data.index, text: '' };
    } else if (frame.event === 'part-content' && current && frame.data.index === current.index
      && frame.data.delta.type === 'data_delta') {
      current.text += frame.data.delta.data;
    } else if (frame.event === 'part-end' && current && frame.data.index === current.index) {
      try {
        result.push(JSON.parse(current.text));
      } catch (e) {
        result.push({ parseError: String(e.message) });
      }
      current = null;
    }
  }
  return result;
}

(async () => {
  console.log(`=== 单文件解析验证：${FILE} ===`);
  const sessionId = SESSION_ID || (await api('POST', '/api/act/assistant/sessions', {})).sessionId;
  console.log(`sessionId=${sessionId}`);
  const ws = openWs(sessionId);
  await ws.ready;

  const bytes = await readFile(FILE);
  const mark = ws.frames.length;
  const form = new FormData();
  form.append('file', new Blob([bytes]), basename(FILE));
  const started = Date.now();
  const uploadResp = await fetch(
    `${BASE}/api/act/assistant/files?sessionId=${encodeURIComponent(sessionId)}`,
    { method: 'POST', body: form });
  const uploadJson = await uploadResp.json();
  if (uploadJson.code !== 0) {
    console.error(`上传失败 code=${uploadJson.code} message=${uploadJson.message}`);
    process.exit(1);
  }
  const stored = uploadJson.data;
  console.log(`  上传完成 fileId=${stored.fileId} name=${stored.name} fileType=${stored.fileType} fileSize=${stored.fileSize}`);
  check(stored.fileSize === bytes.length, `fileSize 与本地一致（${stored.fileSize}）`);

  const deadline = Date.now() + TIMEOUT_MS;
  let events = [];
  while (Date.now() < deadline) {
    events = bizEvents(ws.frames, mark).filter((e) => e.bizType === 'biz-common-parse-file');
    if (events.length >= 2) break;
    await new Promise((r) => setTimeout(r, 200));
  }
  const elapsed = ((Date.now() - started) / 1000).toFixed(1);
  check(events.length >= 2, `收到两态 parse-file 事件（实得 ${events.length}，耗时 ${elapsed}s）`);
  if (events.length >= 2) {
    const done = events[1];
    const content = String(done.params.content || '');
    console.log(`  解析中：${String(events[0].params.content || '').slice(0, 100)}`);
    console.log(`  完成态：${content.slice(0, 300)}`);
    const match = content.match(/提取\s*(\d+)\s*字/);
    const chars = match ? Number(match[1]) : 0;
    check(chars > 0, `提取到文本（${chars} 字）`);
    if (chars === 0) {
      console.log('  提示：提取 0 字通常意味着是扫描件（无文本层），需 OCR，不在本期范围');
    }
    if (content.includes('解析失败')) {
      check(false, `解析失败：${content}`);
    }
    const summary = String(done.params.summary || '');
    console.log(`  摘要预览（${summary.length} 字）：${summary.slice(0, 200)}`);
    console.log(`  是否触发首尾截断：${content.includes('摘要已按首尾截断') ? '是（原文 >3 万字）' : '否'}`);
  }

  // 若该会话已绑定主题，确认材料进了 artifact
  const artifact = await api('GET', `/api/act/assistant/sessions/${sessionId}/artifact`);
  if (artifact && artifact.content && Array.isArray(artifact.content.materials)) {
    const found = artifact.content.materials.find((m) => m.id === stored.fileId);
    check(!!found, '材料已进入 artifact.materials');
    if (found) {
      console.log(`  artifact 条目：${JSON.stringify(found).slice(0, 260)}`);
    }
  } else {
    console.log('  （会话尚无主题，材料已挂会话，create_topic 后会自动归集进 artifact）');
  }

  ws.close();
  console.log(failures.length === 0 ? '\n  ✅ 单文件解析验证通过' : `\n  ❌ ${failures.length} 项失败`);
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((e) => {
  console.error(`脚本异常：${e.message}`);
  process.exit(1);
});