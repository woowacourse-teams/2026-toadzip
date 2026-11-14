import { expect, it } from 'vitest'
import { sampleSheet } from './fixtures/sheet'
import { rebaseCorrection, resolveConflict } from './rebaseCorrection'
import { addCell, removeCells, removeHousingType, setLeaseAmount, setMembersCount, setQuotaCount, updateHousingType } from './sheetEdits'
import { leaseOf, ownCount } from './supplySheet'

it('다른 항목의 변경은 합치고 같은 항목을 서로 다르게 고친 것은 고르게 한다', () => {
  const base = sampleSheet()
  const mine = setLeaseAmount(updateHousingType(base, 'row:1', { name: '46A(신혼)' }), 'cell:3', 'monthlyRent', '90000')
  const latest = setLeaseAmount(updateHousingType(base, 'row:2', { supplyPnu: '1111' }), 'cell:3', 'monthlyRent', '85000')
  const result = rebaseCorrection(base, mine, latest)
  expect(result.sheet.housingTypes.map(type => [type.name, type.supplyPnu])).toEqual([['46A(신혼)', null], ['59B', '1111']])
  expect(result.conflicts).toHaveLength(1)
  expect(result.conflicts[0]).toMatchObject({ field: '월 임대료 (원)', mine: '90000', current: '85000', missing: false })
  expect(leaseOf(result.sheet, 'cell:3')?.monthlyRent).toBe('85000')
  expect(leaseOf(resolveConflict(result.sheet, result.conflicts[0], true), 'cell:3')?.monthlyRent).toBe('90000')
})

it('묶음은 종류와 멤버 칸으로 맞추고, 같은 묶음의 수를 서로 다르게 고치면 고르게 한다', () => {
  const base = sampleSheet()
  const mine = setQuotaCount(setMembersCount(base, ['cell:2'], 'RESERVE', 3), 'quota:1', 12)
  const latest = setQuotaCount(base, 'quota:1', 11)
  const result = rebaseCorrection(base, mine, latest)
  expect(ownCount(result.sheet, 'cell:2', 'RESERVE')).toBe(3)
  expect(result.conflicts.map(conflict => [conflict.mine, conflict.current])).toEqual([['12', '11']])
  expect(resolveConflict(result.sheet, result.conflicts[0], true).quotas.find(quota => quota.key === 'quota:1')?.count).toBe(12)
})

it('내가 지운 칸은 최신값에서도 지우고, 남는 묶음·임대조건 멤버에서 뺀다', () => {
  const base = sampleSheet()
  const result = rebaseCorrection(base, removeCells(base, ['cell:2']), setQuotaCount(base, 'quota:3', 9))
  expect(result.conflicts).toEqual([])
  expect(result.sheet.cells.map(cell => cell.key)).toEqual(['cell:1', 'cell:3'])
  expect(result.sheet.quotas.flatMap(quota => quota.members)).not.toContain('cell:2')
  expect(result.sheet.quotas.find(quota => quota.key === 'quota:3')?.count).toBe(9)
})

it('고친 주택형이 최신값에서 사라지면 입력을 조용히 버리지 않고 버릴지 묻는다', () => {
  const base = sampleSheet()
  const mine = addCell(updateHousingType(base, 'row:2', { name: '59B(신혼)' }), 'row:2')
  const result = rebaseCorrection(base, mine, removeHousingType(base, 'row:2'))
  expect(result.conflicts).toEqual([expect.objectContaining({ missing: true, scope: '두꺼비 · 59B(신혼)' })])
  expect(result.conflicts[0].keepMine).toBeUndefined()
  // 사라진 주택형에 더했던 새 칸은 남기지 않는다.
  expect(result.sheet.cells.every(cell => cell.housingType === 'row:1')).toBe(true)
  expect(result.sheet.housingTypes.map(type => type.key)).toEqual(['row:1'])
})
