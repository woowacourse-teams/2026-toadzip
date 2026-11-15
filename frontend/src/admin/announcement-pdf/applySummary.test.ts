import { expect, it } from 'vitest'
import { correctionReflected, documentReflected } from './applySummary'

type Type = { key: string; name: string; count: number; housingTypeId: number | null; exclusiveArea?: number | null }
/** 주택형마다 칸 하나와 그 칸의 모집 수만 둔 공급표 */
const sheetOf = (types: Type[]) => ({
  housingTypes: types.map(type => ({ key: type.key, housingComplexId: 41, name: type.name, exclusiveArea: type.exclusiveArea ?? null, housingTypeId: type.housingTypeId })),
  cells: types.map(type => ({ key: `${type.key}:c`, housingType: type.key })),
  quotas: types.map(type => ({ kind: 'WINNER' as const, count: type.count, members: [`${type.key}:c`] })),
})
const latest = (types: Type[]) => ({ sheet: { ...sheetOf(types), targets: [], leaseTerms: [] } }) as unknown as Parameters<typeof correctionReflected>[1]

it('보내려던 주택형이 최신 공급표에 모두 있으면 이미 반영된 것으로 본다. 새 주택형은 키가 달라도 값으로 대응한다', () => {
  const intended = sheetOf([{ key: 'row:747', name: '051.9800A', count: 200, housingTypeId: 501 }, { key: 'new:t1', name: '059.9100A', count: 25, housingTypeId: 502 }])
  expect(correctionReflected(intended, latest([{ key: 'row:747', name: '051.9800A', count: 200, housingTypeId: 501 }, { key: 'row:900', name: '059.9100A', count: 25, housingTypeId: 502 }]))).toBe(true)
  expect(correctionReflected(intended, latest([{ key: 'row:747', name: '공공임대', count: 240, housingTypeId: null }]))).toBe(false)
  // 지울 주택형이 남아 있으면 반영되지 않은 것이다.
  expect(correctionReflected(intended, latest([{ key: 'row:747', name: '051.9800A', count: 200, housingTypeId: 501 }, { key: 'row:900', name: '059.9100A', count: 25, housingTypeId: 502 },
    { key: 'row:748', name: '39', count: 10, housingTypeId: null }]))).toBe(false)
})

it('주택타입을 비워 보낸 주택형은 서버가 정한 주택타입과 관계없이 값으로 반영 여부를 본다', () => {
  const intended = sheetOf([{ key: 'new:t0', name: '059.9100A', count: 25, housingTypeId: null }])
  expect(correctionReflected(intended, latest([{ key: 'row:900', name: '059.9100A', count: 25, housingTypeId: 502 }]))).toBe(true)
  expect(correctionReflected(intended, latest([{ key: 'row:900', name: '059.9100A', count: 26, housingTypeId: 502 }]))).toBe(false)
  // 주택타입을 직접 고른 주택형은 그 주택타입이어야 한다.
  expect(correctionReflected(sheetOf([{ key: 'new:t0', name: '059.9100A', count: 25, housingTypeId: 503 }]), latest([{ key: 'row:900', name: '059.9100A', count: 25, housingTypeId: 502 }]))).toBe(false)
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

