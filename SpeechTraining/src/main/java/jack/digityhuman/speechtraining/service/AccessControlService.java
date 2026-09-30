package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.auth.SpeechAuthContext;

import java.util.List;

public interface AccessControlService {
    SpeechAuthContext.Principal requirePrincipal();

    void requireManage();

    boolean hasPermission(String account, String permissionCode);

    List<String> currentPermissions();

    void requireProjectOwner(Long projectId);
}
