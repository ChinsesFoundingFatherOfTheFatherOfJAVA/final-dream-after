package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.resp.ArrangeStudentRespDtos;
import jack.digityhuman.examservice.service.ArrangeStudentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 签名记录接口（04 §5 API-30）。 */
@RestController
@RequestMapping("/api/exam/arrange/{id}/signature")
@RequiredArgsConstructor
public class ArrangeSignatureController {

    private final ArrangeStudentService arrangeStudentService;

    /** API-30 签名记录分页。 */
    @GetMapping("/list")
    public ApiResponse<PageResult<ArrangeStudentRespDtos.SignatureItem>> list(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(arrangeStudentService.signatures(id, current, limit, keyword));
    }
}