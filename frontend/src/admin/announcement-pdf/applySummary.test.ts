import { expect, it } from 'vitest'
import { blankRow, blankTarget, type CorrectionRow } from '../supply/supplyCorrectionApi'
import { correctionReflected, summarizePreview } from './applySummary'

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
  expect(summary.targets).toEqual([{ row: '051.9800A', kind: 'changed', detail: '일반 · 보증금 1,000원 · 월 10원 → 일반 · 보증금 2,000원 · 월 10원' },
    { row: '051.9800B', kind: 'new', detail: '청년 · 보증금 500원 · 월 5원' }])
})

it('보내려던 공급행이 최신 공급행에 모두 있으면 이미 반영된 것으로 본다. 새 행은 키가 달라도 값으로 대응한다', () => {
  const intended = [row(747, 'row:747', '051.9800A', 200, 501), row(null, 'temp', '059.9100A', 25, 502)]
  expect(correctionReflected(intended, [row(747, 'row:747', '051.9800A', 200, 501), row(900, 'row:900', '059.9100A', 25, 502)])).toBe(true)
  expect(correctionReflected(intended, [row(747, 'row:747', '공공임대', 240, null)])).toBe(false)
})
