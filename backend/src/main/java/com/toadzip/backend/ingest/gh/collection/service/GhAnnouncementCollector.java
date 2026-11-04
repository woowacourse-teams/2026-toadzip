package com.toadzip.backend.ingest.gh.collection.service;

import static com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementBoard.RENTAL_HOUSE;

import com.toadzip.backend.ingest.collection.board.dto.BoardDetail;
import com.toadzip.backend.ingest.collection.board.service.BoardRequests;
import com.toadzip.backend.ingest.collection.board.service.BoardSource;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.collection.service.ExternalDataRateLimit;
import com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementSnapshot;
import com.toadzip.backend.ingest.gh.collection.dto.GhAnnouncementContent;
import com.toadzip.backend.ingest.gh.collection.dto.GhAnnouncementPage;
import com.toadzip.backend.ingest.gh.collection.dto.GhAnnouncementPage.Entry;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementHtmlParser;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementListParser;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementStore;
import com.toadzip.backend.ingest.gh.collection.repository.GhHousingTypeDetails;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

/** GH주택청약센터 임대주택 게시판이다. 상세의 주택형마다 상세공급정보 팝업을 더 받는다. */
@Service
@RequiredArgsConstructor
public class GhAnnouncementCollector implements BoardSource<Entry, GhAnnouncementSnapshot> {

    private final GhAnnouncementExternalRepository externalRepository;
    private final GhAnnouncementListParser parser;
    private final GhAnnouncementHtmlParser detailParser;
    private final GhAnnouncementStore store;
    private final GhHousingTypeDetails housingTypeDetails;

    @Override
    public ExternalDataSource source() {
        return ExternalDataSource.GH_ANNOUNCEMENT;
    }

    @Override
    public String agency() {
        return "GH";
    }

    @Override
    public String unit() {
        return "공고";
    }

    @Override
    public int pageSize() {
        return 10;
    }

    @Override
    public String pageRequest(int page) {
        return "board=" + RENTAL_HOUSE.code() + "&page=" + page;
    }

    @Override
    public String detailRequest(String pbancNo) {
        return "pbancNo=" + pbancNo;
    }

    @Override
    public GhAnnouncementPage fetchPage(int page) {
        return parser.parseList(externalRepository.fetchList(RENTAL_HOUSE, page), RENTAL_HOUSE, page);
    }

    @Override
    public Map<String, Instant> findUnchangedCollectedAt(List<Entry> entries) {
        return store.findUnchangedCollectedAt(RENTAL_HOUSE, entries);
    }

    /** 접수 상태·마감일·지역은 상세를 다시 받지 않아도 목록 값으로 갱신한다. */
    @Override
    public void updateListValues(List<Entry> entries) {
        store.updateListValues(entries);
    }

    @Override
    public BoardDetail<GhAnnouncementSnapshot> readDetail(Entry entry, int page, BoardRequests requests) {
        String request = detailRequest(entry.pbancNo());
        String html = requests.send(request,
                () -> externalRepository.fetchDetail(RENTAL_HOUSE, entry.pbancNo(), entry.kindCode()));
        GhAnnouncementContent content = detailParser.parse(html, RENTAL_HOUSE, entry);
        List<String> unread = new ArrayList<>(content.unreadParts());
        String districts = null;
        if (content.districts() != null) {
            districts = withHousingTypeDetails(request, content.districts(), unread, requests);
        }
        return new BoardDetail<>(new GhAnnouncementSnapshot(RENTAL_HOUSE, entry.pbancNo(), entry.kindCode(),
                content.title(), content.supplyType(), entry.region(), content.postedDate(), entry.closingDate(),
                entry.status(), RENTAL_HOUSE.detailUrl(entry.pbancNo(), entry.kindCode()), RENTAL_HOUSE.listUrl(page),
                districts, content.reception(), content.notice(), content.otherNotes(), content.attachments()), unread);
    }

    @Override
    public void store(GhAnnouncementSnapshot snapshot) {
        store.store(snapshot);
    }

    /**
     * 주택형마다 상세공급정보 팝업을 받아 붙인다. 팝업을 못 받은 주택형은 detail 없이 두고 공고 실패 사유에 남긴다.
     * 호출 제한에 걸리면 남은 팝업을 받지 않는다(실패 기록은 공고 단위로 한 번 남는다).
     */
    private String withHousingTypeDetails(
            String request, String districts, List<String> unread, BoardRequests requests
    ) {
        Map<GhHousingTypeDetails.Request, ObjectNode> details = new LinkedHashMap<>();
        for (GhHousingTypeDetails.Request housingType : housingTypeDetails.requests(districts)) {
            try {
                String html = requests.send(request,
                        () -> externalRepository.fetchHousingTypeDetail(RENTAL_HOUSE, housingType.form()));
                details.put(housingType, housingTypeDetails.parse(html, housingType));
            }
            catch (ExternalDataCallFailureException | ExternalDataRequestException
                    | IllegalArgumentException exception) {
                unread.add("주택형 상세(" + housingType.label() + ")");
                if (ExternalDataRateLimit.count(exception) > 0) {
                    unread.add("호출 제한으로 남은 주택형 상세");
                    break;
                }
            }
        }
        return housingTypeDetails.withDetails(districts, details);
    }
}
