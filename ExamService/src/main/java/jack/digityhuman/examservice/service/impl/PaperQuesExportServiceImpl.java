package jack.digityhuman.examservice.service.impl;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.dto.resp.PaperQuesRespDtos;
import jack.digityhuman.examservice.service.PaperQuesExportService;
import jack.digityhuman.examservice.service.PaperQuesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;

/** 试卷试题列表导出实现：列与只读回看页保持一致。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperQuesExportServiceImpl implements PaperQuesExportService {

    private static final String[] TYPE_NAMES = { "单选题", "多选题", "判断题", "填空题", "问答题", "组合题" };
    private static final String[] LEVEL_NAMES = { "易", "中", "难" };
    private static final int EXPORT_LIMIT = 10000;

    private final PaperQuesService paperQuesService;

    @Override
    public byte[] exportQuestions(String paperId, int type, int levelType, String keyword) {
        var page = paperQuesService.questions(paperId, 1, EXPORT_LIMIT, type, levelType, keyword);
        String[] titles = { "试题名称", "题型", "考核点", "所属题库", "难度" };

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("试卷试题");
            Row header = sheet.createRow(0);
            for (int i = 0; i < titles.length; i++) {
                header.createCell(i).setCellValue(titles[i]);
            }

            int rowIndex = 1;
            for (PaperQuesRespDtos.PaperQuesItem item : page.getRecords()) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(plain(item.getSummary()));
                row.createCell(1).setCellValue(typeName(item.getType()));
                row.createCell(2).setCellValue(str(item.getMainPointName()));
                row.createCell(3).setCellValue(str(item.getLibName()));
                row.createCell(4).setCellValue(levelName(item.getLevelType()));
            }

            int[] widths = { 42, 12, 20, 22, 10 };
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("导出试卷试题失败: paperId={}, message={}", paperId, e.getMessage());
            throw new BizException("导出试卷试题失败：" + e.getMessage());
        }
    }

    private String typeName(Integer type) {
        if (type == null || type < 0 || type >= TYPE_NAMES.length) {
            return "";
        }
        return TYPE_NAMES[type];
    }

    private String levelName(Integer levelType) {
        if (levelType == null || levelType < 0 || levelType >= LEVEL_NAMES.length) {
            return "";
        }
        return LEVEL_NAMES[levelType];
    }

    private String plain(String html) {
        return html == null ? "" : html.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }

    private String str(String value) {
        return value == null ? "" : value;
    }
}
