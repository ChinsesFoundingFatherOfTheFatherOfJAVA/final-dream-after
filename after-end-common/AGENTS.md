# AGENTS.md · after-end-common（全站公共基础模块）

## 1. 模块定位

跨应用公共基础：`ApiResponse{code,message,data}` / `BizException` / `GlobalExceptionHandler` /
`PageResult{records,total}` / MyBatis-Plus 分页插件（AutoConfiguration 装配）。
被 SpeechTraining / OnlineClassroom / AtualCombatTraining / ExamService 全部消费——
**改一行影响四个应用**，动本模块前先确认全站兼容。

横向规范见 `D:\JACK\plan\后端架构总纲.md`。

## 2. 技术栈

Java 21 / Spring Boot 4.0.8。无业务依赖（不依赖任何应用的表与外部服务）；
AutoConfiguration 入口在 `src/main/resources/META-INF/spring/` 下注册。

## 3. 铁律（违反即返工）

1. **只放跨应用通用件**：业务 DTO/工具/常量放各自应用，不放公共模块（防止公共层膨胀成大杂烩）。
2. **响应契约不可变方向**：`code=0` 成功、业务失败 HTTP 200 + 非零 code——全站前端 request 拦截器
   按此约定解包（`src/api/request.ts`），改语义需四应用+前端同步，禁止单方面变更。
3. **异常体系单一**：业务异常一律 `BizException(message)`（中文文案直达用户）；
   `GlobalExceptionHandler` 兜底 500。不要新增并行异常类型。
4. 分页插件经 AutoConfiguration 装配（`MybatisPlusAutoConfiguration`），
   消费方不需要也不应该自己再配 `PaginationInnerInterceptor`。

## 4. 变更流程

改本模块后必须：`mvnw.cmd install`（装进本地仓）→ 在至少一个消费应用编译+跑测试确认无破坏 →
在变更说明中列出"需要全站同步重启"的影响面。禁止只改不装（消费方会拿到旧 jar）。
