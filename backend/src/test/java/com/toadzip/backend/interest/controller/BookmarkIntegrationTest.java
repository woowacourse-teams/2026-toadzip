package com.toadzip.backend.interest.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.user.domain.User;
import com.toadzip.backend.user.repository.UserRepository;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BookmarkIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private UserRepository users;

    @Test
    void 비회원은_본인_북마크에_접근하지_못한다() throws Exception {
        mvc.perform(get("/api/v1/bookmarks/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void 회원의_빈_북마크를_조회한다() throws Exception {
        User member = users.save(User.create("bookmark-empty", LocalDateTime.now()));
        mvc.perform(get("/api/v1/bookmarks/me").with(user(member.getId().toString()).roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void 잘못된_유형과_식별자를_거부한다() throws Exception {
        User member = users.save(User.create("bookmark-invalid", LocalDateTime.now()));
        for (String path : java.util.List.of("REGION/1", "COMPLEX/0", "COMPLEX/no", "COMPLEX/-1")) {
            mvc.perform(put("/api/v1/bookmarks/me/" + path).with(user(member.getId().toString()).roles("USER")).with(csrf()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void CSRF_없는_저장을_거부한다() throws Exception {
        mvc.perform(put("/api/v1/bookmarks/me/COMPLEX/1").with(user("91515").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 없는_원본은_저장할_수_없고_반복_해제는_성공한다() throws Exception {
        jdbc.update("INSERT INTO users(id, login_identifier, created_at) VALUES(91515, 'bookmark-test', CURRENT_TIMESTAMP)");
        mvc.perform(put("/api/v1/bookmarks/me/COMPLEX/9223372036854775807")
                        .with(user("91515").roles("USER")).with(csrf())).andExpect(status().isNotFound());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(delete("/api/v1/bookmarks/me/COMPLEX/9223372036854775807")
                            .with(user("91515").roles("USER")).with(csrf())).andExpect(status().isNoContent());
        }
    }

    @Test
    void 저장은_멱등이며_다른_계정은_조회하거나_삭제하지_못한다() throws Exception {
        User owner = users.save(User.create("bookmark-owner", LocalDateTime.now()));
        User other = users.save(User.create("bookmark-other", LocalDateTime.now()));
        HousingComplex complex = complex();
        String path = "/api/v1/bookmarks/me/COMPLEX/" + complex.getId();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(put(path).with(user(owner.getId().toString()).roles("USER")).with(csrf()))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(get("/api/v1/bookmarks/me").with(user(owner.getId().toString()).roles("USER")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("북마크 단지"));
        mvc.perform(delete(path).with(user(other.getId().toString()).roles("USER")).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/bookmarks/me").with(user(other.getId().toString()).roles("USER")))
                .andExpect(jsonPath("$.items").isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM favorite_housing_complexes WHERE user_id = ?",
                Integer.class, owner.getId()));
    }

    @Test
    void 비활성_원본과_종료_공고도_보존하고_최근_저장순으로_조회한다() throws Exception {
        User owner = users.save(User.create("bookmark-closed", LocalDateTime.now()));
        HousingComplex complex = complex();
        jdbc.update("INSERT INTO favorite_housing_complexes(user_id, housing_complex_id, created_at) "
                + "VALUES (?, ?, TIMESTAMP '2026-01-01 00:00:00')", owner.getId(), complex.getId());
        jdbc.update("UPDATE housing_complexes SET admin_deleted = true WHERE id = ?", complex.getId());
        Announcement announcement = announcements.save(Announcement.create("bookmark-announcement", null, null,
                "종료 공고", "원공고", "행복주택", "신규모집", "LH", LocalDate.of(2020, 1, 1),
                LocalDate.of(2020, 1, 2), LocalDate.of(2020, 1, 3), LocalDate.of(2020, 1, 4),
                "https://example.com/notice", null, 0L, null));
        mvc.perform(put("/api/v1/bookmarks/me/ANNOUNCEMENT/" + announcement.getId())
                        .with(user(owner.getId().toString()).roles("USER")).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/bookmarks/me").with(user(owner.getId().toString()).roles("USER")))
                .andExpect(jsonPath("$.items[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.items[1].available").value(false));
        mvc.perform(put("/api/v1/bookmarks/me/COMPLEX/" + complex.getId())
                        .with(user(owner.getId().toString()).roles("USER")).with(csrf()))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE announcements SET application_schedule_reviewed = true WHERE id = ?", announcement.getId());
        mvc.perform(get("/api/v1/bookmarks/me").with(user(owner.getId().toString()).roles("USER")))
                .andExpect(jsonPath("$.items[0].applicationStartDate").isEmpty())
                .andExpect(jsonPath("$.items[0].applicationEndDate").isEmpty());
    }

    @Test
    void 병합으로_생긴_중복은_카드_하나로_조회하고_한번에_해제한다() throws Exception {
        User owner = users.save(User.create("bookmark-merge", LocalDateTime.now()));
        HousingComplex complex = complex();
        for (int attempt = 0; attempt < 2; attempt++) {
            jdbc.update("INSERT INTO favorite_housing_complexes(user_id, housing_complex_id, created_at) "
                    + "VALUES (?, ?, CURRENT_TIMESTAMP)", owner.getId(), complex.getId());
        }
        mvc.perform(get("/api/v1/bookmarks/me").with(user(owner.getId().toString()).roles("USER")))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(delete("/api/v1/bookmarks/me/COMPLEX/" + complex.getId())
                        .with(user(owner.getId().toString()).roles("USER")).with(csrf()))
                .andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM favorite_housing_complexes WHERE user_id = ?",
                Integer.class, owner.getId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 같은_회원의_동시_저장은_중복을_만들지_않는다() throws Exception {
        User owner = users.save(User.create("bookmark-concurrent", LocalDateTime.now()));
        HousingComplex complex = complex();
        var executor = Executors.newFixedThreadPool(4);
        try {
            var calls = java.util.stream.IntStream.range(0, 8).mapToObj(index ->
                    executor.submit(() -> {
                        mvc.perform(put("/api/v1/bookmarks/me/COMPLEX/" + complex.getId())
                                        .with(user(owner.getId().toString()).roles("USER")).with(csrf()))
                                .andExpect(status().isNoContent());
                        return true;
                    })).toList();
            for (var call : calls) {
                call.get(15, TimeUnit.SECONDS);
            }
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM favorite_housing_complexes WHERE user_id = ?",
                    Integer.class, owner.getId()));
        } finally {
            executor.shutdownNow();
            jdbc.update("DELETE FROM favorite_housing_complexes WHERE user_id = ?", owner.getId());
            complexes.deleteById(complex.getId());
            users.deleteById(owner.getId());
        }
    }

    private HousingComplex complex() {
        return complexes.save(HousingComplex.create("북마크 단지", "bookmark-" + java.util.UUID.randomUUID(), "행복주택",
                Address.create("서울 중구 세종대로 110", "1114010100100010000", "1114010100", "11", "11140",
                        new BigDecimal("37.5"), new BigDecimal("126.9")),
                100, "LH", LocalDate.of(2020, 1, 1), "개별난방", "아파트", "계단식", true, 80, null, null));
    }
}
