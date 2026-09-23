#!/usr/bin/env node
/**
 * Dify 工作流诊断探针（开发期工具，用于调 prompt / 排查生成质量）
 *
 * 作用：以流式方式直连 Dify 主题生成工作流，打印每个节点的输出变量与长度，
 *       并给出最终 artifact_json 的关键结构统计（场景数/画像数/维度数/冰山各层条数）。
 *       用于判断"产物为空"到底是工作流（prompt/模型）问题还是 Java 管道问题。
 *
 * 用法：node scripts/dify-workflow-probe.mjs
 *   GOAL / AUDIENCE 可覆盖输入；APP=theme-gen|case-gen（默认 theme-gen）
 * 安全：API key 从 application.properties 读取，只用于本次请求，不打印、不落盘。
 */

import { readFile } from 'node:fs/promises';

const PROPERTIES = process.env.PROPERTIES || 'src/main/resources/application.properties';
const GOAL = process.env.GOAL || '提升订单处理规范与异常应对能力';
const AUDIENCE = process.env.AUDIENCE || '订单运营专员';
/** 材料摘要：空串模拟"用户没传材料"，非空模拟"已上传材料并解析" */
const MATERIAL = process.env.MATERIAL || '';

/** 从 properties 读取配置（不打印值） */
async function loadConfig() {
  const text = await readFile(PROPERTIES, 'utf8');
  const map = new Map();
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#') || !trimmed.includes('=')) continue;
    const idx = trimmed.indexOf('=');
    map.set(trimmed.slice(0, idx).trim(), trimmed.slice(idx + 1).trim());
  }
  const baseUrl = map.get('dify.base-url');
  const apiKey = map.get('dify.theme-gen-key');
  if (!baseUrl || !apiKey) throw new Error('properties 缺少 dify.base-url 或 dify.theme-gen-key');
  return { baseUrl, apiKey };
}

const { baseUrl, apiKey } = await loadConfig();
console.log(`=== Dify 主题生成工作流诊断（${baseUrl}，key 已读取，不回显）===`);
console.log(`输入：goal=${GOAL} audiences=[${AUDIENCE}] material_summary长度=${MATERIAL.length}`);

const body = {
  inputs: {
    goal: GOAL,
    audiences: JSON.stringify(AUDIENCE.split(',')),
    material_summary: MATERIAL,
    keep_existing: 'false',
    existing_scenes: '[]',
  },
  response_mode: 'streaming',
  user: 'admin',
};

const started = Date.now();
const resp = await fetch(`${baseUrl}/workflows/run`, {
  method: 'POST',
  headers: {
    Authorization: `Bearer ${apiKey}`,
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
  },
  body: JSON.stringify(body),
});

if (resp.status !== 200) {
  const text = await resp.text();
  console.error(`HTTP ${resp.status}: ${text.slice(0, 400)}`);
  process.exit(1);
}

let finalOutputs = null;
let status = null;
let error = null;
const decoder = new TextDecoder();
let buffer = '';

for await (const chunk of resp.body) {
  buffer += decoder.decode(chunk, { stream: true });
  const lines = buffer.split('\n');
  buffer = lines.pop() ?? '';
  for (const line of lines) {
    const payload = line.trim().startsWith('data:') ? line.trim().slice(5).trim() : line.trim();
    if (!payload || payload === '[DONE]' || payload.startsWith(':')) continue;
    let event;
    try {
      event = JSON.parse(payload);
    } catch {
      continue;
    }
    const data = event.data || {};
    if (event.event === 'node_finished') {
      const outputs = data.outputs || {};
      const summary = Object.entries(outputs).map(([k, v]) => {
        const len = typeof v === 'string' ? v.length : JSON.stringify(v).length;
        return `${k}(${len})`;
      }).join(', ');
      console.log(`  [node_finished] ${data.node_id} 「${data.title}」 status=${data.status} outputs: ${summary}`);
      if (data.status === 'succeeded') {
        for (const [key, value] of Object.entries(outputs)) {
          if (key === 'profiles_json' || key === 'iceberg_json' || key === 'scenes_json') {
            console.log(`      ${key} = ${String(value).slice(0, 260)}`);
          }
        }
      }
    } else if (event.event === 'workflow_finished') {
      status = data.status;
      error = data.error;
      finalOutputs = data.outputs || null;
    } else if (event.event === 'error') {
      error = event.message;
    }
  }
}

const seconds = ((Date.now() - started) / 1000).toFixed(1);
console.log(`\n工作流 status=${status} 耗时=${seconds}s${error ? ` error=${error}` : ''}`);

const artifactJson = finalOutputs && finalOutputs.artifact_json;
if (!artifactJson) {
  console.log('未取到 artifact_json（可能是工作流失败或版本差异）');
  process.exit(status === 'succeeded' ? 1 : 1);
}
const artifact = JSON.parse(artifactJson);
const scenarios = artifact.scenarios || [];
const profiles = artifact.customerProfiles || [];
const dimensions = (artifact.evaluationCriteria || {}).dimensions || [];
const iceberg = artifact.icebergModel || {};
const lens = (arr) => (Array.isArray(arr) ? arr.length : 0);
console.log('最终 artifact 结构统计：');
console.log(`  scenarios=${scenarios.length} customerProfiles=${profiles.length} dimensions=${dimensions.length}`);
console.log(`  iceberg.visible.knowledge=${lens(iceberg.visible && iceberg.visible.knowledge)}`
  + ` skills=${lens(iceberg.visible && iceberg.visible.skills)}`
  + ` roles=${lens(iceberg.hidden && iceberg.hidden.roles)}`
  + ` selfConcept=${lens(iceberg.hidden && iceberg.hidden.selfConcept)}`
  + ` personalities=${lens(iceberg.hidden && iceberg.hidden.personalities)}`
  + ` motives=${lens(iceberg.hidden && iceberg.hidden.motives)}`);
const sceneWeightsOk = scenarios.every((s) => {
  const sum = (s.dimensionWeights || []).reduce((acc, w) => acc + Number(w.weight || 0), 0);
  return Math.abs(sum - 100) < 0.01;
});
console.log(`  每场景权重合计=100：${sceneWeightsOk ? '是' : '否'}`);
if (profiles.length === 0) {
  console.log('\n结论：**工作流自身产出的 customerProfiles 为空**——问题在 Dify 侧（提示词/模型），不在 Java 管道。');
} else {
  console.log('\n结论：工作流产出了画像，问题若仍存在则需检查 Java 管道的合并/落库环节。');
}