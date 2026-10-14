package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.PaperQuesDtos;
import jack.digityhuman.examservice.dto.req.QuesDtos;
import jack.digityhuman.examservice.entity.ExamPaper;
import jack.digityhuman.examservice.entity.ExamQuestionPoint;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.mapper.ExamPaperMapper;
import jack.digityhuman.examservice.mapper.ExamPointMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionPointMapper;
import jack.digityhuman.examservice.service.PaperImportService;
import jack.digityhuman.examservice.service.PaperQuesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快速出卷导入实现（FP-12）。
 *
 * <p>模板随应用分发（classpath:paper/试卷导入模板.xlsx），表头在第 3 行、数据从第 4 行开始。
 * 一份 Excel = 一张固定卷：逐 sheet 按题型解析，题目内容经 {@link PaperQuesService} 同源写入题库，
 * 同时按模板里的分值建试卷关联。逐行 try-catch，错误行只记账不阻断其它行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperImportServiceImpl implements PaperImportService {

    private static final String TEMPLATE_PATH = "paper/试卷导入模板.xlsx";
    /** 表头行（0 基）：第 0 行是模板说明、第 1 行空、第 2 行才是列名。 */
    private static final int HEADER_ROW = 2;
    /** 5 个 sheet 名 → 题型值（组合题不参与导入）。 */
    private static final Map<String, Integer> SHEET_TYPES = Map.of(
            "单选题", 0, "多选题", 1, "判断题", 2, "填空题", 3, "问答题", 4);
    private static final String[] LEVEL_NAMES = { "易", "中", "难" };

    private final PaperQuesService paperQuesService;
    private final ExamPaperMapper paperMapper;
    private final ExamPointMapper pointMapper;
    private final ExamQuestionPointMapper questionPointMapper;
    private final CurrentUserService currentUserService;

    private final DataFormatter formatter = new DataFormatter();

    @Override
    public byte[] template() {
        try (InputStream in = new ClassPathResource(TEMPLATE_PATH).getInputStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.error("读取导入模板失败: {}", e.getMessage());
            throw new BizException("模板文件缺失，请联系管理员");
        }
    }

    @Override
    @Transactional
    public Map<String, Object> importFile(MultipartFile file, String libId, String catalogId, Integer secrecy) {
        return importFile(file, libId, catalogId, secrecy, null);
    }

    @Override
    @Transactional
    public Map<String, Object> importFile(MultipartFile file, String libId, String catalogId,
                                          Integer secrecy, String paperId) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要导入的文件");
        }
        if (!StringUtils.hasText(libId)) {
            throw new BizException("请选择目标题库");
        }
        String operator = currentUserService.userId(null);
        // 追加模式：题目直接挂到已有试卷；新建模式：先建卷壳再挂（同一事务，失败整体回滚）
        String targetPaperId = paperId;
        if (StringUtils.hasText(targetPaperId)) {
            ExamPaper exists = paperMapper.selectById(targetPaperId);
            if (exists == null) {
                throw new BizException("目标试卷不存在或已删除");
            }
            if (exists.getStatus() != null && exists.getStatus() == 4) {
                throw new BizException(40050, "试卷已发布，不可导入题目");
            }
        } else {
            targetPaperId = IdWorker.getIdStr();
            ExamPaper paper = new ExamPaper();
            paper.setId(targetPaperId);
            paper.setName("导入试卷-" + LocalDateTime.now().toLocalDate());
            paper.setCatalogId(StringUtils.hasText(catalogId) ? catalogId : "root");
            paper.setBuildType(0);
            paper.setStatus(0);
            paper.setQuesMode(0);
            paper.setSecrecy(secrecy == null ? 0 : secrecy);
            paper.setDisabled(0);
            paper.setTotalScore(BigDecimal.ZERO);
            paper.setTotalQuesQty(0);
            paper.setUsedQty(0);
            paper.setDeleted(0);
            paper.setCreatedBy(operator);
            paper.setCreatedAt(LocalDateTime.now());
            paper.setUpdatedBy(operator);
            paper.setUpdatedAt(LocalDateTime.now());
            paperMapper.insert(paper);
        }

        int success = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        Map<String, String> pointCache = loadPoints(libId);

        try (InputStream in = file.getInputStream(); Workbook workbook = WorkbookFactory.create(in)) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                Integer type = SHEET_TYPES.get(sheet.getSheetName().trim());
                if (type == null) {
                    continue;
                }
                for (int r = HEADER_ROW + 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    String stem = cell(row, 0);
                    if (!StringUtils.hasText(stem)) {
                        continue;
                    }
                    // 模板自带示例行（前缀【示例需删除】），跳过以免误入库。
                    // 兼容两种写法：带方括号的官方模板、以及手写"示例"开头的行。
                    if (stem.startsWith("示例") || stem.startsWith("【示例")) {
                        continue;
                    }
                    try {
                        int score = parseScore(cell(row, 3));
                        QuesDtos.SaveReq req = buildQuestion(type, row, libId, pointCache);
                        PaperQuesDtos.AddReq add = new PaperQuesDtos.AddReq();
                        add.setSectionIndex(type);
                        add.setScore(BigDecimal.valueOf(score));
                        add.setLibId(libId);
                        add.setQuestion(req);
                        paperQuesService.addQuestion(targetPaperId, add);
                        success++;
                    } catch (Exception e) {
                        Map<String, Object> error = new LinkedHashMap<>();
                        error.put("sheet", sheet.getSheetName());
                        error.put("row", r + 1);
                        error.put("reason", e instanceof BizException ? e.getMessage() : "该行数据不合法");
                        errors.add(error);
                    }
                }
            }
        } catch (Exception e) {
            log.error("试卷导入解析失败: {}", e.getMessage());
            throw new BizException(50020, "导入文件解析异常（非模板格式/文件损坏）");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("successCount", success);
        result.put("failCount", errors.size());
        result.put("errors", errors);
        result.put("paperId", targetPaperId);
        return result;
    }

    // ---------------- 内部 ----------------

    private int parseScore(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BizException("分值不能为空");
        }
        try {
            int score = new BigDecimal(raw.trim()).intValue();
            if (score <= 0) {
                throw new BizException("分值需大于 0");
            }
            return score;
        } catch (NumberFormatException e) {
            throw new BizException("分值格式不正确");
        }
    }

    /** 按题型把一行 Excel 转成题库请求体。列位照模板 07 §6 的解析规则。 */
    private QuesDtos.SaveReq buildQuestion(int type, Row row, String libId, Map<String, String> pointCache) {
        String stem = cell(row, 0);
        String pointNames = cell(row, 1);
        String levelName = cell(row, 2);
        String answer;
        String analysis;
        List<String> options = new ArrayList<>();

        if (type == 0 || type == 1) {
            // 单选: 4=解析 5=正确答案 6..=选项；多选: 4=漏选得分 5=解析 6=正确答案 7..=选项
            int analysisIdx = type == 0 ? 4 : 5;
            int answerIdx = type == 0 ? 5 : 6;
            int optionStart = type == 0 ? 6 : 7;
            analysis = cell(row, analysisIdx);
            answer = cell(row, answerIdx);
            for (int c = optionStart; c <= optionStart + 9; c++) {
                String option = cell(row, c);
                if (StringUtils.hasText(option)) {
                    options.add(option);
                }
            }
        } else if (type == 2) {
            analysis = cell(row, 4);
            answer = cell(row, 5);
            options.add("正确");
            options.add("错误");
        } else if (type == 3) {
            // 填空: 4=解析 5=填空顺序 6..=各空答案（备选答案用 && 连接）
            analysis = cell(row, 4);
            List<String> blanks = new ArrayList<>();
            for (int c = 6; c <= 6 + 9; c++) {
                String blank = cell(row, c);
                if (!StringUtils.hasText(blank)) {
                    continue;
                }
                // "答案&&备选" 取主答案
                int idx = blank.indexOf("&&");
                blanks.add(idx >= 0 ? blank.substring(0, idx) : blank);
            }
            answer = String.join("\n", blanks);
        } else {
            // 问答: 4=答案 5=关键词 6=解析
            answer = cell(row, 4);
            analysis = cell(row, 6);
        }

        if (!StringUtils.hasText(answer)) {
            throw new BizException("答案不能为空");
        }

        QuesDtos.SaveReq req = new QuesDtos.SaveReq();
        req.setLibId(libId);
        req.setType(type);
        req.setLevelType(levelOf(levelName));
        req.setContent(stem);
        req.setSummary(stem);
        req.setExplainText(analysis);
        req.setNoOrderMark(type == 1 ? 1 : 0);
        req.setPoints(resolvePoints(libId, pointNames, pointCache));

        if (type == 0 || type == 1) {
            if (options.size() < 2) {
                throw new BizException("选择题至少需要 2 个选项");
            }
            String letters = answer.toUpperCase().replaceAll("[^A-Z]", "");
            if (letters.isEmpty()) {
                throw new BizException("选择题答案需为选项字母");
            }
            List<QuesDtos.ItemReq> items = new ArrayList<>();
            for (int i = 0; i < options.size(); i++) {
                QuesDtos.ItemReq item = new QuesDtos.ItemReq();
                item.setItemContent(options.get(i));
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

    private Map<String, String> loadPoints(String libId) {
        Map<String, String> cache = new HashMap<>();
        for (ExamPoint point : pointMapper.selectList(Wrappers.<ExamPoint>lambdaQuery().eq(ExamPoint::getLibId, libId))) {
            cache.putIfAbsent(point.getName(), point.getId());
        }
        return cache;
    }

    /** 考核点：模板里用「；」分隔，最多 5 个；库里没有的当场建（与题库导入同口径）。 */
    private List<QuesDtos.PointRef> resolvePoints(String libId, String names, Map<String, String> cache) {
        List<QuesDtos.PointRef> refs = new ArrayList<>();
        if (!StringUtils.hasText(names)) {
            return refs;
        }
        int order = 0;
        for (String raw : names.split("[；;]")) {
            String name = raw.trim();
            if (name.isEmpty() || order >= 5) {
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

    private String cell(Row row, int index) {
        Cell cell = row.getCell(index);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }
}
