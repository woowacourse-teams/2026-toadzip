package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.ExtractionPageSelection;
import com.toadzip.backend.announcement.dto.response.AnnouncementPdfExtraction;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.external.AnnouncementExtractionModel;
import com.toadzip.backend.announcement.repository.external.AnnouncementExtractionReply;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 공고문 PDF에서 필요한 쪽만 골라 AI 모델로 보정용 JSON을 뽑는다. 결과는 저장하지 않는다.
 * 모델 호출이 수십 초 걸리므로 DB 트랜잭션을 열지 않는다.
 */
@Slf4j
@Service
public class AnnouncementPdfExtractionService {

    private final AnnouncementRepository announcements;
    private final AnnouncementExtractionModel model;
    private final ObjectMapper objectMapper;

    public AnnouncementPdfExtractionService(
            AnnouncementRepository announcements,
            AnnouncementExtractionModel model,
            ObjectMapper objectMapper
    ) {
        this.announcements = announcements;
        this.model = model;
        this.objectMapper = objectMapper;
    }

    public AnnouncementPdfExtraction extract(long announcementId, byte[] pdf) {
        if (!announcements.existsById(announcementId)) {
            throw new AnnouncementNotFoundException();
        }
        AnnouncementPdfDocument document = AnnouncementPdfDocument.read(pdf);
        ExtractionPageSelection selection = ExtractionPageSelection.select(document.pageTexts());
        long started = System.nanoTime();
        AnnouncementExtractionReply reply = model.extract(document.pages(selection.pageNumbers()));
        ExtractedAnnouncementJson extracted = ExtractedAnnouncementJson.from(
                objectMapper, reply.json(), selection.pageNumbers());
        AnnouncementPdfExtraction result = result(document, selection, extracted, reply.usage());
        log.info("event=announcement.pdf_extraction.completed announcementId={} pages={}/{} elapsedMs={} "
                        + "promptTokens={} outputTokens={} thinkingTokens={} warnings={}",
                announcementId, selection.pageNumbers().size(), result.totalPages(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), reply.usage().promptTokens(),
                reply.usage().outputTokens(), reply.usage().thinkingTokens(), result.warnings().size());
        return result;
    }

    private static AnnouncementPdfExtraction result(
            AnnouncementPdfDocument document,
            ExtractionPageSelection selection,
            ExtractedAnnouncementJson extracted,
            AnnouncementExtractionReply.Usage usage
    ) {
        int totalPages = document.pageTexts().size();
        List<String> warnings = new ArrayList<>();
        if (selection.wholeDocument()) {
            warnings.add("공급표·임대조건표 쪽을 찾지 못해 전체 %d쪽을 보냈습니다.".formatted(totalPages));
        }
        warnings.addAll(extracted.warnings());
        return new AnnouncementPdfExtraction(extracted.json(), selection.pageNumbers(), totalPages,
                List.copyOf(warnings), new AnnouncementPdfExtraction.Usage(
                        usage.promptTokens(), usage.outputTokens(), usage.thinkingTokens()));
    }
}
