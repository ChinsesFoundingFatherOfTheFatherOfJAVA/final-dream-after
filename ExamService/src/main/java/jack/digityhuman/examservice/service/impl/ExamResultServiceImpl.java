package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.dto.resp.ExamResultRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeStudent;
import jack.digityhuman.examservice.entity.ExamAttempt;
import jack.digityhuman.examservice.entity.ExamAttemptAnswer;
import jack.digityhuman.examservice.entity.ExamPaper;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamPaperSnapshot;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.entity.ExamQuestionItem;
import jack.digityhuman.examservice.entity.ExamQuestionPoint;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeStudentMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptAnswerMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptMapper;
import jack.digityhuman.examservice.mapper.ExamPaperMapper;
import jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamPaperSnapshotMapper;
import jack.digityhuman.examservice.mapper.ExamPointMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionItemMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionPointMapper;
import jack.digityhuman.examservice.service.ExamResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 答卷详情实现（施工序阶段 6，API-39/40）。
 *
 * <p>PDF 用 PDFBox 3 + 系统黑体（simhei.ttf）内嵌，保证中文可显示；
 * 字体缺失时降级为纯英文提示而不是抛异常。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamResultServiceImpl implements ExamResultService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] TYPE_NAMES = { "单选题", "多选题", "判断题", "填空题", "问答题", "组合题" };
    private static final String[] CN_NUM = { "一", "二", "三", "四", "五", "六", "七", "八", "九", "十" };
    /**
     * 中文字体加载顺序（PDF 中文渲染）：
     * <ol>
     *   <li>{@code exam.pdf.font-path} 配置的绝对路径（部署时可指定）；</li>
     *   <li><b>随应用打包</b>的 classpath 字体（src/main/resources/fonts/ 下任意一个）；</li>
     *   <li>操作系统已装的中文字体（Windows / Linux / macOS 常见路径）。</li>
     * </ol>
     * 全都拿不到时降级为 ASCII 版 PDF（中文以 ? 呈现），保证接口不 500。
     */
    private static final String[] CLASSPATH_FONTS = {
            "fonts/NotoSansSC-VF.ttf", "fonts/NotoSansSC-Regular.ttf", "fonts/NotoSansSC-Regular.otf",
            "fonts/SourceHanSansCN-Regular.otf", "fonts/SourceHanSansCN-Regular.ttf",
            "fonts/simhei.ttf", "fonts/msyh.ttc", "fonts/wqy-microhei.ttc"
    };
    private static final String[] SYSTEM_FONTS = {
            "C:/Windows/Fonts/simhei.ttf", "C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simsun.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
            "/usr/share/fonts/truetype/arphic/uming.ttc",
            "/System/Library/Fonts/PingFang.ttc"
    };
    /** 04 §2：考试域使用 400xx；40101 只保留给网关"未登录"。 */
    private static final int ERR_NOT_FOUND = 40024;

    private final ExamAttemptMapper attemptMapper;
    private final ExamAttemptAnswerMapper answerMapper;
    private final ExamArrangeMapper arrangeMapper;
    private final ExamArrangeStudentMapper studentMapper;
    private final ExamPaperMapper paperMapper;
    private final ExamPaperQuestionMapper paperQuestionMapper;
    private final ExamPaperSnapshotMapper snapshotMapper;
    private final ExamQuestionMapper questionMapper;
    private final ExamQuestionItemMapper itemMapper;
    private final ExamQuestionPointMapper questionPointMapper;
    private final ExamPointMapper pointMapper;

    /** 快照解析结果：题目结构/分值与总分，用于历史答卷回放。 */
    private record SnapshotData(List<ExamPaperQuestion> rels, BigDecimal totalScore) {
    }

    /** 可选：部署时用绝对路径指定中文字体（优先级最高）。 */
    @org.springframework.beans.factory.annotation.Value("${exam.pdf.font-path:}")
    private String pdfFontPath;

    @Override
    public ExamResultRespDtos.ResultView view(String ueId) {
        ExamAttempt attempt = attemptMapper.selectById(ueId);
        if (attempt == null) {
            throw new BizException(ERR_NOT_FOUND, "答卷不存在或已删除");
        }
        ExamArrange arrange = arrangeMapper.selectById(attempt.getArrangeId());
        ExamPaper paper = StringUtils.hasText(attempt.getPaperId()) ? paperMapper.selectById(attempt.getPaperId()) : null;
        ExamArrangeStudent student = StringUtils.hasText(attempt.getStudentId())
                ? studentMapper.selectById(attempt.getStudentId()) : null;
        SnapshotData snapshot = snapshotData(attempt);

        ExamResultRespDtos.ResultView v = new ExamResultRespDtos.ResultView();
        v.setUeId(attempt.getId());
        v.setArrId(attempt.getArrangeId());
        v.setArrName(arrange == null ? "" : arrange.getName());
        v.setExamName(paper == null ? "" : paper.getName());
        // 历史答卷以快照为准；快照缺失才回退当前试卷，并在响应里标记 snapshotMissing
        v.setSnapshotMissing(snapshot == null);
        v.setTotalScore(snapshot != null ? snapshot.totalScore()
                : (paper == null ? BigDecimal.ZERO : paper.getTotalScore()));
        v.setPassScore(arrange == null ? null : arrange.getPassScore());
        v.setUsedTime(attempt.getDurationSec());
        v.setScore(attempt.getScore());
        v.setStatus(attempt.getStatus());
        v.setSubmitAt(attempt.getSubmitAt() == null ? "" : TIME.format(attempt.getSubmitAt()));
        v.setMarkerUsers(splitNames(attempt.getGraderNames()));
        if (student != null) {
            v.setFullname(student.getFullname());
            v.setUserName(student.getAccount());
            v.setUserNo(student.getAccount());
            v.setDeptName(student.getDeptName());
            v.setPositionName(student.getPositionName());
        }

        List<ExamAttemptAnswer> answers = answerMapper.selectList(Wrappers.<ExamAttemptAnswer>lambdaQuery()
                .eq(ExamAttemptAnswer::getAttemptId, ueId));
        Map<String, ExamAttemptAnswer> byQues = new java.util.LinkedHashMap<>();
        for (ExamAttemptAnswer a : answers) {
            byQues.put(a.getQuesId(), a);
        }

        List<ExamResultRespDtos.UserQues> userQues = new ArrayList<>();
        List<ExamResultRespDtos.TypeTitle> titles = new ArrayList<>();
        Set<Integer> seenTypes = new LinkedHashSet<>();
        int order = 0;
        Map<Integer, int[]> sectionCount = new TreeMap<>();
        Map<Integer, BigDecimal> sectionScore = new TreeMap<>();

        List<ExamPaperQuestion> rels = snapshot != null ? snapshot.rels() : paperQuestions(attempt.getPaperId());
        for (ExamPaperQuestion rel : rels) {
            ExamQuestion ques = questionMapper.selectById(rel.getQuesId());
            if (ques == null) {
                continue;
            }
            order++;
            int type = ques.getType() == null ? 0 : ques.getType();
            seenTypes.add(type);
            sectionCount.computeIfAbsent(type, k -> new int[] { 0, 0 })[0]++;
            sectionScore.merge(type, rel.getScore() == null ? BigDecimal.ZERO : rel.getScore(), BigDecimal::add);

            ExamAttemptAnswer ans = byQues.get(ques.getId());
            ExamResultRespDtos.UserQues uq = new ExamResultRespDtos.UserQues();
            uq.setId(ques.getId());
            uq.setContent(ques.getContent());
            uq.setQuesType(type);
            uq.setOrderIndex(order);
            uq.setPointNames(String.join("；", pointsOf(ques.getId())));
            uq.setSubmitContent(ans == null ? "" : formatSubmit(ans.getAnswerJson()));
            uq.setAnswerContent(correctAnswerOf(ques));
            uq.setExplainText(ques.getExplainText());
            uq.setTotalScore(rel.getScore());
            uq.setScore(ans == null ? BigDecimal.ZERO : ans.getScore());
            uq.setChecked(ans == null ? 0 : ans.getCorrectFlag());
            List<ExamQuestionItem> items = itemsOf(ques.getId());
            if (!items.isEmpty()) {
                List<ExamResultRespDtos.ChoiceItem> choices = new ArrayList<>();
                Set<Integer> picked = parseAnswer(ans == null ? null : ans.getAnswerJson());
                for (int i = 0; i < items.size(); i++) {
                    ExamResultRespDtos.ChoiceItem c = new ExamResultRespDtos.ChoiceItem();
                    c.setItemContent(items.get(i).getItemContent());
                    c.setChecked(picked.contains(i));
                    c.setAnswer(items.get(i).getAnswer());
                    choices.add(c);
                }
                uq.setChoiceItems(choices);
            }
            userQues.add(uq);
        }
        int idx = 0;
        for (Integer type : seenTypes) {
            ExamResultRespDtos.TypeTitle t = new ExamResultRespDtos.TypeTitle();
            t.setType(type);
            t.setOrderIndex(idx + 1);
            t.setAlias((idx < CN_NUM.length ? CN_NUM[idx] : String.valueOf(idx + 1)) + "、" + typeName(type));
            int[] c = sectionCount.getOrDefault(type, new int[] { 0, 0 });
            t.setDescription("本题共" + c[0] + "小题，共" + sectionScore.getOrDefault(type, BigDecimal.ZERO) + "分");
            titles.add(t);
            idx++;
        }
        v.setQuesTypeTitles(titles);
        v.setUserQues(userQues);
        return v;
    }

    @Override
    public PdfFile exportPdf(String ueId) {
        ExamResultRespDtos.ResultView v = view(ueId);
        List<String> lines = new ArrayList<>();
        lines.add("答卷详情");
        lines.add("考试：" + v.getArrName());
        lines.add("试卷：" + v.getExamName());
        lines.add("考生：" + nvl(v.getFullname()) + "（" + nvl(v.getUserName()) + "）");
        lines.add("部门：" + nvl(v.getDeptName()) + "    岗位：" + nvl(v.getPositionName()));
        lines.add("得分：" + v.getScore() + " / " + v.getTotalScore() + "    交卷时间：" + v.getSubmitAt());
        lines.add("用时：" + (v.getUsedTime() == null ? 0 : v.getUsedTime()) + " 秒");
        lines.add("");
        for (ExamResultRespDtos.TypeTitle t : v.getQuesTypeTitles()) {
            lines.add(t.getAlias() + "（" + t.getDescription() + "）");
        }
        lines.add("");
        int i = 0;
        for (ExamResultRespDtos.UserQues q : v.getUserQues()) {
            i++;
            lines.add(i + ". " + stripHtml(q.getContent()));
            if (q.getChoiceItems() != null) {
                int k = 0;
                for (ExamResultRespDtos.ChoiceItem c : q.getChoiceItems()) {
                    lines.add("   " + (char) ('A' + k++) + ". " + stripHtml(c.getItemContent())
                            + (Boolean.TRUE.equals(c.getChecked()) ? "  [考生所选]" : "")
                            + (c.getAnswer() != null && c.getAnswer() == 1 ? "  [正确答案]" : ""));
                }
            }
            lines.add("   考生答案：" + nvl(q.getSubmitContent()) + "    正确答案：" + nvl(q.getAnswerContent())
                    + "    得分：" + q.getScore() + "/" + q.getTotalScore());
            if (StringUtils.hasText(q.getExplainText())) {
                lines.add("   解析：" + stripHtml(q.getExplainText()));
            }
            lines.add("");
        }
        byte[] bytes = renderPdf(lines);
        if (bytes == null || bytes.length == 0) {
            bytes = renderAsciiPdf(lines);
        }
        String filename = nvl(v.getFullname()) + "_" + nvl(v.getExamName()) + "_答卷.pdf";
        return new PdfFile(bytes, filename);
    }

    // ---------------- 内部 ----------------

    private byte[] renderPdf(List<String> lines) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType0Font font = loadCjkFont(doc);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = 800;
            boolean first = true;
            for (String raw : expandLines(lines)) {
                if (y < 50) {
                    cs.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    cs = new PDPageContentStream(doc, page);
                    y = 800;
                    first = true;
                }
                cs.beginText();
                cs.newLineAtOffset(50, y);
                if (font != null) {
                    cs.setFont(font, 11);
                    cs.showText(raw);
                } else {
                    // 没有中文字体时降级：非 ASCII 用 ? 占位，保证仍能出 PDF
                    cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 11);
                    cs.showText(raw.replaceAll("[^\\x00-\\x7F]", "?"));
                }
                cs.endText();
                y -= 16;
                first = false;
            }
            cs.close();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            // 中文内嵌字体渲染失败时不让接口 500：记日志后由上层降级为 ASCII 版 PDF
            log.error("生成答卷 PDF 失败，将降级为 ASCII 版: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 按「配置路径 → 随包 classpath → 系统字体」的顺序找一个可用的中文字体。
     *
     * <p>都拿不到就返回 null，由调用方降级成 ASCII 版 PDF，保证接口不 500。
     */
    private PDType0Font loadCjkFont(PDDocument doc) {
        if (StringUtils.hasText(pdfFontPath) && new File(pdfFontPath).exists()) {
            try {
                PDType0Font font = PDType0Font.load(doc, new File(pdfFontPath));
                log.info("答卷 PDF 中文字体: 配置路径 {}", pdfFontPath);
                return font;
            } catch (Exception e) {
                log.warn("配置的中文字体不可用 [{}]: {}", pdfFontPath, e.getMessage());
            }
        }
        for (String path : CLASSPATH_FONTS) {
            try (var in = getClass().getClassLoader().getResourceAsStream(path)) {
                if (in != null) {
                    PDType0Font font = PDType0Font.load(doc, in);
                    log.info("答卷 PDF 中文字体: 随包 classpath {}", path);
                    return font;
                }
            } catch (Exception e) {
                log.warn("随包中文字体加载失败 [{}]: {}", path, e.getMessage());
            }
        }
        for (String path : SYSTEM_FONTS) {
            File file = new File(path);
            if (!file.exists()) {
                continue;
            }
            try {
                PDType0Font font = PDType0Font.load(doc, file);
                log.info("答卷 PDF 中文字体: 系统字体 {}", path);
                return font;
            } catch (Exception e) {
                log.warn("系统中文字体加载失败 [{}]: {}", path, e.getMessage());
            }
        }
        log.warn("未找到可用中文字体，答卷 PDF 将降级为 ASCII（中文以 ? 呈现）");
        return null;
    }

    /** 兜底 PDF：不嵌中文字体，非 ASCII 用 ? 占位（保证接口永远能出文件）。 */
    private byte[] renderAsciiPdf(List<String> lines) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var font = new org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 800;
                for (String raw : expandLines(lines)) {
                    if (y < 50) {
                        break;
                    }
                    cs.beginText();
                    cs.setFont(font, 11);
                    cs.newLineAtOffset(50, y);
                    cs.showText(raw.replaceAll("[^\\x20-\\x7E]", "?"));
                    cs.endText();
                    y -= 16;
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException("生成答卷 PDF 失败：" + e.getMessage());
        }
    }

    /** 长行按字符宽度折行，避免超出页面。 */
    private List<String> expandLines(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String s = line == null ? "" : line;
            while (s.length() > 46) {
                out.add(s.substring(0, 46));
                s = "    " + s.substring(46);
            }
            out.add(s);
        }
        return out;
    }



    private List<ExamPaperQuestion> paperQuestions(String paperId) {
        if (!StringUtils.hasText(paperId)) {
            return List.of();
        }
        return paperQuestionMapper.selectList(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, paperId)
                .orderByAsc(ExamPaperQuestion::getSectionIndex)
                .orderByAsc(ExamPaperQuestion::getOrderIndex));
    }

    /**
     * 读取答卷对应的试卷快照（C-01）：结构、顺序、分值、总分以发布/同步时冻结的 JSON 为准，
     * 历史答卷不会因为之后改卷而变形。快照缺失时返回 null，由调用方回退当前试卷。
     */
    private SnapshotData snapshotData(ExamAttempt attempt) {
        if (attempt == null || !StringUtils.hasText(attempt.getSnapshotId())) {
            return null;
        }
        ExamPaperSnapshot snap = snapshotMapper.selectById(attempt.getSnapshotId());
        if (snap == null || !StringUtils.hasText(snap.getSnapshotJson())) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(snap.getSnapshotJson());
            JsonNode qs = root.path("questions");
            if (!qs.isArray()) {
                return null;
            }
            List<ExamPaperQuestion> rels = new ArrayList<>();
            for (JsonNode n : qs) {
                ExamPaperQuestion rel = new ExamPaperQuestion();
                rel.setQuesId(n.path("quesId").asText(""));
                rel.setSectionIndex(n.path("sectionIndex").asInt(0));
                rel.setOrderIndex(n.path("orderIndex").asInt(0));
                rel.setScore(decimal(n, "score"));
                rel.setMissScore(decimal(n, "missScore"));
                rel.setMissScoreType(n.path("missScoreType").asInt(0));
                rel.setSubScores(n.hasNonNull("subScores") ? n.get("subScores").toString() : null);
                rels.add(rel);
            }
            return new SnapshotData(rels, decimal(root, "totalScore"));
        } catch (Exception e) {
            log.warn("解析试卷快照失败，回退当前试卷：{}", e.getMessage());
            return null;
        }
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? BigDecimal.ZERO : v.decimalValue();
    }

    private List<ExamQuestionItem> itemsOf(String quesId) {
        return itemMapper.selectList(Wrappers.<ExamQuestionItem>lambdaQuery()
                .eq(ExamQuestionItem::getQuesId, quesId)
                .orderByAsc(ExamQuestionItem::getOrderIndex));
    }

    private List<String> pointsOf(String quesId) {
        List<String> names = new ArrayList<>();
        for (ExamQuestionPoint rel : questionPointMapper.selectList(Wrappers.<ExamQuestionPoint>lambdaQuery()
                .eq(ExamQuestionPoint::getQuesId, quesId)
                .orderByAsc(ExamQuestionPoint::getOrderIndex))) {
            ExamPoint p = pointMapper.selectById(rel.getPointId());
            if (p != null) {
                names.add(p.getName());
            }
        }
        return names;
    }

    private String correctAnswerOf(ExamQuestion ques) {
        Integer type = ques.getType();
        if (type != null && (type == 0 || type == 1)) {
            StringBuilder sb = new StringBuilder();
            List<ExamQuestionItem> items = itemsOf(ques.getId());
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).getAnswer() != null && items.get(i).getAnswer() == 1) {
                    sb.append((char) ('A' + i));
                }
            }
            return sb.toString();
        }
        if (type != null && type == 2) {
            return ques.getJudgeAnswer() != null && ques.getJudgeAnswer() == 0 ? "正确" : "错误";
        }
        return ques.getAnswerContent();
    }

    /** 考生答案展示：选项下标转字母，其它原样。 */
    private String formatSubmit(String raw) {
        Set<Integer> picked = parseAnswer(raw);
        if (!picked.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Integer i : picked) {
                if (i >= 0 && i < 26) {
                    sb.append((char) ('A' + i));
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        return raw == null ? "" : raw;
    }

    private Set<Integer> parseAnswer(String raw) {
        Set<Integer> out = new LinkedHashSet<>();
        if (!StringUtils.hasText(raw)) {
            return out;
        }
        String text = raw.trim();
        try {
            JsonNode node = JSON.readTree(text);
            if (node.isArray()) {
                for (JsonNode n : node) {
                    addNode(out, n);
                }
            } else if (node.isObject() && node.has("selected")) {
                for (JsonNode n : node.get("selected")) {
                    addNode(out, n);
                }
            } else {
                addNode(out, node);
            }
        } catch (Exception e) {
            for (char ch : text.toUpperCase().toCharArray()) {
                if (ch >= 'A' && ch <= 'Z') {
                    out.add(ch - 'A');
                }
            }
        }
        return out;
    }

    private void addNode(Set<Integer> out, JsonNode n) {
        if (n == null || n.isNull()) {
            return;
        }
        if (n.isInt() || n.isLong()) {
            out.add(n.asInt());
        } else if (n.isTextual()) {
            for (char ch : n.asText().toUpperCase().toCharArray()) {
                if (ch >= 'A' && ch <= 'Z') {
                    out.add(ch - 'A');
                }
            }
        }
    }

    private List<String> splitNames(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return List.of(raw.split("[,，;；]"));
    }

    private String typeName(int type) {
        return type >= 0 && type < TYPE_NAMES.length ? TYPE_NAMES[type] : "未知";
    }

    private String stripHtml(String html) {
        return html == null ? "" : html.replaceAll("<[^>]+>", "").trim();
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }
}
