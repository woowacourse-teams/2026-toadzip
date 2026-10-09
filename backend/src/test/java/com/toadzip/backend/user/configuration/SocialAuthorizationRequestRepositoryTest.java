package com.toadzip.backend.user.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.toadzip.backend.user.domain.SocialAuthorizationContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

class SocialAuthorizationRequestRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-09T01:00:00Z");
    private final SocialAuthorizationRequestRepository repository = new SocialAuthorizationRequestRepository(
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 여러_탭의_정책_버전은_state별로_보존되고_한번만_소비된다() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest first = request(session, "first");
        MockHttpServletRequest second = request(session, "second");
        var response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(authorization("first", "v1", NOW), first, response);
        repository.saveAuthorizationRequest(authorization("second", "v2", NOW), second, response);

        assertEquals("first", repository.removeAuthorizationRequest(first, response).getState());
        assertEquals(new SocialAuthorizationContext("v1", NOW),
                first.getAttribute(SocialAuthorizationRequestResolver.CONTEXT_ATTRIBUTE));
        assertNull(repository.removeAuthorizationRequest(request(session, "first"), response));
        assertEquals("second", repository.removeAuthorizationRequest(second, response).getState());
        assertEquals(new SocialAuthorizationContext("v2", NOW),
                second.getAttribute(SocialAuthorizationRequestResolver.CONTEXT_ATTRIBUTE));
    }

    @Test
    void 만료_직전까지만_유효하며_만료_시각과_없는_state는_거부한다() {
        var session = new MockHttpSession();
        var response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(authorization("valid", "v1", NOW.minusSeconds(599)),
                request(session, "valid"), response);
        repository.saveAuthorizationRequest(authorization("expired", "v1", NOW.minusSeconds(600)),
                request(session, "expired"), response);
        assertEquals("valid", repository.loadAuthorizationRequest(request(session, "valid")).getState());
        assertNull(repository.removeAuthorizationRequest(request(session, "expired"), response));
        assertNull(repository.removeAuthorizationRequest(request(session, "unknown"), response));
    }

    private OAuth2AuthorizationRequest authorization(String state, String version, Instant issuedAt) {
        return OAuth2AuthorizationRequest.authorizationCode().authorizationUri("https://provider.example/authorize")
                .clientId("test-client").redirectUri("http://localhost/callback").state(state)
                .attributes(attributes -> attributes.put(SocialAuthorizationRequestResolver.CONTEXT_ATTRIBUTE,
                        new SocialAuthorizationContext(version, issuedAt))).build();
    }

    private MockHttpServletRequest request(MockHttpSession session, String state) {
        var request = new MockHttpServletRequest();
        request.setSession(session);
        request.setParameter("state", state);
        return request;
    }
}
