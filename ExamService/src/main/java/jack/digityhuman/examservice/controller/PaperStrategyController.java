package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.examservice.dto.req.PaperStrategyDtos;
import jack.digityhuman.examservice.dto.resp.PaperStrategyRespDtos;
import jack.digityhuman.examservice.service.PaperStrategyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 随机卷抽题策略接口（07 §3：/api/exam/paper/{id}/strategy*）。 */
@RestController
@RequestMapping("/api/exam/paper")
@RequiredArgsConstructor
public class PaperStrategyController {

    private final PaperStrategyService paperStrategyService;

    /** 3.1 策略矩阵（MaxQty 实时重算）。 */
    @GetMapping("/{id}/strategy")
    public ApiResponse<List<PaperStrategyRespDtos.StrategyItem>> list(@PathVariable String id) {
        return ApiResponse.success(paperStrategyService.list(id));
    }

    /** 3.2 设置抽题来源（全量覆盖）。 */
    @PostMapping("/{id}/strategy/source")
    public ApiResponse<Void> setSource(@PathVariable String id, @RequestBody PaperStrategyDtos.SourceReq req) {
        paperStrategyService.setSource(id, req);
        return ApiResponse.success(null);
    }

    /** 3.3 单元格更新（失焦触发）。 */
    @PutMapping("/{id}/strategy/cell")
    public ApiResponse<Void> updateCell(@PathVariable String id, @RequestBody PaperStrategyDtos.CellReq req) {
        paperStrategyService.updateCell(id, req);
        return ApiResponse.success(null);
    }

    /** 3.4 批量单元格更新（快速抽题/快速设置策略用）。 */
    @PostMapping("/{id}/strategy/batch-cell")
    public ApiResponse<Void> batchCell(@PathVariable String id, @RequestBody PaperStrategyDtos.BatchCellReq req) {
        paperStrategyService.batchCell(id, req);
        return ApiResponse.success(null);
    }

    /** 3.4 汇总（题数 + 总分）。 */
    @GetMapping("/{id}/strategy/summary")
    public ApiResponse<PaperStrategyRespDtos.Summary> summary(@PathVariable String id) {
        return ApiResponse.success(paperStrategyService.summary(id));
    }
}