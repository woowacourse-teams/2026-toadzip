import { describe, expect, it } from 'vitest'
import { daegu, savedSheet, scenario } from './fixtures/workspace'
import fixtures from './fixtures/scenarios.json'
import { readExtraction, type Extraction } from './extraction'
import { assembledSheet, assembleReview, chooseCellValue, createReview, decideComplex, editCell, foreignCharacters, needsComplex, pendingChanges, pendingItems, pendingJumpTarget, rebaseReview,
  remapSchedule, removedHousingTypes, serverFieldError, shownDateTime, validCellValue, type Review } from './review'

const evidence = { reason: '공고문과 대조', sourceUrl: 'https://example.com/notice', sourcePage: '4' }
/** 이 공고문을 이미 반영해 단지에 연결된 지금 공급표 */
const linked = (id: number, name: string) => (extracted: Extraction) => savedSheet(extracted, { id, name })
const hansvilleLinked = linked(3055, '익산한스빌'), gongjuLinked = linked(2467, '공주옥룡1')
const cellOf = <T extends { field: string }>(cells: T[], field: string) => cells.find(cell => cell.field === field)!
/** 단지 정하기에서 PDF 단지를 고른 검토 */
function choose(review: Review, key: string, housingComplexId: number, candidates = review.rows[0].candidates) {
  return decideComplex(review, key, { ...review.complexes[key], housingComplexId, housingComplexName: key, supplyPnu: '1111010100', category: 'RESUPPLY', chosen: true }, candidates)
}

describe('공고 공급표 검토', () => {
  it('PDF 주택형은 같은 단지·이름의 지금 주택형과 짝지어 그 키로 저장하고, 짝이 없으면 새 주택형으로 더하며 단지를 정하게 한다', () => {
    const { review, workspace } = scenario('hansville', undefined, hansvilleLinked)
    expect(review.rows.map(row => [row.key, row.existing?.id])).toEqual([['row:1', 1], ['row:2', 2]])
    expect(review.rows.map(row => cellOf(row.cells, 'name').state)).toEqual(['same', 'same'])
    expect(needsComplex(review, 0)).toBe(false)
    const sheet = assembledSheet(review, workspace)
    expect([sheet.housingTypes.map(type => [type.key, type.id, type.housingComplexId]), sheet.targets.map(target => target.key), sheet.cells.map(cell => cell.key)])
      .toEqual([[['row:1', 1, 3055], ['row:2', 2, 3055]], ['target:1'], ['cell:1', 'cell:2']])
    expect(removedHousingTypes(review, workspace)).toEqual([])

    // 지금 공급표의 '아파트'는 PDF 주택형과 이름이 달라 짝짓지 않는다. PDF 주택형은 새로 더하고 단지를 정해야 저장된다.
    const fresh = scenario('hansville')
    expect(fresh.review.rows.map(row => [row.key, row.existing])).toEqual([['new:t0', null], ['new:t1', null]])
    expect(needsComplex(fresh.review, 0)).toBe(true)
    expect(() => assembleReview(fresh.review, fresh.workspace, evidence)).toThrow(expect.objectContaining({ reviewId: 'row:0' }))
    const chosen = choose(fresh.review, '익산한스빌', 3055, fresh.housing[3055])
    expect(needsComplex(chosen, 1)).toBe(false)
    expect(assembledSheet(chosen, fresh.workspace).housingTypes.map(type => [type.key, type.id, type.housingComplexId])).toEqual([['new:t0', null, 3055], ['new:t1', null, 3055]])
  })

  it('관리자가 고친 값과 PDF 값이 다르면 확인 필요로 두고 기본은 지금 값을 지키며, PDF 값을 고르면 그 값을 저장한다', () => {
    const { review, workspace } = scenario('hansville', undefined, extracted => {
      const sheet = hansvilleLinked(extracted)
      sheet.housingTypes[0] = { ...sheet.housingTypes[0], exclusiveArea: 26.6, adminModified: true }
      return sheet
    })
    const area = cellOf(review.rows[0].cells, 'exclusiveArea')
    expect(area).toMatchObject({ state: 'confirm', selected: false, mode: 'keep', before: 26.6 })
    expect(area.note).toContain('관리자가 직접 고친 값')
    expect(pendingItems(review).map(item => item.id)).toContain('cell:housingTypes[0].exclusiveArea')
    expect(assembledSheet(review, workspace).housingTypes[0].exclusiveArea).toBe(26.6)
    review.rows[0].cells = review.rows[0].cells.map(cell => cell.field === 'exclusiveArea' ? chooseCellValue(cell, 'extracted') : cell)
    expect(assembledSheet(review, workspace).housingTypes[0].exclusiveArea).toBe(26.5195)
  })

  it('지금 값과 다른 대상별 임대조건은 PDF 값으로 바꾸고, 지금 값 유지를 고르면 그 칸의 임대조건만 지금 값으로 저장한다', () => {
    const { review, workspace } = scenario('hansville', undefined, extracted => {
      const sheet = hansvilleLinked(extracted)
      sheet.leaseTerms[0] = { ...sheet.leaseTerms[0], rentalDeposit: '4000000' }
      return sheet
    })
    const deposit = cellOf(review.rows[0].targets[0].cells, 'rentalDeposit')
    expect(deposit).toMatchObject({ state: 'changed', before: '4000000', value: '4076000', selected: true })
    const leaseOf = (cell: string) => assembledSheet(review, workspace).leaseTerms.find(lease => lease.members.includes(cell))!.rentalDeposit
    expect([leaseOf('cell:1'), leaseOf('cell:2')]).toEqual(['4076000', '4076000'])
    review.rows[0].targets[0].cells = review.rows[0].targets[0].cells.map(cell => cell.field === 'rentalDeposit' ? chooseCellValue(cell, 'keep') : cell)
    expect([leaseOf('cell:1'), leaseOf('cell:2')]).toEqual(['4000000', '4076000'])
  })

  it('한스빌 26A·26B가 일반 모집 170을 함께 쓰면 함께 쓰는 수 하나로 검토하고, 주택형·대상만의 모집수는 따로 두지 않는다', () => {
    const { review, workspace, housing } = scenario('hansville')
    expect(review.shared.map(entry => [entry.quota, entry.cell.label, entry.cell.value])).toEqual([[0, '26A·26B 일반 함께 모집', 170]])
    expect(review.rows.map(row => cellOf(row.cells, 'count').state)).toEqual(['notApplicable', 'notApplicable'])
    expect(review.rows.map(row => cellOf(row.targets[0].cells, 'winner').state)).toEqual(['notApplicable', 'notApplicable'])
    const chosen = choose(review, '익산한스빌', 3055, housing[3055])
    expect(assembledSheet(chosen, workspace).quotas).toEqual([expect.objectContaining({ kind: 'WINNER', count: 170, members: ['new:c0', 'new:c1'] })])
    chosen.shared[0] = { ...chosen.shared[0], cell: editCell(chosen.shared[0].cell, '180') }
    expect(assembledSheet(chosen, workspace).quotas.map(quota => [quota.count, quota.members])).toEqual([[180, ['new:c0', 'new:c1']]])
  })

  it('지금 공급대상과 소득 유무 표기만 다른 PDF 대상은 그 대상과 짝지어 대상·칸 ID를 지키고 지금 표기를 저장한다', () => {
    const { review, workspace } = scenario('gwangju', undefined, extracted => {
      const sheet = savedSheet(extracted, { id: 9, name: '광주' })
      return { ...sheet, targets: sheet.targets.map(target => target.label === '청년 소득(×)' ? { ...target, label: '청년(소득 없음)' } : target) }
    })
    const current = workspace.supply.sheet, target = current.targets.find(item => item.label === '청년(소득 없음)')!
    const cells = current.cells.filter(cell => cell.target === target.key).map(cell => [cell.key, cell.id])
    expect(cells.length).toBeGreaterThan(1)
    const sheet = assembledSheet(review, workspace)
    expect(sheet.targets.find(item => item.key === target.key)).toMatchObject({ id: target.id, label: '청년(소득 없음)' })
    expect(sheet.targets.filter(item => item.label === '청년 소득(×)')).toEqual([])
    expect(sheet.cells.filter(cell => cell.target === target.key && cell.id !== null).map(cell => [cell.key, cell.id])).toEqual(cells)
  })

  it('공급대상 이름을 읽지 못하면 그 대상을 쓰는 첫 칸으로 보내 저장을 막고, 이름을 넣으면 풀린다', () => {
    const extraction = structuredClone(fixtures.hansville.extraction)
    extraction.data.supplySheet.targets[0].label = ''
    const { review, workspace } = scenario('hansville', extraction, hansvilleLinked)
    expect(() => assembleReview(review, workspace, evidence)).toThrow(expect.objectContaining({ reviewId: 'target:0:0', message: expect.stringContaining('공급대상 이름을 입력하세요') }))
    review.labels[0].cells = review.labels[0].cells.map(cell => cell.field === 'label' ? editCell(cell, '일반') : cell)
    expect(assembleReview(review, workspace, evidence).correction.sheet.targets.map(target => target.label)).toEqual(['일반'])
  })

  it('PDF에 없는 지금 주택형은 저장하면 지우는 것으로 반영 목록에 보이고, 요청은 PDF 주택형만 담은 공급표 하나다', () => {
    const { review, workspace } = daegu()
    expect(removedHousingTypes(review, workspace).map(type => type.key)).toEqual(['row:747', 'row:748'])
    expect(pendingChanges(review, workspace).filter(change => change.cell.label === '주택형 삭제').map(change => change.scope)).toEqual(['대구테크노폴리스4 · 공공임대', '다른 단지 · 39'])
    // 공고 단지가 단지 41에 연결돼 있어 단지를 정하지 않아도 저장된다.
    expect(pendingItems(review).filter(item => item.id.startsWith('row:'))).toEqual([])
    const request = assembleReview(review, workspace, evidence)
    expect(Object.keys(request.correction).sort()).toEqual(['operationId', 'reason', 'sheet', 'snapshotToken', 'sourcePage', 'sourceUrl'])
    expect(request.correction).toMatchObject({ snapshotToken: 'supply', reason: evidence.reason, sourcePage: '4' })
    const { sheet } = request.correction
    expect(sheet.housingTypes.map(type => [type.key, type.id, type.name, type.announcementComplexId, type.housingComplexId])).toEqual([
      ['new:t0', null, '051.9800A', 7, 41], ['new:t1', null, '059.9100A', 7, 41], ['new:t2', null, '059.9300B', 7, 41]])
    expect(sheet.quotas).toEqual([{ kind: 'WINNER', count: 200, members: ['new:c0'] }, { kind: 'WINNER', count: 25, members: ['new:c1'] }, { kind: 'WINNER', count: 15, members: ['new:c2'] }])
    expect(sheet.leaseTerms.map(lease => [lease.rentalDeposit, lease.members])).toEqual([['30000000', ['new:c0']], ['30000000', ['new:c1']], ['30000000', ['new:c2']]])
    expect(sheet.cells.map(cell => [cell.housingType, cell.target])).toEqual([['new:t0', 'target:1'], ['new:t1', 'target:1'], ['new:t2', 'target:1']])
    expect(request.housingTypeValues!.every(value => sheet.housingTypes.some(type => type.key === value.rowKey))).toBe(true)
  })

  it('확인 필요는 공급정보(단지 → 주택형 값 → 대상별 칸) 다음 일정 순서이고, 단지 확인은 단지 정하기로 옮긴다', () => {
    const ids = pendingItems(scenario('gwangju').review).map(item => item.id)
    expect(ids[0]).toBe('row:0')
    expect(ids.indexOf('cell:housingTypes[2].name')).toBeLessThan(ids.indexOf('target:2:0'))
    expect(ids.indexOf('target:2:0')).toBeLessThan(ids.indexOf('target:3:0'))
    expect(ids.at(-1)).toBe('schedule:3')
    expect(pendingJumpTarget('row:0')).toBe('complex')
    expect(pendingJumpTarget('target:2:0')).toBe('target:2:0')
  })

  it('직접 입력 형식 오류는 저장하지 않고 기존 값 사용으로 되돌릴 수 있다', () => {
    const { review, workspace } = scenario('hansville', undefined, hansvilleLinked)
    const row = review.rows[0], index = row.cells.findIndex(cell => cell.field === 'exclusiveArea')
    row.cells[index] = editCell(row.cells[index], '숫자 아님')
    expect(() => assembleReview(review, workspace, evidence)).toThrow('직접 입력값을 확인')
    row.cells[index] = chooseCellValue(row.cells[index], 'keep')
    expect(assembleReview(review, workspace, evidence).correction.sheet.housingTypes[0].exclusiveArea).toBe(workspace.supply.sheet.housingTypes[0].exclusiveArea)
  })

  it('최신 공급표로 다시 비교해도 단지 정하기에서 고른 단지와 칸 선택을 이어받는다', () => {
    const { review, workspace, housing } = scenario('hansville')
    const chosen = choose(review, '익산한스빌', 3055, housing[3055])
    chosen.shared[0] = { ...chosen.shared[0], cell: editCell(chosen.shared[0].cell, '180') }
    const rebased = rebaseReview(chosen, workspace, housing)
    expect(rebased.complexes['익산한스빌']).toMatchObject({ housingComplexId: 3055, chosen: true })
    expect(rebased.shared[0].cell).toMatchObject({ value: 180, mode: 'manual' })
  })
})

it('광주첨단의 단지별 일정은 기존 ID마다 복사하고 새 방문접수도 추가한다', () => {
  const { review, workspace } = scenario('gwangju', undefined, linked(961, '첨단H-3BL'))
  const request = assembleReview(review, workspace, evidence)
  const changed = request.schedules.filter(schedule => schedule.existingId !== null)
  expect(changed).toHaveLength(12)
  expect(new Set(changed.map(schedule => schedule.existingId)).size).toBe(12)
  expect(request.schedules.filter(schedule => schedule.existingId === null)).toHaveLength(1)
  expect(request.document.schedules).toHaveLength(13)
})

it('잘못된 금액·날짜·코드는 자동으로 보정하지 않는다', () => {
  expect(validCellValue('money', '999999999999999999')).toBe(true)
  expect(validCellValue('money', '4,076,000')).toBe(false)
  expect(validCellValue('date', '2026-02-30')).toBe(false)
  expect(validCellValue('datetime', '2026-10-22T25:00')).toBe(false)
  expect(validCellValue('scheduleType', 'MOVE_IN')).toBe(true)
  expect(validCellValue('scheduleType', 'DOCUMENT_RESULT')).toBe(false)
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
  const original = cellOf(review.rows[0].cells, 'name')
  const edited = editCell(chooseCellValue(original, 'manual'), '26 관리자 수정')
  const kept = chooseCellValue(edited, 'keep')
  expect(kept.selected).toBe(false)
  const extracted = chooseCellValue(kept, 'extracted')
  expect(extracted).toMatchObject({ value: '26', selected: true, mode: 'extracted' })
  expect(extracted.extracted.value).toBe('26')
  expect(chooseCellValue(extracted, 'manual')).toMatchObject({ value: '26 관리자 수정', mode: 'manual' })
})

it('접수처가 없던 공고에 PDF가 접수처명 없이 연락처·방식만 주면 접수처명을 확인 필요로 잡고, 입력하거나 접수처 값을 고르지 않으면 풀린다', () => {
  const extraction = structuredClone(fixtures.gongju.extraction)
  extraction.data.receptionPlace.name = null as unknown as string
  extraction.evidence = extraction.evidence.filter(item => item.path !== 'receptionPlace.name')
  const { review, workspace } = scenario('gongju', extraction, gongjuLinked)
  review.schedules.forEach(schedule => { schedule.reviewed = true })
  // 예전 응답의 접수처 객체 하나는 첫 접수처로 읽는다. 이름을 못 읽은 새 접수처는 기본으로 빼 두고, 저장하기로 하면 이름을 요구한다.
  const [place] = review.reception
  expect(place).toMatchObject({ extracted: 0, existing: null, include: false })
  expect(place.cells.filter(cell => cell.selected).map(cell => cell.field)).toEqual(['method', 'contact'])
  expect(pendingItems(review).map(item => item.id)).not.toContain('cell:receptionPlaces[0].name')
  expect(assembleReview(review, workspace, evidence).fields).not.toContain('receptionPlaces')
  const included = { ...review, reception: [{ ...place, include: true }] }
  expect(pendingItems(included).map(item => item.id)).toContain('cell:receptionPlaces[0].name')
  expect(() => assembleReview(included, workspace, evidence)).toThrow(expect.objectContaining({ reviewId: 'cell:receptionPlaces[0].name', message: expect.stringContaining('접수처명이 필요합니다') }))
  const typed = { ...included, reception: [{ ...place, include: true, cells: place.cells.map(cell => cell.field === 'name' ? editCell(cell, 'LH 청약플러스') : cell) }] }
  expect(pendingItems(typed).map(item => item.id)).not.toContain('cell:receptionPlaces[0].name')
  const saved = assembleReview(typed, workspace, evidence)
  expect(saved.fields).toContain('receptionPlaces')
  expect(saved.document.receptionPlaces).toEqual([{ name: 'LH 청약플러스', method: 'VISIT', address: null, contact: '1600-1004', url: null, guidance: null }])
  expect(place.cells.find(cell => cell.field === 'name')!.before).toBeNull()
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
  const { review, workspace } = scenario('gongju', undefined, gongjuLinked)
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
  // 접수처 순번은 저장한 접수처의 순번이다. 뺀 접수처는 세지 않는다.
  const reception = [{ ...review.reception[0], include: false }, { ...review.reception[0], extracted: 1, include: true, cells: review.reception[0].cells.map(cell => ({ ...cell, path: cell.path.replace('[0]', '[1]') })) }]
  expect(serverFieldError({ ...review, reception }, 'receptionPlaces[0].name: 필수 값입니다.')).toEqual({ message: '접수처명: 필수 값입니다.', reviewId: 'cell:receptionPlaces[1].name' })
  expect(serverFieldError(review, 'applicationEndDate: 필수 값입니다. / receptionPlaces[0].name: 필수 값입니다.')).toEqual({ message: '접수 종료일: 필수 값입니다.', reviewId: 'cell:announcement.applicationEndDate' })
  expect(serverFieldError(review, 'originalUrl: HTTP(S) URL이어야 합니다.')).toBeNull()
  expect(serverFieldError(review, '적용할 변경이 없습니다. 현재값과 다른 항목을 선택하세요.')).toBeNull()
})

it('한글 문장에 섞인 한자는 확인 필요로 표시하고 기본 반영에서 뺀다', () => {
  expect(['生계·의료급여수급자 등', '일반 등', 'H-1BL 행복주택', '청년 소득無', null].map(foreignCharacters)).toEqual(['生', '', '', '', ''])
  const { review, workspace, housing } = scenario('gongju')
  const extraction = structuredClone(review.extraction)
  extraction.sheet.targets[0].label = '生계·의료급여수급자 등'
  const cell = createReview(extraction, workspace, housing).labels[0].cells.find(item => item.field === 'label')
  expect(cell).toMatchObject({ state: 'confirm', selected: false })
  expect(cell?.note).toContain('한자·외국 문자(生)')
})

it('일정 일시는 자정이면 날짜만, 시각이 있으면 시:분까지 보여 준다', () => {
  expect(['2026-06-01T00:00:00', '2026-06-01T16:00:00', '2026-06-01T09:30', '2026-06-01', null].map(shownDateTime))
    .toEqual(['2026-06-01', '2026-06-01 16:00', '2026-06-01 09:30', '2026-06-01', '미확인'])
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

it('미확정 공고의 필수 일정·유형이 비면 확인 필요로 표시하고 서버 요청 전에 해당 입력을 안내한다', () => {
  const { review, workspace } = scenario('hansville', undefined, hansvilleLinked)
  workspace.detail.summary.announcement = { ...workspace.detail.summary.announcement!, releaseStatus: 'UNCONFIRMED' }
  workspace.detail.data.rentalType = null
  workspace.detail.data.recruitmentType = null
  review.metadata = review.metadata.map(cell => ['applicationStartDate', 'applicationEndDate', 'winnerAnnouncementDate'].includes(cell.field)
    ? { ...cell, before: null, selected: false } : cell)
  const release = { rentalType: '', recruitmentType: '' }
  expect(pendingItems(review, true, workspace, release).map(item => item.id)).toEqual(expect.arrayContaining([
    'cell:announcement.applicationStartDate', 'cell:announcement.applicationEndDate', 'cell:announcement.winnerAnnouncementDate',
    'release:rentalType', 'release:recruitmentType',
  ]))
  expect(() => assembleReview(review, workspace, evidence, release)).toThrow(/접수 시작일/)
})

describe('접수처 여러 곳', () => {
  const online = { name: 'LH청약플러스', method: 'ONLINE', address: null, contact: '1600-1004', url: 'https://apply.lh.or.kr', guidance: '모바일 앱으로도 신청할 수 있습니다' }
  const visitA = { name: '익산 견본주택', method: 'VISIT', address: '익산시 1', contact: '063-000-0001', url: null, guidance: '인터넷 취약계층만 현장 접수' }
  const visitB = { name: '전주 지사', method: 'VISIT', address: '전주시 2', contact: '063-000-0002', url: null, guidance: null }
  function withPlaces(stored: Record<string, unknown>[]) {
    const extraction = structuredClone(fixtures.gongju.extraction) as unknown as { data: Record<string, unknown>; evidence: { path: string; page: number; quote: string }[] }
    delete extraction.data.receptionPlace
    extraction.data.receptionPlaces = [online, visitA, visitB]
    // 공주 공고의 예전 접수처 안내(해당 없음·미확인)는 첫 접수처 경로로 읽히므로 이 시험에서는 걷어 낸다.
    const notes = extraction as unknown as { notApplicable: { path: string }[]; data: { unresolvedFields: { path: string }[] } }
    notes.notApplicable = notes.notApplicable.filter(item => !item.path.startsWith('receptionPlace'))
    notes.data.unresolvedFields = notes.data.unresolvedFields.filter(item => !item.path.startsWith('receptionPlace'))
    extraction.evidence = [...extraction.evidence.filter(item => !item.path.startsWith('receptionPlace')),
      ...[0, 1, 2].map(index => ({ path: `receptionPlaces[${index}]`, page: 1, quote: `접수처 ${index}` }))]
    const { review: base, workspace, housing } = scenario('gongju', extraction, gongjuLinked)
    workspace.detail.data.receptionPlaces = stored as never
    return { review: createReview(base.extraction, workspace, housing), workspace, housing }
  }

  it('PDF 접수처를 방식·이름이 같은 기존 접수처와 짝짓고, 지금 순서를 지키며 새 접수처는 뒤에 붙이고, 짝 없는 기존 접수처는 빼면 지운다', () => {
    const storedOnline = { ...online, contact: '1600-0000', guidance: null }, mail = { name: '우편 접수', method: 'MAIL', address: '사서함 1', contact: null, url: null, guidance: null }
    const { review, workspace } = withPlaces([mail, storedOnline])
    expect(review.reception.map(entry => [entry.extracted, entry.existing, entry.include])).toEqual([[null, 0, true], [0, 1, true], [1, null, true], [2, null, true]])
    const onlineCells = review.reception[1].cells
    expect(onlineCells.filter(cell => cell.selected).map(cell => cell.field)).toEqual(['contact', 'guidance'])
    const saved = assembleReview(review, workspace, evidence)
    expect(saved.fields).toContain('receptionPlaces')
    expect(saved.document.receptionPlaces).toEqual([mail, online, visitA, visitB])
    // 우편 접수처를 빼면 지운다. 바뀐 값 목록에 접수처 삭제로 보인다.
    const dropped = { ...review, reception: review.reception.map((entry, index) => index === 0 ? { ...entry, include: false } : entry) }
    expect(assembleReview(dropped, workspace, evidence).document.receptionPlaces).toEqual([online, visitA, visitB])
    expect(pendingChanges(dropped).filter(change => change.section === '접수처' && change.cell.label === '접수처 삭제')).toHaveLength(1)
  })

  it('방식이 같아도 주소가 다른 기존 접수처와는 짝짓지 않아 다른 접수처의 주소·연락처가 섞이지 않는다', () => {
    const lh = { name: 'LH 접수처', method: 'VISIT', address: '충주시 2', contact: '043-000-0000', url: null, guidance: null }
    const { review, workspace } = withPlaces([lh])
    expect(review.reception.map(entry => [entry.extracted, entry.existing])).toEqual([[null, 0], [0, null], [1, null], [2, null]])
    expect(assembleReview(review, workspace, evidence).document.receptionPlaces).toEqual([lh, online, visitA, visitB])
  })

  it('이름이 없는 LH 현장 접수처도 도로명·건물번호가 같은 PDF 접수처와 짝짓고, 예전 보정이 따로 더한 같은 곳은 중복으로 기본에서 뺀다', () => {
    const lh = { name: '관리사무소', method: 'VISIT', address: '익산시 목천로 30-14(한스빌) 관리사무소', contact: '063-000-0001', url: null, guidance: '* 자세한 사항은 공고문을 확인하시기 바랍니다.' }
    const pdfVisit = { ...visitA, address: '익산시 목천로 30-14' }
    const { review, workspace } = withPlaces([lh])
    const placed = createReview({ ...review.extraction, receptionPlaces: [online, pdfVisit] }, workspace, scenario('gongju').housing)
    expect(placed.reception.map(entry => [entry.extracted, entry.existing, entry.include])).toEqual([[1, 0, true], [0, null, true]])
    // 지금 주소가 더 자세하면(괄호 속 단지명) 기존 주소를 기본으로 둔다. 이름은 공고문 표기로 바꾼다.
    const visit = placed.reception[0].cells
    expect(visit.find(cell => cell.field === 'name')).toMatchObject({ value: '익산 견본주택', selected: true })
    expect(visit.find(cell => cell.field === 'address')).toMatchObject({ selected: false, note: '현재값이 더 자세합니다.' })
    expect(assembleReview(placed, workspace, evidence).document.receptionPlaces).toHaveLength(2)

    // 예전 보정이 LH 접수처(안내문이 이름이 된 것) 곁에 같은 곳을 따로 더해 둔 공고는, PDF와 짝지은 접수처를 남기고 LH 쪽을 중복으로 뺀다.
    const stale = { ...lh, name: '* 자세한 사항은 반드시 공고문을 확인하시기 바랍니다.' }
    const doubled = withPlaces([stale, { ...pdfVisit, guidance: null }])
    const again = createReview({ ...doubled.review.extraction, receptionPlaces: [online, pdfVisit] }, doubled.workspace, doubled.housing)
    expect(again.reception.map(entry => [entry.extracted, entry.existing, entry.include, entry.duplicateOf ?? null])).toEqual([[null, 0, false, 1], [1, 1, true, null], [0, null, true, null]])
    expect(assembleReview(again, doubled.workspace, evidence).document.receptionPlaces).toEqual([pdfVisit, online])
    // PDF에 현장 접수처가 없어 둘 다 짝이 없으면 앞 접수처를 남기고 뒤 접수처만 중복으로 뺀다.
    const unpaired = createReview({ ...doubled.review.extraction, receptionPlaces: [online] }, doubled.workspace, doubled.housing)
    expect(unpaired.reception.filter(entry => entry.extracted === null).map(entry => [entry.existing, entry.include, entry.duplicateOf ?? null])).toEqual([[0, true, null], [1, false, 0]])
  })

  it('워커가 주소만 있는 현장 접수처를 인터넷 접수로 내도 현장 접수로 읽어 같은 곳인 기존 접수처와 짝짓는다', () => {
    const lh = { name: 'LH 현장 접수처', method: 'VISIT', address: '경기도 성남시 분당구 성남대로 54번길 3', contact: null, url: null, guidance: null }
    const { review, workspace, housing } = withPlaces([lh])
    const placed = createReview({ ...review.extraction, receptionPlaces: [{ ...visitB, method: 'ONLINE', name: 'LH경기남부지역본부 1층 대강당', address: '경기도 성남시 분당구 성남대로54번길 3' }] }, workspace, housing)
    expect(placed.reception.map(entry => [entry.extracted, entry.existing])).toEqual([[0, 0]])
    expect(placed.reception[0].cells.find(cell => cell.field === 'method')).toMatchObject({ value: 'VISIT', state: 'same' })
  })

  it('최신값으로 다시 비교해도 빼기 선택은 같은 접수처에만 남는다', () => {
    const lh = { name: 'LH 접수처', method: 'VISIT', address: '충주시 2', contact: null, url: null, guidance: null }
    const mail = { name: '우편 접수', method: 'MAIL', address: null, contact: null, url: null, guidance: null }
    const { review, workspace, housing } = withPlaces([lh, mail])
    const dropped = { ...review, reception: review.reception.map(entry => entry.existing === 0 ? { ...entry, include: false } : entry) }
    // 그사이 LH 접수처가 지워져 우편 접수가 첫 번째가 됐다. 순번 0의 빼기가 우편 접수로 옮겨가면 안 된다.
    const latest = { ...workspace, detail: { ...workspace.detail, data: { ...workspace.detail.data, receptionPlaces: [mail] as never } } }
    const rebased = rebaseReview(dropped, latest, housing)
    expect(rebased.reception.find(entry => entry.extracted === null)).toMatchObject({ existing: 0, include: true })
    // 우편 접수를 뺐다면 순번이 1에서 0으로 바뀌어도 이름·방식으로 이어받아 계속 뺀다.
    const mailDropped = { ...review, reception: review.reception.map(entry => entry.existing === 1 ? { ...entry, include: false } : entry) }
    expect(rebaseReview(mailDropped, latest, housing).reception.find(entry => entry.extracted === null)).toMatchObject({ existing: 0, include: false })
  })

  it('접수처 원소 형식이 틀려도 공급표까지 버리지 않고 순번을 지켜 20곳까지 읽는다', () => {
    const extraction = structuredClone(fixtures.gongju.extraction) as unknown as { data: Record<string, unknown> }
    delete extraction.data.receptionPlace
    extraction.data.receptionPlaces = ['잘못된 값', ...Array.from({ length: 22 }, (_, index) => ({ ...visitA, name: `현장 ${index}` }))]
    const read = readExtraction(JSON.stringify(extraction))
    // 잘못된 원소는 빈 접수처로 남아 뒤 접수처의 순번(근거·미확인 경로)이 밀리지 않는다.
    expect(read.ok && read.value.receptionPlaces.map(place => place.name ?? null)).toEqual([null, ...Array.from({ length: 19 }, (_, index) => `현장 ${index}`)])
    expect(read.ok && read.value.sheet.housingTypes.length).toBe(fixtures.gongju.extraction.data.supplySheet.housingTypes.length)

    const withEvidence = { ...extraction, evidence: [...(fixtures.gongju.extraction.evidence as { path: string }[]).filter(item => !item.path.startsWith('receptionPlace')), { path: 'receptionPlaces[1]', page: 3, quote: '현장 0 접수' }] }
    const { review } = scenario('gongju', withEvidence)
    const first = review.reception.find(entry => entry.extracted === 1)!
    expect(first.cells.find(cell => cell.field === 'name')?.evidence).toMatchObject({ page: 3 })
    expect(review.reception.find(entry => entry.extracted === 0)).toMatchObject({ include: false })
  })

  it('PDF 접수처가 기존과 같으면 접수처를 바꾼 항목으로 보내지 않는다', () => {
    const { review, workspace } = withPlaces([online, visitA, visitB])
    expect(review.reception.every(entry => entry.cells.every(cell => !cell.selected))).toBe(true)
    expect(assembleReview(review, workspace, evidence).fields).not.toContain('receptionPlaces')
  })

  it('접수처를 한 곳만 다루던 임시저장 검토를 첫 접수처로 읽고 그때 고른 칸 선택을 옮긴다', async () => {
    const { upgradeDraftReview } = await import('./review')
    const { review, workspace } = scenario('gongju')
    const cells = review.reception[0].cells.map(cell => ({ ...cell, path: cell.path.replace('receptionPlaces[0]', 'receptionPlace') }))
    const legacy = { ...review, reception: cells.map(cell => cell.field === 'name' ? editCell(cell, '직접 쓴 접수처') : cell),
      extraction: { ...review.extraction, receptionPlace: fixtures.gongju.extraction.data.receptionPlace, receptionPlaces: undefined,
        evidence: review.extraction.evidence.map(item => ({ ...item, path: item.path.replace('receptionPlaces[0]', 'receptionPlace') })) } } as unknown as typeof review
    const upgraded = upgradeDraftReview(legacy, workspace)
    expect(upgraded.extraction.receptionPlaces).toHaveLength(1)
    expect(upgraded.reception).toHaveLength(1)
    expect(upgraded.reception[0].cells.find(cell => cell.field === 'name')).toMatchObject({ path: 'receptionPlaces[0].name', mode: 'manual', value: '직접 쓴 접수처' })
    expect(upgraded.extraction.evidence.some(item => item.path.startsWith('receptionPlace.') || item.path === 'receptionPlace')).toBe(false)
    expect(upgradeDraftReview(upgraded, workspace)).toBe(upgraded)
  })
})

