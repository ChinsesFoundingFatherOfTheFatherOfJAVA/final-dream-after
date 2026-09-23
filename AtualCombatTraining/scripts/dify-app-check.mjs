#!/usr/bin/env node
/**
 * Dify 应用配置自检（开发期工具）
 *
 * 作用：对 application.properties 里的 3 个 app key 分别调用 `GET /v1/info`，
 *       打印每个 key 实际指向的应用名与 mode，用来排查"key 与 API 路由不匹配"这类问题
 *       （典型症状：`/v1/chat-messages` 返回 400 `not_chat_app`，或 `/v1/workflows/run` 报模式不符）。
 *
 * 期望结果：
 *   orchestrator-key → mode 为 agent-chat（或 chat）  ——编排智能体（对话主入口）
 *   theme-gen-key    → mode 为 workflow             ——主题生成工作流
 *   case-gen-key     → mode 为 workflow             ——案例生成工作流
 *
 * 用法：node scripts/dify-app-check.mjs
 * 安全：key 从 properties 读取，仅用于本次只读请求，不打印 key 本身。
 */

import { readFile } from 'node:fs/promises';

const PROPERTIES = process.env.PROPERTIES || 'src/main/resources/application.properties';

async function loadConfig() {
  const text = await readFile(PROPERTIES, 'utf8');
  const map = new Map();
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#') || !trimmed.includes('=')) continue;
    const idx = trimmed.indexOf('=');
    map.set(trimmed.slice(0, idx).trim(), trimmed.slice(idx + 1).trim());
  }
  return {
    baseUrl: map.get('dify.base-url'),
    keys: {
      'orchestrator-key（期望 agent-chat）': map.get('dify.orchestrator-key'),
      'theme-gen-key（期望 workflow）': map.get('dify.theme-gen-key'),
      'case-gen-key（期望 workflow）': map.get('dify.case-gen-key'),
    },
  };
}

const { baseUrl, keys } = await loadConfig();
if (!baseUrl) {
  console.error('properties 缺少 dify.base-url');
  process.exit(1);
}
console.log(`=== Dify 应用自检（${baseUrl}；key 不打印）===\n`);

for (const [label, key] of Object.entries(keys)) {
  if (!key) {
    console.log(`${label}: ❌ properties 里未配置`);
    continue;
  }
  try {
    const resp = await fetch(`${baseUrl}/info`, { headers: { Authorization: `Bearer ${key}` } });
    const text = await resp.text();
    if (resp.status !== 200) {
      console.log(`${label}: ❌ HTTP ${resp.status} ${text.slice(0, 200)}`);
      continue;
    }
    const info = JSON.parse(text);
    const ok = label.includes('agent-chat')
      ? ['agent-chat', 'chat'].includes(info.mode)
      : info.mode === 'workflow';
    console.log(`${label}: ${ok ? '✅' : '⚠️'} name=《${info.name}》 mode=${info.mode}`);
    if (info.description) {
      console.log(`    description=${String(info.description).slice(0, 120)}`);
    }
  } catch (e) {
    console.log(`${label}: ❌ 请求失败 ${e.message}`);
  }
}

console.log('\n提示：若 orchestrator-key 的 mode 是 workflow，则 /v1/chat-messages 会返回 400 not_chat_app ——'
  + ' 需要把 04a 编排智能体（agent-chat）应用的 API Key 填到 dify.orchestrator-key。');