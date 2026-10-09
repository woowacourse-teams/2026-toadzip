package com.toadzip.backend.user.configuration;

import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import com.toadzip.backend.user.domain.SocialAuthorizationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

public class SocialAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    public static final String CONTEXT_ATTRIBUTE = SocialAuthorizationContext.class.getName();
    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final PrivacyNoticeCatalog notices;
    private final Clock clock;

    public SocialAuthorizationRequestResolver(
            ClientRegistrationRepository registrations, PrivacyNoticeCatalog notices, Clock clock
    ) {
        delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/api/auth/oauth2/authorization");
        this.notices = notices;
        this.clock = clock;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return stamp(delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return stamp(delegate.resolve(request, registrationId));
    }

    private OAuth2AuthorizationRequest stamp(OAuth2AuthorizationRequest request) {
        if (request == null) {
            return null;
        }
        SocialAuthorizationContext context = new SocialAuthorizationContext(
                notices.currentVersion("PRIVACY_POLICY"), clock.instant());
        return OAuth2AuthorizationRequest.from(request)
                .attributes(attributes -> attributes.put(CONTEXT_ATTRIBUTE, context)).build();
    }
}
