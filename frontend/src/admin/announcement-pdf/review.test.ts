import { describe, expect, it } from 'vitest'
import { scenario, separateHansville } from './fixtures/workspace'
import fixtures from './fixtures/scenarios.json'
import { readExtraction } from './extraction'
import { blankTarget } from '../supply/supplyCorrectionApi'
import type { JsonWorkspace } from '../announcement-json/api'
import { complexCorrespondence } from './correspondence'
import { areaOnlyTypeName, createRowReview, destinationLabel, housingTypePrice, linkedHousingTypeId, pendingPrice, foreignCharacters, shownDateTime, pendingChanges, assembleReview, applicableCells, chooseCellValue, createReview, editCell, pendingCell, pendingItems, placementProblems, rebaseReview, remapSchedule, remapTarget, serverFieldError, serverFieldsError, pendingSubtypes, validCellValue } from './review'


const evidence = { reason: '공고문과 대조', sourceUrl: 'https://example.com/notice', sourcePage: '4' }

it('한스빌 26A·26B가 기존 행 하나와 공통 모집수를 함께 적고 대상이 같으면 그 행 하나로 접어 모집수 170과 두 주택형을 저장한다', () => {
  const { review, workspace } = scenario('hansville')
  expect(review.rows.map(row => [row.proposal.destination, row.proposal.needsConfirmation, row.subtypes?.map(subtype => subtype.choice)])).toEqual([['row:90', true, [10353, 10352]]])
  expect(review.rows[0].proposal.reason).toContain('공고문 26A·26B가 공통 모집수 170세대를 함께 적어 공급행 하나의 세부 타입으로 접었습니다.')
  expect(review.extraction.sharedCounts).toEqual([])
  const request = assembleReview(review, workspace, evidence)
  expect(request.correction.newGroups).toEqual([])
  expect(request.correction.rows).toEqual([expect.objectContaining({ id: 90, householdCount: 170, housingTypeId: 10353, linkedHousingTypes: [{ housingTypeId: 10352 }] })])
  expect(request.correction.rows[0].targets.map(target => target.deposit)).toEqual(['4076000'])
})

it('대상 조건이 달라 접지 않은 한스빌의 공통 모집수는 묶음에 한 번만 넣고 기존 공급행 ID를 보존한다', () => {
  const { review, workspace } = scenario('hansville', separateHansville())
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

it('새 공통 모집 묶음의 두 행이 같은 주택형을 연결하면 서버에 보내기 전에 그 행으로 안내한다', () => {
  const { review, workspace } = scenario('hansville', separateHansville())
  const first = assembleReview(review, workspace, evidence).correction.rows[0].housingTypeId!
  review.rows[1].cells = review.rows[1].cells.map(cell => cell.field === 'housingTypeId' ? editCell(cell, String(first)) : cell)
  expect(() => assembleReview(review, workspace, evidence)).toThrow(expect.objectContaining({ reviewId: 'row:0', message: '공통 모집 묶음의 행마다 서로 다른 주택형을 연결하세요.' }))
})

it('지번을 모르는 단지의 공급행은 소재지 PNU 없이 저장하고 문서 PNU도 비운다', () => {
  const { review, workspace } = scenario('hansville')
  workspace.supply.rows.forEach(row => { row.pnu = '' })
  const request = assembleReview(review, workspace, evidence)
  expect(request.correction.rows.every(row => row.pnu === '')).toBe(true)
  expect(request.document.supplyRows.map(row => (row.complexReference as { pnu: unknown }).pnu)).toEqual(request.document.supplyRows.map(() => null))
})

it('연결되지 않은 LH 행이 있어도 새 공급정보 추가를 막지 않고, 그 LH 행은 행마다 경고하지 않고 단지 대응에서 맞는 PDF 행을 묻는다', () => {
  const { review, workspace } = scenario('hansville', separateHansville())
  const index = review.rows.findIndex(row => row.include && row.proposal.destination === 'new')
  expect(index).toBeGreaterThanOrEqual(0)
  const lh = { rowIdentifier: 'LH:P:SUPPLY:1', complexLabel: '한스빌', typeName: '26B', exclusiveArea: null, householdCount: null, attachedRowKey: null, splitRowKeys: [] }
  const unattached = { ...workspace, supply: { ...workspace.supply, lhSupplyRows: [lh], unattachedLhSupplyRows: [lh] } }
  expect(assembleReview(review, unattached, evidence).correction.rows.filter(row => row.id === null)).toHaveLength(1)
  expect(placementProblems(review, unattached).get(index)).toBeUndefined()
  expect(complexCorrespondence(review, unattached, 0).leftovers).toEqual([{ lh, attachedTo: null, kept: false }])
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
  const { review, workspace } = scenario('hansville', separateHansville())
  const destination = review.rows[0].proposal.destination
  review.rows[0].proposal.destination = ''
  expect(() => assembleReview(review, workspace, evidence)).toThrow('저장할 곳을 고르세요')
  review.rows[0].proposal.destination = destination; review.rows[1].include = false
  expect(() => assembleReview(review, workspace, evidence)).toThrow('모든 행을 함께')
})

it('같은 기존 행에 두 번 대응하거나 기존 조건을 임의로 제거하지 않는다', () => {
  const { review, workspace } = scenario('hansville', separateHansville())
  const old = { ...blankTarget(0), id: 33, key: 'target:33', target: '기존 특수 조건', deposit: '999999999999999999' }
  workspace.supply.rows[0].targets.push(old)
  expect(assembleReview(review, workspace, evidence).correction.rows[0].targets[0]).toEqual(old)
  review.rows[1].proposal.destination = review.rows[0].proposal.destination
  expect(() => assembleReview(review, workspace, evidence)).toThrow(expect.objectContaining({ message: expect.stringContaining('공급행 2(26B)도 같은 기존 공급정보 #'), reviewId: 'row:0' }))
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
  const { review } = scenario('hansville', separateHansville())
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

it('단지에 맞는 주택형이 없을 때 새 주택형 추가를 고르면 PDF 형명·면적으로 주택형을 만든다', () => {
  const { review, workspace } = scenario('gwangju')
  const row = review.rows[0], existing = workspace.supply.rows.find(item => item.key === row.proposal.destination)!
  const source = review.extraction.supplyRows[0]
  row.newHousingType = true
  expect(() => assembleReview(review, workspace, evidence)).toThrow('새 주택형')
  // 운영자가 면적 칸에 직접 넣은 값이 원본 추출값보다 앞선다.
  source.values = { ...source.values, exclusiveArea: 26.37125 }
  row.cells = row.cells.map(cell => cell.field === 'exclusiveArea' ? editCell(cell, '99.1234') : cell.field === 'supplyArea' ? editCell(cell, '120.5') : cell)
  const saved = assembleReview(review, workspace, evidence).correction.rows.find(item => item.key === existing.key)!
  expect(saved.housingTypeId).toBeNull()
  expect(saved.newHousingType).toEqual({ name: saved.typeName, exclusiveArea: 99.1234, supplyArea: 120.5 })
})


it('공급행이 없는 공고는 검색해 선택한 단지를 기준으로 첫 공급행을 만든다', () => {
  const { review: seed, workspace, housing } = scenario('hansville', separateHansville())
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

it('세부 타입 둘이 같은 전용면적의 주택형을 연결하면 확인 필요로 잡고, 하나를 연결 안 함으로 두면 풀린다', () => {
  const { review, workspace } = scenario('hansville')
  const row = review.rows[0]
  row.reviewed = true
  row.subtypes = row.subtypes!.map(subtype => ({ ...subtype, choice: 'new' as const, exclusiveArea: 77.7, options: [] }))
  expect(pendingSubtypes(row)).toEqual([1])
  expect(() => assembleReview(review, workspace, evidence)).toThrow(expect.objectContaining({ reviewId: 'subtype:0:1', message: expect.stringContaining('전용면적마다 주택형 하나만') }))
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
  const names = ['A단지', 'B단지', 'A단지', 'B단지', 'A단지', 'B단지', 'A단지']
  review.extraction.supplyRows = review.extraction.supplyRows.map((row, index) => ({ ...row, complexName: names[index] }))
  review.rows = review.rows.map(row => ({ ...row, include: true, proposal: { ...row.proposal, destination: '' } }))
  const rows = pendingItems(review).filter(item => item.id.startsWith('row:')).map(item => item.id)
  expect(rows).toEqual(['row:0', 'row:2', 'row:4', 'row:6', 'row:1', 'row:3', 'row:5'])
})

it('확인 필요 항목은 화면 순서로 모으고 사람이 고르거나 대응을 확인하면 사라진다', () => {
  const { review, workspace, housing } = scenario('gongju')
  // 날짜 없는 '계약안내(개별안내)'는 일정으로 저장하지 않으므로 확인 필요로 세지 않는다.
  expect(review.schedules[2]).toMatchObject({ include: false, proposal: { undated: true } })
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
  expect(order).toEqual([...review.rows.map(row => row.key), 'row:999'])
  expect(order.indexOf('row:27')).toBeLessThan(order.indexOf('row:26'))
  expect(new Set(rows.map(row => row.displayOrder)).size).toBe(rows.length)
})

it('이미 PDF 순서와 같으면 표시 순서를 바꾸지 않는다', () => {
  const { review, workspace } = scenario('hansville', separateHansville())
  const rows = assembleReview(review, workspace, evidence).correction.rows
  expect(rows.map(row => row.displayOrder)).toEqual([workspace.supply.rows[0].displayOrder, workspace.supply.rows[0].displayOrder + 1])
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

it('저장 위치 선택지는 연결 단지·표시 이름·모집수·전용면적으로 구분한다', () => {
  const { workspace } = scenario('gongju')
  const row = { ...workspace.supply.rows[0], housingComplexName: '평화주공1단지', typeName: '26', householdCount: 450, exclusiveArea: 26.37 }
  expect(destinationLabel(row)).toBe(`#${String(row.id)} · 평화주공1단지 · 26 · 모집 450세대 · 전용 26.37㎡`)
  expect(destinationLabel({ ...row, householdCount: null, groupId: 3, exclusiveArea: null })).toContain('공통 모집 · 면적 미확인')
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

it('일정 일시는 자정이면 날짜만, 시각이 있으면 시:분까지 보여 준다', () => {
  expect(['2026-06-01T00:00:00', '2026-06-01T16:00:00', '2026-06-01T09:30', '2026-06-01', null].map(shownDateTime))
    .toEqual(['2026-06-01', '2026-06-01 16:00', '2026-06-01 09:30', '2026-06-01', '미확인'])
})

it('주택형 공고 값은 고른 이름·공급면적과 건설호수만 보내고 고르지 않은 값과 공급대상 괄호는 보내지 않는다', () => {
  const { review, workspace } = scenario('gwangju')
  const valueOf = (key: string) => assembleReview(review, workspace, evidence).housingTypeValues?.find(value => value.rowKey === key)
  const index = review.rows.findIndex(row => row.include && row.proposal.destination === 'row:22')
  // 대상별 금액이 달라 고르지 않은 주택형 가격은 보내지 않는다(지금 값 유지).
  expect(valueOf('row:22')).toEqual({ rowKey: 'row:22', name: '39', supplyArea: 66.6673, householdCount: null, basicDeposit: null, basicMonthlyRent: null })
  const priced = review.rows.findIndex(row => row.include && row.proposal.destination === 'row:22')
  review.rows[priced] = { ...review.rows[priced], priceTarget: 0 }
  const first = review.rows[priced].targets[0].cells
  expect(valueOf('row:22')).toMatchObject({ basicDeposit: Number(first.find(cell => cell.field === 'rentalDeposit')?.value), basicMonthlyRent: Number(first.find(cell => cell.field === 'monthlyRent')?.value) })
  expect(valueOf('row:22')?.basicDeposit).toEqual(expect.any(Number))
  review.rows[priced] = { ...review.rows[priced], priceTarget: undefined }
  expect(valueOf('row:26')?.name).toBe('26')
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
  const rows = workspace.supply.rows.map(row => ({ ...row, typeName: `0${String(review.extraction.supplyRows[0].typeName)}` }))
  const cell = createReview(review.extraction, { ...workspace, supply: { ...workspace.supply, rows } }, housing).rows[0].cells.find(item => item.field === 'sourceHousingTypeName')!
  expect(cell).toMatchObject({ state: 'same', selected: false })
  // 대소문자처럼 숫자 밖의 표기 차이는 공고 표기로 바꾼다.
  const lower = workspace.supply.rows.map(row => ({ ...row, typeName: String(review.extraction.supplyRows[0].typeName).toLowerCase() }))
  const changed = createReview(review.extraction, { ...workspace, supply: { ...workspace.supply, rows: lower } }, housing).rows[0].cells.find(item => item.field === 'sourceHousingTypeName')!
  expect(changed.state).not.toBe('same')
})

it('주택형 가격은 저장과 같은 주택형(반영한 연결 주택형 칸, 아니면 저장 위치의 주택형)을 기준으로 한다', () => {
  const { review, workspace } = scenario('gwangju')
  const savedType = () => assembleReview(review, workspace, evidence).correction.rows.find(row => row.key === 'row:23')!.housingTypeId
  // 저장 위치를 다시 고를 때 면적이 같은 주택형이 둘이라 연결 주택형을 정하지 못하면, 저장은 기존 공급행의 주택형을 그대로 쓴다.
  review.rows[1] = createRowReview(review.extraction, 1, { ...review.rows[1].proposal, housingTypeId: null }, workspace)
  expect(linkedHousingTypeId(review.rows[1])).toBe(3032)
  expect(savedType()).toBe(3032)
  const at = review.rows[1].cells.findIndex(cell => cell.field === 'housingTypeId')
  review.rows[1].cells[at] = editCell(review.rows[1].cells[at], '3033')
  expect(linkedHousingTypeId(review.rows[1])).toBe(3033)
  expect(savedType()).toBe(3033)
})

it('이전 PDF 보정에서 고른 주택형 가격은 확인 필요로 남기고, 확인하지 않고 저장하면 지금 가격을 유지한다', () => {
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

it('409 뒤 저장 위치의 주택형이 바뀌었으면 고른 가격 대상을 풀어 새 주택형의 지금 값을 다시 확인한다', () => {
  const { review, workspace, housing } = scenario('gwangju')
  review.rows[1].priceTarget = 0
  expect(rebaseReview(review, workspace, housing).rows[1].priceTarget).toBe(0)
  const moved = { ...workspace, supply: { ...workspace.supply, rows: workspace.supply.rows.map(row => row.key === 'row:23' ? { ...row, housingTypeId: 3033 } : row) } }
  expect(rebaseReview(review, moved, housing).rows[1].priceTarget).toBeUndefined()
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
    // 연결 주택형이 후보에 없어 지금 값을 모르면 확인 없이 덮지 않도록 기본은 유지한다.
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

/** 단지에 연결된 행의 typeName은 단지 주택형 이름("26")이고, LH 표기는 행이 기록한 LH 원천행(rowSources → sources)에만 있다. */
function linkedLhRow(workspace: JsonWorkspace, label: string) {
  const row = workspace.supply.rows[0]
  Object.assign(row, { typeName: '26', housingTypeId: 10353 })
  workspace.supply.rowSources = [{ rowKey: row.key, category: 'LH_ANNOUNCEMENT_SUPPLY', sourceKey: 'lh:90' }]
  workspace.supply.sources = [...workspace.supply.sources, { category: 'LH_ANNOUNCEMENT_SUPPLY', sourceKey: 'lh:90', rows: [{ raw: { type_name: label } }] }]
}

it('단지에 연결된 LH 행 하나("26A/B")를 공고문이 세부 타입 행 여럿으로 적으면 그 행 하나로 접어 공통 모집수 없이 저장한다', () => {
  const { workspace, housing } = scenario('hansville')
  linkedLhRow(workspace, '26A/B')
  const extracted = readExtraction(JSON.stringify(fixtures.hansville.extraction))
  if (!extracted.ok) throw new Error(extracted.error)
  const review = createReview(extracted.value, workspace, housing)
  expect(review.rows.map(row => [row.proposal.destination, row.subtypes?.map(subtype => subtype.choice)])).toEqual([['row:90', [10353, 10352]]])
  expect(review.extraction.sharedCounts).toEqual([])
  // 공고 표기는 LH 표기가 아니라 접은 공고문 행의 형명이다. 근거 인용이 행마다 따로라 관리자가 받아들여야 쓴다.
  const label = review.rows[0].cells.findIndex(cell => cell.field === 'sourceHousingTypeName')
  expect(review.rows[0].cells[label]).toMatchObject({ value: '26A/26B', state: 'confirm' })
  review.rows[0].cells[label] = chooseCellValue(review.rows[0].cells[label], 'extracted')
  const request = assembleReview(review, workspace, evidence)
  expect(request.correction.newGroups).toEqual([])
  // 공고문이 두 행에 걸쳐 적은 공통 모집수 170은 이 공급행 하나의 모집수다.
  expect(request.correction.rows).toEqual([expect.objectContaining({ id: 90, typeName: '26A/26B', householdCount: 170, housingTypeId: 10353, linkedHousingTypes: [{ housingTypeId: 10352 }] })])
  expect(request.housingTypeValues?.map(value => [value.name, value.linkedHousingTypeId])).toEqual([['26A', undefined], ['26B', 10352]])
})

it('조회한 함께 모집하는 주택형을 그대로 둔 행이라도 PDF가 대표 주택형을 바꾸면 그 목록을 보내지 않는다', () => {
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

describe('세부 타입 묶음 행으로 접기', () => {
  type Input = { data: { supplyRows: Record<string, unknown>[] }; sharedCounts: unknown[] }
  function fold(label: string, edit: (input: Input) => void = () => {}, housing?: Record<number, unknown[]>) {
    const { workspace, housing: all } = scenario('hansville')
    linkedLhRow(workspace, label)
    const input = structuredClone(fixtures.hansville.extraction) as unknown as Input
    edit(input)
    const extracted = readExtraction(JSON.stringify(input))
    if (!extracted.ok) throw new Error(extracted.error)
    return { review: createReview(extracted.value, workspace, (housing ?? all) as never), workspace }
  }
  const targetOf = (input: Input, row: number) => (input.data.supplyRows[row].targets as Record<string, unknown>[])[0]

  it('LH 표기로 고른 행이라도 대상·금액이 행마다 다르면 접지 않고 공통 모집 묶음으로 둔다', () => {
    const { review, workspace } = fold('26A/B', input => { targetOf(input, 1).rentalDeposit = 5000000 })
    expect(review.rows.map(row => row.proposal.destination)).toEqual(['row:90', 'new'])
    expect(review.rows.every(row => !row.subtypes)).toBe(true)
    expect(assembleReview(review, workspace, evidence).correction.newGroups).toEqual([expect.objectContaining({ sourceRowId: 90, sharedHouseholdCount: 170 })])
  })

  it('공급대상 묶음 LH 행은 공고문 행의 대상이 모두 그 묶음일 때만 접는다', () => {
    expect(fold('26A/B(일반)').review.rows.map(row => row.subtypes?.map(subtype => subtype.choice))).toEqual([[10353, 10352]])
    // 묶음 표기가 대상과 달라도 기존 행 하나와 공통 모집수만 묶었으면 공통 모집수 기준 접기(sharedFoldGroups)가 접는다.
    expect(fold('26A/B(고령자)').review.rows[0].proposal.reason).toContain('공통 모집수 170세대를 함께 적어')
  })

  it('접은 행은 대상별 모집수·예비자 수를 더하고, 공통 모집수가 없으면 행 모집수를 더한다', () => {
    const { review } = fold('26A/B', input => {
      input.sharedCounts = []
      input.data.supplyRows.forEach((row, at) => { row.totalSupplyHouseholdCount = [100, 70][at]; Object.assign(targetOf(input, at), { supplyHouseholdCount: [90, 60][at], reserveCount: [10, 10][at] }) })
    })
    expect(review.extraction.supplyRows.map(row => [row.values.totalSupplyHouseholdCount, row.targets[0].supplyHouseholdCount, row.targets[0].reserveCount])).toEqual([[170, 150, 20]])
  })

  it('저장 위치의 지금 주택형이 두 번째 세부 타입이면 그 주택형을 대표로 유지한다', () => {
    const { workspace, housing } = scenario('hansville')
    workspace.supply.rows[0].housingTypeId = 10352
    const extracted = readExtraction(JSON.stringify(fixtures.hansville.extraction))
    if (!extracted.ok) throw new Error(extracted.error)
    const review = createReview(extracted.value, workspace, housing)
    expect(linkedHousingTypeId(review.rows[0])).toBe(10352)
    expect(assembleReview(review, workspace, evidence).correction.rows[0]).toMatchObject({ housingTypeId: 10352, linkedHousingTypes: [{ housingTypeId: 10353 }] })
  })

  it('세부 타입 주택형 이름은 관리자가 공급행의 주택형 표기를 받아들였을 때만 보낸다', () => {
    const { review, workspace } = scenario('hansville')
    const label = review.rows[0].cells.findIndex(cell => cell.field === 'sourceHousingTypeName')
    expect(review.rows[0].cells[label].state).toBe('confirm')
    expect(assembleReview(review, workspace, evidence).housingTypeValues?.map(value => value.name)).toEqual([null, null])
    review.rows[0].cells[label] = chooseCellValue(review.rows[0].cells[label], 'extracted')
    expect(assembleReview(review, workspace, evidence).housingTypeValues?.map(value => value.name)).toEqual(['26A', '26B'])
  })

  it('같은 면적 주택형이 없는 세부 타입은 새 주택형으로 연결하고, 다시 맞출 때 같은 면적 주택형이 생기면 새 주택형 선택을 버린다', () => {
    const only26A = { 3055: fixtures.hansville.complexes[0].housingTypes.filter(type => type.id === 10353) }
    const { review, workspace } = fold('아파트', () => {}, only26A)
    expect(review.rows[0].subtypes?.map(subtype => subtype.choice)).toEqual([10353, 'new'])
    const request = assembleReview(review, workspace, evidence)
    expect(request.correction.rows[0].linkedHousingTypes).toEqual([{ newHousingType: { name: '26B', exclusiveArea: 26.4673, supplyArea: 40.5686 } }])
    expect(request.housingTypeValues?.[1]).toMatchObject({ linkedNewHousingTypeArea: 26.4673 })
    expect(rebaseReview(review, workspace, only26A).rows[0].subtypes?.map(subtype => subtype.choice)).toEqual([10353, 'new'])
    const { housing } = scenario('hansville')
    expect(rebaseReview(review, workspace, housing).rows[0].subtypes?.map(subtype => subtype.choice)).toEqual([10353, 10352])
  })

  it('세부 타입 묶음 행이 다른 공급행과 공통 모집수로 묶이면 공통 모집수를 버리지 않고 이 행의 모집수를 직접 입력할 때까지 저장하지 않는다', () => {
    const { workspace, housing } = scenario('hansville')
    workspace.supply.rows[0].typeName = '26A/B'
    workspace.supply.rows.push({ ...workspace.supply.rows[0], id: 91, key: 'row:91', typeName: '36', displayOrder: 2, householdCount: 30 })
    const input = structuredClone(fixtures.hansville.extraction) as unknown as Input
    const [first] = input.data.supplyRows
    input.data.supplyRows = [{ ...first, sourceHousingTypeName: '26A/B', exclusiveArea: null, supplyArea: null, subtypes: [
      { name: '26A', exclusiveArea: 26.5195, commonArea: null, supplyArea: null, constructedHouseholdCount: null },
      { name: '26B', exclusiveArea: 26.4673, commonArea: null, supplyArea: null, constructedHouseholdCount: null }] }, { ...first, sourceHousingTypeName: '36', exclusiveArea: 36.1 }]
    input.sharedCounts = [{ paths: ['supplyRows[0].totalSupplyHouseholdCount', 'supplyRows[1].totalSupplyHouseholdCount'], count: 200, page: 4, quote: '26A/B 36 200' }]
    const extracted = readExtraction(JSON.stringify(input))
    if (!extracted.ok) throw new Error(extracted.error)
    const review = createReview(extracted.value, workspace, housing)
    expect(review.rows.map(row => [row.proposal.destination, Boolean(row.subtypes), row.proposal.sharedIndex])).toEqual([['row:90', true, 0], ['row:91', false, 0]])
    const count = review.rows[0].cells.findIndex(cell => cell.field === 'totalSupplyHouseholdCount')
    expect(review.rows[0].cells[count]).toMatchObject({ locked: false, state: 'confirm' })
    expect(pendingItems(review).map(item => item.id)).toContain('cell:supplyRows[0].totalSupplyHouseholdCount')
    expect(() => assembleReview(review, workspace, evidence)).toThrow(expect.objectContaining({ reviewId: 'cell:supplyRows[0].totalSupplyHouseholdCount',
      message: expect.stringContaining('공급행 1: 공고문이 이 행의 모집수를 다른 공급행과 합쳐 적었습니다.') }))
    review.rows[0].cells[count] = editCell(review.rows[0].cells[count], '150')
    const request = assembleReview(review, workspace, evidence)
    expect(request.correction.newGroups).toEqual([])
    expect(request.correction.rows.map(row => [row.id, row.householdCount])).toEqual([[90, 150], [91, null]])
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
