package com.toadzip.backend.announcement.domain;

/**
 * 공고 공급정보(공급행·공급대상)의 주인. 원천 소유면 정제가 원천 값으로 공급행을 다시 만들 수 있다. 관리자가 공급정보를 한 번이라도
 * 고치면 공고문 소유가 되고, 정제는 관리자가 공고문으로 확정한 공급행을 다시 만들지 않는다. 공고문 소유는 원천 소유로 돌아가지 않는다.
 */
public enum SupplyOwner {
    LH_SOURCE,
    ANNOUNCEMENT_DOCUMENT
}
