package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.dto.req.QuesDtos;
import jack.digityhuman.examservice.dto.resp.QuesRespDtos;
import jack.digityhuman.examservice.entity.ExamLib;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.mapper.ExamLibMapper;
import jack.digityhuman.examservice.mapper.ExamPointMapper;
import jack.digityhuman.examservice.service.QuesImportService;
import jack.digityhuman.examservice.service.QuesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 试题导入实现（FP-12）。
 *
 * <p>模板与导出同构，且由本类就地生成——保证「下载的模板」和「解析器」永远对齐。
 * 逐行 try-catch：某一行格式不对只记入 errors，不影响其它行入库（验收要求）。
 * 题干/选项/解析中的考核点名若在题库里不存在，**自动建考核点**。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuesImportServiceImpl implements QuesImportService {

    private static final String[] TYPE_NAMES = { "单选题", "多选题", "判断题", "填空题", "问答题" };
    private static final String[] LEVEL_NAMES = { "易", "中", "难" };
    private static final String[] HEADERS = { "题目", "考核点", "难度", "试题解析", "正确答案",
            "答案A", "答案B", "答案C", "答案D", "答案E", "答案F", "答案G", "答案H", "答案I" };

    /** Word 分节标记：【单选题】 */
    private static final Pattern SECTION_RE = Pattern.compile("^【(.+?)】$");
    /** Word 题序：1、题干 */
    private static final Pattern INDEX_RE = Pattern.compile("^(\\d+)、(.+)$");
    /** Word 选项：A、xxx */
    private static final Pattern OPTION_RE = Pattern.compile("^([A-Z])、(.+)$");

    private final QuesService quesService;
    private final ExamPointMapper pointMapper;
    private final ExamLibMapper libMapper;

    /** 解析出来的一行（Excel 一行 / Word 一题），与题型无关的中间结构。 */
    private record ParsedRow(int line, String typeName, String levelName, String stem,
                            List<String> options, String answer, String pointNames, String analysis) {
    }

    @Override
    public byte[] template(String format) {
        return "word".equalsIgnoreCase(format) ? wordTemplate() : excelTemplate();
    }

    @Override
    public QuesRespDtos.ImportResult importFile(MultipartFile file, String libId) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要导入的文件");
        }
        if (!StringUtils.hasText(libId)) {
            throw new BizException("缺少题库 id");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try (InputStream in = file.getInputStream()) {
            List<ParsedRow> rows = filename.endsWith(".docx") || filename.endsWith(".doc")
                    ? parseWord(in)
                    : parseExcel(in);
            return doImport(rows, libId);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("试题导入失败: {}", e.getMessage());
            throw new BizException(50020, "导入文件解析异常（非模板格式/文件损坏）");
        }
    }

    // ---------------- 导入主流程 ----------------

    private QuesRespDtos.ImportResult doImport(List<ParsedRow> rows, String libId) {
        QuesRespDtos.ImportResult result = new QuesRespDtos.ImportResult();

        // 题库已有考核点：名称 -> id。导入过程中遇到新名字就地建点并补进这个缓存。
        Map<String, String> pointCache = new HashMap<>();
        for (ExamPoint point : pointMapper.selectList(Wrappers.<ExamPoint>lambdaQuery().eq(ExamPoint::getLibId, libId))) {
            pointCache.putIfAbsent(point.getName(), point.getId());
        }

        for (ParsedRow row : rows) {
            try {
                QuesDtos.SaveReq req = toSaveReq(row, libId, pointCache);
                quesService.create(req);
                result.setSuccessCount(result.getSuccessCount() + 1);
            } catch (Exception e) {
                result.setFailCount(result.getFailCount() + 1);
                String reason = e instanceof BizException ? e.getMessage() : "该行数据不合法";
                result.getErrors().add(new QuesRespDtos.ImportResult.ImportError(row.line(), reason));
            }
        }

        refreshPointQty(libId);
        return result;
    }

    private QuesDtos.SaveReq toSaveReq(ParsedRow row, String libId, Map<String, String> pointCache) {
        int type = typeOf(row.typeName());
        QuesDtos.SaveReq req = new QuesDtos.SaveReq();
        req.setLibId(libId);
        req.setType(type);
        req.setLevelType(levelOf(row.levelName()));
        req.setContent(row.stem());
        req.setSummary(row.stem());
        req.setExplainText(row.analysis());
        req.setNoOrderMark(type == 1 ? 1 : 0);
        req.setPoints(resolvePoints(libId, row.pointNames(), pointCache));

        String answer = row.answer() == null ? "" : row.answer().trim();
        if (answer.isEmpty()) {
            throw new BizException("答案不能为空");
        }
        if (type == 0 || type == 1) {
            if (row.options().size() < 2) {
                throw new BizException("选择题至少需要 2 个选项");
            }
            String letters = answer.toUpperCase().replaceAll("[^A-Z]", "");
            List<QuesDtos.ItemReq> items = new ArrayList<>();
            for (int i = 0; i < row.options().size(); i++) {
                QuesDtos.ItemReq item = new QuesDtos.ItemReq();
                item.setItemContent(row.options().get(i));
                item.setAnswer(letters.indexOf((char) ('A' + i)) >= 0 ? 1 : 0);
                item.setOrderIndex(i + 1);
                items.add(item);
            }
            req.setQuestionItems(items);
        } else if (type == 2) {
            req.setJudgeCorrectOptionContent("正确");
            req.setJudgeWrongOptionContent("错误");
            req.setJudgeAnswer(answer.contains("对") && !answer.contains("错") ? 0 : 1);
        } else {
            req.setAnswerContent(answer);
            req.setAnswerKeywords(new ArrayList<>());
        }
        return req;
    }

    /** 考核点名 → id；不存在的当场建点（FP-12：导入文本中的新考核点自动入库）。 */
    private List<QuesDtos.PointRef> resolvePoints(String libId, String names, Map<String, String> cache) {
        List<QuesDtos.PointRef> refs = new ArrayList<>();
        if (!StringUtils.hasText(names)) {
            return refs;
        }
        String[] parts = names.split("[；;]");
        int order = 0;
        for (String raw : parts) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            String id = cache.get(name);
            if (id == null) {
                ExamPoint created = new ExamPoint();
                created.setId(IdWorker.getIdStr());
                created.setLibId(libId);
                created.setName(name);
                created.setDefaultPoint(0);
                created.setCreatedAt(LocalDateTime.now());
                pointMapper.insert(created);
                cache.put(name, created.getId());
                id = created.getId();
            }
            QuesDtos.PointRef ref = new QuesDtos.PointRef();
            ref.setPointId(id);
            ref.setOrderIndex(++order);
            refs.add(ref);
        }
        return refs;
    }

    /** 导入后重算考点数，避免详情页的"考核点"计数停留在建库时的 1。 */
    private void refreshPointQty(String libId) {
        Long count = pointMapper.selectCount(Wrappers.<ExamPoint>lambdaQuery().eq(ExamPoint::getLibId, libId));
        ExamLib lib = libMapper.selectById(libId);
        if (lib != null) {
            lib.setPointQty(count == null ? 0 : count.intValue());
            libMapper.updateById(lib);
        }
    }

    // ---------------- Excel ----------------

    private byte[] excelTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int type : new int[] { 0, 1, 3, 4, 2 }) {
                Sheet sheet = workbook.createSheet(TYPE_NAMES[type]);
                Row header = sheet.createRow(0);
                for (int i = 0; i < HEADERS.length; i++) {
                    header.createCell(i).setCellValue(HEADERS[i]);
                }
                Row sample = sheet.createRow(1);
                sample.createCell(0).setCellValue(type == 2 ? "原子性是面向三大特征之一。" : "下面哪一项不属于面向对象的特性。");
                sample.createCell(1).setCellValue("示例考核点");
                sample.createCell(2).setCellValue("中");
                sample.createCell(3).setCellValue("示例解析（可留空）");
                sample.createCell(4).setCellValue(type == 1 ? "A C D" : type == 2 ? "错误" : type == 3 || type == 4 ? "示例答案" : "B");
                if (type == 0 || type == 1) {
                    sample.createCell(5).setCellValue("封装性");
                    sample.createCell(6).setCellValue("原子性");
                    sample.createCell(7).setCellValue("继承性");
                    sample.createCell(8).setCellValue("多态性");
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException("模板生成失败");
        }
    }

    private List<ParsedRow> parseExcel(InputStream in) throws IOException {
        List<ParsedRow> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(in)) {
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                String typeName = sheet.getSheetName();
                if (typeOf(typeName) < 0) {
                    continue;
                }
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    String stem = cell(row, 0);
                    if (!StringUtils.hasText(stem)) {
                        continue;
                    }
                    List<String> options = new ArrayList<>();
                    for (int c = 5; c <= 13; c++) {
                        String option = cell(row, c);
                        if (StringUtils.hasText(option)) {
                            options.add(option);
                        }
                    }
                    rows.add(new ParsedRow(r + 1, typeName, cell(row, 2), stem, options,
                            cell(row, 4), cell(row, 1), cell(row, 3)));
                }
            }
        }
        return rows;
    }

    private String cell(Row row, int index) {
        Cell cell = row.getCell(index);
        return cell == null ? "" : new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(cell).trim();
    }

    // ---------------- Word ----------------

    private byte[] wordTemplate() {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            paragraph(doc, "【单选题】", true);
            paragraph(doc, "1、下面哪一项不属于面向对象的特性。", false);
            paragraph(doc, "A、封装性", false);
            paragraph(doc, "B、原子性", false);
            paragraph(doc, "C、继承性", false);
            paragraph(doc, "D、多态性", false);
            paragraph(doc, "【正确答案】B", false);
            paragraph(doc, "【难易度】中", false);
            paragraph(doc, "【考核点】示例考核点", false);
            paragraph(doc, "【试题解析】示例解析（可留空）", false);
            paragraph(doc, "【判断题】", true);
            paragraph(doc, "1、原子性是面向三大特征之一。", false);
            paragraph(doc, "【正确答案】错误", false);
            paragraph(doc, "【难易度】易", false);
            paragraph(doc, "【考核点】示例考核点", false);
            paragraph(doc, "【试题解析】", false);
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException("模板生成失败");
        }
    }

    /** Word 解析：按【题型】分节、按 "序号、" 切题、按 "A、" 收选项、按【字段】收其余。 */
    private List<ParsedRow> parseWord(InputStream in) throws IOException {
        List<ParsedRow> rows = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(in)) {
            String typeName = null;
            String level = "";
            String stem = null;
            String answer = "";
            String points = "";
            String analysis = "";
            List<String> options = new ArrayList<>();
            int line = 0;
            int index = 0;

            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                String text = paragraph.getText() == null ? "" : paragraph.getText().trim();
                line++;
                if (text.isEmpty()) {
                    continue;
                }
                Matcher section = SECTION_RE.matcher(text);
                if (section.matches()) {
                    if (stem != null && typeName != null) {
                        rows.add(new ParsedRow(index, typeName, level, stem, options, answer, points, analysis));
                        stem = null;
                    }
                    typeName = section.group(1);
                    level = "";
                    options = new ArrayList<>();
                    answer = "";
                    points = "";
                    analysis = "";
                    continue;
                }
                if (typeName == null) {
                    continue;
                }
                Matcher idx = INDEX_RE.matcher(text);
                if (idx.matches()) {
                    if (stem != null) {
                        rows.add(new ParsedRow(index, typeName, level, stem, options, answer, points, analysis));
                    }
                    index = Integer.parseInt(idx.group(1));
                    stem = idx.group(2).trim();
                    options = new ArrayList<>();
                    answer = "";
                    points = "";
                    analysis = "";
                    continue;
                }
                if (text.startsWith("【正确答案】")) {
                    answer = text.substring("【正确答案】".length()).trim();
                    continue;
                }
                if (text.startsWith("【难易度】")) {
                    level = text.substring("【难易度】".length()).trim();
                    continue;
                }
                if (text.startsWith("【考核点】")) {
                    points = text.substring("【考核点】".length()).trim();
                    continue;
                }
                if (text.startsWith("【试题解析】")) {
                    analysis = text.substring("【试题解析】".length()).trim();
                    continue;
                }
                Matcher option = OPTION_RE.matcher(text);
                if (option.matches()) {
                    options.add(option.group(2).trim());
                }
            }
            if (stem != null && typeName != null) {
                rows.add(new ParsedRow(index, typeName, level, stem, options, answer, points, analysis));
            }
        }
        return rows;
    }

    private void paragraph(XWPFDocument doc, String text, boolean bold) {
        XWPFParagraph paragraph = doc.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setBold(bold);
        run.setText(text);
    }

    // ---------------- 取值 ----------------

    private int typeOf(String typeName) {
        if (typeName == null) {
            return -1;
        }
        String name = typeName.trim();
        for (int i = 0; i < TYPE_NAMES.length; i++) {
            if (TYPE_NAMES[i].equals(name) || TYPE_NAMES[i].replace("题", "").equals(name.replace("题", ""))) {
                return i;
            }
        }
        return -1;
    }

    private int levelOf(String levelName) {
        if (levelName != null) {
            for (int i = 0; i < LEVEL_NAMES.length; i++) {
                if (LEVEL_NAMES[i].equals(levelName.trim())) {
                    return i;
                }
            }
        }
        return 1;
    }
}
