#!/usr/bin/env node
/**
 * WS 帧探针：确认服务端 session-start / session-finish 是否携带 traceId。
 * 用法：node scripts/ws-finish-probe.mjs [BASE] [SESSION_ID]
 *   BASE 默认 http://localhost:8082（直连后端；经网关/代理同理，换地址即可）
 */
const BASE = process.argv[2] || process.env.BASE || 'http://localhost:8082';
const WS_BASE = BASE.replace(/^http/, 'ws');
const uuid = () => crypto.randomUUID().replace(/-/g, '');

async function api(method, path, body) {
  const resp = await fetch(BASE + path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await resp.text();
  const json = text ? JSON.parse(text) : null;
  if (json && json.code !== 0) throw new Error(`${path} 失败: ${json.message}`);
  return json ? json.data : null;
}

const sessionId = process.argv[3] || (await api('POST', '/api/act/assistant/sessions', {})).sessionId;
console.log(`sessionId=${sessionId}`);
const ws = new WebSocket(`${WS_BASE}/api/act/ws/chat?sessionId=${encodeURIComponent(sessionId)}`);
const traceId = uuid();
const seen = [];
ws.addEventListener('message', (ev) => {
  const f = JSON.parse(ev.data);
  if (f.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
  seen.push(f.event);
  if (f.event === 'session-start' || f.event === 'session-finish' || f.event === 'session-finish') {
    console.log(`  ${f.event}: traceId=${JSON.stringify(f.data?.traceId)} seq=${f.data?.seq}`);
  }
});
await new Promise((r) => { ws.addEventListener('open', r); });
ws.send(JSON.stringify({
  type: 'chat',
  payload: {
    message: {
      id: uuid(), role: 'user',
      content: [{ type: 'data', data: { type: 'action', actionName: 'common-session-start', params: { sessionId, projectId: '' } } }],
      sender: { id: 'admin', name: '管理员', avatar: '' },
    },
  },
  traceId, spanId: uuid().slice(0, 16),
}));
await new Promise((r) => setTimeout(r, 9000));
console.log(`收到事件: ${seen.join(' → ')}`);
console.log(seen.includes('session-finish') && seen.includes('session-start')
  ? '✅ 帧序列完整（若 session-start 的 traceId 非 null 且与请求 traceId 一致，说明后端已是新版本）'
  : '⚠️ 未收到完整帧序列（可能仍在生成中，属正常；重复握手帧场景只会看到 ack+finish）');
ws.close();
process.exit(0);