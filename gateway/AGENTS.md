# AGENTS.md · gateway（统一网关，8080）

## 1. 应用定位

全站唯一 HTTP 入口：按服务名路由到 Nacos 里的各业务应用，并在边缘完成 JWT 验签。
2026-10-02 起 `gateway/auth/JwtAuthFilter` 已上线：`/api/*` 请求先验 StaffManagement 签发的 HS256 token，
非法/过期一律 401 + code 40101；白名单（登录 / Dify 内网工具回调 / kng 文件流）由 `gateway.auth.whitelist` 配置。
剩余一步（二期）：把解析出的用户身份以请求头下传，业务服务改为只信任请求头，废弃本地 secret 与 dev 后门。

横向规范见 `D:\JACK\plan\后端架构总纲.md`。

## 2. 技术栈与结构

- Java 21 / Spring Boot 4.0.8（已与业务工程统一）/ `spring-cloud-starter-gateway-server-webmvc`（**Servlet 版**，不是 WebFlux 响应式）+ Nacos discovery + jjwt 0.12.6（边缘验签）。
- 结构：`GatewayApplication`、`CorsConfig`、`auth/`（JwtAuthFilter + GatewayAuthProperties + GatewayAuthConfig）、
  `application.properties`（路由 + 鉴权配置在 properties 维护，nacos-config 已引依赖未启用）。

## 3. 铁律（违反即返工）

1. **路由顺序**：业务前缀路由（`/api/act/**`、`/api/sm/**`、`/api/kng/**`、`/api/kng-files/**`）必须用更小的
   `order` 声明在 `/api/**` 通配兜底路由（speech-training，order=0）**之前**，新增前缀路由照抄现有注释模式，漏了会被兜底路由吞掉。
2. **WS 限制**：WebMVC 版网关不支持 WebSocket upgrade（偏差记录 D2）——
   AtualCombatTraining 的 `/ws/chat` 由前端直连服务，**不要**尝试把 WS 路径收进网关，除非整体切换到 WebFlux 版网关（重大架构变更，需用户决策）。
3. **CORS 收敛**：`CorsConfig` 当前是 `addAllowedOriginPattern("*")` + `allowCredentials(true)` 的危险组合，
   属于已知待整改项；改 CORS 时按白名单来源收敛，禁止新增"更开放"的配置。
4. **网关零业务**：不写业务逻辑、不连数据库、不引入业务依赖；它只做路由与（未来的）横切过滤。
5. **服务名即注册名**：`lb://` 后面的名字必须与目标应用 `spring.application.name` 完全一致
   （区分大小写：`lb://AtualCombatTraining`、`lb://StaffManagement`）。

## 4. 二期身份下传（剩余待办）

- 现状：JwtAuthFilter 只做"拒绝非法请求"，业务服务仍自行解析 JWT 取身份（秘钥共享）。
- 下一步：验签通过后向下游注入用户请求头（如 `X-User-Id`、`X-Account`、`X-Permissions`），业务服务逐步改为只信任请求头，
  废弃各服务本地 JWT secret 与开发后门（SpeechTraining `speech.auth.dev-fallback-enabled`）。
- 落地前先与用户确认方案，输出到 plan 下独立文档族再动工。

## 5. 构建与验证

- 提交前 `mvnw.cmd test`；本地联调需 Nacos（169.254.180.49:8848）可达且至少目标应用已注册。
- 验证路由：`curl -i http://localhost:8080/api/{sm,act,kng}/**` 走到正确服务、404 兜底路由不误吞。

## 6. 工作方式

1. 代码实施过程中出现技术或逻辑修改，要及时将技术方案文档同步修改。
2. 关键类、方法、代码行要加上标准的描述功能或步骤的 Java 注释（中文，说明"为什么"，不是复述代码）。
3. 出现信息缺失或认为实现方案不合理的情况一定要和用户沟通确认！！！
