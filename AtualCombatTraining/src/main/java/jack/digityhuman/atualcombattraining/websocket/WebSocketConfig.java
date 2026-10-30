package jack.digityhuman.atualcombattraining.websocket;

import jack.digityhuman.atualcombattraining.websocket.drill.DrillWsHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点注册（FP-34）
 * <p>
 * 端点路径 /api/act/ws/chat?sessionId={id}，与既有 HTTP 前缀一致（网关同前缀路由）。
 * 协议是自定义 JSON 帧（非 STOMP），因此用原生 TextWebSocketHandler 注册。
 * 内网系统，运行期不做 origin 限制（鉴权以 sessionId 为凭证，与管理端现状一致）。
 * <p>
 * 学员对练端点 /api/act/ws/drill（契约 03 §9.1）同为自定义帧协议；网关不支持 WS upgrade
 * （偏差 D2），前端直连本服务。事件字典独立于 /ws/chat，互不影响。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final WsChatHandler wsChatHandler;
    private final DrillWsHandler drillWsHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(wsChatHandler, "/api/act/ws/chat")
                .setAllowedOriginPatterns("*");
        registry.addHandler(drillWsHandler, "/api/act/ws/drill")
                .setAllowedOriginPatterns("*");
    }
}