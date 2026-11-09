package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.announcement.exception.InvalidAnnouncementPdfException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class AnnouncementPdfDocumentTest {

    @Test
    void 읽을_수_있는_PDF는_통과한다() {
        assertThatCode(() -> AnnouncementPdfDocument.requireReadable(pdf("cover", "supply")))
                .doesNotThrowAnyException();
    }

    @Test
    void PDF가_아니면_읽지_않는다() {
        byte[] html = "<html>not a pdf</html>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> AnnouncementPdfDocument.requireReadable(html))
                .isInstanceOf(InvalidAnnouncementPdfException.class);
    }

    static byte[] pdf(String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
