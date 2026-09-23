package com.toadzip.backend.user.configuration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.user.oauth.enabled", havingValue = "true")
public class SocialOAuthConfiguration {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${app.user.oauth.google.client-id}") String googleClientId,
            @Value("${app.user.oauth.google.client-secret}") String googleClientSecret,
            @Value("${app.user.oauth.kakao.client-id}") String kakaoClientId,
            @Value("${app.user.oauth.kakao.client-secret}") String kakaoClientSecret,
            @Value("${app.user.oauth.redirect-base-url}") String redirectBaseUrl
    ) {
        requireConfiguration(googleClientId, "GOOGLE_CLIENT_ID");
        requireConfiguration(googleClientSecret, "GOOGLE_CLIENT_SECRET");
        requireConfiguration(kakaoClientId, "KAKAO_CLIENT_ID");
        requireConfiguration(kakaoClientSecret, "KAKAO_CLIENT_SECRET");
        requireConfiguration(redirectBaseUrl, "USER_OAUTH_REDIRECT_BASE_URL");
        ClientRegistration google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(googleClientId)
                .clientSecret(googleClientSecret)
                .scope("openid")
                .redirectUri(redirectBaseUrl + "/api/auth/oauth2/callback/{registrationId}")
                .build();
        ClientRegistration kakao = ClientRegistration.withRegistrationId("kakao")
                .clientId(kakaoClientId)
                .clientSecret(kakaoClientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectBaseUrl + "/api/auth/oauth2/callback/{registrationId}")
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token")
                .userInfoUri("https://kapi.kakao.com/v2/user/me")
                .userNameAttributeName("id")
                .clientName("Kakao")
                .build();
        return new InMemoryClientRegistrationRepository(google, kakao);
    }

    @Bean
    public OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new OAuth2AuthorizedClientRepository() {
            @Override
            public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                    String clientRegistrationId, Authentication principal, HttpServletRequest request
            ) {
                return null;
            }

            @Override
            public void saveAuthorizedClient(
                    OAuth2AuthorizedClient client, Authentication principal,
                    HttpServletRequest request, HttpServletResponse response
            ) {
                // Only the application's session is retained after sign-in.
            }

            @Override
            public void removeAuthorizedClient(
                    String clientRegistrationId, Authentication principal,
                    HttpServletRequest request, HttpServletResponse response
            ) {
                // No provider token is stored.
            }
        };
    }

    private void requireConfiguration(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + "를 설정해야 합니다.");
        }
    }
}
