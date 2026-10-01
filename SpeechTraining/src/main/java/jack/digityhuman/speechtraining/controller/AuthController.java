package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.speechtraining.auth.SpeechAuthContext;
import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.speechtraining.service.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final AccessControlService accessControlService;

    @GetMapping("/me")
    public ApiResponse<MeResp> me() {
        SpeechAuthContext.Principal principal = accessControlService.requirePrincipal();
        return ApiResponse.success(new MeResp(
                principal.account(),
                principal.name() == null ? principal.account() : principal.name(),
                accessControlService.currentPermissions()));
    }

    public record MeResp(String userId, String fullname, List<String> permissions) {
    }
}
