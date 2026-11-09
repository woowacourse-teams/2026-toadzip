package com.toadzip.backend.announcement.domain;

import java.time.Instant;

/** 저장된 PDF 보정 초안. revision은 첫 저장이 1이고 저장할 때마다 1씩 는다. */
public record StoredCorrectionDraft(CorrectionDraftContent content, long revision, Instant updatedAt) {
}
