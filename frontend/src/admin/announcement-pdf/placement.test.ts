import { expect, it } from 'vitest'
import { daegu } from './fixtures/workspace'
import { chooseCellValue, assembleReview, pendingItems, placementMode, placementProblems, rebaseReview, type Review } from './review'
import { includeRow, placeRow } from './placement'

const evidence = { reason: '공고문과 대조', sourceUrl: 'https://example.com/daegu', sourcePage: '3' }
const modes = (review: Review) => review.rows.map(row => [placementMode(row.proposal), row.proposal.destination === 'new' ? 'new' : row.proposal.destination])
/** 세 행의 모집수 칸에서 PDF 추출값을 고른다. */
function pickCounts(review: Review): Review {
  return { ...review, rows: review.rows.map(row => ({ ...row, reviewed: true, cells: row.cells.map(cell => cell.field === 'totalSupplyHouseholdCount' ? chooseCellValue(cell, 'extracted') : cell) })) }
}

it('근거 없이 통합행 하나에 몰린 PDF 행은 모호로 두고, 모집수 합이 LH 공급 수와 같으면(240 = 200 + 25 + 15) 통합행 분리를 제안한다', () => {
  const { review } = daegu()
  expect(modes(review)).toEqual([['SPLIT', 'row:747'], ['SPLIT', 'new'], ['SPLIT', 'new']])
  expect(review.rows.every(row => row.proposal.lhRow === 'LH:P:SUPPLY:1' && row.proposal.needsConfirmation)).toBe(true)
  expect(review.rows[0].proposal.reason).toContain('200 + 25 + 15 = 240')
})

it('AI가 같은 lhSourceRow로 표시한 행은 통합행 분리로, 통합행은 첫 분리행으로 저장하고 모든 분리행에 LH 행 식별자를 보낸다', () => {
  const { review, workspace } = daegu({ lhSourceRow: true })
  expect(modes(review)).toEqual([['SPLIT', 'row:747'], ['SPLIT', 'new'], ['SPLIT', 'new']])
  const request = assembleReview(pickCounts(review), workspace, evidence)
  const split = request.correction.rows.filter(row => row.saveMode === 'SPLIT')
  expect(split.map(row => [row.id, row.householdCount, row.housingTypeId, row.lhSourceRowIdentifier])).toEqual([
    [747, 200, 501, 'LH:P:SUPPLY:1'], [null, 25, 502, 'LH:P:SUPPLY:1'], [null, 15, 503, 'LH:P:SUPPLY:1']])
  expect(request.correction.rows.find(row => row.id === 748)?.saveMode).toBeUndefined()
})

it('목록 모집수와 다르다는 안내는 막지 않고, 근거가 있는 PDF 추출값을 고를 수 있게 한다', () => {
  const { review, workspace } = daegu({ unresolvedCount: true })
  const count = review.rows[0].cells.find(cell => cell.field === 'totalSupplyHouseholdCount')!
  expect(count.state).toBe('confirm')
  expect(count.note).toBe('참고: 목록 모집수 240과 다름')
  const chosen = chooseCellValue(count, 'extracted')
  expect([chosen.selected, chosen.value]).toEqual([true, 200])
  expect(assembleReview(pickCounts(review), workspace, evidence).correction.rows.find(row => row.id === 747)?.householdCount).toBe(200)
})

it('분리 합계가 LH 공급 수와 다르면 경고로 알리고 분리 행이 하나만 남으면 저장하지 않는다', () => {
  const { review, workspace, housing } = daegu()
  const picked = pickCounts(review)
  expect(placementProblems(picked, workspace).size).toBe(0)
  const kept = { ...picked, rows: picked.rows.map((row, at) => at === 0 ? { ...row, cells: row.cells.map(cell => cell.field === 'totalSupplyHouseholdCount' ? chooseCellValue(cell, 'keep') : cell) } : row) }
  expect(placementProblems(kept, workspace).get(0)).toMatchObject({ level: 'warning', kind: 'split', message: expect.stringContaining('분리 합계 280세대') })
  const one = includeRow(includeRow(picked, 1, false, workspace, housing), 2, false, workspace, housing)
  expect(placementProblems(one, workspace).get(0)).toMatchObject({ level: 'error', kind: 'split' })
  expect(() => assembleReview(one, workspace, evidence)).toThrow('통합행 분리에는 같은 LH 행')
})

it('분리 묶음의 첫 행을 제외하면 남은 첫 행이 통합행을 이어받는다', () => {
  const { review, workspace, housing } = daegu()
  const next = includeRow(review, 0, false, workspace, housing)
  expect(modes(next)).toEqual([['SPLIT', 'new'], ['SPLIT', 'row:747'], ['SPLIT', 'new']])
  expect(next.rows[1].key).toBe('row:747')
})

it('여러 PDF 행이 같은 기존 행을 수정하면 중복 연결 오류와 통합행 분리 안내를 보이고 저장하지 않는다', () => {
  const { review, workspace, housing } = daegu()
  let next = placeRow(review, 0, { mode: 'UPDATE', rowKey: 'row:747' }, workspace, housing)
  next = placeRow(next, 1, { mode: 'UPDATE', rowKey: 'row:747' }, workspace, housing)
  const problem = placementProblems(next, workspace).get(1)
  expect(problem).toMatchObject({ level: 'error', kind: 'duplicate' })
  expect(problem?.message).toContain('통합행 분리로 나눌 수 있습니다')
  expect(pendingItems(next, true, workspace).map(item => item.id)).toContain('row:1')
  expect(() => assembleReview(next, workspace, evidence)).toThrow('중복 연결')
})

it('공급행이 없는 LH 행이 남아도 별도 추가를 막지 않고 경고만 하며, LH 행에 연결하면 그 LH 행 식별자를 보낸다', () => {
  const { review, workspace, housing } = daegu({ unattached: true })
  let next = pickCounts(review)
  next = placeRow(next, 2, { mode: 'ADD', anchorKey: 'row:747' }, workspace, housing)
  expect(placementProblems(next, workspace).get(2)).toMatchObject({ level: 'warning', kind: 'unattached' })
  next = placeRow(next, 2, { mode: 'ATTACH', lhRow: 'LH:P:SUPPLY:2', anchorKey: 'row:747' }, workspace, housing)
  const rows = assembleReview(next, workspace, evidence).correction.rows
  expect(rows.find(row => row.saveMode === 'ATTACH')).toMatchObject({ id: null, lhSourceRowIdentifier: 'LH:P:SUPPLY:2', householdCount: 15 })
})

it('운영자가 고른 저장 방식과 값 선택은 최신값을 다시 불러와 재계산해도 바뀌지 않는다', () => {
  const { review, workspace, housing } = daegu()
  let next = pickCounts(review)
  next = placeRow(next, 2, { mode: 'ADD', anchorKey: 'row:747' }, workspace, housing)
  next = { ...next, rows: next.rows.map((row, at) => at === 1 ? { ...row, cells: row.cells.map(cell => cell.field === 'totalSupplyHouseholdCount' ? { ...chooseCellValue(cell, 'manual'), input: '26', manualInput: '26', value: 26 } : cell) } : row) }
  const latest = { ...workspace, supply: { ...workspace.supply, snapshotToken: 'newer', rows: workspace.supply.rows.map(row => row.id === 747 ? { ...row, householdCount: 230 } : row) } }
  const rebased = rebaseReview(next, latest, housing)
  expect(modes(rebased)).toEqual([['SPLIT', 'row:747'], ['SPLIT', 'new'], ['ADD', 'new']])
  expect(rebased.rows[1].cells.find(cell => cell.field === 'totalSupplyHouseholdCount')).toMatchObject({ mode: 'manual', manualInput: '26' })
  expect(rebased.rows[0].cells.find(cell => cell.field === 'totalSupplyHouseholdCount')).toMatchObject({ mode: 'extracted', before: 230, value: 200 })
})
