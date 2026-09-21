package jack.digityhuman.atualcombattraining.websocket;

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
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final WsChatHandler wsChatHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(wsChatHandler, "/api/act/ws/chat")
                .setAllowedOriginPatterns("*");
    }
}