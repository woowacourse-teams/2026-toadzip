package com.toadzip.backend.user.configuration;

import com.toadzip.backend.user.domain.SocialAuthorizationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

public class SocialAuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String REQUESTS_ATTRIBUTE = SocialAuthorizationRequestRepository.class.getName();
    private static final int MAX_PENDING_REQUESTS = 32;
    private final Clock clock;

    public SocialAuthorizationRequestRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        synchronized (session) {
            return requests(session).get(request.getParameter("state"));
        }
    }

    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorization, HttpServletRequest request, HttpServletResponse response
    ) {
        if (authorization == null) {
            removeAuthorizationRequest(request, response);
            return;
        }
        HttpSession session = request.getSession();
        synchronized (session) {
            Map<String, OAuth2AuthorizationRequest> pending = requests(session);
            if (pending.size() >= MAX_PENDING_REQUESTS) {
                pending.remove(pending.keySet().iterator().next());
            }
            pending.put(authorization.getState(), authorization);
            session.setAttribute(REQUESTS_ATTRIBUTE, pending);
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request, HttpServletResponse response
    ) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        synchronized (session) {
            Map<String, OAuth2AuthorizationRequest> pending = requests(session);
            OAuth2AuthorizationRequest authorization = pending.remove(request.getParameter("state"));
            session.setAttribute(REQUESTS_ATTRIBUTE, pending);
            if (authorization != null) {
                request.setAttribute(SocialAuthorizationRequestResolver.CONTEXT_ATTRIBUTE,
                        authorization.getAttribute(SocialAuthorizationRequestResolver.CONTEXT_ATTRIBUTE));
            }
            return authorization;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, OAuth2AuthorizationRequest> requests(HttpSession session) {
        Object saved = session.getAttribute(REQUESTS_ATTRIBUTE);
        Map<String, OAuth2AuthorizationRequest> pending = new LinkedHashMap<>();
        if (saved instanceof Map<?, ?>) {
            pending.putAll((Map<String, OAuth2AuthorizationRequest>) saved);
        }
        pending.values().removeIf(authorization -> {
            SocialAuthorizationContext context = authorization.getAttribute(
                    SocialAuthorizationRequestResolver.CONTEXT_ATTRIBUTE);
            return context == null || !context.isValidAt(clock.instant());
        });
        session.setAttribute(REQUESTS_ATTRIBUTE, pending);
        return pending;
    }
}
