import { describe, expect, it } from 'vitest'
import type { CheckView, ListConditionResult, TypeView } from '../../user/conditions/conditionsModel'
import { GUIDANCE_KIND_LABEL, evidenceLabel, listResultText, missingNote, sortChecks, sortTypes, typeChipRows, typeStatusText, verdictCopy } from './comparisonPresentation'

const check = (status: CheckView['status'], extra: Partial<CheckView> = {}): CheckView => ({
  id: status, label: '세대 소득', status, mine: null, criterion: '', basis: null, evidence: null, fields: ['HOUSEHOLD_MONTHLY_INCOME'], missingReason: null, ...extra,
})
const type = (status: TypeView['status'], extra: Partial<TypeView> = {}): TypeView => ({
  key: status, label: '청년', status, units: '', checks: [], guidances: [], ...extra,
})

describe('신청자격 결과 표시', () => {
  it('지원 가능 선택지를 먼저 두고 같은 결과의 원문 순서를 지킨다', () => {
    const types = [type('INELIGIBLE', { key: 'a' }), type('UNDETERMINED', { key: 'b' }), type('ELIGIBLE', { key: 'c' }), type('INELIGIBLE', { key: 'd' }), type('UNDETERMINED', { key: 'e' })]
    expect(sortTypes(types).map((item) => item.key)).toEqual(['c', 'b', 'e', 'a', 'd'])
  })
  it('확인된 미충족을 미입력보다 먼저 보여준다', () => {
    expect(sortChecks([check('SATISFIED'), check('DIRECT_CHECK_REQUIRED'), check('INPUT_NEEDED'), check('NOT_SATISFIED')]).map((item) => item.status))
      .toEqual(['NOT_SATISFIED', 'INPUT_NEEDED', 'DIRECT_CHECK_REQUIRED', 'SATISFIED'])
  })
  it('공고문에서 확인할 자격과 수집 가능한 입력 부족을 구분한다', () => {
    expect(verdictCopy(type('ELIGIBLE'))).toMatchObject({ title: '기본 조건을 충족해요', message: '좋은 집과 인연이 닿길 응원할게요.' })
    expect(verdictCopy(type('UNDETERMINED'))).toMatchObject({ title: '직접 확인할 항목이 남았어요', bulkInput: false })
    expect(verdictCopy(type('UNDETERMINED', { checks: [check('INPUT_NEEDED')] })))
      .toMatchObject({ title: '입력하면 확인할 수 있어요', message: '거의 다 왔어요. 1개만 채우면 바로 알려드릴게요.', bulkInput: false })
    const two = type('UNDETERMINED', { checks: [check('INPUT_NEEDED'), check('INPUT_NEEDED', { fields: ['FAMILY_OWNS_HOUSE'] })] })
    expect(verdictCopy(two)).toMatchObject({ message: '거의 다 왔어요. 2개만 채우면 바로 알려드릴게요.', bulkInput: true })
    // 미충족이면 입력이 남아도 한 번에 입력하기 대신 내 정보 수정을 권한다.
    expect(verdictCopy(type('INELIGIBLE', { checks: [check('INPUT_NEEDED'), check('INPUT_NEEDED', { fields: ['FAMILY_OWNS_HOUSE'] })] })))
      .toMatchObject({ title: '맞지 않는 조건이 있어요', showFix: true, bulkInput: false })
    expect(verdictCopy(type('NOT_EVALUATED')).title).toBe('비교한 조건이 없어요')
  })
  it('필요한 미입력만 중복 없이 모은다', () => {
    const view = type('UNDETERMINED', { checks: [check('INPUT_NEEDED'), check('INPUT_NEEDED', { fields: ['FAMILY_OWNS_HOUSE'] }), check('INPUT_NEEDED')] })
    expect(verdictCopy(view).missingFields).toEqual(['HOUSEHOLD_MONTHLY_INCOME', 'FAMILY_OWNS_HOUSE'])
  })
  it('처리 중과 대상 밖을 개인 미충족으로 표시하지 않는다', () => {
    const result = (extra: Partial<ListConditionResult> = {}): ListConditionResult => ({ announcementId: '1', availability: 'AVAILABLE', freshness: 'CURRENT', best: { label: '청년', status: 'ELIGIBLE' }, eligibleInRegion: true, missingInputs: [], ...extra })
    expect(listResultText(result())).toBe('청년 지원 가능')
    expect(listResultText(result({ freshness: 'RECHECKING' }))).toBe('현재 정보로 다시 확인 중')
    expect(listResultText(result({ availability: 'NOT_STRUCTURED' }))).toBe('자격 비교 준비 중')
    expect(listResultText(result({ availability: 'CANCELLED' }))).toBe('취소된 모집이에요')
  })
  it('입력할 항목의 안내는 이유를 말하고, 이미 미충족이면 안내하지 않는다', () => {
    expect(missingNote(check('INPUT_NEEDED'), 'UNDETERMINED')).toBe('아직 입력하지 않은 항목 · 입력하면 바로 확인돼요')
    expect(missingNote(check('INPUT_NEEDED'), 'INELIGIBLE')).toBeNull()
    expect(missingNote(check('INPUT_NEEDED', { missingReason: 'LATER' }), 'UNDETERMINED')).toContain('나중에 입력하기로 한 항목')
    expect(missingNote(check('INPUT_NEEDED', { missingReason: 'FAMILY_SKIPPED' }), 'UNDETERMINED')).toContain('가족 정보를 건너뛴 항목')
    expect(missingNote(check('SATISFIED'), 'ELIGIBLE')).toBeNull()
  })
  it('탭의 상태와 근거 쪽수·안내 분류를 보존한다', () => {
    expect(typeStatusText(type('ELIGIBLE'))).toBe('충족')
    expect(typeStatusText(type('INELIGIBLE'))).toBe('미충족')
    expect(evidenceLabel({ page: 6, location: '소득표', fileName: null })).toBe('6쪽 소득표')
    expect(evidenceLabel({ page: 5, location: null, fileName: null })).toBe('5쪽')
    // PDF 추출 근거의 위치는 데이터 경로라 쪽수만 보인다.
    expect(evidenceLabel({ page: 4, location: 'eligibility.common.requirements[1]', fileName: null })).toBe('4쪽')
    expect(GUIDANCE_KIND_LABEL.APPLICATION_EXCLUSION).toBe('신청 불가능한 특수 경우')
  })
  it('안내 칩을 두 줄로 나눈다', () => {
    expect(typeChipRows(['청년', '신혼부부', '한부모', '고령자', '대학생'])).toEqual([['청년', '신혼부부'], ['한부모', '고령자', '대학생']])
    expect(typeChipRows(['청년', '신혼부부'])).toEqual([['청년', '신혼부부'], []])
    // 여러 주택형에 같은 유형이 있어도 칩은 한 번만 보인다.
    expect(typeChipRows(['대학생', '청년', '대학생', '청년', '대학생'])).toEqual([['대학생', '청년'], []])
  })
})
