package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSource;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexSourceReader;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.correction.dto.IngestCorrectionDetail.Row;
import com.toadzip.backend.ingest.correction.dto.IngestCorrectionRequest;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class IngestCorrectionSources {
    private final MyHomeComplexSourceReader complexes;
    private final MyHomeAnnouncementSourceReader announcements;
    private final MyHomeComplexSourceMapper complexMapper;
    private final IngestCorrectionOverlay overlay;
    private final ObjectMapper json;

    public List<MyHomeComplexSource> complexes(String identifier, List<IngestCorrectionRequest.Row> changes) {
        long number;
        try {
            number = Long.parseLong(identifier.substring(0, identifier.indexOf(':')));
        }
        catch (RuntimeException exception) {
            throw new InvalidIngestRequestException("단지 원천 식별자가 올바르지 않습니다.");
        }
        var sources = complexes.findAllByHsmpSnIn(List.of(number)).stream()
                .filter(source -> matchesIdentifier(source, identifier)).toList();
        checkKeys(sources.stream().map(MyHomeComplexSource::getSourceKey).toList(), changes);
        try {
            return sources.stream().map(source -> correctedComplex(source, changes)).toList();
        }
        catch (tools.jackson.core.JacksonException exception) {
            throw new InvalidIngestRequestException("보완 값의 형식이 올바르지 않습니다. 숫자 항목을 확인해 주세요.");
        }
    }

    public List<MyHomeAnnouncementSource> announcements(String identifier, List<IngestCorrectionRequest.Row> changes) {
        var sources = announcements.findAllByPblancIdOrderByIdAsc(identifier);
        checkKeys(sources.stream().map(MyHomeAnnouncementSource::getSourceKey).toList(), changes);
        try {
            return sources.stream().map(source -> correctedAnnouncement(source, changes)).toList();
        }
        catch (tools.jackson.core.JacksonException exception) {
            throw new InvalidIngestRequestException("보완 값의 형식이 올바르지 않습니다. 숫자 항목을 확인해 주세요.");
        }
    }

    public List<Row> rows(String domain, String identifier, List<IngestCorrectionRequest.Row> changes) {
        List<Row> rows = new ArrayList<>();
        if (domain.equals("complex")) {
            complexes(identifier, List.of()).forEach(source -> rows.add(row(domain, source.getSourceKey(),
                    overlay.values(source.snapshot()), changes)));
            return rows;
        }
        announcements(identifier, List.of()).forEach(source -> rows.add(row(domain, source.getSourceKey(),
                overlay.values(source.snapshot()), changes)));
        return rows;
    }

    private boolean matchesIdentifier(MyHomeComplexSource source, String identifier) {
        try {
            return complexMapper.sourceComplexIdentifier(source).equals(identifier);
        }
        catch (RuntimeException exception) {
            return false;
        }
    }

    private Row row(String domain, String key, Map<String, Object> original,
            List<IngestCorrectionRequest.Row> changes) {
        return new Row(key, original, overlay.merge(domain, original, changesFor(key, changes)));
    }

    private Map<String, Object> changesFor(String key, List<IngestCorrectionRequest.Row> rows) {
        return rows.stream().filter(row -> row.sourceKey().equals(key))
                .map(IngestCorrectionRequest.Row::changes).findFirst().orElse(Map.of());
    }

    private void checkKeys(List<String> keys, List<IngestCorrectionRequest.Row> rows) {
        if (rows.stream().anyMatch(row -> !keys.contains(row.sourceKey()))
                || rows.stream().map(IngestCorrectionRequest.Row::sourceKey).distinct().count() != rows.size()) {
            throw new InvalidIngestRequestException("현재 대상에 없는 원천 행 또는 중복 행이 포함되어 있습니다.");
        }
    }

    private MyHomeComplexSource correctedComplex(MyHomeComplexSource source,
            List<IngestCorrectionRequest.Row> changes) {
        var copy = MyHomeComplexSource.from(source.snapshot());
        var values = overlay.merge("complex", overlay.values(source.snapshot()),
                changesFor(source.getSourceKey(), changes));
        copy.replaceWith(json.convertValue(values, MyHomeComplexSourceSnapshot.class));
        if (source.getCollectedAt() != null) {
            copy.markCollectedAt(source.getCollectedAt());
        }
        return copy;
    }

    private MyHomeAnnouncementSource correctedAnnouncement(MyHomeAnnouncementSource source,
            List<IngestCorrectionRequest.Row> changes) {
        var original = json.convertValue(overlay.values(source.snapshot()),
                com.toadzip.backend.ingest.collection.myhome.announcement.domain
                        .MyHomeAnnouncementSourceSnapshot.class);
        var copy = MyHomeAnnouncementSource.read(source.getId(), source.getSourceOrder(), source.getCollectedAt(),
                source.getLastSeenRunId(), source.getConsecutiveMissCount(), source.isActive(), original);
        var values = overlay.merge("announcement", overlay.values(source.snapshot()),
                changesFor(source.getSourceKey(), changes));
        copy.replaceWith(json.convertValue(values, MyHomeAnnouncementSourceSnapshot.class));
        return copy;
    }
}
