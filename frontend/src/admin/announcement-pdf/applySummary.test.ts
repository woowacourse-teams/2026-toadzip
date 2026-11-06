import { expect, it } from 'vitest'
import { blankRow, blankTarget, type CorrectionRow } from '../supply/supplyCorrectionApi'
import { correctionReflected, documentReflected, summarizePreview } from './applySummary'

const row = (id: number | null, key: string, typeName: string, householdCount: number, housingTypeId: number | null): CorrectionRow =>
  ({ ...blankRow(0), id, key, typeName, householdCount, housingTypeId, housingComplexId: 41, housingComplexName: '대구테크노폴리스4', complexName: '대구테크노폴리스4' })
const preview = (before: CorrectionRow[], after: CorrectionRow[]) => ({ confirmationToken: 'c', fields: [], schedules: [], attachments: [], housingTypes: [], housingTypeWarnings: [], warnings: [],
  supply: { confirmationToken: 's', before, after, warnings: [] } })
const housing = { 41: [{ id: 501, name: '51A', exclusiveArea: 51.98, supplyArea: null }] }

it('같은 주택형에 공급행이 둘 이상 연결되면 중복 확인으로 알리고, 추가·변경되는 임대조건을 모은다', () => {
  const target = { ...blankTarget(0), id: 7, key: 't7', target: '일반', deposit: '1000', rent: '10' }
  const before = [{ ...row(747, 'row:747', '공공임대', 240, null), targets: [target] }]
  const after = [{ ...row(747, 'row:747', '051.9800A', 200, 501), targets: [{ ...target, deposit: '2000' }] }, { ...row(null, 'n1', '051.9800B', 40, 501), targets: [{ ...blankTarget(0), target: '청년', deposit: '500', rent: '5' }] }]
  const summary = summarizePreview(preview(before, after), housing, [])
  expect(summary.complexes[0]).toMatchObject({ beforeTotal: 240, afterTotal: 240, duplicates: ['51A · 전용 51.98㎡에 공급행 2개(051.9800A, 051.9800B)가 연결됩니다.'] })
  expect(summary.complexes[0].after.map(item => item.change)).toEqual(['changed', 'new'])
  expect(summary.targets).toEqual([{ row: '051.9800A', kind: 'changed', detail: '일반 · 보증금 1,000원 → 2,000원' },
    { row: '051.9800B', kind: 'new', detail: '청년 · 보증금 500원 · 월 5원' }])
})

it('보내려던 공급행이 최신 공급행에 모두 있으면 이미 반영된 것으로 본다. 새 행은 키가 달라도 값으로 대응한다', () => {
  const intended = [row(747, 'row:747', '051.9800A', 200, 501), row(null, 'temp', '059.9100A', 25, 502)]
  expect(correctionReflected(intended, { rows: [row(747, 'row:747', '051.9800A', 200, 501), row(900, 'row:900', '059.9100A', 25, 502)] })).toBe(true)
  expect(correctionReflected(intended, { rows: [row(747, 'row:747', '공공임대', 240, null)] })).toBe(false)
})

it('분리·연결 행은 값이 같아도 최신 LH 행의 분리행·연결행이 그 행일 때만 이미 반영된 것으로 본다', () => {
  const lh = (rowIdentifier: string, attachedRowKey: string | null, splitRowKeys: string[]) =>
    ({ rowIdentifier, complexLabel: '대구테크노폴리스4', typeName: '공공임대', exclusiveArea: null, householdCount: 240, attachedRowKey, splitRowKeys })
  const intended = [{ ...row(747, 'row:747', '051.9800A', 200, 501), saveMode: 'SPLIT' as const, lhSourceRowIdentifier: 'LH:P:SUPPLY:1' },
    { ...row(null, 'temp', '059.9100A', 25, 502), saveMode: 'SPLIT' as const, lhSourceRowIdentifier: 'LH:P:SUPPLY:1' },
    { ...row(null, 'attach', '059.9300B', 15, 503), saveMode: 'ATTACH' as const, lhSourceRowIdentifier: 'LH:P:SUPPLY:2' }]
  const rows = [row(747, 'row:747', '051.9800A', 200, 501), row(900, 'row:900', '059.9100A', 25, 502), row(901, 'row:901', '059.9300B', 15, 503)]
  expect(correctionReflected(intended, { rows, lhSupplyRows: [lh('LH:P:SUPPLY:1', 'row:747', ['row:747', 'row:900']), lh('LH:P:SUPPLY:2', 'row:901', [])] })).toBe(true)
  // 다른 저장이 같은 값으로 고쳤지만 나누지 않았으면 이 분리는 반영되지 않았다.
  expect(correctionReflected(intended, { rows, lhSupplyRows: [lh('LH:P:SUPPLY:1', 'row:747', []), lh('LH:P:SUPPLY:2', 'row:901', [])] })).toBe(false)
  expect(correctionReflected(intended, { rows, lhSupplyRows: [lh('LH:P:SUPPLY:1', 'row:747', ['row:747', 'row:900']), lh('LH:P:SUPPLY:2', null, [])] })).toBe(false)
})

it('이미 처리했다는 응답에서 기본정보는 같아도 고른 일정이 최신값에 없으면 반영되지 않은 것으로 본다', () => {
  const schedule = { scheduleType: 'APPLICATION', name: '접수', startAt: '2026-10-27T10:00:00', endAt: '2026-10-29T17:00:00' }
  const input = { fields: ['applicationStartDate', 'receptionPlace.name'], schedules: [{ inputIndex: 0, existingId: 14 }],
    document: { announcement: { applicationStartDate: '2026-10-27' }, receptionPlace: { name: 'LH 청약플러스' }, schedules: [schedule] } } as unknown as Parameters<typeof documentReflected>[0]
  const detail = { data: { applicationStartDate: '2026-10-27', receptionPlace: { name: 'LH 청약플러스' } } } as unknown as Parameters<typeof documentReflected>[1]['detail']
  expect(documentReflected(input, { detail, events: [{ id: 14, data: schedule, complexName: null }] })).toBe(true)
  expect(documentReflected(input, { detail, events: [{ id: 14, data: { ...schedule, name: '이전 접수' }, complexName: null }] })).toBe(false)
  expect(documentReflected(input, { detail: { ...detail, data: { ...detail.data, receptionPlace: null } }, events: [{ id: 14, data: schedule, complexName: null }] })).toBe(false)
})

it('임대조건은 바뀐 항목만 변경 전후로 보이고, 전환보증금이나 모집수만 바뀌어도 변경으로 센다', () => {
  const target = { ...blankTarget(0), id: 7, key: 't7', target: '일반', deposit: '1000', rent: '10', convertedDeposit: '5000', householdCount: 3 }
  const before = [{ ...row(747, 'row:747', '51A', 240, 501), targets: [target] }]
  const converted = summarizePreview(preview(before, [{ ...before[0], targets: [{ ...target, convertedDeposit: '7000' }] }]), housing, [])
  expect(converted.targets).toEqual([{ row: '51A', kind: 'changed', detail: '일반 · 전환보증금 5,000원 → 7,000원' }])
  const counted = summarizePreview(preview(before, [{ ...before[0], targets: [{ ...target, householdCount: 5, reserveCount: 2 }] }]), housing, [])
  expect(counted.targets).toEqual([{ row: '51A', kind: 'changed', detail: '일반 · 대상별 모집수 3 → 5 · 예비자 수 없음 → 2' }])
})

it('적용 전 확인은 기본정보·접수처·일정·단지 주택형 갱신을 개수가 아니라 변경 전후 값으로 보인다', () => {
  const schedule = { scheduleType: 'APPLICATION', name: '접수', startAt: '2026-10-20T10:00:00', endAt: '2026-10-22T17:00:00' }
  const summary = summarizePreview({ ...preview([], []), supply: null,
    fields: [{ field: 'applicationStartDate', before: '2026-10-20', after: '2026-10-27' }, { field: 'receptionPlace.method', before: null, after: 'ONLINE' }, { field: 'rentalType', before: null, after: 'NATIONAL_RENTAL' }],
    schedules: [{ inputIndex: 0, existingId: 14, before: schedule, after: { ...schedule, startAt: '2026-10-27T10:00:00' } }, { inputIndex: 1, existingId: null, before: null, after: { scheduleType: 'WINNER_ANNOUNCEMENT', name: '당첨자 발표', startAt: '2026-11-10T00:00:00', endAt: null } }],
    housingTypes: [{ rowKey: 'row:747', housingTypeId: 501, typeName: '51A', before: { name: '51A', supplyArea: 72.1, householdCount: 468, basicDeposit: 1000, basicMonthlyRent: 10 }, after: { name: '051.9800A', supplyArea: 72.1, householdCount: 468, basicDeposit: 2000, basicMonthlyRent: 10 } },
      { rowKey: 'n1', housingTypeId: null, typeName: '59B', before: null, after: { name: '59B', supplyArea: null, householdCount: 15 } }] }, housing, [])
  expect(summary.fields).toEqual([{ label: '접수 시작일', before: '2026-10-20', after: '2026-10-27' }, { label: '접수 방식', before: '없음', after: '온라인' }, { label: '공급 유형', before: '없음', after: '국민임대' }])
  expect(summary.schedules).toEqual([{ label: '접수', before: '2026-10-20 10:00 ~ 2026-10-22 17:00', after: '2026-10-27 10:00 ~ 2026-10-22 17:00' }, { label: '당첨자 발표', before: '새 일정', after: '2026-11-10' }])
  expect(summary.housingTypes).toEqual([{ label: '#501 「51A」', before: '이름 51A · 기본 보증금 1,000원', after: '이름 051.9800A · 기본 보증금 2,000원' },
    { label: '새 주택형 「59B」', before: '없음', after: '이름 59B · 공급면적 없음 · 세대수 15' }])
})

it('일정은 이름이나 종류만 바뀌어도 변경 전후 문구가 달라 무엇이 바뀌는지 보인다', () => {
  const schedule = { scheduleType: 'APPLICATION', name: '이전 접수', startAt: '2026-10-20T10:00:00', endAt: '2026-10-22T17:00:00' }
  const summary = summarizePreview({ ...preview([], []), supply: null, schedules: [{ inputIndex: 0, existingId: 14, before: schedule, after: { ...schedule, name: '접수', scheduleType: 'DOCUMENT_SUBMISSION' } }] }, housing, [])
  expect(summary.schedules).toEqual([{ label: '접수', before: '이전 접수 · 접수 · 2026-10-20 10:00 ~ 2026-10-22 17:00', after: '접수 · 서류 제출 · 2026-10-20 10:00 ~ 2026-10-22 17:00' }])
})
