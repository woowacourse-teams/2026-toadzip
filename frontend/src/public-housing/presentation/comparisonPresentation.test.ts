import { describe, expect, it } from 'vitest'
import type { CheckView, ListConditionResult, TypeView } from '../../user/conditions/conditionsModel'
import {
  GUIDANCE_KIND_LABEL,
  evidenceLabel,
  listResultText,
  missingNote,
  sortChecks,
  sortTypes,
  typeChipRows,
  typeStatusText,
  verdictCopy,
} from './comparisonPresentation'

const check = (status: CheckView['status'], extra: Partial<CheckView> = {}): CheckView => ({
  id: status, label: status, status, mine: null, criterion: '', basis: null, evidence: null, fields: ['APPLICANT_ASSETS'], missingReason: null, ...extra,
})
const type = (status: TypeView['status'], extra: Partial<TypeView> = {}): TypeView => ({
  key: status, label: '청년', coverage: 'GENERAL', status, units: '', checks: [], guidances: [], ...extra,
})

describe('sorting', () => {
  it('puts matched types first and keeps the original order inside a status', () => {
    const types = [type('NOT_MATCHED', { key: 'a' }), type('INPUT_NEEDED', { key: 'b' }), type('MATCHED', { key: 'c' }), type('NOT_MATCHED', { key: 'd' }), type('PARTIALLY_COMPARED', { key: 'e' })]
    expect(sortTypes(types).map((item) => item.key)).toEqual(['c', 'e', 'b', 'a', 'd'])
  })

  it('puts unmet checks first and satisfied checks last', () => {
    expect(sortChecks([check('SATISFIED'), check('DIRECT_CHECK_REQUIRED'), check('INPUT_NEEDED'), check('NOT_SATISFIED')]).map((item) => item.status))
      .toEqual(['NOT_SATISFIED', 'INPUT_NEEDED', 'DIRECT_CHECK_REQUIRED', 'SATISFIED'])
  })
})

describe('verdictCopy', () => {
  it('words each result with a cheerful line', () => {
    expect(verdictCopy(type('MATCHED'))).toMatchObject({ title: '기본 조건을 충족해요', message: '좋은 집과 인연이 닿길 응원할게요.', showFix: false })
    expect(verdictCopy(type('MATCHED', { coverage: 'COMMON_ONLY' }))).toMatchObject({ title: '공통 조건은 맞아요', message: '위의 별도 신청자격도 꼭 확인해 주세요.' })
    expect(verdictCopy(type('PARTIALLY_COMPARED'))).toMatchObject({ title: '직접 확인할 항목이 남았어요', message: '거의 다 왔어요. 공고문에서 한 가지만 더 확인해 보세요.' })
    expect(verdictCopy(type('NOT_MATCHED'))).toMatchObject({ title: '맞지 않는 조건이 있어요', message: '이번 유형은 아쉬워요. 다른 유형도 함께 살펴봐요.', showFix: true })
    expect(verdictCopy(type('NOT_EVALUATED'))).toMatchObject({ title: '비교한 조건이 없어요', message: '이번 모집 여부를 공고문에서 확인해 주세요.' })
  })

  it('counts the distinct missing fields', () => {
    const view = type('INPUT_NEEDED', { checks: [check('INPUT_NEEDED', { fields: ['APPLICANT_ASSETS'] }), check('INPUT_NEEDED', { fields: ['FAMILY_OWNS_HOUSE'] }), check('INPUT_NEEDED', { fields: ['APPLICANT_ASSETS'] })] })
    expect(verdictCopy(view)).toMatchObject({ title: '입력하면 확인할 수 있어요', message: '거의 다 왔어요. 2개만 채우면 바로 알려드릴게요.', missingFields: ['APPLICANT_ASSETS', 'FAMILY_OWNS_HOUSE'] })
  })
})

describe('short texts', () => {
  it('gives screen reader text for a type tab', () => {
    expect(typeStatusText(type('MATCHED'))).toBe('충족')
    expect(typeStatusText(type('MATCHED', { coverage: 'COMMON_ONLY' }))).toBe('공통 충족')
    expect(typeStatusText(type('INPUT_NEEDED'))).toBe('입력 필요')
    expect(typeStatusText(type('NOT_MATCHED'))).toBe('미충족')
  })

  it('never words a common-only match as 지원 가능', () => {
    const result = (best: ListConditionResult['best'], availability: ListConditionResult['availability'] = 'AVAILABLE'): ListConditionResult => ({ announcementId: '1', availability, best, generalMatched: false })
    expect(listResultText(result({ label: '청년', coverage: 'GENERAL', status: 'MATCHED' }))).toBe('청년 지원 가능')
    expect(listResultText(result({ label: '공통 조건', coverage: 'COMMON_ONLY', status: 'MATCHED' }))).toBe('공통 조건 공통 충족')
    expect(listResultText(result({ label: '신혼부부', coverage: 'GENERAL', status: 'INPUT_NEEDED' }))).toBe('신혼부부 입력 필요')
    expect(listResultText(result(null, 'NOT_STRUCTURED'))).toBe('자격 비교 준비 중')
    expect(listResultText(result(null, 'OUT_OF_SCOPE'))).toBeNull()
  })

  it('explains why an item could not be compared', () => {
    expect(missingNote(check('INPUT_NEEDED', { missingReason: 'LATER' }), 'INPUT_NEEDED')).toEqual({ text: '나중에 입력하기로 한 항목 · 입력하면 바로 확인돼요', quiet: false })
    expect(missingNote(check('INPUT_NEEDED', { missingReason: 'FAMILY_SKIPPED' }), 'INPUT_NEEDED')?.text).toBe('가족 정보를 건너뛴 항목 · 입력하면 바로 확인돼요')
    expect(missingNote(check('INPUT_NEEDED', { missingReason: 'UNANSWERED' }), 'NOT_MATCHED')).toEqual({ text: '다른 조건이 맞지 않아 입력해도 결과는 같아요', quiet: true })
    expect(missingNote(check('SATISFIED'), 'MATCHED')).toBeNull()
  })

  it('labels evidence pages and guidance kinds', () => {
    expect(evidenceLabel({ page: 6, location: '소득표', fileName: null })).toBe('6쪽 소득표')
    expect(evidenceLabel({ page: 5, location: null, fileName: null })).toBe('5쪽')
    expect(GUIDANCE_KIND_LABEL.OTHER_ROUTE).toBe('다른 신청 경로')
  })

  it('splits type chips into two rows with fewer on top', () => {
    expect(typeChipRows(['청년', '신혼부부', '한부모', '고령자', '대학생'])).toEqual([['청년', '신혼부부'], ['한부모', '고령자', '대학생']])
    expect(typeChipRows(['청년', '신혼부부'])).toEqual([['청년', '신혼부부'], []])
  })
})
