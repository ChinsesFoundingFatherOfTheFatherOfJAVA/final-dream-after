package jack.digityhuman.onlineclassroom.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.dto.KngCourseDtos;
import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;
import jack.digityhuman.onlineclassroom.entity.KngCatalog;
import jack.digityhuman.onlineclassroom.entity.KngCourse;
import jack.digityhuman.onlineclassroom.entity.KngCourseChapter;
import jack.digityhuman.onlineclassroom.entity.KngCourseTask;
import jack.digityhuman.onlineclassroom.entity.KngRef;
import jack.digityhuman.onlineclassroom.mapper.KngCatalogMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCourseChapterMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCourseMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCourseTaskMapper;
import jack.digityhuman.onlineclassroom.mapper.KngRefMapper;
import jack.digityhuman.onlineclassroom.service.CourseOpService;
import jack.digityhuman.onlineclassroom.service.CourseRefService;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 课程创建/表单保存与操作事务（CS-07/08/09）。
 *
 * <p>状态机与审计口径对齐课件操作实现（CoursewareOpServiceImpl）：
 * 下架仅 2→3、上架仅 3→2、hidden 为独立双标记；复制为三表深拷贝 + ref 重建。
 * 审计字段说明：本工程未配置 MyBatis-Plus 自动填充（无 MetaObjectHandler），
 * updated_at 沿用课件模块的显式赋值方式（kng_course 无 updated_by 列，更新人不落库）。
 */
@Service
@RequiredArgsConstructor
public class CourseOpServiceImpl implements CourseOpService {

    /** 引用类型：2=培训/考试引用课程（未来模块写入，当前恒无数据；删除/引用记录按此口径预留） */
    private static final int REF_TYPE_BIZ = 2;

    private final KngCourseMapper courseMapper;
    private final KngCourseChapterMapper chapterMapper;
    private final KngCourseTaskMapper taskMapper;
    private final KngCatalogMapper catalogMapper;
    private final KngRefMapper refMapper;
    private final CourseRefService courseRefService;
    /** 操作人姓名经 Dubbo 取自 StaffManagement，不直读 sys_user 表 */
    private final OrgQueryService orgQueryService;

    // ==================== 创建与表单保存（03 §2.6） ====================

    /**
     * 创建课程（CS-08 点"下一步"时）：插入 status=0 未发布记录（列表可见、可持续编辑），
     * 解锁模式/单元/结构类型按请求或默认值落库，study_score 保持 NULL（=跟随任务学分和）。
     */
    @Override
    @Transactional
    public KngCoursewareDtos.IdStatusResponse create(KngCourseDtos.CreateRequest request, String userId) {
        if (request == null || !StringUtils.hasText(request.getCatalogId())) {
            throw new BizException("请选择课程分类");
        }
        KngCatalog catalog = catalogMapper.selectById(request.getCatalogId());
        if (catalog == null) {
            throw new BizException("课程分类不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        KngCourse course = new KngCourse();
        course.setId(IdWorker.getIdStr());
        // 创建表单预填"未命名课程"，但兜底再防一次空标题（DDL title NOT NULL）
        course.setTitle(StringUtils.hasText(request.getTitle()) ? request.getTitle() : "未命名课程");
        course.setCatalogId(catalog.getId());
        // 分类名冗余取法与课件 create 一致（搜索 keyword 命中 catalog_name）
        course.setCatalogName(catalog.getName());
        course.setStatus(0);
        course.setOnSale(1);
        course.setHidden(0);
        course.setStructureType(request.getStructureType() == null ? 0 : request.getStructureType());
        course.setUnlockMode(1);
        course.setUnlockUnit(1);
        course.setStandardDurationFollow(0);
        course.setStandardMinutes(0);
        course.setStandardSeconds(0);
        // study_score 保持 NULL：未单独设置学分，详情展示=任务学分和
        course.setStudyScore(null);
        course.setFinishStandardPercent(
                request.getFinishStandardPercent() == null ? 100 : request.getFinishStandardPercent());
        course.setViewPermission(request.getViewPermission() == null ? 1 : request.getViewPermission());
        course.setSpeedEnabled(1);
        course.setSpeedHoursCalc(1);
        course.setContributorId(StringUtils.hasText(userId) ? userId : null);
        course.setPlatformNo(nextCoursePlatformNo());
        course.setCreatedBy(userId);
        course.setCreatedByName(userName(userId));
        course.setCreatedAt(now);
        course.setUpdatedAt(now);
        courseMapper.insert(course);
        return new KngCoursewareDtos.IdStatusResponse(course.getId(), course.getStatus());
    }

    /**
     * 保存第一步基本信息表单（CS-07）：更新表单字段全集；attachments 仅契约接收，
     * kng_course 无附件存储列（04 DDL），不持久化、详情恒返回空数组。
     */
    @Override
    @Transactional
    public void saveBasic(String id, KngCourseDtos.BasicSaveRequest request) {
        KngCourse course = requireCourse(id);
        if (request == null) {
            throw new BizException("请填写课程信息");
        }
        if (!StringUtils.hasText(request.getTitle())) {
            throw new BizException("请输入课程名称");
        }
        if (!StringUtils.hasText(request.getCatalogId())) {
            throw new BizException("请选择课程分类");
        }
        KngCatalog catalog = catalogMapper.selectById(request.getCatalogId());
        if (catalog == null) {
            throw new BizException("课程分类不存在");
        }
        course.setTitle(request.getTitle());
        course.setCatalogId(catalog.getId());
        course.setCatalogName(catalog.getName());
        // 标准时长跟随（Boolean→tinyint）；跟随时手填值仍保留存储（展示优先任务聚合）
        course.setStandardDurationFollow(Boolean.TRUE.equals(request.getStandardDurationFollow()) ? 1 : 0);
        course.setStandardMinutes(request.getStandardMinutes() == null ? 0 : request.getStandardMinutes());
        course.setStandardSeconds(request.getStandardSeconds() == null ? 0 : request.getStandardSeconds());
        // studyScore null=跟随（展示=任务学分和）
        course.setStudyScore(request.getStudyScore());
        course.setFinishStandardPercent(request.getFinishStandardPercent() == null
                ? course.getFinishStandardPercent() : request.getFinishStandardPercent());
        course.setDescription(request.getDescription());
        // 表单字段名 coverStoragePath，落库到 cover_url（与课件封面上传链路同口径）
        course.setCoverUrl(request.getCoverStoragePath());
        course.setCode(request.getCode());
        course.setVersionStr(request.getVersionStr());
        course.setViewPermission(request.getViewPermission() == null
                ? course.getViewPermission() : request.getViewPermission());
        course.setSpeedEnabled(Boolean.TRUE.equals(request.getSpeedEnabled()) ? 1 : 0);
        course.setSpeedHoursCalc(Boolean.TRUE.equals(request.getSpeedHoursCalc()) ? 1 : 0);
        course.setContributorId(request.getContributorId());
        course.setUpdatedAt(LocalDateTime.now());
        courseMapper.updateById(course);
    }

    // ==================== 课程操作（03 §2.9 CS-09） ====================

    /** 隐藏/取消隐藏：hidden 与 status 独立双标记，任意状态可切换（与课件隐藏同口径）。 */
    @Override
    @Transactional
    public void hide(String id, Boolean hidden) {
        KngCourse course = requireCourse(id);
        course.setHidden(Boolean.TRUE.equals(hidden) ? 1 : 0);
        course.setUpdatedAt(LocalDateTime.now());
        courseMapper.updateById(course);
    }

    /** 下架单课程：仅已发布(2)可下架，状态置 3 并清上架位（口径同 CoursewareOpServiceImpl.takeDown）。 */
    @Override
    @Transactional
    public void takeDown(String id) {
        KngCourse course = requireCourse(id);
        if (!Objects.equals(course.getStatus(), 2)) {
            throw new BizException("仅已发布课程可下架");
        }
        course.setStatus(3);
        course.setOnSale(0);
        course.setUpdatedAt(LocalDateTime.now());
        courseMapper.updateById(course);
    }

    /** 批量下架：先整体校验状态再逐条更新，存在非已发布课程时整体失败（同课件批量口径）。 */
    @Override
    @Transactional
    public KngCoursewareDtos.CountResponse batchTakeDown(KngCoursewareDtos.IdsRequest request) {
        List<String> ids = requireIds(request == null ? null : request.ids(), "请选择课程");
        for (String id : ids) {
            if (!Objects.equals(requireCourse(id).getStatus(), 2)) {
                throw new BizException("存在非已发布课程，无法批量下架");
            }
        }
        for (String id : ids) {
            KngCourse course = requireCourse(id);
            course.setStatus(3);
            course.setOnSale(0);
            course.setUpdatedAt(LocalDateTime.now());
            courseMapper.updateById(course);
        }
        return new KngCoursewareDtos.CountResponse(ids.size());
    }

    /** 批量上架：仅已下架(3)课程可重新上架，存在非法状态时整体失败（同课件批量口径）。 */
    @Override
    @Transactional
    public KngCoursewareDtos.CountResponse batchPutOn(KngCoursewareDtos.IdsRequest request) {
        List<String> ids = requireIds(request == null ? null : request.ids(), "请选择课程");
        for (String id : ids) {
            if (!Objects.equals(requireCourse(id).getStatus(), 3)) {
                throw new BizException("存在非已下架课程，无法批量上架");
            }
        }
        for (String id : ids) {
            KngCourse course = requireCourse(id);
            course.setStatus(2);
            course.setOnSale(1);
            course.setUpdatedAt(LocalDateTime.now());
            courseMapper.updateById(course);
        }
        return new KngCoursewareDtos.CountResponse(ids.size());
    }

    /** 批量移动到目标分类：更新 catalog_id 并刷新 catalog_name 冗余（口径同课件 batchMove）。 */
    @Override
    @Transactional
    public KngCoursewareDtos.CountResponse move(KngCoursewareDtos.MoveRequest request) {
        List<String> ids = requireIds(request == null ? null : request.ids(), "请选择课程");
        String targetCatalogId = request == null ? null : request.targetCatalogId();
        KngCatalog target = StringUtils.hasText(targetCatalogId)
                ? catalogMapper.selectById(targetCatalogId) : null;
        if (target == null) {
            throw new BizException("请选择目标分类");
        }
        for (String id : ids) {
            KngCourse course = requireCourse(id);
            course.setCatalogId(target.getId());
            course.setCatalogName(target.getName());
            course.setUpdatedAt(LocalDateTime.now());
            courseMapper.updateById(course);
        }
        return new KngCoursewareDtos.CountResponse(ids.size());
    }

    /**
     * 复制课程（CS-09）：深拷贝课程主表 + 章节 + 任务三表，任务指向同一批课件；
     * 副本标题"-副本"重名叠加序号（同课件 copy）、status=0 未发布、platformNo 重新生成、
     * created 信息重置为当前操作人；副本任务的 ref 行经 rebuildForCourse 同步生成。
     */
    @Override
    @Transactional
    public KngCoursewareDtos.IdResponse copy(String id, KngCoursewareDtos.CopyRequest request, String userId) {
        KngCourse source = requireCourse(id);
        String targetCatalogId = request == null || !StringUtils.hasText(request.targetCatalogId())
                ? source.getCatalogId() : request.targetCatalogId();
        KngCatalog target = catalogMapper.selectById(targetCatalogId);
        if (target == null) {
            throw new BizException("目标分类不存在");
        }
        LocalDateTime now = LocalDateTime.now();

        KngCourse copy = new KngCourse();
        copy.setId(IdWorker.getIdStr());
        copy.setTitle(copyTitle(source.getTitle()));
        copy.setCatalogId(target.getId());
        copy.setCatalogName(target.getName());
        // 副本一律回到未发布（与课件复制 status=0 同语义），需重新走设计器保存发布
        copy.setStatus(0);
        copy.setOnSale(1);
        copy.setHidden(0);
        copy.setStructureType(source.getStructureType());
        copy.setUnlockMode(source.getUnlockMode());
        copy.setUnlockUnit(source.getUnlockUnit());
        copy.setStandardDurationFollow(source.getStandardDurationFollow());
        copy.setStandardMinutes(source.getStandardMinutes());
        copy.setStandardSeconds(source.getStandardSeconds());
        copy.setStudyScore(source.getStudyScore());
        copy.setFinishStandardPercent(source.getFinishStandardPercent());
        copy.setDescription(source.getDescription());
        copy.setCoverUrl(source.getCoverUrl());
        copy.setCode(source.getCode());
        copy.setVersionStr(source.getVersionStr());
        copy.setPlatformNo(nextCoursePlatformNo());
        copy.setViewPermission(source.getViewPermission());
        copy.setSpeedEnabled(source.getSpeedEnabled());
        copy.setSpeedHoursCalc(source.getSpeedHoursCalc());
        copy.setContributorId(source.getContributorId());
        copy.setCreatedBy(userId);
        copy.setCreatedByName(userName(userId));
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        courseMapper.insert(copy);

        // 章节深拷贝：旧章节 id → 新章节 id 映射，任务 chapter_id 指向新章节
        List<KngCourseChapter> sourceChapters = chapters(source.getId());
        Map<String, String> chapterIdMap = new HashMap<>();
        for (KngCourseChapter sourceChapter : sourceChapters) {
            KngCourseChapter chapter = new KngCourseChapter();
            chapter.setId(IdWorker.getIdStr());
            chapter.setCourseId(copy.getId());
            chapter.setName(sourceChapter.getName());
            chapter.setDescription(sourceChapter.getDescription());
            chapter.setOrderIndex(sourceChapter.getOrderIndex());
            chapter.setCreatedAt(now);
            chapterMapper.insert(chapter);
            chapterIdMap.put(sourceChapter.getId(), chapter.getId());
        }

        // 任务深拷贝：新 id、order 不变、hidden 随任务复制；同步收集课件 id 供 ref 重建
        List<KngCourseTask> sourceTasks = tasks(source.getId());
        List<String> copiedCoursewareIds = new ArrayList<>();
        for (KngCourseTask sourceTask : sourceTasks) {
            KngCourseTask task = new KngCourseTask();
            task.setId(IdWorker.getIdStr());
            task.setCourseId(copy.getId());
            task.setChapterId(sourceTask.getChapterId() == null
                    ? null : chapterIdMap.get(sourceTask.getChapterId()));
            task.setCoursewareId(sourceTask.getCoursewareId());
            task.setTaskName(sourceTask.getTaskName());
            task.setHidden(sourceTask.getHidden());
            task.setOrderIndex(sourceTask.getOrderIndex());
            task.setCreatedAt(now);
            taskMapper.insert(task);
            copiedCoursewareIds.add(sourceTask.getCoursewareId());
        }
        courseRefService.rebuildForCourse(copy.getId(), copy.getTitle(), copiedCoursewareIds);
        return new KngCoursewareDtos.IdResponse(copy.getId());
    }

    /**
     * 删除单课程（CS-09）：先做引用检查（kng_ref ref_type=2=培训/考试引用课程，当前恒无数据，
     * 查询逻辑已就位，未来模块写入后自动生效）→ 级联硬删课程/章节/任务并清理 ref_type=1 引用行。
     */
    @Override
    @Transactional
    public void delete(String id) {
        requireCourse(id);
        assertNotReferencedByBiz(id);
        chapterMapper.delete(Wrappers.<KngCourseChapter>lambdaQuery()
                .eq(KngCourseChapter::getCourseId, id));
        taskMapper.delete(Wrappers.<KngCourseTask>lambdaQuery()
                .eq(KngCourseTask::getCourseId, id));
        courseRefService.deleteByCourse(id);
        courseMapper.deleteById(id);
    }

    /** 批量删除：逐个复用 {@link #delete}，任一课程被引用则整体失败（同课件批量口径）。 */
    @Override
    @Transactional
    public void batchDelete(KngCoursewareDtos.IdsRequest request) {
        List<String> ids = requireIds(request == null ? null : request.ids(), "请选择课程");
        for (String id : ids) {
            delete(id);
        }
    }

    /** 课程引用记录（CS-09 占位）：查 kng_ref ref_type=2；当前恒空属预期（培训/考试模块未建）。 */
    @Override
    public List<KngRef> references(String id) {
        requireCourse(id);
        return refMapper.selectList(Wrappers.<KngRef>lambdaQuery()
                .eq(KngRef::getRefId, id)
                .eq(KngRef::getRefType, REF_TYPE_BIZ)
                .orderByDesc(KngRef::getCreatedAt));
    }

    // ==================== 私有辅助 ====================

    /** 取单条课程，不存在抛"课程不存在"（口径与 CourseServiceImpl.requireCourse 一致）。 */
    private KngCourse requireCourse(String id) {
        KngCourse course = StringUtils.hasText(id) ? courseMapper.selectById(id) : null;
        if (course == null) {
            throw new BizException("课程不存在");
        }
        return course;
    }

    /** 章节列表（order_index,id 升序，复制时保持顺序）。 */
    private List<KngCourseChapter> chapters(String courseId) {
        return chapterMapper.selectList(Wrappers.<KngCourseChapter>lambdaQuery()
                .eq(KngCourseChapter::getCourseId, courseId)
                .orderByAsc(KngCourseChapter::getOrderIndex)
                .orderByAsc(KngCourseChapter::getId));
    }

    /** 任务列表（order_index,id 升序，复制时保持顺序）。 */
    private List<KngCourseTask> tasks(String courseId) {
        return taskMapper.selectList(Wrappers.<KngCourseTask>lambdaQuery()
                .eq(KngCourseTask::getCourseId, courseId)
                .orderByAsc(KngCourseTask::getOrderIndex)
                .orderByAsc(KngCourseTask::getId));
    }

    /** 课程删除引用检查：ref_type=2（培训/考试）行存在即阻止，ref_name 为引用方名称快照。 */
    private void assertNotReferencedByBiz(String courseId) {
        List<KngRef> refs = refMapper.selectList(Wrappers.<KngRef>lambdaQuery()
                .eq(KngRef::getRefId, courseId)
                .eq(KngRef::getRefType, REF_TYPE_BIZ));
        if (!refs.isEmpty()) {
            String refName = StringUtils.hasText(refs.get(0).getRefName())
                    ? refs.get(0).getRefName() : "未知业务";
            throw new BizException("课程已被【" + refName + "】引用，无法删除");
        }
    }

    /**
     * 平台课程编号生成（ZXKT+yyyyMMdd+4位序号）：计数在 kng_course 表内独立递增，
     * 与课件平台编号（kng_courseware 表计数）互不交叉；synchronized 防同实例并发撞号。
     */
    private synchronized String nextCoursePlatformNo() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        int count = courseMapper.countPlatformNoByPrefix("ZXKT" + day);
        return "ZXKT" + day + String.format("%04d", count + 1);
    }

    /** 副本标题去重：base="-副本"，重名叠加 (2)(3)... 序号（与课件 copyTitle 同逻辑，查 kng_course）。 */
    private String copyTitle(String title) {
        String base = (StringUtils.hasText(title) ? title : "课程") + "-副本";
        String candidate = base;
        int index = 2;
        while (courseMapper.selectCount(Wrappers.<KngCourse>lambdaQuery()
                .eq(KngCourse::getTitle, candidate)) > 0) {
            candidate = base + "(" + index++ + ")";
        }
        return candidate;
    }

    /** id 集合入参收敛：去空去重，为空抛业务异常（批量操作通用）。 */
    private List<String> requireIds(List<String> raw, String message) {
        List<String> ids = raw == null ? List.of()
                : raw.stream().filter(StringUtils::hasText).distinct().toList();
        if (ids.isEmpty()) {
            throw new BizException(message);
        }
        return ids;
    }

    /** 操作人姓名补齐（Dubbo 查 sys_user；id 空或解析失败回落"系统"，与课件操作同口径）。 */
    private String userName(String userId) {
        if (!StringUtils.hasText(userId)) return "系统";
        try {
            List<jack.digityhuman.staffmanagement.api.dto.SysUserDTO> users =
                    orgQueryService.listUsersByIds(List.of(Long.parseLong(userId.trim())));
            return users.isEmpty() ? "系统" : users.get(0).getName();
        } catch (NumberFormatException exception) {
            return "系统";
        }
    }
}
