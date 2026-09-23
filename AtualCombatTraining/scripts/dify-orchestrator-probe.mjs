#!/usr/bin/env node
/**
 * 编排智能体可调用性探针（开发期工具）
 *
 * 目的：确认 `dify.orchestrator-key` 指向的应用**能不能被调用、以哪种方式调用**。
 * 会依次尝试：
 *   1. GET /info                     → 看应用 mode（chat/agent-chat/advanced-chat/workflow）
 *   2. POST /v1/chat-messages        → 对话型应用才可用（workflow 会返回 not_chat_app）
 *   3. POST /v1/workflows/run        → 工作流型应用才可用（需要开始节点有 query 输入 + 结束节点有输出）
 *
 * 用法：node scripts/dify-orchestrator-probe.mjs
 * 安全：key 从 properties 读取，只用于只读/最小调用，不打印。
 */

import { readFile } from 'node:fs/promises';

const PROPERTIES = process.env.PROPERTIES || 'src/main/resources/application.properties';

const text = await readFile(PROPERTIES, 'utf8');
const map = new Map();
for (const line of text.split(/\r?\n/)) {
  const t = line.trim();
  if (!t || t.startsWith('#') || !t.includes('=')) continue;
  map.set(t.slice(0, t.indexOf('=')).trim(), t.slice(t.indexOf('=') + 1).trim());
}
const baseUrl = map.get('dify.base-url');
const key = map.get('dify.orchestrator-key');
if (!baseUrl || !key) {
  console.error('properties 缺少 dify.base-url / dify.orchestrator-key');
  process.exit(1);
}
const auth = { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' };

// 1. 应用信息
const infoResp = await fetch(`${baseUrl}/info`, { headers: auth });
const info = infoResp.status === 200 ? await infoResp.json() : null;
console.log(`=== 编排应用自检 ===`);
console.log(`GET /info → ${infoResp.status}` + (info ? `  name=《${info.name}》 mode=${info.mode}` : ''));

// 2. 对话型入口
const chatResp = await fetch(`${baseUrl}/chat-messages`, {
  method: 'POST',
  headers: auth,
  body: JSON.stringify({
    inputs: {
      theme_status: 'new',
      artifact_summary: '',
      material_summary: '',
      // 试探：chat-messages 是否容忍"未声明的输入变量"（决定能否直接由 Java 传 session_id/trace_id）
      session_id: 'probe-session-id',
      trace_id: 'probe-trace-id',
    },
    query: '我想做一个订单运营职能的训练',
    response_mode: 'blocking',
    user: 'admin',
  }),
});
const chatText = await chatResp.text();
console.log(`\nPOST /v1/chat-messages → ${chatResp.status}`);
console.log(`  ${chatResp.status === 200 ? '✅ 可用（对话型应用）' : '❌ ' + chatText.slice(0, 220)}`);

// 3. 工作流型入口（仅在非 200 时试，避免无谓跑一次流程）
if (chatResp.status !== 200) {
  const wfResp = await fetch(`${baseUrl}/workflows/run`, {
    method: 'POST',
    headers: { ...auth, Accept: 'text/event-stream' },
    body: JSON.stringify({
      // 同时试探：Dify 是否容忍"未声明的输入变量"（前端若靠 inputs 传 sessionId/traceId 需要它）
      inputs: {
        theme_status: 'new',
        artifact_summary: '',
        material_summary: '',
        session_id: 'probe-session-id',
        trace_id: 'probe-trace-id',
        query: '我想做一个订单运营职能的训练',
      },
      response_mode: 'streaming',
      user: 'admin',
    }),
  });
  console.log(`\nPOST /v1/workflows/run → ${wfResp.status}`);
  if (wfResp.status !== 200) {
    console.log(`  ❌ ${(await wfResp.text()).slice(0, 300)}`);
  } else {
    let final = null;
    let status = null;
    let error = null;
    const decoder = new TextDecoder();
    let buffer = '';
    for await (const chunk of wfResp.body) {
      buffer += decoder.decode(chunk, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() ?? '';
      for (const line of lines) {
        const p = line.trim().startsWith('data:') ? line.trim().slice(5).trim() : line.trim();
        if (!p || p === '[DONE]') continue;
        let ev;
        try {
          ev = JSON.parse(p);
        } catch {
          continue;
        }
        const d = ev.data || {};
        if (ev.event === 'node_finished') {
          console.log(`  [node_finished] ${d.node_id} 「${d.title}」 status=${d.status}`
            + ` outputs=${Object.keys(d.outputs || {}).join(',') || '（无）'}`);
        } else if (ev.event === 'workflow_finished') {
          status = d.status;
          error = d.error;
          final = d.outputs || null;
        } else if (ev.event === 'error') {
          error = ev.message;
        }
      }
    }
    console.log(`  工作流 status=${status}${error ? ` error=${error}` : ''}`);
    console.log(`  最终 outputs=${final ? JSON.stringify(final).slice(0, 300) : '（无）'}`);
    if (!final || Object.keys(final).length === 0) {
      console.log('  ⚠️ 没有输出变量：工作流缺少「结束节点」输出 Agent 的回答，调用方取不到任何内容。');
    }
  }
}

console.log('\n=== 结论参考 ===');
console.log('对话型应用（mode=chat / agent-chat / advanced-chat）：走 /v1/chat-messages，可用 conversation_id 多轮。');
console.log('工作流型应用（mode=workflow）：走 /v1/workflows/run，需要开始节点有 query 输入、结束节点有输出，且无原生多轮记忆。');