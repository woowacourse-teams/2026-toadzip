import { expect, it } from 'vitest'
import { blankTarget } from '../supply/supplyCorrectionApi'
import { daegu } from './fixtures/workspace'
import { complexTypeViews } from './complexTypes'

it('단지 주택형마다 단지 정보와 이번 공고 모집수·예비자 수·연결 공급행·PDF 추출 행(서버가 전용면적으로 연결할 행 포함)을 나눠 세고, 없는 값은 추정하지 않는다', () => {
  const { review, workspace, housing } = daegu()
  workspace.supply.rows[0] = { ...workspace.supply.rows[0], housingTypeId: 501, targets: [{ ...blankTarget(0), target: '일반', reserveCount: 30 }] }
  const views = complexTypeViews(41, review, workspace, housing[41])
  expect(views.map(view => [view.type.id, view.recruitment, view.reserve, view.rows.map(row => row.id), view.extracted, view.badges])).toEqual([
    [501, 240, 30, [747], [0], []], [502, null, null, [], [1], ['미연결']], [503, null, null, [], [2], ['미연결']]])
})

it('PDF 행 둘이 같은 주택형을 가리키면 중복 후보로 표시한다', () => {
  const { review, workspace, housing } = daegu()
  const same = { ...review, rows: review.rows.map(row => ({ ...row, reviewed: true, cells: row.cells.map(cell => cell.field === 'housingTypeId' ? { ...cell, selected: true, value: 501 } : cell) })) }
  expect(complexTypeViews(41, same, workspace, housing[41])[0].badges).toContain('중복 후보')
})
