package com.toadzip.backend.privacy.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class PrivacyCookieSupport {

    private final boolean secure;
    private final String name;

    public PrivacyCookieSupport(@Value("${privacy.cookie.secure:true}") boolean secure, Environment environment) {
        this.secure = secure;
        if (!secure && !environment.matchesProfiles("local", "test")) {
            throw new IllegalStateException("Insecure privacy cookies are only allowed in local and test profiles");
        }
        if (secure) {
            name = "__Host-toadzip-privacy";
            return;
        }
        name = "toadzip-privacy-local";
    }

    public String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies).filter(cookie -> cookie.getName().equals(name)).map(Cookie::getValue)
                .findFirst().orElse(null);
    }

    public String header(String token, Instant now, Instant expiresAt) {
        long seconds = Math.max(0, Duration.between(now, expiresAt).getSeconds());
        return ResponseCookie.from(name, token).httpOnly(true).secure(secure).sameSite("Lax").path("/")
                .maxAge(seconds).build().toString();
    }
}
