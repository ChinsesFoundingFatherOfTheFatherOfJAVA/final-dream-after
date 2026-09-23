#!/usr/bin/env node
/**
 * 对话主链路冒烟：建会话 → 连 WS → 发一条真实消息 → 收集事件与助手回复。
 *
 * 用途：验证编排智能体（Chatflow/Agent）是否真正接上——是否能回复、是否调用了工具端点。
 * 用法：node scripts/assistant-chat-turn.mjs "我想做一个订单运营职能的训练，目标是提升订单处理规范"
 *   BASE / TEXT 可用环境变量覆盖；TIMEOUT_MS 默认 120000。
 */

const BASE = process.env.BASE || 'http://localhost:8082';
const WS_BASE = process.env.WS_BASE || BASE.replace(/^http/, 'ws');
const TEXT = process.env.TEXT || process.argv[2] || '我想做一个订单运营职能的训练，目标是提升订单处理规范与异常应对能力';
const TIMEOUT_MS = Number(process.env.TIMEOUT_MS || 120000);

const uuid = () => crypto.randomUUID().replace(/-/g, '');

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

const sessionId = (await api('POST', '/api/act/assistant/sessions', {})).sessionId;
console.log(`sessionId=${sessionId}`);
console.log(`发送消息：${TEXT}`);

const frames = [];
const ws = new WebSocket(`${WS_BASE}/api/act/ws/chat?sessionId=${encodeURIComponent(sessionId)}`);
await new Promise((resolve, reject) => {
  ws.addEventListener('open', resolve);
  ws.addEventListener('error', () => reject(new Error('WS 连接失败')));
});

/** 累积 part：text 直接拼、data 整体解析 */
const parts = new Map();
const events = [];
ws.addEventListener('message', (event) => {
  const frame = JSON.parse(event.data);
  if (frame.type === 'ping') {
    ws.send(JSON.stringify({ type: 'pong' }));
    return;
  }
  events.push(frame);
  const d = frame.data || {};
  if (frame.event === 'part-start') {
    parts.set(d.index, { contentType: d.contentType, text: '' });
  } else if (frame.event === 'part-content') {
    const p = parts.get(d.index);
    if (p) p.text += d.delta.type === 'text_delta' ? d.delta.text : d.delta.data;
  }
  console.log(`  <- ${frame.event} seq=${d.seq}${d.index === undefined ? '' : ' index=' + d.index}`);
});

ws.send(JSON.stringify({
  type: 'chat',
  payload: {
    message: {
      id: uuid(),
      role: 'user',
      content: [{ type: 'text', text: TEXT, extendedData: {} }],
      sender: { id: 'admin', name: '管理员', avatar: '' },
    },
  },
  traceId: uuid(),
  spanId: uuid().slice(0, 16),
}));

const deadline = Date.now() + TIMEOUT_MS;
while (Date.now() < deadline) {
  if (events.some((e) => e.event === 'session-finish')) break;
  await new Promise((r) => setTimeout(r, 200));
}
ws.close();

const assistantText = [...parts.values()].filter((p) => p.contentType === 'text').map((p) => p.text).join('');
const dataParts = [...parts.values()].filter((p) => p.contentType === 'data').map((p) => p.text);
console.log('\n=== 助手回复 ===');
console.log(assistantText || '（无文本内容）');
if (dataParts.length) {
  console.log('\n=== 事件 data part ===');
  dataParts.forEach((d) => console.log(`  ${d.slice(0, 300)}`));
}
const finished = events.some((e) => e.event === 'session-finish');
console.log(`\n轮次收尾 session-finish：${finished ? '✅' : '❌ 超时未收尾'}`);
console.log(`助手回复长度：${assistantText.length} 字${assistantText.length === 0 ? '（❌ 智能体没有产出内容）' : ''}`);