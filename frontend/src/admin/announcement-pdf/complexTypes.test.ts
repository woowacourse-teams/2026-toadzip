import { expect, it } from 'vitest'
import { daegu } from './fixtures/workspace'
import { complexTypeViews } from './complexTypes'

it('단지 주택타입마다 이번 공고 모집수·예비자 수·연결 주택형·PDF 주택형(서버가 이름·면적으로 연결할 주택형 포함)을 나눠 세고, 없는 값은 추정하지 않는다', () => {
  const { review, workspace, housing } = daegu()
  const sheet = workspace.supply.sheet
  sheet.housingTypes[0] = { ...sheet.housingTypes[0], housingTypeId: 501 }
  sheet.quotas.push({ key: 'quota:reserve', id: 9, kind: 'RESERVE', count: 30, members: [sheet.cells[0].key], adminModified: false })
  const views = complexTypeViews(41, review, workspace, housing[41])
  expect(views.map(view => [view.type.id, view.recruitment, view.reserve, view.rows.map(row => row.id), view.extracted, view.badges])).toEqual([
    [501, 240, 30, [747], [0], []], [502, null, null, [], [1], ['미연결']], [503, null, null, [], [2], ['미연결']]])
})

it('PDF 주택형 둘이 같은 주택타입을 가리키면 중복 후보로 표시한다', () => {
  const { review, workspace, housing } = daegu()
  const same = { ...review, rows: review.rows.map(row => ({ ...row, cells: row.cells.map(cell => cell.field === 'housingTypeId' ? { ...cell, selected: true, value: 501 } : cell) })) }
  expect(complexTypeViews(41, same, workspace, housing[41])[0].badges).toContain('중복 후보')
})
