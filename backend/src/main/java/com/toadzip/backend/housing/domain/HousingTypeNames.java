package com.toadzip.backend.housing.domain;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 공고문 표기 이름의 비교 키. 공고 주택형(신청형)과 단지 주택타입은 모두 공고문 표기를 따르고 전용면적으로 묶지 않는다
 * (59B-1과 59D-1은 면적이 같아도 다른 주택타입). 원천이 형명 끝에 붙인 신청자 표기("36형 일반(고령자)", "26A[청년]",
 * "26A-1형[고령자(주거약자용)]")는 대상이라 떼고, 공백·대소문자·끝 '형'·앞자리 0("055.7200A")은 무시한다.
 */
public final class HousingTypeNames {

    private static final Pattern HOUSING_VARIANT = Pattern.compile("주거약자(용|형)?");

    private HousingTypeNames() {
    }

    /** 형명 하나의 비교 키. 비었으면 빈 문자열이다. */
    public static String key(String typeName) {
        return withoutApplicant(Normalizer.normalize(typeName == null ? "" : typeName, Normalizer.Form.NFKC))
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT).replaceFirst("형$", "").replaceFirst("^0+(?=\\d)", "");
    }

    /**
     * 단지 주택타입 이름 비교 키. {@link #key}에 더해 LH·마이홈이 면적 숫자로 적은 형명("059.9100A", "84.9600B")을 공고문 표기
     * ("59A", "84B")와 같게 본다. 이름 형식만 다른 같은 집이 단지에 두 번 생기지 않게 하려는 것이다. 공급행 신청형 키(LH 공급행 식별자)는
     * {@link #key}를 그대로 쓴다.
     */
    public static String typeKey(String name) {
        return key(name).replaceFirst("^(\\d+)\\.\\d+(?=[a-z])", "$1");
    }

    /**
     * 같은 단지 주택타입 이름인지. 이름({@link #key})이 같으면 같고, 면적 숫자 형명만 맞춰야 같아지는 이름("059.9100A" ↔ "59A")은
     * 전용면적도 같을 때만 같다. 숫자 형명의 소수 자리(면적)를 버리므로 면적이 다른 집("059.9300A" 59.93㎡ ↔ "59A" 59.91㎡)을 잇지 않으려는 것이다.
     */
    public static boolean sameType(String left, BigDecimal leftArea, String right, BigDecimal rightArea) {
        String name = key(left);
        if (name.isEmpty()) {
            return false;
        }
        return name.equals(key(right)) || typeKey(left).equals(typeKey(right)) && HousingTypeAreas.sameArea(leftArea, rightArea);
    }

    /** 끝의 신청자 표기를 모두 뗀 형명("26A-1형[고령자(주거약자용)]" → "26A-1형"). 표기는 바꾸지 않는다. */
    public static String withoutApplicant(String typeName) {
        String result = typeName == null ? "" : typeName.strip();
        String stripped = withoutTrailingGroup(result);
        while (!stripped.equals(result)) {
            result = stripped;
            stripped = withoutTrailingGroup(result);
        }
        return result;
    }

    /**
     * 형명 맨 끝 신청자 표기 묶음의 안쪽("36형 일반(고령자)" → "고령자", "26A[청년]" → "청년", 이름 전체가 묶음인 "(주거약자)" → "주거약자").
     * 없으면 빈 문자열이다.
     */
    public static String applicant(String typeName) {
        String value = typeName == null ? "" : typeName.strip();
        int open = trailingGroupStart(value);
        return open < 0 ? "" : value.substring(open + 1, value.length() - 1).strip();
    }

    /** 표시명이 가리키는 신청형 키. 여러 표기를 '·'로 이은 이름("55A1 · 55C(주거약자)")은 표기마다 키를 둔다. */
    public static Set<String> keys(String displayName) {
        if (displayName == null) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        Arrays.stream(displayName.split("·")).map(HousingTypeNames::key).filter(key -> !key.isEmpty())
                .forEach(keys::add);
        return keys;
    }

    /**
     * 끝 묶음이 신청자가 아니라 주거약자용 주택을 가리키는 형명인지("26A(주거약자)", "37B(주거약자용)", "46A-H1(주거약자형)").
     * 공고문 공급표에서 이런 형명은 같은 형명("26A")과 다른 주택형이다. "26A-1형[고령자(주거약자용)]"처럼 다른 신청자와 함께 적은
     * 표기와 이름 전체가 묶음인 표기는 아니다.
     */
    public static boolean housingVariant(String typeName) {
        String value = typeName == null ? "" : typeName.strip();
        return trailingGroupStart(value) > 0 && HOUSING_VARIANT.matcher(applicant(value).replaceAll("\\s+", "")).matches();
    }

    /**
     * 공급행끼리 같은 신청형인지 비교하는 키. {@link #keys}와 같되 주거약자용 형명은 묶음을 붙여 같은 형명과 가른다
     * ("26A · 26A(주거약자)" → "26a", "26a(주거약자)"). 공급행 식별자에는 쓰지 않는다.
     */
    public static Set<String> applicationKeys(String displayName) {
        if (displayName == null) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        Arrays.stream(displayName.split("·")).map(HousingTypeNames::applicationKey).filter(key -> !key.isEmpty())
                .forEach(keys::add);
        return keys;
    }

    /** 형명 하나의 신청형 비교 키({@link #applicationKeys}). */
    public static String applicationKey(String typeName) {
        String key = key(typeName);
        return housingVariant(typeName) && !key.isEmpty()
                ? key + "(" + applicant(typeName).replaceAll("\\s+", "") + ")" : key;
    }

    // 끝의 () 또는 [] 묶음 하나를 뗀다. 이름 전체가 묶음이면 떼지 않는다.
    private static String withoutTrailingGroup(String value) {
        int open = trailingGroupStart(value);
        return open <= 0 ? value : value.substring(0, open).strip();
    }

    // 끝의 () 또는 [] 묶음(중첩 포함)이 여는 위치. 없거나 짝이 맞지 않으면 -1이다.
    private static int trailingGroupStart(String value) {
        if (value.isEmpty() || value.charAt(value.length() - 1) != ')' && value.charAt(value.length() - 1) != ']') {
            return -1;
        }
        int depth = 0;
        for (int at = value.length() - 1; at >= 0; at--) {
            char current = value.charAt(at);
            if (current == ')' || current == ']') {
                depth++;
            } else if ((current == '(' || current == '[') && --depth == 0) {
                return at;
            }
        }
        return -1;
    }
}
