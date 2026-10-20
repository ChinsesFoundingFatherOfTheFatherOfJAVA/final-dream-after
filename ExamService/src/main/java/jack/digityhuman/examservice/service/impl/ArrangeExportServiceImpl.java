package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.ArrangeDtos;
import jack.digityhuman.examservice.dto.resp.ArrangeStatRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeSignature;
import jack.digityhuman.examservice.entity.ExamArrangeStudent;
import jack.digityhuman.examservice.entity.ExamExportTask;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeSignatureMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeStudentMapper;
import jack.digityhuman.examservice.mapper.ExamExportTaskMapper;
import jack.digityhuman.examservice.service.ArrangeExportService;
import jack.digityhuman.examservice.service.ArrangeService;
import jack.digityhuman.examservice.service.ArrangeStatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导出实现（施工序阶段 6）。
 *
 * <p>同步导出统一返回 xlsx 字节流；API-28 的"异步任务"当前实现为：
 * 同步生成 → 落临时文件 → 记 exam_export_task（status=1、file_url 指向下载接口），
 * 后续接对象存储时只替换落盘那一段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArrangeExportServiceImpl implements ArrangeExportService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] TYPE_NAMES = { "单选题", "多选题", "判断题", "填空题", "问答题", "组合题" };
    private static final String[] LEVEL_NAMES = { "易", "中", "难" };

    private final ArrangeService arrangeService;
    private final ArrangeStatService arrangeStatService;
    private final ExamArrangeMapper arrangeMapper;
    private final ExamArrangeStudentMapper studentMapper;
    private final ExamArrangeSignatureMapper signatureMapper;
    private final ExamExportTaskMapper taskMapper;
    private final CurrentUserService currentUserService;

    @Override
    public byte[] exportArrangeList(ArrangeDtos.ListQuery query) {
        ArrangeDtos.ListQuery q = query == null ? new ArrangeDtos.ListQuery() : query;
        var page = arrangeService.list(1, 10000, "createdAt", "desc", q);
        String[] titles = { "考试名称", "状态", "出卷方式", "应考人数", "已交卷", "已通过", "通过率", "入场开始", "入场结束", "创建人", "创建时间" };
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("考试列表");
            writeHeader(sheet, titles);
            int r = 1;
            for (var it : page.getRecords()) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(it.getName());
                row.createCell(1).setCellValue(statusText(it.getStatus()));
                row.createCell(2).setCellValue(it.getPaperBuildType() != null && it.getPaperBuildType() == 1 ? "随机出卷" : "固定出卷");
                row.createCell(3).setCellValue(n(it.getArrangedQty()));
                row.createCell(4).setCellValue(n(it.getSubmittedQty()));
                row.createCell(5).setCellValue(n(it.getPassedQty()));
                row.createCell(6).setCellValue(percent(it.getPassRate()));
                row.createCell(7).setCellValue(str(it.getEntryStartAt()));
                row.createCell(8).setCellValue(str(it.getEntryEndAt()));
                row.createCell(9).setCellValue(str(it.getCreateFullname()));
                row.createCell(10).setCellValue(str(it.getCreatedAt()));
            }
            autoSize(sheet, titles.length);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException("导出考试列表失败：" + e.getMessage());
        }
    }

    @Override
    public byte[] exportStudents(String arrangeId, String type) {
        ExamArrange arrange = arrangeMapper.selectById(arrangeId);
        if (arrange == null) {
            throw new BizException("考试不存在或已删除");
        }
        List<ExamArrangeStudent> rows = studentMapper.selectList(Wrappers.<ExamArrangeStudent>lambdaQuery()
                .eq(ExamArrangeStudent::getArrangeId, arrangeId));
        boolean answerMode = "answer".equalsIgnoreCase(type);
        String[] titles = answerMode
                ? new String[] { "账号", "姓名", "部门", "岗位", "考试状态", "成绩", "最高分", "考试次数", "靠前切屏次数", "开考时间", "交卷时间", "IP" }
                : new String[] { "账号", "姓名", "部门", "岗位", "考试状态", "通过状态", "成绩", "最高分", "考试次数", "交卷时间" };
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("考生成绩");
            writeHeader(sheet, titles);
            int r = 1;
            for (ExamArrangeStudent s : rows) {
                Row row = sheet.createRow(r++);
                int c = 0;
                row.createCell(c++).setCellValue(str(s.getAccount()));
                row.createCell(c++).setCellValue(str(s.getFullname()));
                row.createCell(c++).setCellValue(str(s.getDeptName()));
                row.createCell(c++).setCellValue(str(s.getPositionName()));
                row.createCell(c++).setCellValue(studentStatus(s.getExamStatus()));
                if (!answerMode) {
                    row.createCell(c++).setCellValue(passText(s.getPassStatus()));
                }
                row.createCell(c++).setCellValue(num(s.getScore()));
                row.createCell(c++).setCellValue(num(s.getHighestScore()));
                row.createCell(c++).setCellValue(n(s.getAttemptQty()));
                if (answerMode) {
                    row.createCell(c++).setCellValue(n(s.getScreenSwitchCount()));
                    row.createCell(c++).setCellValue(str(s.getStartAt()));
                    row.createCell(c++).setCellValue(str(s.getSubmitAt()));
                    row.createCell(c++).setCellValue(str(s.getIp()));
                } else {
                    row.createCell(c++).setCellValue(str(s.getSubmitAt()));
                }
            }
            autoSize(sheet, titles.length);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException("导出考生数据失败：" + e.getMessage());
        }
    }

    @Override
    public byte[] exportSignatures(String arrangeId) {
        List<ExamArrangeSignature> rows = signatureMapper.selectList(Wrappers.<ExamArrangeSignature>lambdaQuery()
                .eq(ExamArrangeSignature::getArrangeId, arrangeId)
                .orderByDesc(ExamArrangeSignature::getSignedAt));
        String[] titles = { "账号", "姓名", "签名文件", "签名时间" };
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("签名记录");
            writeHeader(sheet, titles);
            int r = 1;
            for (ExamArrangeSignature sig : rows) {
                ExamArrangeStudent s = sig.getStudentId() == null ? null : studentMapper.selectById(sig.getStudentId());
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(s == null ? "" : str(s.getAccount()));
                row.createCell(1).setCellValue(s == null ? "" : str(s.getFullname()));
                row.createCell(2).setCellValue(str(sig.getSignatureUrl()));
                row.createCell(3).setCellValue(str(sig.getSignedAt()));
            }
            autoSize(sheet, titles.length);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException("导出签名记录失败：" + e.getMessage());
        }
    }

    @Override
    public byte[] exportQuestionStat(String arrangeId, ArrangeStatRespDtos.QuestionQuery query) {
        var page = arrangeStatService.questionList(arrangeId, 1, 10000, query);
        String[] titles = { "试题名称", "题型", "难度", "考核点", "作答人数", "正确人数", "正确率" };
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("试题分析");
            writeHeader(sheet, titles);
            int r = 1;
            for (var it : page.getRecords()) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(str(it.getSummary()));
                row.createCell(1).setCellValue(it.getQuesType() == null ? "" : TYPE_NAMES[Math.min(it.getQuesType(), 5)]);
                row.createCell(2).setCellValue(it.getLevelType() == null ? "" : LEVEL_NAMES[Math.min(it.getLevelType(), 2)]);
                row.createCell(3).setCellValue(str(it.getPointName()));
                row.createCell(4).setCellValue(n(it.getSubmitQty()));
                row.createCell(5).setCellValue(n(it.getCorrectQty()));
                row.createCell(6).setCellValue(percent(it.getCorrectRate()));
            }
            autoSize(sheet, titles.length);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException("导出试题分析失败：" + e.getMessage());
        }
    }

    @Override
    @Transactional
    public Map<String, Object> createTask(String arrangeId, String type) {
        String uid = currentUserService.userId(null);
        ExamExportTask task = new ExamExportTask();
        task.setId(IdWorker.getIdStr());
        task.setArrangeId(arrangeId);
        task.setExportType(exportTypeOf(type));
        task.setStatus(0);
        task.setCreatedBy(uid);
        task.setCreatedAt(LocalDateTime.now());
        taskMapper.insert(task);

        Map<String, Object> out = new LinkedHashMap<>();
        try {
            byte[] bytes = "answer".equalsIgnoreCase(type)
                    ? exportStudents(arrangeId, "answer")
                    : exportStudents(arrangeId, "highest");
            Path file = Path.of(System.getProperty("java.io.tmpdir"), "exam-export-" + task.getId() + ".xlsx");
            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                fos.write(bytes);
            }
            task.setStatus(1);
            task.setFileUrl("/api/exam/arrange/export-task/" + task.getId() + "/download");
            task.setFinishedAt(LocalDateTime.now());
            task.setExpiresAt(LocalDateTime.now().plusDays(7));
            taskMapper.updateById(task);
            out.put("taskId", task.getId());
            out.put("status", "SUCCESS");
        } catch (Exception e) {
            task.setStatus(2);
            task.setErrorMessage(e.getMessage());
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            out.put("taskId", task.getId());
            out.put("status", "FAILED");
            out.put("errorMessage", e.getMessage());
        }
        return out;
    }

    @Override
    public Map<String, Object> getTask(String taskId) {
        ExamExportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException("导出任务不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", task.getStatus() == null ? "PROCESSING"
                : task.getStatus() == 1 ? "SUCCESS" : task.getStatus() == 2 ? "FAILED" : "PROCESSING");
        out.put("fileUrl", str(task.getFileUrl()));
        out.put("errorMessage", str(task.getErrorMessage()));
        return out;
    }

    @Override
    public byte[] taskFile(String taskId) {
        ExamExportTask task = taskMapper.selectById(taskId);
        if (task == null || task.getStatus() == null || task.getStatus() != 1) {
            throw new BizException("导出文件尚未就绪");
        }
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "exam-export-" + taskId + ".xlsx");
        if (!Files.exists(file)) {
            throw new BizException("导出文件已过期，请重新导出");
        }
        try {
            return Files.readAllBytes(file);
        } catch (Exception e) {
            throw new BizException("读取导出文件失败");
        }
    }

    // ---------------- 内部 ----------------

    private String exportTypeOf(String type) {
        return switch (type == null ? "" : type) {
            case "answer" -> "STUDENT_ANSWER";
            case "all" -> "STUDENT_ALL";
            default -> "STUDENT_HIGHEST";
        };
    }

    private void writeHeader(Sheet sheet, String[] titles) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < titles.length; i++) {
            header.createCell(i).setCellValue(titles[i]);
        }
    }

    private void autoSize(Sheet sheet, int cols) {
        for (int i = 0; i < cols; i++) {
            sheet.setColumnWidth(i, 4000);
        }
    }

    private String statusText(Integer status) {
        int s = status == null ? 0 : status;
        return s == 0 ? "未发布" : s == 2 ? "进行中" : s == 3 ? "已结束" : "未知";
    }

    private String studentStatus(Integer status) {
        int s = status == null ? 0 : status;
        return switch (s) {
            case 0 -> "未开始";
            case 1 -> "考试中";
            case 2 -> "批阅中";
            case 3 -> "已完成";
            case 4 -> "重新考试";
            default -> "未知";
        };
    }

    private String passText(Integer pass) {
        int p = pass == null ? -1 : pass;
        return p == 1 ? "通过" : p == 0 ? "不通过" : "未知";
    }

    private String percent(java.math.BigDecimal r) {
        return r == null ? "0.00%" : r.multiply(java.math.BigDecimal.valueOf(100))
                .setScale(2, java.math.RoundingMode.HALF_UP) + "%";
    }

    private int n(Integer v) {
        return v == null ? 0 : v;
    }

    private double num(java.math.BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }

    private String str(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof LocalDateTime t) {
            return TIME.format(t);
        }
        return String.valueOf(v);
    }
}