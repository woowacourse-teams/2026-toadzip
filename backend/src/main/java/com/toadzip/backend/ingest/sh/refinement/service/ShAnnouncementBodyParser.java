package com.toadzip.backend.ingest.sh.refinement.service;

import com.toadzip.backend.ingest.sh.refinement.dto.ShAnnouncementBodyValues;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * SH 게시글 본문 요약에서 인터넷 접수기간, 당첨자 발표일, 총 공급호수를 후보로 읽는다. 본문은 글마다 형식이 달라
 * 틀린 값을 채우느니 비우도록, 라벨이 붙은 한 줄만 읽고 같은 항목이 다른 값으로 두 번 나오면 비운다. 방문·우편 접수와
 * 표로 적은 순위별 일정은 읽지 않는다. 단지·주택형·세대수 같은 공급행은 본문에 없으므로 PDF 보정에 맡긴다.
 */
@Component
public class ShAnnouncementBodyParser {

    private static final Pattern APPLICATION = label("인터넷\\s*접수|청약\\s*접수일");
    private static final Pattern WINNER = label("당첨자\\s*발표일?|예비입주자\\s*발표");
    private static final Pattern SUPPLY = label("공급호수|금회\\s*공급단지|금회\\s*모집세대수");
    private static final String YEAR_MONTH_DAY = "(\\d{4})\\s*[.년]\\s*(\\d{1,2})\\s*[.월]\\s*(\\d{1,2})";
    private static final Pattern DATE = Pattern.compile(YEAR_MONTH_DAY);
    private static final Pattern PERIOD = Pattern.compile(YEAR_MONTH_DAY
            + "[^~]*~\\s*(?:(\\d{4})\\s*[.년]\\s*)?(\\d{1,2})\\s*[.월]\\s*(\\d{1,2})");
    private static final Pattern NEW_AND_RESUPPLY = Pattern.compile(
            "신규공급\\s*([\\d,]+)\\s*(?:호|세대)\\s*,\\s*재공급\\s*([\\d,]+)\\s*(?:호|세대)");
    private static final Pattern COUNT = Pattern.compile("([\\d,]+)\\s*(?:호|세대)");

    public ShAnnouncementBodyValues parse(String bodyText) {
        if (bodyText == null) {
            return new ShAnnouncementBodyValues(null, null, null, null);
        }
        List<String> lines = bodyText.lines().map(String::strip).toList();
        Optional<Period> period = single(values(lines, APPLICATION, this::period));
        return new ShAnnouncementBodyValues(period.map(Period::start).orElse(null),
                period.map(Period::end).orElse(null),
                single(values(lines, WINNER, this::date)).orElse(null),
                single(values(lines, SUPPLY, this::supplyCount)).orElse(null));
    }

    private static Pattern label(String names) {
        return Pattern.compile("^[■□○]?\\s*(?:" + names + ")\\s*:\\s*(.+)$");
    }

    private <T> Set<T> values(List<String> lines, Pattern label, Function<String, T> reader) {
        Set<T> values = new LinkedHashSet<>();
        for (String line : lines) {
            Matcher matcher = label.matcher(line);
            if (matcher.matches()) {
                Optional.ofNullable(reader.apply(matcher.group(1))).ifPresent(values::add);
            }
        }
        return values;
    }

    private static <T> Optional<T> single(Set<T> values) {
        if (values.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(values.iterator().next());
    }

    private LocalDate date(String value) {
        Matcher matcher = DATE.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        return localDate(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    private Period period(String value) {
        Matcher matcher = PERIOD.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        LocalDate start = localDate(matcher.group(1), matcher.group(2), matcher.group(3));
        String endYear = Optional.ofNullable(matcher.group(4)).orElse(matcher.group(1));
        LocalDate end = localDate(endYear, matcher.group(5), matcher.group(6));
        if (start == null || end == null) {
            return null;
        }
        if (matcher.group(4) == null && end.isBefore(start)) {
            end = end.plusYears(1);
        }
        if (end.isBefore(start)) {
            return null;
        }
        return new Period(start, end);
    }

    private Integer supplyCount(String value) {
        String outsideParentheses = value.replaceAll("\\([^)]*\\)", "");
        Matcher split = NEW_AND_RESUPPLY.matcher(outsideParentheses);
        if (split.find()) {
            return number(split.group(1)) + number(split.group(2));
        }
        List<Integer> counts = COUNT.matcher(outsideParentheses).results().map(result -> number(result.group(1)))
                .toList();
        if (counts.size() != 1) {
            return null;
        }
        return counts.getFirst();
    }

    private static int number(String value) {
        return Integer.parseInt(value.replace(",", ""));
    }

    private static LocalDate localDate(String year, String month, String day) {
        try {
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
        }
        catch (DateTimeException exception) {
            return null;
        }
    }

    private record Period(LocalDate start, LocalDate end) {
    }
}
