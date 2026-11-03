import { describe, expect, it } from 'vitest'
import { blankRow } from '../supply/supplyCorrectionApi'
import { scenario } from './fixtures/workspace'
import fixtures from './fixtures/scenarios.json'
import { evidenceFor, noteFor, readExtraction } from './extraction'
import { assembleReview, createReview, linkedHousingTypeId } from './review'
import { alignSourceRows } from './sourceAlignment'
import { parseSourceLabel } from './subtypes'

describe('LH 원천행 표기 읽기', () => {
  it.each([
    ['26A/B/C(고령자)', ['26A', '26B', '26C'], '고령자'],
    ['26A,B', ['26A', '26B'], null],
    ['16A(대학생/청년)', ['16A'], '대학생/청년'],
    ['36AS(고령자/주거약자용)', ['36AS'], '고령자/주거약자용'],
  ])('%s', (label, types, group) => expect(parseSourceLabel(label)).toEqual({ types, group }))
})

describe('최초 입주자 공고의 LH 원천행 맞추기', () => {
  const target = (name: string) => ({ target: name, supplyRank: null, supplyHouseholdCount: null, reserveCount: null, rentalDeposit: 10000000, monthlyRent: 100000, convertedDeposit: null, applicationCondition: null })
  const pdfRow = (name: string, exclusiveArea: number, targets: string[], total: number | null) => ({ complexReference: { sourceComplexName: '인천부평(도시재생뉴딜) 행복주택' },
    sourceHousingTypeName: name, expectedMoveInMonth: null, totalSupplyHouseholdCount: total, exclusiveArea, commonArea: null, supplyArea: null, constructedHouseholdCount: null, subtypes: [], targets: targets.map(target) })
  const source = (id: number, typeName: string, householdCount: number) => ({ ...blankRow(id), id, key: `row:${id}`, complexName: '인천부평(도시재생뉴딜)',
    typeName, householdCount, pnu: '2823710100100650017', category: 'NEW_SUPPLY' as const, areaOverride: false })
  function setup(firstName = '16A', firstArea: number | null = null, secondName = '26A', secondArea = 26.01, thirdDeposit = 10000000) {
    const base = structuredClone(fixtures.hansville.extraction) as unknown as Record<string, unknown> & { data: Record<string, unknown> }
    base.data.supplyRows = [pdfRow(firstName, 16.37, ['대학생', '청년 소득(×)', '기존거주자(철거민)'], 78),
      pdfRow(secondName, secondArea, ['고령자', '주거급여수급자', '기존거주자(철거민)'], null),
      { ...pdfRow('26B', 26.1, ['고령자', '주거급여수급자', '기존거주자(철거민)'], null), targets: ['고령자', '주거급여수급자', '기존거주자(철거민)'].map(name => ({ ...target(name), rentalDeposit: thirdDeposit })) }]
    base.sharedCounts = [{ paths: ['supplyRows[1].totalSupplyHouseholdCount', 'supplyRows[2].totalSupplyHouseholdCount'], count: 46, page: 3, quote: '26A/B/C 46' }]
    base.notApplicable = [{ path: 'supplyRows[1].targets[2].supplyRank', reason: '순위 없음' }]
    // 공급 표 한 줄 인용처럼 주택형 표기를 담는다.
    base.evidence = [firstName, secondName, '26B'].map((name, index) => ({ path: `supplyRows[${index}]`, page: 3, quote: `${name} 행 ${index}` }))
    const extraction = readExtraction(JSON.stringify(base))
    if (!extraction.ok) throw new Error(extraction.error)
    const { workspace } = scenario('hansville')
    workspace.supply.rows = [{ ...source(10, '16A(대학생/청년)', 78), exclusiveArea: firstArea }, source(11, '26A/B(고령자)', 4), source(12, '26A/B(주거급여수급자)', 42), source(13, '44A/B(신혼부부·한부모)', 86)]
    const review = createReview(extraction.value, workspace, {})
    const candidates = [{ id: 1, name: '16A', exclusiveArea: 16.37, supplyArea: null, householdCount: null }, { id: 2, name: '26A', exclusiveArea: 26.01, supplyArea: null, householdCount: null },
      { id: 3, name: '26B', exclusiveArea: 26.1, supplyArea: null, householdCount: null }]
    const aligned = alignSourceRows(review, workspace, '인천부평(도시재생뉴딜) 행복주택', { id: 7, name: '인천부평' }, candidates as never)!
    return { aligned, workspace, review, extraction: extraction.value }
  }

  it('하나에 맞는 LH 행은 연결하고, 여러 세부 타입에 맞는 LH 행은 공고문 행들을 그 LH 행 하나의 세부 타입 묶음으로 접는다', () => {
    const { aligned } = setup()
    expect(aligned.extraction.supplyRows.map(row => [row.typeName, row.values.totalSupplyHouseholdCount, row.subtypes.map(subtype => subtype.name)])).toEqual([
      ['16A', 78, []], ['26A/B(고령자)', 4, ['26A', '26B']], ['26A/B(주거급여수급자)', 42, ['26A', '26B']]])
    expect(aligned.rows.map(row => [row.proposal.destination, row.proposal.newComplex?.pnu, row.subtypes?.map(subtype => subtype.choice)])).toEqual([
      ['row:10', '2823710100100650017', undefined], ['row:11', '2823710100100650017', [2, 3]], ['row:12', '2823710100100650017', [2, 3]]])
    expect(aligned.rows.map(row => linkedHousingTypeId(row))).toEqual([1, 2, 2])
    // 공고문의 26A/B 공통 46은 서로 다른 LH 행에 걸쳐 있어 쓰지 않고, LH 행마다 그 행의 이번 공급 수를 모집수로 둔다.
    expect(aligned.extraction.sharedCounts).toEqual([])
    // 형명이 하나로 맞고 주택형도 정한 1:1 연결(16A)만 확인 없이 두고, 접은 행은 확인하게 한다.
    expect(aligned.rows.map(row => row.proposal.needsConfirmation)).toEqual([false, true, true])
    expect(aligned.rows[1].proposal.reason).toContain("LH 원천 '26A/B(고령자)' 한 행에 공고문 26A·26B를 세부 타입으로 함께 연결합니다.")
  })

  it('LH 이름과 공고문 표기가 달라도(16A(대학생/청년) ↔ 16) 아직 맞추지 않은 행 중 전용면적이 하나만 같으면 맞추고 확인하게 한다', () => {
    const { aligned } = setup('16', 16.37)
    expect(aligned.rows[0].proposal.destination).toBe('row:10')
    expect(aligned.rows[0].proposal.needsConfirmation).toBe(true)
    expect(aligned.rows[0].proposal.reason).toContain("형명이 달라(LH '16A(대학생/청년)', 공고문 '16') 전용면적으로 맞췄습니다")
    // LH 면적을 모르거나 같은 면적의 공고문 행이 둘 이상이면 맞추지 않는다.
    expect(setup('16').aligned.rows[0].proposal.destination).not.toBe('row:10')
    expect(setup('16', 16.37, '16B', 16.37).aligned.rows[0].proposal.destination).not.toBe('row:10')
  })

  it('공급대상 묶음마다 행을 복사하고 대상은 묶음 이름으로 나누며, 묶음에 없는 대상은 첫 묶음에 넣고 확인하게 한다', () => {
    const { aligned } = setup()
    expect(aligned.extraction.supplyRows.map(row => row.targets.map(item => item.target))).toEqual([
      ['대학생', '청년 소득(×)', '기존거주자(철거민)'], ['고령자', '기존거주자(철거민)'], ['주거급여수급자']])
    expect(aligned.rows[1].proposal.reason).toContain('기존거주자(철거민)은(는) LH 공급대상 묶음에 없어 이 행에 넣었습니다.')
    expect(aligned.rows[1].proposal.needsConfirmation).toBe(true)
    // 대상 경로의 메모와 공고문 행 근거도 접은 행의 새 번호·세부 타입으로 옮긴다.
    expect(noteFor(aligned.extraction.notApplicable, 'supplyRows[1].targets[1].supplyRank')?.reason).toBe('순위 없음')
    expect(evidenceFor(aligned.extraction.evidence, 'supplyRows[2].subtypes[1].exclusiveArea')?.quote).toBe('26B 행 2')
    expect(evidenceFor(aligned.extraction.evidence, 'supplyRows[2].targets[0].rentalDeposit')?.quote).toBe('26A 행 1')
  })

  it('저장하면 LH 행 하나에 모집수를 두고 세부 타입 주택형을 모두 연결하며 공통 모집 묶음을 만들지 않는다. 맞는 공고문 행이 없는 LH 행은 그대로 둔다', () => {
    const { aligned, workspace } = setup()
    const input = assembleReview(aligned, workspace, { reason: 'PDF 대조', sourceUrl: 'https://example.com/notice.pdf', sourcePage: '3' })
    const rows = input.correction.rows
    expect(rows.find(row => row.key === 'row:11')).toEqual(expect.objectContaining({ housingComplexId: 7, housingTypeId: 2, typeName: '26A/B(고령자)', householdCount: 4, excluded: false, linkedHousingTypes: [{ housingTypeId: 3 }] }))
    expect(rows.find(row => row.key === 'row:12')).toEqual(expect.objectContaining({ housingTypeId: 2, householdCount: 42, linkedHousingTypes: [{ housingTypeId: 3 }] }))
    expect(rows.find(row => row.key === 'row:13')).toEqual(expect.objectContaining({ housingComplexId: null, excluded: false }))
    expect(rows.filter(row => row.id === null)).toEqual([])
    expect(input.correction.newGroups).toEqual([])
    // 주택형 값은 세부 타입마다 그 이름으로 보내고, 대표가 아닌 세부 타입은 함께 모집하는 주택형으로 가리킨다.
    expect(input.housingTypeValues?.filter(value => value.rowKey === 'row:11').map(value => [value.name, value.linkedHousingTypeId])).toEqual([['26A', undefined], ['26B', 3]])
  })

  it('LH 한 행에 맞춘 공고문 행들의 대상·금액이 다르면 접지 않고 행마다 둔 뒤 LH 공급 수를 공통 모집수로 묶는다', () => {
    const { aligned, workspace } = setup('16A', null, '26A', 26.01, 20000000)
    expect(aligned.extraction.supplyRows.map(row => row.typeName)).toEqual(['16A', '26A', '26A', '26B', '26B'])
    expect(aligned.rows.map(row => row.proposal.destination)).toEqual(['row:10', 'row:11', 'row:12', 'new', 'new'])
    expect(aligned.rows.slice(1).every(row => row.proposal.needsConfirmation)).toBe(true)
    const input = assembleReview(aligned, workspace, { reason: 'PDF 대조', sourceUrl: 'https://example.com/notice.pdf', sourcePage: '3' })
    const added = input.correction.rows.filter(row => row.id === null)
    expect(input.correction.newGroups).toEqual([{ sourceRowId: 11, rowKeys: ['row:11', added[0].key], sharedHouseholdCount: 4 },
      { sourceRowId: 12, rowKeys: ['row:12', added[1].key], sharedHouseholdCount: 42 }])
  })

  it('같은 세부 타입을 가진 LH 행이 공급대상 묶음마다 단지에 연결돼 있으면 묶음에 없는 대상이 섞인 공고문 행을 한 LH 행으로 접지 않는다', () => {
    const { workspace, extraction } = setup()
    // 앞선 보정으로 26A/B(고령자)·26A/B(주거급여수급자)가 단지 7에 연결된 상태에서 같은 PDF로 다시 보정한다.
    workspace.supply.rows = workspace.supply.rows.map(row => [11, 12].includes(row.id!) ? { ...row, housingComplexId: 7, housingComplexName: '인천부평' } : row)
    const review = createReview(extraction, workspace, { 7: [] })
    expect(review.extraction.supplyRows.map(row => row.typeName)).toEqual(['16A', '26A', '26B'])
    const toEleven = review.rows.filter(row => row.proposal.destination === 'row:11')
    expect(toEleven.flatMap(row => row.targets.map(target => target.cells.find(cell => cell.field === 'target')?.value))).not.toContain('주거급여수급자')
    expect(toEleven.map(row => row.cells.find(cell => cell.field === 'totalSupplyHouseholdCount')?.value)).not.toContain(46)
  })

  it('단지를 다시 고르면 맞추기 전 검토에서 다시 맞춰 행을 거듭 복사하지 않는다', () => {
    const { aligned, workspace } = setup()
    const again = alignSourceRows(aligned, workspace, '인천부평(도시재생뉴딜) 행복주택', { id: 8, name: '다른 단지' }, [])!
    expect(again.rows).toHaveLength(3)
    expect(again.rows.every(row => row.proposal.newComplex?.id === 8)).toBe(true)
  })
})

describe('미연결 단지가 여럿인 공고의 LH 원천행 맞추기', () => {
  const announcementComplex = (roadAddress: string) => ({ id: 186, name: '화성동탄(2)A48(공임리츠) A48', roadAddress, housingComplexId: null, housingComplexName: null, linkMethod: null, rowCount: 1 })
  // 화성동탄2 리츠 공고 114: 공고문 "동탄2 A48BL"·"동탄2 A63BL"이 각각 단지에 연결되지 않은 LH 공고 단지 행을 가리킨다.
  function setup() {
    const row = (complexName: string, typeName: string, exclusiveArea: number) => ({ complexReference: { sourceComplexName: complexName }, sourceHousingTypeName: typeName,
      exclusiveArea, supplyArea: null, totalSupplyHouseholdCount: 3, expectedMoveInMonth: null, targets: [] })
    const extraction = readExtraction(JSON.stringify({ data: { announcement: {}, receptionPlace: null, schedules: [], unresolvedFields: [],
      complexes: [{ sourceComplexName: '동탄2 A48BL', address: '경기도 화성시 동탄순환대로20길 69' }],
      supplyRows: [row('동탄2 A48BL', '059.5500A', 59.55), row('동탄2 A63BL', '059.9800A', 59.98)] }, notApplicable: [], evidence: [], sharedCounts: [] }))
    if (!extraction.ok) throw new Error(extraction.error)
    const { workspace } = scenario('hansville')
    const lh = (id: number, complexName: string, typeName: string) => ({ ...blankRow(id), id, key: `row:${id}`, complexName, typeName, householdCount: 0, areaOverride: false })
    workspace.supply.rows = [lh(642, '화성동탄(2)A48(공임리츠) A48', '59.5500A'), lh(655, '화성동탄2A-63(공임리츠) A63', '59.9800A')]
    workspace.supply.announcementComplexes = [announcementComplex('경기도 화성시 동탄순환대로20길 69')]
    return { review: createReview(extraction.value, workspace, {}), workspace }
  }

  it('블록 표기가 다른 단지를 차례로 정해도 앞서 맞춘 단지가 풀리지 않고, 같은 단지를 다시 정하면 다른 단지의 맞춤을 유지한다', () => {
    const { review, workspace } = setup()
    const aligned = alignSourceRows(review, workspace, '동탄2 A48BL', { id: 1724, name: '화성동탄2 A48BL' }, [])
    expect(aligned?.rows[0].proposal.destination).toBe('row:642')
    // 첫 단지를 맞춘 뒤 고친 선택(A48 행 제외)도 다음 단지를 맞출 때 남아야 한다.
    const first = { ...aligned!, rows: aligned!.rows.map((row, index) => index === 0 ? { ...row, include: false } : row) }
    const both = alignSourceRows(first, workspace, '동탄2 A63BL', { id: 1621, name: '화성동탄2 A63블록' }, [])!
    expect(both.rows.map(row => [row.proposal.destination, row.proposal.newComplex?.id, row.include])).toEqual([['row:642', 1724, false], ['row:655', 1621, true]])
    const again = alignSourceRows(both, workspace, '동탄2 A48BL', { id: 9, name: '다른 단지' }, [])!
    expect(again.rows.map(row => [row.proposal.destination, row.proposal.newComplex?.id])).toEqual([['row:642', 9], ['row:655', 1621]])
  })

  it('형이 하나인 LH 행에 1:1로 맞으면 확인 없이 두고, 여러 형을 묶은 LH 행에 한 형만 맞으면 확인하게 한다', () => {
    const { review, workspace } = setup()
    const candidates = [{ id: 1, name: '059.5500A', exclusiveArea: 59.55, supplyArea: null, householdCount: null }]
    const confirm = (typeName: string, roadAddress = '경기도 화성시 동탄순환대로20길 69') => {
      const rows = workspace.supply.rows.map(row => row.id === 642 ? { ...row, typeName } : row)
      const supply = { ...workspace.supply, rows, announcementComplexes: [announcementComplex(roadAddress)] }
      return alignSourceRows(review, { ...workspace, supply }, '동탄2 A48BL', { id: 1724, name: '화성동탄2 A48BL' }, candidates)?.rows[0].proposal.needsConfirmation
    }
    expect(confirm('59.5500A')).toBe(false)
    expect(confirm('59.5500A/59.9200C')).toBe(true)
    // LH 주소가 공고문 주소와 달라 블록 번호로만 대응한 단지면 1:1이어도 확인하게 한다.
    expect(confirm('59.5500A', '경기도 화성시 산척동')).toBe(true)
  })
})
