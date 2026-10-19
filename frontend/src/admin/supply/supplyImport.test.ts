import { expect, it } from 'vitest'
import { blankRow, blankTarget } from './supplyCorrectionApi'
import { mergeSupplyImport, parseSupplyImport } from './supplyImport'
const row = { ...blankRow(0), id: 1, key: 'row:1', typeName: '26', targets: [{ ...blankTarget(0), id: 2, key: 'target:2', target: '일반', rent: '100000' }] }
it('명시적으로 대응한 행의 값만 수정하고 다른 행·조건과 미확인·0·큰 금액을 보존한다', () => {
  const input = parseSupplyImport(JSON.stringify({ areaUnit: 'm2', moneyUnit: 'KRW', rows: [{ householdCount: null, targets: [{ id: 2, deposit: '999999999999999999', householdCount: 0 }] }] }))
  const next = mergeSupplyImport([row, { ...row, id: 3, key: 'row:3' }], input, ['row:1'])
  expect(next[0]).toMatchObject({ householdCount: null, targets: [{ id: 2, rent: '100000', deposit: '999999999999999999', householdCount: 0 }] })
  expect(next[1]).toEqual({ ...row, id: 3, key: 'row:3' })
})
it('단위 누락과 중복 대응 및 다른 행의 조건 ID를 거절한다', () => {
  expect(() => parseSupplyImport('{"rows":[{}]}')).toThrow('areaUnit')
  expect(() => mergeSupplyImport([row], { rows: [{}, {}] }, ['row:1', 'row:1'])).toThrow('겹쳐')
  expect(() => mergeSupplyImport([row], { rows: [{ targets: [{ id: 999, rent: '0' }] }] }, ['row:1'])).toThrow('속하지 않는')
})

it.each([0, -1, 1.5])('유효하지 않은 조건 ID %s를 새 조건으로 처리하지 않는다', id => {
  expect(() => mergeSupplyImport([row], { rows: [{ targets: [{ id }] }] }, ['row:1'])).toThrow('ID가 잘못')
})
