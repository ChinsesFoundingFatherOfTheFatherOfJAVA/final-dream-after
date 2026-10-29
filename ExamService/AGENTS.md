# AGENTS.md · ExamService（测练中心-题库管理，8085）

## 1. 应用定位

测练中心·题库管理业务：分类树（10级 routing_path）→ 题库（共享/转移/保密）→
试题（六题型，组合题 parent_id 自关联）/考核点，附正确率统计、导入导出、查重、快速/AI 建题、三层权限。
组卷（试卷管理/固定编辑/随机编辑）与考试管理（列表/向导/考生/统计）均已实现；练习管理/题目反馈/字段设置仍为 PlaceholderPage。

横向规范见 `D:\JACK\plan\后端架构总纲.md`；本模块纵向文档见 `D:\JACK\plan\测练中心题库管理\`
（01 需求 / 02 技术方案 / 03 接口 / 04 建表 / 00b 抓包勘察——接口契约以 03 为准，冲突时以原平台截图为准并记入 06-实施记录）。

## 2. 技术栈与包结构

- Java 21 / Spring Boot 4.0.8 / MyBatis-Plus 3.5.17（含 jsqlparser）/ Nacos discovery / Dubbo 消费者。
- POI 5.4.1（poi-ooxml + poi-scratchpad）：试题 Excel/Word 导入导出。
- 包结构：`common/`（CurrentUserService）、`config/`（Dubbo 接线/静态资源映射）、`controller/`、
  `dto/req|resp`、`entity/`、`mapper/`、`parser/`（导入解析器）+ `service/` + `service/impl/`。
- 端口 8085；网关路由 `/api/exam/**`（order=-1，在 /api/** 兜底前）；静态媒体 `/api/exam/files/**`（白名单放行）。

## 3. 铁律（违反即返工）

1. **数据所有权**：本应用只拥有 `exam_*` 九表。用户/部门（sys_*）禁止直读，
   一律注入 `OrgQueryService`（Dubbo，`config/DubboConsumerConfig`，`check=false`）。
2. **持久层**：MyBatis-Plus 单表 CRUD + 自定义 SQL 进 `src/main/resources/mapper/*.xml`
   （`mybatis-plus.mapper-locations` 已显式配置）；不跨表 JOIN（跨表 Service 批量查 + Map 组装）；外部输入一律 `#{}`。
3. **响应契约**：`ApiResponse`/`BizException`/`GlobalExceptionHandler` 来自 after-end-common，
   禁止本工程重建。业务失败 HTTP 200 + 非零 code（错误码段 40001~50020，见 03 §10）。
4. **id 与审计**：id 统一 varchar(32) 雪花（`IdWorker.getIdStr()`）；审计字段
   created_by/created_by_name/created_at/updated_by/updated_at 写路径必须维护；
   逻辑删 deleted=0/1（exam_catalog_admin/lib_share/question_item 等关系表物理删）。
5. **routing_path 维护**：分类移动/重排必须整棵子树重算（02 §4.2），
   父节点 SELECT FOR UPDATE 防并发；层级>10 拒绝。**禁止只改父指向不改 path 的捷径**。
6. **组合题级联**：试题删除/换库统一走 QuesService 递归入口（父题级联子题），
   禁直接 deleteById；冗余计数（lib.total_ques_qty/point_qty）同事务维护。
7. **媒体文件**：题干/选项/解析的视频音频存本地磁盘 `exam.files.root`，
   FileController 统一上传（原文件名+UUID）；URL 前缀 `/api/exam/files/`。

## 4. 本应用特有注意事项

- **权限三层**当前由 `LibServiceImpl.permissionsOf` 等私有能力集方法实现：分类管理员（含继承链合并）/题库删除权（创建者+超管）/共享范围（部门展开经 Dubbo）；独立 `PermissionService` 是后续拆分目标态。前端能力集置灰 + 后端写操作再校验双保险。
- **保密题库**（secrecy=1）：仅创建者及超管可编辑——列表/详情接口都要回填 `permissions` 能力集。
- 查重：入库维护 `summary_norm`（去空白/HTML 归一）；`sim_hash` 列预留不实施。
- `exam_answer_stat` 数据由考试模块上线后回填；演示可插种子，但**不要**在业务代码里造数。
- AI 出题默认 `agent.exam-gen.provider=native`，走 AIGentService `exam-gen`。改回 dify 并重启才用旧工作流；同一轮失败不自动降级。

## 5. 构建与验证

- 提交前 `mvnw.cmd test`；`contextLoads` 依赖 MySQL/Nacos 可达，网络不通导致的失败属环境问题（注明即可）。
- 依赖管理铁律：不 import dubbo-bom（spring-core 5.x 与 Boot 4 冲突）；
  `spring-cloud-dependencies` 如需引入必须排在 `spring-cloud-alibaba-dependencies` 之前。
- 部署顺序：StaffManagement 先起（Dubbo 提供方），本应用 `check=false` 可后起。

## 6. 工作方式

1. 代码实施过程中出现技术或逻辑修改，要及时将技术方案文档同步修改。
2. 关键类、方法、代码行要加上标准的描述功能或步骤的 Java 注释（中文，说明"为什么"，不是复述代码）。
3. 出现信息缺失或认为实现方案不合理的情况一定要和用户沟通确认！！！
4. **样式冲突裁决**：文档与原平台截图冲突时以截图为准，偏差记入 `06-实施记录.md`（对齐在线课堂模块做法）。
