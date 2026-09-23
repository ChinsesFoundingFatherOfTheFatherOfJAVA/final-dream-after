#!/usr/bin/env node
/**
 * AI 设计助手 · WS 集成冒烟脚本（验收项 2）
 *
 * 走通链路：建会话（HTTP）→ 连 WS → 发 common-session-start → 收到完整事件序列
 *           → 校验 03 接口文档 §3 的帧字段与 seq/part 语义 → 拉历史消息与 artifact 校验字段名。
 *
 * 用法（默认直连后端 8082；经网关时用 BASE=http://localhost:8080 WS_BASE=ws://localhost:8080）：
 *   node scripts/assistant-ws-smoke.mjs
 *   BASE=http://localhost:8082 THEME_ID=1948xxxx node scripts/assistant-ws-smoke.mjs
 *   TIMEOUT_MS=60000 node scripts/assistant-ws-smoke.mjs     # Dify 不可达时留给失败兜底充足时间
 *
 * 说明：Dify 未配置/不可达时，助手回合会走"异常兜底"分支（文本提示 + session-finish），
 * 事件序列依然完整，因此本脚本可在没有 Dify 的环境下验证协议链路。
 */

const BASE = process.env.BASE || 'http://localhost:8082';
const WS_BASE = process.env.WS_BASE || BASE.replace(/^http/, 'ws');
const THEME_ID = process.env.THEME_ID || '';
const TIMEOUT_MS = Number(process.env.TIMEOUT_MS || 45000);

/** 期望的事件序列（03 文档 §3.3） */
const EXPECTED_ORDER = [
  'user-message-ack',
  'session-start',
  'message-extra-info',
  'message-start',
];
const EXPECTED_SUFFIX = ['session-finish'];

const uuid = () => crypto.randomUUID().replace(/-/g, '');

const failures = [];
const notes = [];

function check(condition, message) {
  if (condition) {
    console.log(`  [OK] ${message}`);
  } else {
    failures.push(message);
    console.log(`  [FAIL] ${message}`);
  }
}

async function postJson(path, body) {
  const resp = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body ?? {}),
  });
  const text = await resp.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch (e) {
    throw new Error(`POST ${path} 响应非 JSON：${text.slice(0, 200)}`);
  }
  return { status: resp.status, json };
}

async function getJson(path) {
  const resp = await fetch(BASE + path);
  const text = await resp.text();
  return { status: resp.status, json: JSON.parse(text) };
}

/** 建会话：校验 03 文档 2.1 响应字段名 */
async function createSession() {
  console.log(`\n[1/5] POST /api/act/assistant/sessions  (BASE=${BASE})`);
  const { status, json } = await postJson('/api/act/assistant/sessions', THEME_ID ? { themeId: THEME_ID } : {});
  check(status === 200, `HTTP 200（实际 ${status}）`);
  check(json.code === 0, `统一响应 code=0（实际 ${json.code} / ${json.message}）`);
  const data = json.data || {};
  check(typeof data.sessionId === 'string' && data.sessionId.length > 0, 'data.sessionId 为非空字符串');
  check('themeId' in data, 'data.themeId 字段存在');
  check(typeof data.isNew === 'boolean', 'data.isNew 为布尔');
  check('artifact' in data, 'data.artifact 字段存在（无产物为 null）');
  if (data.artifact) {
    check(typeof data.artifact.artifactId === 'string', 'artifact.artifactId 为字符串');
    check(typeof data.artifact.versionNo === 'string', 'artifact.versionNo 为字符串（前端 ID 一律 string）');
    check(['working', 'confirmed'].includes(data.artifact.status), 'artifact.status ∈ {working, confirmed}');
  }
  console.log(`  sessionId=${data.sessionId} isNew=${data.isNew}`);
  return data.sessionId;
}

/** 连接 WS 并跑一轮，收集帧 */
function connectAndSend(sessionId) {
  return new Promise((resolve) => {
    const url = `${WS_BASE}/api/act/ws/chat?sessionId=${encodeURIComponent(sessionId)}`;
    console.log(`\n[2/5] 连接 ${url}`);
    const frames = [];
    let ws;
    let settled = false;
    let pingCount = 0;

    const finish = (reason) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try {
        ws && ws.close();
      } catch (e) {
        /* ignore */
      }
      resolve({ frames, reason, pingCount });
    };

    const timer = setTimeout(() => finish(`超时 ${TIMEOUT_MS}ms 未收到 session-finish`), TIMEOUT_MS);

    try {
      ws = new WebSocket(url);
    } catch (e) {
      failures.push(`WS 连接失败：${e.message}`);
      return finish('connect-error');
    }

    ws.addEventListener('open', () => {
      console.log('  WS 已连接，发送 common-session-start 帧');
      const frame = {
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
                  actionName: 'common-session-start',
                  params: { sessionId, projectId: THEME_ID || '' },
                },
              },
            ],
            sender: { id: 'admin', name: '管理员', avatar: '' },
          },
        },
        traceId: uuid(),
        spanId: uuid().slice(0, 16),
      };
      ws.send(JSON.stringify(frame));
    });

    ws.addEventListener('message', (event) => {
      let frame;
      try {
        frame = JSON.parse(event.data);
      } catch (e) {
        failures.push(`收到非 JSON 帧：${String(event.data).slice(0, 120)}`);
        return;
      }
      if (frame.type === 'ping') {
        pingCount++;
        check(typeof frame.ts === 'number', '心跳帧 ping 的 ts 为数值');
        ws.send(JSON.stringify({ type: 'pong' }));
        return;
      }
      frames.push(frame);
      const d = frame.data || {};
      const partInfo = d.index === undefined ? '' : ` index=${d.index}`;
      const contentType = d.contentType ? ` contentType=${d.contentType}` : '';
      const delta = d.delta ? ` delta.type=${d.delta.type}` : '';
      console.log(`  <- ${frame.event} seq=${d.seq}${partInfo}${contentType}${delta}`);
      if (frame.event === 'session-finish') {
        finish('session-finish');
      }
    });

    ws.addEventListener('error', (e) => {
      failures.push(`WS 错误：${e.message || e.type}`);
    });

    ws.addEventListener('close', (e) => {
      notes.push(`WS 关闭 code=${e.code} reason=${e.reason || ''}`);
      finish(`closed-${e.code}`);
    });
  });
}

/** 校验事件序列与 seq/part 语义 */
function verifyFrames(frames, reason) {
  console.log('\n[3/5] 校验事件序列与帧字段');
  const events = frames.map((f) => f.event);
  check(events.length > 0, '至少收到一帧');
  check(events[0] === 'user-message-ack', `首帧为 user-message-ack（实际 ${events[0]}）`);
  for (let i = 0; i < EXPECTED_ORDER.length; i++) {
    check(events[i] === EXPECTED_ORDER[i], `第 ${i + 1} 帧为 ${EXPECTED_ORDER[i]}（实际 ${events[i]}）`);
  }
  check(events[events.length - 1] === EXPECTED_SUFFIX[0], `末帧为 session-finish（实际 ${events[events.length - 1]}）`);
  check(reason === 'session-finish', `轮次正常收尾（结束原因 ${reason}）`);

  // seq 单轮内从 1 连续递增
  const seqs = frames.map((f) => f.data && f.data.seq);
  const seqOk = seqs.every((s, i) => s === i + 1);
  check(seqOk, `seq 单轮内从 1 连续递增（实际 ${seqs.slice(0, 8).join(',')}${seqs.length > 8 ? ',...' : ''}）`);

  // 每帧都要有 type/timestamp
  check(
    frames.every((f) => f.type === 'event' && typeof f.data.timestamp === 'string'),
    '每帧 type=event 且 data.timestamp 存在',
  );

  // ack 字段
  const ack = frames[0].data;
  check(typeof ack.userMessageDbId === 'string' && ack.userMessageDbId.length > 0, 'ack.userMessageDbId 非空');
  check(typeof ack.userMessageId === 'string', 'ack.userMessageId 存在');

  // session-start 字段
  const sessionStart = frames.find((f) => f.event === 'session-start').data;
  check(typeof sessionStart.sessionId === 'string', 'session-start.sessionId 存在');
  check(typeof sessionStart.traceId === 'string', 'session-start.traceId 存在');

  // message-start：助手消息 ID 为 xxx_0，sender.id 存在
  const messageStart = frames.find((f) => f.event === 'message-start').data;
  check(/_0$/.test(String(messageStart.messageId)), `message-start.messageId 为 xxx_0 格式（${messageStart.messageId}）`);
  check(messageStart.sender && typeof messageStart.sender.id === 'string', 'message-start.sender.id 存在');
  check(typeof messageStart.agentMessageDbId === 'string', 'message-start.agentMessageDbId 存在');

  // part 序列：index 递增、三段式配对、delta 类型正确
  const partStarts = frames.filter((f) => f.event === 'part-start');
  const partEnds = frames.filter((f) => f.event === 'part-end');
  const partContents = frames.filter((f) => f.event === 'part-content');
  check(partStarts.length > 0, '至少有一个 part（text 或 data）');
  check(partStarts.length === partEnds.length, `part-start 与 part-end 数量一致（${partStarts.length}/${partEnds.length}）`);
  const indexes = partStarts.map((f) => f.data.index);
  check(indexes.every((v, i) => v === i), `part index 从 0 递增（实际 ${indexes.join(',')}）`);
  check(
    partStarts.every((f) => ['text', 'data'].includes(f.data.contentType) && f.data.partExtendedData),
    'part-start.contentType ∈ {text,data} 且携带 partExtendedData',
  );
  check(
    partContents.every((f) =>
      f.data.delta && ['text_delta', 'data_delta'].includes(f.data.delta.type)),
    'part-content.delta.type ∈ {text_delta,data_delta}',
  );
  const dataParts = partContents.filter((f) => f.data.delta.type === 'data_delta');
  for (const part of dataParts) {
    const full = frames
      .filter((f) => f.event === 'part-content' && f.data.index === part.data.index)
      .map((f) => f.data.delta.data)
      .join('');
    try {
      const parsed = JSON.parse(full);
      check(typeof parsed.bizType === 'string', `data part（index=${part.data.index}）拼接后含 bizType：${parsed.bizType}`);
    } catch (e) {
      failures.push(`data part（index=${part.data.index}）拼接结果不是合法 JSON：${full.slice(0, 120)}`);
    }
  }
}

/** 历史消息：校验 03 文档 2.2 字段名与 cursor 分页语义 */
async function verifyMessages(sessionId) {
  console.log('\n[4/5] GET /api/act/assistant/sessions/{id}/messages 校验字段名');
  const { status, json } = await getJson(`/api/act/assistant/sessions/${sessionId}/messages?limit=1000`);
  check(status === 200 && json.code === 0, `HTTP 200 且 code=0（实际 ${status}/${json.code}）`);
  const data = json.data || {};
  check(Array.isArray(data.messages), 'data.messages 为数组');
  check(data.pagination && typeof data.pagination.limit === 'number', 'pagination.limit 为数值');
  check(typeof data.pagination.hasNextPage === 'boolean', 'pagination.hasNextPage 为布尔');
  check(typeof data.pagination.nextCursor === 'string', 'pagination.nextCursor 为字符串');
  check(data.messages.length >= 2, `历史消息已落库（${data.messages.length} 条：用户 + 助手）`);

  const roles = data.messages.map((m) => m.role);
  check(roles.includes('user'), '存在 role=user 的消息');
  check(roles.includes('assistant'), '存在 role=assistant 的消息');

  const userMessage = data.messages.find((m) => m.role === 'user');
  for (const field of ['dbId', 'id', 'sessionId', 'role', 'content', 'sender', 'createdAt']) {
    check(field in userMessage, `消息字段 ${field} 存在`);
  }
  check(Array.isArray(userMessage.content), 'content 为块数组（非字符串）');
  check(userMessage.sender && typeof userMessage.sender.id === 'string', 'sender.id 存在');

  // 按时间正序
  const times = data.messages.map((m) => m.createdAt);
  check(
    times.every((t, i) => i === 0 || new Date(t.replace(' ', 'T')) >= new Date(times[i - 1].replace(' ', 'T'))),
    '消息按时间正序返回',
  );

  // cursor 分页：limit=1 时应返回较新的一条并给出 nextCursor
  const small = await getJson(`/api/act/assistant/sessions/${sessionId}/messages?limit=1`);
  check(small.json.data.messages.length === 1, 'limit=1 只返回 1 条');
  check(small.json.data.pagination.hasNextPage === true, 'limit=1 时 hasNextPage=true');
  check(small.json.data.pagination.nextCursor.length > 0, 'limit=1 时返回 nextCursor');
  const older = await getJson(
    `/api/act/assistant/sessions/${sessionId}/messages?limit=1&cursor=${encodeURIComponent(small.json.data.pagination.nextCursor)}`,
  );
  check(older.json.data.messages.length >= 1, '携带 cursor 可翻到更早一页');
  if (older.json.data.messages.length >= 1 && small.json.data.messages.length >= 1) {
    check(
      older.json.data.messages[0].dbId !== small.json.data.messages[0].dbId,
      '翻页返回的是更早的不同消息（游标生效）',
    );
  }
}

/** artifact 接口：校验 03 文档 2.2b */
async function verifyArtifact(sessionId) {
  console.log('\n[5/5] GET /api/act/assistant/sessions/{id}/artifact 校验字段名');
  const { status, json } = await getJson(`/api/act/assistant/sessions/${sessionId}/artifact`);
  check(status === 200 && json.code === 0, `HTTP 200 且 code=0（实际 ${status}/${json.code}）`);
  if (json.data) {
    check(typeof json.data.artifactId === 'string', 'artifact.artifactId 为字符串');
    check(typeof json.data.versionNo === 'string', 'artifact.versionNo 为字符串');
    check(typeof json.data.status === 'string', 'artifact.status 存在');
    notes.push(`artifact 存在：id=${json.data.artifactId} version=${json.data.versionNo} status=${json.data.status}`);
  } else {
    notes.push('artifact 为 null（新会话无主题，符合 2.2b 约定）');
  }
}

(async () => {
  console.log('=== AI 设计助手 WS 集成冒烟 ===');
  let sessionId;
  try {
    sessionId = await createSession();
  } catch (e) {
    console.error(`\n建会话失败：${e.message}`);
    console.error('请确认后端已在 ' + BASE + ' 启动（mvn spring-boot:run 或 java -jar）。');
    process.exit(1);
  }

  const { frames, reason, pingCount } = await connectAndSend(sessionId);
  verifyFrames(frames, reason);
  if (pingCount > 0) {
    console.log(`  [OK] 心跳：收到 ${pingCount} 次 ping 并已回 pong`);
  } else {
    notes.push('本轮未收到 ping（心跳间隔 20s，短回合属正常）');
  }

  try {
    await verifyMessages(sessionId);
    await verifyArtifact(sessionId);
  } catch (e) {
    failures.push(`HTTP 字段校验异常：${e.message}`);
  }

  console.log('\n=== 结果 ===');
  if (notes.length) {
    notes.forEach((n) => console.log(`  [note] ${n}`));
  }
  if (failures.length === 0) {
    console.log('  ✅ 全部校验通过：事件序列、seq/part 语义、HTTP 字段名与 cursor 分页均符合 03 接口文档');
    process.exit(0);
  }
  console.log(`  ❌ ${failures.length} 项校验失败：`);
  failures.forEach((f) => console.log(`     - ${f}`));
  process.exit(1);
})();