package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.dto.req.FaceBaselineDtos;
import jack.digityhuman.examservice.entity.ExamFaceBaseline;
import jack.digityhuman.examservice.mapper.ExamFaceBaselineMapper;
import jack.digityhuman.examservice.service.FaceBaselineService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 人脸基准照实现（2026-11-12，DER-33）。
 *
 * <p>**能力边界必须记住（DEF-01）**：基准照由学员自己拍，首次就可能是别人。
 * 所以它只能用于检测「这次与上次不是同一人」，不能当成身份认证。
 * 真要解决首次替考，前置条件是官方基准照（HR 预录），那是另一个管理端功能。
 */
@Service
@RequiredArgsConstructor
public class FaceBaselineServiceImpl implements FaceBaselineService {

    private final ExamFaceBaselineMapper faceBaselineMapper;

    @Override
    public String currentImageUrl(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        ExamFaceBaseline row = findByUser(userId);
        return row == null ? null : row.getImageUrl();
    }

    @Override
    public String save(String userId, FaceBaselineDtos.SaveReq req) {
        if (req == null || !StringUtils.hasText(req.getImageUrl())) {
            throw new BizException("人脸照片不能为空");
        }
        String imageUrl = req.getImageUrl().trim();
        // 只接受本模块文件服务产出的 URL：避免把外站地址写进库，
        // 后续比对时前端取图会被防盗链/跨域挡住，排查起来很费劲
        if (!imageUrl.startsWith("/api/exam/files/")) {
            throw new BizException("人脸照片地址不合法，请重新拍摄上传");
        }

        LocalDateTime now = LocalDateTime.now();
        ExamFaceBaseline exist = findByUser(userId);
        if (exist == null) {
            ExamFaceBaseline row = new ExamFaceBaseline();
            row.setId(IdWorker.getIdStr());
            row.setUserId(userId);
            row.setImageUrl(imageUrl);
            // source=1 表示学员自建（2 留给管理员预录，见 07 附录 C4）
            row.setSource(1);
            row.setCreatedAt(now);
            faceBaselineMapper.insert(row);
        } else {
            // 重录即覆盖：一个学员只保留一张基准照（uk_user），
            // 这样比对时永远只有唯一基准，不用纠结"该跟哪张比"
            exist.setImageUrl(imageUrl);
            exist.setCreatedAt(now);
            faceBaselineMapper.updateById(exist);
        }
        return imageUrl;
    }

    /** 按学员取唯一基准照；uk_user 保证最多一条 */
    private ExamFaceBaseline findByUser(String userId) {
        return faceBaselineMapper.selectOne(
                new LambdaQueryWrapper<ExamFaceBaseline>().eq(ExamFaceBaseline::getUserId, userId));
    }
}
