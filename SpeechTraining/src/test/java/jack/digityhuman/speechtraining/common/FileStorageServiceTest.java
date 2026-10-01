package jack.digityhuman.speechtraining.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void 本地上传形象图落到avatar目录并返回访问路径() {
        FileStorageServiceImpl service = new FileStorageServiceImpl();
        ReflectionTestUtils.setField(service, "coverImageDir", tempDir.resolve("coverImage").toString());
        ReflectionTestUtils.setField(service, "coverImageUrlPrefix", "/coverImage");
        ReflectionTestUtils.setField(service, "audioDir", tempDir.resolve("audio").toString());
        ReflectionTestUtils.setField(service, "audioUrlPrefix", "/audio");
        ReflectionTestUtils.setField(service, "avatarDir", tempDir.resolve("avatar").toString());
        ReflectionTestUtils.setField(service, "avatarUrlPrefix", "/avatar");
        service.init();

        MockMultipartFile file = new MockMultipartFile("avatarImage", "trainer.PNG", "image/png",
                "image".getBytes(StandardCharsets.UTF_8));
        String url = service.saveAvatarImage(file);

        assertTrue(url.startsWith("/avatar/"));
        assertTrue(url.endsWith(".png"));
        String filename = url.substring("/avatar/".length());
        assertTrue(Files.exists(tempDir.resolve("avatar").resolve(filename)));
    }
}
