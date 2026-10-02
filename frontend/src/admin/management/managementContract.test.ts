import { describe, expect, it } from 'vitest'
import { parseManagementDetail } from './managementContract'

const summary = {
  id: 7, name: '두꺼비 단지', subtitle: '서울', provider: 'LH', rental: 'HAPPY_HOUSING',
  deleted: false, modified: false, reviewRequired: true, updatedAt: null,
}
const housingType = { id: 1, name: '36A', exclusiveArea: 36.5, householdCount: null }
const supplyRow = {
  id: 9, housingComplexId: null, housingComplexName: null, housingTypeId: null,
  modified: false, data: { sourceComplexName: '미연결 단지', totalSupplyHouseholdCount: 0 },
}

function detail() {
  return {
    summary, sourceIdentifier: 'LH-7', data: { version: 0, hasElevator: false, address: { pnu: null } },
    housingTypes: [housingType], supplyRows: [supplyRow], announcements: [summary],
    scheduleReviewed: true, schedules: [{ rank: 1, verified: false, date: null }],
  }
}

describe('관리 상세 응답 계약', () => {
  it('미연결 공급정보와 중첩 데이터의 0·false·null을 보존한다', () => {
    expect(parseManagementDetail(detail())).toEqual(detail())
  })

  it.each([
    ['버전 없는 데이터', { data: { name: '단지' } }, '상세'],
    ['잘못된 주택형', { housingTypes: [{ ...housingType, exclusiveArea: '36.5' }] }, '주택형'],
    ['잘못된 연결 단지 ID', { supplyRows: [{ ...supplyRow, housingComplexId: '7' }] }, '공급정보'],
    ['잘못된 공급정보', { supplyRows: [{ ...supplyRow, data: { count: undefined } }] }, '공급정보'],
    ['잘못된 연결 공고', { announcements: [{ ...summary, name: null }] }, '연결 공고'],
    ['잘못된 접수 일정', { schedules: [null] }, '접수 일정'],
    ['배열이 아닌 접수 일정', { schedules: {} }, '접수 일정'],
  ] as const)('%s 응답을 거절한다', (_description, invalid, message) => {
    expect(() => parseManagementDetail({ ...detail(), ...invalid })).toThrow(`${message} 응답이 올바르지 않습니다.`)
  })

  it('검토 플래그를 생략한 응답은 검토 완료로 추정하지 않는다', () => {
    expect(parseManagementDetail({ ...detail(), scheduleReviewed: undefined }).scheduleReviewed).toBe(false)
  })
})
