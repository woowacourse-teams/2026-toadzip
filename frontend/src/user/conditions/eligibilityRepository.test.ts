import { describe, expect, it, vi } from 'vitest'
import { FIELD_COPY } from './conditionFieldCopy'
import type { ProfileField } from './conditionsModel'
import { createEligibilityRepository } from './eligibilityRepository'

const ref = { announcementId: '5', title: 'D05', rentalType: null, applicationStatus: 'APPLYING', targets: [] }
const content = {
  metadata: { referenceDate: '2026-10-16' }, documents: [], evidences: [], qualifications: [], guidances: [],
  commonChecks: [], paths: [], supplyUnits: [],
}
const current = {
  requestedAnnouncementId: 5, announcementId: 5, availability: 'AVAILABLE', freshness: 'CURRENT',
  profileVersion: 2, evaluatedProfileVersion: 2, evaluatedAt: '2026-11-09T00:00:00Z', staleReasons: [],
  content, eligibleOptionIdsInRegion: ['ordinary'], selectedRegion: null,
  result: { status: 'ELIGIBLE', referenceDate: '2026-10-16', commonChecks: [],
    paths: [{ pathId: 'ordinary', checks: [] }], options: [{ optionId: 'ordinary', supplyUnitId: 'unit',
      unitLabel: '사천벌리1 26형', pathId: 'ordinary', pathLabel: '일반입주자 신청자격', regionCodes: ['48240'],
      recruitmentStatus: 'RECRUITING', status: 'ELIGIBLE', message: '지원할 수 있어요', missingInputMessage: null,
      missingInputs: [], requiredQualificationIds: [], directCheckGuidanceIds: [], exclusionGuidanceIds: [] }] },
}
const response = (data: unknown) => new Response(JSON.stringify({ data }), { status: 200 })

describe('실제 신청자격 API 연결', () => {
  it('최신 eligibility API의 실제 모집 선택지를 사용한다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response(current))
    const view = await createEligibilityRepository(fetcher).loadComparison(ref)
    expect(String(fetcher.mock.calls[0][0])).toMatch(/\/announcements\/5\/eligibility$/)
    expect(view.types[0]).toMatchObject({ key: 'ordinary', status: 'ELIGIBLE', units: '사천벌리1 26형' })
  })
  it('처리 중 응답을 미구조화나 입력 부족으로 바꾸지 않는다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response({ ...current, freshness: 'RECHECKING', result: null, eligibleOptionIdsInRegion: [] }))
    const view = await createEligibilityRepository(fetcher).loadComparison(ref)
    expect(view).toMatchObject({ availability: 'AVAILABLE', freshness: 'RECHECKING', types: [] })
  })
})

it('지역에 맞는 선택지가 없으면 공고 전체가 ELIGIBLE이어도 지역 필터에서 제외한다', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response({ ...current, eligibleOptionIdsInRegion: [] }))
  const result = await createEligibilityRepository(fetcher).loadListResults([{ ...ref, region: '41465' }])
  expect(String(fetcher.mock.calls[0][0])).toMatch(/eligibility\?region=41465$/)
  expect(result.get('5')).toMatchObject({ best: { status: 'ELIGIBLE' }, eligibleInRegion: false })
})

it('수치 미충족의 기준·방향·입력값을 보존하며 다른 정보 부족과 구분한다', async () => {
  const checks = [
    { id: 'assets', label: '총자산', evidenceIds: [], reading: { status: 'NOT_SATISFIED', missingInputs: [], comparedValues: { field: 'FAMILY_TOTAL_ASSETS', providedValue: 300000000, comparisonLimit: 245000000, unit: 'KRW' } } },
    { id: 'income', label: '세대 소득', evidenceIds: [], reading: { status: 'INPUT_NEEDED', missingInputs: ['HOUSEHOLD_MONTHLY_INCOME'], comparedValues: {} } },
  ]
  const data = { ...current, result: { ...current.result, status: 'INELIGIBLE', paths: [{ pathId: 'ordinary', checks }], options: [{ ...current.result.options[0], status: 'INELIGIBLE', missingInputs: ['HOUSEHOLD_MONTHLY_INCOME'] }] } }
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response(data))
  const view = await createEligibilityRepository(fetcher).loadComparison(ref)
  expect(view.types[0].status).toBe('INELIGIBLE')
  expect(view.types[0].checks[0]).toMatchObject({ mine: '3억 원', criterion: '2억 4,500만 원 이하', status: 'NOT_SATISFIED' })
  expect(view.types[0].checks[1]).toMatchObject({ status: 'INPUT_NEEDED', fields: ['HOUSEHOLD_MONTHLY_INCOME'] })
})

it('법정 필수 자격과 신청 제한 안내를 별도로 보존한다', async () => {
  const data = { ...current, content: { ...content,
    qualifications: [{ id: 'single-parent', exactName: '지원대상 한부모가족', conditionsAsWritten: '해당 법정 자격 확인', evidenceIds: [] }],
    guidances: [{ id: 'history', kind: 'APPLICATION_EXCLUSION', title: '불법전대 제한', body: '제한 이력 확인', items: [], evidenceIds: [] }] },
    result: { ...current.result, status: 'UNDETERMINED', options: [{ ...current.result.options[0], status: 'UNDETERMINED', requiredQualificationIds: ['single-parent'], exclusionGuidanceIds: ['history'] }] } }
  const view = await createEligibilityRepository(vi.fn<typeof fetch>().mockResolvedValue(response(data))).loadComparison(ref)
  expect(view.types[0].missingInputs).toEqual([])
  expect(view.types[0].qualifications?.[0].title).toBe('지원대상 한부모가족')
  expect(view.types[0].exclusions?.[0].title).toBe('불법전대 제한')
})

it('판정을 막지 않는 신청 전 확인 안내를 지원 가능 선택지에도 보인다', async () => {
  const data = { ...current, content: { ...content,
    guidances: [{ id: 'list-path-1', kind: 'REQUIRED_CHECK', title: '공고문에서 확인할 자격', body: '공고문 원문에서 확인',
      items: ['입주 전까지 주택청약종합저축 가입'], evidenceIds: [] }] },
    result: { ...current.result, options: [{ ...current.result.options[0], reminderGuidanceIds: ['list-path-1'] }] } }
  const view = await createEligibilityRepository(vi.fn<typeof fetch>().mockResolvedValue(response(data))).loadComparison(ref)
  expect(view.types[0].status).toBe('ELIGIBLE')
  expect(view.types[0].guidances).toEqual([expect.objectContaining({ kind: 'REQUIRED_CHECK', title: '공고문에서 확인할 자격',
    body: '공고문 원문에서 확인\n입주 전까지 주택청약종합저축 가입' })])
})

it('가산 대상처럼 기준이 그대로 적용되지 않은 조건은 기본 기준과 확인 안내를 함께 알린다', async () => {
  const checks = [{ id: 'income', label: '세대 소득', evidenceIds: [],
    reading: { status: 'DIRECT_CHECK_REQUIRED', missingInputs: [], comparedValues: { reason: 'NOT_APPLIED',
      field: 'HOUSEHOLD_MONTHLY_INCOME', providedValue: 4000000, comparisonLimit: 3519762, operator: 'LTE', unit: 'KRW_PER_MONTH' } } }]
  const data = { ...current, content: { ...content, paths: [{ id: 'ordinary', checks: [{ id: 'income',
    rule: { operator: 'INCOME_CEILING', field: 'HOUSEHOLD_MONTHLY_INCOME' } }] }] },
    result: { ...current.result, paths: [{ pathId: 'ordinary', checks }] } }
  const view = await createEligibilityRepository(vi.fn<typeof fetch>().mockResolvedValue(response(data))).loadComparison(ref)
  expect(view.types[0].status).toBe('ELIGIBLE')
  expect(view.types[0].checks.at(-1)).toMatchObject({ status: 'DIRECT_CHECK_REQUIRED',
    criterion: expect.stringMatching(/^월 .+ 이하\(기본\) · 내 정보 월 400만 원 · 가족 상황에 따라 다른 기준이 적용돼요/) })
})

/** 공고 하나에 공통 조건 하나. rule은 공개 내용의 판정 규칙, reading은 그 규칙을 내 정보로 읽은 결과다. */
async function commonCheck(label: string, rule: unknown, reading: unknown) {
  const data = { ...current, content: { ...content, commonChecks: [{ id: 'c1', label, rule }] },
    result: { ...current.result, commonChecks: [{ id: 'c1', label, evidenceIds: [], reading }] } }
  const view = await createEligibilityRepository(vi.fn<typeof fetch>().mockResolvedValue(response(data))).loadComparison(ref)
  return view.types[0].checks[0]
}

it('공고문 문장 그대로인 조건 이름 대신 무엇을 비교하는지로 부르고, 예/아니요 기준과 내 값을 말로 보인다', async () => {
  const house = await commonCheck('세대구성원 전원이 무주택인 세대의 구성원으로서 공고일 현재 주택을 소유하지 않은 자',
    { operator: 'EQUALS', field: 'FAMILY_OWNS_HOUSE', expected: 'false', children: [] },
    { status: 'SATISFIED', missingInputs: [], comparedValues: { field: 'FAMILY_OWNS_HOUSE', providedValue: false, expected: 'false', operator: 'EQ' } })
  expect(house).toMatchObject({ label: '무주택 세대', criterion: '세대원 모두 무주택', mine: '가족 모두 없음' })

  const student = await commonCheck('대학생', { operator: 'EQUALS', field: 'IS_UNIVERSITY_STUDENT', expected: 'true', children: [] },
    { status: 'NOT_SATISFIED', missingInputs: [], comparedValues: { field: 'IS_UNIVERSITY_STUDENT', providedValue: false, expected: 'true', operator: 'EQ' } })
  expect(student).toMatchObject({ criterion: '재학 중인 대학생', mine: '대학생 아님' })

  // 서로 다른 항목을 모두 갖춰야 하는 묶음은 항목 이름을 이어 부르고, 기준도 한 줄로 잇는다.
  const mixed = await commonCheck('청년 요건', { operator: 'ALL', children: [{ operator: 'AGE_RANGE', field: 'BIRTH_DATE', minimum: 19, maximum: 39, children: [] },
    { operator: 'EQUALS', field: 'IS_UNIVERSITY_STUDENT', expected: 'true', children: [] }] },
  { status: 'INPUT_NEEDED', missingInputs: ['BIRTH_DATE'], comparedValues: { children: [
    { status: 'INPUT_NEEDED', missingInputs: ['BIRTH_DATE'], comparedValues: {} },
    { status: 'SATISFIED', missingInputs: [], comparedValues: { field: 'IS_UNIVERSITY_STUDENT', providedValue: true, expected: 'true' } }] } })
  expect(mixed).toMatchObject({ label: '나이 · 대학생', criterion: '만 19~39세 · 재학 중인 대학생', mine: null, fields: ['BIRTH_DATE'] })
  expect(mixed).not.toHaveProperty('children')
})

// 공고 상세는 모든 조건을 같은 한 줄(기호 · 이름과 기준 · 내 값이나 행동)로 보인다. 묶음도 하위 줄로 펼치지 않는다.
it('어느 하나만 맞으면 되는 묶음은 한 줄로 요약하고, 빠진 항목을 한 번에 입력하게 한다', async () => {
  const rule = { operator: 'ANY', children: [leafRule('MONTHS_SINCE', 'MARRIAGE_EVENT', { maximum: 84 }),
    leafRule('AGE_RANGE', 'YOUNGEST_CHILD', { minimum: 0, maximum: 6 })] }
  const check = await commonCheck('혼인기간이 7년 이내일 것 또는 6세 이하 자녀(태아포함)를 둔 자', rule,
    { status: 'INPUT_NEEDED', missingInputs: ['MARRIAGE_EVENT', 'YOUNGEST_CHILD'], comparedValues: { children: [
      { status: 'INPUT_NEEDED', missingInputs: ['MARRIAGE_EVENT'], comparedValues: {} },
      { status: 'INPUT_NEEDED', missingInputs: ['YOUNGEST_CHILD'], comparedValues: {} }] } })

  expect(check).toMatchObject({ label: '혼인기간 또는 자녀 나이', status: 'INPUT_NEEDED', mine: null,
    criterion: '혼인 7년 이내 또는 막내 자녀 만 6세 이하', fields: ['MARRIAGE_EVENT', 'YOUNGEST_CHILD'], missingReason: 'UNANSWERED' })
  expect(check).not.toHaveProperty('children')

  // 충족하면 맞은 항목의 내 값을 보인다.
  const met = await commonCheck('공고문 문장', rule, { status: 'SATISFIED', missingInputs: [], comparedValues: { children: [
    { status: 'NOT_SATISFIED', missingInputs: [], comparedValues: { field: 'MARRIAGE_EVENT', providedValue: { kind: 'REGISTERED', date: '2015-01-01' } } },
    { status: 'SATISFIED', missingInputs: [], comparedValues: { field: 'YOUNGEST_CHILD', providedValue: '2023-01-01', comparedValue: 3, unit: 'YEARS' } }] } })
  expect(met).toMatchObject({ status: 'SATISFIED', mine: '만 3세' })

  // 맞지 않으면 고칠 수 있게 내 값을 모두 보이고, 묶음 안의 모든 항목을 고치게 한다.
  const unmet = await commonCheck('공고문 문장', rule, { status: 'NOT_SATISFIED', missingInputs: [], comparedValues: { children: [
    { status: 'NOT_SATISFIED', missingInputs: [], comparedValues: { field: 'MARRIAGE_EVENT', providedValue: { kind: 'REGISTERED', date: '2015-01-01' } } },
    { status: 'NOT_SATISFIED', missingInputs: [], comparedValues: { field: 'YOUNGEST_CHILD', providedValue: '2015-01-01', comparedValue: 11, unit: 'YEARS' } }] } })
  expect(unmet).toMatchObject({ status: 'NOT_SATISFIED', mine: '2015-01-01 · 만 11세', fields: ['MARRIAGE_EVENT', 'YOUNGEST_CHILD'] })
})

it('묶음 안의 묶음은 괄호로 감싸 기준을 한 줄로 잇고, 이름은 공고의 조건 이름을 쓴다', async () => {
  const rule = { operator: 'ANY', children: [
    { operator: 'ALL', children: [leafRule('EQUALS', 'IS_UNIVERSITY_STUDENT', { expected: 'true' }), leafRule('AGE_RANGE', 'BIRTH_DATE', { minimum: 19, maximum: 39 })] },
    leafRule('EQUALS', 'IS_JOB_SEEKER', { expected: 'true' })] }
  const check = await commonCheck('청년 신분', rule, { status: 'INPUT_NEEDED', missingInputs: ['IS_JOB_SEEKER'], comparedValues: { children: [
    { status: 'NOT_SATISFIED', missingInputs: [], comparedValues: { children: [
      { status: 'NOT_SATISFIED', missingInputs: [], comparedValues: { field: 'IS_UNIVERSITY_STUDENT', providedValue: false, expected: 'true' } },
      { status: 'SATISFIED', missingInputs: [], comparedValues: { field: 'BIRTH_DATE', providedValue: '1996-03-02', comparedValue: 30, unit: 'YEARS' } }] } },
    { status: 'INPUT_NEEDED', missingInputs: ['IS_JOB_SEEKER'], comparedValues: {} }] } })

  expect(check).toMatchObject({ label: '청년 신분', criterion: '(재학 중인 대학생 · 만 19~39세) 또는 취업준비생', fields: ['IS_JOB_SEEKER'] })
  expect(check).not.toHaveProperty('children')
})

const leafRule = (operator: string, field: string, extra: object = {}) => ({ operator, field, children: [], ...extra })
const satisfied = (comparedValues: object) => ({ status: 'SATISFIED', missingInputs: [], comparedValues })

// 화면이 '기준 '을 붙여 읽으므로 기준은 명사구로, 내 값은 문장형 값으로 만든다.
it.each([
  ['고령 나이', leafRule('AGE_RANGE', 'BIRTH_DATE', { minimum: 65, maximum: 150 }),
    satisfied({ field: 'BIRTH_DATE', providedValue: '1955-03-01', comparedValue: 71, unit: 'YEARS' }), '만 65세 이상', '만 71세'],
  ['막내 자녀 나이', leafRule('AGE_RANGE', 'YOUNGEST_CHILD', { minimum: 0, maximum: 6 }),
    satisfied({ field: 'YOUNGEST_CHILD', providedValue: '2022-01-01', comparedValue: 4, unit: 'YEARS' }), '막내 자녀 만 6세 이하', '만 4세'],
  ['거주지역', leafRule('REGION_IN', 'RESIDENCE_REGION', { regions: ['11'] }),
    satisfied({ field: 'RESIDENCE_REGION', providedValue: '11', requiredRegions: ['11'] }), '서울 거주', '서울'],
  ['혼인기간', leafRule('MONTHS_SINCE', 'MARRIAGE_EVENT', { maximum: 84 }),
    satisfied({ field: 'MARRIAGE_EVENT' }), '혼인 7년 이내', null],
  ['가족 수', leafRule('NUMBER_RANGE', 'REGISTERED_FAMILY_COUNT', { minimum: 3, maximum: 1800 }),
    satisfied({ field: 'REGISTERED_FAMILY_COUNT', providedValue: 4 }), '3명 이상', '4명'],
  ['자동차 없음', leafRule('MONEY_CEILING', 'FAMILY_CAR_VALUE', { maximum: 45_420_000 }),
    satisfied({ field: 'FAMILY_CAR_VALUE', vehicleOwned: false }), '차량 4,542만 원 이하', '자동차 없음'],
])('%s의 기준과 내 값을 짧은 말로 보인다', async (_, rule, reading, criterion, mine) => {
  expect(await commonCheck('공고문 문장', rule, reading)).toMatchObject({ criterion, mine })
})

// 새 예/아니요 항목이 문구 표에서 빠지면 '예/아니요'로 되돌아가므로, 모든 예/아니요 항목을 돌려 막는다.
it('예/아니요 항목의 기준과 내 값은 예·아니요로 보이지 않는다', async () => {
  const fields = (Object.keys(FIELD_COPY) as ProfileField[]).filter((name) => FIELD_COPY[name].kind === 'bool')
  for (const name of fields) {
    for (const value of [true, false]) {
      const check = await commonCheck('공고문 문장', leafRule('EQUALS', name, { expected: String(value) }),
        satisfied({ field: name, providedValue: value, expected: String(value) }))
      expect([check.criterion, check.mine], `${name}=${value}`).not.toContain('예')
      expect([check.criterion, check.mine], `${name}=${value}`).not.toContain('아니요')
    }
  }
})

it('출산자녀 가산 기준은 중첩 줄 대신 기본 기준 한 줄에 자녀 수별 기준을 덧붙인다', async () => {
  const money = (maximum: number) => ({ operator: 'MONEY_CEILING', field: 'FAMILY_CAR_VALUE', maximum, children: [] })
  const tier = (minimum: number, maximum: number, raised: number) => ({ operator: 'ALL', children: [
    { operator: 'AGE_RANGE', field: 'YOUNGEST_CHILD', minimum: 0, maximum: 3, children: [] },
    { operator: 'NUMBER_RANGE', field: 'CHILDREN_COUNT_INCLUDING_FETUS', minimum, maximum, children: [] }, money(raised)] })
  const rule = { operator: 'ANY', children: [money(45_420_000), tier(1, 1, 49_970_000), tier(2, 1800, 54_510_000)] }
  const over = { status: 'NOT_SATISFIED', missingInputs: [], comparedValues: { field: 'FAMILY_CAR_VALUE', providedValue: 48_000_000, comparisonLimit: 45_420_000, unit: 'KRW' } }
  const check = await commonCheck('세대구성원 전원이 보유하고 있는 개별 자동차가액 ( 4,542 )만원 이하', rule,
    { status: 'SATISFIED', missingInputs: [], comparedValues: { children: [over, { status: 'SATISFIED', missingInputs: [], comparedValues: { children: [] } },
      { status: 'NOT_SATISFIED', missingInputs: [], comparedValues: { children: [] } }] } })

  expect(check).toMatchObject({ label: '자동차가액', status: 'SATISFIED', mine: '4,800만 원',
    criterion: '4,542만 원 이하 · 출산자녀 1명 4,997만 원 · 2명 이상 5,451만 원' })

  // 계산하지 못한 가산(맞벌이 등)은 숫자 없이 더 높을 수 있다고만 덧붙인다.
  const lenient = await commonCheck('소득', { operator: 'ANY', children: [money(45_420_000), { operator: 'ALL', children: [
    { operator: 'EQUALS', field: 'FAMILY_TYPE', expected: 'COUPLE_OR_ENGAGED', children: [] }, { operator: 'EQUALS', field: 'IS_DUAL_INCOME', expected: 'true', children: [] }] }] },
  { status: 'SATISFIED', missingInputs: [], comparedValues: { children: [{ ...over, status: 'SATISFIED', comparedValues: { ...over.comparedValues, providedValue: 10_000_000 } },
    { status: 'NOT_SATISFIED', missingInputs: [], comparedValues: { children: [] } }] } })
  expect(lenient.criterion).toBe('4,542만 원 이하 · 가족 상황에 따라 기준이 더 높을 수 있어요')
})

it('가산이 아닌 선택 조건(한부모 또는 부부)은 가산 요약 대신 선택지를 모두 기준에 보인다', async () => {
  const rule = { operator: 'ANY', children: [{ operator: 'EQUALS', field: 'FAMILY_TYPE', expected: 'SINGLE_PARENT', children: [] },
    { operator: 'EQUALS', field: 'FAMILY_TYPE', expected: 'COUPLE_OR_ENGAGED', children: [] }] }
  const check = await commonCheck('한부모 또는 부부', rule, { status: 'SATISFIED', missingInputs: [], comparedValues: { children: [
    { status: 'NOT_SATISFIED', missingInputs: [], comparedValues: { field: 'FAMILY_TYPE', providedValue: 'COUPLE_OR_ENGAGED', expected: 'SINGLE_PARENT' } },
    { status: 'SATISFIED', missingInputs: [], comparedValues: { field: 'FAMILY_TYPE', providedValue: 'COUPLE_OR_ENGAGED', expected: 'COUPLE_OR_ENGAGED' } }] } })

  expect(check).toMatchObject({ label: '가족 형태', criterion: '한부모 또는 부부·예비부부', mine: '부부·예비부부' })
  expect(check.criterion).not.toContain('기준이 더 높을 수 있어요')
})

it('소득 가산 기준은 가구원 수 가산이 붙은 실제 금액으로, 소득이 없으면 기본보다 높은 %p로 보인다', async () => {
  const limit = { standardTableId: 1, sizeExtraPercent: { 2: 10, 1: 20 }, familyCountField: 'REGISTERED_FAMILY_COUNT' }
  const income = (percent: number) => ({ operator: 'INCOME_CEILING', field: 'HOUSEHOLD_MONTHLY_INCOME', incomeLimit: { ...limit, percent }, children: [] })
  const tier = (minimum: number, maximum: number, percent: number) => ({ operator: 'ALL', children: [
    { operator: 'AGE_RANGE', field: 'YOUNGEST_CHILD', minimum: 0, maximum: 3, children: [] },
    { operator: 'NUMBER_RANGE', field: 'CHILDREN_COUNT_INCLUDING_FETUS', minimum, maximum, children: [] }, income(percent)] })
  const rule = { operator: 'ANY', children: [income(70), tier(1, 1, 80), tier(2, 1800, 90)] }
  const basis = { familyCount: 2, standardYear: 2025, standardKind: 'URBAN_WORKER_MONTHLY_INCOME' }
  const compared = (comparisonLimit: number, percent: number) => ({ field: 'HOUSEHOLD_MONTHLY_INCOME', providedValue: 4_000_000, comparisonLimit, unit: 'KRW_PER_MONTH', basis: { ...basis, percent } })
  const alternative = (comparisonLimit: number, percent: number) => ({ status: 'NOT_SATISFIED', missingInputs: [], comparedValues: { children: [
    { status: 'NOT_SATISFIED', missingInputs: [], comparedValues: { field: 'YOUNGEST_CHILD', inputState: 'NOT_APPLICABLE' } },
    { status: 'NOT_SATISFIED', missingInputs: [], comparedValues: { field: 'CHILDREN_COUNT_INCLUDING_FETUS', providedValue: 0 } },
    { status: 'SATISFIED', missingInputs: [], comparedValues: compared(comparisonLimit, percent) }] } })
  const check = await commonCheck('2025년 도시근로자 가구원수별 가구당 월평균 소득(원) 70%', rule,
    { status: 'SATISFIED', missingInputs: [], comparedValues: { children: [{ status: 'SATISFIED', missingInputs: [], comparedValues: compared(4_693_016, 80) },
      alternative(5_279_643, 90), alternative(5_866_270, 100)] } })

  // 기본 비율(가구원 수 가산 뒤 80%)과 출산자녀 1명 비율(80%)이 같아 보이지 않게 금액으로 보이고, 기준표 설명은 기본 금액 바로 뒤에 둔다.
  expect(check).toMatchObject({ label: '세대 소득', basis: null,
    criterion: '월 469만 3,016원 이하 (2025년 · 도시근로자 월평균 소득 · 2인 가구 · 80%) · 출산자녀 1명 월 527만 9,643원 · 2명 이상 월 586만 6,270원' })

  const unanswered = await commonCheck('소득', rule, { status: 'INPUT_NEEDED', missingInputs: ['HOUSEHOLD_MONTHLY_INCOME'], comparedValues: { children: [
    { status: 'INPUT_NEEDED', missingInputs: ['HOUSEHOLD_MONTHLY_INCOME'], comparedValues: {} },
    { status: 'INPUT_NEEDED', missingInputs: ['HOUSEHOLD_MONTHLY_INCOME'], comparedValues: { children: [] } },
    { status: 'INPUT_NEEDED', missingInputs: ['HOUSEHOLD_MONTHLY_INCOME'], comparedValues: { children: [] } }] } })
  expect(unanswered.criterion).toBe('도시근로자 월평균 소득의 70% 이하 · 출산자녀 1명 +10%p · 2명 이상 +20%p')
})

it('필수 응답 상태가 잘못되면 지원 가능으로 추정하지 않고 실패한다', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response({ ...current, freshness: 'UNRECOGNIZED' }))
  await expect(createEligibilityRepository(fetcher).loadComparison(ref)).rejects.toThrow()
})
