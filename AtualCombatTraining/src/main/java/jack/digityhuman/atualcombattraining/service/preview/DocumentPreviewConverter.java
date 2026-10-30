package jack.digityhuman.atualcombattraining.service.preview;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Office→PDF 预览转换器接口（契约 11 §4）
 * <p>
 * provider 只能由配置决定（office-com / libreoffice / none），运行时不得自动探测可执行文件；
 * 实现必须满足 §5 安全要求：ProcessBuilder 参数数组、临时目录独立、超时强杀、输出 PDF 固定目录。
 */
public interface DocumentPreviewConverter {

    String provider();

    /** 是否支持该扩展名（白名单由服务层统一把关，这里只声明能力） */
    boolean supports(String extension);

    /**
     * 同步转换：source → outputDir 下的 PDF 文件。
     * 超时/失败不抛异常，返回 PreviewResult{success=false, error=摘要}。
     */
    PreviewResult convert(Path source, String extension, Path outputDir, Duration timeout);

    /** 转换结果（§4 PreviewResult） */
    record PreviewResult(boolean success, String previewFile, long durationMs, String error) {

        public static PreviewResult ok(String previewFile, long durationMs) {
            return new PreviewResult(true, previewFile, durationMs, null);
        }

        public static PreviewResult fail(String error, long durationMs) {
            return new PreviewResult(false, null, durationMs, error);
        }
    }
}
