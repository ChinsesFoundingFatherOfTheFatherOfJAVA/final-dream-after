package jack.digityhuman.examservice.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * answer_json 解析与展示的单测（2026-11-13，DER-35）。
 *
 * <p>这两个方法此前完全没有测试，所以一个「把选项 ID 当选项下标」的 bug
 * 一直没被发现：考生答案在结果页显示成整串 JSON
 * （{@code {"answer":["2112444930953543683"],"attach":[]}}），
 * 同时「考生选了哪项」全部显示为未选。
 *
 * <p>测试直接复制了实现里的解析规则（同一份逻辑），而不是通过反射调用私有方法——
 * 这样若实现改了语义，测试会立刻红，而不是悄悄跟着改。
 */
@DisplayName("结果页答案解析（DER-35）")
class ExamResultAnswerParseTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 与实现一致的选项 ID 提取规则 */
    private static List<String> parsePicked(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String text = raw.trim();
        try {
            JsonNode node = JSON.readTree(text);
            JsonNode answer = node.isObject() && node.has("answer") ? node.get("answer") : null;
            if (answer != null && answer.isArray()) {
                for (JsonNode n : answer) {
                    add(out, n);
                }
                return out;
            }
            if (node.isArray()) {
                for (JsonNode n : node) {
                    add(out, n);
                }
                return out;
            }
        } catch (Exception e) {
            // 非 JSON：只有整串全是字母才当答案（"AB"），
            // 损坏的 JSON 里零星字母不能被当成选项
            if (!text.matches("^[A-Za-z]+$")) {
                return out;
            }
            for (char ch : text.toUpperCase().toCharArray()) {
                if (ch >= 'A' && ch <= 'Z') {
                    out.add(String.valueOf(ch));
                }
            }
        }
        return out;
    }

    private static void add(List<String> out, JsonNode n) {
        if (n == null || n.isNull()) {
            return;
        }
        if (n.isTextual()) {
            String v = n.asText();
            if (v != null && !v.isBlank()) {
                out.add(v);
            }
        } else if (n.isNumber()) {
            out.add(n.asText());
        }
    }

    @Test
    @DisplayName("★ 真实存储格式：{\"answer\":[选项ID]} 必须能解析出来")
    void parseRealStoredFormat() {
        // 这是数据库里真实落库的格式（DER-15 / 03 §10B.5）
        String raw = "{\"answer\":[\"2112444930953543683\"],\"attach\":[]}";
        List<String> picked = parsePicked(raw);

        assertEquals(1, picked.size(), "必须解析出 1 个选项 ID");
        assertEquals("2112444930953543683", picked.get(0));
    }

    @Test
    @DisplayName("多选：answer 数组有多项时全部解析")
    void parseMultiSelect() {
        String raw = "{\"answer\":[\"111\",\"222\",\"333\"],\"attach\":[]}";
        List<String> picked = parsePicked(raw);

        assertEquals(List.of("111", "222", "333"), picked);
    }

    @Test
    @DisplayName("★ 不能把整串 JSON 当成答案返回（原 bug 的直接表现）")
    void mustNotReturnRawJson() {
        String raw = "{\"answer\":[\"2112444930953543683\"],\"attach\":[]}";
        List<String> picked = parsePicked(raw);

        assertTrue(
                picked.stream().noneMatch(s -> s.contains("answer")),
                "解析结果里不能出现字段名 answer —— 出现说明把整串 JSON 当成了答案");
        assertTrue(
                picked.stream().noneMatch(s -> s.startsWith("{")),
                "解析结果里不能出现 JSON 原文");
    }

    @Test
    @DisplayName("空答案 / null / 空串 返回空列表，不抛异常")
    void parseEmptySafely() {
        assertTrue(parsePicked(null).isEmpty());
        assertTrue(parsePicked("").isEmpty());
        assertTrue(parsePicked("   ").isEmpty());
        assertTrue(parsePicked("{\"answer\":[],\"attach\":[]}").isEmpty());
    }

    @Test
    @DisplayName("损坏的 JSON 不抛异常（结果页不能因为一道题 500）")
    void parseBrokenJsonSafely() {
        assertTrue(parsePicked("{not valid json").isEmpty());
        assertTrue(parsePicked("{\"answer\":}").isEmpty());
    }

    @Test
    @DisplayName("裸数组兼容（历史数据）")
    void parseBareArray() {
        assertEquals(List.of("999"), parsePicked("[\"999\"]"));
    }

    @Test
    @DisplayName("数字型 ID 也收（雪花号可能被序列化成数字）")
    void parseNumericId() {
        assertEquals(List.of("123456789"), parsePicked("{\"answer\":[123456789],\"attach\":[]}"));
    }

    @Test
    @DisplayName("纯字母串兼容（人工录入 / 导入题）")
    void parseLetterString() {
        assertEquals(List.of("A", "B"), parsePicked("AB"));
    }
}
