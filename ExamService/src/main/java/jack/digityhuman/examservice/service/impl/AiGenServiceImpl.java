package jack.digityhuman.examservice.service.impl;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.agent.ExamGenProvider;
import jack.digityhuman.examservice.agent.NativeExamGenClient;
import jack.digityhuman.examservice.dify.DifyProperties;
import jack.digityhuman.examservice.dify.DifyWorkflowSupport;
import jack.digityhuman.examservice.dto.req.AiGenDtos;
import jack.digityhuman.examservice.dto.resp.AiGenRespDtos;
import jack.digityhuman.examservice.service.AiGenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI 建题实现（FP-11）。
 *
 * <p>链路：素材文本 + 各题型数量 → AIGentService exam-gen（默认）或 Dify「AI出题」→ outputs.questions
 * → 逐题校验题型编码后转成 {@link AiGenRespDtos.QuestionDraft} 返回前端卡片流。
 *
 * <p>本类只负责"生成草稿"，不落库——题目真正入库走 FP-10 的录入（/ques 或 /ques/batch-create）。
 * 这样 AI 生成质量不影响数据一致性，用户可先改后存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGenServiceImpl implements AiGenService {

    /** 03 §7.2：文件解析后文本上限。 */
    private static final int MAX_TEXT_CHARS = 20_000;
    /** FP-11：单文件上限 100MB。 */
    private static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    private static final Set<String> SUPPORTED_FILE_EXTENSIONS =
            Set.of(".doc", ".docx", ".ppt", ".pptx", ".pps", ".pdf");
    /** FP-11：每种题型 0~35。 */
    private static final int MAX_PER_TYPE = 35;
    /** 题型编码：0单选 1多选 2判断 3填空 4问答（组合题 5 不由 AI 生成）。 */
    private static final int MAX_TYPE = 4;

    private static final int CODE_AI_GEN_FAILED = 50010;
    private static final int CODE_PARSE_FAILED = 50020;

    private final DifyProperties difyProperties;
    private final DifyWorkflowSupport difyWorkflowSupport;
    private final ExamGenProvider examGenProvider;
    private final NativeExamGenClient nativeExamGenClient;

    /** Tika 是线程安全的，复用一个实例即可。 */
    private final Tika tika = new Tika();

    /** 本项目未由容器暴露 ObjectMapper，自持一个线程安全实例。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    public AiGenRespDtos.AiGenResult generate(AiGenDtos.AiGenReq req) {
        String material = req == null || req.getMaterialText() == null ? "" : req.getMaterialText().trim();
        if (material.isEmpty()) {
            throw new BizException("请先粘贴素材或上传文件");
        }
        if (material.length() > MAX_TEXT_CHARS) {
            throw new BizException("素材过长，请压缩到 " + MAX_TEXT_CHARS + " 字以内");
        }

        Map<String, Integer> counts = normalizeCounts(req.getCounts());
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            throw new BizException("请至少为一种题型设置题量");
        }

        // native 不依赖 Dify key；只有显式切回 dify 时才检查旧配置。
        if (!examGenProvider.usesNative() && !difyProperties.aiGenConfigured()) {
            throw new BizException(CODE_AI_GEN_FAILED,
                    "AI 生成失败：Dify「AI出题」工作流未配置（缺 exam.dify.ai-gen-api-key）");
        }

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("material_text", material);
        inputs.putAll(counts);

        Map<String, Object> outputs = examGenProvider.usesNative()
                ? nativeExamGenClient.run(inputs)
                : difyWorkflowSupport.runAiGen(inputs);

        Object success = outputs.get("success");
        if (success != null && !Boolean.parseBoolean(String.valueOf(success))) {
            throw new BizException(CODE_AI_GEN_FAILED,
                    "AI 生成失败：" + String.valueOf(outputs.getOrDefault("message", "格式错误")));
        }

        // 兼容两种工作流形态：
        //  ① 带 code 节点的版本 → outputs.questions 已是数组；
        //  ② 简化版（start→llm→end，推荐）→ outputs.text 是模型原文，自己解析。
        // 之所以推荐 ②：Dify 的 code 节点在入参变量上踩过 type 处理问题
        //（实测报 'dict' object has no attribute 'replace'），去掉它少一个故障面。
        List<?> list = asQuestionList(outputs.get("questions"));
        if (list.isEmpty()) {
            list = asQuestionList(parseQuestionsText(outputs.get("text")));
        }
        if (list.isEmpty()) {
            throw new BizException(CODE_AI_GEN_FAILED, "AI 生成失败：未返回题目，请重试");
        }

        List<AiGenRespDtos.QuestionDraft> drafts = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            AiGenRespDtos.QuestionDraft draft = toDraft(map);
            if (draft != null) {
                drafts.add(draft);
            }
        }
        if (drafts.isEmpty()) {
            log.warn("Dify 返回的题目全部无法解析，原始条数={}", list.size());
            throw new BizException(CODE_AI_GEN_FAILED, "AI 生成失败：返回的题目格式无法解析");
        }

        AiGenRespDtos.AiGenResult result = new AiGenRespDtos.AiGenResult();
        result.setQuestions(drafts);
        return result;
    }

    @Override
    public String parseDocument(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String extension = extensionOf(filename);
        if (!SUPPORTED_FILE_EXTENSIONS.contains(extension)) {
            throw new BizException(CODE_PARSE_FAILED,
                    "文件解析异常：仅支持 .doc/.docx/.ppt/.pptx/.pps/.pdf");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BizException(CODE_PARSE_FAILED, "文件解析异常：单个文件不能超过 100MB");
        }
        try {
            String text = ".pdf".equals(extension)
                    ? extractPdfText(file)
                    : extractOfficeText(file);
            text = text == null ? "" : text.trim();
            if (text.isEmpty()) {
                throw new BizException(CODE_PARSE_FAILED, "文件解析异常：未提取到文本（可能是扫描件或缺文本层）");
            }
            // 超长直接截断：AI 出题只吃前 20000 字，前端展示解析结果时也不至于卡顿
            return text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI 建题文件解析失败: file={}, err={}", filename, e.getMessage());
            throw new BizException(CODE_PARSE_FAILED, "文件解析异常（非模板格式/文件损坏）");
        }
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    /**
     * Tika 2.x 的 PDF 模块依赖 PDFBox 2，而本工程答卷导出已使用 PDFBox 3；
     * 两者共存会在 Tika PDFParser 内触发 NoSuchMethodError，故 PDF 分支直接调用 PDFBox 3。
     */
    private String extractPdfText(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream();
             RandomAccessReadBuffer source = new RandomAccessReadBuffer(in);
             PDDocument document = Loader.loadPDF(source)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractOfficeText(MultipartFile file) throws Exception {
        try (InputStream in = file.getInputStream()) {
            return tika.parseToString(in);
        }
    }

    /** 题量归一：空值按 0，负值按 0，超过 35 截到 35。 */
    private Map<String, Integer> normalizeCounts(AiGenDtos.Counts counts) {
        AiGenDtos.Counts c = counts == null ? new AiGenDtos.Counts() : counts;
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("single", clamp(c.getSingle()));
        map.put("multi", clamp(c.getMulti()));
        map.put("judge", clamp(c.getJudge()));
        map.put("fill", clamp(c.getFill()));
        map.put("answer", clamp(c.getAnswer()));
        return map;
    }

    private int clamp(Integer value) {
        if (value == null || value < 0) {
            return 0;
        }
        return Math.min(value, MAX_PER_TYPE);
    }

    private List<?> asQuestionList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value instanceof Map<?, ?> map) {
            Object inner = map.get("questions");
            if (inner instanceof List<?> list) {
                return list;
            }
        }
        return List.of();
    }

    /** 把模型原文（可能带 markdown 围栏）解析成 questions 列表。 */
    private Object parseQuestionsText(Object text) {
        if (text == null) {
            return null;
        }
        String cleaned = String.valueOf(text).trim();
        if (cleaned.startsWith("```")) {
            int nl = cleaned.indexOf('\n');
            cleaned = nl >= 0 ? cleaned.substring(nl + 1) : "";
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.lastIndexOf("```"));
        }
        cleaned = cleaned.trim();
        try {
            return OBJECT_MAPPER.readValue(cleaned, Map.class);
        } catch (Exception e) {
            log.warn("AI 输出不是合法 JSON：{}", e.getMessage());
            return null;
        }
    }

    /**
     * Dify 返回的一道题 → QuestionDraft。
     * 题型越界（如误出组合题 5）直接丢弃，避免把脏数据带给前端卡片流。
     */
    @SuppressWarnings("unchecked")
    private AiGenRespDtos.QuestionDraft toDraft(Map<?, ?> map) {
        Object typeValue = map.get("type");
        if (!(typeValue instanceof Number number)) {
            return null;
        }
        int type = number.intValue();
        if (type < 0 || type > MAX_TYPE) {
            log.warn("AI 返回了越界题型 {}，已丢弃", type);
            return null;
        }

        AiGenRespDtos.QuestionDraft draft = new AiGenRespDtos.QuestionDraft();
        draft.setType(type);
        draft.setLevelType(asInteger(map.get("levelType"), 1));
        draft.setStem(asString(map.get("stem")));
        draft.setAnswer(asString(map.get("answer")));
        draft.setAnalysis(asString(map.get("analysis")));

        Object options = map.get("options");
        if (options instanceof List<?> list) {
            List<String> texts = new ArrayList<>();
            for (Object option : list) {
                texts.add(asString(option));
            }
            draft.setOptions(texts);
        } else {
            draft.setOptions(new ArrayList<>());
        }

        Object keywords = map.get("keywords");
        if (keywords instanceof List<?> list) {
            List<AiGenRespDtos.Keyword> items = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> kw)) {
                    continue;
                }
                AiGenRespDtos.Keyword keyword = new AiGenRespDtos.Keyword();
                keyword.setKeyword(asString(kw.get("keyword")));
                keyword.setScoreRatio(asInteger(kw.get("scoreRatio"), 0));
                items.add(keyword);
            }
            draft.setKeywords(items);
        } else {
            draft.setKeywords(new ArrayList<>());
        }
        return draft;
    }

    private Integer asInteger(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
