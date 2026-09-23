#!/usr/bin/env node
/**
 * AI 设计助手 · 工具/确认/发布 全链路冒烟脚本（无需 Dify）
 *
 * 走通：建会话 → 连 WS → create_topic（建草稿+推 topic-changed）→ update_artifact（推 artifact-updated）
 *       → 发 confirm action（推 save-success + 事务翻译落库）→ 校验业务表落库
 *       → publish（异步编译剧本）→ 轮询 script-progress 至已发布 → 校验版本 +1。
 *
 * 用法：node scripts/assistant-flow-smoke.mjs
 *   BASE=http://localhost:8082 node scripts/assistant-flow-smoke.mjs
 *
 * 说明：生成类工具（generate_theme/generate_cases）依赖 Dify，不在本脚本范围（需先配好 3 个 app key）。
 * 本脚本用 update_artifact 直接推入完整 artifact，等价于"生成完成后的产物状态"，用于验证确认与发布链路。
 */

const BASE = process.env.BASE || 'http://localhost:8082';
const WS_BASE = process.env.WS_BASE || BASE.replace(/^http/, 'ws');
const TIMEOUT_MS = Number(process.env.TIMEOUT_MS || 30000);
const PROGRESS_TIMEOUT_MS = Number(process.env.PROGRESS_TIMEOUT_MS || 60000);

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

async function request(method, path, body) {
  const resp = await fetch(BASE + path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await resp.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch (e) {
    throw new Error(`${method} ${path} 响应非 JSON：${text.slice(0, 200)}`);
  }
  if (json && json.code !== 0) {
    throw new Error(`${method} ${path} 业务失败 code=${json.code} message=${json.message}`);
  }
  return json ? json.data : null;
}

/** 测试用 artifact：4 个场景（各 1 个案例）+ 3 个全局维度，权重合计均为 100 */
function buildMaterials() {
  return [
    {
      id: 'file-1',
      name: '订单运营部职能介绍.pptx',
      fileType: 'pptx',
      fileSize: 2638848,
      fileSizeUnit: 'B',
      visibility: 1,
      fileServId: 'srv-1',
      fileSource: 2,
      targetId: '',
    },
    {
      id: 'file-2',
      name: '特殊包装与地址核对规范.pdf',
      fileType: 'pdf',
      fileSize: 102400,
      fileSizeUnit: 'B',
      visibility: 1,
      fileServId: 'srv-2',
      fileSource: 2,
      targetId: '',
    },
  ];
}

/** 测试用 artifact：4 个场景（各 1 个案例）+ 3 个全局维度，权重合计均为 100 */
function buildScenarios() {
  const w = (dimensionId, weight) => ({ dimensionId, weight });
  const caseOf = (name, difficulty) => ({
    scriptName: name,
    difficulty,
    actorName: '王敏',
    actorPosition: '运营主管',
    actorCompany: '宏达科技',
    actorType: '客户方对接负责人',
    actorPersonality: '急躁、强势、注重结果',
    actorCommunicationStyle: '语速快，习惯用期限施压',
    scriptBackground: '学员是订单运营专员，客户因昨日订单录入错误要求今天必须处理完毕，否则将影响其月度考核。',
    scriptOpening: '（把单据拍在桌上）你们昨天录错的那个地址，今天再不解决，我就直接找你们领导了。',
  });
  return [
    {
      id: 'sc-01',
      name: '开场接触：需求摸底',
      audiences: ['订单运营专员'],
      businessGoal: '建立信任并摸清客户真实诉求',
      behaviorGoals: ['提出开放式问题确认需求', '复述客户诉求并得到确认'],
      relatedIcebergElements: { knowledge: ['订单处理规范'], skills: ['异常识别'] },
      businessImpact: '影响后续方案匹配度',
      failureCost: '需求误判导致返工与投诉',
      coreConflict: '客户急于推进 vs 需完整信息核对',
      failureReasons: ['×未复核收货地址即提交'],
      difficulties: ['客户催促'],
      constraints: { forbidBehaviors: ['禁止跳过核对环节'], maxTime: 15 },
      relatedKnowledgeIds: [],
      relatedScriptIds: [],
      relatedProductProfileArtifactIds: [],
      caseArtifactIds: [],
      cases: [caseOf('高压催单下的精准录入：特殊包装与地址核对的生死线', 2)],
      dimensionWeights: [w('dim-01', 60), w('dim-02', 40)],
    },
    {
      id: 'sc-02',
      name: '信息核对：异常识别',
      audiences: ['订单运营专员'],
      businessGoal: '在压力下完成信息核对与异常识别',
      behaviorGoals: ['逐项核对关键字段', '主动暴露风险并给出方案'],
      relatedIcebergElements: { knowledge: ['订单处理规范', '特殊包装要求'], skills: ['异常识别'] },
      businessImpact: '影响交付准确率',
      failureCost: '错发导致赔付成本',
      coreConflict: '客户要求快速放行 vs 合规核对不可省',
      failureReasons: ['×以客户催促为由简化流程'],
      difficulties: ['客户施压', '时限紧张'],
      constraints: { forbidBehaviors: ['禁止承诺免除核对手续'], maxTime: 20 },
      relatedKnowledgeIds: [],
      relatedScriptIds: [],
      relatedProductProfileArtifactIds: [],
      caseArtifactIds: [],
      cases: [caseOf('时限压迫下的合规坚守：特殊包装要求的红线沟通', 3)],
      dimensionWeights: [w('dim-01', 50), w('dim-02', 50)],
    },
    {
      id: 'sc-03',
      name: '异议处理：情绪安抚',
      audiences: ['订单运营专员'],
      businessGoal: '化解客户不满并重建信任',
      behaviorGoals: ['先共情再解释', '给出可执行的补救方案'],
      relatedIcebergElements: { knowledge: ['投诉处理规范'], skills: ['情绪安抚'] },
      businessImpact: '影响客户留存',
      failureCost: '投诉升级至上级',
      coreConflict: '客户情绪激烈 vs 责任边界清晰',
      failureReasons: ['×急于辩解引发二次冲突'],
      difficulties: ['客户情绪激烈', '扬言投诉'],
      constraints: { forbidBehaviors: ['禁止与客户争辩'], maxTime: 18 },
      relatedKnowledgeIds: [],
      relatedScriptIds: [],
      relatedProductProfileArtifactIds: [],
      caseArtifactIds: [],
      cases: [caseOf('情绪对抗下的信任重建：投诉升级前的关键三十秒', 2)],
      dimensionWeights: [w('dim-02', 40), w('dim-03', 60)],
    },
    {
      id: 'sc-04',
      name: '方案沟通：价值呈现',
      audiences: ['订单运营专员'],
      businessGoal: '推动客户认可处理方案',
      behaviorGoals: ['呈现方案对客户的价值', '确认下一步行动'],
      relatedIcebergElements: { knowledge: ['订单处理规范'], skills: ['方案表达'] },
      businessImpact: '影响方案采纳率',
      failureCost: '方案被搁置',
      coreConflict: '客户质疑成本 vs 方案必要性',
      failureReasons: ['×只讲流程不讲价值'],
      difficulties: ['客户质疑方案成本'],
      constraints: { forbidBehaviors: ['禁止夸大承诺'], maxTime: 25 },
      relatedKnowledgeIds: [],
      relatedScriptIds: [],
      relatedProductProfileArtifactIds: [],
      caseArtifactIds: [],
      cases: [caseOf('成本质疑下的价值呈现：把合规要求翻译成客户收益', 2)],
      dimensionWeights: [w('dim-01', 30), w('dim-03', 70)],
    },
  ];
}

function buildEvaluationCriteria() {
  const tier = (name) =>
    `优秀(90-100分)：${name}表现完整且主动；良好(70-89分)：${name}基本到位；需改进(<70分)：${name}缺失或失当`;
  return {
    passScore: 70,
    dimensions: [
      { id: 'dim-01', name: '流程规范', description: '是否按规范完成关键核对与留痕', criteria: tier('流程执行') },
      { id: 'dim-02', name: '沟通技巧', description: '是否共情、表达清晰并推动共识', criteria: tier('沟通') },
      { id: 'dim-03', name: '情绪控制', description: '压力情境下是否保持稳定与专业', criteria: tier('情绪管理') },
    ],
    defaultDimensionWeights: [
      { dimensionId: 'dim-01', weight: 40 },
      { dimensionId: 'dim-02', weight: 30 },
      { dimensionId: 'dim-03', weight: 30 },
    ],
  };
}

/** 在 WS 上收集帧（返回 controller 供脚本发帧） */
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
    /** 记录当前帧数：用于"只等待本次操作新产生的事件"，避免匹配到上一轮的残留帧 */
    mark: () => frames.length,
    /** 等待指定事件出现（只统计 mark 之后的帧） */
    waitFor(eventName, after = 0, timeoutMs = TIMEOUT_MS) {
      const deadline = Date.now() + timeoutMs;
      return new Promise((resolve, reject) => {
        const tick = () => {
          const found = frames.slice(after).find((f) => f.event === eventName);
          if (found) return resolve(found);
          if (Date.now() > deadline) return reject(new Error(`等待事件 ${eventName} 超时`));
          setTimeout(tick, 100);
        };
        tick();
      });
    },
    send: (obj) => ws.send(JSON.stringify(obj)),
    close: () => ws.close(),
    ready: new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve);
      ws.addEventListener('error', () => reject(new Error('WS 连接失败')));
    }),
  };
}

/** 从 mark 之后的 data part 里解析全部 bizType 载荷（按 part-start/part-content/part-end 顺序拼接） */
function dataPartPayloads(frames, after) {
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
        result.push({ parseError: String(e.message), raw: current.text.slice(0, 200) });
      }
      current = null;
    }
  }
  return result;
}

/** 在 frames[after..] 中查找指定 bizType 的 data part */
function findBizEvent(frames, after, bizType) {
  return dataPartPayloads(frames, after).find((p) => p.bizType === bizType) || null;
}

(async () => {
  console.log('=== AI 设计助手 工具/确认/发布 全链路冒烟 ===');
  const sessionId = (await request('POST', '/api/act/assistant/sessions', {})).sessionId;
  console.log(`sessionId=${sessionId}`);
  const ws = openWs(sessionId);
  await ws.ready;
  console.log('WS 已连接');

  // ---------- 1. create_topic：建草稿 + 推 topic-changed ----------
  console.log('\n[1/7] POST /api/act/internal/tools/create_topic');
  const topicMark = ws.mark();
  const topicTrace = uuid();
  const topicData = await request(
    'POST',
    `/api/act/internal/tools/create_topic?traceId=${topicTrace}&sessionId=${sessionId}`,
    { topic: '订单运营职能实战演练', description: '提升订单处理规范与异常应对能力，面向订单运营专员' },
  );
  check(typeof topicData.themeId === 'string' && topicData.themeId.length > 0, 'data.themeId 非空');
  check(topicData.created === true, 'data.created=true（新建草稿）');
  const themeId = topicData.themeId;
  await ws.waitFor('session-finish', topicMark, 10000);
  const topicChanged = findBizEvent(ws.frames, topicMark, 'biz-side-manage-topic-changed');
  check(!!topicChanged, '收到 biz-side-manage-topic-changed 事件');
  if (topicChanged) {
    check(topicChanged.params.explicit === true, 'topic-changed.params.explicit=true');
    check(topicChanged.params.topic === '订单运营职能实战演练', 'topic-changed.params.topic 正确');
  }

  // 草稿主题在列表页可见（FP-39）
  const list = await request('GET', `/api/act/admin/theme/list?keyword=${encodeURIComponent('订单运营职能实战演练')}`);
  check(list.total >= 1, `草稿主题已出现在列表（total=${list.total}）`);
  const detail = await request('GET', `/api/act/admin/theme/${themeId}`);
  check(detail.status === 0, `主题状态为草稿（status=${detail.status}）`);

  // ---------- 2. update_artifact：推入完整 artifact ----------
  console.log('\n[2/7] POST /api/act/internal/tools/update_artifact（full_replace 推入产物）');
  const artifact = await request('GET', `/api/act/assistant/sessions/${sessionId}/artifact`);
  check(!!artifact, 'session 已返回工作区 artifact');
  const updateMark = ws.mark();
  const updateTrace = uuid();
  const patches = [
    { op: 'full_replace', path: '/scenarios', value: buildScenarios(), reason: 'set scenarios' },
    { op: 'full_replace', path: '/materials', value: buildMaterials(), reason: 'set materials' },
    { op: 'full_replace', path: '/evaluationCriteria', value: buildEvaluationCriteria(), reason: 'set criteria' },
    {
      op: 'full_replace',
      path: '/customerProfiles',
      value: [
        {
          id: 'cp-01',
          type: '时限压迫型客户',
          description: '月度考核在即、对延误零容忍的对接负责人',
          personalities: ['急躁', '强势', '重结果'],
          decisionFactors: ['时效', '准确率'],
          communicationStyle: '语速快，习惯用期限与上级施压',
          painPoints: ['重复沟通', '返工'],
        },
        {
          id: 'cp-02',
          type: '成本敏感型采购',
          description: '关注赔付与成本口径的采购负责人',
          personalities: ['谨慎', '讲依据'],
          decisionFactors: ['成本', '合规'],
          communicationStyle: '引用条款，逐项确认',
          painPoints: ['隐性成本'],
        },
      ],
      reason: 'set profiles',
    },
  ];
  const updateData = await request(
    'POST',
    `/api/act/internal/tools/update_artifact?traceId=${updateTrace}&sessionId=${sessionId}`,
    { patches },
  );
  check(typeof updateData.versionNo === 'string', `data.versionNo 为字符串（${updateData.versionNo}）`);
  await ws.waitFor('session-finish', updateMark, 10000);
  const updatedEvent = findBizEvent(ws.frames, updateMark, 'biz-side-manage-artifact-updated');
  check(!!updatedEvent, '收到 biz-side-manage-artifact-updated 事件');
  if (updatedEvent) {
    check(Array.isArray(updatedEvent.params.content), 'artifact-updated.params.content 为补丁数组');
    check(typeof updatedEvent.params.id === 'string', 'artifact-updated.params.id 存在');
    check('version' in updatedEvent.params, 'artifact-updated.params.version 存在');
  }

  // ---------- 2b. WS action：材料「学员可见」开关（03 §3.2 前端写入通道） ----------
  console.log('\n[2b/7] WS 发 biz-side-manage-update-material action（关闭材料1 学员可见）');
  const materialMark = ws.mark();
  const artifactForMaterial = await request('GET', `/api/act/assistant/sessions/${sessionId}/artifact`);
  ws.send({
    type: 'chat',
    payload: {
      message: {
        id: uuid(),
        role: 'user',
        content: [
          {
            type: 'data',
            data: {
              type: 'action',
              actionName: 'biz-side-manage-update-material',
              // 按 03 文档 §3.2 的入参：id/version 为前端上下文，materialId/visibility 为变更内容
              params: {
                id: artifactForMaterial.artifactId,
                version: Number(artifactForMaterial.versionNo),
                materialId: 'file-1',
                visibility: 0,
              },
            },
          },
        ],
        sender: { id: 'admin', name: '管理员', avatar: '' },
      },
    },
    traceId: uuid(),
    spanId: uuid().slice(0, 16),
  });
  await ws.waitFor('session-finish', materialMark, 15000);
  const visibilityEvent = findBizEvent(ws.frames, materialMark, 'biz-side-manage-artifact-updated');
  check(!!visibilityEvent, '收到 biz-side-manage-artifact-updated 事件（材料开关）');
  if (visibilityEvent) {
    check(typeof visibilityEvent.params.id === 'string', '开关事件 params.id 存在');
    check(Array.isArray(visibilityEvent.params.content), '开关事件 params.content 为补丁数组');
    const patch = visibilityEvent.params.content[0];
    check(patch.op === 'full_replace' && patch.path === '/materials',
      `补丁为 full_replace /materials（实际 ${patch.op} ${patch.path}）`);
    const toggled = patch.value.find((m) => m.id === 'file-1');
    check(toggled && toggled.visibility === 0, '补丁 value 中该材料 visibility=0（学员不可见）');
    const untouched = patch.value.find((m) => m.id === 'file-2');
    check(untouched && untouched.visibility === 1, '未操作的材料 visibility 保持 1');
    check(visibilityEvent.params.version === String(artifactForMaterial.versionNo),
      '版本号不变（工作区原地更新）');
  }
  const artifactAfterToggle = await request('GET', `/api/act/assistant/sessions/${sessionId}/artifact`);
  const storedMaterial = (artifactAfterToggle.content.materials || []).find((m) => m.id === 'file-1');
  check(storedMaterial && storedMaterial.visibility === 0, 'artifact 落库后该材料 visibility=0（已持久化）');

  // 兼容别名 + 布尔入参 + fileServId 定位
  const aliasMark = ws.mark();
  ws.send({
    type: 'chat',
    payload: {
      message: {
        id: uuid(),
        role: 'user',
        content: [
          {
            type: 'data',
            data: {
              type: 'action',
              actionName: 'biz-side-manage-material-visibility',
              params: { materialId: 'srv-2', visibility: true },
            },
          },
        ],
        sender: { id: 'admin', name: '管理员', avatar: '' },
      },
    },
    traceId: uuid(),
    spanId: uuid().slice(0, 16),
  });
  await ws.waitFor('session-finish', aliasMark, 15000);
  check(!!findBizEvent(ws.frames, aliasMark, 'biz-side-manage-artifact-updated'),
    '别名 action 同样生效（biz-side-manage-material-visibility）');
  const artifactAfterAlias = await request('GET', `/api/act/assistant/sessions/${sessionId}/artifact`);
  const srvMaterial = (artifactAfterAlias.content.materials || []).find((m) => m.id === 'file-2');
  check(srvMaterial && srvMaterial.visibility === 1, '布尔 true 转 1 且按 fileServId 命中（visibility=1）');

  // ---------- 3. confirm action：确认落库 ----------
  console.log('\n[3/7] WS 发 biz-side-manage-confirm action（确认落库）');
  const confirmMark = ws.mark();
  const artifactBeforeConfirm = await request('GET', `/api/act/assistant/sessions/${sessionId}/artifact`);
  ws.send({
    type: 'chat',
    payload: {
      message: {
        id: uuid(),
        role: 'user',
        content: [
          {
            type: 'data',
            data: {
              type: 'action',
              actionName: 'biz-side-manage-confirm',
              params: { id: artifactBeforeConfirm.artifactId, version: Number(artifactBeforeConfirm.versionNo) },
            },
          },
        ],
        sender: { id: 'admin', name: '管理员', avatar: '' },
      },
    },
    traceId: uuid(),
    spanId: uuid().slice(0, 16),
  });
  await ws.waitFor('session-finish', confirmMark, 20000);
  const saveSuccess = findBizEvent(ws.frames, confirmMark, 'biz-side-manage-save-success');
  check(!!saveSuccess, '收到 biz-side-manage-save-success 事件');
  if (saveSuccess) {
    check(saveSuccess.params.ex_id === themeId, 'save-success.params.ex_id 为主题 ID');
    check(typeof saveSuccess.params.version === 'string', 'save-success.params.version 为字符串');
  }

  // ---------- 4. 校验业务表落库（FP-40） ----------
  console.log('\n[4/7] 校验确认落库结果（场景单元 / 维度展开 / 案例 / 画像计数）');
  const units = await request('GET', `/api/act/admin/theme/${themeId}/scenario-units`);
  check(units.length === 4, `场景单元落库 4 条（实际 ${units.length}）`);
  if (units.length === 0) {
    console.log('  ❌ 确认未落库，后续步骤无法继续，提前退出');
    ws.close();
    process.exit(1);
  }
  const firstUnit = units[0];
  check(firstUnit.dimensions.length === 2, `场景 1 维度展开为独立行 2 条（实际 ${firstUnit.dimensions.length}）`);
  check(Number(firstUnit.dimensions[0].weight) === 60, `场景 1 首维度权重取自 dimensionWeights（${firstUnit.dimensions[0].weight}）`);
  check(firstUnit.dimensions[0].excellentStandard.includes('流程执行'), '维度三档标准已解析落库');
  const settings = await request('GET', `/api/act/admin/theme/${themeId}/settings`);
  check(settings.contentOverview.scenarioUnitCount === 4, `内容设计概览场景数=4（${settings.contentOverview.scenarioUnitCount}）`);
  check(settings.contentOverview.customerProfileCount === 2,
    `客户画像真实值=2（${settings.contentOverview.customerProfileCount}）`);
  check(settings.contentOverview.evaluationDimensionCount === 3,
    `维度名去重计数=3（${settings.contentOverview.evaluationDimensionCount}）`);

  // 重复确认（并发幂等语义的串行验证）
  const artifactAfterConfirm = await request('GET', `/api/act/assistant/sessions/${sessionId}/artifact`);
  check(artifactAfterConfirm === null || artifactAfterConfirm.status === 'confirmed',
    '确认后工作区已让位（artifact 为 null 或 confirmed）');

  // ---------- 5. publish ----------
  console.log('\n[5/7] POST /api/act/admin/theme/{id}/publish');
  const publish = await request('POST', `/api/act/admin/theme/${themeId}/publish`);
  check(publish.totalCases === 4, `data.totalCases=4（实际 ${publish.totalCases}）`);
  check(publish.status === 1 || publish.status === 3, `data.status 为 1 或 3（实际 ${publish.status}）`);

  // ---------- 6. script-progress 轮询至完成 ----------
  console.log('\n[6/7] GET /api/act/admin/theme/{id}/script-progress 轮询');
  const deadline = Date.now() + PROGRESS_TIMEOUT_MS;
  let progress = null;
  while (Date.now() < deadline) {
    progress = await request('GET', `/api/act/admin/theme/${themeId}/script-progress`);
    console.log(`  progress: total=${progress.total} completed=${progress.completed} failed=${progress.failed} progress=${progress.progress} status=${progress.status}`);
    if (progress.status === 3) break;
    await new Promise((r) => setTimeout(r, 1000));
  }
  check(progress.total === 4, `progress.total=4（实际 ${progress.total}）`);
  check(progress.completed === 4, `progress.completed=4（实际 ${progress.completed}）`);
  check(progress.failed === 0, `progress.failed=0（实际 ${progress.failed}）`);
  check(progress.progress === 100, `progress.progress=100（实际 ${progress.progress}）`);
  check(progress.status === 3, `progress.status=3 已发布（实际 ${progress.status}）`);

  // ---------- 7. 版本 +1 与已发布态 ----------
  console.log('\n[7/7] 校验发布后版本与状态');
  const detailAfter = await request('GET', `/api/act/admin/theme/${themeId}`);
  check(detailAfter.status === 3, `主题状态=已发布（实际 ${detailAfter.status}）`);
  check(detailAfter.versionCount === 2, `版本历史 +1（versionCount=${detailAfter.versionCount}）`);
  check(detailAfter.currentVersionNo === '2', `当前版本指向新版本（currentVersionNo=${detailAfter.currentVersionNo}）`);
  check(detailAfter.scenarioUnitCount === 4, `新版本场景单元已复制（${detailAfter.scenarioUnitCount}）`);
  const unitsAfter = await request('GET', `/api/act/admin/theme/${themeId}/scenario-units`);
  check(unitsAfter.length === 4 && unitsAfter[0].dimensions.length === 2,
    '新版本场景单元与维度完整（4 单元 / 首单元 2 维度）');
  check(unitsAfter[0].unitName === '开场接触：需求摸底', 'AI 内容已随版本复制保留');

  ws.close();

  console.log('\n=== 结果 ===');
  if (failures.length === 0) {
    console.log('  ✅ 全链路通过：create_topic → update_artifact → confirm → 业务表落库 → publish → 已发布 + 版本 +1');
    process.exit(0);
  }
  console.log(`  ❌ ${failures.length} 项校验失败：`);
  failures.forEach((f) => console.log(`     - ${f}`));
  process.exit(1);
})().catch((e) => {
  console.error(`\n脚本异常：${e.message}`);
  process.exit(1);
});