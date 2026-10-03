package jack.digityhuman.onlineclassroom.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.onlineclassroom.entity.KngRef;
import jack.digityhuman.onlineclassroom.mapper.KngRefMapper;
import jack.digityhuman.onlineclassroom.service.CourseRefService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * kng_ref 引用维护实现（CS-10）。
 *
 * <p>采用"全删全插"策略与设计器保存的口径一致：任务清单整体替换时引用行整体重建，
 * 事务保证一致；单课件被多任务引用时每个任务各占一行（与原平台逐任务记录引用的语义对齐）。
 */
@Service
@RequiredArgsConstructor
public class CourseRefServiceImpl implements CourseRefService {

    /** 引用类型：1=课程引用课件（本模块）；2=培训/考试引用课程（未来模块预留，当前恒无数据） */
    private static final int REF_TYPE_COURSE = 1;

    private final KngRefMapper refMapper;

    /** 重建课程引用行：先删旧行再按任务序插入，ref_name 存课程名快照（课件删除提示取该值）。 */
    @Override
    @Transactional
    public void rebuildForCourse(String courseId, String courseTitle, List<String> coursewareIds) {
        refMapper.delete(Wrappers.<KngRef>lambdaQuery()
                .eq(KngRef::getRefId, courseId)
                .eq(KngRef::getRefType, REF_TYPE_COURSE));
        if (coursewareIds == null || coursewareIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<KngRef> rows = new ArrayList<>();
        for (String coursewareId : coursewareIds) {
            if (!StringUtils.hasText(coursewareId)) {
                continue;
            }
            KngRef ref = new KngRef();
            ref.setId(IdWorker.getIdStr());
            ref.setCoursewareId(coursewareId);
            ref.setRefType(REF_TYPE_COURSE);
            ref.setRefId(courseId);
            ref.setRefName(courseTitle);
            ref.setCreatedAt(now);
            rows.add(ref);
        }
        rows.forEach(refMapper::insert);
    }

    /** 删除课程的全部任务引用行（仅 ref_type=1，不触碰未来培训/考试侧的 ref_type=2 行）。 */
    @Override
    @Transactional
    public void deleteByCourse(String courseId) {
        refMapper.delete(Wrappers.<KngRef>lambdaQuery()
                .eq(KngRef::getRefId, courseId)
                .eq(KngRef::getRefType, REF_TYPE_COURSE));
    }

    /** 引用某课件的课程引用行（课件删除检查与课件侧"引用记录"的数据源），按创建时间倒序。 */
    @Override
    public List<KngRef> listCourseRefs(String coursewareId) {
        if (!StringUtils.hasText(coursewareId)) {
            return List.of();
        }
        return refMapper.selectList(Wrappers.<KngRef>lambdaQuery()
                .eq(KngRef::getCoursewareId, coursewareId)
                .eq(KngRef::getRefType, REF_TYPE_COURSE)
                .orderByDesc(KngRef::getCreatedAt));
    }
}
