package com.toadzip.backend.ingest.lh.refinement.domain;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LH 공고명의 정정·취소 머리말 규칙. LH 공고목록에는 정정·취소공고와 원공고를 잇는 값이 없어서, 공고명 앞의 `[정정공고]`, `[취소공고]`로
 * 종류를 정하고 머리말을 뗀 이름으로 원공고를 찾는다.
 */
public record LhRevisionName(String value) {

    private static final Pattern REVISION_PREFIX = Pattern.compile("^\\s*\\[(정정|취소)공고\\]\\s*");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    public LhRevisionName {
        Objects.requireNonNull(value);
    }

    public boolean isRevision() {
        return REVISION_PREFIX.matcher(value).find();
    }

    /** 첫 머리말로 정한 정정·취소 종류. 머리말이 없으면 비어 있다. */
    public Optional<AnnouncementPublicationType> publicationType() {
        Matcher matcher = REVISION_PREFIX.matcher(value);
        if (!matcher.find()) {
            return Optional.empty();
        }
        if ("취소".equals(matcher.group(1))) {
            return Optional.of(AnnouncementPublicationType.CANCELLATION);
        }
        return Optional.of(AnnouncementPublicationType.CORRECTION);
    }

    /** 머리말을 모두 떼고 공백을 하나로 맞춘 이름. */
    public String baseName() {
        String stripped = value;
        Matcher matcher = REVISION_PREFIX.matcher(stripped);
        while (matcher.find()) {
            stripped = stripped.substring(matcher.end());
            matcher = REVISION_PREFIX.matcher(stripped);
        }
        return SPACES.matcher(stripped.strip()).replaceAll(" ");
    }
}
