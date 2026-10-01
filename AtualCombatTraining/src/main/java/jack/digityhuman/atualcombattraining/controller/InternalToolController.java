package jack.digityhuman.atualcombattraining.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.dto.req.ArtifactPatchesReq;
import jack.digityhuman.atualcombattraining.dto.req.CreateTopicReq;
import jack.digityhuman.atualcombattraining.dto.req.GenerateCasesReq;
import jack.digityhuman.atualcombattraining.dto.req.GenerateThemeReq;
import jack.digityhuman.atualcombattraining.dto.resp.AcceptedResp;
import jack.digityhuman.atualcombattraining.dto.resp.CreateTopicResp;
import jack.digityhuman.atualcombattraining.dto.resp.UpdateArtifactResp;
import jack.digityhuman.atualcombattraining.service.AssistantToolService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Dify 工具端点（/api/act/internal/tools/**，03 文档 §2.5，FP-35~37/39）
 * <p>
 * 仅供 Dify 编排智能体回调：Dify 以 API 工具方式调用，业务全部在 Java 完成，
 * 每个端点执行后主动向会话 WS 推送对应 bizType 事件（事件推送不依赖本响应）。
 * 响应 data 会被 Dify 当作工具观察结果喂回模型，因此只回必要字段。
 * <p>
 * 访问控制：仅放行内网来源（环回 / 站点本地地址）。注意这是纵深防御的一道网——
 * 真正的边界是"部署在内网 + 路由前缀不对公网开放"；若将来经反向代理转发，
 * remoteAddr 为代理地址，此时该判断退化为对代理地址的判断。
 */
@Slf4j
@RestController
@RequestMapping("/api/act/internal/tools")
@RequiredArgsConstructor
public class InternalToolController {

    private final AssistantToolService toolService;

    /** 2.5.1 create_topic：建/改草稿主题 + 回填会话 + 推 topic-changed（FP-39） */
    @PostMapping("/create_topic")
    public ApiResponse<CreateTopicResp> createTopic(@RequestParam(required = false) String traceId,
                                                    @RequestParam(required = false) String sessionId,
                                                    @RequestBody CreateTopicReq req,
                                                    HttpServletRequest request) {
        requireInternal(request);
        return ApiResponse.success(toolService.createTopic(traceId, sessionId, req));
    }

    /** 2.5.2 update_artifact：JSON-Patch（op=full_replace）更新工作区 + 推 artifact-updated */
    @PostMapping("/update_artifact")
    public ApiResponse<UpdateArtifactResp> updateArtifact(@RequestParam(required = false) String traceId,
                                                          @RequestParam(required = false) String sessionId,
                                                          @RequestBody ArtifactPatchesReq req,
                                                          HttpServletRequest request) {
        requireInternal(request);
        return ApiResponse.success(toolService.updateArtifact(traceId, sessionId, req));
    }

    /** 2.5.3 generate_theme：受理即返回，异步生成并推 artifact-generated（FP-36） */
    @PostMapping("/generate_theme")
    public ApiResponse<AcceptedResp> generateTheme(@RequestParam(required = false) String traceId,
                                                   @RequestParam(required = false) String sessionId,
                                                   @RequestBody GenerateThemeReq req,
                                                   HttpServletRequest request) {
        requireInternal(request);
        return ApiResponse.success(toolService.generateTheme(traceId, sessionId, req));
    }

    /** 2.5.4 generate_cases：受理即返回，逐场景生成并推 case-generated（FP-37） */
    @PostMapping("/generate_cases")
    public ApiResponse<AcceptedResp> generateCases(@RequestParam(required = false) String traceId,
                                                   @RequestParam(required = false) String sessionId,
                                                   @RequestBody GenerateCasesReq req,
                                                   HttpServletRequest request) {
        requireInternal(request);
        return ApiResponse.success(toolService.generateCases(traceId, sessionId, req));
    }

    /** 内网来源校验：仅允许环回与站点本地（私有）地址 */
    private void requireInternal(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        try {
            InetAddress address = InetAddress.getByName(remoteAddr);
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()) {
                return;
            }
            // 链路本地（169.254/16）在容器/主机网络下也属内部
            if (address.isLinkLocalAddress()) {
                return;
            }
        } catch (UnknownHostException e) {
            log.warn("工具端点无法解析来源地址：{}", remoteAddr);
        }
        log.warn("工具端点拒绝非内网来源：{}", remoteAddr);
        throw new BizException("仅允许内网访问");
    }
}