import { expect, it } from 'vitest'
import { blankRow, type CorrectionRow } from '../supply/supplyCorrectionApi'
import { correctionReflected, documentReflected } from './applySummary'

const row = (id: number | null, key: string, typeName: string, householdCount: number, housingTypeId: number | null): CorrectionRow =>
  ({ ...blankRow(0), id, key, typeName, householdCount, housingTypeId, housingComplexId: 41, housingComplexName: '대구테크노폴리스4', complexName: '대구테크노폴리스4' })

it('보내려던 공급행이 최신 공급행에 모두 있으면 이미 반영된 것으로 본다. 새 행은 키가 달라도 값으로 대응한다', () => {
  const intended = [row(747, 'row:747', '051.9800A', 200, 501), row(null, 'temp', '059.9100A', 25, 502)]
  expect(correctionReflected(intended, { rows: [row(747, 'row:747', '051.9800A', 200, 501), row(900, 'row:900', '059.9100A', 25, 502)] })).toBe(true)
  expect(correctionReflected(intended, { rows: [row(747, 'row:747', '공공임대', 240, null)] })).toBe(false)
})

it('주택형을 비워 보낸 행은 서버가 전용면적으로 정한 주택형과 관계없이 값으로 반영 여부를 본다', () => {
  const intended = [row(null, 'temp', '059.9100A', 25, null)]
  expect(correctionReflected(intended, { rows: [row(900, 'row:900', '059.9100A', 25, 502)] })).toBe(true)
  expect(correctionReflected(intended, { rows: [row(900, 'row:900', '059.9100A', 26, 502)] })).toBe(false)
  // 주택형을 직접 고른 행은 그 주택형이어야 한다.
  expect(correctionReflected([row(null, 'temp', '059.9100A', 25, 503)], { rows: [row(900, 'row:900', '059.9100A', 25, 502)] })).toBe(false)
})

it('이미 처리했다는 응답에서 기본정보는 같아도 고른 일정이 최신값에 없으면 반영되지 않은 것으로 본다', () => {
  const schedule = { scheduleType: 'APPLICATION', name: '접수', startAt: '2026-10-27T10:00:00', endAt: '2026-10-29T17:00:00' }
  const places = [{ name: 'LH 청약플러스', method: 'ONLINE' }, { name: '견본주택', method: 'VISIT' }]
  const input = { fields: ['applicationStartDate', 'receptionPlaces'], schedules: [{ inputIndex: 0, existingId: 14 }],
    document: { announcement: { applicationStartDate: '2026-10-27' }, receptionPlaces: places, schedules: [schedule] } } as unknown as Parameters<typeof documentReflected>[0]
  const detail = { data: { applicationStartDate: '2026-10-27', receptionPlaces: places } } as unknown as Parameters<typeof documentReflected>[1]['detail']
  expect(documentReflected(input, { detail, events: [{ id: 14, data: schedule, complexName: null }] })).toBe(true)
  expect(documentReflected(input, { detail, events: [{ id: 14, data: { ...schedule, name: '이전 접수' }, complexName: null }] })).toBe(false)
  expect(documentReflected(input, { detail: { ...detail, data: { ...detail.data, receptionPlaces: places.slice(0, 1) } }, events: [{ id: 14, data: schedule, complexName: null }] })).toBe(false)
})

