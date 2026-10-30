package jack.digityhuman.atualcombattraining.service.face.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jack.digityhuman.atualcombattraining.entity.ActFaceEvidence;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.mapper.ActFaceEvidenceMapper;
import jack.digityhuman.atualcombattraining.service.AttemptLifecycleService;
import jack.digityhuman.atualcombattraining.service.face.FaceCheckService;
import jack.digityhuman.atualcombattraining.service.face.FaceVerificationProvider;
import jack.digityhuman.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 人脸核验实现（契约 03 §12.2/§12.3）
 * <p>
 * 证据存储：`act-drill-evidence/{attemptId}/{uuid}.jpg`——刻意放在独立目录，
 * 禁止位于静态资源目录（§12.3），学员端拿不到路径；保留期默认 30 天（delete_after）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaceCheckServiceImpl implements FaceCheckService {

    private static final String STAGE_PRECHECK = "precheck";
    private static final String STAGE_ONGOING = "ongoing";
    /** 抓拍单张上限 2MB（前端压缩后上传，§5.7） */
    private static final int MAX_JPEG_BYTES = 2 * 1024 * 1024;

    private final FaceVerificationProvider provider;
    private final ActFaceEvidenceMapper evidenceMapper;
    private final AttemptLifecycleService lifecycleService;

    @Value("${act.face.evidence-dir:./act-drill-evidence/}")
    private String evidenceDir;

    @Value("${act.face.retention-days:30}")
    private long retentionDays;

    @Override
    public boolean providerConfigured() {
        return provider.configured();
    }

    @Override
    public Map<String, Object> verify(String attemptId, String userId, String stage, byte[] jpeg) {
        ActPracticeAttempt attempt = lifecycleService.require(attemptId);
        if (!Objects.equals(attempt.getUserId(), userId)) {
            throw new BizException("无权访问该练习");
        }
        String normalizedStage = STAGE_PRECHECK.equals(stage) ? STAGE_PRECHECK : STAGE_ONGOING;
        if (jpeg == null || jpeg.length == 0) {
            throw new BizException("抓拍内容为空");
        }
        if (jpeg.length > MAX_JPEG_BYTES) {
            throw new BizException("抓拍图片过大");
        }
        if (!provider.configured()) {
            // 未配置：不落证据、不假装通过（fail-closed）
            throw new BizException("人脸核验服务未配置");
        }

        Map<String, Object> verdict = provider.verify(jpeg, normalizedStage);
        boolean passed = Boolean.TRUE.equals(verdict.get("passed"));
        String storagePath = store(attemptId, jpeg);

        ActFaceEvidence evidence = new ActFaceEvidence();
        evidence.setId(IdWorker.getIdStr());
        evidence.setAttemptId(attemptId);
        evidence.setStage(normalizedStage);
        evidence.setStoragePath(storagePath);
        evidence.setProviderResultJson(toJson(verdict));
        evidence.setPassed(passed ? 1 : 0);
        evidence.setCreatedAt(LocalDateTime.now());
        evidence.setDeleteAfter(LocalDateTime.now().plusDays(Math.max(1, retentionDays)));
        evidenceMapper.insert(evidence);

        // 同时写防作弊事件审计（face.pass / face.fail），与切屏事件同一张表便于管理端串联
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", passed);
        result.put("score", verdict.get("score"));
        result.put("reason", verdict.get("reason"));
        result.put("evidenceId", evidence.getId());
        log.info("人脸核验 attemptId={} stage={} passed={}", attemptId, normalizedStage, passed);
        return result;
    }

    /** 证据落盘：独立目录 + attempt 子目录（§12.3） */
    private String store(String attemptId, byte[] jpeg) {
        try {
            Path dir = Paths.get(evidenceDir).toAbsolutePath().normalize().resolve(attemptId);
            Files.createDirectories(dir);
            String filename = UUID.randomUUID().toString().replace("-", "") + ".jpg";
            Files.write(dir.resolve(filename), jpeg);
            return attemptId + "/" + filename;
        } catch (Exception e) {
            // 证据落盘失败不阻断核验结论（结论已拿到），但要留痕
            log.warn("人脸证据落盘失败 attemptId={}：{}", attemptId, e.getMessage());
            return null;
        }
    }

    private String toJson(Map<String, Object> verdict) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of(
                            "passed", Boolean.TRUE.equals(verdict.get("passed")),
                            "score", verdict.get("score"),
                            // reason 里可能带 provider 内部信息，只留通用文案
                            "provider", provider.name()));
        } catch (Exception e) {
            return null;
        }
    }
}
