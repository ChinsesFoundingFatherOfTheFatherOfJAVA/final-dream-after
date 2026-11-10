# AGENTS.md · AIGentService（智能体运行时，8086）

## 1. 应用定位

本项目承载 Dify 工作流/Agent 的原生替代运行时。当前已实施 **P1 自由演练助手 + P2 主题/案例
生成 + P3 六个 workflow（含文生图）**；Dify 仅作为可切换 fallback。

权威输入：

- `D:\JACK\plan\AIGentService智能体迁移\04-P1-native-runtime实施计划.md`
- `D:\JACK\plan\AIGentService智能体迁移\05-P1实施偏差与决策记录.md`
- `D:\JACK\plan\AIGentService智能体迁移\06-P2-P3-native-workflow实施计划.md`
- `D:\JACK\plan\final_dream_difys\*.yml`（DSL 唯一权威源，只读）

## 2. 数据与边界

1. **不直写业务表**：AIGentService 不拥有 `act_*`、`sys_*`，业务副作用只能通过
   `AssistantHttpToolClient` 调 AtualCombatTraining 内部工具端点完成。
2. **WS 留在 AtualCombatTraining**：前端协议、消息落库、bizType 事件都由
   AtualCombatTraining 负责；这里只提供 `POST /internal/agent/chat` SSE。
3. **模型适配层隔离**：业务/引擎只依赖 `LlmClient`，千问差异只能落在 `QwenClient`。
4. **API Key 不落库不入仓**：使用 `SPEECH_DASHSCOPE_API_KEY` 环境变量；日志禁止打印密钥。
5. **阶段守卫是安全边界**：不能只依赖 prompt。`create_topic`、`generate_theme`、
   `generate_cases` 必须经过 `ToolStageGuard`，其前置条件要有单测。
6. **Workflow 产物只返回 outputs**：P2/P3 不直接写业务表；由调用方校验、落库和推事件。
7. **内部接口鉴权**：`/internal/**` 必须携带 `X-Internal-Token`，值与
   `AGENT_INTERNAL_TOKEN` 一致；未配置 token 时 fail-closed 返回 503，Actuator 保持只读公开。

## 3. 当前最终决策

- 模型：`qwen3.8-flash`，千问 OpenAI-compatible `chat/completions`；
- 工具：HTTP 调用 AtualCombatTraining `/api/act/internal/tools/**`，暂不 Dubbo 化；
- 记忆：L1 进程内 `SessionMemory` + Redis L2 `agent:session:{sessionId}`（7 天 TTL）
  + AtualCombatTraining `act_ai_message` L3 重建；Redis 故障自动降级，不阻断主链路；
- 监控：Actuator 暴露 `health/metrics/prometheus`；chat/workflow/LLM 提供计数与耗时直方图，
  LLM outcome 区分 `success/rate_limited/timeout/server_error/transport_error`；
- SSE：当前输出完整 `agent_message` 后立即 `message_end`，还不是逐 token 流；
- provider 分流在消费端：`agent.<name>.provider=dify|native`，2026-10-27 起默认 native。
- native 失败不在同一轮自动重试 Dify，避免工具副作用重复执行。
- P2：`/internal/agent/task/{name}` blocking + `/internal/agent/task/theme-gen/stream` SSE；
- P3 文本任务：`gen-scene` / `gen-outline` / `gen-script` / `speech-score` / `report-score` / `exam-gen`；
- 文生图：`avatar-gen` 通过 ModelScope 异步接口生成，模型 `Qwen/Qwen-Image-2.1-Turbo`；
  Dify 仍可通过 provider 切回。
- Workflow prompt/schema 最初从 DSL 提取到 `resources/prompts/workflows/`。2026-11-09 起这些 md 以手工修订稿为准：角色要写清所在产品和职责，输入在输出前面，输入输出字段要写含义。不得再用 DSL 提取覆盖。改字段名或占位符前，先核对 `WorkflowContext.render` 和调用方 inputs。DSL 本身仍不改；
- 对练一轮拆成两次模型调用：训练模式先 `coach.md` 再 `actor.md`，考核模式只调对方。对外仍是一次 `/internal/agent/drill/chat` SSE，事件顺序不变。不要把点评和台词重新合并进同一个提示词。`avatar-gen` 输出英文生图提示词，不套 JSON schema；`free-practice` 是 ReAct 对话，也不套；
- 长任务异步超时 `spring.mvc.async.request-timeout=610000`，千问读超时 `600000`。

## 4. 修改规则

1. 新行为先写失败测试（TDD），再写最小实现；外部 HTTP 边界用本地 fake server 或 mock。
2. 改工具 schema 时同时核对 `CreateTopicReq`、`GenerateThemeReq`、`GenerateCasesReq`、
   `ArtifactPatchesReq`，特别是数组元素类型。
3. 改引擎阶段规则时同步 `ToolStageGuardTest` 与 `SessionMemoryTest`。
4. 改 provider/接口协议时同步 AIGentService 的 03/04/05 文档与 AtualCombatTraining 的
   `07-实现说明与偏差记录.md`。
5. 不修改 `D:\JACK\plan\final_dream_difys` 下的权威 DSL；如需从 DSL 提取 prompt，
   在 `src/main/resources/prompts/` 生成可版本化副本。
6. workflow 字段必须对齐 DSL end 变量：`artifact_json`、`case_json`、`sceneResult`、
   `outlineResult`、`scriptResult`、评分五字段/报告字段。

## 5. 验证

```powershell
cmd /c "mvnw.cmd test"
cmd /c "mvnw.cmd -DskipTests package"
```

真实 smoke 至少验证：

- 无 token 调 `/internal/**` 返回 401，正确 token 可正常调用；
- `GET /actuator/health` 无需 token，`GET /actuator/prometheus` 能看到 chat/workflow/LLM 指标；
- Redis 写入 `agent:session:{sessionId}` 且 TTL 约 7 天；清 key 并重启 L1 后可由 Atual 端重建并回填；
- 模糊需求不入工具；
- 明确确认且已有场景规划才允许生成类工具；
- 工具端点非 2xx / 非零业务码会回填可读错误；
- Qwen 429/超时返回可读 `error` SSE，不伪装成功。
- P2/P3 真实 smoke：theme-gen 三节点、case-gen、三组话术和两个评分任务至少各跑一次。
