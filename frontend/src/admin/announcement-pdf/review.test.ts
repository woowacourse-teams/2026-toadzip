import { expect, it } from 'vitest'
import { scenario } from './fixtures/workspace'
import { blankTarget } from '../supply/supplyCorrectionApi'
import { assembleReview, applicableCells, editCell, rebaseReview, remapSchedule, validCellValue } from './review'


const evidence = { reason: '공고문과 대조', sourceUrl: 'https://example.com/notice', sourcePage: '4' }

it('한스빌의 공통 모집수는 묶음에 한 번만 넣고 기존 공급행 ID를 보존한다', () => {
  const { review, workspace } = scenario('hansville')
  const request = assembleReview(review, workspace, evidence)
  expect(request.correction.rows).toHaveLength(2)
  expect(request.correction.rows.map(row => row.householdCount)).toEqual([null, null])
  expect(request.correction.rows[0].id).toBe(90)
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

it('행 대응·블록 확인과 공통 모집 묶음 일부 제외를 검증한다', () => {
  const { review, workspace } = scenario('hansville')
  review.rows[0].blockConfirmed = false
  expect(() => assembleReview(review, workspace, evidence)).toThrow('블록을 확인')
  review.rows[0].blockConfirmed = true; review.rows[1].include = false
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
it('409 뒤 최신값을 비교하면서 수동 입력·제외·대응을 유지하고 재확인을 요구한다', () => {
  const { review, workspace } = scenario('hansville')
  review.rows[0].cells[0] = editCell(review.rows[0].cells[0], '26A 수동')
  review.rows[0].cells[1].selected = false
  review.rows[0].targets[0].destination = 'exclude'
  workspace.supply.rows[0].typeName = '새 저장값'
  const rebased = rebaseReview(review, workspace, {})
  expect(rebased.rows[0].cells[0]).toMatchObject({ value: '26A 수동', before: '새 저장값', selected: true })
  expect(rebased.rows[0].cells[1].selected).toBe(false)
  expect(rebased.rows[0].targets[0].destination).toBe('exclude')
  expect(rebased.rows[0].blockConfirmed).toBe(false)
  expect(() => assembleReview(rebased, workspace, evidence)).toThrow('확인')
})
it('일정 대응을 새 일정으로 바꾸면 현재값도 다시 계산한다', () => {
  const { review, workspace } = scenario('gwangju')
  const next = remapSchedule(review, 0, [], workspace)
  expect(next.cells.every(cell => cell.before === null)).toBe(true)
  expect(next.confirmed).toBe(false)
})
