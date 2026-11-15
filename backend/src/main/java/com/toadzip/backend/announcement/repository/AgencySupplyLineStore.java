package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.AgencySupplyLine;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.housing.domain.AgencyCode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 공고 하나의 기관 준비 줄(agency_supply_lines)을 이번 원천 그대로 바꾼다. 부르는 쪽(LH·GH 정제)의 트랜잭션에서 쓴다. 원천 줄 키가 같은
 * 줄은 고쳐 ID를 지키고, 이번 원천에 없는 줄은 지운다. 공고 공급표(정본)는 건드리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AgencySupplyLineStore {

    private final AgencySupplyLineRepository lines;

    public Counts replace(Announcement announcement, AgencyCode agency, List<AgencySupplyLine.Values> values) {
        Map<String, AgencySupplyLine> stored = lines.findAllByAnnouncement(announcement).stream()
                .collect(Collectors.toMap(AgencySupplyLine::getSourceLineKey, Function.identity()));
        Set<String> keys = new HashSet<>();
        int created = 0;
        int updated = 0;
        for (AgencySupplyLine.Values value : values) {
            if (!keys.add(value.sourceLineKey())) {
                throw new IllegalArgumentException("원천 줄 키가 겹친다: " + value.sourceLineKey());
            }
            AgencySupplyLine line = stored.remove(value.sourceLineKey());
            if (line == null) {
                lines.save(AgencySupplyLine.create(announcement, agency, value));
                created++;
            }
            else if (line.apply(value)) {
                updated++;
            }
        }
        lines.deleteAll(stored.values());
        return new Counts(created, updated, stored.size());
    }

    /** 이번에 만든·고친·지운 준비 줄 수. */
    public record Counts(int created, int updated, int deleted) {
    }
}
