package jack.digityhuman.examservice.service.impl;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AiGenServiceImplTest {

    private final AiGenServiceImpl service = new AiGenServiceImpl(null, null, null, null);

    @Test
    void shouldExtractTextFromPdfWithPdfBox3() throws Exception {
        byte[] pdf = createPdf("AI question PDF parser regression");
        MockMultipartFile file = new MockMultipartFile(
                "file", "material.pdf", "application/pdf", pdf);

        String text = service.parseDocument(file);

        assertThat(text).contains("AI question PDF parser regression");
    }

    private byte[] createPdf(String content) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(content);
                stream.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
