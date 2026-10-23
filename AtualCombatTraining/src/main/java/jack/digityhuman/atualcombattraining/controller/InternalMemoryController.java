package jack.digityhuman.atualcombattraining.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.atualcombattraining.service.InternalMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AIGentService L3 记忆重建查询端点。 */
@RestController
@RequestMapping("/api/act/internal/memory")
@RequiredArgsConstructor
public class InternalMemoryController {

    private final InternalMemoryService memoryService;

    @GetMapping("/{sessionId}")
    public ApiResponse<InternalMemoryService.MemorySnapshot> snapshot(
            @PathVariable String sessionId) {
        return ApiResponse.success(memoryService.load(sessionId));
    }
}
