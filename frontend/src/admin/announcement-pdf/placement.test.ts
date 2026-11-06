import { expect, it } from 'vitest'
import { daegu, ghFrame } from './fixtures/workspace'
import { chooseCellValue, assembleReview, createReview, pendingItems, placementMode, placementProblems, rebaseReview, ReviewError, type Review } from './review'
import { includeRow, keepMerged, placeRow, splitMerged } from './placement'
import { complexCorrespondence, offerResult, offerSummary, placementDoubt } from './correspondence'

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

const countCell = (review: Review, at: number, patch: (cell: Review['rows'][number]['cells'][number]) => Review['rows'][number]['cells'][number]) =>
  ({ ...review, rows: review.rows.map((row, index) => index === at ? { ...row, cells: row.cells.map(cell => cell.field === 'totalSupplyHouseholdCount' ? patch(cell) : cell) } : row) })

it('분리 합계가 LH 공급 수와 다르면 경고로 알리고 분리 행이 하나만 남으면 저장하지 않는다', () => {
  const { review, workspace, housing } = daegu()
  const picked = pickCounts(review)
  expect(placementProblems(picked, workspace).size).toBe(0)
  const typed = countCell(picked, 1, cell => ({ ...chooseCellValue(cell, 'manual'), input: '26', manualInput: '26', value: 26 }))
  expect(placementProblems(typed, workspace).get(0)).toMatchObject({ level: 'warning', kind: 'split', message: expect.stringContaining('나눈 행의 모집수 합 241세대가 LH 이번 공급 240세대와 다릅니다') })
  const one = includeRow(includeRow(picked, 1, false, workspace, housing), 2, false, workspace, housing)
  expect(placementProblems(one, workspace).get(0)).toMatchObject({ level: 'error', kind: 'split' })
  expect(() => assembleReview(one, workspace, evidence)).toThrow("LH 행 '공공임대 240세대' 나누기에는 PDF 행이 둘 이상 필요한데 지금은 공급행 1(051.9800A) 하나뿐입니다")
})

it('첫 분리행이 통합 모집수 240을 그대로 두고 다른 분리행에 모집수가 있으면 두 번 집계되므로 오류로 막고 첫 분리행 모집수 칸으로 이동하게 한다', () => {
  const { review, workspace } = daegu()
  const kept = countCell(pickCounts(review), 0, cell => chooseCellValue(cell, 'keep'))
  const problem = placementProblems(kept, workspace).get(1)
  expect(problem).toMatchObject({ level: 'error', kind: 'split', focus: 'cell:supplyRows[0].totalSupplyHouseholdCount', message: expect.stringContaining('공급행 1(051.9800A)은 #747을 이어받는 첫 행인데 모집수가 합친 240세대 그대로라, 나머지 행의 모집수와 두 번 셉니다') })
  let error: unknown
  try { assembleReview(kept, workspace, evidence) } catch (cause) { error = cause }
  expect(error).toBeInstanceOf(ReviewError)
  expect((error as ReviewError).reviewId).toBe('cell:supplyRows[0].totalSupplyHouseholdCount')
  // 첫 분리행만 PDF 값을 고르면 통합 모집수가 남지 않아 오류가 아니다.
  expect(placementProblems(countCell(kept, 0, cell => chooseCellValue(cell, 'extracted')), workspace).size).toBe(0)
})

it('AI가 돌려준 LH 행 식별자로 LH 행을 맞추고, 목록 위치나 이 공고에 없는 식별자로는 분리·연결을 제안하지 않는다', () => {
  // 통합 LH 행 앞에 나눌 수 있는 다른 LH 행이 있어 목록 위치(1번째)로 맞추면 다른 단지 LH 행을 고른다.
  const { review } = daegu({ leadingLhRow: true, lhSourceRow: ['LH:P:SUPPLY:1', 'LH:P:SUPPLY:1', 'LH:P:SUPPLY:9'] })
  expect(review.rows.slice(0, 2).map(row => [row.proposal.saveMode, row.proposal.lhRow, row.proposal.destination])).toEqual([
    ['SPLIT', 'LH:P:SUPPLY:1', 'row:747'], ['SPLIT', 'LH:P:SUPPLY:1', 'new']])
  expect([review.rows[2].proposal.saveMode, review.rows[2].proposal.lhRow]).toEqual([undefined, undefined])
  const legacy = daegu({ leadingLhRow: true, lhSourceRow: true }).review.extraction
  legacy.supplyRows.forEach(row => { row.values.lhSourceRow = 1 })
  const { workspace, housing } = daegu({ leadingLhRow: true })
  expect(createReview(legacy, workspace, housing).rows.some(row => row.proposal.lhRow === 'LH:P:SUPPLY:0')).toBe(false)
})

it('공고문 소유라 미연결 LH 행 목록이 비어도 공급행이 없는 LH 행에는 연결을 제안하고 저장한다', () => {
  const { review, workspace, housing } = daegu({ unattached: true, documentOwned: true, lhSourceRow: [undefined, undefined, 'LH:P:SUPPLY:2'] })
  expect(workspace.supply.unattachedLhSupplyRows).toEqual([])
  expect(review.rows[2].proposal).toMatchObject({ saveMode: 'ATTACH', lhRow: 'LH:P:SUPPLY:2', destination: 'new' })
  let next = placeRow(pickCounts(review), 2, { mode: 'ATTACH', lhRow: 'LH:P:SUPPLY:2', anchorKey: 'row:747' }, workspace, housing)
  next = placeRow(next, 0, { mode: 'UPDATE', rowKey: 'row:747' }, workspace, housing)
  next = includeRow(next, 1, false, workspace, housing)
  expect(placementProblems(next, workspace).get(2)).toBeUndefined()
  expect(assembleReview(next, workspace, evidence).correction.rows.find(row => row.saveMode === 'ATTACH')).toMatchObject({ id: null, lhSourceRowIdentifier: 'LH:P:SUPPLY:2' })
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
  expect(problem?.message).toBe('공급행 1(051.9800A) · 공급행 3(059.9300B)도 같은 기존 공급정보 #747(공공임대)을 고칩니다. 기존 공급정보 하나에는 PDF 행 하나만 저장할 수 있습니다. #747(공공임대)은 LH가 여러 주택형을 합친 행이라 PDF 행마다 나눌 수 있습니다.')
  expect(pendingItems(next, true, workspace).map(item => item.id)).toContain('row:1')
  expect(() => assembleReview(next, workspace, evidence)).toThrow('같은 기존 공급정보 #747(공공임대)을 고칩니다')
})

it('공급행이 없는 LH 행이 남아도 별도 추가를 막지 않고 단지 대응에 남기며, LH 행에 붙이면 그 LH 행 식별자를 보낸다', () => {
  const { review, workspace, housing } = daegu({ unattached: true })
  let next = pickCounts(review)
  next = placeRow(next, 2, { mode: 'ADD', anchorKey: 'row:747' }, workspace, housing)
  expect(placementProblems(next, workspace).get(2)).toBeUndefined()
  expect(complexCorrespondence(next, workspace, 0).leftovers.map(item => [item.lh.rowIdentifier, item.attachedTo])).toEqual([['LH:P:SUPPLY:2', null]])
  next = placeRow(next, 2, { mode: 'ATTACH', lhRow: 'LH:P:SUPPLY:2', anchorKey: 'row:747' }, workspace, housing)
  expect(complexCorrespondence(next, workspace, 0).leftovers.map(item => [item.lh.rowIdentifier, item.attachedTo])).toEqual([['LH:P:SUPPLY:2', 2]])
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

it('LH 통합행 240을 PDF가 200·25·15로 적으면 단지 대응이 근거와 함께 나누기를 권하고, 나누면 #747이 첫 행이 되고 나머지는 새로 생긴다', () => {
  const { review, workspace, housing } = daegu()
  // 운영자가 한 행을 다른 단지 행으로 옮겨 둔 뒤에도 단지 대응은 처음 제안한 세 행을 같은 통합행의 후보로 남긴다.
  const away = placeRow(pickCounts(review), 0, { mode: 'UPDATE', rowKey: 'row:748' }, workspace, housing)
  const [offer] = complexCorrespondence(away, workspace, 0).offers
  expect([offer.lh.rowIdentifier, offer.members, offer.recommended]).toEqual(['LH:P:SUPPLY:1', [0, 1, 2], true])
  expect(offerSummary(away, offer, false)).toBe("LH는 '공공임대' 주택형을 1행(240세대, 면적 없음)으로, PDF는 3행(200·25·15, 합계 240)으로 적었습니다.")
  const split = splitMerged(away, offer.lh.rowIdentifier, offer.members, workspace, housing)
  expect(modes(split)).toEqual([['SPLIT', 'row:747'], ['SPLIT', 'new'], ['SPLIT', 'new']])
  expect(split.rows.every(row => row.reviewed)).toBe(true)
  const decided = complexCorrespondence(split, workspace, 0).offers[0]
  expect(decided.choice).toBe('split')
  expect(offerResult(split, decided)).toBe('#747(240세대)이 051.9800A 200세대가 되고 059.9100A 25세대 · 059.9300B 15세대가 새로 생깁니다.')
  expect(assembleReview(split, workspace, evidence).correction.rows.filter(row => row.saveMode === 'SPLIT').map(row => [row.id, row.householdCount, row.lhSourceRowIdentifier]))
    .toEqual([[747, 200, 'LH:P:SUPPLY:1'], [null, 25, 'LH:P:SUPPLY:1'], [null, 15, 'LH:P:SUPPLY:1']])
})

it('LH대로 1행 유지를 고르면 첫 PDF 행이 #747을 고치고 나머지 행은 반영하지 않아 두 번 세지 않으며, 다시 나누면 세 행이 돌아온다', () => {
  const { review, workspace, housing } = daegu()
  const kept = keepMerged(pickCounts(review), 'LH:P:SUPPLY:1', [0, 1, 2], workspace, housing)
  expect(modes(kept)).toEqual([['UPDATE', 'row:747'], ['ADD', 'new'], ['ADD', 'new']])
  expect(kept.rows.map(row => row.include)).toEqual([true, false, false])
  const offer = complexCorrespondence(kept, workspace, 0).offers[0]
  expect(offer.choice).toBe('keep')
  expect(offerResult(kept, offer)).toBe('#747(240세대)을 051.9800A 행 값으로 고칩니다. 저장하면 모집수는 200세대입니다. 059.9100A · 059.9300B 행은 이번 보정에서 반영하지 않습니다.')
  expect(placementProblems(kept, workspace).size).toBe(0)
  const rows = assembleReview(kept, workspace, evidence).correction.rows
  // 문장에 보인 모집수가 실제로 보내는 값이다.
  expect(rows.find(row => row.id === 747)?.householdCount).toBe(200)
  expect(rows.filter(row => row.id === null || row.saveMode)).toEqual([])
  const again = splitMerged(kept, 'LH:P:SUPPLY:1', offer.members, workspace, housing)
  expect(modes(again)).toEqual([['SPLIT', 'row:747'], ['SPLIT', 'new'], ['SPLIT', 'new']])
  expect(again.rows.every(row => row.include)).toBe(true)
})

it('AI 표시도 없고 모집수 합도 LH와 다르면 나누기를 권하지 않는다', () => {
  const { review, workspace, housing } = daegu()
  // 다른 LH 통합행(84 5세대)을 같은 세 행이 나누는 중이라고 둔다. 처음 제안한 240 통합행만 근거가 있다.
  const other = splitMerged(review, 'LH:P:SUPPLY:2', [0, 1, 2], workspace, housing)
  const offers = complexCorrespondence(other, workspace, 0).offers
  expect(offers.map(offer => [offer.lh.rowIdentifier, offer.recommended, offer.choice])).toEqual([['LH:P:SUPPLY:1', true, null], ['LH:P:SUPPLY:2', false, 'split']])
})

it('대응이 의심스러울 때만 까닭을 돌려준다: AI 표시와 다른 저장 위치, 면적이 다른 LH 행, 이번 공급 0세대 LH 행', () => {
  const { review, workspace, housing } = daegu({ lhSourceRow: true })
  expect(review.rows.map((_, index) => placementDoubt(review, index, workspace))).toEqual([null, null, null])
  const moved = placeRow(review, 2, { mode: 'UPDATE', rowKey: 'row:748' }, workspace, housing)
  expect(placementDoubt(moved, 2, workspace)).toBe("AI는 이 행을 LH 행 '공공임대 240세대'에서 나온 행으로 봤지만, 지금은 LH 행 '84 5세대'에 저장합니다.")
  const open = daegu({ unattached: true })
  const attached = placeRow(open.review, 2, { mode: 'ATTACH', lhRow: 'LH:P:SUPPLY:2', anchorKey: 'row:747' }, open.workspace, open.housing)
  expect(placementDoubt(attached, 2, open.workspace)).toBe("PDF는 전용 59.93㎡인데 LH 행 '84 5세대'의 전용면적은 84㎡입니다.")
  const empty = { ...open.workspace, supply: { ...open.workspace.supply, lhSupplyRows: open.workspace.supply.lhSupplyRows!.map(lh => lh.rowIdentifier === 'LH:P:SUPPLY:2' ? { ...lh, householdCount: 0 } : lh) } }
  expect(placementDoubt(attached, 2, empty)).toBe("LH 행 '84 0세대'의 이번 공급 수가 0세대입니다. 이 PDF 행이 그 LH 행에서 나온 것이 맞는지 공고문과 대조하세요.")
})

it('나누기를 고르면 고르지 않은 모집수는 PDF 값으로 정하되 직접 입력한 값은 두고, 근거가 약한 칸의 확인 필요는 남긴다', () => {
  const { review, workspace, housing } = daegu({ unresolvedCount: true })
  const typed = countCell(review, 1, cell => ({ ...chooseCellValue(cell, 'manual'), input: '26', manualInput: '26', value: 26 }))
  const split = splitMerged(typed, 'LH:P:SUPPLY:1', [0, 1, 2], workspace, housing)
  const request = assembleReview(split, workspace, evidence)
  expect(request.correction.rows.filter(row => row.saveMode === 'SPLIT').map(row => [row.id, row.householdCount])).toEqual([[747, 200], [null, 26], [null, 15]])
  expect(pendingItems(split, true, workspace).map(item => item.id)).toContain('cell:supplyRows[0].totalSupplyHouseholdCount')
})

it('기관 중립 이름(sourceRow)으로 받은 LH 행 식별자도 이전 결과(lhSourceRow)와 같이 통합행 분리로 둔다', () => {
  const renamed = daegu({ lhSourceRow: true, sourceKey: 'sourceRow' }).review, legacy = daegu({ lhSourceRow: true }).review
  expect(modes(renamed)).toEqual(modes(legacy))
  expect(renamed.rows.map(row => [row.proposal.lhRow, row.proposal.reason])).toEqual(legacy.rows.map(row => [row.proposal.lhRow, row.proposal.reason]))
})

it('표기·면적으로 고를 수 없는 GH 공급행 틀은 그대로 두면 새 행으로 추가된다', () => {
  const { review } = ghFrame()
  expect(review.rows.map(row => row.proposal.destination)).not.toContain('row:901')
})

it('AI가 GH 틀 행 키로 표시한 PDF 행은 작업실의 그 틀 행을 고치는 것으로 제안하고 LH 문구를 쓰지 않는다', () => {
  const { review, workspace } = ghFrame({ sourceRow: true })
  expect(modes(review)).toEqual([['UPDATE', 'row:901'], ['UPDATE', 'row:902']])
  expect(review.rows.every(row => row.proposal.needsConfirmation && row.proposal.reason.includes("GH 공급행 틀 '36") && !/LH/.test(row.proposal.reason))).toBe(true)
  expect(review.rows.every(row => row.proposal.saveMode === undefined && row.proposal.lhRow === undefined)).toBe(true)
  const request = assembleReview(pickCounts(review), workspace, evidence)
  expect(request.correction.rows.map(row => [row.id, row.saveMode, row.householdCount])).toEqual([[901, undefined, 20], [902, undefined, 10]])
})

it('작업실에 없는 GH 틀 행 키는 쓰지 않는다', () => {
  const { review } = ghFrame({ sourceRow: true })
  const stale = structuredClone(review.extraction)
  stale.supplyRows.forEach(row => { row.values.sourceRow = 'GH:801:없는 지구:36A' })
  const { workspace, housing } = ghFrame()
  expect(createReview(stale, workspace, housing).rows.map(row => row.proposal.destination)).not.toContain('row:901')
})

it('다른 PDF 행이 표기로 이미 고른 GH 틀 행은 AI가 가리켜도 한 틀 행을 두 행이 고치지 않게 따르지 않는다', () => {
  const { review, workspace, housing } = ghFrame({ sourceRow: true })
  const pointed = structuredClone(review.extraction)
  pointed.supplyRows[0].typeName = '36A 청년'
  delete pointed.supplyRows[0].values.sourceRow
  pointed.supplyRows[1].values.sourceRow = 'GH:801:수원 광교:36A'
  const destinations = createReview(pointed, workspace, housing).rows.map(row => row.proposal.destination)
  expect(destinations[0]).toBe('row:901')
  expect(destinations[1]).not.toBe('row:901')
})

it('표기 매칭이 다른 기존 행을 고른 PDF 행은 AI의 GH 지정을 따르지 않는다', () => {
  const { review, workspace, housing } = ghFrame({ sourceRow: true })
  const pointed = structuredClone(review.extraction)
  pointed.supplyRows[0].typeName = '36C 신혼부부'
  pointed.supplyRows[1].values.sourceRow = null
  const rows = createReview(pointed, workspace, housing).rows
  expect(rows[0].proposal.destination).toBe('row:902')
  expect(rows[0].proposal.reason).not.toContain('GH 공급행 틀')
})
