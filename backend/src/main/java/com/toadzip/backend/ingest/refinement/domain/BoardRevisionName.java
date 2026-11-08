package com.toadzip.backend.ingest.refinement.domain;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SH·GH 게시판 공고명의 정정 표시 규칙. 게시판 목록에는 정정공고와 원공고를 잇는 값이 없어서, 기관마다 쓰는 머리말·꼬리말을 떼어낸
 * 이름으로 원공고를 찾는다. 표시는 기관별 목록에 있는 것만 정정으로 보고, 그 밖의 말(`…서류심사대상자 발표` 같은 후속 안내)은 이름의
 * 일부로 남아 정정공고로 보지 않는다.
 */
public record BoardRevisionName(String value, List<Pattern> markers) {

    private static final Pattern SPACES = Pattern.compile("\\s+");
    // 앞: (수정) [수정] [정정]
    private static final Pattern SH_LEADING = Pattern.compile("^\\s*[(\\[](수정|정정)[)\\]]\\s*");
    // 뒤: (정정) (수정) (수정-…)
    private static final Pattern SH_TRAILING = Pattern.compile("\\s*\\((정정|수정(-[^)]*)?)\\)\\s*$");
    private static final Pattern GH_LEADING = Pattern.compile("^\\s*(\\[정정공고\\]|\\(수정\\))\\s*");
    private static final Pattern GH_TRAILING = Pattern.compile("\\s*(\\[정정공고\\]|\\(수정\\))\\s*$");

    public BoardRevisionName {
        Objects.requireNonNull(value);
        markers = List.copyOf(markers);
    }

    public static BoardRevisionName sh(String value) {
        return new BoardRevisionName(value, List.of(SH_LEADING, SH_TRAILING));
    }

    public static BoardRevisionName gh(String value) {
        return new BoardRevisionName(value, List.of(GH_LEADING, GH_TRAILING));
    }

    public boolean isRevision() {
        return markers.stream().anyMatch(marker -> marker.matcher(value).find());
    }

    /** 정정 표시를 모두 떼고 공백을 하나로 맞춘 이름. */
    public String baseName() {
        String stripped = value;
        boolean removed = true;
        while (removed) {
            removed = false;
            for (Pattern marker : markers) {
                Matcher matcher = marker.matcher(stripped);
                if (matcher.find()) {
                    stripped = stripped.substring(0, matcher.start()) + stripped.substring(matcher.end());
                    removed = true;
                }
            }
        }
        return SPACES.matcher(stripped.strip()).replaceAll(" ");
    }
}
