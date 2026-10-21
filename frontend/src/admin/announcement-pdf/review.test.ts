import { expect, it } from 'vitest'
import { scenario } from './fixtures/workspace'
import { blankTarget } from '../supply/supplyCorrectionApi'
import { assembleReview, applicableCells, chooseCellValue, createReview, editCell, pendingCell, pendingItems, rebaseReview, remapSchedule, remapTarget, validCellValue } from './review'


const evidence = { reason: '공고문과 대조', sourceUrl: 'https://example.com/notice', sourcePage: '4' }

it('한스빌의 공통 모집수는 묶음에 한 번만 넣고 기존 공급행 ID를 보존한다', () => {
  const { review, workspace } = scenario('hansville')
  const request = assembleReview(review, workspace, evidence)
  expect(request.correction.rows).toHaveLength(2)
  expect(request.correction.rows.map(row => row.householdCount)).toEqual([null, null])
  expect(request.correction.rows[0].id).toBe(90)
  // 수집 행 순서가 1부터 시작해도 새 행은 그 뒤에 둔다.
  expect(request.correction.rows.map(row => row.displayOrder)).toEqual([workspace.supply.rows[0].displayOrder, workspace.supply.rows[0].displayOrder + 1])
  expect(request.correction.newGroups).toEqual([{ sourceRowId: 90, rowKeys: request.correction.rows.map(row => row.key), sharedHouseholdCount: 170 }])
  expect(request.correction.rows[0].targets[0].deposit).toBe('4076000')
  expect(request.document.announcement.name).toBe('기존 공고')
  expect(request.document.announcement.rentalType).toBe('NATIONAL_RENTAL')
  expect(workspace.supply.rows[0].householdCount).toBe(170)
})

it('광주첨단의 단지별 일정은 기존 ID마다 복사하고 새 방문접수도 추가한다', () => {
  const { review, workspace } = scenario('gwangju')
  const request = assembleReview(review, workspace, evidence)
  const changed = request.schedules.filter(schedule => schedule.existingId !== null)
  expect(changed).toHaveLength(12)
  expect(new Set(changed.map(schedule => schedule.existingId)).size).toBe(12)
  expect(request.schedules.filter(schedule => schedule.existingId === null)).toHaveLength(1)
  expect(request.document.schedules).toHaveLength(13)
  expect(request.correction.rows[4].key).toBe('row:26')
  expect(request.correction.newGroups).toEqual([])
})

it('공주옥룡1의 미확인 공급면적은 현재값을 유지하고 수동 확인한 값만 반영한다', () => {
  const { review, workspace } = scenario('gongju')
  workspace.supply.rows[0].supplyArea = 39.87
  let request = assembleReview(review, workspace, evidence)
  expect(request.correction.rows[0].supplyArea).toBe(39.87)
  const row = review.rows[0], position = row.cells.findIndex(cell => cell.field === 'supplyArea')
  row.cells[position] = editCell(row.cells[position], '39.84')
  request = assembleReview(review, workspace, evidence)
  expect(request.correction.rows[0].supplyArea).toBe(39.84)
  expect(request.document.schedules.every(schedule => schedule.startAt !== null)).toBe(true)
})

it('저장 위치 누락과 공통 모집 묶음 일부 제외를 검증한다', () => {
  const { review, workspace } = scenario('hansville')
  const destination = review.rows[0].proposal.destination
  review.rows[0].proposal.destination = ''
  expect(() => assembleReview(review, workspace, evidence)).toThrow('기존 공급정보를 선택')
  review.rows[0].proposal.destination = destination; review.rows[1].include = false
  expect(() => assembleReview(review, workspace, evidence)).toThrow('모든 행을 함께')
})

it('같은 기존 행에 두 번 대응하거나 기존 조건을 임의로 제거하지 않는다', () => {
  const { review, workspace } = scenario('hansville')
  const old = { ...blankTarget(0), id: 33, key: 'target:33', target: '기존 특수 조건', deposit: '999999999999999999' }
  workspace.supply.rows[0].targets.push(old)
  expect(assembleReview(review, workspace, evidence).correction.rows[0].targets[0]).toEqual(old)
  review.rows[1].proposal.destination = review.rows[0].proposal.destination
  expect(() => assembleReview(review, workspace, evidence)).toThrow('두 번 갱신')
})

it('잘못된 금액·날짜·코드는 자동으로 보정하지 않는다', () => {
  expect(validCellValue('money', '999999999999999999')).toBe(true)
  expect(validCellValue('money', '4,076,000')).toBe(false)
  expect(validCellValue('date', '2026-02-30')).toBe(false)
  expect(validCellValue('datetime', '2026-10-22T25:00')).toBe(false)
  expect(validCellValue('scheduleType', 'MOVE_IN')).toBe(true)
  expect(validCellValue('scheduleType', 'DOCUMENT_RESULT')).toBe(false)
})

it('제외한 행과 조건의 값은 선택 수에 포함하지 않고 현재 조건은 보존한다', () => {
  const { review, workspace } = scenario('hansville')
  review.rows[0].targets[0].destination = 'exclude'
  expect(applicableCells(review)).not.toContain(review.rows[0].targets[0].cells[0])
  expect(assembleReview(review, workspace, evidence).correction.rows[0].targets).toEqual([])
})
it('409 뒤 최신값을 비교하면서 수동 입력·제외·저장 위치를 유지한다', () => {
  const { review, workspace, housing } = scenario('hansville')
  review.rows[0].cells[0] = editCell(review.rows[0].cells[0], '26A 수동')
  review.rows[0].cells[1] = chooseCellValue(review.rows[0].cells[1], 'keep')
  review.rows[0].targets[0].destination = 'exclude'
  workspace.supply.rows[0].typeName = '새 저장값'
  const rebased = rebaseReview(review, workspace, housing)
  expect(rebased.rows[0].cells[0]).toMatchObject({ value: '26A 수동', before: '새 저장값', selected: true })
  expect(rebased.rows[0].cells[1].selected).toBe(false)
  expect(rebased.rows[0].targets[0].destination).toBe('exclude')
  expect(assembleReview(rebased, workspace, evidence).correction.rows[0].typeName).toBe('26A 수동')
})
it('일정 대응을 새 일정으로 바꾸면 현재값도 다시 계산한다', () => {
  const { review, workspace } = scenario('gwangju')
  const next = remapSchedule(review, 0, [], workspace)
  expect(next.cells.every(cell => cell.before === null)).toBe(true)
  expect(next.proposal.existingIds).toEqual([])
})

it('기존 일정 연결이나 날짜 근거가 불명확한 일정은 기본 반영하지 않는다', () => {
  const { review, workspace, housing } = scenario('gwangju')
  const initial = createReview(review.extraction, workspace, housing)
  const uncertain = initial.schedules.filter(schedule => schedule.proposal.needsConfirmation)
  expect(uncertain.length).toBeGreaterThan(0)
  expect(uncertain.every(schedule => !schedule.include)).toBe(true)
})

it('기존 값·보정 값·직접 입력을 바꿔도 원래 추출값과 수동 초안을 유지한다', () => {
  const { review } = scenario('hansville')
  const original = review.rows[0].cells[0]
  const edited = editCell(chooseCellValue(original, 'manual'), '26A 관리자 수정')
  const kept = chooseCellValue(edited, 'keep')
  expect(kept.selected).toBe(false)
  const extracted = chooseCellValue(kept, 'extracted')
  expect(extracted).toMatchObject({ value: '26A', selected: true, mode: 'extracted' })
  expect(extracted.extracted.value).toBe('26A')
  expect(chooseCellValue(extracted, 'manual')).toMatchObject({ value: '26A 관리자 수정', mode: 'manual' })
})
it('직접 입력 형식 오류는 저장하지 않고 기존 값 사용으로 되돌릴 수 있다', () => {
  const { review, workspace } = scenario('hansville')
  const row = review.rows[0], index = row.cells.findIndex(cell => cell.field === 'exclusiveArea')
  row.cells[index] = editCell(row.cells[index], '숫자 아님')
  expect(() => assembleReview(review, workspace, evidence)).toThrow('직접 입력값을 확인')
  row.cells[index] = chooseCellValue(row.cells[index], 'keep')
  expect(assembleReview(review, workspace, evidence).correction.rows[0].exclusiveArea).toBe(workspace.supply.rows[0].exclusiveArea)
})
it('조건의 저장 위치를 바꿔도 직접 입력 후 선택한 기존 값 유지가 풀리지 않는다', () => {
  const { review, workspace } = scenario('hansville')
  const target = review.rows[0].targets[0], index = target.cells.findIndex(cell => cell.field === 'rentalDeposit')
  target.cells[index] = chooseCellValue(editCell(target.cells[index], '9999999'), 'keep')
  const next = remapTarget(review, 0, 0, 'new', workspace)
  expect(next.cells[index]).toMatchObject({ mode: 'keep', selected: false, manualInput: '9999999' })
})
it('연결 주택형도 기존 값을 유지하거나 보정값을 선택해 저장한다', () => {
  const { review, workspace } = scenario('gwangju')
  const row = review.rows[0], index = row.cells.findIndex(cell => cell.field === 'housingTypeId')
  const existing = workspace.supply.rows.find(item => item.key === row.proposal.destination)!
  row.cells[index] = chooseCellValue(row.cells[index], 'keep')
  expect(assembleReview(review, workspace, evidence).correction.rows.find(item => item.key === existing.key)?.housingTypeId).toBe(existing.housingTypeId)
  row.cells[index] = chooseCellValue(row.cells[index], 'extracted')
  expect(assembleReview(review, workspace, evidence).correction.rows.find(item => item.key === existing.key)?.housingTypeId).toBe(row.cells[index].extracted.value)
})
it('수동 값이 기존 값과 같았어도 재조회 후 달라진 기존 값에 덮어쓰지 않는다', () => {
  const { review, workspace, housing } = scenario('gwangju')
  const row = review.rows[0], existing = workspace.supply.rows.find(item => item.key === row.proposal.destination)!
  existing.typeName = '관리자 값'
  row.cells[0] = editCell({ ...row.cells[0], before: '관리자 값' }, '관리자 값')
  expect(row.cells[0].selected).toBe(false)
  existing.typeName = '서버 최신값'
  const rebased = rebaseReview(review, workspace, housing)
  expect(rebased.rows[0].cells[0]).toMatchObject({ mode: 'manual', value: '관리자 값', selected: true })
  expect(assembleReview(rebased, workspace, evidence).correction.rows.find(item => item.key === existing.key)?.typeName).toBe('관리자 값')
})
it('직접 수정에서 주택형 미연결을 선택하면 기존 연결을 해제한다', () => {
  const { review, workspace } = scenario('gwangju')
  const row = review.rows[0], existing = workspace.supply.rows.find(item => item.key === row.proposal.destination)!
  existing.housingTypeId = row.proposal.candidates[0].id
  const index = row.cells.findIndex(cell => cell.field === 'housingTypeId')
  row.cells[index] = editCell({ ...row.cells[index], before: existing.housingTypeId }, '')
  expect(row.cells[index]).toMatchObject({ mode: 'manual', value: null, selected: true })
  expect(assembleReview(review, workspace, evidence).correction.rows.find(item => item.key === existing.key)?.housingTypeId).toBeNull()
})


it('공급행이 없는 공고는 검색해 선택한 단지를 기준으로 첫 공급행을 만든다', () => {
  const { review: seed, workspace, housing } = scenario('hansville')
  workspace.supply.rows = []
  const extraction = { ...seed.extraction, supplyRows: seed.extraction.supplyRows.slice(0, 1), sharedCounts: [] }
  const review = createReview(extraction, workspace, housing)
  expect(review.rows[0].proposal.destination).toBe('new')
  expect(() => assembleReview(review, workspace, evidence)).toThrow('기존 단지')
  review.rows[0].proposal.newComplex = { id: 7, name: '선택 단지', pnu: '1114010100100010000', category: 'RESUPPLY' }
  const result = assembleReview(review, workspace, evidence)
  expect(result.correction.rows).toEqual([expect.objectContaining({ id: null, housingComplexId: 7, housingComplexName: '선택 단지', pnu: '1114010100100010000', category: 'RESUPPLY', typeName: '26A', targets: expect.any(Array) })])
  expect(workspace.supply.rows).toEqual([])
})
it('수동으로 작성 중인 행과 조건도 PDF 대응에 사용하여 중복 추가하지 않는다', () => {
  const { review: seed, workspace, housing } = scenario('hansville')
  const extraction = { ...seed.extraction, supplyRows: seed.extraction.supplyRows.slice(0, 1), sharedCounts: [] }
  const imported = assembleReview(seed, workspace, evidence).correction.rows[0]
  imported.id = null; imported.key = 'draft-row'; imported.groupId = null
  imported.targets = imported.targets.map((target, index) => ({ ...target, id: null, key: `draft-target-${index}`, condition: '관리자가 쓴 조건' }))
  workspace.supply.rows = [imported]
  const review = createReview(extraction, workspace, housing)
  expect(review.rows[0].proposal.destination).toBe('draft-row')
  expect(review.rows[0].targets[0].destination).toBe('draft-target-0')
  review.rows[0].targets[0] = remapTarget(review, 0, 0, 'draft-target-0', workspace)
  const result = assembleReview(review, workspace, evidence).correction.rows
  expect(result).toHaveLength(1)
  expect(result[0].targets).toHaveLength(imported.targets.length)
  expect(result[0].targets[0].key).toBe('draft-target-0')
})

it('확인 필요 항목은 화면 순서로 모으고 사람이 고르거나 대응을 확인하면 사라진다', () => {
  const { review, workspace, housing } = scenario('gongju')
  expect(pendingItems(review).map(item => item.id)).toEqual(['row:0', 'target:0:0', 'target:0:1', 'schedule:2'])
  review.rows[0].reviewed = true
  review.rows[0].targets[0].cells = review.rows[0].targets[0].cells.map(cell => pendingCell(cell) ? chooseCellValue(cell, 'keep') : cell)
  review.schedules[2].reviewed = true
  expect(pendingItems(review).map(item => item.id)).toEqual(['target:0:1'])
  const deposit = review.rows[0].targets[0].cells.find(cell => cell.field === 'rentalDeposit')!
  expect(pendingCell(editCell(deposit, '1,000'))).toBe(true)
  expect(pendingCell(editCell(deposit, '1000'))).toBe(false)
  expect(rebaseReview(review, workspace, housing).rows[0].reviewed).toBe(true)
})

it('PDF 공급 표 순서와 기존 표시 순서가 다르면 PDF 순서로 다시 매기고 PDF에 없는 행은 뒤에 둔다', () => {
  const { review, workspace } = scenario('gwangju')
  const extra = { ...workspace.supply.rows[0], id: 999, key: 'row:999', displayOrder: -1, typeName: 'PDF에 없는 행' }
  workspace.supply.rows.push(extra)
  const rows = assembleReview(review, workspace, evidence).correction.rows
  const order = [...rows].sort((left, right) => left.displayOrder - right.displayOrder).map(row => row.key)
  expect(order).toEqual([...review.rows.map(row => row.key), 'row:999'])
  expect(order.indexOf('row:27')).toBeLessThan(order.indexOf('row:26'))
  expect(new Set(rows.map(row => row.displayOrder)).size).toBe(rows.length)
})

it('이미 PDF 순서와 같으면 표시 순서를 바꾸지 않는다', () => {
  const { review, workspace } = scenario('hansville')
  const rows = assembleReview(review, workspace, evidence).correction.rows
  expect(rows.map(row => row.displayOrder)).toEqual([workspace.supply.rows[0].displayOrder, workspace.supply.rows[0].displayOrder + 1])
})
