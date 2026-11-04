package com.toadzip.backend.ingest.sh.collection.service;

import com.toadzip.backend.ingest.collection.board.dto.BoardDetail;
import com.toadzip.backend.ingest.collection.board.service.BoardRequests;
import com.toadzip.backend.ingest.collection.board.service.BoardSource;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.sh.collection.domain.ShAnnouncementSnapshot;
import com.toadzip.backend.ingest.sh.collection.dto.ShAnnouncementContent;
import com.toadzip.backend.ingest.sh.collection.dto.ShAnnouncementPage;
import com.toadzip.backend.ingest.sh.collection.dto.ShAnnouncementPage.Entry;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementExternalRepository;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementHtmlParser;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementListParser;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** SH 인터넷청약 주택임대 분류 게시판이다. 매입임대·전세임대 글은 건설임대가 아니라 받지 않는다. */
@Service
@RequiredArgsConstructor
public class ShAnnouncementBoardSource implements BoardSource<Entry, ShAnnouncementSnapshot> {

    private final ShAnnouncementExternalRepository externalRepository;
    private final ShAnnouncementListParser parser;
    private final ShAnnouncementHtmlParser detailParser;
    private final ShAnnouncementStore store;

    @Override
    public ExternalDataSource source() {
        return ExternalDataSource.SH_ANNOUNCEMENT;
    }

    @Override
    public String agency() {
        return "SH";
    }

    @Override
    public String unit() {
        return "게시글";
    }

    @Override
    public int pageSize() {
        return 10;
    }

    @Override
    public String pageRequest(int page) {
        return "page=" + page;
    }

    @Override
    public String detailRequest(String seq) {
        return "seq=" + seq;
    }

    @Override
    public ShAnnouncementPage fetchPage(int page) {
        return parser.parseList(externalRepository.fetchList(page), page);
    }

    @Override
    public boolean isTarget(Entry entry) {
        return !entry.isPurchaseOrJeonseRental();
    }

    @Override
    public Map<String, Instant> findUnchangedCollectedAt(List<Entry> entries) {
        return store.findUnchangedCollectedAt(entries);
    }

    @Override
    public BoardDetail<ShAnnouncementSnapshot> readDetail(Entry entry, int page, BoardRequests requests) {
        String html = requests.send(detailRequest(entry.seq()), () -> externalRepository.fetchDetail(entry.seq()));
        ShAnnouncementContent content = detailParser.parse(html, entry.seq(), entry.title(), entry.registeredDate());
        String department = content.department();
        if (department == null) {
            department = entry.department();
        }
        return new BoardDetail<>(new ShAnnouncementSnapshot(entry.seq(), content.title(), department,
                content.registeredDate(), ShAnnouncementExternalRepository.detailUrl(entry.seq()),
                ShAnnouncementExternalRepository.LIST_URL + "&page=" + page, content.bodyText(),
                content.attachments()), content.unreadParts());
    }

    @Override
    public void store(ShAnnouncementSnapshot snapshot) {
        store.store(snapshot);
    }
}
