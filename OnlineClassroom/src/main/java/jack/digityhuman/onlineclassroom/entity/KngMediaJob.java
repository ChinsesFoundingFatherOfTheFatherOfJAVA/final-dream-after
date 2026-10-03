package jack.digityhuman.onlineclassroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 媒体处理任务（kng_media_job，AI字幕与转码专项 03 §4 DDL）。
 *
 * <p>转码（job_type=1）与 AI 字幕（job_type=2）共用一张表：落库保证进程重启后 pending 可恢复，
 * 也是转码/字幕两个管理页的数据源；发布状态机由本表驱动（取代原 PublishSimulateJob 模拟定时）。
 */
@Data
@TableName("kng_media_job")
public class KngMediaJob {

    /** job_type：转码 */
    public static final int TYPE_TRANSCODE = 1;
    /** job_type：AI 字幕 */
    public static final int TYPE_SUBTITLE = 2;

    /** status：待处理 */
    public static final int STATUS_PENDING = 0;
    /** status：处理中 */
    public static final int STATUS_RUNNING = 1;
    /** status：成功 */
    public static final int STATUS_SUCCESS = 2;
    /** status：失败 */
    public static final int STATUS_FAILED = 3;

    /** 字幕管线 stage：ASR 转写 */
    public static final String STAGE_ASR = "asr";
    /** 字幕管线 stage：简中 SRT 生成 */
    public static final String STAGE_SRT = "srt";
    /** 字幕管线 stage：多语言翻译 */
    public static final String STAGE_TRANSLATE = "translate";

    /** 重试次数上限（手动重试与僵死回收共用口径） */
    public static final int RETRY_LIMIT = 3;

    @TableId(type = IdType.INPUT)
    private String id;
    /** 1 transcode 2 subtitle */
    private Integer jobType;
    private String coursewareId;
    /** 0 pending 1 running 2 success 3 failed */
    private Integer status;
    /** subtitle 管线细分：asr/srt/translate（transcode 为 NULL）；重试时从断点续跑 */
    private String stage;
    /** 翻译阶段已完成语言 CSV（如 "zh,ha"），断点续翻不重翻已完成语言 */
    private String progressLang;
    /** 失败原因（ffmpeg stderr 尾部 500 字符 / 异常消息） */
    private String error;
    /** 已重试次数，上限 {@link #RETRY_LIMIT} */
    private Integer retryCount;
    /** 操作人（投递 job 的用户 id；字幕管理页"操作人"列的取数依据） */
    private String createdBy;
    /** 开始处理时间；转码管理页"已转换时间"= now-started_at */
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** 投递时间；字幕管理页"开始时间"列 */
    private LocalDateTime createdAt;
}
