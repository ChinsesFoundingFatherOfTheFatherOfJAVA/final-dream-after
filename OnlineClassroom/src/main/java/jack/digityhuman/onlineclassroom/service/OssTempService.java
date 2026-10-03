package jack.digityhuman.onlineclassroom.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import jack.digityhuman.common.api.BizException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.Date;

/**
 * OSS 临时对象服务（MT-03）：字幕管线的 wav 临时上传 / 预签名 / 删除。
 *
 * <p>约定：对象键 {@code kng-asr-tmp/{coursewareId}.wav}，预签名有效期 15 分钟，管线 finally 兜底删除；
 * 桶侧建议再配 1 天生命周期规则作双保险（02 §6 难点对策）。未配置 OSS（四项配置任一为空）时
 * 字幕功能不可用、转码不受影响（02 §7 降级策略），调用即抛业务异常。
 */
@Slf4j
@Service
public class OssTempService {

    /** 预签名 URL 有效期（分钟）：百炼拉取一次音频即够，取短降低泄漏面 */
    private static final long PRESIGN_MINUTES = 15;
    private static final String OBJECT_PREFIX = "kng-asr-tmp/";

    private final String endpoint;
    private final String bucket;
    private final String accessKeyId;
    private final String accessKeySecret;
    private volatile OSS client;

    public OssTempService(
            @Value("${media.oss.endpoint:}") String endpoint,
            @Value("${media.oss.bucket:}") String bucket,
            @Value("${media.oss.access-key-id:}") String accessKeyId,
            @Value("${media.oss.access-key-secret:}") String accessKeySecret) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
    }

    /** OSS 是否已配置（字幕功能可用性开关）。 */
    public boolean configured() {
        return StringUtils.hasText(endpoint) && StringUtils.hasText(bucket)
                && StringUtils.hasText(accessKeyId) && StringUtils.hasText(accessKeySecret);
    }

    /**
     * 上传 wav 临时对象并返回 15 分钟预签名 URL（百炼 fileUrls 输入）。
     *
     * @throws jack.digityhuman.common.api.BizException 未配置 OSS 或上传失败
     */
    public String uploadAndPresign(Path wav, String coursewareId) {
        requireConfigured();
        try {
            String objectKey = objectKey(coursewareId);
            client().putObject(bucket, objectKey, wav.toFile());
            Date expiration = new Date(System.currentTimeMillis() + PRESIGN_MINUTES * 60_000);
            return client().generatePresignedUrl(bucket, objectKey, expiration).toString();
        } catch (RuntimeException exception) {
            log.warn("wav 临时对象上传失败 coursewareId={}", coursewareId, exception);
            throw new BizException("字幕音频上传 OSS 失败，请检查 media.oss 配置");
        }
    }

    /** 删除临时对象：管线 finally 兜底调用；删除失败仅告警（还有桶生命周期兜底）。 */
    public void delete(String coursewareId) {
        if (!configured()) {
            return;
        }
        try {
            client().deleteObject(bucket, objectKey(coursewareId));
        } catch (RuntimeException exception) {
            log.warn("OSS 临时对象删除失败（等待桶生命周期兜底）coursewareId={}", coursewareId, exception);
        }
    }

    private String objectKey(String coursewareId) {
        return OBJECT_PREFIX + coursewareId + ".wav";
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new BizException("字幕功能未配置 OSS（media.oss.*），无法生成字幕");
        }
    }

    /** 懒初始化 OSS 客户端：未配置 OSS 的部署环境绝不创建连接。 */
    private OSS client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
                }
            }
        }
        return client;
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) {
            client.shutdown();
        }
    }
}
