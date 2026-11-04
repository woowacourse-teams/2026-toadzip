package com.toadzip.backend.ingest.gh.collection.dto;

import com.toadzip.backend.ingest.collection.board.dto.BoardEntry;
import com.toadzip.backend.ingest.collection.board.dto.BoardPage;
import java.time.LocalDate;
import java.util.List;

public record GhAnnouncementPage(int page, int totalCount, List<Entry> entries)
        implements BoardPage<GhAnnouncementPage.Entry> {

    /** closingDate·status는 목록에 '-'로 표시되면 null이다. */
    public record Entry(
            String pbancNo, String kindCode, String supplyType, String title, String region,
            LocalDate postedDate, LocalDate closingDate, String status
    ) implements BoardEntry {

        @Override
        public String key() {
            return pbancNo;
        }
    }
}
