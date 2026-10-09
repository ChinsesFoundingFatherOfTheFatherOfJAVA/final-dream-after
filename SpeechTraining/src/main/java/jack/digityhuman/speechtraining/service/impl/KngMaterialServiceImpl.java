package jack.digityhuman.speechtraining.service.impl;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.dto.resp.AiGenRespDtos;
import jack.digityhuman.speechtraining.service.KngMaterialService;
import jack.digityhuman.speechtraining.service.MaterialExtractService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 在线课堂素材服务实现（SG-03b/03c）。
 *
 * <p>跨应用只读同库 kng_courseware/kng_courseware_file（02 §1 决策：final_dream 同库直查，
 * 组织架构模块先例）。字段白名单=列表四列+file 的 storage_path/file_name（02 §8 风险表：
 * 课件模块 DDL 变更需同步本类）。文件读取走 kng 磁盘根目录（与 OnlineClassroom StorageService
 * 同一约定路径 /kng-files/**）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KngMaterialServiceImpl implements KngMaterialService {

    /** 文档类课件类型（课件管理 v1.0：1文档 6SCORM 7HTML——SCORM/HTML 压缩包也可能含文档，但按 01 拍板仅文档类） */
    private static final int TYPE_DOCUMENT = 1;
    /** 文档类扩展名（与本地素材三入口口径一致） */
    private static final Set<String> DOC_EXTENSIONS = Set.of("doc", "docx", "ppt", "pptx", "pps", "pdf");

    private final JdbcTemplate jdbcTemplate;
    private final MaterialExtractService materialExtractService;

    /** kng 磁盘根目录（与 OnlineClassroom kng.upload.dir 同一约定；跨应用读文件用） */
    @Value("${ai.gen.kng-files-root:D:/JACK/final_dream/.runtime-kng-files}")
    private String kngFilesRoot;

    @Override
    public AiGenRespDtos.KngMaterialItem[] page(int pageNo, int pageSize, String keyword) {
        int page = Math.max(1, pageNo);
        int size = Math.min(50, Math.max(1, pageSize));
        String like = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";

        // 只读白名单查询：已发布(status=2)+上架(on_sale=1)+类型=文档+存在文档类源文件（EXISTS）
        String baseWhere = """
                FROM kng_courseware c
                WHERE c.status = 2 AND c.on_sale = 1 AND c.type = ?
                  AND EXISTS (SELECT 1 FROM kng_courseware_file f
                              WHERE f.courseware_id = c.id AND f.storage_path IS NOT NULL
                                AND (LOWER(f.file_name) LIKE '%.doc' OR LOWER(f.file_name) LIKE '%.docx'
                                     OR LOWER(f.file_name) LIKE '%.ppt' OR LOWER(f.file_name) LIKE '%.pptx'
                                     OR LOWER(f.file_name) LIKE '%.pps' OR LOWER(f.file_name) LIKE '%.pdf'))
                """;
        String where = baseWhere + (like == null ? "" : " AND c.title LIKE ?");
        Object[] params = like == null
                ? new Object[]{TYPE_DOCUMENT}
                : new Object[]{TYPE_DOCUMENT, like};

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + where, Long.class, params);
        int offset = (page - 1) * size;
        Object[] pageParams = new Object[params.length + 2];
        System.arraycopy(params, 0, pageParams, 0, params.length);
        pageParams[params.length] = size;
        pageParams[params.length + 1] = offset;

        List<AiGenRespDtos.KngMaterialItem> records = jdbcTemplate.query(
                "SELECT c.id, c.title, c.study_score, c.standard_minutes, c.standard_seconds, c.created_by_name "
                        + where + " ORDER BY c.created_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    AiGenRespDtos.KngMaterialItem item = new AiGenRespDtos.KngMaterialItem();
                    item.setId(rs.getString("id"));
                    item.setTitle(rs.getString("title"));
                    item.setCredits(rs.getObject("study_score") == null
                            ? null : rs.getDouble("study_score"));
                    item.setDurationText(durationText(
                            (int) rs.getObject("standard_minutes", Integer.class),
                            (int) rs.getObject("standard_seconds", Integer.class)));
                    item.setCreatedByName(rs.getString("created_by_name"));
                    return item;
                },
                pageParams);
        return records.toArray(new AiGenRespDtos.KngMaterialItem[0]);
    }

    @Override
    public AiGenRespDtos.MaterialExtractResp extractByCourseware(String coursewareId) {
        // 取第一个文档类源文件（sort_order 优先）
        List<FileRow> files = jdbcTemplate.query(
                "SELECT f.storage_path, f.file_name FROM kng_courseware_file f "
                        + "WHERE f.courseware_id = ? AND f.storage_path IS NOT NULL "
                        + "ORDER BY f.sort_order, f.id",
                (rs, rowNum) -> new FileRow(rs.getString("storage_path"), rs.getString("file_name")),
                coursewareId);
        FileRow target = files.stream()
                .filter(f -> DOC_EXTENSIONS.contains(extensionOf(f.fileName())))
                .findFirst()
                .orElseThrow(() -> new BizException("该课件没有文档类素材文件"));

        Path path = resolveInsideRoot(target.storagePath());
        if (!Files.isRegularFile(path)) {
            throw new BizException("课件源文件不存在: " + target.fileName());
        }
        MaterialExtractService.ExtractResult result =
                materialExtractService.extract(path, target.fileName());
        AiGenRespDtos.MaterialExtractResp resp = new AiGenRespDtos.MaterialExtractResp();
        resp.setFileName(target.fileName());
        resp.setText(result.text());
        resp.setTruncated(result.truncated());
        resp.setChars(result.chars());
        return resp;
    }

    /** storagePath（/kng-files/202610/xx.doc）→ 磁盘根内绝对路径；越界拒绝。 */
    private Path resolveInsideRoot(String storagePath) {
        String relative = storagePath == null ? "" : storagePath.trim();
        if (relative.startsWith("/kng-files/")) {
            relative = relative.substring("/kng-files/".length());
        } else if (relative.startsWith("kng-files/")) {
            relative = relative.substring("kng-files/".length());
        }
        Path root = Path.of(kngFilesRoot).toAbsolutePath().normalize();
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) {
            throw new BizException("文件路径不合法");
        }
        return path;
    }

    /** 课件管理同款时长文案口径（0分0秒→空串；仅秒→"N 秒"；仅分→"N 分"；否则"X 分 Y 秒"）。 */
    private String durationText(Integer minutes, Integer seconds) {
        int m = minutes == null ? 0 : minutes;
        int s = seconds == null ? 0 : seconds;
        if (m == 0 && s == 0) {
            return "";
        }
        if (m == 0) {
            return s + " 秒";
        }
        if (s == 0) {
            return m + " 分";
        }
        return m + " 分 " + s + " 秒";
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }

    private record FileRow(String storagePath, String fileName) {
    }
}
