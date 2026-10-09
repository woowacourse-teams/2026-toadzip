package com.toadzip.backend.admin.configuration;

import com.toadzip.backend.admin.domain.AdminRole;
import com.toadzip.backend.global.security.JsonAccessDeniedHandler;
import com.toadzip.backend.global.security.JsonAuthenticationEntryPoint;
import com.toadzip.backend.global.security.SecurityErrorResponseWriter;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import com.toadzip.backend.user.configuration.DeletedUserSessionFilter;
import com.toadzip.backend.user.configuration.SocialAuthorizationRequestRepository;
import com.toadzip.backend.user.configuration.SocialAuthorizationRequestResolver;
import com.toadzip.backend.user.configuration.SocialLoginPolicyFilter;
import com.toadzip.backend.user.repository.UserRepository;
import com.toadzip.backend.user.service.SocialLoginFailureHandler;
import com.toadzip.backend.user.service.SocialLoginSuccessHandler;
import java.util.List;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class AdminSecurityConfiguration {

    private final JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint;
    private final JsonAccessDeniedHandler jsonAccessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            CookieCsrfTokenRepository csrfTokenRepository,
            ObjectProvider<ClientRegistrationRepository> registrations,
            ObjectProvider<OAuth2AuthorizedClientRepository> authorizedClients,
            SocialLoginSuccessHandler socialLoginSuccessHandler,
            SocialLoginFailureHandler socialLoginFailureHandler,
            UserRepository users,
            SecurityErrorResponseWriter errors,
            PrivacyNoticeCatalog notices,
            Clock clock,
            @Value("${app.user.oauth.failure-url}") String failureUrl
    ) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(sessionFixation -> sessionFixation.changeSessionId())
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/admin/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/auth/oauth2/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/login").permitAll()
                        .requestMatchers("/api/admin/**").hasRole(AdminRole.ADMIN.name())
                        .requestMatchers("/api/auth/me", "/api/auth/logout").hasRole("USER")
                        .requestMatchers("/api/v1/privacy/analytics/me").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/notification-subscriptions/guest").permitAll()
                        .requestMatchers("/api/v1/notification-subscriptions/**").hasRole("USER")
                        .anyRequest().permitAll()
                )
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(jsonAuthenticationEntryPoint)
                        .accessDeniedHandler(jsonAccessDeniedHandler)
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .addFilterBefore(new DeletedUserSessionFilter(users, errors), AuthorizationFilter.class);
        if (registrations.getIfAvailable() != null) {
            http.addFilterBefore(new SocialLoginPolicyFilter(notices, failureUrl),
                    OAuth2AuthorizationRequestRedirectFilter.class);
            http.oauth2Login(oauth -> oauth
                    .authorizationEndpoint(endpoint -> endpoint.baseUri("/api/auth/oauth2/authorization")
                            .authorizationRequestResolver(new SocialAuthorizationRequestResolver(
                                    registrations.getObject(), notices, clock))
                            .authorizationRequestRepository(new SocialAuthorizationRequestRepository(clock)))
                    .redirectionEndpoint(endpoint -> endpoint.baseUri("/api/auth/oauth2/callback/*"))
                    .authorizedClientRepository(authorizedClients.getObject())
                    .successHandler(socialLoginSuccessHandler)
                    .failureHandler(socialLoginFailureHandler));
        }
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.admin.cors.allowed-origin}") String allowedOrigin
    ) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "X-Notification-Client-Id"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
