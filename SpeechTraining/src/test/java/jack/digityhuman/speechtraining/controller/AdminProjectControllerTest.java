package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.speechtraining.common.FileStorageService;
import jack.digityhuman.speechtraining.common.ProjectFormParser;
import jack.digityhuman.speechtraining.dto.req.ProjectSaveReq;
import jack.digityhuman.speechtraining.service.TrainingProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminProjectControllerTest {

    @Mock
    private TrainingProjectService projectService;
    @Mock
    private FileStorageService fileStorageService;

    private AdminProjectController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminProjectController(projectService, fileStorageService, new ProjectFormParser());
    }

    @Test
    void 创建项目_形象文件与封面文件分别落盘() {
        MockMultipartFile cover = file("coverImage", "cover.png");
        MockMultipartFile avatar = file("avatarImage", "avatar.png");
        MockMultipartHttpServletRequest request = request("{}");
        request.addFile(cover);
        request.addFile(avatar);
        when(fileStorageService.saveCoverImage(cover)).thenReturn("/coverImage/cover.png");
        when(fileStorageService.saveAvatarImage(avatar)).thenReturn("/avatar/avatar.png");

        controller.createWithCover(request);

        ArgumentCaptor<ProjectSaveReq> captor = ArgumentCaptor.forClass(ProjectSaveReq.class);
        verify(projectService).createProject(captor.capture());
        ProjectSaveReq saved = captor.getValue();
        assertEquals("/coverImage/cover.png", saved.getCoverImage());
        assertNotNull(saved.getVirtualTrainer());
        assertEquals("/avatar/avatar.png", saved.getVirtualTrainer().getAvatarImage());
    }

    @Test
    void 更新项目_没有virtualTrainer时形象文件仍能写入() {
        MockMultipartFile avatar = file("avatarImage", "avatar.png");
        MockMultipartHttpServletRequest request = request("{}");
        request.addFile(avatar);
        when(fileStorageService.saveAvatarImage(avatar)).thenReturn("/avatar/new.png");

        controller.updateWithCover(7L, request);

        ArgumentCaptor<ProjectSaveReq> captor = ArgumentCaptor.forClass(ProjectSaveReq.class);
        verify(projectService).updateProject(eq(7L), captor.capture());
        ProjectSaveReq saved = captor.getValue();
        assertNull(saved.getCoverImage());
        assertNotNull(saved.getVirtualTrainer());
        assertEquals("/avatar/new.png", saved.getVirtualTrainer().getAvatarImage());
    }

    private MockMultipartHttpServletRequest request(String json) {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addParameter("data", json);
        return request;
    }

    private MockMultipartFile file(String fieldName, String filename) {
        return new MockMultipartFile(fieldName, filename, "image/png",
                "image".getBytes(StandardCharsets.UTF_8));
    }
}
