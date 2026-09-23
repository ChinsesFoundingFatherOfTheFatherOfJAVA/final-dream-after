#!/usr/bin/env node
/**
 * AI 设计助手 · 真 Dify 全链路打样脚本
 *
 * 覆盖「打样清单」的自动化部分（不依赖人的主观判断）：
 *   建会话 → 连 WS → create_topic（草稿+事件）→ generate_theme（**渐次出现**：多段 in_progress + completed）
 *   → 校验生成产物结构（场景数/维度数/权重合计）→ generate_cases（逐场景事件）
 *   → 确认落库（save-success）→ 发布 → 轮询 script-progress 至已发布。
 *
 * 用法：node scripts/assistant-dify-smoke.mjs
 *   GOAL / AUDIENCE / MATERIAL 可覆盖生成输入；THEME_GEN_TIMEOUT_MS 等可调等待上限。
 * 说明：耗时较长（主题生成 1~3 分钟、案例逐场景），请给足超时（建议 10 分钟以上）。
 */

const BASE = process.env.BASE || 'http://localhost:8082';
const WS_BASE = process.env.WS_BASE || BASE.replace(/^http/, 'ws');
const GOAL = process.env.GOAL || '提升订单处理规范与异常应对能力';
const AUDIENCES = (process.env.AUDIENCE || '订单运营专员').split(',');
const MATERIAL = process.env.MATERIAL || '';
const THEME_WAIT_MS = Number(process.env.THEME_WAIT_MS || 360000);
const CASE_WAIT_MS = Number(process.env.CASE_WAIT_MS || 480000);
const PROGRESS_WAIT_MS = Number(process.env.PROGRESS_WAIT_MS || 120000);

const uuid = () => crypto.randomUUID().replace(/-/g, '');
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
    mark: () => frames.length,
    send: (obj) => ws.send(JSON.stringify(obj)),
    close: () => ws.close(),
    ready: new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve);
      ws.addEventListener('error', () => reject(new Error('WS 连接失败')));
    }),
  };
}

/** 解析帧里的 data part */
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

const byBiz = (frames, after, bizType) => bizEvents(frames, after).filter((e) => e.bizType === bizType);

/** 轮询等待满足条件（predicate 可为同步或异步；异步需返回真值才结束） */
async function waitUntil(label, timeoutMs, predicate) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await predicate();
    if (value) return value;
    if (Date.now() > deadline) throw new Error(`等待「${label}」超时（${Math.round(timeoutMs / 1000)}s）`);
    await new Promise((r) => setTimeout(r, 300));
  }
}

const weightsSum = (weights) => (weights || []).reduce((s, w) => s + Number(w.weight || 0), 0);

(async () => {
  console.log('=== 真 Dify 全链路打样 ===');
  const sessionId = (await api('POST', '/api/act/assistant/sessions', {})).sessionId;
  console.log(`sessionId=${sessionId}`);
  const ws = openWs(sessionId);
  await ws.ready;
  console.log('WS 已连接');

  // ---------- 1. create_topic ----------
  console.log('\n[1/6] create_topic（建草稿主题）');
  const topicMark = ws.mark();
  const topic = await api('POST',
    `/api/act/internal/tools/create_topic?traceId=${uuid()}&sessionId=${sessionId}`,
    { topic: '订单运营职能实战演练', description: `${GOAL}，面向${AUDIENCES.join('、')}` });
  check(topic.created === true, 'data.created=true（新建草稿）');
  const themeId = topic.themeId;
  console.log(`  themeId=${themeId}`);
  await waitUntil('topic-changed 事件', 15000, () => byBiz(ws.frames, topicMark, 'biz-side-manage-topic-changed')[0]);

  // ---------- 2. generate_theme（渐次出现） ----------
  console.log('\n[2/6] generate_theme（观察渐次出现）');
  const genMark = ws.mark();
  const t0 = Date.now();
  const accepted = await api('POST',
    `/api/act/internal/tools/generate_theme?traceId=${uuid()}&sessionId=${sessionId}`,
    { goal: GOAL, audiences: AUDIENCES, materialSummary: MATERIAL, keepExisting: false });
  check(accepted.accepted === true, 'data.accepted=true（受理即返回）');
  console.log(`  受理耗时 ${((Date.now() - t0) / 1000).toFixed(1)}s（应立即返回）`);

  const completedEvent = await waitUntil('artifact-generated completed', THEME_WAIT_MS,
    () => byBiz(ws.frames, genMark, 'biz-side-manage-artifact-generated')
      .find((e) => e.params.status === 'completed'));

  const genEvents = byBiz(ws.frames, genMark, 'biz-side-manage-artifact-generated');
  const inProgress = genEvents.filter((e) => e.params.status === 'in_progress');
  const failed = genEvents.find((e) => e.params.status === 'failed');
  check(!failed, `无 failed 事件${failed ? `（${failed.params.error}）` : ''}`);
  console.log(`  生成耗时 ${((Date.now() - t0) / 1000).toFixed(1)}s`);
  console.log(`  in_progress 事件数=${inProgress.length} completed=${completedEvent ? 1 : 0}`);

  // 渐次出现的证据：至少 2 段 in_progress，且每段 content 里的场景数递增/非空
  const partialScenarios = inProgress.map((e) => {
    const content = e.params.content;
    return content && Array.isArray(content.scenarios) ? content.scenarios.length : 0;
  });
  console.log(`  各段 in_progress 的场景数：${partialScenarios.join(' → ') || '（无 content）'}`);
  check(inProgress.length >= 2, `渐次出现：至少 2 段 in_progress（实得 ${inProgress.length}）`);
  check(partialScenarios.some((n) => n > 0), 'in_progress 事件已携带部分产物（场景已就绪）');
  const withStage = inProgress.filter((e) => e.params.stage);
  console.log(`  带 stage 字段的段数=${withStage.length}（${withStage.map((e) => e.params.stage).join(' / ')}）`);

  // ---------- 3. 校验产物结构 ----------
  console.log('\n[3/6] 校验生成产物结构（03 §3.5 规则）');
  const artifact = await api('GET', `/api/act/assistant/sessions/${sessionId}/artifact`);
  const content = artifact.content;
  const scenarios = content.scenarios || [];
  const dimensions = (content.evaluationCriteria && content.evaluationCriteria.dimensions) || [];
  const profiles = content.customerProfiles || [];
  const iceberg = content.icebergModel || {};
  check(scenarios.length >= 4 && scenarios.length <= 7, `场景数 4~7（实得 ${scenarios.length}）`);
  check(dimensions.length >= 3 && dimensions.length <= 6, `评估维度 3~6（实得 ${dimensions.length}）`);
  // 注：03 §3.5 的校验规则未包含画像条数，但 FP-36 要求画像 2~4 个；此处按需求意图断言，便于尽早暴露生成质量问题
  check(profiles.length >= 2, `客户画像 ≥2（实得 ${profiles.length}）`);
  check(!!iceberg.visible && !!iceberg.hidden, '冰山模型六层存在');
  const badWeights = scenarios.filter((s) => Math.abs(weightsSum(s.dimensionWeights) - 100) > 0.01);
  check(badWeights.length === 0, `每场景权重合计=100（异常场景数 ${badWeights.length}）`);
  const defaultWeights = (content.evaluationCriteria && content.evaluationCriteria.defaultDimensionWeights) || [];
  check(Math.abs(weightsSum(defaultWeights) - 100) < 0.01, `默认权重合计=100（${weightsSum(defaultWeights)}）`);
  const dimIds = new Set(dimensions.map((d) => d.id));
  const badRefs = scenarios.flatMap((s) => (s.dimensionWeights || []).filter((w) => !dimIds.has(w.dimensionId)));
  check(badRefs.length === 0, `场景维度引用均存在（异常引用 ${badRefs.length}）`);
  console.log(`  场景：${scenarios.map((s) => s.name).slice(0, 3).join(' / ')}…`);
  console.log(`  画像：${profiles.map((p) => p.type).join('、')}`);
  console.log(`  维度：${dimensions.map((d) => d.name).join('、')}`);

  // ---------- 4. generate_cases（逐场景） ----------
  console.log('\n[4/6] generate_cases（逐场景生成，观察逐个到达）');
  const caseMark = ws.mark();
  const t1 = Date.now();
  const caseAccepted = await api('POST',
    `/api/act/internal/tools/generate_cases?traceId=${uuid()}&sessionId=${sessionId}`,
    { scenarioIds: [], difficulty: 2 });
  check(caseAccepted.accepted === true, 'data.accepted=true');
  const expected = scenarios.length;
  await waitUntil('全部场景案例完成', CASE_WAIT_MS, () => {
    const done = byBiz(ws.frames, caseMark, 'biz-side-manage-case-generated')
      .filter((e) => e.params.status === 'completed');
    return done.length >= expected ? done : null;
  });
  const caseEvents = byBiz(ws.frames, caseMark, 'biz-side-manage-case-generated');
  const caseDone = caseEvents.filter((e) => e.params.status === 'completed');
  const caseFailed = caseEvents.filter((e) => e.params.status === 'failed');
  console.log(`  案例耗时 ${((Date.now() - t1) / 1000).toFixed(1)}s，完成 ${caseDone.length} / 预期 ${expected}，失败 ${caseFailed.length}`);
  check(caseDone.length === expected, `每个场景都产出案例（${caseDone.length}/${expected}）`);
  check(caseFailed.length === 0, `无场景失败${caseFailed.length ? `（${caseFailed.map((e) => e.params.error).join('; ')}）` : ''}`);
  console.log(`  案例名：${caseDone.map((e) => e.params.scriptName).slice(0, 2).join(' / ')}…`);

  const artifactAfterCases = await api('GET', `/api/act/assistant/sessions/${sessionId}/artifact`);
  const withCases = (artifactAfterCases.content.scenarios || []).filter((s) => (s.cases || []).length > 0);
  check(withCases.length === expected, `artifact 中带案例的场景数=${expected}（实得 ${withCases.length}）`);
  const opening = withCases[0] && withCases[0].cases[0] ? withCases[0].cases[0].scriptOpening : '';
  check(String(opening).length >= 40, `开场白长度 ≥40 字（实得 ${String(opening).length}）`);
  console.log(`  示例开场白：${String(opening).slice(0, 120)}`);

  // ---------- 5. 确认落库 ----------
  console.log('\n[5/6] 确认落库（biz-side-manage-confirm）');
  const confirmMark = ws.mark();
  const current = await api('GET', `/api/act/assistant/sessions/${sessionId}/artifact`);
  ws.send({
    type: 'chat',
    payload: {
      message: {
        id: uuid(),
        role: 'user',
        content: [{
          type: 'data',
          data: {
            type: 'action',
            actionName: 'biz-side-manage-confirm',
            params: { id: current.artifactId, version: Number(current.versionNo) },
          },
        }],
        sender: { id: 'admin', name: '管理员', avatar: '' },
      },
    },
    traceId: uuid(),
    spanId: uuid().slice(0, 16),
  });
  const saveSuccess = await waitUntil('save-success', 60000,
    () => byBiz(ws.frames, confirmMark, 'biz-side-manage-save-success')[0]);
  check(saveSuccess.params.ex_id === themeId, 'save-success.ex_id=主题 ID');
  const units = await api('GET', `/api/act/admin/theme/${themeId}/scenario-units`);
  check(units.length === expected, `业务表场景单元数=${expected}（实得 ${units.length}）`);
  const dimRows = units.reduce((s, u) => s + (u.dimensions || []).length, 0);
  console.log(`  维度行合计 ${dimRows}（按场景展开）`);

  // ---------- 6. 发布 ----------
  console.log('\n[6/6] 发布 + 初始化进度');
  const publish = await api('POST', `/api/act/admin/theme/${themeId}/publish`);
  check(publish.totalCases === expected, `totalCases=${expected}（实得 ${publish.totalCases}）`);
  const progress = await waitUntil('发布完成(status=3)', PROGRESS_WAIT_MS, async () => {
    const p = await api('GET', `/api/act/admin/theme/${themeId}/script-progress`);
    return p.status === 3 ? p : null;
  });
  check(progress.completed === expected && progress.failed === 0,
    `剧本编译完成 ${progress.completed}/${expected}，failed=${progress.failed}`);
  check(progress.progress === 100, `progress=100（实得 ${progress.progress}）`);
  const detail = await api('GET', `/api/act/admin/theme/${themeId}`);
  check(detail.status === 3 && detail.versionCount === 2,
    `已发布且版本 +1（status=${detail.status} versionCount=${detail.versionCount}）`);

  ws.close();
  console.log('\n=== 结果 ===');
  if (failures.length === 0) {
    console.log('  ✅ 真 Dify 全链路通过：渐次出现 → 产物结构合法 → 逐场景案例 → 确认落库 → 发布已就绪');
    console.log(`  themeId=${themeId}（可在管理端列表查看）`);
    process.exit(0);
  }
  console.log(`  ❌ ${failures.length} 项失败：`);
  failures.forEach((f) => console.log(`     - ${f}`));
  process.exit(1);
})().catch((e) => {
  console.error(`\n脚本异常：${e.message}`);
  process.exit(1);
});