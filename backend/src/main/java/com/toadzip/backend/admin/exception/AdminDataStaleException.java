package com.toadzip.backend.admin.exception;

/** 화면이 받은 기준값이 지금 저장값·원천과 달라, 최신값을 다시 조회하면 이어서 저장할 수 있는 충돌. */
public class AdminDataStaleException extends AdminDataConflictException {
    public AdminDataStaleException(String message) { super(message); }
}
