package com.toadzip.backend.user.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import com.toadzip.backend.user.domain.SocialAuthorizationContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

class SocialAuthorizationRequestResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    private final PrivacyNoticeCatalog notices = new PrivacyNoticeCatalog();
    private final SocialAuthorizationRequestResolver resolver = new SocialAuthorizationRequestResolver(
            new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("google")
                    .clientId("test-client").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("http://localhost/api/auth/oauth2/callback/google")
                    .authorizationUri("https://provider.example/authorize").tokenUri("https://provider.example/token")
                    .build()), notices, Clock.fixed(NOW, ZoneOffset.UTC));

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", "analytics-2026-10-09-v2"})
    void 확인할_수_없는_정책은_대체하지_않고_정상_인가_요청을_만든다(String version) {
        var authorization = resolver.resolve(request(version));

        assertNotNull(authorization.getState());
        SocialAuthorizationContext context = authorization.getAttribute(
                SocialAuthorizationRequestResolver.CONTEXT_ATTRIBUTE);
        assertEquals(new SocialAuthorizationContext(null, NOW), context);
        assertTrue(context.isValidAt(NOW));
    }

    @ParameterizedTest
    @ValueSource(strings = {"privacy-2026-10-09-v1", "privacy-2026-10-10-v1"})
    void 전달된_보관_정책_버전을_최신으로_바꾸지_않고_state에_묶는다(String version) {
        var authorization = resolver.resolve(request(version), "google");

        assertEquals(new SocialAuthorizationContext(version, NOW),
                authorization.getAttribute(SocialAuthorizationRequestResolver.CONTEXT_ATTRIBUTE));
        assertFalse(authorization.getAuthorizationRequestUri().contains("policyVersion"));
    }

    private MockHttpServletRequest request(String version) {
        var request = new MockHttpServletRequest("GET", "/api/auth/oauth2/authorization/google");
        request.setServletPath("/api/auth/oauth2/authorization/google");
        if (version != null) {
            request.setParameter("policyVersion", version);
        }
        return request;
    }
}
