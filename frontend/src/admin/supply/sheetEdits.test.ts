import { describe, expect, it } from 'vitest'
import { sampleSheet } from './fixtures/sheet'
import { cellsOf, housingTypeCount, leaseOf, ownCount, sharedQuotas, toSheetInput, totalCount } from './supplySheet'
import { addCell, addHousingType, addSharedQuota, cellName, cellValues, moveHousingType, removeCells, removeHousingType, setCellTarget, setLeaseAmount, setMembersCount, wholeTypeQuota } from './sheetEdits'

describe('공급표 읽기', () => {
  it('칸 하나만 멤버면 그 대상만의 수, 한 주택형의 모든 칸이면 주택형 모집수, 여러 주택형에 걸치면 함께 쓰는 수로 읽는다', () => {
    const sheet = sampleSheet()
    expect(ownCount(sheet, 'cell:1', 'RESERVE')).toBe(5)
    expect(ownCount(sheet, 'cell:1', 'WINNER')).toBeNull()
    expect(housingTypeCount(sheet, 'row:1')).toBe(10)
    expect(housingTypeCount(sheet, 'row:2')).toBe(7)
    expect(wholeTypeQuota(sheet, 'row:1')?.key).toBe('quota:1')
    // 주택형 모집수는 함께 쓰는 수 목록에서 빼고, 여러 주택형에 걸친 예비 8만 남는다.
    expect(sharedQuotas(sheet, 'row:1')).toEqual([{ quota: sheet.quotas[3], acrossTypes: true }])
    expect(totalCount(sheet)).toBe(17)
    expect(totalCount(sheet, 'RESERVE')).toBe(13)
    expect(cellValues(sheet, sheet.cells[1])).toEqual({ label: '고령자', applicationCondition: '', winner: null, reserve: null,
      rentalDeposit: '1000000', monthlyRent: '50000', convertedDeposit: null })
    expect(cellName(sheet, 'cell:2')).toBe('46A 고령자')
  })

  it('보낼 입력에서는 보호 표시·단지 표시명과 묶음·임대조건의 키·ID를 뺀다', () => {
    const input = toSheetInput(sampleSheet())
    expect(input.housingTypes[0]).not.toHaveProperty('adminModified')
    expect(input.housingTypes[0]).not.toHaveProperty('housingComplexName')
    expect(input.quotas[0]).toEqual({ kind: 'WINNER', count: 10, members: ['cell:1', 'cell:2'] })
    expect(input.leaseTerms[1]).toEqual({ rentalDeposit: '2000000', monthlyRent: '80000', convertedDeposit: null, members: ['cell:3'] })
  })
})

describe('공급표 편집이 어긋나지 않게 하기', () => {
  it('칸을 지우면 묶음·임대조건 멤버에서 빼고, 멤버가 없어진 묶음과 아무 칸도 쓰지 않는 공급대상을 지운다', () => {
    const sheet = removeCells(sampleSheet(), ['cell:2'])
    expect(sheet.cells.map(cell => cell.key)).toEqual(['cell:1', 'cell:3'])
    expect(sheet.quotas.find(quota => quota.key === 'quota:1')?.members).toEqual(['cell:1'])
    expect(sheet.quotas.find(quota => quota.key === 'quota:4')?.members).toEqual(['cell:3'])
    expect(sheet.leaseTerms[0].members).toEqual(['cell:1'])
    expect(sheet.targets.map(target => target.key)).toEqual(['target:1'])

    const emptied = removeCells(sheet, ['cell:1'])
    expect(emptied.quotas.map(quota => quota.key)).toEqual(['quota:3', 'quota:4'])
    expect(emptied.leaseTerms.map(lease => lease.key)).toEqual(['lease:2'])
    expect(emptied.targets.map(target => target.key)).toEqual(['target:1'])
  })

  it('주택형을 지우면 그 칸이 든 묶음·임대조건도 정리하고 다른 주택형과 함께 쓰던 수에는 남은 칸만 둔다', () => {
    const sheet = removeHousingType(sampleSheet(), 'row:2')
    expect(sheet.housingTypes.map(type => type.key)).toEqual(['row:1'])
    expect(sheet.cells.every(cell => cell.housingType === 'row:1')).toBe(true)
    expect(sheet.quotas.map(quota => [quota.key, quota.members])).toEqual([['quota:1', ['cell:1', 'cell:2']], ['quota:2', ['cell:1']], ['quota:4', ['cell:2']]])
    expect(sheet.leaseTerms.map(lease => lease.key)).toEqual(['lease:1'])
    expect(sheet.targets.map(target => target.key)).toEqual(['target:1', 'target:2'])
  })

  it('칸을 더하면 칸이 둘 이상인 주택형의 모집수에 넣고, 칸이 하나뿐인 주택형의 그 대상만의 수는 넓히지 않는다', () => {
    const wider = addCell(sampleSheet(), 'row:1')
    const added = cellsOf(wider, 'row:1')[2]
    expect(added.key).toMatch(/^new:/)
    expect(wider.quotas[0].members).toEqual(['cell:1', 'cell:2', added.key])
    expect(housingTypeCount(wider, 'row:1')).toBe(10)

    const single = addCell(sampleSheet(), 'row:2')
    expect(single.quotas[2].members).toEqual(['cell:3'])
    expect(ownCount(single, 'cell:3', 'WINNER')).toBe(7)
  })

  it('다른 칸이 쓰는 공급대상은 고치지 않고 이 칸만 새 대상이나 같은 이름의 대상으로 옮긴다', () => {
    // target:1은 46A·59B 일반이 함께 쓴다. 59B 일반만 이름을 바꾼다.
    const renamed = setCellTarget(sampleSheet(), 'cell:3', '신혼부부', '')
    expect(renamed.targets.find(target => target.key === 'target:1')?.label).toBe('일반')
    const moved = renamed.cells.find(cell => cell.key === 'cell:3')!
    expect(moved.target).toMatch(/^new:/)
    // 같은 이름·조건의 대상이 있으면 새로 만들지 않고 그 대상을 가리킨다.
    const reused = setCellTarget(sampleSheet(), 'cell:3', '고령자', '')
    expect(reused.cells.find(cell => cell.key === 'cell:3')?.target).toBe('target:2')
    expect(reused.targets).toHaveLength(2)
    // 이 칸만 쓰는 대상은 그대로 고친다.
    const alone = setCellTarget(sampleSheet(), 'cell:2', '고령자', '65세 이상')
    expect(alone.targets.find(target => target.key === 'target:2')?.applicationCondition).toBe('65세 이상')
  })

  it('수량을 비우면 그 묶음을 지우고, 함께 쓰는 수는 칸을 둘 이상 고르고 같은 묶음이 없어야 더한다', () => {
    const cleared = setMembersCount(sampleSheet(), ['cell:1'], 'RESERVE', null)
    expect(ownCount(cleared, 'cell:1', 'RESERVE')).toBeNull()
    expect(cleared.quotas.some(quota => quota.key === 'quota:2')).toBe(false)
    const own = setMembersCount(sampleSheet(), ['cell:2'], 'WINNER', 3)
    expect(ownCount(own, 'cell:2', 'WINNER')).toBe(3)

    expect(addSharedQuota(sampleSheet(), 'WINNER', ['cell:1'], 4)).toBe('함께 쓰는 수는 공급대상 칸을 둘 이상 골라야 합니다.')
    expect(addSharedQuota(sampleSheet(), 'RESERVE', ['cell:3', 'cell:2'], 4)).toMatch(/이미 있습니다/)
    const shared = addSharedQuota(sampleSheet(), 'WINNER', ['cell:1', 'cell:3'], 70)
    if (typeof shared === 'string') throw new Error(shared)
    expect(sharedQuotas(shared, 'row:2').map(item => [item.quota.count, item.acrossTypes])).toEqual([[8, true], [70, true]])
  })

  it('함께 쓰는 임대조건은 한 번 고치면 함께 바뀌고, 세 금액을 모두 비우면 임대조건을 지운다', () => {
    const changed = setLeaseAmount(sampleSheet(), 'cell:2', 'monthlyRent', '60000')
    expect(leaseOf(changed, 'cell:1')?.monthlyRent).toBe('60000')
    const created = setLeaseAmount(removeCells(sampleSheet(), []), 'cell:3', 'convertedDeposit', '300')
    expect(leaseOf(created, 'cell:3')?.convertedDeposit).toBe('300')
    const withoutDeposit = setLeaseAmount(sampleSheet(), 'cell:3', 'rentalDeposit', null)
    const removed = setLeaseAmount(withoutDeposit, 'cell:3', 'monthlyRent', null)
    expect(leaseOf(removed, 'cell:3')).toBeNull()
    expect(removed.leaseTerms.map(lease => lease.key)).toEqual(['lease:1'])
  })

  it('새 주택형은 마지막 순서로 더하고, 순서를 옮기면 0부터 다시 매긴다', () => {
    const { sheet, key } = addHousingType(sampleSheet(), { complexName: '두꺼비' })
    expect(sheet.housingTypes.at(-1)).toMatchObject({ key, id: null, displayOrder: 2, complexName: '두꺼비', name: '' })
    const moved = moveHousingType(sheet, key, -1)
    expect([...moved.housingTypes].sort((a, b) => a.displayOrder - b.displayOrder).map(type => type.key)).toEqual(['row:1', key, 'row:2'])
    expect(moveHousingType(moved, 'row:1', -1)).toBe(moved)
  })
})
