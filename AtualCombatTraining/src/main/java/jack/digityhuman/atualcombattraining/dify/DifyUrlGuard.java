package jack.digityhuman.atualcombattraining.dify;

import jack.digityhuman.atualcombattraining.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Dify 出站 URL 安全校验（服务端发起 HTTP 请求前的统一入口）
 * <p>
 * 规则：
 * <ol>
 *   <li>只允许 http / https 协议，拒绝 file、ftp 等；</li>
 *   <li>必须有主机名；</li>
 *   <li>一律拒绝 localhost、环回、任意本地、链路本地（169.254/16、fe80::/10）、
 *       组播、通配与保留网段——这些地址既不可能承载 Dify，也是 SSRF 的经典目标；</li>
 *   <li>私有网段（10/8、172.16/12、192.168/16、100.64/10、fc00::/7）默认同样拒绝，
 *       <b>仅当显式开启 {@code dify.allow-private-host=true}</b> 时放行——Dify 1.10.1 自建实例
 *       部署在内网 10.10.166.81，属知情的部署决策，因此做成"默认拒绝 + 显式开关"。</li>
 * </ol>
 * 调用地址只来自配置（dify.base-url），不接受任何请求参数拼装，从根上避免参数化 SSRF。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DifyUrlGuard {

    private final DifyProperties properties;

    /**
     * 校验并返回可用的 URI；不合规直接抛业务异常（调用方不再发请求）。
     *
     * @param url 待请求的完整地址
     */
    public URI validate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BizException("Dify 地址非法：" + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new BizException("Dify 地址协议不允许（仅支持 http/https）：" + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BizException("Dify 地址缺少主机名：" + url);
        }
        if (isLocalHostName(host)) {
            throw new BizException("Dify 地址不允许指向本机：" + host);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new BizException("Dify 地址无法解析：" + host);
        }
        if (addresses.length == 0) {
            throw new BizException("Dify 地址无法解析：" + host);
        }
        for (InetAddress address : addresses) {
            if (isAlwaysForbidden(address)) {
                throw new BizException("Dify 地址不允许指向环回/链路本地/保留地址：" + address.getHostAddress());
            }
            if (isPrivate(address) && !properties.isAllowPrivateHost()) {
                throw new BizException("Dify 地址为私有网段，未开启 dify.allow-private-host：" + address.getHostAddress());
            }
        }
        return uri;
    }

    /** 主机名字面量层面的本机判断（DNS 解析前的快速拦截） */
    private boolean isLocalHostName(String host) {
        String lower = host.toLowerCase();
        return "localhost".equals(lower)
                || lower.endsWith(".localhost")
                || lower.endsWith(".local")
                || lower.endsWith(".internal")
                || "0.0.0.0".equals(lower)
                || "[::]".equals(lower);
    }

    /** 环回 / 任意本地 / 链路本地 / 组播 / 保留等一律拒绝的地址 */
    private boolean isAlwaysForbidden(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 0.0.0.0/8 本网络、127/8 环回、169.254/16 链路本地、224/4 组播、240/4 保留
            return first == 0 || first == 127 || (first == 169 && second == 254)
                    || (first >= 224 && first <= 239) || first >= 240;
        }
        if (bytes.length == 16) {
            int first = bytes[0] & 0xFF;
            // fe80::/10 链路本地、ff00::/8 组播
            return (first == 0xFE && (bytes[1] & 0xC0) == 0x80) || first == 0xFF;
        }
        return true;
    }

    /** 私有/非公网可路由网段（需显式开关才放行） */
    private boolean isPrivate(InetAddress address) {
        if (address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            int third = bytes[2] & 0xFF;
            // 100.64.0.0/10 运营商级 NAT
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            // 192.0.0.0/24、192.0.2.0/24、198.18.0.0/15、198.51.100.0/24、203.0.113.0/24 保留/文档段
            if (first == 192 && second == 0 && third == 0) {
                return true;
            }
            if (first == 192 && second == 0 && third == 2) {
                return true;
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return true;
            }
            if (first == 198 && second == 51 && third == 100) {
                return true;
            }
            return first == 203 && second == 0 && third == 113;
        }
        if (bytes.length == 16) {
            // fc00::/7 唯一本地地址
            return (bytes[0] & 0xFE) == 0xFC;
        }
        return true;
    }
}