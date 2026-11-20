package com.toadzip.backend.announcement.service;

/** 자동 공개를 멈추고 운영자 검수로 넘기는 사유. 메시지는 검수함에 그대로 보이므로 운영자가 읽을 수 있는 문장만 담는다. */
class PublicationHold extends RuntimeException {
    PublicationHold(String reason) {
        super(reason);
    }

    PublicationHold(String reason, Throwable cause) {
        super(reason, cause);
    }
}
