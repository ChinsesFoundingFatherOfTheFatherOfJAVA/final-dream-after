# AGENTS.md · AIGentService（智能体运行时，8086）

## 1. 应用定位

本项目承载 Dify 工作流/Agent 的原生替代运行时。当前只实施 **P1 自由演练助手**；
P2/P3 仍是规划态，不得以迁移 P1 为由顺手扩散。

权威输入：

- `D:\JACK\plan\AIGentService智能体迁移\04-P1-native-runtime实施计划.md`
- `D:\JACK\plan\AIGentService智能体迁移\05-P1实施偏差与决策记录.md`
- `D:\JACK\plan\final_dream_difys\自由演练助手.yml`（DSL 唯一权威源，只读）

## 2. 数据与边界

1. **不直写业务表**：AIGentService 不拥有 `act_*`、`sys_*`，业务副作用只能通过
   `AssistantHttpToolClient` 调 AtualCombatTraining 内部工具端点完成。
2. **WS 留在 AtualCombatTraining**：前端协议、消息落库、bizType 事件都由
   AtualCombatTraining 负责；这里只提供 `POST /internal/agent/chat` SSE。
3. **模型适配层隔离**：业务/引擎只依赖 `LlmClient`，千问差异只能落在 `QwenClient`。
4. **API Key 不落库不入仓**：使用 `SPEECH_DASHSCOPE_API_KEY` 环境变量；日志禁止打印密钥。
5. **阶段守卫是安全边界**：不能只依赖 prompt。`create_topic`、`generate_theme`、
   `generate_cases` 必须经过 `ToolStageGuard`，其前置条件要有单测。

## 3. 当前最终决策

- 模型：`qwen3.8-flash`，千问 OpenAI-compatible `chat/completions`；
- 工具：HTTP 调用 AtualCombatTraining `/api/act/internal/tools/**`，暂不 Dubbo 化；
- 记忆：L1 进程内 `SessionMemory`，不实现 Redis L2/DB L3；
- SSE：当前输出完整 `agent_message` 后立即 `message_end`，还不是逐 token 流；
- provider 分流在 AtualCombatTraining：`agent.chat.provider=dify|native`，默认 dify；
- native 失败不在同一轮自动重试 Dify，避免工具副作用重复执行。

## 4. 修改规则

1. 新行为先写失败测试（TDD），再写最小实现；外部 HTTP 边界用本地 fake server 或 mock。
2. 改工具 schema 时同时核对 `CreateTopicReq`、`GenerateThemeReq`、`GenerateCasesReq`、
   `ArtifactPatchesReq`，特别是数组元素类型。
3. 改引擎阶段规则时同步 `ToolStageGuardTest` 与 `SessionMemoryTest`。
4. 改 provider/接口协议时同步 AIGentService 的 03/04/05 文档与 AtualCombatTraining 的
   `07-实现说明与偏差记录.md`。
5. 不修改 `D:\JACK\plan\final_dream_difys` 下的权威 DSL；如需从 DSL 提取 prompt，
   在 `src/main/resources/prompts/` 生成可版本化副本。

## 5. 验证

```powershell
cmd /c "mvnw.cmd test"
cmd /c "mvnw.cmd -DskipTests package"
```

真实 smoke 至少验证：

- 模糊需求不入工具；
- 明确确认且已有场景规划才允许生成类工具；
- 工具端点非 2xx / 非零业务码会回填可读错误；
- Qwen 429/超时返回可读 `error` SSE，不伪装成功。
