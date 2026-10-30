package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.exception.InvalidAnnouncementPdfException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/** 공고문 PDF의 쪽별 텍스트를 읽고, 고른 쪽만 남긴 PDF를 만든다. */
final class AnnouncementPdfDocument {

    private static final String UNREADABLE = "PDF를 읽지 못했습니다. 손상되었거나 암호가 걸린 파일인지 확인해 주세요.";

    private final byte[] content;
    private final List<String> pageTexts;

    private AnnouncementPdfDocument(byte[] content, List<String> pageTexts) {
        this.content = content;
        this.pageTexts = pageTexts;
    }

    static AnnouncementPdfDocument read(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.getNumberOfPages() == 0) {
                throw new InvalidAnnouncementPdfException("쪽이 없는 PDF입니다.");
            }
            return new AnnouncementPdfDocument(content, textsOf(document));
        }
        catch (IOException exception) {
            throw new InvalidAnnouncementPdfException(UNREADABLE, exception);
        }
    }

    List<String> pageTexts() {
        return pageTexts;
    }

    /** 쪽 번호는 1부터 시작하고, 주어진 순서대로 담는다. */
    byte[] pages(List<Integer> pageNumbers) {
        try (PDDocument source = Loader.loadPDF(content); PDDocument target = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int pageNumber : pageNumbers) {
                target.importPage(source.getPage(pageNumber - 1));
            }
            target.save(output);
            return output.toByteArray();
        }
        catch (IOException exception) {
            throw new InvalidAnnouncementPdfException(UNREADABLE, exception);
        }
    }

    private static List<String> textsOf(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        List<String> texts = new ArrayList<>();
        for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            texts.add(stripper.getText(document));
        }
        return List.copyOf(texts);
    }
}
