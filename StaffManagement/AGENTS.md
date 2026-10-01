# AGENTS.md · StaffManagement（组织架构与权限中心，8083 / Dubbo 20881）

## 1. 应用定位

全站的组织架构与权限地基：登录签发 JWT、用户/部门/角色/权限管理（`sys_*` 表）、
Oracle HR 视图 → MySQL 的员工同步任务、面向其他应用的 Dubbo 查询服务。
**`sys_*` 表的唯一所有方。其他应用读这些数据只能走 Dubbo 契约，本应用不得以任何理由给消费方开直连库的口子。**

横向规范见 `D:\JACK\plan\后端架构总纲.md`；本模块纵向文档见 `D:\JACK\plan\组织架构与角色权限模块\`、`D:\JACK\plan\员工信息管理模块\`。

## 2. 工程结构（聚合 POM）

```
StaffManagement/
├── pom.xml               # 聚合 + dependencyManagement（Boot 4.0.8 父、SC 2025.1.3 在 SCA 之前、Dubbo 3.3.6 显式声明不 import dubbo-bom）
├── StaffManagement-api/  # Dubbo 契约模块：纯 POJO + 接口，零 Spring/Dubbo 依赖，DTO 必须 Serializable
│   └── jack.digityhuman.staffmanagement.api.{StaffQueryService, OrgQueryService, dto, query}
└── StaffManagement-provider/  # 可运行应用（Web + Dubbo 提供方）
```

- api 是对外契约，改接口/DTO 是**破坏性变更**：必须同步更新 provider 实现与全部消费方（SpeechTraining、OnlineClassroom 的 `config/DubboConsumerConfig`），并在文档中记录。
- provider 包结构正在从遗留按层（`dal/ web/ entity/ mapper/ service/`）向按领域（`auth/ department/ position/ role/ sync/ user/`）收敛：**新代码一律进领域包**，改到遗留类时顺手迁移，不做一次性大搬家。

## 3. 铁律（违反即返工）

1. **双数据源**：MySQL 主数据源（`sm.mysql.*`，MyBatis-Plus，可写）+ Oracle 视图只读次数据源（`sm.oracle.*`，JdbcTemplate，Oracle 11g 方言不支持 offset/fetch，只能 rownum）。Oracle 侧不进事务、不做写操作；这是《后端持久层规范化整改计划》的书面豁免例外，代码注释与文档都要保留说明。
2. **Dubbo 提供**：`@DubboService(version="1.0.0", timeout=30_000)`，实现类放 `service/impl`（在 `dubbo.scan.base-packages` 内）；`register-mode=interface`（应用级注册会把 HTTP 流量导到 20881）；qos-port 22223。
3. **同步任务**：`sm.sync.enabled` 默认 false，首轮全量必须人工触发；upsert 更新分支永不碰 `password/status/is_super_admin/角色`（这些归本地管理）；Oracle 已离职账号走 `disableByAccounts` 单独置停。
4. **密码与口令**：BCrypt 入库；任何接口不得回传 password；`sm.auth.default-password`、`sm.auth.jwt-secret` 等敏感项不得新增明文默认值（新配置一律 `${ENV_VAR:}` 形式）。
5. **响应契约**：对外 HTTP 统一 `ApiResponse{code,message,data}`、code=0 成功、业务失败 HTTP 200 + 非零 code、id 一律 string、分页 `{list,total,page,size}`。
   `ApiResponse` / `GlobalExceptionHandler` / MP 分页插件统一来自 **`after-end-common`** 模块（`jack.digityHuman:after-end-common`，经 AutoConfiguration 自动装配），
   **禁止在本工程重建这些类**；本应用的 `common/BizException` 是 common 版的子类（补 ErrorCode 构造器），`PageVo{list,total,page,size}` 是本应用 HTTP 分页契约，勿与 common 的 `PageResult{records,total}`（ST/ACT 历史形状）混用。
   注意：2026-10-02 起参数错误/未知异常统一改为 HTTP 200 + code（原 400/500 状态码分支已删除），前端按 code 分支处理。
6. 持久层六条铁律见总纲 §5：MP 单表 CRUD + 自定义 SQL 进 XML + 不跨表 JOIN（跨表 Service 组装）+ `#{}` 绑定 + **不读他域表**。

## 4. 对外契约清单（改动需同步消费方）

| 契约 | 数据源 | 主要消费方 |
|---|---|---|
| `StaffQueryService`（Oracle 员工模糊查询/部门子树） | JACKSHR.V_BD_JACKMES_INFO | 待接入 |
| `OrgQueryService`（sys 用户/部门/角色/权限只读 + 鉴权快照） | sys_* 表 | SpeechTraining、OnlineClassroom |

## 5. 构建与验证

- 本地：`mvnw.cmd install -DskipTests`（api 会装进本地 .m2，消费方编译依赖它）。
- 提交前：`mvnw.cmd test`；注意 `StaffManagementApplicationTests.contextLoads` 依赖真实 MySQL/Oracle/Redis 可达，网络不通时该测试失败属环境问题，单测（sync/user）必须全绿。
- 端口冲突排查：本机 dubbo-spike 占 20880/22222，本应用固定 20881/22223。

## 6. 工作方式

1. 代码实施过程中出现技术或逻辑修改，要及时将技术方案文档同步修改。
2. 关键类、方法、代码行要加上标准的描述功能或步骤的 Java 注释（中文，说明"为什么"，不是复述代码）。
3. 出现信息缺失或认为实现方案不合理的情况一定要和用户沟通确认！！！
