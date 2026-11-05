package com.toadzip.backend.basiccomparison;

import static com.toadzip.backend.basiccomparison.ComparisonFixtures.*;
import static com.toadzip.backend.basiccomparison.domain.ComparisonContent.*;
import static com.toadzip.backend.basiccomparison.domain.ProfileField.*;

import static org.assertj.core.api.Assertions.*;

import com.toadzip.backend.basiccomparison.domain.*;
import com.toadzip.backend.basiccomparison.exception.InvalidComparisonException;
import com.toadzip.backend.basiccomparison.repository.ComparisonRegionMembership;
import com.toadzip.backend.region.repository.CsvRegionCodeResolver;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

class BasicComparisonTest {
    private static AnnotationConfigApplicationContext context;
    private static ComparisonRegionMembership regions;
    private final BasicComparisonEvaluator evaluator = new BasicComparisonEvaluator();

    @BeforeAll
    static void initialize() {
        context =
                new AnnotationConfigApplicationContext(
                        CsvRegionCodeResolver.class, ComparisonRegionMembership.class);
        regions = context.getBean(ComparisonRegionMembership.class);
    }

    @AfterAll
    static void close() {
        context.close();
    }

    private ComparisonResult evaluate(ComparisonContent content, ComparisonProfile profile) {
        profile.validate(LocalDate.of(2026, 11, 6));
        new ComparisonContentValidator(regions, STANDARDS.keySet()).validate(content, true);
        return evaluator.evaluate(content, profile, STANDARDS, regions);
    }

    @Test
    void 복합_조건의_미충족에도_실제_하위_비교값을_근거로_남긴다() {
        var rule =
                new BasicRule(
                        BasicRule.Operator.ALL,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(income(1, 50), equal(FAMILY_OWNS_HOUSE, "false")));
        var content =
                withPaths(
                        sacheon(),
                        List.of(
                                new ComparisonPath(
                                        "ordinary",
                                        "소득과 주택 기본 조건",
                                        PathKind.GENERAL,
                                        List.of(check("combined", rule)))));
        var result = evaluate(content, with(b(), HOUSEHOLD_MONTHLY_INCOME, 4000000L));
        var reading = result.options().getFirst().checks().getFirst().reading();
        assertThat(reading.status()).isEqualTo(RuleReading.Status.NOT_SATISFIED);
        assertThat(reading.comparedValues().get("children"))
                .asList()
                .hasSize(2)
                .first()
                .isInstanceOfSatisfying(
                        RuleReading.class,
                        income ->
                                assertThat(income.comparedValues())
                                        .containsEntry("providedValue", 4000000L)
                                        .containsEntry(
                                                "comparisonLimit", new BigDecimal("3519762")));
    }

    @Test
    void 세대_합산_소득으로_일반_기본조건을_비교하고_미수집_신분과_이력은_안내한다() {
        var result = evaluate(sacheon(), b());
        var option = result.options().getFirst();
        assertThat(option.status()).isEqualTo(ComparisonResult.BasicStatus.MATCHED);
        assertThat(option.coverage()).isEqualTo(PathKind.GENERAL);
        assertThat(result.generalBasicMatched()).isTrue();
        var income =
                option.checks().stream()
                        .filter(check -> check.id().equals("income"))
                        .findFirst()
                        .orElseThrow();
        assertThat(income.reading().comparedValues())
                .containsEntry("providedValue", 3000000L)
                .containsEntry("comparisonLimit", new BigDecimal("3519762"));
        assertThat(result.directChecks())
                .extracting(Guidance::kind)
                .contains(GuidanceKind.REQUIRED_CHECK, GuidanceKind.OTHER_ROUTE);
        assertThat(option.missingInputs()).isEmpty();
        assertThat(option.message()).doesNotContain("지원할 수", "신청불가", "1순위");
    }

    @Test
    void 한도는_충족하고_1원_초과는_비교한_일반경로만_미충족이다() {
        var atLimit = evaluate(sacheon(), with(b(), HOUSEHOLD_MONTHLY_INCOME, 3519762L));
        var over = evaluate(sacheon(), with(b(), HOUSEHOLD_MONTHLY_INCOME, 3519763L));
        assertThat(atLimit.options().getFirst().status())
                .isEqualTo(ComparisonResult.BasicStatus.MATCHED);
        assertThat(over.options().getFirst().status())
                .isEqualTo(ComparisonResult.BasicStatus.NOT_MATCHED);
        assertThat(over.options().getFirst().checks())
                .anySatisfy(
                        check -> {
                            assertThat(check.id()).isEqualTo("income");
                            assertThat(check.reading().status())
                                    .isEqualTo(RuleReading.Status.NOT_SATISFIED);
                            assertThat(check.evidenceIds()).isNotEmpty();
                        });
        assertThat(over.directChecks())
                .anyMatch(guidance -> guidance.kind() == GuidanceKind.OTHER_ROUTE);
        assertThat(over.options().getFirst().message()).contains("이 경로", "다른 신청 경로");
    }

    @Test
    void 가족정보를_생략해도_세대소득은_비교하며_선택입력을_강요하지_않는다() {
        var result = evaluate(sacheon(), skipped());
        var option = result.options().getFirst();
        assertThat(option.status()).isEqualTo(ComparisonResult.BasicStatus.INPUT_NEEDED);
        assertThat(option.missingInputs())
                .extracting(ComparisonResult.MissingInput::field)
                .contains(FAMILY_TOTAL_ASSETS, FAMILY_OWNS_HOUSE, FAMILY_CAR_VALUE)
                .doesNotContain(HOUSEHOLD_MONTHLY_INCOME);
        assertThat(option.missingInputs()).allMatch(input -> input.optional() && input.skipped());
        assertThat(option.checks())
                .anySatisfy(
                        check -> {
                            assertThat(check.id()).isEqualTo("income");
                            assertThat(check.reading().status())
                                    .isEqualTo(RuleReading.Status.SATISFIED);
                        });
        assertThat(result.generalInputNeeded()).isTrue();
    }

    @Test
    void D11은_공통_조건이_맞아도_별도_자격이_필수이며_일반_충족_공고로_세지_않는다() {
        var user = with(b(), RESIDENCE_REGION, "11110");
        var result = evaluate(shPermanent(), user);
        assertThat(result.options().getFirst().status())
                .isEqualTo(ComparisonResult.BasicStatus.MATCHED);
        assertThat(result.options().getFirst().coverage()).isEqualTo(PathKind.COMMON_ONLY);
        assertThat(result.headline()).contains("별도의 신청자격");
        assertThat(result.requiresSeparateQualification()).isTrue();
        assertThat(result.generalBasicMatched()).isFalse();
        assertThat(result.generalInputNeeded()).isFalse();
        assertThat(result.options().getFirst().checks())
                .noneMatch(check -> check.id().equals("income"));
        assertThat(result.directChecks())
                .anyMatch(
                        guidance ->
                                guidance.kind() == GuidanceKind.REQUIRED_QUALIFICATION
                                        && guidance.items().size() == 9);
    }

    @Test
    void 한부모_가족형태를_법정_지원대상_한부모로_승격하지_않는다() {
        var user = singleParent();
        var result = evaluate(shPermanent(), user);
        assertThat(result.requiresSeparateQualification()).isTrue();
        assertThat(result.directChecks())
                .anyMatch(guidance -> guidance.items().contains("지원대상 한부모가족"));
        assertThat(result.options().getFirst().missingInputs()).isEmpty();
        assertThatThrownBy(() -> ProfileField.valueOf("SUPPORTED_SINGLE_PARENT_FAMILY"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void D03의_사전선정_제한은_우선기회가_아니며_개인공고를_제외하지_않는다() {
        var result = evaluate(anyang(), b());
        assertThat(result.scope()).isEqualTo(ComparisonResult.Scope.INDIVIDUAL);
        assertThat(result.directChecks())
                .anySatisfy(
                        guidance -> {
                            assertThat(guidance.kind())
                                    .isEqualTo(GuidanceKind.REQUIRED_QUALIFICATION);
                            assertThat(guidance.title()).contains("이미 선정", "일반인은 청약할 수 없");
                        });
        assertThat(result.options().getFirst().status())
                .isNotEqualTo(ComparisonResult.BasicStatus.NOT_MATCHED);
    }

    @Test
    void 일반_비교와_우선공급_기회는_독립적으로_제공한다() {
        var result = evaluate(dasan(), b());
        assertThat(result.generalBasicMatched()).isTrue();
        assertThat(result.requiresSeparateQualification()).isFalse();
        assertThat(result.directChecks())
                .anyMatch(guidance -> guidance.kind() == GuidanceKind.PRIORITY_OPPORTUNITY);
        assertThat(result.options().getFirst().checks())
                .anySatisfy(
                        check -> {
                            assertThat(check.id()).isEqualTo("income");
                            assertThat(check.reading().comparedValues())
                                    .containsEntry("comparisonLimit", new BigDecimal("6718867"));
                        });
    }

    @Test
    void 기업_신청만_제외하고_제목의_산단이나_특수경로로_개인공고를_제외하지_않는다() {
        assertThat(evaluate(company(), b()).scope()).isEqualTo(ComparisonResult.Scope.OUT_OF_SCOPE);
        assertThat(evaluate(company(), b()).options()).isEmpty();
        for (String document : List.of("D06", "D08", "D09", "D17")) {
            var result = evaluate(classification(document, "산업단지 일자리연계형 일반 개인 모집"), b());
            assertThat(result.scope()).isEqualTo(ComparisonResult.Scope.INDIVIDUAL);
            assertThat(result.options()).hasSize(1);
            if (document.equals("D08") || document.equals("D09")) {
                assertThat(result.directChecks())
                        .anyMatch(guidance -> guidance.kind() == GuidanceKind.OTHER_ROUTE);
                continue;
            }
            assertThat(result.directChecks()).isEmpty();
        }
    }

    @Test
    void 비교_항목이_없으면_빈_조건을_충족으로_취급하지_않는다() {
        var result = evaluate(classification("D08", "분류만 확인한 개인 공고"), b());
        assertThat(result.options().getFirst().status())
                .isEqualTo(ComparisonResult.BasicStatus.NOT_EVALUATED);
        assertThat(result.generalBasicMatched()).isFalse();
    }

    @Test
    void 자녀_가산의_적용을_확정하지_못하면_기본한도_초과를_미충족으로_만들지_않는다() {
        var user =
                with(
                        with(singleParent(), RESIDENCE_REGION, "48240"),
                        HOUSEHOLD_MONTHLY_INCOME,
                        4000000L);
        var result = evaluate(sacheon(), user);
        assertThat(result.options().getFirst().status())
                .isEqualTo(ComparisonResult.BasicStatus.PARTIALLY_COMPARED);
        assertThat(result.options().getFirst().checks())
                .anySatisfy(
                        check -> {
                            assertThat(check.id()).isEqualTo("income");
                            assertThat(check.reading().status())
                                    .isEqualTo(RuleReading.Status.DIRECT_CHECK_REQUIRED);
                            assertThat(check.directCheckId()).isEqualTo("scope");
                        });
    }

    @Test
    void 같은_차목이라도_사천과_아산의_거주_조건을_섞지_않는다() {
        assertThat(evaluate(asan(), b()).options().getFirst().status())
                .isEqualTo(ComparisonResult.BasicStatus.NOT_MATCHED);
        assertThat(evaluate(asan(), with(b(), RESIDENCE_REGION, "44200")).generalBasicMatched())
                .isTrue();
    }

    @Test
    void 미확정_원문과_없는_근거_및_잘못된_필수자격_분류는_공개할_수_없다() {
        var content = shPermanent();
        var general =
                new ComparisonPath(
                        "common",
                        "잘못 만든 일반 경로",
                        PathKind.GENERAL,
                        content.paths().getFirst().checks());
        assertThatThrownBy(() -> evaluate(withPaths(content, List.of(general)), b()))
                .isInstanceOf(InvalidComparisonException.class)
                .hasMessageContaining("일반 경로");
        assertThatThrownBy(
                        () ->
                                evaluate(
                                        withGuidances(
                                                content,
                                                List.of(
                                                        guidance(
                                                                "scope",
                                                                GuidanceKind.REQUIRED_CHECK,
                                                                "세대 확인"))),
                                        b()))
                .isInstanceOf(InvalidComparisonException.class)
                .hasMessageContaining("필수 별도 자격");
    }

    @Test
    void 원문_URL과_검토완료_상태가_유효해야_공개할_수_있다() {
        var original = sacheon();
        var metadata = original.metadata();
        var reviewing =
                new Metadata(
                        metadata.referenceDate(),
                        metadata.applicantKind(),
                        metadata.accessMode(),
                        ReviewStatus.REVIEWING,
                        metadata.classificationEvidenceId(),
                        metadata.scopeNote());
        var draft =
                new ComparisonContent(
                        reviewing,
                        original.documents(),
                        original.evidences(),
                        original.supplyUnits(),
                        original.paths(),
                        original.options(),
                        original.guidances(),
                        original.quantities(),
                        original.rentOffers(),
                        original.schedules());
        var validator = new ComparisonContentValidator(regions, STANDARDS.keySet());
        assertThatCode(() -> validator.validate(draft, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(draft, true))
                .isInstanceOf(InvalidComparisonException.class)
                .hasMessageContaining("검토 완료");
        var badDocument = new SourceDocument("D05", "공고.pdf", "relative/path.pdf", 60, null);
        var broken =
                new ComparisonContent(
                        metadata,
                        List.of(badDocument),
                        original.evidences(),
                        original.supplyUnits(),
                        original.paths(),
                        original.options(),
                        original.guidances(),
                        original.quantities(),
                        original.rentOffers(),
                        original.schedules());
        assertThatThrownBy(() -> validator.validate(broken, true))
                .isInstanceOf(InvalidComparisonException.class)
                .hasMessageContaining("URL");
    }
}
