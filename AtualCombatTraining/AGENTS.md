# AGENTS.md · AtualCombatTraining（AI 实战训练场，8082）

## 1. 应用定位

AI 实战训练场业务：管理端训练主题管理（主题列表/详情五 Tab/版本与回滚）+
AI 设计助手（Dify/native 双 provider 编排 + 主题/案例生成工作流，WebSocket 自定义帧协议【线上同款】）。

横向规范见 `D:\JACK\plan\后端架构总纲.md`；本模块纵向文档见 `D:\JACK\plan\AI实战训练场管理端复刻\`、
`D:\JACK\plan\AI实战训练场AI助手模块\`——其中 `07-实现说明与偏差记录.md` 记录了 D1~D21 条实现偏差，改动前必读。

## 2. 技术栈与包结构

- Java 21 / Spring Boot 4.0.8 / MyBatis-Plus 3.5.17（含 jsqlparser）/ Nacos discovery / WebSocket。
- 包结构：`common/`、`config/`、`controller/`、`dify/`、`dto/req|resp`、`entity/`、`mapper/`、`service/impl/`、`websocket/`。
- 外部依赖：Dify（编排 Agent `side_free` + theme-gen / case-gen 工作流）、DashScope、Tika（材料解析）。
- native 编排：`agent/AgentRuntimeRouter` → `NativeAgentClient` → AIGentService:8086；
  只替换编排调用，WS 帧、消息落库、工具 bizType 事件仍在本应用。
- P2 workflow：`agent/WorkflowRuntimeRouter` → `NativeWorkflowClient`；主题
  `agent.theme-gen.provider`、案例 `agent.case-gen.provider` 默认 dify。
- native 调用 AIGentService `/internal/**` 时携带 `X-Internal-Token`，值来自
  `AGENT_INTERNAL_TOKEN`；`GET /api/act/internal/memory/{sessionId}` 只供 L3 记忆重建，
  同样强制 token。

## 3. 铁律（违反即返工）

1. **数据所有权**：本应用只拥有 `act_*` 表。用户/部门/角色（`sys_*`）**禁止**建 Mapper 直读——
   需要时照 SpeechTraining/OnlineClassroom 的模式接入 `OrgQueryService`（Dubbo 消费，
   依赖 `StaffManagement-api` + `dubbo-spring-boot-starter`/`dubbo-registry-nacos` 3.3.6，不 import dubbo-bom），
   并与用户确认后再动手。
2. **鉴权（现状与红线）**：本工程目前**没有鉴权拦截**（已知接受风险 R-3：仅靠前端拦截）。
   在网关统一鉴权（架构总纲 §11.1）落地前：新增接口不得依赖"有登录态"这一假设；
   不得在代码里自造第三套 JWT 解析；涉及管理端写操作的接口先和用户确认是否补 `AccessControl` 模式（照 SpeechTraining）。
3. **WS 协议兼容**：`/ws/chat` 帧协议（chat/session-start/part-content/session-finish/ping-pong、bizType 事件字典、
   artifact JSON Schema 8 个顶级字段）是对【线上同款】协议的还原，前端与抓包文档都依赖它——
   改字段/事件名是破坏性变更，必须同步《AI实战训练场AI助手模块\03-接口文档.md》并和用户确认。
   注意：gateway（WebMVC 版）不支持 WS upgrade（偏差 D2），WS 是绕过网关直连本服务的，不要把 WS 路径迁到网关路由里。
4. **持久层**：MyBatis-Plus 单表 CRUD + 自定义 SQL 进 `src/main/resources/mapper/*.xml`；不跨表 JOIN，跨表数据 Service 组装；外部输入一律 `#{}`。
5. **响应契约**：`ApiResponse{code,message,data}`、`PageResult{records,total}`、`BizException`、`GlobalExceptionHandler`、
   MyBatis-Plus 分页插件统一来自 **`after-end-common`** 模块（`jack.digityHuman:after-end-common`，经 AutoConfiguration 自动装配），
   **禁止在本工程重建这些类**；code=0 成功、业务失败 HTTP 200 + 非零 code，id 一律 string。
6. **编排 provider**：`agent.chat.provider=dify|native`，默认 dify。native 失败不得在同一轮自动重试
   Dify（工具可能已产生副作用）；切换 provider 当前需要重启本应用，后续接 Nacos 动态配置再放开。
7. **P2 workflow**：主题生成仍按 `node_finished` 逐段落 artifact 并推 `artifact-generated`；
   案例生成仍按单场景校验/合入；native 分支复用现有校验、重试和事件逻辑。
8. **内部工具鉴权兼容**：`/api/act/internal/tools/**` 为保留 Dify fallback 调用契约，当前
   不强制 `X-Internal-Token`；不得把该口径扩大到 memory 端点。若未来 Dify 工具也强制 token，
   必须同步调整 Dify 侧工具配置后再改。

## 4. 本应用特有注意事项

- **artifact 版本化**：主题产物带版本（`act_theme_version`），确认落库/发布初始化的流程见 02-技术实现方案的消息处理主链路；
  幂等摘要用 SHA-256（偏差 D15，不要改回 md5）。
- **线程池分池**：WS 推送、Dify 调用、报告生成各有线程池配置，不要共享一个池，调整前读 02-技术实现方案 §线程池分池策略。
- **运行时文件**：`assistant.file-dir` 指向的上传目录（历史原因曾落在仓库根 `act-files/`，36MB 已在清理）不进 git，
  新文件路径配置一律指向源码树外。
- JSON 序列化统一 Jackson（偏差 D1，不用 fastjson2）。
- Dify 工作流问题（空画像、工具描述）诊断与修改建议见 `08/09-*.md`，那是 DSL 侧的事，后端不要用代码去 workaround。

## 5. 构建与验证

- 提交前 `mvnw.cmd test`（当前 161 个测试应全绿）；`contextLoads` 依赖 MySQL/Nacos 可达，
  网络不通导致的失败属环境问题，需在报告中注明。
- 依赖管理铁律：`spring-cloud-dependencies` 排在 `spring-cloud-alibaba-dependencies` 之前 import；不 import dubbo-bom。
- 网关路由：本应用挂在 `/api/act/**`，新路由必须置于网关 `/api/**` 通配之前。

## 6. 工作方式

1. 代码实施过程中出现技术或逻辑修改，要及时将技术方案文档同步修改。
2. 关键类、方法、代码行要加上标准的描述功能或步骤的 Java 注释（中文，说明"为什么"，不是复述代码）。
3. 出现信息缺失或认为实现方案不合理的情况一定要和用户沟通确认！！！
