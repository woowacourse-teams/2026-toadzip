package com.toadzip.backend.user.exception;

public class SocialAuthorizationRejectedException extends RuntimeException {

    public SocialAuthorizationRejectedException() {
        super("탈퇴 이전에 시작된 로그인 요청입니다.");
    }
}
