package com.toadzip.backend.announcement.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 관리자가 올린 공고문 PDF 하나.
 *
 * @param fileName 올린 파일 이름
 * @param content PDF 내용. 작업이 끝나면 워커가 비운다.
 * @param sha256 내용의 SHA-256(소문자 16진수). 화면이 지금 고른 PDF와 같은지 비교한다.
 */
public record PdfExtractionSource(String fileName, byte[] content, String sha256) {

    public static PdfExtractionSource of(String fileName, byte[] content) {
        return new PdfExtractionSource(fileName, content, sha256(content));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
