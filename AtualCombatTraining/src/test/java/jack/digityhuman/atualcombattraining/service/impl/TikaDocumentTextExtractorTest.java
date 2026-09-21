package jack.digityhuman.atualcombattraining.service.impl;

import jack.digityhuman.atualcombattraining.common.BizException;
import jack.digityhuman.atualcombattraining.config.AssistantProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tika 文档文本提取单测（FP-42）：
 * 正常提取 txt、空文件、目录外路径拒绝、文件不存在。
 * 说明：只测 txt（走 Tika core 即可），office/pdf 解析依赖 parsers-standard-package，由联调验证。
 */
class TikaDocumentTextExtractorTest {

    @TempDir
    Path tempDir;

    private TikaDocumentTextExtractor extractor() {
        AssistantProperties properties = new AssistantProperties();
        properties.setFileDir(tempDir.toString());
        return new TikaDocumentTextExtractor(properties);
    }

    private Path writeFile(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void 提取纯文本内容() throws Exception {
        Path file = writeFile("material-1.txt", "订单运营部职能介绍：负责订单录入、异常处理与客户沟通。");

        String text = extractor().extract(file.toString());

        assertTrue(text.contains("订单录入"));
        assertTrue(text.contains("异常处理"));
    }

    @Test
    void 提取md内容并去除首尾空白() throws Exception {
        Path file = writeFile("material-2.md", "\n\n# 合规红线\n\n禁止跳过核对环节。\n\n");

        String text = extractor().extract(file.toString());

        assertTrue(text.startsWith("# 合规红线"), "应去掉首部空白");
        assertTrue(text.endsWith("禁止跳过核对环节。"));
    }

    @Test
    void 空文件返回空串() throws Exception {
        Path file = writeFile("material-3.txt", "");

        assertEquals("", extractor().extract(file.toString()));
    }

    @Test
    void 拒绝存储目录之外的路径() throws Exception {
        Path outside = Files.createTempFile("outside-", ".txt");
        Files.writeString(outside, "不该被读取", StandardCharsets.UTF_8);
        try {
            BizException error = assertThrows(BizException.class,
                    () -> extractor().extract(outside.toString()));
            assertTrue(error.getMessage().contains("非法的材料路径"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void 文件不存在时抛业务异常() {
        BizException error = assertThrows(BizException.class,
                () -> extractor().extract(tempDir.resolve("missing.txt").toString()));
        assertTrue(error.getMessage().contains("材料文件不存在"));
    }
}