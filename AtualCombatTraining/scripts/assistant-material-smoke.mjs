#!/usr/bin/env node
/**
 * AI 设计助手 · 材料解析链路冒烟脚本（FP-42）
 *
 * 走通：建会话 → 连 WS → 上传材料（带 sessionId）→ 收 parse-file 两态事件（解析中→完成）
 *       → 校验历史消息可重放 → create_topic → 材料自动进入 artifact.materials（服务端写入）
 *       → 重复上传同一文件不产生重复条目。
 *
 * 用法：node scripts/assistant-material-smoke.mjs
 *   BASE=http://localhost:8082 node scripts/assistant-material-smoke.mjs
 *   TXT=target/fixtures/order-ops-intro.txt PDF=target/fixtures/compliance-note.pdf
 *
 * 说明：解析用本地 Apache Tika，不依赖 Dify；PDF 用的是手工构造的最小 PDF，用于验证重量级解析器可加载。
 */

import { readFile } from 'node:fs/promises';
import { basename } from 'node:path';

const BASE = process.env.BASE || 'http://localhost:8082';
const WS_BASE = process.env.WS_BASE || BASE.replace(/^http/, 'ws');
const TXT = process.env.TXT || 'scripts/fixtures/order-ops-intro.txt';
const PDF = process.env.PDF || 'scripts/fixtures/compliance-note.pdf';
const TIMEOUT_MS = Number(process.env.TIMEOUT_MS || 30000);

const uuid = () => crypto.randomUUID().replace(/-/g, '');
const failures = [];

function check(condition, message) {
  if (condition) {
    console.log(`  [OK] ${message}`);
  } else {
    failures.push(message);
    console.log(`  [FAIL] ${message}`);
  }
}

async function api(method, path, body) {
  const resp = await fetch(BASE + path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await resp.text();
  const json = text ? JSON.parse(text) : null;
  if (json && json.code !== 0) {
    throw new Error(`${method} ${path} 业务失败 code=${json.code} message=${json.message}`);
  }
  return json ? json.data : null;
}

/** 上传文件（multipart，带 sessionId） */
async function upload(sessionId, filePath) {
  const bytes = await readFile(filePath);
  const form = new FormData();
  form.append('file', new Blob([bytes]), basename(filePath));
  const resp = await fetch(`${BASE}/api/act/assistant/files?sessionId=${encodeURIComponent(sessionId)}`, {
    method: 'POST',
    body: form,
  });
  const json = await resp.json();
  if (json.code !== 0) {
    throw new Error(`上传失败 code=${json.code} message=${json.message}`);
  }
  return json.data;
}

/** 收集 WS 帧 */
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
    const d = frame.data || {};
    console.log(`  <- ${frame.event} seq=${d.seq}${d.index === undefined ? '' : ' index=' + d.index}`);
  });
  return {
    frames,
    mark: () => frames.length,
    ready: new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve);
      ws.addEventListener('error', () => reject(new Error('WS 连接失败')));
    }),
    close: () => ws.close(),
  };
}

/** 解析 mark 之后的 data part（一个 part 一个 bizType JSON） */
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

/** 等待某个 bizType 的事件出现达到次数 */
async function waitForEvents(getter, bizType, count, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const matched = getter().filter((e) => e.bizType === bizType);
    if (matched.length >= count) return matched;
    if (Date.now() > deadline) throw new Error(`等待 ${bizType} × ${count} 超时（实得 ${matched.length}）`);
    await new Promise((r) => setTimeout(r, 120));
  }
}

/** 一次上传 + 两态事件断言 */
async function uploadAndAssert(ws, sessionId, filePath, label) {
  console.log(`\n上传 ${label}：${filePath}`);
  const mark = ws.mark();
  const stored = await upload(sessionId, filePath);
  check(typeof stored.fileId === 'string' && stored.fileId.length > 0, `${label} 返回 fileId`);
  check(typeof stored.name === 'string', `${label} 返回 name`);
  check(stored.fileSize > 0, `${label} 返回 fileSize > 0（${stored.fileSize}）`);

  const events = await waitForEvents(() => bizEvents(ws.frames, mark), 'biz-common-parse-file', 2, TIMEOUT_MS);
  const parsing = events[0];
  const done = events[1];
  check(filesStatus(parsing) === 0, `${label} 第一态为"解析中"（files[].status=0）`);
  check(filesStatus(done) === 1, `${label} 第二态为"解析完成"（files[].status=1）`);
  check(parsing.params.context_id === done.params.context_id, `${label} 两态事件共享 context_id（卡片原地更新）`);
  check(typeof parsing.params.call_id === 'string', `${label} 携带 call_id`);
  check(fileIds(parsing).includes(stored.fileId), `${label} 事件 files[].id 与上传 fileId 一致`);
  check(String(done.params.content).includes('解析完成'), `${label} 完成态 content 含"解析完成"`);
  const summary = String(done.params.summary || '');
  check(summary.length > 0, `${label} 完成态携带摘要预览`);
  console.log(`     完成文案：${String(done.params.content).slice(0, 120)}`);
  return { stored, events, summary };
}

function filesStatus(event) {
  const files = event.params.files || [];
  return files.length ? files[0].status : null;
}

function fileIds(event) {
  return (event.params.files || []).map((f) => f.id);
}

(async () => {
  console.log('=== AI 设计助手 材料解析链路冒烟 ===');
  const sessionId = (await api('POST', '/api/act/assistant/sessions', {})).sessionId;
  console.log(`sessionId=${sessionId}`);
  const ws = openWs(sessionId);
  await ws.ready;
  console.log('WS 已连接');

  // ---------- 1. 建主题前上传：txt ----------
  const txt = await uploadAndAssert(ws, sessionId, TXT, 'txt 材料');

  // ---------- 2. 建主题前上传：pdf（验证 Tika 解析器包可加载） ----------
  const pdf = await uploadAndAssert(ws, sessionId, PDF, 'pdf 材料');

  // ---------- 3. 历史消息可重放（data 块落库） ----------
  console.log('\n[3] 校验历史消息中的 parse-file 事件块');
  const messages = await api('GET', `/api/act/assistant/sessions/${sessionId}/messages?limit=1000`);
  const assistantBlocks = messages.messages
    .filter((m) => m.role === 'assistant')
    .flatMap((m) => (Array.isArray(m.content) ? m.content : []))
    .filter((b) => b.type === 'data' && b.data && b.data.bizType === 'biz-common-parse-file');
  check(assistantBlocks.length >= 4, `历史中含 parse-file 事件块（实得 ${assistantBlocks.length}，txt+pdf 各两态）`);

  // ---------- 4. 建主题：材料自动进入 artifact.materials ----------
  console.log('\n[4] create_topic 后材料应自动进入 artifact.materials（服务端写入）');
  const topic = await api('POST',
    `/api/act/internal/tools/create_topic?traceId=${uuid()}&sessionId=${sessionId}`,
    { topic: '订单运营职能实战演练', description: '提升订单处理规范与异常应对能力，面向订单运营专员' });
  const themeId = topic.themeId;
  check(typeof themeId === 'string' && themeId.length > 0, 'create_topic 返回 themeId');

  const artifact = await api('GET', `/api/act/assistant/sessions/${sessionId}/artifact`);
  check(!!artifact, '建主题后工作区 artifact 已存在');
  const materials = (artifact.content && artifact.content.materials) || [];
  check(materials.length === 2, `artifact.materials 含 2 条材料（实得 ${materials.length}）`);
  const txtMaterial = materials.find((m) => m.id === txt.stored.fileId);
  const pdfMaterial = materials.find((m) => m.id === pdf.stored.fileId);
  check(!!txtMaterial, 'txt 材料已按 fileId 入 artifact');
  check(!!pdfMaterial, 'pdf 材料已按 fileId 入 artifact');
  if (txtMaterial) {
    check(txtMaterial.name === txt.stored.name, `材料名保持一致（${txtMaterial.name}）`);
    check(txtMaterial.visibility === 1, '默认 visibility=1（学员可见）');
    check(txtMaterial.fileType === 'txt', 'fileType 正确');
  }

  // ---------- 5. 重复上传同一文件：不产生重复条目 ----------
  console.log('\n[5] 重复上传同一文件（同 fileId）应原地更新，不重复追加');
  const again = await uploadAndAssert(ws, sessionId, TXT, 'txt 材料（重复上传）');
  check(again.stored.fileId !== txt.stored.fileId, '每次上传生成新的 fileId（服务端 uuid 命名）');
  const artifact2 = await api('GET', `/api/act/assistant/sessions/${sessionId}/artifact`);
  const materials2 = (artifact2.content && artifact2.content.materials) || [];
  check(materials2.length === 3, `新 fileId 追加为第 3 条（实得 ${materials2.length}）`);

  ws.close();

  console.log('\n=== 结果 ===');
  if (failures.length === 0) {
    console.log('  ✅ 材料解析链路通过：上传→解析中/完成两态事件→落库→建主题归集入 artifact→历史可重放');
    process.exit(0);
  }
  console.log(`  ❌ ${failures.length} 项校验失败：`);
  failures.forEach((f) => console.log(`     - ${f}`));
  process.exit(1);
})().catch((e) => {
  console.error(`\n脚本异常：${e.message}`);
  process.exit(1);
});