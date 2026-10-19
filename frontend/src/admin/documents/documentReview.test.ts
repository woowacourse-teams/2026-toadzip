import { describe, expect, it } from 'vitest'
import { parseReviewResult, correctionBody, reviewTarget } from './documentReview'
import type { ManagementDetailData } from '../management/managementContract'
import type { ExtractedDocument } from './pdfExtraction'

export const detail: ManagementDetailData = {
  summary: { id: 7, name: '두꺼비', subtitle: '서울', provider: 'LH', rental: 'HAPPY_HOUSING', deleted: false,
    modified: false, reviewRequired: false, updatedAt: null }, sourceIdentifier: 'TEST-7',
  data: { version: 2, name: '두꺼비', totalHouseholdCount: 10, totalParkingCount: 0 },
  housingTypes: [{ id: 8, name: '46A', exclusiveArea: 46.8, householdCount: null }],
  announcements: [], supplyRows: [], schedules: [], scheduleReviewed: false,
}
export const document: ExtractedDocument = { fileName: '공고.pdf', sha256: 'a'.repeat(64), totalPages: 3,
  pages: [{ page: 2, text: '단지 전체 세대수 120세대. 주차대수 0대.', items: [], requiresOcr: false }] }
const target = reviewTarget('complexes', detail, 'basic')
const proposal = { field: 'totalHouseholdCount', value: 120, page: 2, quote: '단지 전체 세대수 120세대.',
  unit: '세대', scope: '단지 전체', reason: '전체 세대수 보완' }
function result(overrides: Record<string, unknown> = {}) {
  return JSON.stringify({ schemaVersion: 'document-review/v1', targetKey: target.key,
    version: 2, sha256: document.sha256, proposals: [proposal], unresolved: [], ...overrides })
}

describe('PDF 근거 검토', () => {
  it('확인한 항목만 반영하고 기존 0과 선택하지 않은 값을 보존한다', () => {
    const review = parseReviewResult(result(), target, document)
    const body = correctionBody(target, review.proposals, document, 'https://example.com/a.pdf', '전체 세대수 확인')
    expect(body).toMatchObject({ version: 2, totalHouseholdCount: 120, totalParkingCount: 0,
      documentEvidence: { sha256: document.sha256, citations: [{ field: 'totalHouseholdCount', page: 2 }] } })
    expect(detail.data.totalHouseholdCount).toBe(10)
  })
  it.each([{ targetKey: 'complexes:9:basic' }, { version: 1 }, { sha256: 'b'.repeat(64) }])(
    '다른 대상·버전·파일 결과를 거부한다: %j', override => {
      expect(() => parseReviewResult(result(override), target, document)).toThrow()
    })
  it.each([
    { quote: '실제 없는 문장' }, { page: 1 }, { value: -1 }, { value: '120' }, { value: 1.5 },
    { field: 'version' }, { unit: '만원' }, { scope: '이번 모집' }, { value: null },
  ])('원문·형식·단위·범위가 맞지 않으면 거부한다: %j', override => {
    expect(() => parseReviewResult(result({ proposals: [{ ...proposal, ...override }] }), target, document)).toThrow()
  })
  it('같은 항목에 서로 다른 제안이 오면 선택 반영을 막는다', () => {
    expect(() => parseReviewResult(result({ proposals: [proposal, proposal] }), target, document)).toThrow()
  })
  it('주택형의 미확인 세대수와 0을 구분한다', () => {
    const housingType = reviewTarget('complexes', detail, 'type:8')
    expect(housingType.current.householdCount).toBeNull()
    expect(correctionBody(housingType, [{ ...proposal, field: 'householdCount', value: 0 }],
      document, 'https://example.com/a.pdf', '확인').householdCount).toBe(0)
  })
  it('금액은 정확한 원 단위 정수 문자열로 받고 잘못된 단위와 숫자를 거부한다', () => {
    const condition = reviewTarget('announcements', { ...detail, supplyTargets: [{ id: 9, supplyRowId: 8,
      data: { target: '청년', rentalDeposit: null, monthlyRent: '0' } }] }, 'target:9')
    const packet = (value: unknown, unit = '원') => JSON.stringify({ schemaVersion: 'document-review/v1',
      targetKey: condition.key, version: condition.version, sha256: document.sha256, unresolved: [],
      proposals: [{ ...proposal, field: 'rentalDeposit', value, unit, scope: '해당 공급대상' }] })
    expect(parseReviewResult(packet('9007199254740993'), condition, document).proposals[0].value).toBe('9007199254740993')
    for (const value of [10000000, '10만원', '1.5', '-1', '1000000000000000000', '1e7']) {
      expect(() => parseReviewResult(packet(value), condition, document)).toThrow()
    }
    expect(() => parseReviewResult(packet('10000000', '만원'), condition, document)).toThrow()
  })
  it('임대조건 수정은 조회 당시 값을 함께 보내 선택하지 않은 최신 값의 덮어쓰기를 차단한다', () => {
    const current = { target: '청년', rentalDeposit: '10000000', monthlyRent: '200000' }
    const condition = reviewTarget('announcements', { ...detail,
      supplyTargets: [{ id: 9, supplyRowId: 8, data: current }] }, 'target:9')
    const body = correctionBody(condition, [{ ...proposal, field: 'rentalDeposit', value: '12000000' }],
      document, 'https://example.com/a.pdf', '보증금 확인')
    expect(body).toMatchObject({ rentalDeposit: '12000000', monthlyRent: '200000', expectedValues: current })
    expect(current.rentalDeposit).toBe('10000000')
  })
})
