package com.toadzip.backend.admin.controller;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 관리자 데이터 관리 API. 서비스가 던진 IllegalArgumentException을 400 INVALID_REQUEST로 돌려준다. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminManagementApi {
}
