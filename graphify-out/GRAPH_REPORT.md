# Graph Report - after-end  (2026-10-21)

## Corpus Check
- 791 files · ~200,705 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 6062 nodes · 21824 edges · 204 communities (129 shown, 75 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 1367 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Exam Catalog CRUD & Shared DTOs
- Auth Controllers & Web Layer
- Exam DTOs & PageResult Contract
- Exam & Speech Entities
- Combat AI Mappers & Artifacts
- Assistant Identity & Biz Types
- Exam Notice Outbox & DTOs
- Spring Boot Test Scaffolding
- Excel/Word Export (POI)
- Exam Result Views & PDF
- Exam Paper & Question DTOs
- Excel/Word Export (POI)
- Staff User Admin & Sync
- Mockito Test Scaffolding
- Exam Catalog Tree Entities
- Combat Scenario & Version Responses
- Classroom Catalog Tree DTOs
- Document Extraction (POI/Tika)
- Course Operation Resources
- Combat WebSocket Registry
- Staff Auth & Crypto
- Speech User Context Tests
- Combat Training Dify Tests
- Speech AI Generation DTOs
- Speech Times Averages Report
- Speech Voice Message Results
- Notice Query & Recipients
- Staff Login & User VOs
- HTTP Client Test Helpers
- Classroom Study Attachments
- Combat Publish Init
- Speech Session Controllers
- Speech AI Gen & ASR Service
- Classroom Saved Attachments
- Speech Project Statistics
- File Upload Controllers
- Classroom Study Overview Stats
- Exam Paper Question Scores
- Cross-Service Docs & Rationale
- Thread Pool & Async Config
- Combat Material Summarizer
- Exam Question Transfer & Sharing
- Classroom Settings Rules
- Exam Transfer & List Queries
- Classroom Move & Tx Ops
- Common Web Exception Handling
- Scenario/Theme Service Impls
- Speech Flow Write & Scoring
- Document Extraction (POI/Tika)
- Staff Role/Dept Count DTOs
- Staff Role Service & BizException
- Speech Training Project Impl
- Combat Training Confirms & Artifacts
- Combat WS E2E Test Helpers
- Exam Urge & Sync Requests
- Combat WS Frame Encoding
- Exam Lib Permissions
- Classroom Tx Sync & Scheduling
- Classroom Move & Tx Ops
- Staff Role Member Service
- Speech TTS Cache Service
- Combat Artifact & Case Entities
- Staff Transaction Management
- Spring Boot Test Scaffolding
- Staff Transaction Management
- Combat AI Session DB Columns
- Exam Arrange Requests
- Exam Catalog CRUD & Shared DTOs
- Classroom Transcode & Subtitles
- Speech Template Save
- Combat Training Confirms & Artifacts
- Staff Persistence & Dubbo Services
- Speech Student Project Service
- Exam Arrange Save & Snapshot
- Exam Question Stats & Scoring
- Staff Bootstrap & PasswordEncoder
- CORS & Dubbo Consumer Config
- Staff PageVo & Query Helpers
- Exam Question Stats & Scoring
- Combat Training Confirms & Artifacts
- Dify Workflow Event Parser
- Speech Multipart Project Parsing
- Speech TTS Cache & Voice Map
- Combat Scenario & Version Responses
- Exam Paper Strategy
- Classroom Transcoding Tasks
- Speech Report Generation & Ranking
- Cross-Service Mapper & Entity Scaffolding
- Spring Boot Entrypoints
- Exam DTOs & PageResult Contract
- Exam Paper Snapshot
- Exam Paper Strategy Summary
- Exam DTOs & PageResult Contract
- Classroom SRT Service
- Speech Report Metric Outcomes
- Web MVC Config (Resources)
- Speech Multipart Project Parsing
- Staff Transaction Management
- WAV Header Parsing
- Combat Training Confirms & Artifacts
- Combat Training Confirms & Artifacts
- Exam Question Stats & Scoring
- Speech Doubao SAUC WebSocket
- Classroom Subtitles
- Staff Persistence & Dubbo Services
- Speech Student Report Service
- Speech Pass Strategy & Reports
- Speech Speed & Dimension Metrics
- Staff Auth & Crypto
- Common Id-As-String Serialization
- Classroom Upload Service
- Speech Speed & Dimension Metrics
- Document Extraction (POI/Tika)
- Staff Multi-Datasource Config
- Classroom Study Count DTOs
- Speech Student Session Controller
- Speech Template Save
- Exam Question Import Rows
- Classroom Role/Position Rows
- Speech Tone Scoring
- Cross-Service Mapper & Entity Scaffolding
- Exam Arrange Catalog Controllers
- Exam Point Service
- Exam Arrange Stat Tests
- Staff User Search Fields
- Shared DTOs (Staff/Classroom)
- Classroom OSS Temp Storage
- Exam Arrange Permissions
- Exam Arrange Service Tests
- Speech Tone Scoring Tests
- Speech Training DB Schema
- Combat WS Verification Helpers
- Dify Event Translator
- Combat WS Flow Test Helpers
- Speech Report Agent Output
- MP3 Duration Parsing
- Staff Persistence & Dubbo Services
- Exam Arrange Save & Snapshot
- Combat WS Flow Test Helpers II
- Exam AI Generation Requests
- Speech TTS Cache & Voice Map
- Speech Material Extraction
- Speech TTS Cache & Voice Map
- Flexible LocalDateTime Deserializer
- Exam Arrange Column Settings
- Exam Result Views & PDF
- Classroom Async Executor Config
- Common MyBatis-Plus Pagination
- Combat WS Frame Test Helpers
- Exam & Speech Entities
- Exam Arrange Column Settings Test
- Staff Persistence & Dubbo Services
- Doubao SAUC Frame Tests
- Speech Pass Strategy & Reports
- Exam Question DB Schema
- Auth Controllers & Web Layer
- Exam Question Save DTOs
- Forbidden Word Checker
- Combat Training DB Schema
- CJK Font Loading for PDF Export
- Dubbo Service Registry Names
- Flexible LocalDateTime Deserializer II
- Exam Score Config Service
- Media Job Service Tests
- File Upload Controllers
- Staff User Auth DB Schema
- WS Finish Probe Script
- WebSocket Config
- Classroom Courseware DB Schema
- Classroom Transcoding Tasks
- Speech Report Metric Outcomes
- Speech TTS Cache Service
- Speech Pass Strategy & Reports
- Staff Sync Cleanup DB Schema
- CORS & Dubbo Consumer Config
- Speech Training DB Schema II
- Artifact Support & Scoring Tiers
- Speech Multipart Project Parsing
- Classroom Kng DB Schema
- Notification DB Schema
- Speech Acoustic Dimension Schema
- Classroom Setting Migration
- Speech Trainer Video Asset Schema
- Speech Project Student Schema
- Speech Sparring AI Gen Schema
- Staff Sync Department Schema
- Order Ops Compliance PDF Fixture
- Gateway Dubbo Service Name

## God Nodes (most connected - your core abstractions)
1. `BizException` - 401 edges
2. `ApiResponse` - 353 edges
3. `PageResult` - 88 edges
4. `CoursewareServiceImpl` - 65 edges
5. `ProjectStatisticsServiceImpl` - 59 edges
6. `OrgQueryService` - 57 edges
7. `FlowEngineServiceImpl` - 57 edges
8. `JsonSupport` - 56 edges
9. `ActAiArtifact` - 49 edges
10. `CatalogServiceImpl` - 49 edges

## Surprising Connections (you probably didn't know these)
- `Dify Agents (script/scoring/report-scoring/avatar)` --semantically_similar_to--> `Dify Orchestration Agent (side_free / theme-gen / case-gen)`  [INFERRED] [semantically similar]
  SpeechTraining/AGENTS.md → AtualCombatTraining/AGENTS.md
- `OrgQueryService Dubbo 消费 (ExamService)` --semantically_similar_to--> `OrgQueryService Dubbo 消费 (OnlineClassroom)`  [INFERRED] [semantically similar]
  ExamService/AGENTS.md → OnlineClassroom/AGENTS.md
- `SpeechJwtFilter` --semantically_similar_to--> `JwtAuthFilter 边缘验签`  [INFERRED] [semantically similar]
  SpeechTraining/AGENTS.md → gateway/AGENTS.md
- `AccessControlService 管理端权限校验` --shares_data_with--> `OrgQueryService (sys 只读 + 鉴权快照)`  [INFERRED]
  SpeechTraining/AGENTS.md → StaffManagement/AGENTS.md
- `FrameListener` --implements--> `Listener`  [EXTRACTED]
  SpeechTraining/src/main/java/jack/digityhuman/speechtraining/doubao/DoubaoSaucClient.java → AtualCombatTraining/src/main/java/jack/digityhuman/atualcombattraining/dify/DifyWorkflowEventParser.java

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **after-end-common 全站共享响应契约** — after_end_common_agents_apiresponse, after_end_common_agents_bizexception, after_end_common_agents_globalexceptionhandler, after_end_common_agents_pagination_plugin, after_end_common_agents_after_end_common [EXTRACTED 1.00]
- **CJK Font Resolution Pipeline for PDF Export** — examservice_src_main_resources_fonts_readme_exam_result_service_impl_load_cjk_font, examservice_src_main_resources_fonts_readme_font_loading_order, examservice_src_main_resources_fonts_readme_pdfbox_subset_embedding, examservice_src_main_resources_fonts_readme_ascii_degradation_fallback [EXTRACTED 1.00]
- **OrgQueryService Dubbo 提供与消费** — staffmanagement_agents_orgqueryservice, speechtraining_agents_accesscontrolservice, onlineclassroom_agents_orgqueryservice_consumer, examservice_agents_orgqueryservice_consumer, atualcombattraining_agents_orgqueryservice_integration [EXTRACTED 1.00]
- **JWT 鉴权链路 (签发/验签/解析)** — staffmanagement_agents_jwt_issuance, gateway_agents_jwtauthfilter, speechtraining_agents_speechjwtfilter, gateway_agents_identity_propagation [INFERRED 0.85]

## Communities (204 total, 75 thin omitted)

### Community 0 - "Exam Catalog CRUD & Shared DTOs"
Cohesion: 0.03
Nodes (20): ApiResponse, AdminPublishController, AdminThemeController, AssistantController, MaterialBriefResp, RollbackResp, ThemeSaveResp, VersionResp (+12 more)

### Community 1 - "Auth Controllers & Web Layer"
Cohesion: 0.05
Nodes (19): ArrangeCatalogController, ArrangeExportController, ArrangeSignatureController, ExamResultController, PaperCatalogController, PaperStrategyController, ExamCatalogDtos, MoveReq (+11 more)

### Community 2 - "Exam DTOs & PageResult Contract"
Cohesion: 0.03
Nodes (76): AssistantSessionReq, Access, AntiCheat, Basic, ContentOverview, PassStandard, Strategy, ThemeSettingsResp (+68 more)

### Community 3 - "Exam & Speech Entities"
Cohesion: 0.06
Nodes (35): ActAiCallLog, ExamAnswerStat, ExamArrangeCatalogAdmin, ExamArrangeOperationLog, ExamArrangeScoreStat, ExamArrangeSignature, ExamArrangeStudent, ExamAttempt (+27 more)

### Community 4 - "Combat AI Mappers & Artifacts"
Cohesion: 0.04
Nodes (45): ActAiArtifactMapper, ActAiCallLogMapper, ActAiMaterialMapper, ActAiSessionMapper, ActCustomerProfileMapper, ActDrillScriptMapper, ActThemeVersionMapper, ExamAnswerStatMapper (+37 more)

### Community 5 - "Assistant Identity & Biz Types"
Cohesion: 0.06
Nodes (13): AssistantIdentity, BizTypes, JsonSupport, DifyCallLogService, ActAiSession, AssistantContextService, AssistantMaterialService, AssistantToolServiceImpl (+5 more)

### Community 6 - "Exam Notice Outbox & DTOs"
Cohesion: 0.05
Nodes (20): NoticeReq, ExamNoticeOutbox, ExamChangeNoticeMapper, ExamNoticeOutboxMapper, ArrangeNoticeServiceImpl, TypeReference<List<String>>, ExamNoticeOutboxWorker, ArrangeNoticeServiceTest (+12 more)

### Community 7 - "Spring Boot Test Scaffolding"
Cohesion: 0.05
Nodes (14): AiGentServiceApplicationTests, AtualCombatTrainingApplicationTests, MaterialBriefRespTest, TikaDocumentTextExtractorTest, ExamServiceApplicationTests, CatalogServiceTest, JwtAuthFilterTest, GatewayApplicationTests (+6 more)

### Community 8 - "Excel/Word Export (POI)"
Cohesion: 0.06
Nodes (16): CurrentUserService, QuesRespDtos, ExamArrange, ExamLib, ExamPaper, ExamPoint, ExamQuestionPoint, ExamArrangeMapper (+8 more)

### Community 10 - "Exam Paper & Question DTOs"
Cohesion: 0.06
Nodes (9): PaperDetail, PaperItem, ItemResp, PointResp, QuesDetail, ExamQuestion, PaperServiceImpl, QuesExportServiceImpl (+1 more)

### Community 11 - "Excel/Word Export (POI)"
Cohesion: 0.06
Nodes (15): KngReadDtos, KngRef, KngRefMapper, CourseRefService, CourseRefServiceImpl, SubjectServiceImpl, ProjectStudent, ProjectStudentMapper (+7 more)

### Community 12 - "Staff User Admin & Sync"
Cohesion: 0.04
Nodes (12): InternalToolController, CourseController, CourseService, ListVo, DepartmentNodeVo, PositionController, PositionService, RoleController (+4 more)

### Community 13 - "Mockito Test Scaffolding"
Cohesion: 0.11
Nodes (5): ActThemeMapper, KngSetting, KngSettingMapper, CoursewareServiceTest, SubtitlePipelineTest

### Community 14 - "Exam Catalog Tree Entities"
Cohesion: 0.06
Nodes (9): AdminUser, TreeNode, ExamArrangeCatalog, ExamCatalog, ExamPaperCatalog, ExamArrangeCatalogMapper, ArrangeCatalogServiceImpl, CatalogServiceImpl (+1 more)

### Community 15 - "Combat Scenario & Version Responses"
Cohesion: 0.06
Nodes (19): ThemeListReq, Access, AntiCheat, Basic, PassStandard, Strategy, ThemeSettingsSaveReq, StatusResp (+11 more)

### Community 16 - "Classroom Catalog Tree DTOs"
Cohesion: 0.06
Nodes (16): BatchRequest, CountResponse, Detail, IdResponse, KngCatalogDtos, MoveRequest, PermissionItem, SaveRequest (+8 more)

### Community 17 - "Document Extraction (POI/Tika)"
Cohesion: 0.04
Nodes (9): Asr, AssistantProperties, Ws, AssistantFileServiceImpl, DifyWorkflowSupport, DifyWorkflowSupportImpl, DifyWorkflowSupport, DifyWorkflowSupportImpl (+1 more)

### Community 18 - "Course Operation Resources"
Cohesion: 0.05
Nodes (9): CourseOpController, CoursewareOpController, CreateRequest, CopyRequest, DownloadRecordResponse, IdsRequest, IdStatusResponse, CourseOpService (+1 more)

### Community 19 - "Combat WebSocket Registry"
Cohesion: 0.06
Nodes (8): AssistantChatService, WsChatHandler, Lane, WsInboundDispatcher, WsSessionRegistry, WsInboundDispatcherTest, FileStorageServiceImpl, FileStorageServiceTest

### Community 20 - "Staff Auth & Crypto"
Cohesion: 0.06
Nodes (11): GatewayAuthProperties, JwtAuthFilter, SpeechJwtFilter, SpeechJwtService, SpeechJwtServiceImpl, JwtFilter, JwtFilterConfig, JwtServiceImpl (+3 more)

### Community 21 - "Speech User Context Tests"
Cohesion: 0.05
Nodes (12): Principal, SpeechAuthContext, AuthController, MeResp, InviteStudentsReq, InviteResult, AccessControlService, AccessControlServiceImpl (+4 more)

### Community 22 - "Combat Training Dify Tests"
Cohesion: 0.09
Nodes (7): AssistantActions, AssistantEventPublisher, AssistantSessionService, AssistantChatServiceImpl, DifyAgentClient.AgentStreamListener, AssistantRoundContext, AssistantEventPublisherTest

### Community 23 - "Speech AI Generation DTOs"
Cohesion: 0.07
Nodes (19): AiGenDtos, GenOutlineReq, GenSceneReq, GenScriptReq, ImportScriptReq, KngListReq, MaterialItem, OutlineItem (+11 more)

### Community 24 - "Speech Times Averages Report"
Cohesion: 0.06
Nodes (21): PageResult, PaperQuesItem, AdminProjectStatisticsController, AdminProjectStudentController, ProjectStudentQueryReq, Active, ActiveItem, AttendRecordRow (+13 more)

### Community 25 - "Speech Voice Message Results"
Cohesion: 0.08
Nodes (14): KeyPoint, KeyPointsUtil, MessageResp, SendMessageResp, TrainingNode, TrainingSessionMessage, TrainingNodeMapper, AiHitResult (+6 more)

### Community 26 - "Notice Query & Recipients"
Cohesion: 0.06
Nodes (14): ArrangeNoticeController, ArrangeOptionsController, ArrangeNoticeDtos, ArrangeNoticeRespDtos, NoticeItem, Preview, SendResult, ArrangeNoticeService (+6 more)

### Community 27 - "Staff Login & User VOs"
Cohesion: 0.08
Nodes (12): ChangePasswordRequest, AuthService, AuthServiceImpl, LoginResultVo, LoginUserVo, BizException, AuthProperties, SysUser (+4 more)

### Community 30 - "Combat Publish Init"
Cohesion: 0.09
Nodes (11): PublishResp, ScriptProgressResp, ActCaseScript, ActDrillScript, ActEvaluationDimension, ActCaseScriptMapper, ActEvaluationDimensionMapper, ActScenarioUnitMapper (+3 more)

### Community 31 - "Speech Session Controllers"
Cohesion: 0.06
Nodes (14): StudentSessionController, SessionDetailResp, StartSessionResp, VoiceMessageResp, TrainingSession, TrainingProjectMapper, TrainingSessionMapper, FlowEngineService (+6 more)

### Community 32 - "Speech AI Gen & ASR Service"
Cohesion: 0.06
Nodes (5): BizException, AssistantAsrServiceImpl, QwenTranslateEngine, AIGenService, StudentSessionAccessServiceImpl

### Community 33 - "Classroom Saved Attachments"
Cohesion: 0.07
Nodes (24): BasicSaveRequest, Contributor, DeptBrief, DeptFinish, DeptFinishRequest, DesignerChapter, DesignerSaveChapter, DesignerSaveRequest (+16 more)

### Community 34 - "Speech Project Statistics"
Cohesion: 0.11
Nodes (4): PracticeAnalysis, PracticeItem, ProjectStatisticsServiceImpl, StudentAggregate

### Community 35 - "File Upload Controllers"
Cohesion: 0.07
Nodes (16): AssistantFileService, AiGenController, FileController, PaperImportController, AiGenDtos, AiGenReq, Counts, ImportResult (+8 more)

### Community 36 - "Classroom Study Overview Stats"
Cohesion: 0.08
Nodes (6): ChapterStudyStats, TaskStudyStats, StudyRecentCountRow, KngCourseTask, KngCourseTaskMapper, CourseServiceImpl

### Community 38 - "Cross-Service Docs & Rationale"
Cohesion: 0.06
Nodes (46): after-end-common 全站公共基础模块, ApiResponse 响应契约, BizException, GlobalExceptionHandler, MyBatis-Plus 分页插件 (AutoConfiguration), Artifact Versioning (act_theme_version), AtualCombatTraining (AI 实战训练场), Dify Orchestration Agent (side_free / theme-gen / case-gen) (+38 more)

### Community 39 - "Thread Pool & Async Config"
Cohesion: 0.09
Nodes (9): ArtifactPatchesReq, CreateTopicReq, GenerateCasesReq, AcceptedResp, CreateTopicResp, UpdateArtifactResp, AssistantToolService, CaseGenerationService (+1 more)

### Community 40 - "Combat Material Summarizer"
Cohesion: 0.10
Nodes (6): FileUploadResp, DocumentTextExtractor, AssistantMaterialServiceImpl, MaterialTextSummarizer, AssistantMaterialServiceImplTest, MaterialTextSummarizerTest

### Community 41 - "Exam Question Transfer & Sharing"
Cohesion: 0.06
Nodes (5): PaperController, ShareItem, ShareReq, PaperUseItem, PaperService

### Community 42 - "Classroom Settings Rules"
Cohesion: 0.12
Nodes (12): CheatConf, CheatSaveRequest, DocumentRule, FaceConf, KngSettingDtos, MicrocourseRule, PlayConf, Response (+4 more)

### Community 43 - "Exam Transfer & List Queries"
Cohesion: 0.07
Nodes (10): LibController, Permissions, DuplicateCondition, ExamLibRespDtos, LibDetail, LibItem, Permissions, ShareInfo (+2 more)

### Community 44 - "Classroom Move & Tx Ops"
Cohesion: 0.08
Nodes (6): KngCoursewareAttachment, KngCoursewareFile, KngCoursewareAttachmentMapper, KngCoursewareFileMapper, CoursewareService, CoursewareOpServiceImpl

### Community 45 - "Common Web Exception Handling"
Cohesion: 0.09
Nodes (7): GlobalExceptionHandler, AdminAiController, AiScoringReq, AvatarGenReq, AvatarGenResp, AvatarGenService, GlobalExceptionHandlerTest

### Community 46 - "Scenario/Theme Service Impls"
Cohesion: 0.08
Nodes (3): ArrangeSettingServiceImpl, DifyScoringServiceImpl, TypeReference<List<String>>

### Community 47 - "Speech Flow Write & Scoring"
Cohesion: 0.12
Nodes (6): DifyScoringService, AiScoringResp, TrainingRoundAcousticMapper, TrainingSessionMessageMapper, FlowEngineVoiceShortCircuitTest, FlowEngineWritePhaseTest

### Community 50 - "Staff Role Service & BizException"
Cohesion: 0.09
Nodes (7): SysRole, SysRoleMapper, RoleFormRequest, RoleServiceImpl, RoleService, RoleDetailVo, RoleListVo

### Community 51 - "Speech Training Project Impl"
Cohesion: 0.09
Nodes (6): EdgeItemReq, VirtualTrainerReq, TrainingProjectServiceImpl, UserContext, TrainingProjectServiceImplTest, UserContextTest

### Community 52 - "Combat Training Confirms & Artifacts"
Cohesion: 0.14
Nodes (3): ArtifactSupport, ArtifactSupportTest, ArtifactServiceImplPartialMergeTest

### Community 53 - "Combat WS E2E Test Helpers"
Cohesion: 0.06
Nodes (21): bizEvents(), check(), failures, fileIds(), filesStatus(), TIMEOUT_MS, upload(), uploadAndAssert() (+13 more)

### Community 54 - "Exam Urge & Sync Requests"
Cohesion: 0.09
Nodes (12): ArrangeStudentController, ArrangeStudentDtos, BatchRemoveReq, SyncReq, UrgeReq, AnalyzeResp, ArrangeStudentRespDtos, RetakeResp (+4 more)

### Community 55 - "Combat WS Frame Encoding"
Cohesion: 0.15
Nodes (3): RoundWriter, WsFrameEncoder, WsFrameEncoderTest

### Community 56 - "Exam Lib Permissions"
Cohesion: 0.12
Nodes (4): ArrangeDtos, BatchReq, ListQuery, LibServiceImpl

### Community 57 - "Classroom Tx Sync & Scheduling"
Cohesion: 0.11
Nodes (7): JobItem, JobsResponse, KngMediaJob, KngMediaJobMapper, MediaJobService, TransactionSynchronization, MediaWorker

### Community 58 - "Classroom Move & Tx Ops"
Cohesion: 0.13
Nodes (5): KngCourse, KngCourseChapter, KngCourseChapterMapper, KngCourseMapper, CourseOpServiceImpl

### Community 59 - "Staff Role Member Service"
Cohesion: 0.09
Nodes (6): SysUserRole, RoleCountRow, SysUserRoleMapper, RoleMemberQuery, RoleMemberServiceImpl, RoleMemberVo

### Community 60 - "Speech TTS Cache Service"
Cohesion: 0.09
Nodes (4): FileStorageService, TtsCacheServiceImpl, AdminProjectControllerTest, DifyWorkflowSupportTest

### Community 61 - "Combat Artifact & Case Entities"
Cohesion: 0.14
Nodes (5): ActAiArtifact, ArtifactService, MaterialVisibilityResult, AssistantContextServiceImpl, ConfirmServiceImplTest

### Community 62 - "Staff Transaction Management"
Cohesion: 0.08
Nodes (9): SyncProperties, DeptPathCountRow, DeptPathInfoRow, SysUserMapper, PositionServiceImpl, OracleSyncJob, OracleSyncService, SyncLogVo (+1 more)

### Community 64 - "Staff Transaction Management"
Cohesion: 0.12
Nodes (6): DepartmentService, DepartmentServiceImpl, SysDepartment, SysDepartmentMapper, OracleSyncServiceImpl, SyncCounters

### Community 65 - "Combat AI Session DB Columns"
Cohesion: 0.12
Nodes (10): Artifact, AssistantSessionResp, Message, MessagePageResp, Pagination, Sender, ActAiMessage, ActAiMessageMapper (+2 more)

### Community 66 - "Exam Arrange Requests"
Cohesion: 0.09
Nodes (6): ArrangeController, ArrangeDetail, ArrangeItem, ArrangeRespDtos, LinkResp, ArrangeService

### Community 67 - "Exam Catalog CRUD & Shared DTOs"
Cohesion: 0.09
Nodes (5): QuesController, QuesItem, QuesExportService, QuesImportService, QuesService

### Community 68 - "Classroom Transcode & Subtitles"
Cohesion: 0.08
Nodes (25): AttachmentItem, Contributor, CountResponse, DeptBrief, DeptFinish, DeptFinishRequest, Detail, FileItem (+17 more)

### Community 69 - "Speech Template Save"
Cohesion: 0.12
Nodes (6): AdminTemplateController, TemplateQueryParam, TemplateSaveReq, TemplateDetailResp, TemplateListResp, SpeechTemplateService

### Community 71 - "Staff Persistence & Dubbo Services"
Cohesion: 0.17
Nodes (11): DepartmentStaffDTO, EmployeeDTO, PageResult, DepartmentStaffQuery, EmployeeSearchQuery, WorkStatus, ACTIVE, ALL (+3 more)

### Community 75 - "Staff Bootstrap & PasswordEncoder"
Cohesion: 0.12
Nodes (6): DataInitializer, Seed, SysPermissionMapper, SysRolePermissionMapper, PermissionServiceImpl, PermissionNodeVo

### Community 76 - "CORS & Dubbo Consumer Config"
Cohesion: 0.12
Nodes (8): AssistantExecutorConfig, Pool, DubboConsumerConfig, DubboConsumerConfig, MediaRestTemplateConfig, RestTemplateConfig, DubboConsumerConfig, NotificationPublishService

### Community 77 - "Staff PageVo & Query Helpers"
Cohesion: 0.10
Nodes (11): PageVo, PositionVo, UserQuery, UserRowVo, UserSearchField, ACCOUNT, EMAIL, EMPLOYEE_NO (+3 more)

### Community 78 - "Exam Question Stats & Scoring"
Cohesion: 0.14
Nodes (11): ArrangeStatController, AnswerDetail, ArrangeStatRespDtos, CorrectRateResp, OptionStat, OverviewResp, QuestionQuery, QuestionStat (+3 more)

### Community 79 - "Combat Training Confirms & Artifacts"
Cohesion: 0.19
Nodes (3): ConfirmResult, ConfirmService, ConfirmServiceImpl

### Community 80 - "Dify Workflow Event Parser"
Cohesion: 0.24
Nodes (3): DifyWorkflowEventParser, Listener, DifyWorkflowEventParserTest

### Community 81 - "Speech Multipart Project Parsing"
Cohesion: 0.18
Nodes (3): ProjectFormParser, AdminProjectController, ProjectSaveReq

### Community 82 - "Speech TTS Cache & Voice Map"
Cohesion: 0.14
Nodes (8): Acoustic, AsrResult, SpeechService, TtsResult, SpeechServiceImpl, TtsCacheService, VoiceMapProperties, VoiceMapPropertiesBindingTest

### Community 83 - "Combat Scenario & Version Responses"
Cohesion: 0.16
Nodes (5): EvaluationDimensionResp, ScenarioUnitResp, ActScenarioUnit, ScenarioUnitServiceImpl, ScenarioUnitServiceImplTest

### Community 85 - "Classroom Transcoding Tasks"
Cohesion: 0.20
Nodes (3): VideoAudioCodecs, TranscodeTask, TranscodeTaskTest

### Community 86 - "Speech Report Generation & Ranking"
Cohesion: 0.16
Nodes (4): DimensionPlan, RankResult, ReportGenerationServiceImpl, com.fasterxml.jackson.core.type.TypeReference<List<String>>

### Community 87 - "Cross-Service Mapper & Entity Scaffolding"
Cohesion: 0.14
Nodes (6): StudentProjectController, PracticeRecordResp, ProjectCardResp, Tops, StudentPageData, StudentProjectDetailResp

### Community 88 - "Spring Boot Entrypoints"
Cohesion: 0.15
Nodes (7): AiGentServiceApplication, AtualCombatTrainingApplication, ExamServiceApplication, GatewayApplication, OnlineClassroomApplication, SpeechTrainingApplication, StaffManagementApplication

### Community 89 - "Exam DTOs & PageResult Contract"
Cohesion: 0.11
Nodes (3): Item, ScoreConfigDtos, TrainingReportDimension

### Community 91 - "Exam Paper Strategy Summary"
Cohesion: 0.12
Nodes (7): BatchCellReq, CellReq, PaperStrategyDtos, SourceReq, PaperStrategyRespDtos, StrategyItem, PaperStrategyService

### Community 92 - "Exam DTOs & PageResult Contract"
Cohesion: 0.10
Nodes (12): AddReq, BatchDeleteReq, BatchScoreReq, Move, PaperQuesDtos, PickItem, PickReq, ScoreItem (+4 more)

### Community 93 - "Classroom SRT Service"
Cohesion: 0.15
Nodes (6): AsrEngine, BailianAsrEngine, Sentence, SrtService, BailianAsrEngineTest, SrtServiceTest

### Community 94 - "Speech Report Metric Outcomes"
Cohesion: 0.20
Nodes (3): ScoringDimension, ForbiddenResult, ReportGenerationServiceTest

### Community 95 - "Web MVC Config (Resources)"
Cohesion: 0.15
Nodes (6): JacksonConfig, ExamWebConfig, KngWebConfig, SpeechJwtFilterConfig, WebMvcConfig, SecurityConfig

### Community 96 - "Speech Multipart Project Parsing"
Cohesion: 0.14
Nodes (4): ProjectQueryParam, ProjectDetailResp, ProjectListResp, TrainingProjectService

### Community 97 - "Staff Transaction Management"
Cohesion: 0.15
Nodes (4): StaffViewRepository, OracleReader, OracleDepartment, OracleEmployee

### Community 99 - "Combat Training Confirms & Artifacts"
Cohesion: 0.18
Nodes (3): GenerateThemeReq, ThemeGenerationServiceImpl, ThemeGenerationService

### Community 100 - "Combat Training Confirms & Artifacts"
Cohesion: 0.19
Nodes (3): CaseMergeResult, CaseGenerationServiceImpl, CaseGenerationDifficultyTest

### Community 102 - "Speech Doubao SAUC WebSocket"
Cohesion: 0.16
Nodes (3): DoubaoSaucClient, FrameListener, ServerFrame

### Community 103 - "Classroom Subtitles"
Cohesion: 0.18
Nodes (5): SubtitleEntry, SubtitleSaveRequest, SubtitlesResponse, SubtitlePipeline, TranslateEngine

### Community 104 - "Staff Persistence & Dubbo Services"
Cohesion: 0.19
Nodes (3): DepartmentNodeDTO, DepartmentStat, DepartmentDirectoryServiceImpl

### Community 105 - "Speech Student Report Service"
Cohesion: 0.17
Nodes (6): StudentReportController, DimensionItem, LastDimensionItem, ReportResp, RoundSuggestionItem, StudentReportService

### Community 106 - "Speech Pass Strategy & Reports"
Cohesion: 0.20
Nodes (4): TrainingProject, TrainingReport, TrainingReportMapper, ProjectStudentServiceImpl

### Community 108 - "Staff Auth & Crypto"
Cohesion: 0.10
Nodes (14): ErrorCode, ACCOUNT_DISABLED, BAD_CREDENTIALS, BUILTIN_ADMIN_PROTECTED, FORBIDDEN, NOT_FOUND, OLD_PASSWORD_WRONG, PARAM_ERROR (+6 more)

### Community 109 - "Common Id-As-String Serialization"
Cohesion: 0.14
Nodes (4): IdAsStringAutoConfiguration, IdAsStringModule, ValueSerializerModifier, JacksonConfig

### Community 110 - "Classroom Upload Service"
Cohesion: 0.20
Nodes (7): KngUploadChunk, StorageService, InitRequest, InitResult, MergeRequest, MergeResult, UploadService

### Community 111 - "Speech Speed & Dimension Metrics"
Cohesion: 0.20
Nodes (3): DimensionMetricConfig, TypeReference<Map<String, Object>>, ScoringDimensionReq

### Community 112 - "Document Extraction (POI/Tika)"
Cohesion: 0.17
Nodes (5): AgentStreamListener, AgentStreamResult, DifyAgentClient, DifyProperties, DifyWorkflowClient

### Community 113 - "Staff Multi-Datasource Config"
Cohesion: 0.15
Nodes (5): DifyProperties, AgentConfig, DifyProperties, DoubaoAsrProperties, DataSourceConfig

### Community 114 - "Classroom Study Count DTOs"
Cohesion: 0.14
Nodes (7): StudyRecordQuery, StudyRecordResponse, FinishTimeRow, StudyCountRow, StudyDeptCountRow, StudyOverviewRow, KngStudyRecordMapper

### Community 115 - "Speech Student Session Controller"
Cohesion: 0.15
Nodes (3): ReportGenerationService, FlowEngineRoutingTest, FlowEngineServiceImpl

### Community 116 - "Speech Template Save"
Cohesion: 0.17
Nodes (5): EdgeResp, FlowDataResp, KeyPointItem, NodeResp, SpeechTemplateServiceImpl

### Community 118 - "Classroom Role/Position Rows"
Cohesion: 0.19
Nodes (7): SubjectController, DeptNode, KngSubjectDtos, PageResult, PositionRow, RoleRow, SubjectService

### Community 119 - "Speech Tone Scoring"
Cohesion: 0.18
Nodes (5): ToneRoundInput, ToneScoringService, RoundResult, ScoreAttempt, ToneScoringServiceImpl

### Community 120 - "Cross-Service Mapper & Entity Scaffolding"
Cohesion: 0.19
Nodes (5): MyRank, ProjectRankResp, Rank, RankItem, ProjectRankServiceImpl

### Community 121 - "Exam Arrange Catalog Controllers"
Cohesion: 0.17
Nodes (3): BatchCreateReq, BatchCreateResult, CatalogService

### Community 122 - "Exam Point Service"
Cohesion: 0.20
Nodes (5): PointController, PointDtos, PointItem, PointRespDtos, PointService

### Community 124 - "Staff User Search Fields"
Cohesion: 0.14
Nodes (7): DeleteOutcome, DELETED, NOT_ORACLE, PROTECTED, UserAdminService, Fixture, UserAdminServiceTest

### Community 130 - "Speech Training DB Schema"
Cohesion: 0.12
Nodes (15): project_student, scoring_dimension, speech_template, template_edge, template_node, training_edge, training_message_like, training_node (+7 more)

### Community 131 - "Combat WS Verification Helpers"
Cohesion: 0.22
Nodes (14): check(), connectAndSend(), createSession(), EXPECTED_ORDER, EXPECTED_SUFFIX, failures, getJson(), notes (+6 more)

### Community 133 - "Combat WS Flow Test Helpers"
Cohesion: 0.15
Nodes (7): AUDIENCES, bizEvents(), byBiz(), CASE_WAIT_MS, failures, PROGRESS_WAIT_MS, THEME_WAIT_MS

### Community 134 - "Speech Report Agent Output"
Cohesion: 0.27
Nodes (5): AgentDimension, AgentRoundSuggestion, DifyReportScoringService, ReportAgentOutput, DifyReportScoringServiceImpl

### Community 138 - "Combat WS Flow Test Helpers II"
Cohesion: 0.17
Nodes (5): dataPartPayloads(), failures, findBizEvent(), PROGRESS_TIMEOUT_MS, TIMEOUT_MS

### Community 142 - "Speech TTS Cache & Voice Map"
Cohesion: 0.24
Nodes (3): TtsCacheHitTest, SpeechServiceImpl, SpeechServiceImpl

### Community 144 - "Exam Arrange Column Settings"
Cohesion: 0.29
Nodes (5): ArrangeSettingController, ArrangeSettingDtos, Column, ColumnSetting, ArrangeSettingService

### Community 145 - "Exam Result Views & PDF"
Cohesion: 0.26
Nodes (7): ChoiceItem, ExamResultRespDtos, ResultView, TypeTitle, UserQues, ExamResultService, PdfFile

### Community 146 - "Classroom Async Executor Config"
Cohesion: 0.24
Nodes (3): GatewayAuthConfig, AsyncConfig, SchedulingConfig

### Community 148 - "Combat WS Frame Test Helpers"
Cohesion: 0.18
Nodes (8): assistantText, dataParts, events, finished, frames, parts, TIMEOUT_MS, ws

### Community 157 - "Exam Question DB Schema"
Cohesion: 0.20
Nodes (9): `exam_answer_stat`, `exam_catalog`, `exam_catalog_admin`, `exam_lib`, `exam_lib_share`, `exam_point`, `exam_question`, `exam_question_item` (+1 more)

### Community 159 - "Exam Question Save DTOs"
Cohesion: 0.33
Nodes (9): BatchReq, DuplicateCheckReq, ItemReq, Keyword, ListQuery, PointRef, QuesDtos, SaveReq (+1 more)

### Community 165 - "Combat Training DB Schema"
Cohesion: 0.22
Nodes (8): act_ai_artifact, act_ai_call_log, act_ai_material, act_ai_message, act_ai_session, act_case_script, act_customer_profile, act_drill_script

### Community 166 - "CJK Font Loading for PDF Export"
Cohesion: 0.25
Nodes (9): SIL Open Font License 1.1, ASCII Degradation Fallback for PDF Export, Bundled CJK Fonts for Exam Answer-Sheet PDF, exam.pdf.font-path Configuration, ExamResultServiceImpl.loadCjkFont, Three-Tier CJK Font Loading Order, Microsoft System Fonts Not Redistributable, Noto Sans SC Regular (SubsetOTF/SC) Font (+1 more)

### Community 167 - "Dubbo Service Registry Names"
Cohesion: 0.31
Nodes (9): jack.digityHuman:after-end-common, jack.digityHuman:AIGentService, jack.digityHuman:AtualCombatTraining, jack.digityHuman:ExamService, jack.digityHuman:OnlineClassroom, jack.digityHuman:SpeechTraining, jack.digityHuman:StaffManagement, jack.digityHuman:StaffManagement-api (+1 more)

### Community 172 - "Staff User Auth DB Schema"
Cohesion: 0.25
Nodes (7): sys_department, sys_permission, sys_role, sys_role_permission, sys_sync_log, sys_user, sys_user_role

### Community 173 - "WS Finish Probe Script"
Cohesion: 0.29
Nodes (4): seen, traceId, ws, WS_BASE

### Community 175 - "Classroom Courseware DB Schema"
Cohesion: 0.29
Nodes (6): `kng_courseware`, `kng_courseware_attachment`, `kng_courseware_file`, `kng_ref`, `kng_study_record`, `kng_upload_chunk`

### Community 180 - "Staff Sync Cleanup DB Schema"
Cohesion: 0.29
Nodes (6): project_student_bak_20260928, sys_department_bak_20260928, sys_user_bak_20260928, sys_user_role_bak_20260928, tmp_sync_cleanup_protected, tmp_sync_cleanup_users

### Community 182 - "Speech Training DB Schema II"
Cohesion: 0.33
Nodes (5): training_message_like, training_report, training_report_dimension, training_session, training_session_message

### Community 185 - "Classroom Kng DB Schema"
Cohesion: 0.50
Nodes (3): `kng_catalog`, `kng_catalog_permission`, `kng_setting`

### Community 186 - "Notification DB Schema"
Cohesion: 0.50
Nodes (3): `sys_device_token`, `sys_notice_event`, `sys_notice_recipient`

## Knowledge Gaps
- **41 isolated node(s):** `ACTIVE`, `ALL`, `LEFT`, `jack.digityHuman:AIGentService`, `jack.digityHuman:AtualCombatTraining` (+36 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 729 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **75 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `BizException` connect `Speech AI Gen & ASR Service` to `Exam Catalog CRUD & Shared DTOs`, `Auth Controllers & Web Layer`, `Exam DTOs & PageResult Contract`, `Exam & Speech Entities`, `Assistant Identity & Biz Types`, `Spring Boot Test Scaffolding`, `Excel/Word Export (POI)`, `Exam Paper & Question DTOs`, `Excel/Word Export (POI)`, `Staff User Admin & Sync`, `Mockito Test Scaffolding`, `Exam Catalog Tree Entities`, `Combat Scenario & Version Responses`, `Classroom Catalog Tree DTOs`, `Document Extraction (POI/Tika)`, `Course Operation Resources`, `Combat WebSocket Registry`, `Speech User Context Tests`, `Combat Training Dify Tests`, `Speech AI Generation DTOs`, `Speech Voice Message Results`, `HTTP Client Test Helpers`, `Classroom Study Attachments`, `Combat Publish Init`, `Speech Session Controllers`, `Classroom Saved Attachments`, `Speech Project Statistics`, `File Upload Controllers`, `Classroom Study Overview Stats`, `Thread Pool & Async Config`, `Combat Material Summarizer`, `Classroom Settings Rules`, `Classroom Move & Tx Ops`, `Common Web Exception Handling`, `Scenario/Theme Service Impls`, `Speech Flow Write & Scoring`, `Document Extraction (POI/Tika)`, `Speech Training Project Impl`, `Combat Training Confirms & Artifacts`, `Classroom Tx Sync & Scheduling`, `Classroom Move & Tx Ops`, `Combat Artifact & Case Entities`, `Combat AI Session DB Columns`, `Combat Training Confirms & Artifacts`, `Speech Student Project Service`, `Exam Question Stats & Scoring`, `Dify Workflow Event Parser`, `Speech Multipart Project Parsing`, `Speech TTS Cache & Voice Map`, `Exam Paper Strategy`, `Classroom Transcoding Tasks`, `Speech Report Generation & Ranking`, `Cross-Service Mapper & Entity Scaffolding`, `Classroom SRT Service`, `Speech Report Metric Outcomes`, `Speech Multipart Project Parsing`, `Combat Training Confirms & Artifacts`, `Combat Training Confirms & Artifacts`, `Exam Question Stats & Scoring`, `Speech Doubao SAUC WebSocket`, `Classroom Subtitles`, `Speech Student Report Service`, `Speech Pass Strategy & Reports`, `Classroom Upload Service`, `Speech Speed & Dimension Metrics`, `Document Extraction (POI/Tika)`, `Speech Template Save`, `Exam Question Import Rows`, `Cross-Service Mapper & Entity Scaffolding`, `Shared DTOs (Staff/Classroom)`, `Classroom OSS Temp Storage`, `Speech Report Agent Output`, `Exam AI Generation Requests`, `Speech TTS Cache & Voice Map`, `Speech Material Extraction`, `Media Job Service Tests`, `File Upload Controllers`, `Artifact Support & Scoring Tiers`?**
  _High betweenness centrality (0.139) - this node is a cross-community bridge._
- **What connects `ACTIVE`, `ALL`, `LEFT` to the rest of the system?**
  _41 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Exam Catalog CRUD & Shared DTOs` be split into smaller, more focused modules?**
  _Cohesion score 0.03464147286821705 - nodes in this community are weakly interconnected._
- **Why does `ApiResponse` connect `Exam Catalog CRUD & Shared DTOs` to `Auth Controllers & Web Layer`, `Exam DTOs & PageResult Contract`, `Exam & Speech Entities`, `Assistant Identity & Biz Types`, `Excel/Word Export (POI)`, `Staff User Admin & Sync`, `Exam Arrange Column Settings`, `Course Operation Resources`, `Speech User Context Tests`, `Speech Times Averages Report`, `Auth Controllers & Web Layer`, `Speech Session Controllers`, `File Upload Controllers`, `Exam Question Transfer & Sharing`, `Exam Transfer & List Queries`, `Common Web Exception Handling`, `Exam Urge & Sync Requests`, `Speech Multipart Project Parsing`, `Exam Arrange Requests`, `Exam Catalog CRUD & Shared DTOs`, `Speech Template Save`, `Staff Persistence & Dubbo Services`, `Exam Question Stats & Scoring`, `Speech Multipart Project Parsing`, `Cross-Service Mapper & Entity Scaffolding`, `Exam Paper Strategy Summary`, `Speech Multipart Project Parsing`, `Speech Student Report Service`, `Speech Student Session Controller`, `Classroom Role/Position Rows`, `Cross-Service Mapper & Entity Scaffolding`, `Exam Arrange Catalog Controllers`, `Exam Point Service`?**
  _High betweenness centrality (0.079) - this node is a cross-community bridge._
- **Should `Auth Controllers & Web Layer` be split into smaller, more focused modules?**
  _Cohesion score 0.05438468992248062 - nodes in this community are weakly interconnected._
- **Why does `CoursewareServiceImpl` connect `Classroom Study Attachments` to `Classroom Saved Attachments`, `Combat AI Mappers & Artifacts`, `Classroom Transcode & Subtitles`, `Assistant Identity & Biz Types`, `Classroom Settings Rules`, `Excel/Word Export (POI)`, `Classroom Move & Tx Ops`, `Mockito Test Scaffolding`, `Scenario/Theme Service Impls`, `Classroom Study Count DTOs`, `Classroom Tx Sync & Scheduling`?**
  _High betweenness centrality (0.018) - this node is a cross-community bridge._
- **Should `Exam DTOs & PageResult Contract` be split into smaller, more focused modules?**
  _Cohesion score 0.027669129364044618 - nodes in this community are weakly interconnected._