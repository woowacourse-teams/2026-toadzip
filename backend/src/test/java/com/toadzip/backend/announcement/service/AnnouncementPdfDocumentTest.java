package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.announcement.exception.InvalidAnnouncementPdfException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class AnnouncementPdfDocumentTest {

    @Test
    void 쪽마다_텍스트를_읽는다() throws IOException {
        AnnouncementPdfDocument document = AnnouncementPdfDocument.read(pdf("cover", "supply", "contact"));

        assertThat(document.pageTexts()).hasSize(3);
        assertThat(document.pageTexts().get(1)).contains("supply");
    }

    @Test
    void 고른_쪽만_순서대로_남긴_PDF를_만든다() throws IOException {
        AnnouncementPdfDocument document = AnnouncementPdfDocument.read(pdf("cover", "notice", "supply", "contact"));

        AnnouncementPdfDocument subset = AnnouncementPdfDocument.read(document.pages(List.of(1, 3, 4)));

        assertThat(subset.pageTexts()).hasSize(3);
        assertThat(subset.pageTexts().get(1)).contains("supply");
        assertThat(subset.pageTexts().get(2)).contains("contact");
    }

    @Test
    void PDF가_아니면_읽지_않는다() {
        byte[] html = "<html>not a pdf</html>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> AnnouncementPdfDocument.read(html))
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
