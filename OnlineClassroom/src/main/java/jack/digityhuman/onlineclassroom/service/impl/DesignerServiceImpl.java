package jack.digityhuman.onlineclassroom.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.dto.KngCourseDtos;
import jack.digityhuman.onlineclassroom.entity.KngCourse;
import jack.digityhuman.onlineclassroom.entity.KngCourseChapter;
import jack.digityhuman.onlineclassroom.entity.KngCourseTask;
import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import jack.digityhuman.onlineclassroom.mapper.KngCourseChapterMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCourseMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCourseTaskMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareMapper;
import jack.digityhuman.onlineclassroom.service.CourseRefService;
import jack.digityhuman.onlineclassroom.service.CoursewareService;
import jack.digityhuman.onlineclassroom.service.DesignerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 课程设计器结构整体读写实现（CS-07a）。
 *
 * <p>口径说明：
 * <ul>
 *   <li>保存=全删全插（02-技术方案 §5，任务数≤999 规模下无性能问题），章节/任务 id 全部重新生成，
 *       请求中的旧 id 仅作前端回显用途、保存后前端刷新重拉；</li>
 *   <li>任务展示字段（credits/durationText/coursewareStatus/coursewareType）为 join 课件实时值，
 *       跨表数据按铁律单表 in 查询 + Map 组装，不 JOIN；</li>
 *   <li>课件被物理删除的任务：taskName 保留原值，展示字段置 null（历史引用悬空不丢任务行）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DesignerServiceImpl implements DesignerService {

    /** 设计器任务总数上限（01-接口文档 CS-07a 校验项；选课件抽屉单次上限 99，累计上限 999） */
    private static final int MAX_TASK_COUNT = 999;

    private final KngCourseMapper courseMapper;
    private final KngCourseChapterMapper chapterMapper;
    private final KngCourseTaskMapper taskMapper;
    private final KngCoursewareMapper coursewareMapper;
    private final CourseRefService courseRefService;
    /** 课件学分展示值（studyScoreCalc，含策略折算）复用课件服务，避免两处维护 */
    private final CoursewareService coursewareService;

    /** 读取设计器结构：章节按 order_index 排序、章节内任务按 order_index 排序；无章节课程任务进 flatTasks。 */
    @Override
    public KngCourseDtos.DesignerView view(String courseId) {
        KngCourse course = requireCourse(courseId);
        List<KngCourseChapter> chapters = chapterMapper.selectList(
                Wrappers.<KngCourseChapter>lambdaQuery()
                        .eq(KngCourseChapter::getCourseId, course.getId())
                        .orderByAsc(KngCourseChapter::getOrderIndex)
                        .orderByAsc(KngCourseChapter::getId));
        List<KngCourseTask> tasks = taskMapper.selectList(
                Wrappers.<KngCourseTask>lambdaQuery()
                        .eq(KngCourseTask::getCourseId, course.getId())
                        .orderByAsc(KngCourseTask::getOrderIndex)
                        .orderByAsc(KngCourseTask::getId));
        // 单表 in 批量查课件 + Map 组装（不 JOIN），供学分/时长/状态/类型实时取值
        Map<String, KngCourseware> coursewareMap = coursewareMap(tasks);

        KngCourseDtos.DesignerView view = new KngCourseDtos.DesignerView();
        view.setStructureType(course.getStructureType());
        view.setUnlockMode(course.getUnlockMode());
        view.setUnlockUnit(course.getUnlockUnit());

        Set<String> chapterIds = chapters.stream()
                .map(KngCourseChapter::getId).collect(Collectors.toSet());
        Map<String, List<KngCourseTask>> byChapter = tasks.stream()
                .filter(item -> item.getChapterId() != null && chapterIds.contains(item.getChapterId()))
                .collect(Collectors.groupingBy(KngCourseTask::getChapterId));

        for (KngCourseChapter chapter : chapters) {
            KngCourseDtos.DesignerChapter row = new KngCourseDtos.DesignerChapter();
            row.setId(chapter.getId());
            row.setName(chapter.getName());
            row.setDescription(chapter.getDescription());
            row.setTasks(byChapter.getOrDefault(chapter.getId(), List.of()).stream()
                    .map(task -> toDesignerTask(task, coursewareMap))
                    .toList());
            view.getChapters().add(row);
        }

        // flatTasks=chapter_id 为空的任务；章节被删但任务残留（悬空 chapter_id）时兜底归入 flatTasks，
        // 避免结构切换（有→无）前的脏数据在界面上"消失"
        List<KngCourseTask> flat = tasks.stream()
                .filter(item -> item.getChapterId() == null || !chapterIds.contains(item.getChapterId()))
                .toList();
        view.setFlatTasks(flat.stream()
                .map(task -> toDesignerTask(task, coursewareMap))
                .toList());
        return view;
    }

    /**
     * 保存设计器（保存/暂存共用，03-接口文档 FP-CS-07a-2）：
     * 校验 → 更新课程结构字段 → 全删全插 chapters+tasks → ref 重建 → publish=true 且 status=0 时置已发布。
     * publish=false（暂存关闭）状态不变。
     */
    @Override
    @Transactional
    public void save(String courseId, KngCourseDtos.DesignerSaveRequest request) {
        KngCourse course = requireCourse(courseId);
        if (request == null) {
            throw new BizException("请提交设计器数据");
        }
        List<KngCourseDtos.DesignerSaveChapter> chapters =
                request.chapters() == null ? List.of() : request.chapters();
        List<KngCourseDtos.DesignerSaveTask> flatTasks =
                request.flatTasks() == null ? List.of() : request.flatTasks();

        // 校验：任务总数 ≤999；章节名必填≤200；章节说明填了才校验 ≥5 字
        int total = flatTasks.size()
                + chapters.stream()
                .mapToInt(chapter -> chapter.tasks() == null ? 0 : chapter.tasks().size())
                .sum();
        if (total > MAX_TASK_COUNT) {
            throw new BizException("任务数量超出上限（" + MAX_TASK_COUNT + "）");
        }
        for (KngCourseDtos.DesignerSaveChapter chapter : chapters) {
            if (!StringUtils.hasText(chapter.name()) || chapter.name().length() > 200) {
                throw new BizException("章节名称必填且不超过 200 字");
            }
            if (StringUtils.hasText(chapter.description()) && chapter.description().length() < 5) {
                throw new BizException("章节说明至少 5 个字");
            }
        }

        // 校验：coursewareId 必须存在于 kng_courseware（硬删/不存在即阻止，已下架允许）
        List<KngCourseDtos.DesignerSaveTask> allTasks = new ArrayList<>(flatTasks);
        chapters.forEach(chapter -> allTasks.addAll(
                chapter.tasks() == null ? List.of() : chapter.tasks()));
        Map<String, KngCourseware> coursewareMap = coursewareMapByIds(allTasks.stream()
                .map(KngCourseDtos.DesignerSaveTask::coursewareId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList());
        for (KngCourseDtos.DesignerSaveTask task : allTasks) {
            if (!StringUtils.hasText(task.coursewareId())
                    || !coursewareMap.containsKey(task.coursewareId())) {
                String name = StringUtils.hasText(task.taskName())
                        ? task.taskName() : task.coursewareId();
                throw new BizException("课件【" + name + "】已删除，无法保存");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        // 更新课程结构字段；publish=true 且当前未发布(0) → 已发布(2)（一次 update 落库）
        course.setStructureType(request.structureType() == null ? 0 : request.structureType());
        course.setUnlockMode(request.unlockMode() == null ? 1 : request.unlockMode());
        course.setUnlockUnit(request.unlockUnit() == null ? 1 : request.unlockUnit());
        if (Boolean.TRUE.equals(request.publish()) && Objects.equals(course.getStatus(), 0)) {
            course.setStatus(2);
        }
        course.setUpdatedAt(now);
        courseMapper.updateById(course);

        // 全删全插：章节/任务 id 全部重新生成（请求旧 id 忽略，前端保存后刷新重拉）
        chapterMapper.delete(Wrappers.<KngCourseChapter>lambdaQuery()
                .eq(KngCourseChapter::getCourseId, course.getId()));
        taskMapper.delete(Wrappers.<KngCourseTask>lambdaQuery()
                .eq(KngCourseTask::getCourseId, course.getId()));

        List<String> insertedCoursewareIds = new ArrayList<>();
        int chapterOrder = 0;
        for (KngCourseDtos.DesignerSaveChapter saveChapter : chapters) {
            KngCourseChapter chapter = new KngCourseChapter();
            chapter.setId(IdWorker.getIdStr());
            chapter.setCourseId(course.getId());
            chapter.setName(saveChapter.name());
            chapter.setDescription(saveChapter.description());
            chapter.setOrderIndex(chapterOrder++);
            chapter.setCreatedAt(now);
            chapterMapper.insert(chapter);
            insertTasks(course.getId(), chapter.getId(),
                    saveChapter.tasks() == null ? List.of() : saveChapter.tasks(),
                    coursewareMap, now, insertedCoursewareIds);
        }
        // 无章节课程（structureType=0）任务平铺：chapter_id=NULL
        insertTasks(course.getId(), null, flatTasks, coursewareMap, now, insertedCoursewareIds);

        // ref 重建：delete by ref_id=courseId → 按任务序 batch insert（ref_name=课程名快照）
        courseRefService.rebuildForCourse(course.getId(), course.getTitle(), insertedCoursewareIds);
    }

    // ==================== 私有辅助 ====================

    /** 章节内任务落库：新雪花 id、order_index 按列表序、task_name 空时回落课件名、hidden 归一为 0/1。 */
    private void insertTasks(
            String courseId, String chapterId,
            List<KngCourseDtos.DesignerSaveTask> saveTasks,
            Map<String, KngCourseware> coursewareMap,
            LocalDateTime now, List<String> insertedCoursewareIds) {
        int order = 0;
        for (KngCourseDtos.DesignerSaveTask saveTask : saveTasks) {
            KngCourseware courseware = coursewareMap.get(saveTask.coursewareId());
            KngCourseTask task = new KngCourseTask();
            task.setId(IdWorker.getIdStr());
            task.setCourseId(courseId);
            task.setChapterId(chapterId);
            task.setCoursewareId(saveTask.coursewareId());
            // 任务名默认=课件名（编辑抽屉可改；请求未传名时以课件名兜底，DDL task_name NOT NULL）
            task.setTaskName(StringUtils.hasText(saveTask.taskName())
                    ? saveTask.taskName()
                    : (courseware == null ? null : courseware.getTitle()));
            task.setHidden(Boolean.TRUE.equals(saveTask.hidden()) ? 1 : 0);
            task.setOrderIndex(order++);
            task.setCreatedAt(now);
            taskMapper.insert(task);
            insertedCoursewareIds.add(saveTask.coursewareId());
        }
    }

    /** 设计器任务行组装：credits/durationText/coursewareStatus/coursewareType 取课件实时值。 */
    private KngCourseDtos.DesignerTask toDesignerTask(
            KngCourseTask task, Map<String, KngCourseware> coursewareMap) {
        KngCourseDtos.DesignerTask row = new KngCourseDtos.DesignerTask();
        row.setId(task.getId());
        row.setCoursewareId(task.getCoursewareId());
        row.setTaskName(task.getTaskName());
        row.setHidden(task.getHidden() != null && task.getHidden() == 1);
        // 课件已被物理删除（悬空引用）时展示字段全部置 null，taskName 保留供界面辨认（不丢行）
        KngCourseware courseware = coursewareMap.get(task.getCoursewareId());
        if (courseware == null) {
            row.setCredits(null);
            row.setDurationText(null);
            row.setCoursewareStatus(null);
            row.setCoursewareType(null);
            return row;
        }
        row.setCredits(coursewareService.studyScoreCalc(courseware));
        row.setDurationText(durationText(courseware));
        row.setCoursewareStatus(courseware.getStatus());
        row.setCoursewareType(courseware.getType());
        return row;
    }

    /**
     * 时长展示文案：由课件 standardMinutes/Seconds 拼"X 分 Y 秒"。
     * 0 分 0 秒返回空串（界面显示"—"由前端处理）；仅分或仅秒有值时省略 0 段，更贴近中文习惯。
     */
    private String durationText(KngCourseware courseware) {
        int minutes = courseware.getStandardMinutes() == null ? 0 : courseware.getStandardMinutes();
        int seconds = courseware.getStandardSeconds() == null ? 0 : courseware.getStandardSeconds();
        if (minutes == 0 && seconds == 0) {
            return "";
        }
        if (minutes == 0) {
            return seconds + " 秒";
        }
        if (seconds == 0) {
            return minutes + " 分";
        }
        return minutes + " 分 " + seconds + " 秒";
    }

    /** 批量取任务引用的课件实体（单表 in 查询 + Map 组装，不 JOIN；id 集合为空不发 SQL）。 */
    private Map<String, KngCourseware> coursewareMapByIds(List<String> coursewareIds) {
        if (coursewareIds.isEmpty()) {
            return Map.of();
        }
        return coursewareMapper.selectBatchIds(coursewareIds).stream()
                .collect(Collectors.toMap(KngCourseware::getId, Function.identity(), (l, r) -> l));
    }

    private Map<String, KngCourseware> coursewareMap(List<KngCourseTask> tasks) {
        return coursewareMapByIds(tasks.stream()
                .map(KngCourseTask::getCoursewareId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList());
    }

    /** 取单条课程，不存在抛"课程不存在"（口径与 CourseServiceImpl/CourseOpServiceImpl 一致）。 */
    private KngCourse requireCourse(String id) {
        KngCourse course = StringUtils.hasText(id) ? courseMapper.selectById(id) : null;
        if (course == null) {
            throw new BizException("课程不存在");
        }
        return course;
    }
}
