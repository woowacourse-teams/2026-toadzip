package com.toadzip.backend.ingest.lhrefinement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import org.junit.jupiter.api.Test;

class LhRevisionNameTest {

    @Test
    void 정정공고_머리말이면_정정으로_분류한다() {
        LhRevisionName name = new LhRevisionName("[정정공고] 동탄 행복주택 입주자 모집");

        assertThat(name.isRevision()).isTrue();
        assertThat(name.publicationType()).contains(AnnouncementPublicationType.CORRECTION);
    }

    @Test
    void 취소공고_머리말이면_취소로_분류한다() {
        LhRevisionName name = new LhRevisionName("  [취소공고]동탄 행복주택 입주자 모집");

        assertThat(name.isRevision()).isTrue();
        assertThat(name.publicationType()).contains(AnnouncementPublicationType.CANCELLATION);
    }

    @Test
    void 머리말이_이름_중간에_있으면_정정_취소공고가_아니다() {
        LhRevisionName name = new LhRevisionName("동탄 행복주택 [정정공고] 입주자 모집");

        assertThat(name.isRevision()).isFalse();
        assertThat(name.publicationType()).isEmpty();
        assertThat(name.baseName()).isEqualTo("동탄 행복주택 [정정공고] 입주자 모집");
    }

    @Test
    void 머리말이_여러_개면_첫_머리말로_분류하고_모두_뗀_이름을_원공고_이름으로_쓴다() {
        LhRevisionName name = new LhRevisionName(" [취소공고] [정정공고]  동탄   행복주택\t입주자 모집 ");

        assertThat(name.publicationType()).contains(AnnouncementPublicationType.CANCELLATION);
        assertThat(name.baseName()).isEqualTo("동탄 행복주택 입주자 모집");
    }

    @Test
    void 원공고_이름은_공백만_하나로_맞춘다() {
        assertThat(new LhRevisionName("  동탄   행복주택 입주자 모집 ").baseName()).isEqualTo("동탄 행복주택 입주자 모집");
    }
}
