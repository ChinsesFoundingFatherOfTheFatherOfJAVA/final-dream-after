package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.dto.KngAiSourceDtos;

/**
 * 在线课堂 AI 素材源服务（AI 设计助手 / AI 出题接入，2026-10-28）
 * <p>
 * 职责边界：本服务只做"查已发布文档课件"和"把某个课件的文档源文件读成文本"，
 * 不关心谁在消费（设计助手存 act_ai_material / 出题写 materialText），
 * 也不把磁盘路径透出给调用方。
 */
public interface KngAiSourceService {

    /**
     * 素材源分页列表。
     *
     * @param pageNo   页码（从 1 开始）
     * @param pageSize 每页条数（1~100）
     * @param orderBy  排序字段：updateTime / credits / duration
     * @param orderType asc / desc
     * @param query    筛选条件（purpose 不参与筛选）
     * @return 真实 total + 当前页记录
     */
    KngAiSourceDtos.ListResponse list(int pageNo, int pageSize, String orderBy, String orderType,
                                      KngAiSourceDtos.ListQuery query);

    /**
     * 抽取某课件的文档正文。
     *
     * @param coursewareId 课件 id
     * @param purpose      designAssistant（3 万字）/ question（2 万字）
     * @return 抽取结果（纯文本，无磁盘路径）
     */
    KngAiSourceDtos.ExtractResult extract(String coursewareId, String purpose);

    /** 用途枚举 → 抽取字数上限；未知用途按设计助手的 3 万字处理（宽上限不会让内容变多） */
    int maxCharsOf(String purpose);
}
