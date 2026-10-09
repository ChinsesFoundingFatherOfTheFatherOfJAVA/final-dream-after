package jack.digityhuman.speechtraining.service.impl;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.service.MaterialExtractService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 素材文本抽取实现：复用工程既有 POI 5.3.0（docx/pptx/pps）+ PDFBox（pdf）
 * + POI HWPF（doc，poi-scratchpad）——与 02 方案 Tika 等效覆盖六格式但不引入 60MB+ 的
 * tika-parsers 全家桶（工程已有依赖优先，抽不出文本的格式按 02 风险口径单条报错不阻塞）。
 */
@Slf4j
@Service
public class MaterialExtractServiceImpl implements MaterialExtractService {

    /** 支持的素材扩展名（01 SG-03：.doc/.docx/.ppt/.pptx/.pps/.pdf） */
    private static final Set<String> SUPPORTED = Set.of("doc", "docx", "ppt", "pptx", "pps", "pdf");

    /** 文本抽取上限（02 §4：30k 截断，超出返回 truncated=true 提示） */
    @Value("${ai.gen.material-max-chars:30000}")
    private int maxChars;

    @Override
    public ExtractResult extract(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extensionOf(name);
        if (!SUPPORTED.contains(ext)) {
            throw new BizException("不支持的文件格式（支持 .doc/.docx/.ppt/.pptx/.pps/.pdf）");
        }
        try (InputStream in = file.getInputStream()) {
            return truncate(extractByExt(in, ext));
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("素材抽取失败 file={} err={}", name, exception.getMessage());
            throw new BizException("素材文本抽取失败: " + exception.getMessage());
        }
    }

    @Override
    public ExtractResult extract(Path file, String fileName) {
        String ext = extensionOf(fileName == null ? "" : fileName);
        if (!SUPPORTED.contains(ext)) {
            throw new BizException("不支持的文件格式（支持 .doc/.docx/.ppt/.pptx/.pps/.pdf）");
        }
        try (InputStream in = Files.newInputStream(file)) {
            return truncate(extractByExt(in, ext));
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("课件素材抽取失败 file={} err={}", fileName, exception.getMessage());
            throw new BizException("素材文本抽取失败: " + exception.getMessage());
        }
    }

    /** 按扩展名分派抽取器；pptx 与 pps 同走 XSLF，doc 走 HWPF（poi-scratchpad）。 */
    private String extractByExt(InputStream in, String ext) throws Exception {
        return switch (ext) {
            case "pdf" -> extractPdf(in);
            case "docx" -> extractDocx(in);
            case "doc" -> extractDoc(in);
            case "pptx", "pps" -> extractPptx(in);
            case "ppt" -> extractPpt(in);
            default -> throw new BizException("不支持的文件格式");
        };
    }

    private String extractPdf(InputStream in) throws Exception {
        try (PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String extractDocx(InputStream in) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    private String extractDoc(InputStream in) throws Exception {
        try (org.apache.poi.hwpf.HWPFDocument doc = new org.apache.poi.hwpf.HWPFDocument(in);
             org.apache.poi.hwpf.extractor.WordExtractor extractor =
                     new org.apache.poi.hwpf.extractor.WordExtractor(doc)) {
            return extractor.getText();
        }
    }

    private String extractPptx(InputStream in) throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow(in)) {
            StringBuilder sb = new StringBuilder();
            for (XSLFSlide slide : ppt.getSlides()) {
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            sb.append(text).append('\n');
                        }
                    }
                }
            }
            return sb.toString();
        }
    }

    /** 老格式 .ppt：slide.getTextParagraphs() 返回 List<List<HSLFTextParagraph>>，
     *  run 级取文本用 HSLFTextRun.getRawText()（POI 5.x 的 getText 在 SL 接口上不存在）。 */
    private String extractPpt(InputStream in) throws Exception {
        try (HSLFSlideShow ppt = new HSLFSlideShow(in)) {
            StringBuilder sb = new StringBuilder();
            for (org.apache.poi.hslf.usermodel.HSLFSlide slide : ppt.getSlides()) {
                for (List<List<org.apache.poi.hslf.usermodel.HSLFTextParagraph>> blocks
                        : slide.getTextParagraphs()) {
                    for (List<org.apache.poi.hslf.usermodel.HSLFTextParagraph> block : blocks) {
                        for (org.apache.poi.hslf.usermodel.HSLFTextParagraph paragraph : block) {
                            for (org.apache.poi.hslf.usermodel.HSLFTextRun run : paragraph.getTextRuns()) {
                                String text = run.getRawText();
                                if (text != null && !text.isBlank()) {
                                    sb.append(text);
                                }
                            }
                            sb.append('\n');
                        }
                    }
                }
            }
            return sb.toString();
        }
    }

    /** 30k 截断（02 §7：防素材文本超 prompt 上限）。 */
    private ExtractResult truncate(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.length() <= maxChars) {
            return new ExtractResult(text, false, text.length());
        }
        return new ExtractResult(text.substring(0, maxChars), true, maxChars);
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
