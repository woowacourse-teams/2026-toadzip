package com.toadzip.backend.user.service;

import com.toadzip.backend.notification.service.NotificationPushDeviceService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;

class SocialLoginSuccessHandlerTest {

    @AfterEach
    void 인증_컨텍스트를_정리한다() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({
            "http://localhost/, http://localhost/explore",
            "https://bokduckbang.com, https://bokduckbang.com/explore",
            "https://dev.bokduckbang.com/login, https://dev.bokduckbang.com/explore",
            "/, /explore",
            "https://bokduckbang.com/?complexId=7#detail, https://bokduckbang.com/explore?complexId=7#detail",
            "https://bokduckbang.com/explore?complexId=7, https://bokduckbang.com/explore?complexId=7",
            "https://bokduckbang.com/mypage/notifications, https://bokduckbang.com/mypage/notifications"
    })
    void 로그인_성공은_브라우저_저장소_없이_탐색으로_복귀하고_명시한_다른_경로는_보존한다(
            String configuredUrl, String expectedUrl
    ) throws Exception {
        SocialUserService userService = mock(SocialUserService.class);
        when(userService.findOrCreate("kakao", "123", null, null)).thenReturn(7L);
        SocialLoginSuccessHandler handler = new SocialLoginSuccessHandler(
                userService, new HttpSessionSecurityContextRepository(), new SocialLoginFailureHandler(),
                mock(NotificationPushDeviceService.class));
        ReflectionTestUtils.setField(handler, "successUrl", configuredUrl);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        DefaultOAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("id", 123), "id");
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), "kakao");

        handler.onAuthenticationSuccess(request, response, authentication);

        assertEquals(expectedUrl, response.getRedirectedUrl());
        assertEquals("7", SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
