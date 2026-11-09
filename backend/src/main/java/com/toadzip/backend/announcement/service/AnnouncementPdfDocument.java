package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.exception.InvalidAnnouncementPdfException;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

/** 작업을 넣기 전에 공고문 PDF를 열어 본다. 쪽 고르기와 추출은 AI 워커가 한다. */
final class AnnouncementPdfDocument {

    private static final String UNREADABLE = "PDF를 읽지 못했습니다. 손상되었거나 암호가 걸린 파일인지 확인해 주세요.";

    private AnnouncementPdfDocument() {
    }

    /** 잘못된 PDF는 워커에서 실패로 끝나기 전에 요청 단계에서 400으로 알린다. */
    static void requireReadable(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.getNumberOfPages() == 0) {
                throw new InvalidAnnouncementPdfException("쪽이 없는 PDF입니다.");
            }
        }
        catch (IOException exception) {
            throw new InvalidAnnouncementPdfException(UNREADABLE, exception);
        }
    }
}
