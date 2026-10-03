package jack.digityhuman.onlineclassroom.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 媒体处理专项 DTO 容器（转码/字幕管理页、播放与字幕读写接口，AI字幕与转码专项 03 §2 契约）。
 */
public final class KngMediaDtos {

    private KngMediaDtos() {
    }

    /** 媒体任务分页查询请求（03 §2 FP-MT-04/05）：type=1 转码 / 2 字幕；tab=processing（pending+running）/failed。 */
    public record JobsQuery(Integer type, String tab, String keyword) {
    }

    /** 媒体任务行（字段与 03 §2 响应示例逐字对齐；uploadTime 为课件上传时间，供"上传日期"列的兼容扩展）。 */
    @lombok.Data
    public static class JobItem {
        private String id;
        private Integer jobType;
        private Integer status;
        private String stage;
        private String coursewareId;
        private String coursewareTitle;
        private String catalogName;
        private String error;
        private Integer retryCount;
        /** 操作人展示名（取课件创建人姓名快照，与字幕管理页"操作人"列口径一致） */
        private String createdByName;
        private String createdAt;
        private String startedAt;
        private String finishedAt;
        /** running 时前端按 now-startedAt 自算"已转换时间"；finished 后为定值 */
        private Long elapsedSeconds;
        /** 课件上传时间（转码管理页"上传日期"列） */
        private String uploadTime;
    }

    @lombok.Data
    public static class JobsResponse {
        private long total;
        private List<JobItem> records = new ArrayList<>();
    }

    /** MT-06 播放地址响应：m3u8 相对路径 + 标准时长秒数（时长兜底链路的展示值）。 */
    public record PlayResponse(String playUrl, Integer durationSeconds) {
    }

    /** 单条字幕条目（SRT 一行的时间戳与文本；时间戳格式 HH:MM:SS,mmm）。 */
    public record SubtitleEntry(int index, String beginTime, String endTime, String text) {
    }

    /** MT-05a 读取字幕响应：未生成时 subtitlesFlag=false、languages 与 entries 为空。 */
    @lombok.Data
    public static class SubtitlesResponse {
        private boolean subtitlesFlag;
        private List<String> languages = new ArrayList<>();
        private Map<String, List<SubtitleEntry>> entries = new LinkedHashMap<>();
    }

    /** MT-05b 保存字幕请求：整语言 SRT 重写。 */
    public record SubtitleSaveRequest(List<SubtitleEntry> entries) {
    }
}
