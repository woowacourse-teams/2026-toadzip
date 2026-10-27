package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.AnnouncementPdfExtractionRecord;
import com.toadzip.backend.announcement.domain.ExtractionPageSelection;
import com.toadzip.backend.announcement.dto.response.AnnouncementPdfExtraction;
import com.toadzip.backend.announcement.dto.response.SavedAnnouncementPdfExtraction;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.repository.AnnouncementPdfExtractionRecordRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.external.AnnouncementExtractionModel;
import com.toadzip.backend.announcement.repository.external.AnnouncementExtractionReply;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 공고문 PDF에서 필요한 쪽만 골라 AI 모델로 보정용 JSON을 뽑는다.
 * 같은 PDF를 다시 추출하는 비용을 줄이려고 결과를 남기고, 공고 데이터에는 반영하지 않는다.
 * 모델 호출이 수십 초 걸리므로 DB 트랜잭션을 열지 않는다.
 */
@Slf4j
@Service
public class AnnouncementPdfExtractionService {

    private final AnnouncementRepository announcements;
    private final AnnouncementPdfExtractionRecordRepository records;
    private final AnnouncementExtractionModel model;
    private final ObjectMapper objectMapper;

    public AnnouncementPdfExtractionService(
            AnnouncementRepository announcements,
            AnnouncementPdfExtractionRecordRepository records,
            AnnouncementExtractionModel model,
            ObjectMapper objectMapper
    ) {
        this.announcements = announcements;
        this.records = records;
        this.model = model;
        this.objectMapper = objectMapper;
    }

    public AnnouncementPdfExtraction extract(long announcementId, byte[] pdf, String fileName) {
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
        save(announcementId, pdf, fileName, result);
        return result;
    }

    /** 가장 최근에 저장한 추출 결과. 없으면 null이다. */
    public SavedAnnouncementPdfExtraction latest(long announcementId) {
        if (!announcements.existsById(announcementId)) {
            throw new AnnouncementNotFoundException();
        }
        return records.findFirstByAnnouncementIdOrderByCreatedAtDescIdDesc(announcementId)
                .map(record -> new SavedAnnouncementPdfExtraction(record.getFileName(), record.getPdfSha256(),
                        record.getCreatedAt(), objectMapper.readValue(record.getResult(),
                                AnnouncementPdfExtraction.class)))
                .orElse(null);
    }

    // 모델 비용은 이미 들었으므로 저장에 실패해도 관리자가 받은 결과로 검토를 이어 가게 한다.
    private void save(long announcementId, byte[] pdf, String fileName, AnnouncementPdfExtraction result) {
        try {
            records.save(new AnnouncementPdfExtractionRecord(announcementId, sha256(pdf), fileName,
                    objectMapper.writeValueAsString(result)));
        }
        catch (RuntimeException exception) {
            log.warn("event=announcement.pdf_extraction.save_failed announcementId={}", announcementId, exception);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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
