package com.toadzip.backend.housing.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.housing.domain.ComplexSort;
import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.dto.request.HousingComplexSearchRequest;
import com.toadzip.backend.housing.dto.response.HousingComplexDetailResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexListResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexSearchResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexSearchBoundsResponse;
import com.toadzip.backend.housing.exception.HousingComplexNotFoundException;
import com.toadzip.backend.housing.exception.InvalidComplexRequestException;
import com.toadzip.backend.housing.repository.ComplexDetailQueryRepository;
import com.toadzip.backend.housing.repository.ComplexDetailRow;
import com.toadzip.backend.housing.repository.ComplexSummaryCursor;
import com.toadzip.backend.housing.repository.ComplexSummaryQueryRepository;
import com.toadzip.backend.housing.repository.ComplexSummaryRow;
import com.toadzip.backend.housing.repository.HousingComplexFilterCondition;
import com.toadzip.backend.housing.repository.HousingComplexSearchCondition;

@Service
public class HousingComplexQueryService {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final ComplexSummaryQueryRepository repository;

    private final ComplexDetailQueryRepository detailRepository;

    private final HousingComplexSummaryMapper summaryMapper;

    private final HousingComplexDetailMapper detailMapper;

    private final HousingComplexCursorCodec cursorCodec;

    private final HousingComplexSearchRequestNormalizer requestNormalizer;

    private final Clock clock;

    @Autowired
    public HousingComplexQueryService(
            ComplexSummaryQueryRepository repository,
            HousingComplexSummaryMapper summaryMapper,
            ComplexDetailQueryRepository detailRepository,
            HousingComplexDetailMapper detailMapper,
            HousingComplexSearchRequestNormalizer requestNormalizer,
            Clock clock
    ) {
        this.repository = repository;
        this.summaryMapper = summaryMapper;
        this.detailRepository = detailRepository;
        this.detailMapper = detailMapper;
        this.cursorCodec = new HousingComplexCursorCodec();
        this.requestNormalizer = requestNormalizer;
        this.clock = clock;
    }

    HousingComplexQueryService(
            ComplexSummaryQueryRepository repository,
            HousingComplexSummaryMapper summaryMapper,
            HousingComplexSearchRequestNormalizer requestNormalizer,
            Clock clock
    ) {
        this(repository, summaryMapper, null, null, requestNormalizer, clock);
    }

    @Transactional(readOnly = true)
    public HousingComplexListResponse getComplexes(
            HousingComplexSearchRequest request,
            ComplexSort sort,
            String cursor,
            int size
    ) {
        MapBounds bounds = requestNormalizer.normalizeSearchBounds(request);
        requireValidSize(size);
        ComplexSort normalizedSort = normalizedSort(sort);
        HousingComplexFilterCondition filters = requestNormalizer.normalizeFilters(request);
        HousingComplexSearchCondition condition = new HousingComplexSearchCondition(bounds, filters);
        return findPage(condition, normalizedSort, cursor, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HousingComplexSearchResponse searchComplexes(
            HousingComplexSearchRequest request,
            ComplexSort sort,
            int size
    ) {
        if (request == null || request.scope() == null) {
            throw new InvalidComplexRequestException();
        }
        requireValidSize(size);
        MapBounds bounds = requestNormalizer.normalizeSearchBounds(request);
        HousingComplexFilterCondition filters = requestNormalizer.normalizeFilters(request);
        HousingComplexSearchCondition condition = new HousingComplexSearchCondition(bounds, filters);
        List<ComplexSummaryRow> allRows = repository.findAll(condition);
        List<ComplexSummaryRow> locatedRows = allRows.stream()
                .filter(row -> row.latitude() != null && row.longitude() != null).toList();
        return new HousingComplexSearchResponse(
                allRows.size(), locatedRows.size(), allRows.stream().map(ComplexSummaryRow::complexId).toList(),
                resultBounds(locatedRows), locatedRows.stream().map(summaryMapper::toMapItem).toList(),
                findPage(condition, normalizedSort(sort), null, size)
        );
    }

    private HousingComplexSearchBoundsResponse resultBounds(List<ComplexSummaryRow> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        return new HousingComplexSearchBoundsResponse(
                rows.stream().map(ComplexSummaryRow::latitude).min(BigDecimal::compareTo).orElseThrow(),
                rows.stream().map(ComplexSummaryRow::longitude).min(BigDecimal::compareTo).orElseThrow(),
                rows.stream().map(ComplexSummaryRow::latitude).max(BigDecimal::compareTo).orElseThrow(),
                rows.stream().map(ComplexSummaryRow::longitude).max(BigDecimal::compareTo).orElseThrow()
        );
    }

    private HousingComplexListResponse findPage(
            HousingComplexSearchCondition condition, ComplexSort sort, String cursor, int size
    ) {
        ComplexSummaryCursor decodedCursor = decodeCursor(cursor, sort);
        List<ComplexSummaryRow> fetched = repository.findPage(
                condition,
                sort,
                decodedCursor,
                size + 1
        );
        boolean hasNext = fetched.size() > size;
        List<ComplexSummaryRow> page = fetched.stream().limit(size).toList();
        return new HousingComplexListResponse(
                summaryMapper.toListItems(page, condition.filters().today()),
                nextCursor(page, hasNext, sort),
                hasNext
        );
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HousingComplexDetailResponse getComplex(long complexId) {
        LocalDate today = today();
        ComplexDetailRow complex = detailRepository.findComplex(complexId)
                .orElseThrow(HousingComplexNotFoundException::new);
        long resolvedId = complex.complexId();
        return detailMapper.toResponse(
                complex,
                detailRepository.findHousingTypes(resolvedId),
                detailRepository.findCurrentSupplyConditions(resolvedId, today),
                detailRepository.findCurrentAnnouncements(resolvedId, today),
                detailRepository.findCurrentAnnouncementTargets(resolvedId, today),
                today
        );
    }

    private void requireValidSize(int size) {
        if (size < 1 || size > 50) {
            throw new InvalidComplexRequestException();
        }
    }

    private ComplexSort normalizedSort(ComplexSort sort) {
        if (sort == null) {
            return ComplexSort.LATEST_ANNOUNCEMENT;
        }
        return sort;
    }

    private ComplexSummaryCursor decodeCursor(String cursor, ComplexSort sort) {
        if (cursor == null) {
            return null;
        }
        return cursorCodec.decode(cursor, sort);
    }

    private String nextCursor(List<ComplexSummaryRow> page, boolean hasNext, ComplexSort sort) {
        if (!hasNext) {
            return null;
        }
        ComplexSummaryRow finalItem = page.getLast();
        return cursorCodec.encode(cursorOf(finalItem, sort));
    }

    private ComplexSummaryCursor cursorOf(ComplexSummaryRow row, ComplexSort sort) {
        return switch (sort) {
            case LATEST_ANNOUNCEMENT -> dateCursor(sort, row.postedDate(), row.complexId());
            case DEPOSIT_ASC -> decimalCursor(sort, row.depositMin(), row.complexId());
            case MONTHLY_RENT_ASC -> decimalCursor(sort, row.monthlyRentMin(), row.complexId());
            case AREA_DESC -> decimalCursor(sort, row.exclusiveAreaMax(), row.complexId());
            case COMPLETION_DATE_DESC -> dateCursor(sort, row.completionDate(), row.complexId());
        };
    }

    private ComplexSummaryCursor dateCursor(ComplexSort sort, LocalDate value, long complexId) {
        ComplexSummaryCursor.DateValue primaryValue = null;
        if (value != null) {
            primaryValue = new ComplexSummaryCursor.DateValue(value);
        }
        return new ComplexSummaryCursor(sort, primaryValue, complexId);
    }

    private ComplexSummaryCursor decimalCursor(ComplexSort sort, BigDecimal value, long complexId) {
        ComplexSummaryCursor.DecimalValue primaryValue = null;
        if (value != null) {
            primaryValue = new ComplexSummaryCursor.DecimalValue(value);
        }
        return new ComplexSummaryCursor(sort, primaryValue, complexId);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), SEOUL_ZONE);
    }
}
