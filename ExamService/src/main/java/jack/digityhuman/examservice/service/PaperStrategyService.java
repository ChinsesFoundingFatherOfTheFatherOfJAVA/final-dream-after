package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.dto.req.PaperStrategyDtos;
import jack.digityhuman.examservice.dto.resp.PaperStrategyRespDtos;

import java.util.List;

/** 随机卷抽题策略服务（FP-08/09，07 §3）。 */
public interface PaperStrategyService {

    /** 3.1 策略矩阵（MaxQty 每次请求按实时库存重算）。 */
    List<PaperStrategyRespDtos.StrategyItem> list(String paperId);

    /** 3.2 设置抽题来源（全量覆盖；已发布卷拒绝 40050）。 */
    void setSource(String paperId, PaperStrategyDtos.SourceReq req);

    /** 3.3 单元格更新（Qty 类校验 ≤ MaxQty，超出 40060）。 */
    void updateCell(String paperId, PaperStrategyDtos.CellReq req);

    /** 3.4 批量单元格更新。 */
    void batchCell(String paperId, PaperStrategyDtos.BatchCellReq req);

    /** 3.4 汇总（题数 + 总分，按当前 quesMode 生效字段）。 */
    PaperStrategyRespDtos.Summary summary(String paperId);
}