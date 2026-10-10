import { describe, expect, it } from 'vitest'
import { parseComplexVerification } from './complexVerificationContract'
import { currentText, mapUrl, safeEvidenceUrl, sameValue, sourceMatches } from './complexVerificationPresentation'

const currentValues = {
  NAME: '두꺼비 단지', ADDRESS: { roadAddress: '서울 중구 세종대로 110', pnu: '1114010100100010000',
    legalDongCode: '1114010100', provinceCode: '11', cityCountyDistrictCode: '11140' },
  LOCATION: [37.5665, 126.978] as [number, number], AGENCY: 'LH', RENTAL_TYPE: 'HAPPY_HOUSING', HOUSEHOLD_COUNT: 0,
}
const response = {
  version: 2, snapshotToken: 'a'.repeat(64), status: 'UNREVIEWED', currentValues, sources: [], latestReview: null, history: [],
}

describe('단지 검증 응답', () => {
  it('원천 없음과 0세대를 보존한다', () => {
    const value = parseComplexVerification(response)
    expect(value.sources).toEqual([])
    expect(currentText('HOUSEHOLD_COUNT', value.currentValues)).toBe('0세대')
  })
  it.each([
    { ...response, status: 'AUTO_VERIFIED' },
    { ...response, snapshotToken: '' },
    { ...response, currentValues: { ...currentValues, LOCATION: [NaN, 181] } },
    { ...response, currentValues: { ...currentValues, HOUSEHOLD_COUNT: -1 } },
    { ...response, sources: [{ sourceIdentifier: '12:HAPPY_HOUSING' }] },
  ])('잘못된 응답을 성공으로 표시하지 않는다', value => {
    expect(() => parseComplexVerification(value)).toThrow('단지 검증 응답이 올바르지 않습니다.')
  })
  it('검토 범위와 당시 값이 맞지 않는 기록을 거부한다', () => {
    const review = { id: 1, outcome: 'VERIFIED', fields: ['NAME'], checkedValues: { AGENCY: 'LH' },
      actor: 'admin', reviewedAt: '2026-10-08T00:00:00Z', evidenceUrl: null, evidenceNote: '공식 안내 확인' }
    expect(() => parseComplexVerification({ ...response, latestReview: review, history: [review] })).toThrow()
  })
})

it('지도에 저장 좌표를 전달하고 위험한 근거 링크를 차단한다', () => {
  const url = new URL(mapUrl(currentValues.LOCATION.join(',')))
  expect(url.searchParams.get('query')).toBe('37.5665,126.978')
  expect(url.searchParams.get('api')).toBe('1')
  expect(safeEvidenceUrl('javascript:alert(1)')).toBeNull()
  expect(safeEvidenceUrl('https://user:password@example.com')).toBeNull()
  expect(safeEvidenceUrl('https://example.com/?serviceKey=secret&a=1')).toBe('https://example.com/?a=1')
})
it('코드와 기존 한글 유형값을 같은 의미로 비교하며 주소가 같아도 PNU가 다르면 구분한다', () => {
  const source = { sourceIdentifier: '12:HAPPY_HOUSING', name: '두꺼비 단지', provider: '한국토지주택공사',
    rentalType: '5년임대', roadAddress: currentValues.ADDRESS.roadAddress, pnu: '다른 PNU', householdCount: 0,
    housingType: '26A', exclusiveArea: 26, collectedAt: null }
  expect(sourceMatches('AGENCY', currentValues, source)).toBe(true)
  expect(sourceMatches('RENTAL_TYPE', { ...currentValues, RENTAL_TYPE: 'PUBLIC_RENTAL_5Y' }, source)).toBe(true)
  expect(sourceMatches('RENTAL_TYPE', { ...currentValues, RENTAL_TYPE: '5년임대' }, source)).toBe(true)
  expect(sourceMatches('ADDRESS', currentValues, source)).toBe(false)
  expect(sameValue({ a: 1, b: [0, 2] }, { b: [0, 2], a: 1 })).toBe(true)
})
