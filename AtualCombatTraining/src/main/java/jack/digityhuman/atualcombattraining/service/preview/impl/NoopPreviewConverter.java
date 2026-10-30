package jack.digityhuman.atualcombattraining.service.preview.impl;

import jack.digityhuman.atualcombattraining.service.preview.DocumentPreviewConverter;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * none provider（契约 11 §4.1）：不转换，直接回退文本阅读。
 * 生产未部署 LibreOffice 时的显式选择；转换失败不阻塞上传与发布（§10）。
 */
@Component
public class NoopPreviewConverter implements DocumentPreviewConverter {

    private static final long NO_DURATION = 0L;

    @Override
    public String provider() {
        return "none";
    }

    @Override
    public boolean supports(String extension) {
        // none 不声称支持任何格式（服务层据此直接置 text_only）
        return false;
    }

    @Override
    public PreviewResult convert(Path source, String extension, Path outputDir, Duration timeout) {
        return PreviewResult.fail("未配置转换器（provider=none）", NO_DURATION);
    }
}
