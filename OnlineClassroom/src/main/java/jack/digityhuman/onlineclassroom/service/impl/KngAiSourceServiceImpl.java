package jack.digityhuman.onlineclassroom.service.impl;

import jack.digityhuman.common.api.BizException;import jack.digityhuman.onlineclassroom.dto.KngAiSourceDtos;
import jack.digityhuman.onlineclassroom.entity.KngCatalog;
import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import jack.digityhuman.onlineclassroom.entity.KngCoursewareFile;
import jack.digityhuman.onlineclassroom.mapper.KngCatalogMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareFileMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareMapper;
import jack.digityhuman.onlineclassroom.service.CoursewareService;
import jack.digityhuman.onlineclassroom.service.KngAiSourceService;
import jack.digityhuman.onlineclassroom.service.KngDocumentTextExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 素材源服务实现（AI 设计助手 / AI 出题接入在线课堂）
 * <p>
 * 两个入口共用同一份查询与抽取，靠 {@code purpose} 只区分抽取上限。列表固定只返回
 * "已发布 + 已上架 + 文档 + 有文档源文件"的课件——草稿/下架/隐藏态资源若被选进 AI 材料，
 * 学员端后续打开课件会 404，因此入口处就挡住。
 * <p>
 * 分页 total 与列表用同一套筛选条件（互不复用"取当页条数"这种伪总数），前端"共 N 条"才可信。
 */
@Slf4j
@Service
public class KngAiSourceServiceImpl implements KngAiSourceService {

    /** 文档类课件（首期唯一开放类型） */
    private static final int DOCUMENT_TYPE = 1;
    /** 允许抽取的文档扩展名（与抽取实现的白名单一致） */
    private static final List<String> DOC_EXTENSIONS = List.of("doc", "docx", "ppt", "pptx", "pps", "pdf");
    /** 设计助手材料上限：30,000 字（与 MaterialTextSummarizer 截断阈值一致） */
    private static final int DESIGN_ASSISTANT_MAX_CHARS = 30_000;
    /** AI 出题素材上限：20,000 字（与出题接口 materialText 上限一致） */
    private static final int QUESTION_MAX_CHARS = 20_000;
    /** 排序白名单，非白名单值统一回落更新时间 */
    private static final List<String> ORDER_BY_WHITELIST = List.of("updateTime", "credits", "duration");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KngCoursewareMapper coursewareMapper;
    private final KngCoursewareFileMapper fileMapper;
    private final KngCatalogMapper catalogMapper;
    private final CoursewareService coursewareService;
    private final KngDocumentTextExtractor textExtractor;

    public KngAiSourceServiceImpl(KngCoursewareMapper coursewareMapper,
                                  KngCoursewareFileMapper fileMapper,
                                  KngCatalogMapper catalogMapper,
                                  CoursewareService coursewareService,
                                  KngDocumentTextExtractor textExtractor) {
        this.coursewareMapper = coursewareMapper;
        this.fileMapper = fileMapper;
        this.catalogMapper = catalogMapper;
        this.coursewareService = coursewareService;
        this.textExtractor = textExtractor;
    }

    @Override
    public KngAiSourceDtos.ListResponse list(int pageNo, int pageSize, String orderBy, String orderType,
                                             KngAiSourceDtos.ListQuery query) {
        int safePage = Math.max(1, pageNo);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        String safeOrderBy = ORDER_BY_WHITELIST.contains(orderBy) ? orderBy : "updateTime";
        String safeOrderType = "asc".equalsIgnoreCase(orderType) ? "asc" : "desc";
        KngAiSourceDtos.ListQuery filter = query == null
                ? new KngAiSourceDtos.ListQuery(null, null, null, null, 1, null, null, true)
                : query;

        List<String> catalogIds = StringUtils.hasText(filter.catalogId())
                ? (filter.includeChildCatalog() != null && filter.includeChildCatalog() == 0
                ? List.of(filter.catalogId()) : coursewareService.descendantCatalogIds(filter.catalogId()))
                : List.of();
        // 候选集合：本表按扩展名算出的"有文档源文件"的课件 id，跨表条件不进 XML
        List<String> candidateIds = fileMapper.selectCoursewareIdsWithExtensions(DOC_EXTENSIONS);

        KngAiSourceDtos.ListResponse response = new KngAiSourceDtos.ListResponse();
        if (candidateIds.isEmpty()) {
            // 一门文档课件都没有时不再查主表：既省一次 COUNT，也避免 in () 空集合的方言差异
            response.setTotal(0);
            response.setRecords(List.of());
            return response;
        }
        response.setTotal(coursewareMapper.countAiSources(filter, catalogIds, candidateIds));
        if (response.getTotal() == 0) {
            response.setRecords(List.of());
            return response;
        }
        List<KngCourseware> page = coursewareMapper.selectPageAiSources(
                filter, catalogIds, candidateIds, safeOrderBy, safeOrderType,
                (safePage - 1) * safeSize, safeSize);
        response.setRecords(toListItems(page));
        return response;
    }

    @Override
    public KngAiSourceDtos.ExtractResult extract(String coursewareId, String purpose) {
        if (!StringUtils.hasText(coursewareId)) {
            throw new BizException("课件 id 不能为空");
        }
        KngCourseware courseware = coursewareMapper.selectById(coursewareId);
        if (courseware == null) {
            throw new BizException("课件不存在或不可用");
        }
        // 与列表同口径：草稿/下架/非文档类型一律拒绝，避免把不可用资源写进 AI 材料
        if (courseware.getType() == null || courseware.getType() != DOCUMENT_TYPE) {
            throw new BizException("该课件类型暂不支持 AI 抽取，首期仅支持文档");
        }
        if (courseware.getStatus() == null || courseware.getStatus() != 2
                || courseware.getOnSale() == null || courseware.getOnSale() != 1) {
            throw new BizException("课件未发布或已下架，不能作为 AI 素材");
        }
        KngCoursewareFile sourceFile = firstDocumentFile(coursewareId);
        if (sourceFile == null) {
            throw new BizException("课件没有可抽取的文档源文件");
        }
        KngDocumentTextExtractor.Extracted extracted =
                textExtractor.extract(sourceFile.getStoragePath(), maxCharsOf(purpose));

        KngAiSourceDtos.ExtractResult result = new KngAiSourceDtos.ExtractResult();
        result.setCoursewareId(courseware.getId());
        result.setCoursewareTitle(courseware.getTitle());
        result.setSourceFileId(sourceFile.getId());
        result.setFileName(sourceFile.getFileName());
        result.setFileType(extensionOf(sourceFile.getFileName(), sourceFile.getStoragePath()));
        result.setFileSize(sourceFile.getFileSize());
        result.setText(extracted.text());
        result.setChars(extracted.text().length());
        result.setTruncated(extracted.truncated());
        log.info("AI 素材抽取完成 coursewareId={} purpose={} 字数={} 截断={}",
                coursewareId, purpose, result.getChars(), result.isTruncated());
        return result;
    }

    @Override
    public int maxCharsOf(String purpose) {
        return "question".equalsIgnoreCase(purpose) ? QUESTION_MAX_CHARS : DESIGN_ASSISTANT_MAX_CHARS;
    }

    /**
     * 课件第一个文档类源文件（按 sort_order, id 升序取第一个）。
     * <p>
     * 只看存储路径的扩展名：来源文件被改名后展示名会变，但存储路径后缀才是真实格式。
     */
    private KngCoursewareFile firstDocumentFile(String coursewareId) {
        return fileMapper.selectByCoursewareIdsOrdered(List.of(coursewareId))
                .stream()
                .filter(file -> DOC_EXTENSIONS.contains(extensionOf(file.getFileName(), file.getStoragePath())))
                .filter(file -> StringUtils.hasText(file.getStoragePath()))
                .findFirst()
                .orElse(null);
    }

    /** 列表行装配：分类名批量补齐（不逐行查库），源文件批量对齐（一次 in 查询） */
    private List<KngAiSourceDtos.ListItem> toListItems(List<KngCourseware> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        Map<String, String> catalogNames = catalogNames(page);
        Map<String, KngCoursewareFile> sourceFiles = firstDocumentFiles(page.stream()
                .map(KngCourseware::getId).toList());
        List<KngAiSourceDtos.ListItem> records = new ArrayList<>(page.size());
        for (KngCourseware courseware : page) {
            KngAiSourceDtos.ListItem item = new KngAiSourceDtos.ListItem();
            item.setId(courseware.getId());
            item.setTitle(courseware.getTitle());
            item.setType(courseware.getType());
            item.setTypeDesc("文档");
            item.setCatalogId(courseware.getCatalogId());
            item.setCatalogName(StringUtils.hasText(courseware.getCatalogName())
                    ? courseware.getCatalogName() : catalogNames.get(courseware.getCatalogId()));
            // 学分口径与课件列表一致：单独设置优先，否则按课程设置折算
            item.setCredits(coursewareService.studyScoreCalc(courseware));
            item.setDurationText(durationText(courseware));
            item.setCreatedByName(courseware.getCreatedByName());
            item.setCreateTime(format(courseware.getCreatedAt()));
            item.setUpdateTime(format(courseware.getUpdatedAt()));
            // 本系统无课件过期字段（02-技术实现方案 §2.2 刻意不同项），前端固定显示 --
            item.setExpirationDate(null);
            item.setHidden(courseware.getHidden() != null && courseware.getHidden() == 1);
            KngCoursewareFile file = sourceFiles.get(courseware.getId());
            if (file != null) {
                item.setSourceFileId(file.getId());
                item.setSourceFileName(file.getFileName());
                item.setSourceFileType(extensionOf(file.getFileName(), file.getStoragePath()));
            }
            records.add(item);
        }
        return records;
    }

    /** 分类 id → 名称（一次全表取回后在内存拼 map，避免逐行点查） */
    private Map<String, String> catalogNames(List<KngCourseware> page) {
        List<String> missing = page.stream()
                .filter(item -> !StringUtils.hasText(item.getCatalogName()))
                .map(KngCourseware::getCatalogId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (missing.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (KngCatalog catalog : catalogMapper.selectList(null)) {
            names.put(catalog.getId(), catalog.getName());
        }
        return names;
    }

    /** 候选课件的第一个文档源文件（一次 in 查询后在内存里按 sort_order 取首个） */
    private Map<String, KngCoursewareFile> firstDocumentFiles(List<String> coursewareIds) {
        if (coursewareIds.isEmpty()) {
            return Map.of();
        }
        List<KngCoursewareFile> files = fileMapper.selectByCoursewareIdsOrdered(coursewareIds);
        Map<String, KngCoursewareFile> first = new LinkedHashMap<>();
        files.stream()
                .filter(file -> StringUtils.hasText(file.getStoragePath()))
                .filter(file -> DOC_EXTENSIONS.contains(extensionOf(file.getFileName(), file.getStoragePath())))
                .sorted(Comparator.comparing((KngCoursewareFile file) -> file.getSortOrder() == null
                        ? Integer.MAX_VALUE : file.getSortOrder()))
                .forEach(file -> first.putIfAbsent(file.getCoursewareId(), file));
        return first;
    }

    /** 课时文案：与课件列表同口径的「N分M秒」 */
    private String durationText(KngCourseware courseware) {
        int minutes = courseware.getStandardMinutes() == null ? 0 : courseware.getStandardMinutes();
        int seconds = courseware.getStandardSeconds() == null ? 0 : courseware.getStandardSeconds();
        return minutes + "分" + seconds + "秒";
    }

    private String format(LocalDateTime value) {
        return value == null ? null : DATE_TIME.format(value);
    }

    /** 扩展名：优先取存储路径后缀，缺失时回落展示文件名（都无扩展名返回空串） */
    private String extensionOf(String fileName, String storagePath) {
        String source = StringUtils.hasText(storagePath) ? storagePath : fileName;
        if (!StringUtils.hasText(source)) {
            return "";
        }
        String lower = source.toLowerCase(Locale.ROOT);
        int slash = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        int dot = lower.lastIndexOf('.');
        return dot > slash ? lower.substring(dot + 1) : "";
    }
}
