package jack.digityhuman.atualcombattraining.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * 学员端当前用户解析（复用 StaffManagement 签发的 HS256 JWT，密钥跨工程共享——
 * 与 OnlineClassroom 的 CurrentUserService 同一套约定；不是第三套解析方案，
 * 是同一 token/claim 约定在本工程的落点）。
 * <p>
 * 密钥未配置或长度不足 256 位时按"fail-closed"处理：不阻断应用启动（保持 R-3 现状——
 * 应用可运行），但所有 token 视为无效，学员接口统一得到"未获取到登录用户"的预检拒绝；
 * 与 AIGentService 内部接口"未配置 token 拒绝服务"是同一安全口径。
 * <p>
 * 解析失败返回 "0"：与既有"解析失败回落系统用户"的口径一致；学员端接口据此得到
 * "不在学员名单中"的预检拒绝，而不是 500。
 */
@Service
public class CurrentUserService {

    private final String secret;
    private volatile SecretKey cachedKey;
    private volatile boolean keyResolved;

    public CurrentUserService(@Value("${act.auth.jwt-secret:}") String secret) {
        this.secret = secret == null ? "" : secret;
    }

    /** 从 Authorization 头解析 userId；解析失败返回 "0"（既有约定，勿改） */
    public String userId(String authorization) {
        String userId = claim(authorization, "userId");
        return userId == null ? "0" : userId;
    }

    /**
     * 从 Authorization 头解析用户名（JWT name claim）。
     * <p>
     * 报告页要显示"学员"姓名：StaffManagement 签发 token 时已写入 name claim
     * （JwtServiceImpl 的 CLAIM_NAME），因此无需读 sys_user 表（ACT AGENTS 铁律 1）。
     * 解析失败返回 null（不抛异常），调用方按"未注册"显示。
     */
    public String userName(String authorization) {
        return claim(authorization, "name");
    }

    /** 取指定 claim；token 缺失/密钥无效/claim 空 都返回 null（fail-closed） */
    private String claim(String authorization, String name) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        SecretKey key = keyOrNull();
        if (key == null) {
            // 密钥未配置/过短：fail-closed
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(authorization.substring(7))
                    .getPayload();
            String value = claims.get(name, String.class);
            return value == null || value.isBlank() ? null : value;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 惰性构建密钥：HS256 要求 ≥256 位；不满足返回 null（调用方按未配置处理） */
    private SecretKey keyOrNull() {
        if (!keyResolved) {
            synchronized (this) {
                if (!keyResolved) {
                    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
                    cachedKey = bytes.length >= 32 ? Keys.hmacShaKeyFor(bytes) : null;
                    keyResolved = true;
                }
            }
        }
        return cachedKey;
    }
}
