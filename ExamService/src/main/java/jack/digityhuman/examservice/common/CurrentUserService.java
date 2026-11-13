package jack.digityhuman.examservice.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * 从 StaffManagement 签发的 JWT 中读取当前用户。
 *
 * <p>本模块不做接口拦截；仅审计字段与权限判定（PermissionService）需要当前用户。
 * 解析失败时返回 {@code 0}（对齐工程既有约定，展示回落为"系统"）。
 *
 * <p><b>2026-10-12 修复</b>：调用方历史上一律传 {@code null}（{@code userId(null)}），
 * 旧实现遇到 null 直接返回 {@code "0"}，于是 operator 恒为 "0"：
 * 创建者字段全写成 "0"、{@code canManageSecrecy} 只对 "0" 成立的库放行——
 * 一旦把题库转移给真实用户，新创建者反而失去管理/删除权限。
 * 现改为 authorization 为空时自动回落到当前请求的 Authorization 头。
 */
@Service
public class CurrentUserService {

    private final SecretKey key;

    public CurrentUserService(@Value("${exam.auth.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 当前用户 id；authorization 为空时自动取当前请求头。解析不出来时回落到 "0"。 */
    public String userId(String authorization) {
        Claims claims = claims(authorization);
        if (claims == null) {
            return "0";
        }
        String userId = claims.get("userId", String.class);
        return userId == null || userId.isBlank() ? "0" : userId;
    }

    /**
     * 是否超级管理员。取自 StaffManagement 签发的 JWT {@code isSuperAdmin} claim，
     * 不再沿用"userId=1"的占位判定（内置超管 account=admin 的 userId 并不固定）。
     */
    public boolean isSuperAdmin(String authorization) {
        Claims claims = claims(authorization);
        if (claims == null) {
            return false;
        }
        Object flag = claims.get("isSuperAdmin");
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
    }

    /**
     * 当前用户姓名（JWT 的 {@code name} claim）。
     *
     * <p><b>为什么从 token 取而不是查 sys_user</b>：本应用不拥有用户表，
     * 直读 sys_* 违反模块数据所有权铁律；走 Dubbo `OrgQueryService` 又要为
     * 「批阅时记一个姓名」多一次跨服务调用，且该服务未上线时会拖慢批阅提交。
     * token 里的 name 是登录时的权威值，足够做留痕。
     *
     * <p>解析不出来返回空串，调用方自行回落成 userId。
     */
    public String realName(String authorization) {
        Claims claims = claims(authorization);
        if (claims == null) {
            return "";
        }
        String name = claims.get("name", String.class);
        return name == null ? "" : name.trim();
    }

    private Claims claims(String authorization) {
        String header = resolveAuthorization(authorization);
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(header.substring(7))
                    .getPayload();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String resolveAuthorization(String authorization) {
        if (authorization != null && !authorization.isBlank()) {
            return authorization;
        }
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return null;
            }
            return attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}