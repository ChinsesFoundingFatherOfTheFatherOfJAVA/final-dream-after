# AGENTS.md · OnlineClassroom（在线课堂，8084）

## 1. 应用定位

在线课堂业务：管理端（分类管理、课件管理——上传/转码/字幕/学分/防挂机/人脸识别设置、主体选人抽屉、
转码与学习记录看板）+ 学员端学习记录（kng_study_record）。

横向规范见 `D:\JACK\plan\后端架构总纲.md`；本模块纵向文档见 `D:\JACK\plan\在线课堂分类管理模块\`
（含课程设置扩展 v2.0 文档族）、`D:\JACK\plan\在线课堂课件管理模块\`。

## 2. 技术栈与包结构

- Java 21 / Spring Boot 4.0.8 / MyBatis-Plus 3.5.17（含 jsqlparser）/ Nacos discovery / Dubbo 消费者。
- 包结构：`common/`、`config/`、`controller/`、`dto/`、`entity/`、`mapper/`、`service/` + `service/impl/`。
  DTO 采用嵌套 record/类容器风格（`KngCatalogDtos`、`KngCoursewareDtos`、`KngSettingDtos`、`KngReadDtos`）——新增接口沿用该风格。
- 课件文件落本地磁盘（`kng.upload.dir`，默认 `D:/JACK/final_dream/.runtime-kng-files`，支持环境变量覆盖），不进 git。

## 3. 铁律（违反即返工）

1. **数据所有权**：本应用只拥有 `kng_*` 表。用户/部门/角色（`sys_*`）**禁止**建 Mapper 直读，
   一律注入 `OrgQueryService`（Dubbo，见 `config/DubboConsumerConfig`，`@DubboReference(version="1.0.0", check=false)`）。
   需要新数据能力时：先在 `StaffManagement-api` 加契约方法再消费——2026-10-02 已整改过一轮直读，不许回退。
2. **id 口径**：kng 表里 `user_id/dept_id` 存字符串；`sys_*` 主键是 BIGINT。跨界转换只在边界做
   （参考 `CoursewareServiceImpl.toLongIds`、`CatalogServiceImpl.parseUserId`）：解析失败/`"0"` 不发起 RPC，
   按原逻辑兜底显示"系统"。对外 JSON 中 id 一律 string。
3. **鉴权口径（现状）**：本模块不做登录拦截，复用 StaffManagement JWT（`kng.auth.jwt-secret`）仅在
   `common/CurrentUserService` 解出创建人/更新人。解析失败返回 "0" 的行为是既有约定，改动会影响
   `created_by="0"→"系统"` 的展示链路，需谨慎评估。网关统一鉴权落地后本条会重写。
4. **持久层**：MyBatis-Plus 单表 CRUD + 自定义 SQL 进 `src/main/resources/mapper/*.xml`；不跨表 JOIN，
   跨表数据 Service 批量查 + Map 组装（课件列表/学习记录已按此模式，参考 `KngCoursewareMapper.xml`、`KngStudyRecordMapper.xml`）；外部输入一律 `#{}`。
5. **响应契约**：`ApiResponse`、`BizException`、`GlobalExceptionHandler`、MyBatis-Plus 分页插件统一来自
   **`after-end-common`** 模块（`jack.digityHuman:after-end-common`，经 AutoConfiguration 自动装配），**禁止在本工程重建这些类**；
   `ApiResponse{code,message,data}`，code=0 成功、业务失败 HTTP 200 + 非零 code。
6. **幂等与审计**：kng 表带 `created_at/updated_at/created_by/created_by_name` 冗余列，写路径必须维护；
   雪花 id 用 `IdWorker.getIdStr()`，主键 VARCHAR(32)。

## 4. 本应用特有注意事项

- **配置缺口**：本工程历史上没配 `mybatis-plus.mapper-locations`，靠 MP 默认值 `classpath*:/mapper/**/*.xml` 生效。
  新增/移动 XML 后必须启动验证能加载；有余力时把该配置显式补上。
- 转码/字幕列表**已接入** `kng_media_job`（转码 `type=transcode`、字幕 `type=subtitle`），不是占位；
  旧路径 `transcodeList/subtitleList` 保持兼容，新转码/字幕页统一消费 `POST /media/jobs`（同一数据源）。
- 课件发布/学习记录统计的口径（完课标准、学分策略、防挂机）由 `KngSetting` 驱动，改动前读课程设置扩展文档族
  （`05~08-课程设置扩展-*.md`）。
- 发布模拟任务 `PublishSimulateJob` **已退役**；课件发布状态由 `CoursewareServiceImpl` 直接写入：
  媒体类（视频/音频/文档等）先置 `status=1`（发布中）并 afterCommit 投递转码，转码完成后置 `2`（已发布）；
  非媒体类（链接）直接 `2`；下架仍为 `3`。

## 5. 构建与验证

- 提交前 `mvnw.cmd test`（当前 11 个测试必须全绿；`OnlineClassroomApplicationTests.contextLoads` 依赖
  MySQL/Nacos 可达，网络不通导致的失败属环境问题，需在报告中注明）。
- 依赖管理铁律：`spring-cloud-dependencies` 排在 `spring-cloud-alibaba-dependencies` 之前 import；不 import dubbo-bom。
- 部署顺序：StaffManagement 先起（Dubbo 提供方），本应用 `check=false` 可后起。

## 6. 工作方式

1. 代码实施过程中出现技术或逻辑修改，要及时将技术方案文档同步修改。
2. 关键类、方法、代码行要加上标准的描述功能或步骤的 Java 注释（中文，说明"为什么"，不是复述代码）。
3. 出现信息缺失或认为实现方案不合理的情况一定要和用户沟通确认！！！
