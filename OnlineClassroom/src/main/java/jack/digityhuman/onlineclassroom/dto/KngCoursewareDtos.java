package jack.digityhuman.onlineclassroom.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class KngCoursewareDtos {

    private KngCoursewareDtos() {
    }

    public record ListQuery(
            String keyword,
            Integer type,
            Integer status,
            String catalogId,
            Integer includeChildCatalog,
            Integer onSale,
            Integer quoted,
            String uploadTimeStart,
            String uploadTimeEnd,
            String updateTimeStart,
            String updateTimeEnd) {
    }

    @Data
    public static class ListItem {
        private String id;
        private String title;
        private Integer type;
        private String typeDesc;
        private String catalogId;
        private String catalogName;
        private Integer status;
        private String statusDesc;
        private Integer onSale;
        private String updateTime;
        private Integer studyCount;
        private String platformNo;
        private String coverUrl;
        private String createdByName;
    }

    @Data
    public static class ListResponse {
        private long total;
        private List<ListItem> records = new ArrayList<>();
    }

    @Data
    public static class FileItem {
        private String id;
        private String fileName;
        private Long fileSize;
        private String language;
        private String storagePath;
    }

    @Data
    public static class AttachmentItem {
        private String id;
        private String fileName;
        private Long fileSize;
        private String storagePath;
    }

    @Data
    public static class Contributor {
        private String userId;
        private String name;
        private String account;
    }

    @Data
    public static class Detail {
        private String id;
        private String title;
        private Integer type;
        private String typeDesc;
        private String catalogId;
        private String catalogPath;
        private Integer status;
        private String platformNo;
        private Integer standardMinutes;
        private Integer standardSeconds;
        private Boolean standardDurationFollow;
        private BigDecimal studyScore;
        private BigDecimal studyScoreCalc;
        private Integer finishStandardPercent;
        private String description;
        private String coverUrl;
        private String code;
        private String versionStr;
        private Integer viewPermission;
        private Boolean downloadEnabled;
        private Integer downloadPermission;
        private Boolean appSaveEnabled;
        private Integer appSavePermission;
        private Boolean speedEnabled;
        private Boolean speedHoursCalc;
        private Boolean aiSubtitleEnabled;
        private Contributor contributor;
        private String createdByName;
        private String createTime;
        private String updateTime;
        private List<FileItem> files = new ArrayList<>();
        private List<AttachmentItem> attachments = new ArrayList<>();
    }

    public record StudyRecordQuery(
            String deptId,
            Integer hasChild,
            Integer studyStatus,
            Integer finishMethod,
            String keyword) {
    }

    @Data
    public static class StudyRecordItem {
        private String userId;
        private String name;
        private String account;
        private String deptName;
        private String deptFullName;
        private String post;
        private Integer studyStatus;
        private String studyStatusDesc;
        private String startStudyTime;
        private String endStudyTime;
        private BigDecimal studyLenHours;
        private Integer finishCostDays;
        private Integer finishMethod;
        private String finishMethodDesc;
    }

    @Data
    public static class StudyRecordResponse {
        private long total;
        private List<StudyRecordItem> records = new ArrayList<>();
    }

    public record StudyOverview(
            int numOf7days,
            int numOftotal,
            int numOfFinish) {
    }

    public record FinishTimeOverview(
            int numInOneDay,
            int numInOneWeek,
            int numInOneMon,
            int numInThreeMon,
            int numInMoreThrMon) {
    }

    public record DeptBrief(String id, String name) {
    }

    public record DeptFinishRequest(List<String> deptIds) {
    }

    public record DeptFinish(
            String deptId,
            String deptName,
            int numOfStudyPeo,
            int numOfFinishPeo) {
    }

    @Data
    public static class SavedFile {
        private String storagePath;
        private String fileName;
        private Long fileSize;
        private String language;
    }

    @Data
    public static class SavedAttachment {
        private String storagePath;
        private String fileName;
        private Long fileSize;
    }

    @Data
    public static class SaveRequest {
        private String title;
        private Integer type;
        private String catalogId;
        private Integer standardMinutes;
        private Integer standardSeconds;
        private Boolean standardDurationFollow;
        private BigDecimal studyScore;
        private Integer finishStandardPercent;
        private String description;
        private String coverStoragePath;
        private String code;
        private String versionStr;
        private Integer viewPermission;
        private Boolean downloadEnabled;
        private Integer downloadPermission;
        private Boolean appSaveEnabled;
        private Integer appSavePermission;
        private Boolean speedEnabled;
        private Boolean speedHoursCalc;
        private Boolean aiSubtitleEnabled;
        private List<SavedFile> files = new ArrayList<>();
        private List<SavedFile> links = new ArrayList<>();
        private List<SavedAttachment> attachmentPaths = new ArrayList<>();
    }

    public record IdResponse(String id) {
    }

    public record IdStatusResponse(String id, int status) {
    }

    public record IdsRequest(List<String> ids) {
    }

    public record MoveRequest(List<String> ids, String targetCatalogId) {
    }

    public record CopyRequest(String targetCatalogId) {
    }

    public record CountResponse(int count) {
    }

    public record TranscodeQuery(String tab, String keyword) {
    }

    @Data
    public static class TranscodeItem {
        private String id;
        private String title;
        private String catalogName;
        private String uploadTime;
        private long convertedSeconds;
        private String createdByName;
        private String remark;
    }

    @Data
    public static class TranscodeResponse {
        private long total;
        private int processingCount;
        private List<TranscodeItem> records = new ArrayList<>();
    }

    public record SubtitleQuery(String tab, String keyword) {
    }

    @Data
    public static class SubtitleResponse {
        private long total;
        private List<Object> records = new ArrayList<>();
    }

    public record DownloadRecordResponse(long total, List<Object> records) {
    }
}
