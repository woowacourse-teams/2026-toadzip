package com.toadzip.backend.housing.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.dto.response.HousingComplexListResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexMapResponse;
import com.toadzip.backend.housing.exception.InvalidComplexRequestException;
import com.toadzip.backend.housing.repository.ComplexSummaryQueryRepository;
import com.toadzip.backend.housing.repository.ComplexSummaryRow;

@Service
public class HousingComplexQueryService {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final ComplexSummaryQueryRepository repository;

    private final HousingComplexSummaryMapper summaryMapper;

    private final HousingComplexCursorCodec cursorCodec;

    private final Clock clock;

    @Autowired
    public HousingComplexQueryService(
            ComplexSummaryQueryRepository repository,
            HousingComplexSummaryMapper summaryMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.summaryMapper = summaryMapper;
        this.cursorCodec = new HousingComplexCursorCodec();
        this.clock = clock;
    }

    HousingComplexQueryService(
            ComplexSummaryQueryRepository repository,
            HousingComplexSummaryMapper summaryMapper
    ) {
        this(repository, summaryMapper, Clock.fixed(Instant.EPOCH, SEOUL_ZONE));
    }

    @Transactional(readOnly = true)
    public HousingComplexMapResponse getComplexesForMap(MapBounds bounds) {
        return new HousingComplexMapResponse(repository.findAllInBounds(bounds).stream()
                .map(summaryMapper::toMapItem)
                .toList());
    }

    @Transactional(readOnly = true)
    public HousingComplexListResponse getComplexes(MapBounds bounds, String cursor, int size) {
        requireValidSize(size);
        List<ComplexSummaryRow> fetched = findRows(bounds, cursor, size + 1);
        boolean hasNext = fetched.size() > size;
        List<ComplexSummaryRow> page = fetched.stream().limit(size).toList();
        return new HousingComplexListResponse(
                summaryMapper.toListItems(page, today()),
                nextCursor(page, hasNext),
                hasNext
        );
    }

    private void requireValidSize(int size) {
        if (size < 1 || size > 50) {
            throw new InvalidComplexRequestException();
        }
    }

    private List<ComplexSummaryRow> findRows(MapBounds bounds, String cursor, int limit) {
        if (cursor == null) {
            return repository.findFirstPage(bounds, limit);
        }
        return repository.findPageAfter(bounds, cursorCodec.decode(cursor), limit);
    }

    private String nextCursor(List<ComplexSummaryRow> page, boolean hasNext) {
        if (!hasNext) {
            return null;
        }
        ComplexSummaryRow finalItem = page.getLast();
        return cursorCodec.encode(finalItem.postedDate(), finalItem.complexId());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), SEOUL_ZONE);
    }
}
