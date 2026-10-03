package jack.digityhuman.onlineclassroom.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 课程管理模块 DTO 容器（嵌套 record/类风格，参照 KngCoursewareDtos）。
 *
 * <p>列表/详情/学习数据形状与课件模块同构（03-接口文档），差异点：
 * 无 type/quoted 筛选、ListItem 带 hidden 与 structureType、studyCount 恒 0（kng_study_record 无课程维度）。
 * 学习记录查询/响应直接复用 {@link KngCoursewareDtos.StudyRecordQuery}/{@link KngCoursewareDtos.StudyRecordResponse}。
 */
public final class KngCourseDtos {

    private KngCourseDtos() {
    }

    /** 课程列表查询条件（同课件列表去掉 type 与"是否被引用"——课程无被引用概念）。 */
    public record ListQuery(
            String keyword,
            Integer status,
            String catalogId,
            Integer includeChildCatalog,
            Integer onSale,
            String uploadTimeStart,
            String uploadTimeEnd,
            String updateTimeStart,
            String updateTimeEnd) {
    }

    /** 课程列表行。 */
    @Data
    public static class ListItem {
        private String id;
        private String title;
        private String catalogId;
        private String catalogName;
        private Integer status;
        private String statusDesc;
        private Integer onSale;
        /** 隐藏标记（独立于 status 的置灰标记，与课件模块同口径） */
        private Boolean hidden;
        private String updateTime;
        /** 学习次数恒 0：kng_study_record 只有课件维度，课程列表学习数据 tab 预期空态 */
        private Integer studyCount;
        private String platformNo;
        private String coverUrl;
        private String createdByName;
        /** 课程结构 0无章节 1有章节 */
        private Integer structureType;
    }

    /** 课程列表分页响应（与课件列表同构：records/total）。 */
    @Data
    public static class ListResponse {
        private long total;
        private List<ListItem> records = new ArrayList<>();
    }

    /** 贡献者展示信息（用户数据经 OrgQueryService Dubbo 补齐，不直读 sys_user）。 */
    @Data
    public static class Contributor {
        private String userId;
        private String name;
        private String account;
    }

    /** 课程详情（基础信息 tab；时长/学分为任务聚合值，附件恒空数组）。 */
    @Data
    public static class Detail {
        private String id;
        private String title;
        private String catalogId;
        /** 分类全路径（parentPath 拼装，参照课件详情） */
        private String catalogPath;
        private Integer status;
        private String platformNo;
        /** 标准时长-分（有任务=聚合换算，无任务=手填值） */
        private Integer standardMinutes;
        /** 标准时长-秒 */
        private Integer standardSeconds;
        /** 标准时长聚合值（秒）=Σ(课件 standardMinutes*60+standardSeconds)；无任务时=手填值换算 */
        private Long standardDurationCalc;
        /** 单独学分；null=跟随 */
        private BigDecimal studyScore;
        /** 学分展示值：有任务=Σ课件 studyScoreCalc；无任务且未单独设置时返回 null（待定，见实现注释） */
        private BigDecimal studyScoreCalc;
        private Integer finishStandardPercent;
        private String description;
        private String coverUrl;
        private String code;
        private String versionStr;
        private Integer viewPermission;
        private Boolean speedEnabled;
        private Boolean speedHoursCalc;
        private Contributor contributor;
        private String createdByName;
        private Integer structureType;
        private Integer unlockMode;
        private Integer unlockUnit;
        /** 附件恒空数组：04 DDL 的 kng_course 无附件存储，表单的 attachments 字段不持久化 */
        private List<Object> attachments = new ArrayList<>();
    }

    /** 章节学习统计分组（学习详情 tab；无章节课程 chapterId/chapterName 均为 null）。 */
    @Data
    public static class ChapterStudyStats {
        private String chapterId;
        private String chapterName;
        private List<TaskStudyStats> tasks = new ArrayList<>();
    }

    /** 章节内单任务的课件学习统计（近 7 天/总数，口径沿用学习概览的 start_study_time）。 */
    @Data
    public static class TaskStudyStats {
        private String taskName;
        private String coursewareId;
        private Integer coursewareType;
        private Integer numOfPastweek;
        private Integer numOfTotal;
    }

    /** 学习概览三项计数（与课件模块同构）。 */
    public record StudyOverview(
            int numOf7days,
            int numOftotal,
            int numOfFinish) {
    }

    /** 完课用时分布五档（与课件模块同构）。 */
    public record FinishTimeOverview(
            int numInOneDay,
            int numInOneWeek,
            int numInOneMon,
            int numInThreeMon,
            int numInMoreThrMon) {
    }

    /** 一级部门下拉项（与课件模块同构）。 */
    public record DeptBrief(String id, String name) {
    }

    /** 部门完成度请求（与课件模块同构）。 */
    public record DeptFinishRequest(List<String> deptIds) {
    }

    /** 部门完成度行（与课件模块同构）。 */
    public record DeptFinish(
            String deptId,
            String deptName,
            int numOfStudyPeo,
            int numOfFinishPeo) {
    }

    /** 创建课程请求（CS-08，点"下一步"时创建未发布课程记录；创建端点由 CourseOp 侧实现，本 DTO 先定义契约）。 */
    @Data
    public static class CreateRequest {
        private String title;
        private String catalogId;
        private Integer finishStandardPercent;
        private Integer viewPermission;
        private Integer structureType;
    }

    /** 课程附件（仅表单传输用）：04 DDL 的 kng_course 无附件存储列，后端接收后不持久化，详情恒返回空数组。 */
    public record SavedAttachment(
            String storagePath,
            String fileName,
            Long fileSize) {
    }

    /** 课程隐藏/取消隐藏请求（CS-09；hidden 与 status 上下架互不影响的独立双标记）。 */
    public record HiddenRequest(Boolean hidden) {
    }

    /** 设计器保存-单任务（CS-07a；coursewareId 必须存在于 kng_courseware，已下架允许）。 */
    public record DesignerSaveTask(
            String coursewareId,
            String taskName,
            Boolean hidden) {
    }

    /** 设计器保存-单章节（CS-07a；id 为旧章节 id，保存全删全插后不沿用，仅前端回显用）。 */
    public record DesignerSaveChapter(
            String id,
            String name,
            String description,
            List<DesignerSaveTask> tasks) {
    }

    /** 设计器保存请求（CS-07a，保存/暂存共用；publish=false 时课程状态保持不变）。 */
    public record DesignerSaveRequest(
            Boolean publish,
            Integer structureType,
            Integer unlockMode,
            Integer unlockUnit,
            List<DesignerSaveChapter> chapters,
            List<DesignerSaveTask> flatTasks) {
    }

    /** 设计器-任务行（读取响应；credits/durationText/coursewareStatus/coursewareType 为 join 课件实时值）。 */
    @Data
    public static class DesignerTask {
        private String id;
        private String coursewareId;
        private String taskName;
        private Boolean hidden;
        /** 课件学分展示值（studyScoreCalc），课件已物理删除时为 null */
        private BigDecimal credits;
        /** 课件时长展示文案（"X 分 Y 秒"），课件已物理删除时为 null */
        private String durationText;
        /** 课件状态（实时），课件已物理删除时为 null */
        private Integer coursewareStatus;
        /** 课件类型（实时），课件已物理删除时为 null */
        private Integer coursewareType;
    }

    /** 设计器-章节行（读取响应；无章节课程 chapters 为空数组）。 */
    @Data
    public static class DesignerChapter {
        private String id;
        private String name;
        private String description;
        private List<DesignerTask> tasks = new ArrayList<>();
    }

    /** 设计器结构读取响应（CS-07a；无章节课程 flatTasks 生效、chapters=[]）。 */
    @Data
    public static class DesignerView {
        private Integer structureType;
        private Integer unlockMode;
        private Integer unlockUnit;
        private List<DesignerChapter> chapters = new ArrayList<>();
        private List<DesignerTask> flatTasks = new ArrayList<>();
    }

    /** 第一步基本信息保存请求（CS-07，编辑/创建共用；保存端点由 CourseOp 侧实现，本 DTO 先定义契约）。 */
    @Data
    public static class BasicSaveRequest {
        private String title;
        private String catalogId;
        private Boolean standardDurationFollow;
        private Integer standardMinutes;
        private Integer standardSeconds;
        private BigDecimal studyScore;
        private Integer finishStandardPercent;
        private String description;
        /** 封面上传后的存储路径 */
        private String coverStoragePath;
        private String code;
        private String versionStr;
        private Integer viewPermission;
        private Boolean speedEnabled;
        private Boolean speedHoursCalc;
        private String contributorId;
        /** 仅透传给前端回显用途，后端不持久化（kng_course 无附件存储列） */
        private List<SavedAttachment> attachments = new ArrayList<>();
    }
}
