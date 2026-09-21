package jack.digityhuman.atualcombattraining.dto.resp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 材料解析结果装配（卡片展开用的 GET materials 接口契约）：
 * 预览按固定长度截断、全文长度如实返回、空文本不报错。
 */
class MaterialBriefRespTest {

    @Test
    void 短文本不截断且长度如实() {
        MaterialBriefResp resp = MaterialBriefResp.of("m1", "f1", "材料.pdf", "pdf", 1024L, 1, "很短的正文");
        assertEquals(5, resp.getExtractLength());
        assertFalse(resp.getTruncated());
        assertEquals("很短的正文", resp.getPreview());
    }

    @Test
    void 超长文本只给前两千字并标记截断() {
        String text = "字".repeat(MaterialBriefResp.PREVIEW_CHARS + 300);
        MaterialBriefResp resp = MaterialBriefResp.of("m1", "f1", "长文.docx", "docx", 2048L, 0, text);
        assertEquals(MaterialBriefResp.PREVIEW_CHARS, resp.getPreview().length());
        assertEquals(MaterialBriefResp.PREVIEW_CHARS + 300, resp.getExtractLength());
        assertTrue(resp.getTruncated());
        assertEquals(0, resp.getVisibility());
    }

    @Test
    void 空提取文本按零字返回不报错() {
        MaterialBriefResp resp = MaterialBriefResp.of("m1", "f1", "空.pdf", "pdf", 0L, 1, null);
        assertEquals(0, resp.getExtractLength());
        assertFalse(resp.getTruncated());
        assertEquals("", resp.getPreview());
    }
}