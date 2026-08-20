package com.toadzip.backend.user.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class UserEligibilityInfoTest {

    @Test
    void 공공주택_자격_판단에_필요한_유저_자격_정보를_생성한다() {
        User user = User.create("login-id", LocalDateTime.of(2026, 8, 19, 12, 0));
        LocalDate birthDate = LocalDate.of(1995, 5, 10);
        LocalDate marriageDate = LocalDate.of(2024, 3, 1);
        LocalDate subscriptionJoinDate = LocalDate.of(2020, 1, 15);
        LocalDate newbornBirthDate = LocalDate.of(2026, 2, 1);
        BigDecimal spouseIncome = new BigDecimal("2500000");
        BigDecimal personalIncome = new BigDecimal("3000000");
        BigDecimal householdIncome = new BigDecimal("5500000");
        BigDecimal parentsIncome = new BigDecimal("6000000");
        BigDecimal totalAssets = new BigDecimal("150000000");
        BigDecimal vehicleValue = new BigDecimal("20000000");
        Method createMethod = assertDoesNotThrow(
                () -> UserEligibilityInfo.class.getDeclaredMethod(
                        "create",
                        User.class,
                        LocalDate.class,
                        String.class,
                        boolean.class,
                        int.class,
                        boolean.class,
                        boolean.class,
                        String.class,
                        LocalDate.class,
                        BigDecimal.class,
                        int.class,
                        boolean.class,
                        boolean.class,
                        BigDecimal.class,
                        BigDecimal.class,
                        BigDecimal.class,
                        BigDecimal.class,
                        BigDecimal.class,
                        boolean.class,
                        LocalDate.class,
                        Integer.class,
                        boolean.class,
                        boolean.class,
                        boolean.class,
                        boolean.class,
                        LocalDate.class
                )
        );

        UserEligibilityInfo userEligibilityInfo = assertDoesNotThrow(
                () -> (UserEligibilityInfo) createMethod.invoke(
                        null,
                        user,
                        birthDate,
                        "서울특별시",
                        true,
                        3,
                        false,
                        true,
                        "기혼",
                        marriageDate,
                        spouseIncome,
                        1,
                        false,
                        true,
                        personalIncome,
                        householdIncome,
                        parentsIncome,
                        totalAssets,
                        vehicleValue,
                        true,
                        subscriptionJoinDate,
                        36,
                        false,
                        false,
                        false,
                        false,
                        newbornBirthDate
                )
        );

        assertEquals(user, userEligibilityInfo.getUser());
        assertEquals(birthDate, userEligibilityInfo.getBirthDate());
        assertEquals("서울특별시", userEligibilityInfo.getCurrentResidenceRegion());
        assertTrue(userEligibilityInfo.isHeadOfHousehold());
        assertEquals(3, userEligibilityInfo.getHouseholdMemberCount());
        assertFalse(userEligibilityInfo.isSingleParentFamily());
        assertTrue(userEligibilityInfo.isNonHomeowner());
        assertEquals("기혼", userEligibilityInfo.getMaritalStatus());
        assertEquals(marriageDate, userEligibilityInfo.getMarriageDate());
        assertEquals(spouseIncome, userEligibilityInfo.getSpouseMonthlyIncome());
        assertEquals(1, userEligibilityInfo.getChildCount());
        assertFalse(userEligibilityInfo.isStudent());
        assertTrue(userEligibilityInfo.isEmployed());
        assertEquals(personalIncome, userEligibilityInfo.getPersonalAverageMonthlyIncome());
        assertEquals(householdIncome, userEligibilityInfo.getHouseholdAverageMonthlyIncome());
        assertEquals(parentsIncome, userEligibilityInfo.getParentsAverageMonthlyIncome());
        assertEquals(totalAssets, userEligibilityInfo.getTotalAssets());
        assertEquals(vehicleValue, userEligibilityInfo.getVehicleValue());
        assertTrue(userEligibilityInfo.isHousingSubscriptionAccount());
        assertEquals(subscriptionJoinDate, userEligibilityInfo.getHousingSubscriptionAccountJoinDate());
        assertEquals(36, userEligibilityInfo.getHousingSubscriptionPaymentCount());
        assertFalse(userEligibilityInfo.isHousingBenefitRecipient());
        assertFalse(userEligibilityInfo.isBasicLivingRecipient());
        assertFalse(userEligibilityInfo.isDisabled());
        assertFalse(userEligibilityInfo.isVeteran());
        assertEquals(newbornBirthDate, userEligibilityInfo.getNewbornBirthDate());
    }

    @Test
    void 유저와_기본_자격정보는_필수다() {
        User user = createUser();
        LocalDate birthDate = LocalDate.of(1995, 5, 10);
        BigDecimal amount = new BigDecimal("1000000");

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(null, birthDate, "서울특별시", 3, "기혼", amount, 1,
                                amount, amount, amount, amount, amount, 36)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, null, "서울특별시", 3, "기혼", amount, 1,
                                amount, amount, amount, amount, amount, 36)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, birthDate, " ", 3, "기혼", amount, 1,
                                amount, amount, amount, amount, amount, 36)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, birthDate, "서울특별시", 3, " ", amount, 1,
                                amount, amount, amount, amount, amount, 36)
                )
        );
    }

    @Test
    void 가구원과_자녀와_청약통장_납입_횟수는_음수일_수_없다() {
        User user = createUser();
        LocalDate birthDate = LocalDate.of(1995, 5, 10);
        BigDecimal amount = new BigDecimal("1000000");

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, birthDate, "서울특별시", -1, "기혼", amount, 1,
                                amount, amount, amount, amount, amount, 36)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, birthDate, "서울특별시", 3, "기혼", amount, -1,
                                amount, amount, amount, amount, amount, 36)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, birthDate, "서울특별시", 3, "기혼", amount, 1,
                                amount, amount, amount, amount, amount, -1)
                )
        );
    }

    @Test
    void 소득과_자산은_음수일_수_없다() {
        User user = createUser();
        LocalDate birthDate = LocalDate.of(1995, 5, 10);
        BigDecimal amount = new BigDecimal("1000000");
        BigDecimal negativeAmount = BigDecimal.ONE.negate();

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, birthDate, "서울특별시", 3, "기혼", negativeAmount, 1,
                                amount, amount, amount, amount, amount, 36)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, birthDate, "서울특별시", 3, "기혼", amount, 1,
                                negativeAmount, amount, amount, amount, amount, 36)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, birthDate, "서울특별시", 3, "기혼", amount, 1,
                                amount, negativeAmount, amount, amount, amount, 36)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, birthDate, "서울특별시", 3, "기혼", amount, 1,
                                amount, amount, negativeAmount, amount, amount, 36)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, birthDate, "서울특별시", 3, "기혼", amount, 1,
                                amount, amount, amount, negativeAmount, amount, 36)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, birthDate, "서울특별시", 3, "기혼", amount, 1,
                                amount, amount, amount, amount, negativeAmount, 36)
                )
        );
    }

    @Test
    void 본인과_가구와_부모_소득과_자산은_필수다() {
        User user = createUser();
        LocalDate birthDate = LocalDate.of(1995, 5, 10);
        BigDecimal amount = new BigDecimal("1000000");

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, birthDate, "서울특별시", 3, "기혼", amount, 1,
                                null, amount, amount, amount, amount, 36)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, birthDate, "서울특별시", 3, "기혼", amount, 1,
                                amount, null, amount, amount, amount, 36)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, birthDate, "서울특별시", 3, "기혼", amount, 1,
                                amount, amount, null, amount, amount, 36)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, birthDate, "서울특별시", 3, "기혼", amount, 1,
                                amount, amount, amount, null, amount, 36)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserEligibilityInfo(user, birthDate, "서울특별시", 3, "기혼", amount, 1,
                                amount, amount, amount, amount, null, 36)
                )
        );
    }

    private User createUser() {
        return User.create("login-id", LocalDateTime.of(2026, 8, 19, 12, 0));
    }

    private UserEligibilityInfo createUserEligibilityInfo(
            User user,
            LocalDate birthDate,
            String currentResidenceRegion,
            int householdMemberCount,
            String maritalStatus,
            BigDecimal spouseMonthlyIncome,
            int childCount,
            BigDecimal personalAverageMonthlyIncome,
            BigDecimal householdAverageMonthlyIncome,
            BigDecimal parentsAverageMonthlyIncome,
            BigDecimal totalAssets,
            BigDecimal vehicleValue,
            Integer housingSubscriptionPaymentCount
    ) {
        return UserEligibilityInfo.create(
                user,
                birthDate,
                currentResidenceRegion,
                true,
                householdMemberCount,
                false,
                true,
                maritalStatus,
                LocalDate.of(2024, 3, 1),
                spouseMonthlyIncome,
                childCount,
                false,
                true,
                personalAverageMonthlyIncome,
                householdAverageMonthlyIncome,
                parentsAverageMonthlyIncome,
                totalAssets,
                vehicleValue,
                true,
                LocalDate.of(2020, 1, 15),
                housingSubscriptionPaymentCount,
                false,
                false,
                false,
                false,
                LocalDate.of(2026, 2, 1)
        );
    }
}
