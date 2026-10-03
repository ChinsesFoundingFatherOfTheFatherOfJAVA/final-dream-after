package jack.digityhuman.onlineclassroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 在线课堂课程主表（kng_course），字段口径以《04-建表与种子数据.sql》为唯一权威。
 *
 * <p>课程与课件分表存储（课件/上传相关字段对课程无意义），列表/详情接口形态与课件保持同构。
 * 标准时长/学分存在"跟随策略"语义：展示值优先取任务聚合（见 CourseServiceImpl.detail），
 * 本表冗余存手填值。hidden 为独立隐藏标记，与 status（上下架状态机）互不影响。
 */
@Data
@TableName("kng_course")
public class KngCourse {

    /** 雪花 ID（字符串主键，IdWorker.getIdStr() 生成） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 名称（创建默认"未命名课程"） */
    private String title;

    /** 所属分类 kng_catalog.id */
    private String catalogId;

    /** 0未发布 1发布中 2已发布 3已下架（原平台 5→0、4→2） */
    private Integer status;

    /** 上架位（同课件语义） */
    private Integer onSale;

    /** 隐藏标记（独立于 status，学员不可见，列表置灰） */
    private Integer hidden;

    /** 课程结构 0无章节 1有章节（可切换） */
    private Integer structureType;

    /** 解锁模式 1自由学习 2按顺序学习 */
    private Integer unlockMode;

    /** 解锁单元 1按内容 2按章节（unlockMode=2 时生效） */
    private Integer unlockUnit;

    /** 标准时长 0单独 1跟随学分策略（表单存储；展示用任务聚合） */
    private Integer standardDurationFollow;

    /** 标准时长-分（手填，展示优先任务聚合） */
    private Integer standardMinutes;

    /** 标准时长-秒 */
    private Integer standardSeconds;

    /** 单独学分；NULL=跟随（展示=任务学分和） */
    private BigDecimal studyScore;

    /** 完课标准 10~100 */
    private Integer finishStandardPercent;

    /** 简介（富文本） */
    private String description;

    /** 封面地址 */
    private String coverUrl;

    /** 编码 */
    private String code;

    /** 版本号 */
    private String versionStr;

    /** 平台课程编号（ZXKTyyyyMMddNNNN） */
    private String platformNo;

    /** 浏览权限 1跟随分类 2单独允许浏览 */
    private Integer viewPermission;

    /** 倍速播放（受全局 playConf 压制） */
    private Integer speedEnabled;

    /** 学时倍速计算 */
    private Integer speedHoursCalc;

    /** 贡献者 sys_user.id（字符串存储，跨界转换只在边界做） */
    private String contributorId;

    /** 分类名冗余（搜索用） */
    private String catalogName;

    /** 创建人 id */
    private String createdBy;

    /** 创建人姓名冗余（搜索用） */
    private String createdByName;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
