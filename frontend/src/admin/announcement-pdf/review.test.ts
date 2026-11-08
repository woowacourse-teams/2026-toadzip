import { describe, expect, it } from 'vitest'
import { ghFrame, ghSameArea, scenario, separateHansville } from './fixtures/workspace'
import fixtures from './fixtures/scenarios.json'
import { readExtraction } from './extraction'
import { blankTarget } from '../supply/supplyCorrectionApi'
import { createdTypeName, foldSameHousingType, renamedTypeName } from './subtypes'
import { describeRow } from './matching'
import { areaOnlyTypeName, createRowReview, housingTypePrice, leftoverRows, linkedHousingTypeId, pendingPrice, foreignCharacters, shownDateTime, pendingChanges, assembleReview, applicableCells, chooseCellValue, createReview, editCell, pendingCell, pendingItems, rebaseReview, remapSchedule, remapTarget, rowStatus, serverFieldError, serverFieldsError, pendingSubtypes, validCellValue } from './review'


const evidence = { reason: '공고문과 대조', sourceUrl: 'https://example.com/notice', sourcePage: '4' }

it('한스빌 26A·26B가 모집수 170을 함께 적으면 공급행 하나로 접어 같은 대상은 하나로 두고, 기존 행에 모집수 170과 두 주택형을 저장한다', () => {
  const { review, workspace } = scenario('hansville')
  expect(review.rows.map(row => [row.proposal.destination, row.proposal.needsConfirmation, row.subtypes?.map(subtype => subtype.choice)])).toEqual([['row:90', false, [10353, 10352]]])
  expect(review.extraction.sharedCounts).toEqual([])
  const request = assembleReview(review, workspace, evidence)
  expect(request.correction).not.toHaveProperty('newGroups')
  expect(request.correction.rows).toEqual([expect.objectContaining({ id: 90, householdCount: 170, housingTypeId: 10353, linkedHousingTypes: [{ housingTypeId: 10352 }] })])
  expect(request.correction.rows[0].targets.map(target => target.deposit)).toEqual(['4076000'])
})

it('대상 조건이 달라도 모집수를 함께 쓰는 한스빌의 두 주택형은 공급행 하나로 접고, 다른 대상은 둘 다 남겨 기존 공급행 ID를 보존한다', () => {
  const { review, workspace } = scenario('hansville', separateHansville())
  const request = assembleReview(review, workspace, evidence)
  expect(request.correction.rows).toEqual([expect.objectContaining({ id: 90, householdCount: 170, groupId: null })])
  expect(request.correction.rows[0].targets.map(target => [target.target, target.convertedDeposit])).toEqual([['일반', '1000000'], ['일반', '2000000']])
  expect(request.document.announcement.name).toBe('기존 공고')
  expect(request.document.announcement.rentalType).toBe('NATIONAL_RENTAL')
})

it('공통 수량과 겹친 대상별 수량은 미확인으로 남겨 대상별 값으로 저장하지 않는다', () => {
  const input = structuredClone(fixtures.hansville.extraction) as unknown as { data: { supplyRows: { targets: Record<string, unknown>[] }[] }; sharedCounts: unknown[] }
  input.data.supplyRows[0].targets.push({ ...input.data.supplyRows[0].targets[0], target: '청년', reserveCount: 20 })
  input.data.supplyRows[0].targets.forEach(target => { target.reserveCount = 20 })
  input.sharedCounts = [{ paths: ['supplyRows[0].targets[0].reserveCount', 'supplyRows[0].targets[1].reserveCount'], count: 20, page: 3, quote: '대학생 청년 통합 20' }]
  const parsed = readExtraction(JSON.stringify(input))
  if (!parsed.ok) throw new Error(parsed.error)
  const { workspace, housing } = scenario('hansville')
  const review = createReview(parsed.value, workspace, housing)
  expect(review.rows[0].targets.map(target => target.cells.find(cell => cell.field === 'reserveCount')?.state)).toEqual(['unresolved', 'unresolved'])
  // 행 모집수를 함께 적지 않은 두 주택형(면적이 다름)은 접지 않는다.
  expect(review.rows).toHaveLength(2)
  review.rows[0].proposal.destination = workspace.supply.rows[0].key
  review.rows[1].include = false
  const request = assembleReview(review, workspace, evidence)
  expect(request.correction.rows.find(row => row.id === 90)?.targets.map(target => target.reserveCount)).toEqual([null, null])
  expect(review.extraction.sharedCounts[0].count).toBe(20)
})

it('지번을 모르는 단지의 공급행은 소재지 PNU 없이 저장하고 문서 PNU도 비운다', () => {
  const { review, workspace } = scenario('hansville')
  workspace.supply.rows.forEach(row => { row.pnu = '' })
  const request = assembleReview(review, workspace, evidence)
  expect(request.correction.rows.every(row => row.pnu === '')).toBe(true)
  expect(request.document.supplyRows.map(row => (row.complexReference as { pnu: unknown }).pnu)).toEqual(request.document.supplyRows.map(() => null))
})

it('어느 공급행에도 붙지 않은 LH 행이 있거나 LH 줄 수가 PDF 행 수와 달라도 확인 필요로 잡지 않고 저장을 막지 않는다', () => {
  const { review, workspace } = scenario('gwangju')
  const lh = (order: number, attachedRowKey: string | null, householdCount: number) => ({ rowIdentifier: `LH:P:SUPPLY:${order}`, complexLabel: '광주첨단H-3BL', typeName: '26', exclusiveArea: '26.97', householdCount, attachedRowKey, ambiguous: false })
  const lhSupplyRows = [lh(1, 'row:26', 7), lh(2, 'row:26', 7), lh(3, 'row:27', 9), lh(4, null, 3)]
  const withLh = { ...workspace, supply: { ...workspace.supply, lhSupplyRows, unattachedLhSupplyRows: [lhSupplyRows[3]] } }
  review.rows.forEach(row => { row.reviewed = true })
  expect(pendingItems(review, true, withLh).filter(item => item.id.startsWith('row:'))).toEqual([])
  expect(assembleReview(review, withLh, evidence).correction.rows.filter(row => !row.excluded)).toHaveLength(6)
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

it('같은 기존 행에 두 번 대응하거나 기존 조건을 임의로 제거하지 않는다', () => {
  const { review, workspace } = scenario('hansville', separateHansville())
  const old = { ...blankTarget(0), id: 33, key: 'target:33', target: '기존 특수 조건', deposit: '999999999999999999' }
  workspace.supply.rows[0].targets.push(old)
  expect(assembleReview(review, workspace, evidence).correction.rows[0].targets[0]).toEqual(old)
  const gwangju = scenario('gwangju')
  gwangju.review.rows[1].proposal.destination = gwangju.review.rows[0].proposal.destination
  expect(() => assembleReview(gwangju.review, gwangju.workspace, evidence)).toThrow(expect.objectContaining({ message: expect.stringContaining('이 같은 기존 공급정보 #22'), reviewId: 'row:1' }))
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
  review.rows[0].priceTarget = null
  workspace.supply.rows[0].typeName = '새 저장값'
  const rebased = rebaseReview(review, workspace, housing)
  // 관리자가 고른 '지금 값 유지'도 기본값('모두 같음')으로 되돌리지 않는다.
  expect(rebased.rows[0].priceTarget).toBeNull()
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
  const { review } = scenario('gongju')
  const original = review.rows[0].cells[0]
  const edited = editCell(chooseCellValue(original, 'manual'), '26 관리자 수정')
  const kept = chooseCellValue(edited, 'keep')
  expect(kept.selected).toBe(false)
  const extracted = chooseCellValue(kept, 'extracted')
  expect(extracted).toMatchObject({ value: '26', selected: true, mode: 'extracted' })
  expect(extracted.extracted.value).toBe('26')
  expect(chooseCellValue(extracted, 'manual')).toMatchObject({ value: '26 관리자 수정', mode: 'manual' })
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
it('연결 주택타입은 비워 두면 기존 연결을 유지하고, 직접 고르면 그 주택형을 저장한다', () => {
  const { review, workspace } = scenario('gwangju')
  const row = review.rows[1], index = row.cells.findIndex(cell => cell.field === 'housingTypeId')
  const existing = workspace.supply.rows.find(item => item.key === row.proposal.destination)!
  expect(row.cells[index]).toMatchObject({ value: null, state: 'notApplicable', selected: false, note: expect.stringContaining('지금 연결한 주택타입을 유지합니다') })
  const saved = () => assembleReview(review, workspace, evidence).correction.rows.find(item => item.key === existing.key)?.housingTypeId
  expect(saved()).toBe(existing.housingTypeId)
  row.cells[index] = editCell(row.cells[index], '3033')
  expect(saved()).toBe(3033)
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
it('직접 수정에서 주택타입 미연결을 선택하면 기존 연결을 해제한다', () => {
  const { review, workspace } = scenario('gwangju')
  const row = review.rows[0], existing = workspace.supply.rows.find(item => item.key === row.proposal.destination)!
  existing.housingTypeId = row.proposal.candidates[0].id
  const index = row.cells.findIndex(cell => cell.field === 'housingTypeId')
  row.cells[index] = editCell({ ...row.cells[index], before: existing.housingTypeId }, '')
  expect(row.cells[index]).toMatchObject({ mode: 'manual', value: null, selected: true })
  expect(assembleReview(review, workspace, evidence).correction.rows.find(item => item.key === existing.key)?.housingTypeId).toBeNull()
})

it('주택형이 없는 공급행은 확인 필요 없이 공고문 주택타입 이름이 같은 단지 주택타입을 정해 보낸다', () => {
  const { review, workspace } = scenario('gwangju')
  // H-1BL 39(창업인)은 주택형이 없는 마이홈 통합행(#22)을 고친다. 단지에는 같은 면적 주택형 39(3039)가 있다.
  const row = review.rows[0]
  expect(row.cells.find(cell => cell.field === 'housingTypeId')).toMatchObject({ value: null, note: expect.stringContaining('저장하면 같은 단지·전용면적') })
  row.reviewed = false
  expect(pendingItems(review, true, workspace).filter(item => rowOf(item.id) === 0)).toEqual([])
  expect(rowStatus(row, false).label).toBe('준비됨')
  const request = assembleReview(review, workspace, evidence)
  // 공고문 39(창업인)의 주택타입 이름 39와 같은 단지 주택타입 39(3039)에 잇는다.
  expect(request.correction.rows.find(item => item.key === 'row:22')).toMatchObject({ housingTypeId: 3039, newHousingType: null, exclusiveArea: 39.78, housingComplexId: 962 })
  // 공고 표기(신청형)는 단지 주택타입 이름으로 보내지 않는다.
  expect(request.housingTypeValues?.find(value => value.rowKey === 'row:22')).toMatchObject({ name: null })
  // 면적도 연결 주택타입도 없으면 서버가 정할 수 없어 주택타입 미연결로 알린다(저장은 막지 않는다).
  const area = row.cells.findIndex(cell => cell.field === 'exclusiveArea')
  row.cells[area] = editCell(row.cells[area], '')
  expect(rowStatus(row, false).label).toBe('주택타입 미연결')
})
const rowOf = (id: string) => Number(/^(?:row|cell:supplyRows\[|target|subtype|price)[:[]?(\d+)/.exec(id)?.[1] ?? -1)

it('공급행이 없는 공고는 검색해 선택한 단지를 기준으로 첫 공급행을 만든다', () => {
  const { review: seed, workspace, housing } = scenario('gongju')
  workspace.supply.rows = []
  const extraction = { ...seed.extraction, supplyRows: seed.extraction.supplyRows.slice(0, 1), sharedCounts: [] }
  const review = createReview(extraction, workspace, housing)
  expect(review.rows[0].proposal.destination).toBe('new')
  expect(() => assembleReview(review, workspace, evidence)).toThrow('단지 정하기')
  review.rows[0].proposal.newComplex = { id: 7, name: '선택 단지', pnu: '1114010100100010000', category: 'RESUPPLY' }
  const result = assembleReview(review, workspace, evidence)
  expect(result.correction.rows).toEqual([expect.objectContaining({ id: null, housingComplexId: 7, housingComplexName: '선택 단지', pnu: '1114010100100010000', category: 'RESUPPLY', typeName: '26', targets: expect.any(Array) })])
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

it('이름 없이 새로 추가할 공급대상은 확인 필요로 남고, 적용은 그 대상으로 안내하며 이름을 넣으면 풀린다', () => {
  const { review, workspace } = scenario('gongju')
  review.rows[0].reviewed = true
  review.rows[0].targets = review.rows[0].targets.map((_, at) => remapTarget(review, 0, at, 'new', workspace))
  review.rows[0].targets.forEach(target => { target.cells = target.cells.map(cell => pendingCell(cell) ? chooseCellValue(cell, 'keep') : cell) })
  expect(pendingItems(review, false).filter(item => item.id.startsWith('target:'))).toEqual([])
  const name = review.rows[0].targets[1].cells.findIndex(cell => cell.field === 'target')
  review.rows[0].targets[1].cells[name] = chooseCellValue(review.rows[0].targets[1].cells[name], 'keep')
  expect(pendingItems(review, false).map(item => item.id)).toContain('target:0:1')
  expect(() => assembleReview(review, workspace, evidence)).toThrow(expect.objectContaining({ reviewId: 'target:0:1', message: `공주옥룡1 공급행 1(${review.extraction.supplyRows[0].typeName}) 조건 2: 공급 대상 이름을 입력하세요.` }))
  review.rows[0].targets[1].cells[name] = editCell(review.rows[0].targets[1].cells[name], '   ')
  expect(pendingItems(review, false).map(item => item.id)).toContain('target:0:1')
  review.rows[0].targets[1].cells[name] = editCell(review.rows[0].targets[1].cells[name], '일반공급')
  expect(pendingItems(review, false).map(item => item.id)).not.toContain('target:0:1')
  expect(assembleReview(review, workspace, evidence).correction.rows[0].targets.map(target => target.target)).toContain('일반공급')
})

it('공급 순위는 PDF에서 가져오지 않아 확인할 칸이 없고, 기존 조건의 순위는 그대로 둔다', () => {
  const { review, workspace } = scenario('gongju')
  review.rows[0].reviewed = true
  expect(review.extraction.supplyRows[0].targets[0].supplyRank).toBe(1)
  expect(review.rows[0].targets.flatMap(target => target.cells.map(cell => cell.field))).not.toContain('supplyRank')
  const stored = { ...blankTarget(0), target: '가군 (생계·의료급여수급자 등)', supplyRank: '전체' }
  workspace.supply.rows.find(row => row.key === review.rows[0].proposal.destination)!.targets = [stored]
  review.rows[0].targets[0] = remapTarget(review, 0, 0, stored.key, workspace)
  review.rows[0].targets[1] = remapTarget(review, 0, 1, 'new', workspace)
  const targets = assembleReview(review, workspace, evidence).correction.rows.find(row => row.key === review.rows[0].proposal.destination)!.targets
  expect(targets.map(target => [target.target, target.supplyRank])).toEqual([['가군 (생계·의료급여수급자 등)', '전체'], ['나군 (일반 등)', null]])
})

it('접수처가 없던 공고에 PDF가 접수처명 없이 연락처·방식만 주면 접수처명을 확인 필요로 잡고, 입력하거나 접수처 값을 고르지 않으면 풀린다', () => {
  const extraction = structuredClone(fixtures.gongju.extraction)
  extraction.data.receptionPlace.name = null as unknown as string
  extraction.evidence = extraction.evidence.filter(item => item.path !== 'receptionPlace.name')
  const { review, workspace } = scenario('gongju', extraction)
  review.rows[0].reviewed = true
  review.schedules.forEach(schedule => { schedule.reviewed = true })
  const reception = () => review.reception.find(cell => cell.field === 'name')!
  expect(review.reception.filter(cell => cell.selected).map(cell => cell.field)).toEqual(['method', 'contact'])
  expect(pendingItems(review).map(item => item.id)).toContain('cell:receptionPlace.name')
  expect(() => assembleReview(review, workspace, evidence)).toThrow(expect.objectContaining({ reviewId: 'cell:receptionPlace.name', message: expect.stringContaining('접수처명이 필요합니다') }))
  const typed = review.reception.map(cell => cell.field === 'name' ? editCell(cell, 'LH 청약플러스') : cell)
  expect(pendingItems({ ...review, reception: typed }).map(item => item.id)).not.toContain('cell:receptionPlace.name')
  expect(assembleReview({ ...review, reception: typed }, workspace, evidence).document.receptionPlace).toMatchObject({ name: 'LH 청약플러스', method: 'VISIT', contact: '1600-1004' })
  const skipped = review.reception.map(cell => cell.selected ? chooseCellValue(cell, 'keep') : cell)
  expect(pendingItems({ ...review, reception: skipped }).map(item => item.id)).not.toContain('cell:receptionPlace.name')
  expect(assembleReview({ ...review, reception: skipped }, workspace, evidence).fields.filter(field => field.startsWith('receptionPlace.'))).toEqual([])
  expect(reception().before).toBeNull()
})

it('저장된 일시(초까지)와 PDF·입력값(분까지)이 같은 시각이면 바뀐 값으로 보지 않는다', () => {
  const extraction = structuredClone(fixtures.gongju.extraction)
  extraction.data.schedules[1] = { ...extraction.data.schedules[1], name: '당첨자 발표', startAt: '2027-02-26T00:00', endAt: '2027-02-26T00:00' }
  const { review } = scenario('gongju', extraction)
  const start = review.schedules[1].cells.find(cell => cell.field === 'startAt')!
  expect([start.before, start.value, start.state]).toEqual(['2027-02-26T00:00:00', '2027-02-26T00:00:00', 'same'])
  expect(editCell(start, '2027-02-26T00:00').state).toBe('same')
  expect(editCell(start, '2027-02-26T14:00')).toMatchObject({ value: '2027-02-26T14:00:00', state: 'changed' })
})

it('접수 시작일만 PDF 값으로 바꿔 기존 종료일보다 늦어지면 두 날짜를 확인 필요로 잡고 적용을 막는다', () => {
  const { review, workspace } = scenario('gongju')
  review.rows[0].reviewed = true
  review.schedules.forEach(schedule => { schedule.reviewed = true })
  review.metadata = review.metadata.map(cell => cell.field === 'applicationEndDate' ? { ...cell, before: '2026-10-20', selected: false, mode: 'keep' as const } : cell)
  expect(pendingItems(review).map(item => item.id)).toEqual(expect.arrayContaining(['cell:announcement.applicationStartDate', 'cell:announcement.applicationEndDate']))
  expect(() => assembleReview(review, workspace, evidence)).toThrow(expect.objectContaining({ reviewId: 'cell:announcement.applicationStartDate', message: expect.stringContaining('접수 종료일(2026-10-20)이 시작일(2026-10-27)보다 빠릅니다') }))
  review.metadata = review.metadata.map(cell => cell.field === 'applicationEndDate' ? chooseCellValue(cell, 'extracted') : cell)
  expect(pendingItems(review).map(item => item.id)).not.toContain('cell:announcement.applicationEndDate')
})

it('URL은 서버와 같이 소문자 http(s)://로 시작하고 공백이 없어야 한다', () => {
  expect(validCellValue('url', 'https://apply.lh.or.kr')).toBe(true)
  expect(validCellValue('url', 'HTTPS://apply.lh.or.kr')).toBe(false)
  expect(validCellValue('url', 'https:apply.lh.or.kr')).toBe(false)
  expect(validCellValue('url', 'apply.lh.or.kr')).toBe(false)
})

it('서버가 칸 경로로 돌려준 기본정보·접수처 검증 메시지를 화면 칸 이름과 위치로 바꾼다', () => {
  const { review } = scenario('gongju')
  expect(serverFieldError(review, 'receptionPlace.name: 필수 값입니다.')).toEqual({ message: '접수처명: 필수 값입니다.', reviewId: 'cell:receptionPlace.name' })
  expect(serverFieldError(review, 'applicationEndDate: 필수 값입니다. / receptionPlace.name: 필수 값입니다.')).toEqual({ message: '접수 종료일: 필수 값입니다.', reviewId: 'cell:announcement.applicationEndDate' })
  expect(serverFieldError(review, 'originalUrl: HTTP(S) URL이어야 합니다.')).toBeNull()
  expect(serverFieldError(review, '적용할 변경이 없습니다. 현재값과 다른 항목을 선택하세요.')).toBeNull()
})

it('세부 타입 둘이 같은 주택형을 연결하면 확인 필요로 잡고, 같은 면적이어도 다른 주택타입이면 함께 연결한다', () => {
  const { review, workspace } = scenario('hansville')
  const row = review.rows[0]
  row.reviewed = true
  // 같은 면적의 서로 다른 새 주택타입(26A·26B)은 신청형 한 행에 함께 연결한다.
  row.subtypes = row.subtypes!.map(subtype => ({ ...subtype, choice: 'new' as const, exclusiveArea: 77.7, options: [] }))
  expect(pendingSubtypes(row)).toEqual([])
  const sent = assembleReview(review, workspace, evidence).correction.rows[0]
  expect([sent.newHousingType?.name, ...(sent.linkedHousingTypes ?? []).map(link => link.newHousingType?.name)]).toEqual(['26A', '26B'])
  // 같은 기존 주택형을 두 세부 타입이 고르면 막는다.
  const typeId = row.proposal.candidates[0].id
  row.subtypes = row.subtypes.map(subtype => ({ ...subtype, choice: typeId }))
  expect(pendingSubtypes(row)).toEqual([1])
  expect(() => assembleReview(review, workspace, evidence)).toThrow(expect.objectContaining({ reviewId: 'subtype:0:1', message: expect.stringContaining('같은 주택타입을 연결') }))
  row.subtypes[1] = { ...row.subtypes[1], choice: 'none' }
  expect(pendingSubtypes(row)).toEqual([])
})

it('대상별 모집수를 합해 볼 수 있는 행에 같은 대상을 새로 겹쳐 넣어 합계가 행 모집수를 넘으면 적용 전에 막는다', () => {
  const { review, workspace } = scenario('gongju')
  const stored = workspace.supply.rows.find(row => row.key === review.rows[0].proposal.destination)!
  Object.assign(stored, { targetCountsComparable: true, householdCount: 70, targets: [{ ...blankTarget(0), id: 13, target: '기존 일반', householdCount: 70 }] })
  review.rows[0].reviewed = true
  review.rows[0].targets = review.rows[0].targets.map((_, at) => remapTarget(review, 0, at, 'new', workspace))
  review.rows[0].cells = review.rows[0].cells.map(cell => cell.field === 'totalSupplyHouseholdCount' ? chooseCellValue(cell, 'keep') : cell)
  review.rows[0].targets[0].cells = review.rows[0].targets[0].cells.map(cell => cell.field === 'supplyHouseholdCount' ? editCell(cell, '5') : cell)
  expect(() => assembleReview(review, workspace, evidence)).toThrow(expect.objectContaining({ reviewId: 'row:0', message: expect.stringContaining('대상별 모집수 합계 75세대가 행 모집수 70세대를 넘습니다') }))
})

it('서버 요청값 검증이 돌려준 공급행 경로를 화면의 공급행 이름으로 바꾸고 그 행으로 옮긴다', () => {
  const { review, workspace } = scenario('gongju')
  review.rows[0].reviewed = true
  const input = assembleReview(review, workspace, evidence)
  const at = input.correction.rows.findIndex(row => row.key === review.rows[0].proposal.destination)
  expect(at).toBeGreaterThanOrEqual(0)
  const result = serverFieldsError(review, input,
    { [`request.correction.rows[${at}].targets[1].deposit`]: '0 이상이어야 합니다', 'housingTypeValues[0].supplyArea': '숫자 형식이 맞지 않습니다' })
  expect(result.reviewId).toBe('row:0')
  expect(result.message).toContain(`공급행 1(26) targets[1].deposit: 0 이상이어야 합니다`)
  expect(result.message).toContain('housingTypeValues[0].supplyArea: 숫자 형식이 맞지 않습니다')
})

it('공급행 확인 필요 항목은 화면처럼 PDF 단지 이름별 묶음 순서로 모은다', () => {
  const { review } = scenario('gwangju')
  const names = ['A단지', 'B단지', 'A단지', 'B단지', 'A단지', 'B단지']
  review.extraction.supplyRows = review.extraction.supplyRows.map((row, index) => ({ ...row, complexName: names[index] }))
  review.rows = review.rows.map(row => ({ ...row, include: true, reviewed: false, proposal: { ...row.proposal, needsConfirmation: true } }))
  const rows = pendingItems(review).filter(item => item.id.startsWith('row:')).map(item => item.id)
  expect(rows).toEqual(['row:0', 'row:2', 'row:4', 'row:1', 'row:3', 'row:5'])
})

it('확인 필요 항목은 화면 순서로 모으고 사람이 고르거나 대응을 확인하면 사라진다', () => {
  const { review, workspace, housing } = scenario('gongju')
  // 날짜 없는 '계약안내(개별안내)'는 일정으로 저장하지 않으므로 확인 필요로 세지 않는다.
  expect(review.schedules[2]).toMatchObject({ include: false, proposal: { undated: true } })
  review.rows[0].proposal.needsConfirmation = true
  expect(pendingItems(review).map(item => item.id)).toEqual(['row:0'])
  review.rows[0].reviewed = true
  expect(pendingItems(review).map(item => item.id)).toEqual([])
  // 적용하기로 한 일정에 빈 값이 있으면 저장 전에 확인 필요로 잡는다.
  review.schedules[0] = { ...review.schedules[0], include: true, cells: review.schedules[0].cells.map(cell => cell.field === 'endAt' ? { ...cell, value: null, selected: false, before: null } : cell) }
  expect(pendingItems(review).map(item => item.id)).toEqual(['schedule:0'])
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
  // 같은 주택형이라 함께 제외하는 옛 행(#27)도 PDF에 없는 행처럼 뒤에 둔다.
  expect(order).toEqual([...review.rows.map(row => row.key), 'row:999', 'row:27'])
  expect(new Set(rows.map(row => row.displayOrder)).size).toBe(rows.length)
})

it('이미 PDF 순서와 같으면 표시 순서를 바꾸지 않는다', () => {
  const { review, workspace } = scenario('hansville', separateHansville())
  const rows = assembleReview(review, workspace, evidence).correction.rows
  expect(rows.map(row => row.displayOrder)).toEqual([workspace.supply.rows[0].displayOrder])
})

it('면적만 적힌 주택형 표기는 공고 표기로 제안하지 않는다', () => {
  expect(['26.37', '26.37㎡', '39 m2'].map(areaOnlyTypeName)).toEqual([true, true, true])
  expect(['26', '26A', '26형 고령자(주거약자용)', null].map(areaOnlyTypeName)).toEqual([false, false, false, false])
  const { review, workspace, housing } = scenario('gongju')
  const extraction = structuredClone(review.extraction)
  extraction.supplyRows[0].values.sourceHousingTypeName = '26.37'
  const cell = createReview(extraction, workspace, housing).rows[0].cells.find(item => item.field === 'sourceHousingTypeName')
  expect(cell).toMatchObject({ state: 'notApplicable', selected: false, note: '면적만 적힌 값이라 주택형 표기로 쓰지 않습니다.' })
})

it('주택형 표기가 그 행의 PDF 근거 인용에 없으면(LH 이름 24A ↔ 공급 표 24) 바꾸지 않고 확인 필요로 둔다', () => {
  const { review, workspace, housing } = scenario('gongju')
  const extraction = structuredClone(review.extraction)
  extraction.supplyRows[0].values.sourceHousingTypeName = '24A'
  extraction.evidence = [...extraction.evidence.filter(item => item.path !== 'supplyRows[0].sourceHousingTypeName'), { path: 'supplyRows[0].sourceHousingTypeName', page: 2, quote: '24 24.7200 12.6758 50' }]
  const cell = createReview(extraction, workspace, housing).rows[0].cells.find(item => item.field === 'sourceHousingTypeName')!
  expect(cell).toMatchObject({ state: 'confirm', selected: false })
  expect(cell.note).toContain("PDF 근거 인용에 '24A'가 없습니다")
  extraction.evidence = extraction.evidence.map(item => item.path === 'supplyRows[0].sourceHousingTypeName' ? { ...item, quote: '24A 24.7200 12.6758 50' } : item)
  expect(createReview(extraction, workspace, housing).rows[0].cells.find(item => item.field === 'sourceHousingTypeName')!.state).not.toBe('confirm')
  // 공급 표 전체(상위 항목)의 근거를 빌려 온 경우에는 그 행 인용이 아니므로 대조하지 않는다.
  extraction.evidence = [...extraction.evidence.filter(item => !['supplyRows[0]', 'supplyRows[0].sourceHousingTypeName'].includes(item.path)), { path: 'supplyRows', page: 2, quote: '형별 공급계획' }]
  expect(createReview(extraction, workspace, housing).rows[0].cells.find(item => item.field === 'sourceHousingTypeName')!.note).not.toContain('PDF 근거 인용에')
})

it('저장 전 변경 목록은 실제로 바뀌는 값만 영역별로 모으고 선택을 끄면 뺀다', () => {
  const { review } = scenario('gongju')
  const start = review.metadata.findIndex(cell => cell.field === 'applicationStartDate')
  review.metadata[start] = { ...review.metadata[start], before: '2026-03-03', value: '2026-03-01', selected: true, locked: false }
  let changes = pendingChanges(review)
  expect(changes.filter(change => change.section === '기본정보').map(change => [change.cell.label, change.cell.before, change.cell.value]))
    .toContainEqual(['접수 시작일', '2026-03-03', '2026-03-01'])
  expect(changes.every(change => JSON.stringify(change.cell.before) !== JSON.stringify(change.cell.value))).toBe(true)
  review.metadata[start] = { ...review.metadata[start], selected: false }
  changes = pendingChanges(review)
  expect(changes.some(change => change.cell.field === 'applicationStartDate')).toBe(false)
})

it('기존 공급행은 번호 대신 단지·표시 이름·전용면적·모집수·만든 곳으로 적고 번호는 맨 뒤에 둔다', () => {
  const { workspace } = scenario('gongju')
  const row = { ...workspace.supply.rows[0], housingComplexName: '평화주공1단지', typeName: '26', householdCount: 450, exclusiveArea: 26.37 }
  expect(describeRow(row, { rowSources: [{ rowKey: row.key, category: 'LH_ANNOUNCEMENT_SUPPLY', sourceKey: 'LH:1' }] })).toBe(`평화주공1단지 26 · 전용 26.37㎡ · 모집 450세대 · LH 공고에서 만든 행 · #${String(row.id)}`)
  expect(describeRow(row, { rowSources: [], deletableRowKeys: [row.key] })).toContain('관리자가 추가한 행')
  expect(describeRow({ ...row, householdCount: null, groupId: 3, exclusiveArea: null })).toBe(`평화주공1단지 26 · 면적 미확인 · 공통 모집 · #${String(row.id)}`)
})

it('한글 문장에 섞인 한자는 확인 필요로 표시하고 기본 반영에서 뺀다', () => {
  expect(['生계·의료급여수급자 등', '일반 등', 'H-1BL 행복주택', '청년 소득無', null].map(foreignCharacters)).toEqual(['生', '', '', '', ''])
  const { review, workspace, housing } = scenario('gongju')
  const extraction = structuredClone(review.extraction)
  extraction.supplyRows[0].targets[0].target = '生계·의료급여수급자 등'
  const cell = createReview(extraction, workspace, housing).rows[0].targets[0].cells.find(item => item.field === 'target')
  expect(cell).toMatchObject({ state: 'confirm', selected: false })
  expect(cell?.note).toContain('한자·외국 문자(生)')
})

it('PDF 주택타입이 하나뿐인 행은 공고 표기를 신청형 그대로 두고 단지 주택타입 이름만 그 주택타입으로 바꾼다', () => {
  const extraction = structuredClone(fixtures.gwangju.extraction) as { data: { supplyRows: Record<string, unknown>[] } }
  const first = extraction.data.supplyRows[0]
  extraction.data.supplyRows[0] = { ...first, subtypes: [{ name: '39A', exclusiveArea: first.exclusiveArea, commonArea: null, supplyArea: null, constructedHouseholdCount: null }] }
  const { review, workspace } = scenario('gwangju', extraction)
  const index = review.rows.findIndex(row => row.proposal.destination === 'row:22')
  expect(review.extraction.supplyRows[index].typeName).toBe(first.sourceHousingTypeName)
  expect(assembleReview(review, workspace, evidence).housingTypeValues?.find(value => value.rowKey === 'row:22')?.name).toBe('39A')
  // 공고문 한 칸에 같은 면적 주택타입 여럿(39A·39B)을 적으면 공고 신청형 행은 하나로 두고 단지 주택타입은 따로 만들어 모두 그 행에 잇는다.
  extraction.data.supplyRows[0] = { ...first, subtypes: [{ name: '39A·39B', exclusiveArea: first.exclusiveArea, commonArea: null, supplyArea: null, constructedHouseholdCount: null }] }
  const several = scenario('gwangju', extraction)
  const severalIndex = several.review.rows.findIndex(row => row.proposal.destination === 'row:22')
  expect(several.review.extraction.supplyRows[severalIndex].subtypes.map(subtype => subtype.name)).toEqual(['39A', '39B'])
  const request = assembleReview(several.review, several.workspace, evidence)
  const saved = request.correction.rows.find(row => row.key === 'row:22')!
  expect([saved.newHousingType?.name, ...(saved.linkedHousingTypes ?? []).map(link => link.newHousingType?.name)]).toEqual(['39A', '39B'])
  expect(request.housingTypeValues?.filter(value => value.rowKey === 'row:22').map(value => value.linkedNewHousingTypeName ?? 'representative'))
    .toEqual(['representative', '39B'])
})

it('세부 타입 이름이 신청형과 같으면 단지 주택타입 이름을 바꾸지 않고, 한 면적의 주택타입이 여럿이면 새로 만들 때 첫 표기를 쓴다', () => {
  const extraction = structuredClone(fixtures.gwangju.extraction) as { data: { supplyRows: Record<string, unknown>[] } }
  const first = extraction.data.supplyRows[0]
  // 모델이 세부 타입 이름을 공고 표기(신청형)로 낸 경우
  extraction.data.supplyRows[0] = { ...first, subtypes: [{ name: first.sourceHousingTypeName, exclusiveArea: first.exclusiveArea, commonArea: null, supplyArea: null, constructedHouseholdCount: null }] }
  const same = scenario('gwangju', extraction)
  expect(assembleReview(same.review, same.workspace, evidence).housingTypeValues?.find(value => value.rowKey === 'row:22')?.name ?? null).toBeNull()
  expect(renamedTypeName('59B', '059.9000B')).toBe('59B')
  expect(renamedTypeName('059.9000B', '059.9000B')).toBeNull()
  expect(renamedTypeName('59A·59A1', '059.9000A')).toBeNull()
  expect(createdTypeName('59A·59A1·59C')).toBe('59A')
})

it('같은 주택타입으로 접은 행들의 주택타입 이름이 다르면 단지 주택타입 이름을 바꾸지 않고, 같으면 그 이름으로 바꾼다', () => {
  const extraction = structuredClone(fixtures.gwangju.extraction) as { data: { supplyRows: Record<string, unknown>[] } }
  const first = extraction.data.supplyRows[0]
  const withType = (name: string) => ({ ...first, subtypes: [{ name, exclusiveArea: first.exclusiveArea, commonArea: null, supplyArea: null, constructedHouseholdCount: null }] })
  const nameOf = (names: string[]) => {
    extraction.data.supplyRows.splice(0, 1, ...names.map(withType))
    const { review } = scenario('gwangju', extraction)
    extraction.data.supplyRows.splice(0, names.length, first)
    expect(review.extraction.supplyRows.length).toBe(scenario('gwangju').review.extraction.supplyRows.length)
    // 같은 단지·같은 전용면적 행 둘이 공급행 하나로 접힌다. 접은 행의 houseTypeName이 rowTypeValues가 보낼 이름이다.
    // 앞 두 행이 공급행 하나로 접혀, 다음 행은 원래 두 번째 행(16형)이다.
    expect(review.extraction.supplyRows[1].typeName).toBe(extraction.data.supplyRows[1].sourceHousingTypeName)
    return review.extraction.supplyRows[0].values.houseTypeName ?? null
  }
  expect(nameOf(['39A', '39B'])).toBeNull()
  expect(nameOf(['39A', '39A'])).toBe('39A')
})

it('일정 일시는 자정이면 날짜만, 시각이 있으면 시:분까지 보여 준다', () => {
  expect(['2026-06-01T00:00:00', '2026-06-01T16:00:00', '2026-06-01T09:30', '2026-06-01', null].map(shownDateTime))
    .toEqual(['2026-06-01', '2026-06-01 16:00', '2026-06-01 09:30', '2026-06-01', '미확인'])
})

it('주택타입 공고 값은 신청형 표기를 이름으로 보내지 않고 고른 공급면적과 건설호수만 보내며 고르지 않은 값은 보내지 않는다', () => {
  const { review, workspace } = scenario('gwangju')
  const valueOf = (key: string) => assembleReview(review, workspace, evidence).housingTypeValues?.find(value => value.rowKey === key)
  const index = review.rows.findIndex(row => row.include && row.proposal.destination === 'row:22')
  // 대상별 금액이 달라 고르지 않은 주택타입 가격은 보내지 않는다(지금 값 유지).
  expect(valueOf('row:22')).toEqual({ rowKey: 'row:22', name: null, supplyArea: 66.6673, householdCount: null, basicDeposit: null, basicMonthlyRent: null })
  const priced = review.rows.findIndex(row => row.include && row.proposal.destination === 'row:22')
  review.rows[priced] = { ...review.rows[priced], priceTarget: 0 }
  const first = review.rows[priced].targets[0].cells
  expect(valueOf('row:22')).toMatchObject({ basicDeposit: Number(first.find(cell => cell.field === 'rentalDeposit')?.value), basicMonthlyRent: Number(first.find(cell => cell.field === 'monthlyRent')?.value) })
  expect(valueOf('row:22')?.basicDeposit).toEqual(expect.any(Number))
  review.rows[priced] = { ...review.rows[priced], priceTarget: undefined }
  expect(valueOf('row:26')?.name).toBeNull()
  const areaOnly = review.rows.findIndex(row => row.include && row.proposal.destination === 'row:24')
  const label = review.rows[areaOnly].cells.findIndex(cell => cell.field === 'sourceHousingTypeName')
  review.rows[areaOnly].cells[label] = editCell(review.rows[areaOnly].cells[label], '36.91㎡')
  expect(valueOf('row:24')?.name).toBeNull()
  const row = review.rows[index], edited = row.cells.findIndex(cell => cell.field === 'supplyArea')
  row.cells[edited] = editCell(row.cells[edited], '66.6')
  expect(valueOf('row:22')?.supplyArea).toBe(66.6)
  const name = row.cells.findIndex(cell => cell.field === 'sourceHousingTypeName')
  row.cells[name] = { ...row.cells[name], selected: false, state: 'changed' }
  expect(valueOf('row:22')?.name).toBeNull()
  const area = row.cells.findIndex(cell => cell.field === 'supplyArea')
  row.cells[area] = { ...row.cells[area], selected: false, state: 'changed' }
  expect(valueOf('row:22')).toBeUndefined()
})

it('정정공고는 PDF의 원공고 공고일로 기존 공고일을 덮지 않고, 일반 공고는 다른 공고일을 반영한다', () => {
  const { review, workspace, housing } = scenario('hansville')
  const posted = (name: string) => createReview(review.extraction, { ...workspace, detail: { ...workspace.detail, data: { ...workspace.detail.data, name, postedDate: '2026-10-10' } } }, housing)
    .metadata.find(cell => cell.field === 'postedDate')!
  expect(posted('[정정공고]익산한스빌 예비입주자 모집')).toMatchObject({ value: '2026-10-08', state: 'changed', selected: false, mode: 'keep' })
  expect(posted('[정정공고]익산한스빌 예비입주자 모집').note).toContain('원공고 공고일')
  expect(posted('익산한스빌 예비입주자 모집')).toMatchObject({ state: 'changed', selected: true, mode: 'extracted' })
  // 정정공고라도 PDF 공고일이 더 늦으면 원공고 날짜가 아니므로 반영한다.
  expect(createReview(review.extraction, { ...workspace, detail: { ...workspace.detail, data: { ...workspace.detail.data, name: '[정정공고]익산한스빌', postedDate: '2026-10-01' } } }, housing)
    .metadata.find(cell => cell.field === 'postedDate')).toMatchObject({ selected: true })
})

it('숫자 앞뒤의 0만 다른 주택형 표기는 기존 표기를 유지한다', () => {
  const { review, workspace, housing } = scenario('hansville')
  // 기존 행이 같은 주택형(전용면적)을 가리켜야 그 행을 고친다.
  const rows = workspace.supply.rows.map(row => ({ ...row, housingTypeId: 10353, typeName: `0${String(review.extraction.supplyRows[0].typeName)}` }))
  const cell = createReview(review.extraction, { ...workspace, supply: { ...workspace.supply, rows } }, housing).rows[0].cells.find(item => item.field === 'sourceHousingTypeName')!
  expect(cell).toMatchObject({ state: 'same', selected: false })
  // 대소문자처럼 숫자 밖의 표기 차이는 공고 표기로 바꾼다.
  const lower = workspace.supply.rows.map(row => ({ ...row, housingTypeId: 10353, typeName: String(review.extraction.supplyRows[0].typeName).toLowerCase() }))
  const changed = createReview(review.extraction, { ...workspace, supply: { ...workspace.supply, rows: lower } }, housing).rows[0].cells.find(item => item.field === 'sourceHousingTypeName')!
  expect(changed.state).not.toBe('same')
})

it('주택타입 가격은 저장과 같은 주택형(반영한 연결 주택타입 칸, 아니면 저장 위치의 주택형)을 기준으로 한다', () => {
  const { review, workspace } = scenario('gwangju')
  const savedType = () => assembleReview(review, workspace, evidence).correction.rows.find(row => row.key === 'row:23')!.housingTypeId
  // 저장 위치를 다시 고를 때 면적이 같은 주택형이 둘이라 연결 주택타입을 정하지 못하면, 저장은 기존 공급행의 주택형을 그대로 쓴다.
  review.rows[1] = createRowReview(review.extraction, 1, review.rows[1].proposal, workspace)
  expect(linkedHousingTypeId(review.rows[1])).toBe(3032)
  expect(savedType()).toBe(3032)
  const at = review.rows[1].cells.findIndex(cell => cell.field === 'housingTypeId')
  review.rows[1].cells[at] = editCell(review.rows[1].cells[at], '3033')
  expect(linkedHousingTypeId(review.rows[1])).toBe(3033)
  expect(savedType()).toBe(3033)
})

it('이전 PDF 보정에서 고른 주택타입 가격은 확인 필요로 남기고, 확인하지 않고 저장하면 지금 가격을 유지한다', () => {
  const { review, workspace } = scenario('hansville')
  const row = review.rows[0]
  row.proposal = { ...row.proposal, candidates: row.proposal.candidates.map(type => type.id === 10353 ? { ...type, basicDeposit: 1_000_000, basicMonthlyRent: 10_000, rentalConditionSource: 'ANNOUNCEMENT' as const } : type) }
  expect(pendingPrice(row)).toBe(true)
  expect(pendingItems(review).map(item => item.id)).toContain('price:0')
  const price = () => assembleReview(review, workspace, evidence).housingTypeValues?.find(value => value.rowKey === row.key)
  expect(price()?.basicDeposit ?? null).toBeNull()
  // PDF로 공급정보 편집표만 채울 때는 주택형 값을 저장하지 않으므로 가격 확인을 남기지 않는다.
  expect(pendingItems(review, false).map(item => item.id)).not.toContain('price:0')
  row.priceTarget = 0
  expect(pendingPrice(row)).toBe(false)
  expect(price()?.basicDeposit).toEqual(expect.any(Number))
})

it('409 뒤 저장 위치의 주택형이 바뀌어도 공고문 주택타입으로 정한 연결 주택형이 같으면 가격 대상을 유지하고, 연결 주택형을 직접 바꾸면 푼다', () => {
  const { review, workspace, housing } = scenario('gwangju')
  review.rows[1].priceTarget = 0
  expect(rebaseReview(review, workspace, housing).rows[1].priceTarget).toBe(0)
  // 저장할 곳이 다른 주택형(26, 3033)에 이어져 있어도 이 행은 공고문 주택타입 16의 단지 주택타입에 다시 잇는다.
  const moved = { ...workspace, supply: { ...workspace.supply, rows: workspace.supply.rows.map(row => row.key === 'row:23' ? { ...row, housingTypeId: 3033 } : row) } }
  const rebased = rebaseReview(review, moved, housing).rows[1]
  expect(rebased.priceTarget).toBe(0)
  expect(rebased.relinkTo).toEqual(expect.any(Number))
})

describe('주택형 기본 임대조건으로 쓸 공급대상', () => {
  const cell = (field: string, value: unknown) => ({ field, value, before: null, mode: 'extracted' })
  // 화면의 금액 칸은 숫자 문자열이다.
  const target = (name: string, deposit: number, rent: number, destination = 'new') => ({ destination, cells: [cell('target', name), cell('rentalDeposit', String(deposit)), cell('monthlyRent', String(rent))] })
  const row = (targets: unknown[], priceTarget?: number | null) => ({ targets, ...(priceTarget !== undefined ? { priceTarget } : {}) }) as unknown as Parameters<typeof housingTypePrice>[0]

  it('대상 금액이 모두 같으면 그 금액을, 다르면 일반 대상을 고르고, 일반도 없으면 지금 값을 유지한다', () => {
    expect(housingTypePrice(row([target('대학생', 12_308_000, 76_920), target('청년 소득X', 12_308_000, 76_920)]))).toMatchObject({ chosen: 0, same: true })
    expect(housingTypePrice(row([target('고령자', 7_500_000, 70_000), target('일반', 8_100_000, 156_230)]))).toMatchObject({ chosen: 1, same: false })
    expect(housingTypePrice(row([target('가군', 2_395_000, 47_630), target('나군', 9_095_000, 98_630)]))).toMatchObject({ chosen: null, same: false })
    // 반영하지 않는 대상과 금액이 없는 대상은 고르지 않는다.
    expect(housingTypePrice(row([target('가군', 2_395_000, 47_630, 'exclude'), target('나군', 9_095_000, 98_630)])).options.map(option => option.label)).toEqual(['나군'])
  })

  it('금액을 읽지 못한 대상이 있으면 모두 같다고 보지 않고, 지금 값 유지로 둔 칸은 현재 금액을 쓴다', () => {
    const missing = { destination: 'new', cells: [cell('target', '일반'), cell('rentalDeposit', null), cell('monthlyRent', null)] }
    expect(housingTypePrice(row([missing, target('청년', 8_100_000, 156_230)]))).toMatchObject({ chosen: null, same: false })
    const kept = { destination: '7', cells: [cell('target', '일반'), { field: 'rentalDeposit', value: '8100000', before: '7500000', mode: 'keep' }, { field: 'monthlyRent', value: '156230', before: '150000', mode: 'keep' }] }
    expect(housingTypePrice(row([kept])).options).toEqual([{ index: 0, label: '일반', deposit: 7_500_000, monthlyRent: 150_000 }])
  })

  it('지금 값이 이전 PDF 보정에서 고른 공고문 값이면 바꾸기 전에 확인 필요로 두고 기본은 유지한다', () => {
    const announced = { id: 1, name: '26', exclusiveArea: 26, supplyArea: null, basicDeposit: 7_000_000, basicMonthlyRent: 140_000, rentalConditionSource: 'ANNOUNCEMENT' as const }
    const same = row([target('대학생', 12_308_000, 76_920), target('청년', 12_308_000, 76_920)])
    expect(housingTypePrice(same, announced)).toMatchObject({ chosen: null, confirm: true })
    expect(housingTypePrice(same, { ...announced, rentalConditionSource: 'MYHOME' })).toMatchObject({ chosen: 0, confirm: false })
    expect(housingTypePrice(same, { ...announced, basicDeposit: 12_308_000, basicMonthlyRent: 76_920 })).toMatchObject({ chosen: 0, confirm: false })
    // 관리자가 대상이나 유지를 고르면 확인을 마친 것으로 본다.
    expect(housingTypePrice({ ...same, priceTarget: 0 }, announced)).toMatchObject({ chosen: 0, confirm: false })
    expect(housingTypePrice({ ...same, priceTarget: null }, announced)).toMatchObject({ chosen: null, confirm: false })
    // 연결 주택타입이 후보에 없어 지금 값을 모르면 확인 없이 덮지 않도록 기본은 유지한다.
    expect(housingTypePrice(same, null)).toMatchObject({ chosen: null, unknown: true })
    expect(housingTypePrice(same)).toMatchObject({ chosen: 0, unknown: false })
  })

  it('관리자가 고른 대상이나 유지를 따른다', () => {
    const targets = [target('가군', 2_395_000, 47_630), target('나군', 9_095_000, 98_630)]
    expect(housingTypePrice(row(targets, 1)).chosen).toBe(1)
    expect(housingTypePrice(row([target('고령자', 7_500_000, 70_000), target('일반', 8_100_000, 156_230)], null)).chosen).toBeNull()
    expect(housingTypePrice(row(targets, 5)).chosen).toBeNull()
    // 금액이 모두 같아도 관리자가 지금 값 유지를 고를 수 있다.
    expect(housingTypePrice(row([target('대학생', 12_308_000, 76_920), target('청년', 12_308_000, 76_920)], null)).chosen).toBeNull()
  })
})

it('조회한 함께 모집하는 주택타입을 그대로 둔 행이라도 PDF가 대표 주택타입을 바꾸면 그 목록을 보내지 않는다', () => {
  const { workspace, housing } = scenario('gongju')
  workspace.supply.rows[0] = { ...workspace.supply.rows[0], housingTypeId: 8577, linkedHousingTypes: [{ housingTypeId: 8576 }] }
  const extracted = readExtraction(JSON.stringify(fixtures.gongju.extraction))
  if (!extracted.ok) throw new Error(extracted.error)
  const review = createReview(extracted.value, workspace, housing)
  const position = review.rows[0].cells.findIndex(cell => cell.field === 'housingTypeId')
  review.rows[0].cells[position] = editCell(review.rows[0].cells[position], '8575')
  const sent = JSON.parse(JSON.stringify(assembleReview(review, workspace, evidence).correction.rows[0])) as Record<string, unknown>
  expect(sent).toMatchObject({ housingTypeId: 8575 })
  expect(sent).not.toHaveProperty('linkedHousingTypes')
})

describe('공급행은 단지 × 전용면적 주택형 하나', () => {
  type Input = { data: { supplyRows: Record<string, unknown>[] }; sharedCounts: unknown[] }
  const target = (name: string, deposit = 10_000_000) => ({ target: name, supplyRank: null, supplyHouseholdCount: null, reserveCount: null, rentalDeposit: deposit, monthlyRent: 100000, convertedDeposit: null })
  const pdfRow = (complexName: string, typeName: string, exclusiveArea: number | null, count: number | null, targets: unknown[]) => ({ complexReference: { sourceComplexName: complexName },
    sourceHousingTypeName: typeName, expectedMoveInMonth: null, totalSupplyHouseholdCount: count, exclusiveArea, supplyArea: null, targets })
  function extract(rows: Record<string, unknown>[], sharedCounts: unknown[] = []) {
    const parsed = readExtraction(JSON.stringify({ data: { announcement: {}, receptionPlace: {}, supplyRows: rows, schedules: [], unresolvedFields: [] }, sharedCounts, notApplicable: [],
      evidence: rows.map((row, index) => ({ path: `supplyRows[${index}]`, page: 3, quote: `${String(row.sourceHousingTypeName)} ${String(row.exclusiveArea)}` })) }))
    if (!parsed.ok) throw new Error(parsed.error)
    return parsed.value
  }
  function hansville(edit: (input: Input) => void = () => {}) {
    const input = structuredClone(fixtures.hansville.extraction) as unknown as Input
    edit(input)
    const parsed = readExtraction(JSON.stringify(input))
    if (!parsed.ok) throw new Error(parsed.error)
    return parsed.value
  }

  it('저장할 곳이 같은 면적의 다른 주택타입(55C-1)에 이어져 있으면 다시 보정할 때 공고문 이름(55D-1)으로 새로 만들어 다시 잇고, 55C-1 행은 그대로 둔다', () => {
    const { workspace, housing } = scenario('gwangju')
    // H-3BL의 36 행(#28)을 36C-1로 두고, 같은 면적 주택타입(36C-1)에 잘못 이어진 두 번째 행(#29, 36D-1)을 둔다.
    const base = workspace.supply.rows.find(row => row.key === 'row:28')!
    const complexId = base.housingComplexId!, typeId = housing[complexId].find(type => type.name === '36')!.id
    workspace.supply.rows = [...workspace.supply.rows.map(row => row.key === base.key ? { ...row, typeName: '36C-1', housingTypeId: typeId, exclusiveArea: 36.72 } : row),
      { ...base, id: 29, key: 'row:29', typeName: '36D-1', housingTypeId: typeId, exclusiveArea: 36.72, displayOrder: base.displayOrder + 1 }]
    housing[complexId] = housing[complexId].map(type => type.id === typeId ? { ...type, name: '36C-1' } : type)
    const review = createReview(extract([pdfRow('첨단H-3BL', '36C-1', 36.72, 10, [target('신혼부부')]), pdfRow('첨단H-3BL', '36D-1', 36.72, 20, [target('청년')])]), workspace, housing)
    expect(review.rows.map(row => row.proposal.destination)).toEqual([base.key, 'row:29'])
    expect(review.rows[1].relinkTo).toBe('new')
    const rows = assembleReview(review, workspace, evidence).correction.rows
    expect(rows.find(row => row.key === base.key)).toMatchObject({ housingTypeId: typeId, newHousingType: null })
    expect(rows.find(row => row.key === 'row:29')).toMatchObject({ housingTypeId: null, newHousingType: { name: '36D-1', exclusiveArea: 36.72 } })
  })

  it('공고문이 같은 면적에 따로 적은 주택타입(36A·36B)은 이름 형식만 다른 단지 주택타입(36)에 함께 잇지 않고 이름별로 새로 만든다', () => {
    const { workspace, housing } = scenario('gwangju')
    const review = createReview(extract([pdfRow('첨단H-3BL', '36A', 36.72, 10, [target('신혼부부')]), pdfRow('첨단H-3BL', '36B', 36.72, 20, [target('청년')])]), workspace, housing)
    const rows = assembleReview(review, workspace, evidence).correction.rows.filter(row => ['36A', '36B'].includes(row.typeName))
    expect(rows.map(row => [row.typeName, row.housingTypeId, row.newHousingType?.name ?? null])).toEqual([['36A', null, '36A'], ['36B', null, '36B']])
  })

  it('면적이 같은 공고문 주택타입이 하나뿐이면 이름 형식만 다른 단지 주택타입(36)을 같은 집으로 보고 잇는다', () => {
    const { workspace, housing } = scenario('gwangju')
    const review = createReview(extract([pdfRow('첨단H-3BL', '36A', 36.72, 10, [target('신혼부부')])]), workspace, housing)
    const row = assembleReview(review, workspace, evidence).correction.rows.find(item => item.typeName === '36A')!
    expect(row.newHousingType).toBeNull()
    expect(housing[row.housingComplexId!].find(type => type.id === row.housingTypeId)?.name).toBe('36')
  })

  it('같은 면적에 신청자 구분마다 적은 PDF 행(36A형(고령자)·(신혼부부·한부모가족)·(청년))은 대상이 셋인 공급행 하나가 되고 모집수는 그 합이다', () => {
    const { workspace, housing } = scenario('gwangju')
    const extraction = extract([pdfRow('첨단H-3BL', '36A형(고령자)', 36.72, 10, [target('고령자')]), pdfRow('첨단H-3BL', '36A형(신혼부부·한부모가족)', 36.72, 20, [target('신혼부부·한부모가족')]),
      pdfRow('첨단H-3BL', '36A형(청년)', 36.72, 30, [target('청년')])])
    const review = createReview(extraction, workspace, housing)
    expect(review.rows).toHaveLength(1)
    expect(review.extraction.supplyRows[0]).toMatchObject({ typeName: '36A형', subtypes: [], values: { totalSupplyHouseholdCount: 60, exclusiveArea: 36.72 } })
    expect(review.rows[0].proposal).toMatchObject({ destination: 'row:28', needsConfirmation: false })
    // 대상마다 근거 경로를 옮겨 PDF 근거를 그대로 볼 수 있다.
    expect(review.rows[0].targets.map(item => item.cells.find(cell => cell.field === 'target')?.value)).toEqual(['고령자', '신혼부부·한부모가족', '청년'])
    expect(review.rows[0].targets.map(item => item.cells[0].evidence?.quote)).toEqual(['36A형(고령자) 36.72', '36A형(신혼부부·한부모가족) 36.72', '36A형(청년) 36.72'])
    const saved = assembleReview(review, workspace, evidence).correction.rows.find(row => row.key === 'row:28')!
    expect(saved).toMatchObject({ householdCount: 60, housingTypeId: 3037 })
    expect(saved.targets.map(item => item.target)).toEqual(['고령자', '신혼부부·한부모가족', '청년'])
  })

  it('PDF가 모집수 하나로만 적은 26A/B/C(고령자) 4세대는 면적 셋의 공급행 하나로 접어 모집수를 나누지 않고, 대표 주택타입과 함께 모집하는 주택타입으로 저장한다', () => {
    const { workspace, housing } = scenario('gwangju')
    const extraction = extract([pdfRow('첨단H-3BL', '26A(고령자)', 26.01, null, [target('고령자')]), pdfRow('첨단H-3BL', '26B(고령자)', 26.1, null, [target('고령자')]),
      pdfRow('첨단H-3BL', '26C(고령자)', 26.7, null, [target('고령자')])],
    [{ paths: [0, 1, 2].map(index => `supplyRows[${index}].totalSupplyHouseholdCount`), count: 4, page: 3, quote: '26A/B/C 4' }])
    const review = createReview(extraction, workspace, housing)
    expect(review.extraction.sharedCounts).toEqual([])
    expect(review.extraction.supplyRows[0]).toMatchObject({ typeName: '26A·26B·26C', values: { totalSupplyHouseholdCount: 4 } })
    expect(review.rows[0].subtypes?.map(subtype => [subtype.name, subtype.exclusiveArea, subtype.choice])).toEqual([['26A', 26.01, 'new'], ['26B', 26.1, 'new'], ['26C', 26.7, 'new']])
    expect(review.rows[0].targets).toHaveLength(1)
    expect(pendingItems(review, true, workspace).filter(item => item.id.startsWith('subtype:') || item.id === 'row:0')).toEqual([])
    // 접은 표기(26A·26B·26C)는 표기마다 그 행의 근거 인용에 있어 확인 필요로 두지 않는다.
    expect(review.rows[0].cells.find(cell => cell.field === 'sourceHousingTypeName')).toMatchObject({ state: 'new', value: '26A·26B·26C' })
    const saved = assembleReview(review, workspace, evidence).correction.rows.find(row => row.id === null)!
    expect(saved).toMatchObject({ householdCount: 4, housingComplexId: 961, housingTypeId: null, newHousingType: { name: '26A', exclusiveArea: 26.01 },
      linkedHousingTypes: [{ newHousingType: { name: '26B', exclusiveArea: 26.1, supplyArea: null } }, { newHousingType: { name: '26C', exclusiveArea: 26.7, supplyArea: null } }] })
  })

  it('입주 예정월을 일까지 적은 공고문 값(2020.10.19)은 연월(2020-10)로 읽는다', () => {
    const { workspace, housing } = scenario('gongju')
    workspace.supply.rows[0].exclusiveArea = 26.37
    const row = { ...pdfRow('', '26', 26.37, 5, [target('일반')]), expectedMoveInMonth: '2020.10.19' }
    const cell = createReview(extract([row]), workspace, housing).rows[0].cells.find(item => item.field === 'expectedMoveInMonth')!
    expect(cell).toMatchObject({ value: '2020-10' })
    expect(cell.state).not.toBe('invalid')
  })

  it('소득 유무 표기가 다른 대상(청년(소득無))은 통일한 표기(청년(소득 없음))로 저장한다', () => {
    const { workspace, housing } = scenario('gongju')
    workspace.supply.rows[0].exclusiveArea = 26.37
    const review = createReview(extract([pdfRow('', '26', 26.37, 5, [target('청년(소득無)')])]), workspace, housing)
    const saved = assembleReview(review, workspace, evidence).correction.rows.find(row => row.key === workspace.supply.rows[0].key)!
    expect(saved.targets.map(item => item.target)).toContain('청년(소득 없음)')
  })

  it('공급 표에 단지명이 없어 PDF 단지명이 비어도 공고 단지가 하나뿐이면 그 단지의 같은 면적 공급행을 고친다', () => {
    const { workspace, housing } = scenario('gongju')
    workspace.supply.rows[0].exclusiveArea = 26.37
    const review = createReview(extract([pdfRow('', '26', 26.37, null, [target('')])]), workspace, housing)
    expect(review.rows[0].proposal).toMatchObject({ destination: workspace.supply.rows[0].key, needsConfirmation: false })
  })

  it('같은 면적이어도 신청형이 다르면(26A·26B(주거약자)) 접지 않고, 신청자 표기만 다른 같은 신청형(26A·26A(고령자))만 공급행 하나로 접는다', () => {
    const folded = foldSameHousingType(extract([pdfRow('A단지', '26A', 26.0100, 3, [target('일반')]), pdfRow('A단지', '26B(주거약자)', 26.01, 2, [target('주거약자')]),
      pdfRow('A단지', '26A [고령자]', 26.01, 4, [target('고령자')]), pdfRow('B단지', '26A', 26.01, 5, [target('일반')])]))
    expect(folded.supplyRows.map(row => [row.complexName, row.typeName, row.subtypes.length, row.values.exclusiveArea, row.values.totalSupplyHouseholdCount, row.targets.length]))
      .toEqual([['A단지', '26A', 0, 26.01, 7, 2], ['A단지', '26B(주거약자)', 0, 26.01, 2, 1], ['B단지', '26A', 0, 26.01, 5, 1]])
  })

  it('같은 주택형을 신청자 구분마다 나눠 저장한 옛 공급행은 앞 행만 고치고 나머지는 안내와 함께 제외하며, 직접 추가한 행이면 지운다', () => {
    const { review, workspace } = scenario('gwangju')
    const merged = review.rows.findIndex(row => row.proposal.destination === 'row:26')
    expect(review.rows[merged].proposal.supersedes).toEqual(['row:27'])
    expect(pendingItems(review, true, workspace).map(item => item.id)).not.toContain(`row:${merged}`)
    expect(assembleReview(review, workspace, evidence).correction.rows.find(row => row.key === 'row:27')).toMatchObject({ excluded: true })
    const deletable = { ...workspace, supply: { ...workspace.supply, deletableRowKeys: ['row:27'] } }
    const request = assembleReview(review, deletable, evidence)
    expect(request.correction.deletedRowKeys).toEqual(['row:27'])
    expect(request.correction.rows.some(row => row.key === 'row:27')).toBe(false)
    // 함께 제외할 행을 관리자가 이 PDF 행에서 빼면 그 행은 그대로 둔다.
    review.rows[merged].include = false
    expect(assembleReview(review, workspace, evidence).correction.rows.find(row => row.key === 'row:27')).toMatchObject({ excluded: false })
  })

  it('옛 공통 모집 묶음의 행이 모두 공급행 하나에 합쳐지면 묶음에서 빼고 PDF 모집수를 그 행에 두며, 묶음 일부만 합쳐지면 묶음을 그대로 둔다', () => {
    const { workspace, housing, review: base } = scenario('gwangju')
    const grouped = { ...workspace, supply: { ...workspace.supply, rows: workspace.supply.rows.map(row => ['row:26', 'row:27'].includes(row.key)
      ? { ...row, groupId: 5, sharedHouseholdCount: 9, householdCount: null } : row) } }
    const review = createReview(base.extraction, grouped, housing)
    const merged = review.rows.findIndex(row => row.proposal.destination === 'row:26')
    const pdfCount = review.extraction.supplyRows[merged].values.totalSupplyHouseholdCount
    const rows = assembleReview(review, grouped, evidence).correction.rows
    expect(rows.find(row => row.key === 'row:26')).toMatchObject({ groupId: null, sharedHouseholdCount: null, householdCount: pdfCount })
    expect(rows.find(row => row.key === 'row:27')).toMatchObject({ groupId: 5, excluded: true })
    // 묶음의 다른 행이 이 공급행에 합쳐지지 않으면 묶음 모집수를 바꾸지 않는다.
    const partial = { ...grouped, supply: { ...grouped.supply, rows: [...grouped.supply.rows, { ...grouped.supply.rows.find(row => row.key === 'row:27')!,
      id: 999, key: 'row:999', typeName: '84', exclusiveArea: 84.99, housingTypeId: null }] } }
    partial.supply.rows = partial.supply.rows.map(row => row.key === 'row:999' ? { ...row, groupId: 5 } : row)
    const kept = createReview(base.extraction, partial, housing)
    const at = kept.rows.findIndex(row => row.proposal.destination === 'row:26')
    expect(assembleReview(kept, partial, evidence).correction.rows.find(row => row.key === 'row:26')).toMatchObject({ groupId: 5, householdCount: null })
    expect(kept.rows[at].cells.find(cell => cell.field === 'totalSupplyHouseholdCount')).toMatchObject({ locked: true })
  })

  it('PDF 행이 가리키지 않아 남는 기존 공급행은 알려 주고, 관리자가 고르면 이번 보정에서 제외하거나 지운다', () => {
    const { workspace, housing } = scenario('gongju')
    workspace.supply.rows.push({ ...workspace.supply.rows[0], id: 132, key: 'row:132', typeName: '공공임대', displayOrder: 2 })
    const review = createReview(scenario('gongju').review.extraction, workspace, housing)
    // 전용면적을 몰라 PDF 행과 맞추지 못한 통합행이 둘이면 어느 쪽도 고르지 않고 새로 추가한다.
    expect(review.rows[0].proposal.destination).toBe('new')
    expect(leftoverRows(review, workspace).map(row => row.key)).toEqual(['row:131', 'row:132'])
    const excluded = { ...review, excludedRows: ['row:131', 'row:132'] }
    const request = assembleReview(excluded, { ...workspace, supply: { ...workspace.supply, deletableRowKeys: ['row:132'] } }, evidence)
    expect(request.correction.rows.find(row => row.key === 'row:131')).toMatchObject({ excluded: true })
    expect(request.correction.deletedRowKeys).toEqual(['row:132'])
  })

  it('행을 접을 때 대상 하나로 합쳐지는 공통 예비자 모집수를 대상 필드에 보존한다', () => {
    const review = createReview(hansville(input => {
      input.sharedCounts = [...input.sharedCounts, { paths: ['supplyRows[0].targets[0].reserveCount', 'supplyRows[1].targets[0].reserveCount'], count: 20, page: 4, quote: '26A·26B 예비자 20' }]
    }), scenario('hansville').workspace, scenario('hansville').housing)
    expect(review.extraction.sharedCounts).toEqual([])
    expect(review.extraction.supplyRows[0].targets[0].reserveCount).toBe(20)
    expect(review.extraction.unresolved).toEqual([])
  })

  it('접은 대상의 기존 예비자 수가 공통 수량과 충돌하면 값을 비우고 확인 사유를 남긴다', () => {
    const extraction = hansville()
    extraction.sharedCounts = [...extraction.sharedCounts, { paths: ['supplyRows[0].targets[0].reserveCount', 'supplyRows[1].targets[0].reserveCount'], count: 20, page: 4, quote: '26A·26B 예비자 20' }]
    extraction.supplyRows.forEach(row => { row.targets[0].reserveCount = 5 })
    const folded = foldSameHousingType(extraction)
    expect(folded.supplyRows[0].targets[0].reserveCount).toBeNull()
    expect(folded.unresolved).toEqual([expect.objectContaining({ path: 'supplyRows[0].targets[0].reserveCount', reason: expect.stringContaining('그 대상의 값 10과 다릅니다') })])
  })

  it('접은 행은 같은 대상의 대상별 모집수·예비자 수를 더하고, 공통 모집수가 없으면 행 모집수를 더한다', () => {
    const folded = foldSameHousingType(hansville(input => {
      input.sharedCounts = []
      input.data.supplyRows.forEach((row, at) => { row.totalSupplyHouseholdCount = [100, 70][at]; Object.assign((row.targets as Record<string, unknown>[])[0], { supplyHouseholdCount: [90, 60][at], reserveCount: [10, 10][at] }) })
      // 두 행을 신청자 표기만 다른 같은 신청형으로 둬 행 모집수 공통값 없이 신청형으로 접는다.
      input.data.supplyRows[1].exclusiveArea = input.data.supplyRows[0].exclusiveArea
      input.data.supplyRows[1].sourceHousingTypeName = `${String(input.data.supplyRows[0].sourceHousingTypeName)}(고령자)`
    }))
    expect(folded.supplyRows.map(row => [row.values.totalSupplyHouseholdCount, row.targets[0].supplyHouseholdCount, row.targets[0].reserveCount])).toEqual([[170, 150, 20]])
  })

  it('저장 위치의 지금 주택형이 두 번째 세부 타입이면 그 주택형을 대표로 유지한다', () => {
    const { workspace, housing } = scenario('hansville')
    workspace.supply.rows[0].housingTypeId = 10352
    const review = createReview(hansville(), workspace, housing)
    expect(linkedHousingTypeId(review.rows[0])).toBe(10352)
    expect(assembleReview(review, workspace, evidence).correction.rows[0]).toMatchObject({ housingTypeId: 10352, linkedHousingTypes: [{ housingTypeId: 10353 }] })
  })

  it('세부 타입 주택형 이름은 관리자가 공급행의 주택형 표기를 받아들였을 때만 보낸다', () => {
    const { review, workspace } = scenario('hansville')
    const label = review.rows[0].cells.findIndex(cell => cell.field === 'sourceHousingTypeName')
    // 접은 표기(26A·26B)는 각 표기가 근거 인용에 있어 기본으로 받아들이고, 기존 표기를 고르면 세부 타입 이름을 보내지 않는다.
    expect(review.rows[0].cells[label].state).not.toBe('confirm')
    expect(assembleReview(review, workspace, evidence).housingTypeValues?.map(value => value.name)).toEqual(['26A', '26B'])
    review.rows[0].cells[label] = chooseCellValue(review.rows[0].cells[label], 'keep')
    expect(assembleReview(review, workspace, evidence).housingTypeValues?.map(value => value.name)).toEqual([null, null])
  })

  it('같은 면적 주택형이 없는 세부 타입은 새 주택형으로 연결하고, 다시 맞출 때 같은 면적 주택형이 생기면 새 주택타입 선택을 버린다', () => {
    const only26A = { 3055: fixtures.hansville.complexes[0].housingTypes.filter(type => type.id === 10353) }
    const { workspace, housing } = scenario('hansville')
    const review = createReview(hansville(), workspace, only26A)
    expect(review.rows[0].subtypes?.map(subtype => subtype.choice)).toEqual([10353, 'new'])
    const request = assembleReview(review, workspace, evidence)
    expect(request.correction.rows[0].linkedHousingTypes).toEqual([{ newHousingType: { name: '26B', exclusiveArea: 26.4673, supplyArea: 40.5686 } }])
    expect(request.housingTypeValues?.[1]).toMatchObject({ linkedNewHousingTypeArea: 26.4673 })
    expect(rebaseReview(review, workspace, only26A).rows[0].subtypes?.map(subtype => subtype.choice)).toEqual([10353, 'new'])
    expect(rebaseReview(review, workspace, housing).rows[0].subtypes?.map(subtype => subtype.choice)).toEqual([10353, 10352])
  })

  it('단지가 다른 공급행끼리 함께 쓰는 모집수는 새 공통 모집 묶음을 만들지 않고 행 모집수로 정하지 않으며, 직접 입력하면 그 값을 저장한다', () => {
    const { workspace, housing } = scenario('gwangju')
    const extraction = extract([pdfRow('첨단H-2BL', '16', 16.8, null, [target('대학생')]), pdfRow('첨단H-3BL', '16', 16.8, null, [target('대학생')])],
      [{ paths: ['supplyRows[0].totalSupplyHouseholdCount', 'supplyRows[1].totalSupplyHouseholdCount'], count: 80, page: 3, quote: '16 80' }])
    const review = createReview(extraction, workspace, housing)
    expect(review.rows.map(row => [row.proposal.destination, row.proposal.sharedIndex])).toEqual([['row:23', 0], ['row:25', 0]])
    const count = review.rows[0].cells.findIndex(cell => cell.field === 'totalSupplyHouseholdCount')
    expect(review.rows[0].cells[count]).toMatchObject({ locked: false, selected: false, note: expect.stringContaining('다른 단지의 공급행과 합쳐 적어') })
    expect(pendingItems(review, true, workspace).map(item => item.id)).not.toContain('cell:supplyRows[0].totalSupplyHouseholdCount')
    review.rows[0].cells[count] = editCell(review.rows[0].cells[count], '50')
    const request = assembleReview(review, workspace, evidence)
    expect(request.correction).not.toHaveProperty('newGroups')
    expect(request.correction.rows.filter(row => ['row:23', 'row:25'].includes(row.key)).map(row => [row.key, row.householdCount])).toEqual([['row:23', 50], ['row:25', 50]])
  })
})


it('미확정 공고의 필수 일정·유형이 비면 확인 필요로 표시하고 서버 요청 전에 해당 입력을 안내한다', () => {
  const { review, workspace } = scenario('hansville')
  workspace.detail.summary.announcement = { ...workspace.detail.summary.announcement!, releaseStatus: 'UNCONFIRMED' }
  workspace.detail.data.rentalType = null
  workspace.detail.data.recruitmentType = null
  review.metadata = review.metadata.map(cell => ['applicationStartDate', 'applicationEndDate', 'winnerAnnouncementDate'].includes(cell.field)
    ? { ...cell, before: null, selected: false } : cell)
  const release = { rentalType: '', recruitmentType: '', publish: false }
  expect(pendingItems(review, true, workspace, release).map(item => item.id)).toEqual(expect.arrayContaining([
    'cell:announcement.applicationStartDate', 'cell:announcement.applicationEndDate', 'cell:announcement.winnerAnnouncementDate',
    'release:rentalType', 'release:recruitmentType',
  ]))
  expect(() => assembleReview(review, workspace, evidence, release)).toThrow(/접수 시작일/)
})

it('단지를 정하지 않은 새 공급행은 대응을 확인해도 저장이 막히므로 단지를 정할 때까지 확인 필요로 남는다', () => {
  const { review, workspace } = scenario('gwangju')
  review.rows[0] = { ...review.rows[0], reviewed: true, proposal: { ...review.rows[0].proposal, destination: 'new', anchorKey: '', newComplex: undefined } }
  expect(pendingItems(review, true, workspace).map(item => item.id)).toContain('row:0')
  expect(() => assembleReview(review, workspace, { reason: '보정', sourceUrl: 'https://example.com', sourcePage: null })).toThrow(expect.objectContaining({ reviewId: 'row:0' }))
  review.rows[0].proposal.newComplex = { id: 962, name: '광주첨단H-1BL', pnu: '1233011600106570004', category: 'RESUPPLY' } as NonNullable<typeof review.rows[0]['proposal']['newComplex']>
  expect(pendingItems(review, true, workspace).map(item => item.id)).not.toContain('row:0')
})

it('단지가 비어 있는 기존 공급행도 단지 정하기 안내와 같게 단지를 정할 때까지 확인 필요로 남는다', () => {
  const { review, workspace } = scenario('gwangju')
  workspace.supply.rows.forEach(row => { row.housingComplexId = null })
  review.rows[0].reviewed = true
  expect(pendingItems(review, true, workspace).map(item => item.id)).toContain('row:0')
})

it('GH의 같은 전용면적 행이라도 신청형이 다르면(24ABC·24B·24C) 각자의 GH 틀 행을 고치고 합치지 않는다', () => {
  const { review, workspace } = ghSameArea()
  expect(review.rows.map(row => [row.proposal.destination, row.proposal.supersedes, row.proposal.needsConfirmation]))
    .toEqual([['row:911', undefined, false], ['row:912', undefined, false], ['row:913', undefined, false]])
  const rows = assembleReview(review, workspace, evidence).correction.rows
  expect(rows.filter(row => row.excluded)).toEqual([])
  expect(rows.map(row => [row.id, row.householdCount, row.targets.map(target => [target.target, target.reserveCount])]))
    .toEqual([[911, 20, [['청년', 20]]], [912, 3, [['주거급여 수급자', 3]]], [913, 3, [['고령자', 3]]]])
})

const sameApplicationType: [string, string, string] = ['24A(청년)', '24A(주거급여 수급자)', '24A(고령자)']
it('GH가 신청자마다 나눈 같은 신청형 행 셋은 PDF 공급행 하나로 고치고, 나머지는 합쳐 제외하며 모집수는 세 행의 합으로 저장한다', () => {
  const { review, workspace } = ghSameArea(sameApplicationType)
  // 신청형 24A의 공고문 행 셋은 한 공급행이고, 워커가 행마다 맞춘 GH 틀 행 중 첫 행을 고친다.
  expect(review.rows.map(row => [row.proposal.destination, row.proposal.supersedes, row.proposal.needsConfirmation])).toEqual([['row:911', ['row:912', 'row:913'], false]])
  const count = review.rows[0].cells.find(cell => cell.field === 'totalSupplyHouseholdCount')!
  expect([count.before, count.selected]).toEqual([26, false])
  const rows = assembleReview(review, workspace, evidence).correction.rows
  expect(rows.find(row => row.id === 911)).toMatchObject({ householdCount: 26, excluded: false })
  expect(rows.filter(row => row.excluded).map(row => row.id)).toEqual([912, 913])
  expect(rows.find(row => row.id === 911)!.targets.map(target => [target.target, target.reserveCount])).toEqual([['청년', 20], ['주거급여 수급자', 3], ['고령자', 3]])
})

it('합쳐지는 GH 행 중 모집수를 모르는 행이 있으면 합을 만들지 않고 모집수를 확인 필요로 둔다', () => {
  const { read, workspace, housing } = ghSameArea(sameApplicationType)
  workspace.supply.rows[2].householdCount = null
  const review = createReview(read, workspace, housing)
  const count = review.rows[0].cells.find(cell => cell.field === 'totalSupplyHouseholdCount')!
  expect([count.before, count.state]).toEqual([20, 'confirm'])
  expect(pendingItems(review, true, workspace).some(item => JSON.stringify(item).includes(count.path))).toBe(true)
})

it('PDF 모집수가 저장할 행의 지금 값과 같아도 합쳐지는 행의 합과 다르면 바뀜으로 고른다', () => {
  const { read, workspace, housing } = ghSameArea(sameApplicationType)
  // 공고문 모집수 합은 20(14·3·3)이고, 저장할 행 #911의 지금 값도 20이다. 합쳐지는 행까지 더한 지금 값은 26이다.
  read.supplyRows.forEach((row, at) => { row.values.totalSupplyHouseholdCount = [14, 3, 3][at] })
  read.evidence.push({ path: 'supplyRows[0].totalSupplyHouseholdCount', page: 4, quote: '24ABC 청년' })
  const review = createReview(read, workspace, housing)
  const count = review.rows[0].cells.find(cell => cell.field === 'totalSupplyHouseholdCount')!
  expect([count.before, count.value, count.state, count.selected]).toEqual([26, 20, 'changed', true])
})

describe('GH 공급행 틀', () => {
  it('면적이 다른 두 PDF 행은 워커가 맞춘 각자의 틀 행을 확인 없이 고치고 합칠 행이 없다', () => {
    const { review, workspace } = ghFrame({ sourceRow: true })
    expect(review.rows.map(row => [row.proposal.destination, row.proposal.supersedes, row.proposal.needsConfirmation])).toEqual([['row:901', undefined, false], ['row:902', undefined, false]])
    const rows = assembleReview(review, workspace, evidence).correction.rows
    expect(rows.map(row => [row.id, row.householdCount, row.excluded])).toEqual([[901, 20, false], [902, 10, false]])
  })

  it('PDF 단지명이 틀 행의 단지와 다르면 틀 행을 고르되 확인을 받는다', () => {
    const { read, workspace, housing } = ghFrame({ sourceRow: true })
    read.supplyRows[0].complexName = '영통'
    expect(createReview(read, workspace, housing).rows[0].proposal).toMatchObject({ destination: 'row:901', needsConfirmation: true })
  })

  it('작업실에 없는 틀 행 키와 다른 PDF 행이 이미 고른 틀 행은 쓰지 않는다', () => {
    const { read, workspace, housing } = ghFrame({ sourceRow: true })
    read.supplyRows[0].values.sourceRow = 'GH:801:수원 광교:없는형'
    read.supplyRows[1].values.sourceRow = 'GH:801:수원 광교:36A'
    const proposals = createReview(read, workspace, housing).rows.map(row => row.proposal.destination)
    // 첫 행은 틀이 없어 면적으로 고르고(틀 행에는 면적이 없어 새 공급정보), 둘째 행은 #901 틀을 고른다.
    expect(proposals).toEqual(['new', 'row:901'])
    read.supplyRows[0].values.sourceRow = 'GH:801:수원 광교:36A'
    expect(createReview(read, workspace, housing).rows.map(row => row.proposal.destination)).toEqual(['row:901', 'new'])
  })
})

it('대상 예비자 수만 여러 면적 행에 걸치면 행마다 모집수가 없을 때만 한 공급행으로 접는다', () => {
  const { read } = ghSameArea()
  read.supplyRows[1].values.exclusiveArea = 36.5
  const shared = { ...read, sharedCounts: [{ paths: ['supplyRows[0].targets[0].reserveCount', 'supplyRows[1].targets[0].reserveCount'], count: 23, page: 4, quote: '24ABC 24B 23' }] }
  // 같은 면적이어도 신청형이 다른 24C는 접지 않는다.
  expect(foldSameHousingType(shared).supplyRows.map(row => row.typeName)).toEqual(['24ABC·24B', '24C'])
  // 행마다 자기 모집수가 있으면 면적이 다른 행은 그대로 둔다(대상 수만 함께 쓴다).
  shared.supplyRows[0].values.totalSupplyHouseholdCount = 20
  shared.supplyRows[1].values.totalSupplyHouseholdCount = 3
  expect(foldSameHousingType(shared).supplyRows.map(row => row.typeName)).toEqual(['24ABC', '24B', '24C'])
})
