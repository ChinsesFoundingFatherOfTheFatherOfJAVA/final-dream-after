package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.dto.req.QuesDtos;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.entity.ExamQuestionItem;
import jack.digityhuman.examservice.entity.ExamQuestionPoint;
import jack.digityhuman.examservice.mapper.ExamPointMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionItemMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionPointMapper;
import jack.digityhuman.examservice.service.QuesExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 试题导出实现（FP-13）。
 *
 * <p>Excel 与 Word 的列/段落结构照《导出样例》（观察素材/导出样例）与 01-需求文档 FP-13：
 * Excel 五 Sheet 分题型，列 = 题目/考核点/难度/试题解析/正确答案/答案A~I；
 * Word 按题型分节，每题 = 序号、题干 → A、选项 → 【正确答案】→【难易度】→【考核点】→【试题解析】。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuesExportServiceImpl implements QuesExportService {

    private static final String[] TYPE_NAMES = { "单选题", "多选题", "判断题", "填空题", "问答题", "组合题" };
    private static final String[] LEVEL_NAMES = { "易", "中", "难" };
    /** 导出 Sheet 顺序按需求/样例：单选/多选/填空/问答/判断（组合题不进导出）。 */
    private static final int[] EXPORT_TYPES = { 0, 1, 3, 4, 2 };
    private static final int MAX_POINTS = 5;
    private static final int MAX_OPTIONS = 9;

    private final ExamQuestionMapper questionMapper;
    private final ExamQuestionItemMapper itemMapper;
    private final ExamQuestionPointMapper questionPointMapper;
    private final ExamPointMapper pointMapper;

    @Override
    public byte[] exportExcel(QuesDtos.ListQuery query) {
        List<ExamQuestion> questions = queryQuestions(query);
        Map<String, List<ExamQuestionItem>> items = itemsOf(questions);
        Map<String, List<String>> points = pointsOf(questions);

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int type : EXPORT_TYPES) {
                Sheet sheet = workbook.createSheet(TYPE_NAMES[type]);
                Row header = sheet.createRow(0);
                String[] titles = { "题目", "考核点", "难度", "试题解析", "正确答案",
                        "答案A", "答案B", "答案C", "答案D", "答案E", "答案F", "答案G", "答案H", "答案I" };
                for (int i = 0; i < titles.length; i++) {
                    header.createCell(i).setCellValue(titles[i]);
                }

                int rowIndex = 1;
                for (ExamQuestion ques : questions) {
                    if (ques.getType() == null || ques.getType() != type) {
                        continue;
                    }
                    Row row = sheet.createRow(rowIndex++);
                    row.createCell(0).setCellValue(plain(ques.getSummary()));
                    row.createCell(1).setCellValue(String.join("；", points.getOrDefault(ques.getId(), List.of())));
                    row.createCell(2).setCellValue(levelName(ques.getLevelType()));
                    row.createCell(3).setCellValue(plain(ques.getExplainText()));
                    row.createCell(4).setCellValue(answerText(ques, items.get(ques.getId())));
                    List<ExamQuestionItem> options = items.getOrDefault(ques.getId(), List.of());
                    for (int i = 0; i < MAX_OPTIONS && i < options.size(); i++) {
                        row.createCell(5 + i).setCellValue(plain(options.get(i).getItemContent()));
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("导出 Excel 失败: {}", e.getMessage());
            throw new BizException("导出失败，请重试");
        }
    }

    @Override
    public byte[] exportWord(QuesDtos.ListQuery query) {
        List<ExamQuestion> questions = queryQuestions(query);
        Map<String, List<ExamQuestionItem>> items = itemsOf(questions);
        Map<String, List<String>> points = pointsOf(questions);

        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int type : EXPORT_TYPES) {
                List<ExamQuestion> ofType = questions.stream()
                        .filter(q -> q.getType() != null && q.getType() == type)
                        .toList();
                if (ofType.isEmpty()) {
                    continue;
                }
                paragraph(doc, "【" + TYPE_NAMES[type] + "】", true);
                int index = 1;
                for (ExamQuestion ques : ofType) {
                    paragraph(doc, index + "、" + plain(ques.getSummary()), false);
                    List<ExamQuestionItem> options = items.getOrDefault(ques.getId(), List.of());
                    for (int i = 0; i < options.size(); i++) {
                        paragraph(doc, (char) ('A' + i) + "、" + plain(options.get(i).getItemContent()), false);
                    }
                    paragraph(doc, "【正确答案】" + answerText(ques, options), false);
                    paragraph(doc, "【难易度】" + levelName(ques.getLevelType()), false);
                    paragraph(doc, "【考核点】" + String.join("；", points.getOrDefault(ques.getId(), List.of())), false);
                    paragraph(doc, "【试题解析】" + plain(ques.getExplainText()), false);
                    index++;
                }
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("导出 Word 失败: {}", e.getMessage());
            throw new BizException("导出失败，请重试");
        }
    }

    // ---------------- 内部 ----------------

    /** 与列表同口径的筛选（不分页）；组合题子题不导出。 */
    private List<ExamQuestion> queryQuestions(QuesDtos.ListQuery query) {
        QuesDtos.ListQuery q = query == null ? new QuesDtos.ListQuery() : query;
        if (!StringUtils.hasText(q.getLibId())) {
            throw new BizException("缺少题库 id");
        }
        var wrapper = Wrappers.<ExamQuestion>lambdaQuery()
                .eq(ExamQuestion::getLibId, q.getLibId())
                .and(w -> w.isNull(ExamQuestion::getParentId).or().eq(ExamQuestion::getParentId, ""));
        if (q.getType() != null && q.getType() >= 0 && q.getType() <= 4) {
            wrapper.eq(ExamQuestion::getType, q.getType());
        } else {
            wrapper.in(ExamQuestion::getType, 0, 1, 2, 3, 4);
        }
        if (q.getLevelType() != null && q.getLevelType() >= 0) {
            wrapper.eq(ExamQuestion::getLevelType, q.getLevelType());
        }
        if (q.getDisabled() != null && q.getDisabled() >= 0) {
            wrapper.eq(ExamQuestion::getDisabled, q.getDisabled());
        }
        if (StringUtils.hasText(q.getKeyword())) {
            wrapper.like(ExamQuestion::getSummary, q.getKeyword().trim());
        }
        if (q.getIsDuplicate() != null && q.getIsDuplicate() == 1) {
            wrapper.eq(ExamQuestion::getDuplicate, 1);
        }
        if (StringUtils.hasText(q.getPointId())) {
            List<String> quesIds = questionPointMapper.selectList(Wrappers.<ExamQuestionPoint>lambdaQuery()
                            .eq(ExamQuestionPoint::getPointId, q.getPointId()))
                    .stream().map(ExamQuestionPoint::getQuesId).distinct().toList();
            if (quesIds.isEmpty()) {
                return List.of();
            }
            wrapper.in(ExamQuestion::getId, quesIds);
        }
        wrapper.orderByAsc(ExamQuestion::getCreatedAt);
        return questionMapper.selectList(wrapper);
    }

    private Map<String, List<ExamQuestionItem>> itemsOf(List<ExamQuestion> questions) {
        Map<String, List<ExamQuestionItem>> result = new HashMap<>();
        if (questions.isEmpty()) {
            return result;
        }
        List<String> ids = questions.stream().map(ExamQuestion::getId).toList();
        List<ExamQuestionItem> all = itemMapper.selectList(Wrappers.<ExamQuestionItem>lambdaQuery()
                .in(ExamQuestionItem::getQuesId, ids)
                .orderByAsc(ExamQuestionItem::getOrderIndex));
        for (ExamQuestionItem item : all) {
            result.computeIfAbsent(item.getQuesId(), k -> new ArrayList<>()).add(item);
        }
        return result;
    }

    private Map<String, List<String>> pointsOf(List<ExamQuestion> questions) {
        Map<String, List<String>> result = new HashMap<>();
        if (questions.isEmpty()) {
            return result;
        }
        List<String> ids = questions.stream().map(ExamQuestion::getId).toList();
        List<ExamQuestionPoint> rels = questionPointMapper.selectList(Wrappers.<ExamQuestionPoint>lambdaQuery()
                .in(ExamQuestionPoint::getQuesId, ids)
                .orderByAsc(ExamQuestionPoint::getOrderIndex));
        Map<String, String> names = new HashMap<>();
        for (ExamPoint point : pointMapper.selectBatchIds(rels.stream().map(ExamQuestionPoint::getPointId).distinct().toList())) {
            names.put(point.getId(), point.getName());
        }
        for (ExamQuestionPoint rel : rels) {
            List<String> list = result.computeIfAbsent(rel.getQuesId(), k -> new ArrayList<>());
            if (list.size() < MAX_POINTS) {
                String name = names.get(rel.getPointId());
                if (StringUtils.hasText(name)) {
                    list.add(name);
                }
            }
        }
        return result;
    }

    /** 正确答案：选择/判断回字母或对错，填空/问答回答案文本。 */
    private String answerText(ExamQuestion ques, List<ExamQuestionItem> options) {
        Integer type = ques.getType();
        if (type != null && (type == 0 || type == 1)) {
            StringBuilder letters = new StringBuilder();
            if (options != null) {
                for (int i = 0; i < options.size(); i++) {
                    if (options.get(i).getAnswer() != null && options.get(i).getAnswer() == 1) {
                        letters.append((char) ('A' + i));
                    }
                }
            }
            return letters.toString();
        }
        if (type != null && type == 2) {
            return ques.getJudgeAnswer() != null && ques.getJudgeAnswer() == 0 ? "正确" : "错误";
        }
        return ques.getAnswerContent() == null ? "" : ques.getAnswerContent();
    }

    private String levelName(Integer levelType) {
        if (levelType == null || levelType < 0 || levelType >= LEVEL_NAMES.length) {
            return "";
        }
        return LEVEL_NAMES[levelType];
    }

    /** 导出的文本不带 HTML 标签（原平台导出样例也是纯文本）。 */
    private String plain(String html) {
        return html == null ? "" : html.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }

    private void paragraph(XWPFDocument doc, String text, boolean bold) {
        XWPFParagraph paragraph = doc.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setBold(bold);
        run.setText(text);
    }
}
