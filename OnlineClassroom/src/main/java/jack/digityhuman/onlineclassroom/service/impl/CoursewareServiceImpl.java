package jack.digityhuman.onlineclassroom.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;
import jack.digityhuman.onlineclassroom.dto.KngReadDtos;
import jack.digityhuman.onlineclassroom.dto.KngSettingDtos;
import jack.digityhuman.onlineclassroom.entity.KngCatalog;
import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import jack.digityhuman.onlineclassroom.entity.KngCoursewareAttachment;
import jack.digityhuman.onlineclassroom.entity.KngCoursewareFile;
import jack.digityhuman.onlineclassroom.entity.KngSetting;
import jack.digityhuman.onlineclassroom.entity.KngStudyRecord;
import jack.digityhuman.onlineclassroom.mapper.KngCatalogMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareAttachmentMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareFileMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareMapper;
import jack.digityhuman.onlineclassroom.mapper.KngRefMapper;
import jack.digityhuman.onlineclassroom.mapper.KngSettingMapper;
import jack.digityhuman.onlineclassroom.mapper.KngStudyRecordMapper;
import jack.digityhuman.onlineclassroom.service.CoursewareService;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import jack.digityhuman.staffmanagement.api.dto.SysDepartmentDTO;
import jack.digityhuman.staffmanagement.api.dto.SysUserDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 课件查询、详情、保存与学习数据聚合。
 */
@Service
@RequiredArgsConstructor
public class CoursewareServiceImpl implements CoursewareService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MINUTE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<Integer> TYPES = Set.of(1, 2, 3, 4, 5, 6, 7, 8);

    private final KngCoursewareMapper coursewareMapper;
    private final KngCoursewareFileMapper fileMapper;
    private final KngCoursewareAttachmentMapper attachmentMapper;
    private final KngCatalogMapper catalogMapper;
    private final KngSettingMapper settingMapper;
    private final KngRefMapper refMapper;
    private final KngStudyRecordMapper studyRecordMapper;
    /** 用户/部门数据经 Dubbo 取自 StaffManagement，不再直读 sys_* 表 */
    private final OrgQueryService orgQueryService;

    /** 课件列表：分页/多条件筛选（目录子树展开、引用状态、时间校验），支持按学习次数排序（全量取回内存排序）。 */
    public KngCoursewareDtos.ListResponse list(
            int pageNo, int pageSize, String orderBy, String orderType,
            KngCoursewareDtos.ListQuery query) {
        int safePage = Math.max(1, pageNo);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        KngCoursewareDtos.ListQuery filter = query == null
                ? new KngCoursewareDtos.ListQuery(null, null, null, null, 1, null, null, null, null, null, null)
                : query;
        parseDate(filter.uploadTimeStart());
        parseDate(filter.uploadTimeEnd());
        parseDate(filter.updateTimeStart());
        parseDate(filter.updateTimeEnd());
        List<String> catalogIds = StringUtils.hasText(filter.catalogId())
                ? (filter.includeChildCatalog() != null && filter.includeChildCatalog() == 0
                ? List.of(filter.catalogId()) : descendantCatalogIds(filter.catalogId()))
                : List.of();
        List<String> quotedIds = filter.quoted() == null
                ? List.of() : refMapper.selectDistinctCoursewareIds();

        boolean studyCountSort = "studyCount".equalsIgnoreCase(orderBy);
        boolean asc = "asc".equalsIgnoreCase(orderType);
        KngCoursewareDtos.ListResponse response = new KngCoursewareDtos.ListResponse();
        if (studyCountSort) {
            List<KngCourseware> all = coursewareMapper.selectAllByQuery(filter, catalogIds, quotedIds);
            Map<String, Integer> allStudyCounts = studyCounts(all.stream()
                    .map(KngCourseware::getId).toList());
            all.sort((left, right) -> {
                int compared = Integer.compare(
                        allStudyCounts.getOrDefault(left.getId(), 0),
                        allStudyCounts.getOrDefault(right.getId(), 0));
                if (!asc) compared = -compared;
                if (compared != 0) return compared;
                return Objects.compare(right.getUpdatedAt(), left.getUpdatedAt(),
                        Comparator.nullsLast(Comparator.naturalOrder()));
            });
            int from = Math.min(all.size(), (safePage - 1) * safeSize);
            int to = Math.min(all.size(), from + safeSize);
            response.setTotal(all.size());
            response.setRecords(all.subList(from, to).stream()
                    .map(item -> toListItem(item, allStudyCounts.getOrDefault(item.getId(), 0)))
                    .toList());
        } else {
            String safeOrderType = asc ? "asc" : "desc";
            List<KngCourseware> page = coursewareMapper.selectPageByQuery(
                    filter, catalogIds, quotedIds, "updateTime", safeOrderType,
                    (safePage - 1) * safeSize, safeSize);
            Map<String, Integer> studyCounts = studyCounts(page.stream()
                    .map(KngCourseware::getId).toList());
            List<KngCoursewareDtos.ListItem> records = page.stream()
                    .map(item -> toListItem(item, studyCounts.getOrDefault(item.getId(), 0)))
                    .collect(Collectors.toCollection(ArrayList::new));
            response.setTotal(coursewareMapper.countByQuery(filter, catalogIds, quotedIds));
            response.setRecords(records);
        }
        return response;
    }

    /** 课件详情：基础字段 + 目录路径/时长换算/贡献人姓名补齐 + 文件与附件列表。 */
    public KngCoursewareDtos.Detail detail(String id) {
        KngCourseware courseware = require(id);
        KngCoursewareDtos.Detail detail = new KngCoursewareDtos.Detail();
        detail.setId(courseware.getId());
        detail.setTitle(courseware.getTitle());
        detail.setType(courseware.getType());
        detail.setTypeDesc(typeDesc(courseware.getType()));
        detail.setCatalogId(courseware.getCatalogId());
        detail.setCatalogPath(catalogPath(courseware.getCatalogId()));
        detail.setStatus(courseware.getStatus());
        detail.setPlatformNo(courseware.getPlatformNo());
        detail.setStandardMinutes(courseware.getStandardMinutes());
        detail.setStandardSeconds(courseware.getStandardSeconds());
        detail.setStandardDurationFollow(isTrue(courseware.getStandardDurationFollow()));
        detail.setStudyScore(courseware.getStudyScore());
        detail.setStudyScoreCalc(studyScoreCalc(courseware));
        detail.setFinishStandardPercent(courseware.getFinishStandardPercent());
        detail.setDescription(courseware.getDescription());
        detail.setCoverUrl(courseware.getCoverUrl());
        detail.setCode(courseware.getCode());
        detail.setVersionStr(courseware.getVersionStr());
        detail.setViewPermission(courseware.getViewPermission());
        detail.setDownloadEnabled(isTrue(courseware.getDownloadEnabled()));
        detail.setDownloadPermission(courseware.getDownloadPermission());
        detail.setAppSaveEnabled(isTrue(courseware.getAppSaveEnabled()));
        detail.setAppSavePermission(courseware.getAppSavePermission());
        detail.setSpeedEnabled(isTrue(courseware.getSpeedEnabled()));
        detail.setSpeedHoursCalc(isTrue(courseware.getSpeedHoursCalc()));
        detail.setAiSubtitleEnabled(isTrue(courseware.getAiSubtitleEnabled()));
        detail.setContributor(findUser(courseware.getContributorId()));
        detail.setCreatedByName(courseware.getCreatedByName());
        detail.setCreateTime(format(courseware.getCreatedAt()));
        detail.setUpdateTime(format(courseware.getUpdatedAt()));
        detail.setFiles(files(courseware.getId()).stream().map(this::toFileItem).toList());
        detail.setAttachments(attachments(courseware.getId()).stream().map(this::toAttachmentItem).toList());
        return detail;
    }

    /** 新建课件：雪花 id、初始状态草稿、平台编号顺延，创建人姓名经 Dubbo 补齐；同事务写文件与附件行。 */
    @Transactional
    public KngCoursewareDtos.IdStatusResponse create(
            KngCoursewareDtos.SaveRequest request, String userId) {
        validateSave(request);
        KngCourseware courseware = new KngCourseware();
        courseware.setId(IdWorker.getIdStr());
        apply(courseware, request, true);
        courseware.setStatus(1);
        courseware.setOnSale(1);
        courseware.setPlatformNo(nextPlatformNo());
        courseware.setCreatedBy(userId);
        courseware.setCreatedByName(userName(userId));
        courseware.setContributorId(StringUtils.hasText(userId) ? userId : "0");
        courseware.setCreatedAt(LocalDateTime.now());
        courseware.setUpdatedAt(LocalDateTime.now());
        coursewareMapper.insert(courseware);
        replaceFiles(courseware.getId(), request);
        replaceAttachments(courseware.getId(), request);
        return new KngCoursewareDtos.IdStatusResponse(courseware.getId(), courseware.getStatus());
    }

    /** 编辑课件：只允许覆盖可编辑字段，创建信息/状态/平台编号保持不变；同事务重建文件与附件行。 */
    @Transactional
    public KngCoursewareDtos.IdResponse update(String id, KngCoursewareDtos.SaveRequest request, String userId) {
        validateSave(request);
        KngCourseware courseware = require(id);
        Integer originalStatus = courseware.getStatus();
        String originalPlatformNo = courseware.getPlatformNo();
        String originalCreatedBy = courseware.getCreatedBy();
        String originalCreatedByName = courseware.getCreatedByName();
        LocalDateTime originalCreatedAt = courseware.getCreatedAt();
        Integer originalFinishStandard = courseware.getFinishStandardPercent();
        apply(courseware, request, false);
        courseware.setStatus(originalStatus);
        courseware.setPlatformNo(originalPlatformNo);
        courseware.setCreatedBy(originalCreatedBy);
        courseware.setCreatedByName(originalCreatedByName);
        courseware.setCreatedAt(originalCreatedAt);
        courseware.setFinishStandardPercent(originalFinishStandard);
        courseware.setUpdatedAt(LocalDateTime.now());
        coursewareMapper.updateById(courseware);
        replaceFiles(id, request);
        replaceAttachments(id, request);
        return new KngCoursewareDtos.IdResponse(id);
    }

    /** 学习记录分页：部门子树/人员关键词先展开为 id 集合再过滤，行级补齐学员姓名与部门展示名。 */
    public KngCoursewareDtos.StudyRecordResponse studyRecords(
            String coursewareId, int pageNo, int pageSize, KngCoursewareDtos.StudyRecordQuery query) {
        require(coursewareId);
        int safePage = Math.max(1, pageNo);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        KngCoursewareDtos.StudyRecordQuery filter = query == null
                ? new KngCoursewareDtos.StudyRecordQuery(null, null, null, null, null) : query;

        List<String> deptIds = List.of();
        if (StringUtils.hasText(filter.deptId())) {
            if (filter.hasChild() != null && filter.hasChild() == 1) {
                SysDepartmentDTO department = departmentById(filter.deptId());
                if (department == null) {
                    deptIds = List.of(filter.deptId());
                } else {
                    String prefix = department.getDeptPath() + "_";
                    deptIds = orgQueryService.listAllDepartments().stream()
                            .filter(item -> item.getDeptPath().equals(department.getDeptPath())
                                    || item.getDeptPath().startsWith(prefix))
                            .map(item -> String.valueOf(item.getId()))
                            .toList();
                }
            } else {
                deptIds = List.of(filter.deptId());
            }
        }
        List<String> userIds = List.of();
        if (StringUtils.hasText(filter.keyword())) {
            userIds = orgQueryService.listUserIdsByKeyword(filter.keyword().trim()).stream()
                    .map(String::valueOf)
                    .toList();
            if (userIds.isEmpty()) {
                return new KngCoursewareDtos.StudyRecordResponse();
            }
        }

        List<String> resolvedDeptIds = deptIds;
        List<String> resolvedUserIds = userIds;
        long total = studyRecordMapper.countRecordQuery(
                coursewareId, filter, resolvedDeptIds, resolvedUserIds);
        List<KngStudyRecord> pageRecords = studyRecordMapper.selectRecordPage(
                coursewareId, filter, resolvedDeptIds, resolvedUserIds,
                (safePage - 1) * safeSize, safeSize);
        Map<String, SysUserDTO> users = userMap(pageRecords.stream()
                .map(KngStudyRecord::getUserId).distinct().toList());
        Map<String, SysDepartmentDTO> departments = departmentMap(pageRecords.stream()
                .map(KngStudyRecord::getDeptId).filter(StringUtils::hasText).distinct().toList());
        List<KngCoursewareDtos.StudyRecordItem> records = pageRecords.stream()
                .map(record -> toStudyRecordItem(record, users, departments))
                .toList();
        KngCoursewareDtos.StudyRecordResponse response = new KngCoursewareDtos.StudyRecordResponse();
        response.setTotal(total);
        response.setRecords(records);
        return response;
    }

    /** 学习概览：近 7 天学习数 / 累计学习数 / 完课数三项计数。 */
    public KngCoursewareDtos.StudyOverview studyOverview(String coursewareId) {
        require(coursewareId);
        KngReadDtos.StudyOverviewRow counts = studyRecordMapper.selectOverview(
                coursewareId, LocalDateTime.now().minusDays(7));
        return new KngCoursewareDtos.StudyOverview(
                counts.getNumOf7days(),
                counts.getNumOftotal(),
                counts.getNumOfFinish());
    }

    /** 完课用时分布：1 天内 / 1 周内 / 1 月内 / 3 月内 / 更久五档计数。 */
    public KngCoursewareDtos.FinishTimeOverview finishTimeOverview(String coursewareId) {
        require(coursewareId);
        KngReadDtos.FinishTimeRow counts = studyRecordMapper.selectFinishTimeOverview(coursewareId);
        return new KngCoursewareDtos.FinishTimeOverview(
                counts.getNumInOneDay(),
                counts.getNumInOneWeek(),
                counts.getNumInOneMon(),
                counts.getNumInThreeMon(),
                counts.getNumInMoreThrMon());
    }

    /** 学习记录筛选用的一级部门下拉。 */
    public List<KngCoursewareDtos.DeptBrief> deptFirstLevel() {
        return orgQueryService.listFirstLevelDepartments().stream()
                .map(item -> new KngCoursewareDtos.DeptBrief(
                        String.valueOf(item.getId()), item.getDeptName()))
                .toList();
    }

    /** 部门完成度统计：各部门学习/完课计数，按部门 sort 排序输出。 */
    public List<KngCoursewareDtos.DeptFinish> deptFinishOverview(
            String coursewareId, KngCoursewareDtos.DeptFinishRequest request) {
        require(coursewareId);
        List<String> deptIds = request == null || request.deptIds() == null
                ? List.of() : request.deptIds().stream().filter(StringUtils::hasText).distinct().toList();
        List<KngReadDtos.StudyDeptCountRow> counts = studyRecordMapper.selectDeptCounts(coursewareId, deptIds);
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
                .map(item -> new KngCoursewareDtos.DeptFinish(
                        item.getDeptId(),
                        departments.get(item.getDeptId()).getDeptName(),
                        item.getStudyCount(),
                        item.getFinishCount()))
                .toList();
    }

    /** 转码列表（转码中/失败两个 Tab），附带转码中总数角标。 */
    public KngCoursewareDtos.TranscodeResponse transcodeList(
            int pageNo, int pageSize, KngCoursewareDtos.TranscodeQuery query) {
        KngCoursewareDtos.TranscodeQuery filter = query == null
                ? new KngCoursewareDtos.TranscodeQuery("processing", null) : query;
        boolean failed = "failed".equalsIgnoreCase(filter.tab());
        int safePage = Math.max(1, pageNo);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        Integer status = failed ? -1 : 1;
        String keyword = StringUtils.hasText(filter.keyword()) ? filter.keyword().trim() : null;
        long total = coursewareMapper.countTranscode(status, keyword);
        List<KngCourseware> page = coursewareMapper.selectTranscodePage(
                status, keyword, (safePage - 1) * safeSize, safeSize);
        long processingCount = coursewareMapper.countByStatus(1);
        KngCoursewareDtos.TranscodeResponse response = new KngCoursewareDtos.TranscodeResponse();
        response.setTotal(total);
        response.setProcessingCount((int) processingCount);
        response.setRecords(page.stream().map(item -> {
            KngCoursewareDtos.TranscodeItem row = new KngCoursewareDtos.TranscodeItem();
            row.setId(item.getId());
            row.setTitle(item.getTitle());
            row.setCatalogName(item.getCatalogName());
            row.setUploadTime(format(item.getCreatedAt()));
            row.setConvertedSeconds(item.getCreatedAt() == null ? 0
                    : Math.max(0, Duration.between(item.getCreatedAt(), LocalDateTime.now()).toSeconds()));
            row.setCreatedByName(item.getCreatedByName());
            row.setRemark("");
            return row;
        }).toList());
        return response;
    }

    /** 字幕列表：占位实现，返回空集合（媒体能力待办见课件管理模块文档）。 */
    public KngCoursewareDtos.SubtitleResponse subtitleList(KngCoursewareDtos.SubtitleQuery query) {
        KngCoursewareDtos.SubtitleResponse response = new KngCoursewareDtos.SubtitleResponse();
        response.setTotal(0);
        response.setRecords(List.of());
        return response;
    }

    /** 取单条课件，不存在抛"课件不存在"（供本服务与 CoursewareOpService 共用）。 */
    public KngCourseware require(String id) {
        KngCourseware courseware = StringUtils.hasText(id) ? coursewareMapper.selectById(id) : null;
        if (courseware == null) {
            throw new BizException("课件不存在");
        }
        return courseware;
    }

    /** 课件文件行（按 sort,id 升序）。 */
    public List<KngCoursewareFile> files(String coursewareId) {
        return fileMapper.selectList(Wrappers.<KngCoursewareFile>lambdaQuery()
                .eq(KngCoursewareFile::getCoursewareId, coursewareId)
                .orderByAsc(KngCoursewareFile::getSortOrder)
                .orderByAsc(KngCoursewareFile::getId));
    }

    /** 课件附件行（按创建时间,id 升序）。 */
    public List<KngCoursewareAttachment> attachments(String coursewareId) {
        return attachmentMapper.selectList(Wrappers.<KngCoursewareAttachment>lambdaQuery()
                .eq(KngCoursewareAttachment::getCoursewareId, coursewareId)
                .orderByAsc(KngCoursewareAttachment::getCreatedAt)
                .orderByAsc(KngCoursewareAttachment::getId));
    }

    /** 目录子树展开：取全量目录在内存里递归收集（含自身 id），用于"含子目录"筛选。 */
    public List<String> descendantCatalogIds(String catalogId) {
        List<KngCatalog> catalogs = catalogMapper.selectList(Wrappers.<KngCatalog>lambdaQuery());
        Map<String, List<String>> children = catalogs.stream()
                .filter(item -> item.getParentId() != null)
                .collect(Collectors.groupingBy(KngCatalog::getParentId,
                        Collectors.mapping(KngCatalog::getId, Collectors.toList())));
        List<String> result = new ArrayList<>();
        collectCatalog(catalogId, children, result);
        if (result.isEmpty()) {
            result.add(catalogId);
        }
        return result;
    }

    private void collectCatalog(String catalogId, Map<String, List<String>> children, List<String> result) {
        result.add(catalogId);
        for (String child : children.getOrDefault(catalogId, List.of())) {
            collectCatalog(child, children, result);
        }
    }

    private KngCoursewareDtos.ListItem toListItem(KngCourseware item, int studyCount) {
        KngCoursewareDtos.ListItem row = new KngCoursewareDtos.ListItem();
        row.setId(item.getId());
        row.setTitle(item.getTitle());
        row.setType(item.getType());
        row.setTypeDesc(typeDesc(item.getType()));
        row.setCatalogId(item.getCatalogId());
        row.setCatalogName(item.getCatalogName());
        row.setStatus(item.getStatus());
        row.setStatusDesc(statusDesc(item.getStatus()));
        row.setOnSale(item.getOnSale());
        row.setUpdateTime(formatDate(item.getUpdatedAt()));
        row.setStudyCount(studyCount);
        row.setPlatformNo(item.getPlatformNo());
        row.setCoverUrl(item.getCoverUrl());
        row.setCreatedByName(item.getCreatedByName());
        return row;
    }

    private Map<String, Integer> studyCounts(List<String> coursewareIds) {
        if (coursewareIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> result = new HashMap<>();
        for (KngReadDtos.StudyCountRow row : studyRecordMapper.countByCoursewareIds(coursewareIds)) {
            result.put(row.getCoursewareId(), row.getCountValue());
        }
        return result;
    }

    private Map<String, SysUserDTO> userMap(List<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<String, SysUserDTO> result = new HashMap<>();
        for (SysUserDTO user : orgQueryService.listUsersByIds(toLongIds(userIds))) {
            result.put(String.valueOf(user.getId()), user);
        }
        return result;
    }

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

    private SysDepartmentDTO departmentById(String deptId) {
        List<Long> parsed = toLongIds(List.of(deptId));
        return parsed.isEmpty() ? null : orgQueryService.getDepartment(parsed.get(0));
    }

    /** 学习记录/请求里存的用户与部门 id 是字符串，容忍 "0"、空串等非数字值（调用侧自己兜底显示"系统"）。 */
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

    private KngCoursewareDtos.StudyRecordItem toStudyRecordItem(
            KngStudyRecord record,
            Map<String, SysUserDTO> users,
            Map<String, SysDepartmentDTO> departments) {
        KngCoursewareDtos.StudyRecordItem item = new KngCoursewareDtos.StudyRecordItem();
        SysUserDTO user = users.get(record.getUserId());
        SysDepartmentDTO department = departments.get(record.getDeptId());
        item.setUserId(record.getUserId());
        item.setName(user == null ? null : user.getName());
        item.setAccount(user == null ? null : user.getAccount());
        item.setDeptName(department == null ? null : department.getDeptName());
        item.setDeptFullName(department == null ? null
                : department.getDeptPath().replace("_", "->"));
        item.setPost(record.getPost());
        item.setStudyStatus(record.getStudyStatus());
        item.setStudyStatusDesc(Objects.equals(record.getStudyStatus(), 1) ? "已完课" : "学习中");
        item.setStartStudyTime(formatMinute(record.getStartStudyTime()));
        item.setEndStudyTime(formatMinute(record.getEndStudyTime()));
        item.setStudyLenHours(record.getStudyLenHours());
        item.setFinishCostDays(record.getFinishCostDays());
        item.setFinishMethod(record.getFinishMethod());
        item.setFinishMethodDesc(Objects.equals(record.getFinishMethod(), 1) ? "培训"
                : Objects.equals(record.getFinishMethod(), 2) ? "自学" : null);
        return item;
    }

    private KngCoursewareDtos.FileItem toFileItem(KngCoursewareFile file) {
        KngCoursewareDtos.FileItem item = new KngCoursewareDtos.FileItem();
        item.setId(file.getId());
        item.setFileName(file.getFileName());
        item.setFileSize(file.getFileSize());
        item.setLanguage(file.getLanguage());
        item.setStoragePath(file.getStoragePath());
        return item;
    }

    private KngCoursewareDtos.AttachmentItem toAttachmentItem(KngCoursewareAttachment attachment) {
        KngCoursewareDtos.AttachmentItem item = new KngCoursewareDtos.AttachmentItem();
        item.setId(attachment.getId());
        item.setFileName(attachment.getFileName());
        item.setFileSize(attachment.getFileSize());
        item.setStoragePath(attachment.getStoragePath());
        return item;
    }

    private void apply(KngCourseware courseware, KngCoursewareDtos.SaveRequest request, boolean create) {
        courseware.setTitle(request.getTitle().trim());
        courseware.setType(request.getType());
        courseware.setCatalogId(request.getCatalogId());
        courseware.setCatalogName(catalogName(request.getCatalogId()));
        courseware.setStandardMinutes(request.getStandardMinutes() == null ? 0 : request.getStandardMinutes());
        courseware.setStandardSeconds(request.getStandardSeconds() == null ? 0 : request.getStandardSeconds());
        courseware.setStandardDurationFollow(isTrue(request.getStandardDurationFollow()) ? 1 : 0);
        courseware.setStudyScore(request.getStudyScore());
        if (create) {
            courseware.setFinishStandardPercent(request.getFinishStandardPercent() == null
                    ? 100 : request.getFinishStandardPercent());
        }
        courseware.setDescription(request.getDescription());
        courseware.setCoverUrl(request.getCoverStoragePath());
        courseware.setCode(request.getCode());
        courseware.setVersionStr(request.getVersionStr());
        courseware.setViewPermission(request.getViewPermission() == null ? 1 : request.getViewPermission());
        courseware.setDownloadEnabled(isTrue(request.getDownloadEnabled()) ? 1 : 0);
        courseware.setDownloadPermission(request.getDownloadPermission() == null ? 1 : request.getDownloadPermission());
        courseware.setAppSaveEnabled(isTrue(request.getAppSaveEnabled()) ? 1 : 0);
        courseware.setAppSavePermission(request.getAppSavePermission() == null ? 1 : request.getAppSavePermission());
        courseware.setSpeedEnabled(isTrue(request.getSpeedEnabled()) ? 1 : 0);
        courseware.setSpeedHoursCalc(isTrue(request.getSpeedHoursCalc()) ? 1 : 0);
        courseware.setAiSubtitleEnabled(isTrue(request.getAiSubtitleEnabled()) ? 1 : 0);
        String language = request.getFiles() == null ? null : request.getFiles().stream()
                .map(KngCoursewareDtos.SavedFile::getLanguage)
                .filter(StringUtils::hasText)
                .findFirst().orElse(null);
        courseware.setLanguage(language);
    }

    private void validateSave(KngCoursewareDtos.SaveRequest request) {
        if (request == null) {
            throw new BizException("请求参数不能为空");
        }
        String title = request.getTitle() == null ? "" : request.getTitle().trim();
        if (title.isEmpty()) {
            throw new BizException("请输入课件名称");
        }
        if (title.length() > 200) {
            throw new BizException("课件名称不能超过 200 字");
        }
        if (request.getType() == null || !TYPES.contains(request.getType())) {
            throw new BizException("课件类型不正确");
        }
        if (!StringUtils.hasText(request.getCatalogId()) || catalogMapper.selectById(request.getCatalogId()) == null) {
            throw new BizException("请选择所属分类");
        }
        List<KngCoursewareDtos.SavedFile> files = request.getFiles() == null ? List.of() : request.getFiles();
        List<KngCoursewareDtos.SavedFile> links = request.getLinks() == null ? List.of() : request.getLinks();
        if (request.getType() == 8) {
            if (links.isEmpty()) {
                throw new BizException("请添加课件链接");
            }
            for (KngCoursewareDtos.SavedFile link : links) {
                if (!StringUtils.hasText(link.getStoragePath())
                        || !(link.getStoragePath().startsWith("http://") || link.getStoragePath().startsWith("https://"))) {
                    throw new BizException("请输入正确的课件网址");
                }
            }
        } else if (files.isEmpty()) {
            throw new BizException("请上传课件文件");
        }
        for (KngCoursewareDtos.SavedFile file : files) {
            if (!StringUtils.hasText(file.getStoragePath())) {
                throw new BizException("文件上传未完成");
            }
            validateFile(request.getType(), file.getFileName(), file.getFileSize());
        }
        if (request.getAttachmentPaths() != null && request.getAttachmentPaths().size() > 50) {
            throw new BizException("附件最多上传 50 个");
        }
        if (request.getFinishStandardPercent() != null
                && (request.getFinishStandardPercent() < 10
                || request.getFinishStandardPercent() > 100
                || request.getFinishStandardPercent() % 10 != 0)) {
            throw new BizException("完课标准需为 10~100 的整十数");
        }
    }

    private void validateFile(Integer type, String fileName, Long size) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        long bytes = size == null ? 0 : size;
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        if ((type == 6 || type == 7) && !Set.of("zip").contains(extension)) {
            throw new BizException(type == 6 ? "SCORM课件仅支持zip文件" : "HTML课件仅支持zip文件");
        }
        long limit = switch (extension) {
            case "doc", "docx", "ppt", "pptx", "xls", "xlsx", "pps", "pdf" -> 200L * 1024 * 1024;
            case "jpg", "jpeg", "gif", "png", "bmp" -> 100L * 1024 * 1024;
            case "wmv", "mp4", "flv", "avi", "rmvb", "mpg", "mkv", "mov", "mts" -> 5L * 1024 * 1024 * 1024;
            case "m4a", "wma", "wav", "mp3", "amr" -> 1024L * 1024 * 1024;
            case "rar", "zip" -> type == 6 || type == 7 ? 3L * 1024 * 1024 * 1024 : 2L * 1024 * 1024 * 1024;
            default -> 5L * 1024 * 1024 * 1024;
        };
        if (bytes > limit) {
            String label = switch (extension) {
                case "wmv", "mp4", "flv", "avi", "rmvb", "mpg", "mkv", "mov", "mts" -> "视频";
                case "m4a", "wma", "wav", "mp3", "amr" -> "音频";
                case "rar", "zip" -> "压缩";
                default -> "文件";
            };
            throw new BizException(label + "文件不能超过" + readableLimit(limit));
        }
    }

    private String readableLimit(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return (bytes / (1024L * 1024 * 1024)) + "GB";
        return (bytes / (1024L * 1024)) + "MB";
    }

    private void replaceFiles(String coursewareId, KngCoursewareDtos.SaveRequest request) {
        fileMapper.delete(Wrappers.<KngCoursewareFile>lambdaQuery()
                .eq(KngCoursewareFile::getCoursewareId, coursewareId));
        List<KngCoursewareDtos.SavedFile> rows = new ArrayList<>();
        if (request.getType() == 8) {
            rows.addAll(request.getLinks() == null ? List.of() : request.getLinks());
        } else {
            rows.addAll(request.getFiles() == null ? List.of() : request.getFiles());
        }
        for (int index = 0; index < rows.size(); index++) {
            KngCoursewareDtos.SavedFile source = rows.get(index);
            KngCoursewareFile file = new KngCoursewareFile();
            file.setId(IdWorker.getIdStr());
            file.setCoursewareId(coursewareId);
            file.setFileName(StringUtils.hasText(source.getFileName()) ? source.getFileName() : "未命名文件");
            file.setStoragePath(source.getStoragePath());
            file.setFileSize(source.getFileSize() == null ? 0 : source.getFileSize());
            file.setLanguage(source.getLanguage());
            file.setSortOrder(index);
            file.setCreatedAt(LocalDateTime.now());
            fileMapper.insert(file);
        }
    }

    private void replaceAttachments(String coursewareId, KngCoursewareDtos.SaveRequest request) {
        attachmentMapper.delete(Wrappers.<KngCoursewareAttachment>lambdaQuery()
                .eq(KngCoursewareAttachment::getCoursewareId, coursewareId));
        for (KngCoursewareDtos.SavedAttachment source :
                request.getAttachmentPaths() == null ? List.<KngCoursewareDtos.SavedAttachment>of() : request.getAttachmentPaths()) {
            if (source == null || !StringUtils.hasText(source.getStoragePath())) continue;
            KngCoursewareAttachment attachment = new KngCoursewareAttachment();
            attachment.setId(IdWorker.getIdStr());
            attachment.setCoursewareId(coursewareId);
            attachment.setFileName(StringUtils.hasText(source.getFileName()) ? source.getFileName() : "附件");
            attachment.setStoragePath(source.getStoragePath());
            attachment.setFileSize(source.getFileSize() == null ? 0 : source.getFileSize());
            attachment.setCreatedAt(LocalDateTime.now());
            attachmentMapper.insert(attachment);
        }
    }

    private BigDecimal studyScoreCalc(KngCourseware courseware) {
        if (courseware.getStudyScore() != null) {
            return courseware.getStudyScore();
        }
        KngSettingDtos.ScoreConf scoreConf = readScoreConf();
        BigDecimal minutes = BigDecimal.valueOf(
                (courseware.getStandardMinutes() == null ? 0 : courseware.getStandardMinutes())
                        + (courseware.getStandardSeconds() == null ? 0 : courseware.getStandardSeconds()) / 60.0);
        if (Objects.equals(courseware.getType(), 8)) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return switch (courseware.getType() == null ? 0 : courseware.getType()) {
            case 2, 3 -> calculate(minutes, scoreConf.videoAudio());
            case 1, 4 -> calculateDocument(minutes, scoreConf.document());
            case 5 -> calculate(minutes, scoreConf.microcourse().standardMinutes(), scoreConf.microcourse().score());
            case 6 -> calculate(minutes, scoreConf.scorm());
            case 7 -> calculate(minutes, scoreConf.html());
            default -> BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        };
    }

    private BigDecimal calculate(BigDecimal minutes, KngSettingDtos.StandardRule rule) {
        if (rule == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return calculate(minutes, rule.standardMinutes(), rule.score());
    }

    private BigDecimal calculateDocument(BigDecimal minutes, KngSettingDtos.DocumentRule rule) {
        if (rule == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return calculate(minutes, rule.standardMinutes(), rule.score());
    }

    private BigDecimal calculate(BigDecimal minutes, Integer standardMinutes, BigDecimal score) {
        if (standardMinutes == null || standardMinutes <= 0 || score == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return minutes.divide(BigDecimal.valueOf(standardMinutes), 6, RoundingMode.HALF_UP)
                .multiply(score)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private KngSettingDtos.ScoreConf readScoreConf() {
        KngSetting setting = settingMapper.selectById(1);
        if (setting == null || !StringUtils.hasText(setting.getScoreConf())) {
            return new KngSettingDtos.ScoreConf(
                    new KngSettingDtos.StandardRule(10, BigDecimal.ONE, false),
                    new KngSettingDtos.DocumentRule(1, new BigDecimal("0.1"), 1, new BigDecimal("6"), false),
                    new KngSettingDtos.MicrocourseRule(10, BigDecimal.ONE, new BigDecimal("10"), false),
                    new KngSettingDtos.StandardRule(10, BigDecimal.ONE, false),
                    new KngSettingDtos.StandardRule(10, BigDecimal.ONE, false),
                    0);
        }
        try {
            return JSON.readValue(setting.getScoreConf(), KngSettingDtos.ScoreConf.class);
        } catch (JsonProcessingException exception) {
            throw new BizException("课程学分策略解析失败");
        }
    }

    private String catalogName(String catalogId) {
        KngCatalog catalog = catalogMapper.selectById(catalogId);
        return catalog == null ? "" : catalog.getName();
    }

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

    private KngCoursewareDtos.Contributor findUser(String userId) {
        KngCoursewareDtos.Contributor contributor = new KngCoursewareDtos.Contributor();
        contributor.setUserId(userId);
        if (!StringUtils.hasText(userId)) {
            contributor.setName("系统");
            contributor.setAccount("--");
            return contributor;
        }
        List<SysUserDTO> rows = userMap(List.of(userId)).values().stream().toList();
        if (rows.isEmpty()) {
            contributor.setName("系统");
            contributor.setAccount("--");
        } else {
            contributor.setName(rows.get(0).getName());
            contributor.setAccount(rows.get(0).getAccount());
        }
        return contributor;
    }

    private String userName(String userId) {
        if (!StringUtils.hasText(userId)) return "系统";
        List<SysUserDTO> users = orgQueryService.listUsersByIds(toLongIds(List.of(userId)));
        return users.isEmpty() ? "系统" : users.get(0).getName();
    }

    private LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw new BizException("筛选日期格式不正确");
        }
    }

    private String typeDesc(Integer type) {
        return switch (type == null ? 0 : type) {
            case 1 -> "文档";
            case 2 -> "视频";
            case 3 -> "音频";
            case 4 -> "压缩文件";
            case 5 -> "微课";
            case 6 -> "SCORM";
            case 7 -> "HTML";
            case 8 -> "外链课";
            default -> "未知";
        };
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

    private boolean isTrue(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private String format(LocalDateTime value) {
        return value == null ? null : DATE_TIME.format(value);
    }

    private String formatDate(LocalDateTime value) {
        return value == null ? null : DATE.format(value);
    }

    private String formatMinute(LocalDateTime value) {
        return value == null ? null : MINUTE_TIME.format(value);
    }

    private synchronized String nextPlatformNo() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        int count = coursewareMapper.countPlatformNoByPrefix("ZXKT" + day);
        return "ZXKT" + day + String.format("%04d", count + 1);
    }
}
