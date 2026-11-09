package com.toadzip.backend.announcement.domain;

/** 공고 단지를 제품 단지에 연결한 방법. 한 번 붙은 연결은 정제가 다시 계산하지 않고, 바꾸는 것은 PDF 보정의 관리자뿐이다. */
public enum ComplexLinkMethod {
    /** 공고 상세 주소와 공급유형이 같은 기존 단지가 하나뿐이라 정제가 연결했다. */
    AUTO,
    /** PDF 보정에서 관리자가 정했다. */
    ADMIN,
    /** 기존 공급행이 이 공고 단지에서 한 단지만 가리켜 그 연결을 옮겼다. */
    MIGRATED
}
