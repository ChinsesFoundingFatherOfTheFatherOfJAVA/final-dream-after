package jack.digityhuman.onlineclassroom.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 素材源 DTO 容器（AI 设计助手 / AI 出题接入在线课堂，2026-10-28）
 * <p>
 * 只描述"已发布、已上架、文档类且存在文档源文件"的课件，供跨模块（AtualCombatTraining 设计助手、
 * ExamService AI 出题）选择素材与抽取正文。字段口径见《在线课堂资源选择接入\03-接口文档.md》§1。
 * <p>
 * 刻意不返回 {@code storagePath}：源文件磁盘位置是 OnlineClassroom 的内部实现，跨模块只需要文本。
 */
public final class KngAiSourceDtos {

    private KngAiSourceDtos() {
    }

    /**
     * 列表查询条件。
     *
     * @param purpose            用途：designAssistant（上限 3 万字）/ question（上限 2 万字）；仅影响抽取上限，列表条件一致
     * @param keyword            课件名称/分类名/创建人模糊查询
     * @param type               课件类型；首期只接受 1（文档），其余类型即便传入也会被固定条件挡掉
     * @param catalogId          分类 id
     * @param includeChildCatalog 1=含子分类（默认），0=仅本分类
     * @param createTimeStart    创建时间起（yyyy-MM-dd）
     * @param createTimeEnd      创建时间止（yyyy-MM-dd，含当天）
     * @param includeHidden      是否包含隐藏课件，默认 true（原平台选中隐藏课仍可使用）
     */
    public record ListQuery(
            String purpose,
            String keyword,
            Integer type,
            String catalogId,
            Integer includeChildCatalog,
            String createTimeStart,
            String createTimeEnd,
            Boolean includeHidden) {
    }

    /** 列表项（含第一个文档源文件信息，供前端展示与抽取前判空） */
    @Data
    public static class ListItem {
        private String id;
        private String title;
        /** 首期固定 1 */
        private Integer type;
        /** 首期固定"文档" */
        private String typeDesc;
        private String catalogId;
        private String catalogName;
        private BigDecimal credits;
        /** 课时文案，如 10分0秒 */
        private String durationText;
        private String createdByName;
        private String createTime;
        private String updateTime;
        /** 本系统无过期字段，恒为 null（前端显示 --） */
        private String expirationDate;
        private Boolean hidden;
        private String sourceFileId;
        private String sourceFileName;
        private String sourceFileType;
    }

    /** 列表响应（total 为真实总数，与分页无关） */
    @Data
    public static class ListResponse {
        private long total;
        private List<ListItem> records = new ArrayList<>();
    }

    /** 抽取结果（纯文本，不含磁盘路径） */
    @Data
    public static class ExtractResult {
        private String coursewareId;
        private String coursewareTitle;
        private String sourceFileId;
        private String fileName;
        private String fileType;
        private Long fileSize;
        private String text;
        private int chars;
        /** 是否因超出用途上限被截断 */
        private boolean truncated;
    }
}
