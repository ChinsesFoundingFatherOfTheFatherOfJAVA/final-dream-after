package jack.digityhuman.atualcombattraining.common;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置一致性防回归测试。
 * <p>
 * 背景（真实踩坑）：材料上传 2.6MB 的 pptx 时报 500 —— Spring multipart 默认上限是 **1MB**，
 * 它在应用层自己的 `assistant.file-max-size-bytes` 检查之前就把请求拒了，只报"服务器内部错误"。
 * 03 文档的示例文件本身就是 2.6MB，所以两条上限必须保持一致，这里用测试把它钉住。
 */
class MultipartLimitConfigTest {

    private Properties loadProperties() throws Exception {
        Properties properties = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            assertTrue(in != null, "classpath 下应存在 application.properties");
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    /** 解析 "50MB" / "1MB" / 纯字节数，返回字节数 */
    private long toBytes(String value) {
        String text = value.trim().toUpperCase();
        long multiplier = 1L;
        if (text.endsWith("MB")) {
            multiplier = 1024L * 1024L;
            text = text.substring(0, text.length() - 2).trim();
        } else if (text.endsWith("KB")) {
            multiplier = 1024L;
            text = text.substring(0, text.length() - 2).trim();
        }
        return Long.parseLong(text) * multiplier;
    }

    @Test
    void multipart上限不低于应用层材料上限() throws Exception {
        Properties properties = loadProperties();
        String multipart = properties.getProperty("spring.servlet.multipart.max-file-size");
        String appLimit = properties.getProperty("assistant.file-max-size-bytes");

        assertTrue(multipart != null && !multipart.isBlank(),
                "必须显式配置 spring.servlet.multipart.max-file-size，否则默认 1MB 会拦掉大材料");
        assertTrue(appLimit != null && !appLimit.isBlank(), "必须配置 assistant.file-max-size-bytes");

        long multipartBytes = toBytes(multipart);
        long appLimitBytes = Long.parseLong(appLimit.trim());
        assertTrue(multipartBytes >= appLimitBytes,
                "multipart 上限(" + multipartBytes + ") 不应小于应用层材料上限(" + appLimitBytes + ")，"
                        + "否则超限请求会在应用校验前被拒并报 500");
        assertTrue(appLimitBytes >= 2_638_848L,
                "应用层材料上限应至少覆盖 03 文档示例的 2.6MB 文件");
    }
}