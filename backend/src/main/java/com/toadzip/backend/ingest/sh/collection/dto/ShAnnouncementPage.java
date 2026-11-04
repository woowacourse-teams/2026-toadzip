package com.toadzip.backend.ingest.sh.collection.dto;

import com.toadzip.backend.ingest.collection.board.dto.BoardEntry;
import com.toadzip.backend.ingest.collection.board.dto.BoardPage;
import java.time.LocalDate;
import java.util.List;

public record ShAnnouncementPage(int page, int totalCount, List<Entry> entries)
        implements BoardPage<ShAnnouncementPage.Entry> {

    public record Entry(String seq, String title, String department, LocalDate registeredDate) implements BoardEntry {

        @Override
        public String key() {
            return seq;
        }

        @Override
        public LocalDate postedDate() {
            return registeredDate;
        }

        /** 주택임대 분류에 함께 올라오는 매입임대·기존주택 전세임대 글. 건설임대가 아니라 받지 않는다. */
        public boolean isPurchaseOrJeonseRental() {
            return title.contains("매입임대") || title.contains("전세임대");
        }
    }
}
