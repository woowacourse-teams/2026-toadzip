package com.toadzip.backend.eligibility.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.eligibility.EligibilityFixtures;
import com.toadzip.backend.eligibility.domain.EligibilityProfile;
import com.toadzip.backend.eligibility.domain.EligibilityWork;
import com.toadzip.backend.eligibility.domain.FamilyDetailsState;
import com.toadzip.backend.eligibility.domain.InputValue;
import com.toadzip.backend.eligibility.domain.ProfileField;
import com.toadzip.backend.eligibility.service.EligibilityRecheckWorker;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** 여러 공고 조회가 공고마다 쿼리를 반복하지 않는지(DB N+1이 없는지) 실제로 보낸 SQL 수로 확인한다. */
@Import(EligibilityBatchQueryCountIntegrationTest.StatementCounting.class)
class EligibilityBatchQueryCountIntegrationTest extends EligibilityIntegrationSupport {
    private static final ThreadLocal<Integer> STATEMENTS = ThreadLocal.withInitial(() -> 0);

    @Autowired EligibilityRecheckWorker worker;

    @Test
    void 공고_수가_늘어도_여러_공고_조회의_SQL_수는_같다() throws Exception {
        List<Long> announcements = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            long announcement = announcement();
            publish(announcement, saveDraft(announcement, null, 0, EligibilityFixtures.d01()))
                    .andExpect(status().isOk());
            announcements.add(announcement);
        }
        jdbc.update("INSERT INTO basic_comparison_profiles (user_id, version, profile) VALUES (?, 1, ?::jsonb)",
                USER, json.writeValueAsString(new EligibilityProfile(FamilyDetailsState.NOT_STARTED, Map.of(
                        ProfileField.BIRTH_DATE, InputValue.provided("1997-01-10"),
                        ProfileField.FAMILY_TYPE, InputValue.provided("UNMARRIED")))));
        assertThat(worker.runOnce(EligibilityWork.Kind.PROFILE)).isTrue();
        statements(announcements.subList(0, 1));

        int one = statements(announcements.subList(0, 1));
        int four = statements(announcements);

        assertThat(four).isEqualTo(one);
    }

    private int statements(List<Long> announcements) throws Exception {
        String ids = announcements.stream().map(String::valueOf).collect(Collectors.joining(","));
        STATEMENTS.set(0);
        mvc.perform(get("/api/v2/eligibility/announcements").param("announcementIds", ids).param("region", "41463")
                        .with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(announcements.size()))
                .andExpect(jsonPath("$.data[*].freshness", everyItem(is("CURRENT"))));
        return STATEMENTS.get();
    }

    @TestConfiguration
    static class StatementCounting {
        @Bean
        static BeanPostProcessor countingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource source)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                            (proxy, method, args) -> {
                                Object result = invoke(source, method, args);
                                if (result instanceof Connection connection) {
                                    return counting(connection);
                                }
                                return result;
                            });
                }
            };
        }

        private static Connection counting(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") || method.getName().equals("createStatement")
                                || method.getName().equals("prepareCall")) {
                            STATEMENTS.set(STATEMENTS.get() + 1);
                        }
                        return invoke(connection, method, args);
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
                throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}
