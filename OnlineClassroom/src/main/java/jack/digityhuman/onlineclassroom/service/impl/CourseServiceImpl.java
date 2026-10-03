package jack.digityhuman.onlineclassroom.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.dto.KngCourseDtos;
import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;
import jack.digityhuman.onlineclassroom.dto.KngReadDtos;
import jack.digityhuman.onlineclassroom.entity.KngCatalog;
import jack.digityhuman.onlineclassroom.entity.KngCourse;
import jack.digityhuman.onlineclassroom.entity.KngCourseChapter;
import jack.digityhuman.onlineclassroom.entity.KngCourseTask;
import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import jack.digityhuman.onlineclassroom.mapper.KngCatalogMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCourseChapterMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCourseMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCourseTaskMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareMapper;
import jack.digityhuman.onlineclassroom.mapper.KngStudyRecordMapper;
import jack.digityhuman.onlineclassroom.service.CourseService;
import jack.digityhuman.onlineclassroom.service.CoursewareService;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import jack.digityhuman.staffmanagement.api.dto.SysDepartmentDTO;
import jack.digityhuman.staffmanagement.api.dto.SysUserDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 课程查询、详情任务聚合、章节学习统计与学习数据（CS-01~CS-06）。
 *
 * <p>口径说明：
 * <ul>
 *   <li>列表/详情与课件模块同构，目录子树展开/学分策略折算直接复用 CoursewareService；</li>
 *   <li>课程维度学习数据一律以"本课程任务的课件 id 集合"过滤 kng_study_record
 *       （该表无课程列），当前无学习数据时恒为空态，属预期；</li>
 *   <li>列表 studyCount 恒 0：kng_study_record 无课程维度，列表"学习次数"列预期空态。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CourseServiceImpl implements CourseService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 排序字段白名单：外部 orderBy 只允许这三种 token，其余一律回落 updateTime（XML 侧再映射成列名） */
    private static final Set<String> ORDER_BY_WHITELIST = Set.of("updatetime", "createdat", "platformno");

    private final KngCourseMapper courseMapper;
    private final KngCourseChapterMapper chapterMapper;
    private final KngCourseTaskMapper taskMapper;
    private final KngCoursewareMapper coursewareMapper;
    private final KngCatalogMapper catalogMapper;
    private final KngStudyRecordMapper studyRecordMapper;
    /** 目录子树展开/学分策略折算/学习记录分页复用课件模块实现，避免逻辑重复 */
    private final CoursewareService coursewareService;
    /** 用户/部门数据经 Dubbo 取自 StaffManagement，不直读 sys_* 表 */
    private final OrgQueryService orgQueryService;

    /** 课程列表：多条件筛选 + 分页；排序白名单 updateTime/createdAt/platformNo（学习次数排序无数据源，不支持）。 */
    @Override
    public KngCourseDtos.ListResponse list(
            int pageNo, int pageSize, String orderBy, String orderType,
            KngCourseDtos.ListQuery query) {
        int safePage = Math.max(1, pageNo);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        KngCourseDtos.ListQuery filter = query == null
                ? new KngCourseDtos.ListQuery(null, null, null, null, null, null, null, null, null)
                : query;
        // 时间区间只做格式校验，合法字符串直接进 #{} 条件（与课件列表同口径）
        parseDate(filter.uploadTimeStart());
        parseDate(filter.uploadTimeEnd());
        parseDate(filter.updateTimeStart());
        parseDate(filter.updateTimeEnd());
        List<String> catalogIds = StringUtils.hasText(filter.catalogId())
                ? (filter.includeChildCatalog() != null && filter.includeChildCatalog() == 0
                ? List.of(filter.catalogId()) : coursewareService.descendantCatalogIds(filter.catalogId()))
                : List.of();

        boolean asc = "asc".equalsIgnoreCase(orderType);
        String safeOrderType = asc ? "asc" : "desc";
        String safeOrderBy = normalizeOrderBy(orderBy);
        List<KngCourse> page = courseMapper.selectPageByQuery(
                filter, catalogIds, safeOrderBy, safeOrderType,
                (safePage - 1) * safeSize, safeSize);
        KngCourseDtos.ListResponse response = new KngCourseDtos.ListResponse();
        response.setTotal(courseMapper.countByQuery(filter, catalogIds));
        response.setRecords(page.stream().map(this::toListItem).toList());
        return response;
    }

    /** 课程详情：基础字段 + 任务聚合（标准时长=Σ(分*60+秒)、学分=Σ课件 studyScoreCalc）+ 贡献者补齐。 */
    @Override
    public KngCourseDtos.Detail detail(String id) {
        KngCourse course = requireCourse(id);
        List<KngCourseTask> tasks = tasks(course.getId());
        Map<String, KngCourseware> coursewareMap = coursewareMap(tasks);
        boolean hasTasks = !tasks.isEmpty();

        // 标准时长聚合：有任务=按任务逐条取课件时长求和（两任务引用同一课件时按任务数累计）；
        // 无任务回退手填值（02-技术方案 §2.1 的展示优先级）
        long manualSeconds = (long) nz(course.getStandardMinutes()) * 60 + nz(course.getStandardSeconds());
        long standardDurationCalc = manualSeconds;
        if (hasTasks) {
            standardDurationCalc = tasks.stream()
                    .mapToLong(task -> taskSeconds(coursewareMap.get(task.getCoursewareId())))
                    .sum();
        }

        KngCourseDtos.Detail detail = new KngCourseDtos.Detail();
        detail.setId(course.getId());
        detail.setTitle(course.getTitle());
        detail.setCatalogId(course.getCatalogId());
        detail.setCatalogPath(catalogPath(course.getCatalogId()));
        detail.setStatus(course.getStatus());
        detail.setPlatformNo(course.getPlatformNo());
        detail.setStandardMinutes((int) (standardDurationCalc / 60));
        detail.setStandardSeconds((int) (standardDurationCalc % 60));
        detail.setStandardDurationCalc(standardDurationCalc);
        detail.setStudyScore(course.getStudyScore());
        detail.setStudyScoreCalc(studyScoreCalc(course, tasks, coursewareMap, hasTasks));
        detail.setFinishStandardPercent(course.getFinishStandardPercent());
        detail.setDescription(course.getDescription());
        detail.setCoverUrl(course.getCoverUrl());
        detail.setCode(course.getCode());
        detail.setVersionStr(course.getVersionStr());
        detail.setViewPermission(course.getViewPermission());
        detail.setSpeedEnabled(isTrue(course.getSpeedEnabled()));
        detail.setSpeedHoursCalc(isTrue(course.getSpeedHoursCalc()));
        detail.setContributor(findUser(course.getContributorId()));
        detail.setCreatedByName(course.getCreatedByName());
        detail.setStructureType(course.getStructureType());
        detail.setUnlockMode(course.getUnlockMode());
        detail.setUnlockUnit(course.getUnlockUnit());
        // attachments 恒空数组：kng_course 无附件存储列（04 DDL），表单 attachments 字段不持久化
        detail.setAttachments(List.of());
        return detail;
    }

    /**
     * 章节学习统计（CS-04）：按章节分组任务，每任务取其课件在 kng_study_record 的近 7 天/总学习数。
     * 无章节课程返回一个 chapterId=null 的分组；学习数据恒空属预期（学员端未接）。
     */
    @Override
    public List<KngCourseDtos.ChapterStudyStats> chapterStudyStats(String courseId) {
        KngCourse course = requireCourse(courseId);
        List<KngCourseChapter> chapters = chapterMapper.selectList(
                Wrappers.<KngCourseChapter>lambdaQuery()
                        .eq(KngCourseChapter::getCourseId, course.getId())
                        .orderByAsc(KngCourseChapter::getOrderIndex)
                        .orderByAsc(KngCourseChapter::getId));
        List<KngCourseTask> tasks = tasks(course.getId());
        Map<String, KngCourseware> coursewareMap = coursewareMap(tasks);
        // 近 7 天口径沿用课件学习概览（start_study_time >= now-7d），一次批量查询避免 N+1
        Map<String, KngReadDtos.StudyRecentCountRow> countMap = studyRecentCounts(
                tasks.stream().map(KngCourseTask::getCoursewareId).distinct().toList());

        List<KngCourseDtos.ChapterStudyStats> result = new ArrayList<>();
        if (chapters.isEmpty()) {
            // 无章节课程（structureType=0）：任务平铺为一个空章节名的分组
            result.add(toChapterGroup(null, null, tasks, coursewareMap, countMap));
            return result;
        }
        Set<String> chapterIds = chapters.stream().map(KngCourseChapter::getId).collect(Collectors.toSet());
        Map<String, List<KngCourseTask>> byChapter = tasks.stream()
                .filter(item -> item.getChapterId() != null && chapterIds.contains(item.getChapterId()))
                .collect(Collectors.groupingBy(KngCourseTask::getChapterId,
                        LinkedHashMap::new, Collectors.toList()));
        for (KngCourseChapter chapter : chapters) {
            result.add(toChapterGroup(chapter.getId(), chapter.getName(),
                    byChapter.getOrDefault(chapter.getId(), List.of()), coursewareMap, countMap));
        }
        // 兜底：章节被删但任务残留（chapterId 为空或悬空）时不丢数据，归入 null 分组
        List<KngCourseTask> orphans = tasks.stream()
                .filter(item -> item.getChapterId() == null || !chapterIds.contains(item.getChapterId()))
                .toList();
        if (!orphans.isEmpty()) {
            result.add(toChapterGroup(null, null, orphans, coursewareMap, countMap));
        }
        return result;
    }

    /** 学习概览（CS-06）：本课程任务课件集合的三项计数；无任务返回全 0（不发 SQL）。 */
    @Override
    public KngCourseDtos.StudyOverview studyOverview(String courseId) {
        KngCourse course = requireCourse(courseId);
        List<String> coursewareIds = coursewareIds(course.getId());
        if (coursewareIds.isEmpty()) {
            return new KngCourseDtos.StudyOverview(0, 0, 0);
        }
        KngReadDtos.StudyOverviewRow counts = studyRecordMapper.selectOverviewByCoursewareIds(
                coursewareIds, LocalDateTime.now().minusDays(7));
        return new KngCourseDtos.StudyOverview(
                counts.getNumOf7days(), counts.getNumOftotal(), counts.getNumOfFinish());
    }

    /** 完课用时分布（CS-06）：口径同课件模块，过滤口径换成本课程任务课件集合。 */
    @Override
    public KngCourseDtos.FinishTimeOverview finishTimeOverview(String courseId) {
        requireCourse(courseId);
        List<String> coursewareIds = coursewareIds(courseId);
        if (coursewareIds.isEmpty()) {
            return new KngCourseDtos.FinishTimeOverview(0, 0, 0, 0, 0);
        }
        KngReadDtos.FinishTimeRow counts =
                studyRecordMapper.selectFinishTimeOverviewByCoursewareIds(coursewareIds);
        return new KngCourseDtos.FinishTimeOverview(
                counts.getNumInOneDay(),
                counts.getNumInOneWeek(),
                counts.getNumInOneMon(),
                counts.getNumInThreeMon(),
                counts.getNumInMoreThrMon());
    }

    /** 一级部门下拉：直接复用课件模块 deptFirstLevel 的实现口径（OrgQueryService Dubbo）。 */
    @Override
    public List<KngCourseDtos.DeptBrief> deptFirstLevel() {
        return orgQueryService.listFirstLevelDepartments().stream()
                .map(item -> new KngCourseDtos.DeptBrief(
                        String.valueOf(item.getId()), item.getDeptName()))
                .toList();
    }

    /** 部门完成度（CS-06）：本课程任务课件集合按部门分组计数，部门名/排序经 Dubbo 补齐。 */
    @Override
    public List<KngCourseDtos.DeptFinish> deptFinishOverview(
            String courseId, KngCourseDtos.DeptFinishRequest request) {
        requireCourse(courseId);
        List<String> coursewareIds = coursewareIds(courseId);
        if (coursewareIds.isEmpty()) {
            return List.of();
        }
        List<String> deptIds = request == null || request.deptIds() == null
                ? List.of() : request.deptIds().stream().filter(StringUtils::hasText).distinct().toList();
        List<KngReadDtos.StudyDeptCountRow> counts =
                studyRecordMapper.selectDeptCountsByCoursewareIds(coursewareIds, deptIds);
        // 未传部门时取全量部门（与课件模块一致：返回所有有数据的部门，按 sort 排序）
        Map<String, SysDepartmentDTO> departments = deptIds.isEmpty()
                ? departmentMap(orgQueryService.listAllDepartments().stream()
                .map(item -> String.valueOf(item.getId())).toList())
                : departmentMap(deptIds);
        return counts.stream()
                .filter(item -> departments.containsKey(item.getDeptId()))
                .sorted(Comparator.comparing(item -> {
                    SysDepartmentDTO department = departments.get(item.getDeptId());
                    return department.getSort() == null ? Integer.MAX_VALUE : department.getSort();
                }))
                .map(item -> new KngCourseDtos.DeptFinish(
                        item.getDeptId(),
                        departments.get(item.getDeptId()).getDeptName(),
                        item.getStudyCount(),
                        item.getFinishCount()))
                .toList();
    }

    /**
     * 课程内课件学习记录（CS-05）：完全复用课件 study-records 逻辑（部门子树/关键词展开、行级补齐）。
     * courseId 仅做课程存在性校验（kng_study_record 无课程维度，记录归属以 courseware_id 为准）；
     * 响应行多一个 studySchedule（学习进度%），由课件侧 toStudyRecordItem 统一带出。
     */
    @Override
    public KngCoursewareDtos.StudyRecordResponse studyRecords(
            String courseId, String coursewareId, int pageNo, int pageSize,
            KngCoursewareDtos.StudyRecordQuery query) {
        requireCourse(courseId);
        return coursewareService.studyRecords(coursewareId, pageNo, pageSize, query);
    }

    // ==================== 私有辅助 ====================

    /** 取单条课程，不存在抛"课程不存在"（课程 id 悬空时前端应回到列表页）。 */
    private KngCourse requireCourse(String id) {
        KngCourse course = StringUtils.hasText(id) ? courseMapper.selectById(id) : null;
        if (course == null) {
            throw new BizException("课程不存在");
        }
        return course;
    }

    /** 课程任务列表（按 order_index,id 升序，保证章节内任务顺序稳定）。 */
    private List<KngCourseTask> tasks(String courseId) {
        return taskMapper.selectList(Wrappers.<KngCourseTask>lambdaQuery()
                .eq(KngCourseTask::getCourseId, courseId)
                .orderByAsc(KngCourseTask::getOrderIndex)
                .orderByAsc(KngCourseTask::getId));
    }

    /** 本课程任务引用的全部课件 id（去重；学习数据四件套与章节统计的过滤口径）。 */
    private List<String> coursewareIds(String courseId) {
        return tasks(courseId).stream()
                .map(KngCourseTask::getCoursewareId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    /** 单任务课件时长（秒），任务引用的课件不存在（悬空 ref）时按 0 计。 */
    private long taskSeconds(KngCourseware courseware) {
        return courseware == null ? 0L
                : (long) nz(courseware.getStandardMinutes()) * 60 + nz(courseware.getStandardSeconds());
    }

    /** 批量取任务引用的课件实体（单表 in 查询 + Map 组装，不 JOIN）。 */
    private List<KngCourseware> coursewares(List<KngCourseTask> tasks) {
        List<String> ids = tasks.stream()
                .map(KngCourseTask::getCoursewareId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        return ids.isEmpty() ? List.of() : coursewareMapper.selectBatchIds(ids);
    }

    private Map<String, KngCourseware> coursewareMap(List<KngCourseTask> tasks) {
        return coursewares(tasks).stream()
                .collect(Collectors.toMap(KngCourseware::getId, item -> item, (left, right) -> left));
    }

    /** 近 7 天/总数按课件分组批量查询（课件 id 集合为空时直接返回空 Map，不发 SQL）。 */
    private Map<String, KngReadDtos.StudyRecentCountRow> studyRecentCounts(List<String> coursewareIds) {
        if (coursewareIds.isEmpty()) {
            return Map.of();
        }
        Map<String, KngReadDtos.StudyRecentCountRow> result = new HashMap<>();
        for (KngReadDtos.StudyRecentCountRow row :
                studyRecordMapper.selectRecentAndTotalByCoursewareIds(coursewareIds, LocalDateTime.now().minusDays(7))) {
            result.put(row.getCoursewareId(), row);
        }
        return result;
    }

    private KngCourseDtos.ChapterStudyStats toChapterGroup(
            String chapterId,
            String chapterName,
            List<KngCourseTask> tasks,
            Map<String, KngCourseware> coursewareMap,
            Map<String, KngReadDtos.StudyRecentCountRow> countMap) {
        KngCourseDtos.ChapterStudyStats group = new KngCourseDtos.ChapterStudyStats();
        group.setChapterId(chapterId);
        group.setChapterName(chapterName);
        group.setTasks(tasks.stream().map(task -> {
            KngCourseDtos.TaskStudyStats row = new KngCourseDtos.TaskStudyStats();
            row.setTaskName(task.getTaskName());
            row.setCoursewareId(task.getCoursewareId());
            KngCourseware courseware = coursewareMap.get(task.getCoursewareId());
            row.setCoursewareType(courseware == null ? null : courseware.getType());
            KngReadDtos.StudyRecentCountRow counts = countMap.get(task.getCoursewareId());
            row.setNumOfPastweek(counts == null || counts.getRecentCount() == null ? 0 : counts.getRecentCount());
            row.setNumOfTotal(counts == null || counts.getTotalCount() == null ? 0 : counts.getTotalCount());
            return row;
        }).toList());
        return group;
    }

    private KngCourseDtos.ListItem toListItem(KngCourse item) {
        KngCourseDtos.ListItem row = new KngCourseDtos.ListItem();
        row.setId(item.getId());
        row.setTitle(item.getTitle());
        row.setCatalogId(item.getCatalogId());
        row.setCatalogName(item.getCatalogName());
        row.setStatus(item.getStatus());
        row.setStatusDesc(statusDesc(item.getStatus()));
        row.setOnSale(item.getOnSale());
        row.setHidden(isTrue(item.getHidden()));
        row.setUpdateTime(format(item.getUpdatedAt()));
        // 恒 0：kng_study_record 无课程维度，列表"学习次数"预期空态（03-接口文档 CS-01）
        row.setStudyCount(0);
        row.setPlatformNo(item.getPlatformNo());
        row.setCoverUrl(item.getCoverUrl());
        row.setCreatedByName(item.getCreatedByName());
        row.setStructureType(item.getStructureType());
        return row;
    }

    /**
     * 课程学分展示值：有任务=按任务逐条取课件 studyScoreCalc 求和（CS-07 拍板"课程跟随时=任务学分和"）；
     * 无任务且单独设置了学分=该值；无任务且未设置（跟随态）时返回 null——课程无 type 字段，
     * 无法套用课件模块按类型的策略折算公式，待产品确认后补充（不编造折算逻辑）。
     */
    private BigDecimal studyScoreCalc(
            KngCourse course, List<KngCourseTask> tasks,
            Map<String, KngCourseware> coursewareMap, boolean hasTasks) {
        if (hasTasks) {
            return tasks.stream()
                    .map(task -> coursewareMap.get(task.getCoursewareId()))
                    .filter(Objects::nonNull)
                    .map(coursewareService::studyScoreCalc)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return course.getStudyScore();
    }

    /** 排序白名单收敛：只放行 updateTime/createdAt/platformNo，其余（含 studyCount）回落 updateTime。 */
    private String normalizeOrderBy(String orderBy) {
        String lower = orderBy == null ? "" : orderBy.trim().toLowerCase();
        if (!ORDER_BY_WHITELIST.contains(lower)) {
            return "updateTime";
        }
        return switch (lower) {
            case "createdat" -> "createdAt";
            case "platformno" -> "platformNo";
            default -> "updateTime";
        };
    }

    /** 分类全路径拼装（parentPath 逐级上溯，参照课件详情 catalogPath 实现）。 */
    private String catalogPath(String catalogId) {
        List<KngCatalog> all = catalogMapper.selectList(Wrappers.<KngCatalog>lambdaQuery());
        Map<String, KngCatalog> byId = all.stream().collect(Collectors.toMap(KngCatalog::getId, item -> item));
        List<String> names = new ArrayList<>();
        KngCatalog current = byId.get(catalogId);
        while (current != null) {
            names.add(0, current.getName());
            current = StringUtils.hasText(current.getParentId()) ? byId.get(current.getParentId()) : null;
        }
        return String.join("/", names);
    }

    /** 贡献者姓名/账号补齐：Dubbo 查 sys_user；id 空/查不到回落"系统"（与课件详情同口径）。 */
    private KngCourseDtos.Contributor findUser(String userId) {
        KngCourseDtos.Contributor contributor = new KngCourseDtos.Contributor();
        contributor.setUserId(userId);
        if (!StringUtils.hasText(userId)) {
            contributor.setName("系统");
            contributor.setAccount("--");
            return contributor;
        }
        List<Long> parsed = toLongIds(List.of(userId));
        List<SysUserDTO> rows = parsed.isEmpty() ? List.of()
                : orgQueryService.listUsersByIds(parsed);
        if (rows.isEmpty()) {
            contributor.setName("系统");
            contributor.setAccount("--");
        } else {
            contributor.setName(rows.get(0).getName());
            contributor.setAccount(rows.get(0).getAccount());
        }
        return contributor;
    }

    /** 部门 id → 部门 DTO（Dubbo 批量），空入参直接返回空 Map 避免无效 RPC。 */
    private Map<String, SysDepartmentDTO> departmentMap(List<String> deptIds) {
        if (deptIds.isEmpty()) {
            return Map.of();
        }
        Map<String, SysDepartmentDTO> result = new HashMap<>();
        for (SysDepartmentDTO department : orgQueryService.listDepartmentsByIds(toLongIds(deptIds))) {
            result.put(String.valueOf(department.getId()), department);
        }
        return result;
    }

    /** kng 表里存的用户/部门 id 是字符串，容忍 "0"、空串等非数字值（与课件模块 toLongIds 同口径）。 */
    private static List<Long> toLongIds(Iterable<String> values) {
        List<Long> result = new ArrayList<>();
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            try {
                result.add(Long.parseLong(value.trim()));
            } catch (NumberFormatException ignored) {
                // 非数字 id 不可能命中 sys 表，直接跳过
            }
        }
        return result;
    }

    private LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw new BizException("筛选日期格式不正确");
        }
    }

    private String statusDesc(Integer status) {
        return switch (status == null ? 0 : status) {
            case 0 -> "未发布";
            case 1 -> "发布中";
            case 2 -> "已发布";
            case 3 -> "已下架";
            default -> "未知";
        };
    }

    private boolean isTrue(Integer value) {
        return value != null && value == 1;
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private String format(LocalDateTime value) {
        return value == null ? null : DATE_TIME.format(value);
    }
}
