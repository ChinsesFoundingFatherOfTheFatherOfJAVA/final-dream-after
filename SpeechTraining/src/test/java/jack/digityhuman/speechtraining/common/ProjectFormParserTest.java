package jack.digityhuman.speechtraining.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ProjectFormParserTest {

    private ProjectFormParser parser;

    @BeforeEach
    void setUp() {
        parser = new ProjectFormParser();
    }

    @Test
    void 形象图不能作为封面兜底() {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addFile(file("avatarImage", "avatar.png"));

        assertNull(parser.extractFile(request));
    }

    @Test
    void 泛化文件字段不再作为封面() {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addFile(file("file", "other.png"));
        request.addFile(file("image", "other.jpg"));

        assertNull(parser.extractFile(request));
    }

    @Test
    void 封面字段使用专用名并优先coverImage() {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        MockMultipartFile cover = file("cover", "cover.png");
        MockMultipartFile preferred = file("coverImage", "cover-image.png");
        request.addFile(cover);
        request.addFile(preferred);

        assertSame(preferred, parser.extractFile(request));
    }

    private MockMultipartFile file(String fieldName, String filename) {
        return new MockMultipartFile(fieldName, filename, "image/png",
                "image".getBytes(StandardCharsets.UTF_8));
    }
}
