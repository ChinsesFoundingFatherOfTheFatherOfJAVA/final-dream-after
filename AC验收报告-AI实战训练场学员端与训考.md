# AC-01~AC-25 验收报告 · AI实战训练场学员端与训考

> 版本：v2.0（终稿） ｜ 日期：2026-10-30 ｜ 验收人：AutoClaw（全权代码实施代理）
> 依据：`D:\JACK\plan\学员端AI实战训练场\01-需求文档.md` §9 验收清单
> 验证方式：① 单元测试（ACT 273 例 / AIGentService 68 例 / SpeechTraining 164 例 / 前端 35 例，全部绿）；
> ② ego 浏览器端到端实测（真服务 + 真 LLM + 真 ASR/TTS，2026-10-30）。

## 结论

**25/25 全部通过**（含人脸 provider 接入；仅 PDF Range 分片为非阻断优化项）。

## v2.0 终稿补齐（AC-11 人脸 provider）

- **配置化 provider**：`FaceVerificationProvider` HTTP 适配器（`act.face.provider= http-verify` + `verify-url` + `api-key` 三者齐备才 configured）；未配置 fail-closed，不假装通过。
- **证据与隐私**：抓拍存 `act-drill-evidence/{attemptId}/`（独立目录，禁止静态资源目录），元数据落 `act_face_evidence`（stage/result/passed/delete_after 30 天）；学员端只拿 `{passed,score,reason,evidenceId}`，不返回路径与 provider 细节（§12.3）。
- **启用守卫**：主题设置启用人脸但 provider 未配置 → 保存直接拒绝（§12.2「未配置时不允许启用」），杜绝假防作弊。
- **真机验证**：`/face/available` → `{configured:false}`；`/face/verify` → `code=1 人脸核验服务未配置`。

## v1.2 本轮补齐（AC-02 / AC-04 / AC-06）

- **首页三栏**：契约 02 §5.2 网格（真机 `280px 528px 320px`）——左"我的训练"统计、中主题与机会、右"最近练习"（进行中续练/完成进报告/其他回详情）；1024px 两栏、760px 单列；三区块独立 loading/empty，机会与最近练习失败静默。
- **详情维度摘要**：`StudentThemeDetailResp.Scenario` 增加 `dimensions[{name,weight}]`，真机渲染 22 个带权重标签。
- **场景准备向导**：新增 `ScenarioPrepareDialog`（三类入口统一）——随机练习难度选择（主题锁定则禁用）、自定义练习按后端 `requiredSteps` 渲染五步（产品档案→场景背景→AI 客户→难度→确认），预览页汇总并显示真实限时。
- **预检详情接口**：`PreflightResp` 七字段（allowed/activeAttemptId/requiredSteps/maxDurationSeconds/两个 remaining/checks），阻断码供前端解释。
- **关键修复**：Qwen `response_format` 拒绝 array 上的 `uniqueItems`（契约 schema 含此键），导致随机/自定义脚本生成与产品档案候选生成全部 400 失败；改为送模型层净化 schema，契约副本保持逐字一致。真机验证自定义练习脚本生成成功。

## v1.1 补齐（AC-10 / AC-11）

- **AC-10 语音**：SpeechTraining 新增 `/internal/voice/{asr,tts}`（复用 `SpeechService`，X-Internal-Token fail-closed，TTS 走"文本+声音+语速"缓存）；ACT 新增 `SpeechVoiceClient` + 学员端 `voice/transcribe`、`tts` 代理端点；对练页输入栏语音入口（WAV → ASR 文本回填，不自动发送）。
- **AC-11 防作弊**：`AntiCheatService` 事件白名单 + `uk_anti_cheat_client` 幂等 + 归属校验；切屏按 §12.1 结算（forbid 第 1 次即结束；allow 超过 limit 才结束，limit 缺失/≤0 按默认 3 次而非立即判违规）；结算走 finish CAS 幂等 + 自动出报告；前端 `useAntiCheat` 1 秒去重上报。
- **真机验证**：allow limit=2 时第 1/2 次切屏 `violation=false`、第 3 次 `violation=true` → attempt `report_generating` → `endReason=screen_violation` → 报告 `passed=0`（D21 强制）。

## 逐条验收

| 编号 | 结论 | 证据 |
|---|---|---|
| AC-01 | ✅ | E2E：StudentTopNav 启用「AI实战训练场」入口（startsWith 激活态），点击进入 `/sparring/web/drillmate` |
| AC-02 | ✅ | 三栏布局（真机 grid `280px 528px 320px`，契约 02 §5.2）：左统计（主题/训练/通过）、中主题+机会、右最近练习（进行中续练/完成进报告）；1024px 两栏、760px 单栏；三区块独立 loading/empty |
| AC-03 | ✅ | 新增 `GET /student/opportunities`（join_mode=public+已发布+计划期内+未参加，D02/D13），Home 机会卡区块，接口实测 code=0 |
| AC-04 | ✅ | 真机：主题详情 6 场景卡（限时长/通过分/已练次数）+ **评估维度摘要 22 个标签带权重**（合规开场 50% 等）+ 学习资料列表 |
| AC-05 | ✅ | 三类入口按 settings.practiceMethods 渲染（后端 checkPracticeMethod 强校验）；训练/考核按钮按模式开关禁用 |
| AC-06 | ✅ | 真机走完自定义五步向导（产品档案→场景背景→AI 客户→难度→确认）→ 创建 attempt → 脚本生成 `preparing→build_actor(85%)→ready`（maxRound=50/maxDuration=600 来自脚本 runtime）；随机练习难度可选（主题锁定则禁用） |
| AC-07 | ✅ | AttemptPreflight「需先完成 N 次训练才能开始考核」+ quotaService.examUnlocked 单测 |
| AC-08 | ✅ | markReady 状态 CAS（刷新不重复生成）；脚本失败置 failed 不扣次（AttemptGenerationServicesTest） |
| AC-09 | ✅ | D06：start CAS+扣次（单测），重复点击幂等（单测），E2E 实测 deadline 写入 |
| AC-10 | ✅ | v1.1：文字 ✓ E2E；教练点评 ✓ E2E（真 LLM）；语音 ASR ✓（voice/transcribe 真调 ST，实测鉴权与错误口径正确）；TTS ✓（端点 + ST 缓存，单测覆盖）；建议 AG-D03 就绪 |
| AC-11 | ✅ | 考核无 AI 辅助 ✓；切屏事件落库+规则结算 ✓（**真机**：allow limit=2 第 3 次触发 `screen_violation`，报告 passed=0）；人脸 provider 配置化适配器 ✓（HTTP 适配器 + 证据落库 + 保留期；**未配置时主题禁止启用人脸、verify fail-closed 拒绝**，真机验证 configured=false + 「人脸核验服务未配置」） |
| AC-12 | ✅ | finish/收口均 CAS 幂等（DrillRuntimeServiceImplTest）；uk_report_attempt 唯一；E2E 实测补偿任务自动恢复失败报告且未重复扣次 |
| AC-13 | ✅ | E2E 报告：passed(28.8<70=0)/targetReached/endReason(manual_exit)/timedOut 四字段独立（D04）；D21 强制 passed=false 单测 |
| AC-14 | ✅ | ReportPage「再练一次」→ 主题详情重选场景；每次开始创建新 attempt（后端无状态复用） |
| AC-15 | ✅ | history/records 均按 userId 过滤 + 分页（上限 50/100）；单测覆盖 |
| AC-16 | ✅ | StudentPanel 真实实现 AD01~AD06 + 8 例单测；E2E 实测添加学员（successCount=1） |
| AC-17 | ✅ | M2 版本/设置拆分（181 测试）：设置保存不产生版本（D18 锁定）；发布与 AI 确认版本决策全单测；E2E 使用已发布主题 v2 |
| AC-18 | ✅ | ProductProfileService（v0 工作区/快照隔离/删除引用保护/20 上限）+ 12 例单测 + ArtifactPanel 产品档案 Tab |
| AC-19 | ✅ | 在线课堂导入为既有实现（from-kng 同步更新材料 Tab + 解析消息），本轮未破坏（AssistantMaterialServiceImplTest 11 例） |
| AC-20 | ✅ | viewer-context 按 renderMode 分流（pdf/text/online_classroom）；路径规范化防穿越（单测）；preview_error 不下发 |
| AC-21 | ✅ | D13 计划时间窗硬准入（preflight 单测+实现）；计划结束后由 endPlanOverdueAttempts 收口在场 attempt |
| AC-22 | ✅ | preview_error/内部 id/token 不入响应；profileKey 不暴露数据库版本；WS 帧无敏感数据；日志不含密钥（AGENTS 铁律） |
| AC-23 | ✅ | AIGentService 出口自检 schemaVersion（DrillTaskTest）+ ACT 入口 DrillSchemas 深校验；E2E 报告 5 维度通过校验才落库 |
| AC-24 | ✅ | 迁移验证 6/6 PASS（1c2c64d）：未发布主题仅隐藏 v0、已发布指针与场景/维度映射正确 |
| AC-25 | ✅ | MaterialPreviewServiceImplTest：none provider → text_only 正式回退；上传/解析/发布不受转换器缺失影响（12 例） |

## 遗留缺口（后续迭代项）

**无非阻断遗留项之外的功能缺口。** 唯一优化项：PDF Range 分片（内网 ≤50MB 整文件返回已够用，浏览器 PDF 查看器不依赖 Range）。

## E2E 证据快照

- `tmp/e2e-home-3col.png`（三栏首页）、`tmp/e2e-detail-dims.png`（场景卡维度摘要）、`tmp/e2e-wizard3.png`（自定义五步向导）
- WS 一轮事件序列：connected → user_message_ack → assistant_start → coach_delta → assistant_delta → assistant_end → progress → turn_finished
- 正常报告：5 维度（合规开场35/身份确认30/争取转接25/风险顾虑15/边界礼仪40），总分 28.8，passed=0
- 切屏违规报告：`endReason=screen_violation`，passed=0，totalScore=0（D21 强制）
- 人脸守卫：`/face/available` → `{configured:false}`；`/face/verify` → `人脸核验服务未配置`（fail-closed）
- 自定义练习脚本：`preparing → build_actor(85%) → ready`（maxRound=50 / maxDuration=600 来自脚本 runtime）

## 提交清单（本目标全部提交）

数据线 `1c2c64d` → 版本拆分 → Schema 校验器 → attempt 生命周期 → AIGentService AG-D01~D04（65 测试）→ 脚本/报告接入 → WS 运行时 → 巡检+学生只读 → 产品档案+学员管理 → 材料预览 → 学员端前端 → 管理端前端 → E2E 修复×3 → 机会列表+再练（AC-03/14）→ 语音+防作弊（AC-10/11）→ 三栏+向导+预检详情（AC-02/04/06）→ Qwen schema 净化修复 → 人脸 provider（AC-11 完成，25/25）



