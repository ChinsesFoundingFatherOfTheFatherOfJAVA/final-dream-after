# AGENTS.md · SpeechTraining（话术训练，8081）

## 1. 应用定位

AI 对练-话术训练业务：管理端（项目创建/评分维度/虚拟陪练者/流程图/学员指派/统计排名）+
学员端（训练对话、语音对话、训练报告、排行榜）。核心机制是 FlowEngineService 流程引擎
（拓扑流转/权重路由/通关策略判定）与 ReportGenerationService 异步报告。

横向规范见 `D:\JACK\plan\后端架构总纲.md`；本模块纵向文档见 `D:\JACK\plan\话术训练模块\`、
`D:\JACK\plan\话术训练业务闭环模块\`、`D:\JACK\plan\学员端话术训练模块\`（其中 `09-问题清单.md` 是问题台账，改动前先查）。

## 2. 技术栈与包结构

- Java 21 / Spring Boot 4.0.8 / MyBatis-Plus 3.5.17（含 jsqlparser）/ Nacos discovery。
- 包结构：`auth/`（SpeechJwtFilter、SpeechAuthContext）、`common/`、`controller/`、`dify/`、`doubao/`（SAUC ASR）、
  `dto/req|resp`、`entity/`、`mapper/`、`service/` + `service/impl/`、`config/`。
- 外部依赖：Dify（script-gen / scoring / report-scoring / avatar-gen 四个 agent，配置在 `dify.agents.*`）、
  百炼 CosyVoice TTS（`speech.tts.*`，v2 系列模型只认 `*_v2` 音色）、豆包 SAUC v3 流式 ASR（`speech.asr.doubao.*`）。
- P3 native：`agent/NativeWorkflowClient` → AIGentService:8086；`DifyWorkflowSupportImpl`
  按 `agent.<name>.provider` 分流。`avatar-gen` 仍默认 dify。

## 3. 铁律（违反即返工）

1. **数据所有权**：本应用只拥有 `training_*`、`speech_*`、`project_student` 表。
   用户/部门/角色/权限（`sys_*`）**禁止**建 Mapper/JdbcTemplate 直读，一律注入 `OrgQueryService`
   （Dubbo，见 `config/DubboConsumerConfig`，`@DubboReference(version="1.0.0", check=false)`）。
   需要新数据能力时：先在 `StaffManagement-api` 加契约方法，再在消费侧使用——2026-10-02 已整改过一轮直读，不许回退。
2. **鉴权链**：登录态是 StaffManagement 签发的 JWT（HS256 共享密钥，`speech.auth.jwt-secret`）。
   `SpeechJwtFilter` 解出 Principal → `AccessControlService` 做管理端权限（`act:*` 权限码经 `getAuthSnapshot` 获取）
   与项目创建者校验。学员身份 `UserContext.getStudentId()` 返回的是**账号（工号）**，与 `project_student.user_id` 同键。
   `speech.auth.dev-fallback-enabled` 是开发后门（无 token 放行为 mock 学员），生产配置必须为 false，禁止扩大其行为。
3. **持久层**：MyBatis-Plus 单表 CRUD + 自定义 SQL 进 `src/main/resources/mapper/*.xml`
   （`mybatis-plus.mapper-locations` 已显式配置，新 XML 放对位置才会被加载）；不跨表 JOIN，跨表数据 Service 批量查 + Map 组装；外部输入一律 `#{}`。
4. **通关与评分口径**：项目类型（1=考核制）、通过策略（2=累计 N 次 / 3=连续 N 次）、完课状态（0/1/2）的判定统一走
   `PassStrategyService` 与既有私有方法口径，新增统计页不得另写一套判定。
5. **响应契约**：`ApiResponse{code,message,data}`、`PageResult{records,total}`、`BizException`、`GlobalExceptionHandler`、
   MyBatis-Plus 分页插件统一来自 **`after-end-common`** 模块（`jack.digityHuman:after-end-common`，经 AutoConfiguration 自动装配），
   **禁止在本工程重建这些类**；业务失败 HTTP 200 + 非零 code，分页 `{list,total,page,size}` 形状统一需前端配合，暂缓。

## 4. 本应用特有注意事项

- **接口/Impl 统一（2026-10-02 完成）**：基础设施型 Service 已全部拆为"接口 + Impl"（同包放置）：
  FlowEngineService、ReportGenerationService、SpeechService、TtsCacheService、ToneScoringService、
  AvatarGenService、PassStrategyService、StudentSessionAccessService、FileStorageService（common）、
  SpeechJwtService（auth）、Dify 四件套（dify）。新增基础设施 Service 必须同样拆接口；
  消费方一律注入接口类型，测试直接构造时用 `XxxServiceImpl`。
  `UserContext`（上下文持有者）与计算器类（ForbiddenWordChecker/MetricOutcome/SpeechSpeedCalculator）保持具体类，刻意不接口化。
- **Dify 对接**：agent 配置、超时（avatar-gen 5 分钟）与错误处理见 `dify/` 包与 `dify.agents.*` 配置；评分器返回 JSON 的容错解析是已知问题高发区（见问题台账 A 类）。
- **P3 provider**：`gen-scene` / `gen-outline` / `gen-script` / `scoring` / `report-scoring`
  已支持 native；`scoring` 映射 `speech-score`，`report-scoring` 映射 `report-score`。
  native 超时/非 0 业务码统一转 `BizException`，不静默回退 Dify。
- **语音链路**：TTS/ASR/语气评分（`speech.tone.*`）的分池线程与超时口径不要随意调整，改动前读
  `学员端话术训练模块\08-口型驱动与语音维度专项方案.md`。
- 运行时上传文件（封面/音频/头像）目录见 `app.upload.*`，不进 git。
- 训练会话/消息的序号唯一约束、每轮声学特征表等 DDL 变更以 `07-DDL变更清单.md` 为准，执行保持幂等。

## 5. 构建与验证

- 提交前 `mvnw.cmd test`（当前 161 个测试；`SpeechTrainingApplicationTests.contextLoads` 和
  `VoiceMapPropertiesBindingTest` 依赖 MySQL/Nacos 可达，网络不通导致的失败属环境问题，需在报告中注明）。
- 改了依赖管理需注意：`spring-cloud-dependencies` 必须排在 `spring-cloud-alibaba-dependencies` 之前 import；不 import dubbo-bom。
- 部署顺序：StaffManagement 先起（Dubbo 提供方），本应用 `check=false` 可后起。

## 6. 工作方式

1. 代码实施过程中出现技术或逻辑修改，要及时将技术方案文档同步修改。
2. 关键类、方法、代码行要加上标准的描述功能或步骤的 Java 注释（中文，说明"为什么"，不是复述代码）。
3. 出现信息缺失或认为实现方案不合理的情况一定要和用户沟通确认！！！
