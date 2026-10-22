package jack.digityhuman.examservice.service;

import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.resp.PaperQuesRespDtos;
import jack.digityhuman.examservice.service.impl.PaperQuesExportServiceImpl;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaperQuesExportServiceImplTest {

    @Mock
    private PaperQuesService paperQuesService;

    @Test
    void exportsTheSameFilteredPaperQuestionColumnsAsTheReadOnlyList() throws Exception {
        PaperQuesRespDtos.PaperQuesItem item = new PaperQuesRespDtos.PaperQuesItem();
        item.setSummary("缝纫机安全操作");
        item.setType(0);
        item.setLevelType(1);
        item.setMainPointName("设备安全");
        item.setLibName("设备题库");

        when(paperQuesService.questions("p1", 1, 10000, 0, -1, "缝纫"))
                .thenReturn(PageResult.of(List.of(item), 1));

        PaperQuesExportService exportService = new PaperQuesExportServiceImpl(paperQuesService);
        byte[] bytes = exportService.exportQuestions("p1", 0, -1, "缝纫");

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("试卷试题");
            assertEquals("试题名称", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("题型", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("考核点", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("所属题库", sheet.getRow(0).getCell(3).getStringCellValue());
            assertEquals("难度", sheet.getRow(0).getCell(4).getStringCellValue());

            Row row = sheet.getRow(1);
            assertEquals("缝纫机安全操作", row.getCell(0).getStringCellValue());
            assertEquals("单选题", row.getCell(1).getStringCellValue());
            assertEquals("设备安全", row.getCell(2).getStringCellValue());
            assertEquals("设备题库", row.getCell(3).getStringCellValue());
            assertEquals("中", row.getCell(4).getStringCellValue());
        }
        verify(paperQuesService).questions("p1", 1, 10000, 0, -1, "缝纫");
    }
}
