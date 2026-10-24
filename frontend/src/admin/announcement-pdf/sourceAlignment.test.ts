import { describe, expect, it } from 'vitest'
import { blankRow } from '../supply/supplyCorrectionApi'
import { scenario } from './fixtures/workspace'
import fixtures from './fixtures/scenarios.json'
import { evidenceFor, noteFor, readExtraction } from './extraction'
import { assembleReview, createReview } from './review'
import { alignSourceRows, parseSourceLabel } from './sourceAlignment'

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
  function setup() {
    const base = structuredClone(fixtures.hansville.extraction) as unknown as Record<string, unknown> & { data: Record<string, unknown> }
    base.data.supplyRows = [pdfRow('16A', 16.37, ['대학생', '청년 소득(×)', '기존거주자(철거민)'], 78),
      pdfRow('26A', 26.01, ['고령자', '주거급여수급자', '기존거주자(철거민)'], null), pdfRow('26B', 26.1, ['고령자', '주거급여수급자', '기존거주자(철거민)'], null)]
    base.sharedCounts = [{ paths: ['supplyRows[1].totalSupplyHouseholdCount', 'supplyRows[2].totalSupplyHouseholdCount'], count: 46, page: 3, quote: '26A/B/C 46' }]
    base.notApplicable = [{ path: 'supplyRows[1].targets[2].supplyRank', reason: '순위 없음' }]
    base.evidence = [0, 1, 2].map(index => ({ path: `supplyRows[${index}]`, page: 3, quote: `행 ${index}` }))
    const extraction = readExtraction(JSON.stringify(base))
    if (!extraction.ok) throw new Error(extraction.error)
    const { workspace } = scenario('hansville')
    workspace.supply.rows = [source(10, '16A(대학생/청년)', 78), source(11, '26A/B(고령자)', 4), source(12, '26A/B(주거급여수급자)', 42), source(13, '44A/B(신혼부부·한부모)', 86)]
    const review = createReview(extraction.value, workspace, {})
    const candidates = [{ id: 1, name: '16A', exclusiveArea: 16.37, supplyArea: null, householdCount: null }, { id: 2, name: '26A', exclusiveArea: 26.01, supplyArea: null, householdCount: null },
      { id: 3, name: '26B', exclusiveArea: 26.1, supplyArea: null, householdCount: null }]
    const aligned = alignSourceRows(review, workspace, '인천부평(도시재생뉴딜) 행복주택', { id: 7, name: '인천부평' }, candidates as never)!
    return { aligned, workspace, review }
  }

  it('하나에 맞는 LH 행은 연결하고, 여러 세부 타입에 맞는 LH 행은 첫 타입으로 고쳐 쓰고 나머지를 새 행으로 나눈다', () => {
    const { aligned } = setup()
    expect(aligned.extraction.supplyRows.map(row => row.typeName)).toEqual(['16A', '26A', '26A', '26B', '26B'])
    expect(aligned.rows.map(row => [row.proposal.destination, row.proposal.housingTypeId, row.proposal.newComplex?.pnu])).toEqual([
      ['row:10', 1, '2823710100100650017'], ['row:11', 2, '2823710100100650017'], ['row:12', 2, '2823710100100650017'],
      ['new', 3, '2823710100100650017'], ['new', 3, '2823710100100650017']])
    // 공고문의 26A/B 공통 46 대신 LH 행 단위 공통 모집수(고령자 4, 주거급여수급자 42)로 묶는다.
    expect(aligned.extraction.sharedCounts.map(group => [group.paths, group.count])).toEqual([
      [['supplyRows[1].totalSupplyHouseholdCount', 'supplyRows[3].totalSupplyHouseholdCount'], 4],
      [['supplyRows[2].totalSupplyHouseholdCount', 'supplyRows[4].totalSupplyHouseholdCount'], 42]])
    expect(aligned.rows.map(row => row.newHousingType)).toEqual([false, false, false, false, false])
  })

  it('공급대상 묶음마다 행을 복사하고 대상은 묶음 이름으로 나누며, 묶음에 없는 대상은 첫 묶음에 넣고 확인하게 한다', () => {
    const { aligned } = setup()
    expect(aligned.extraction.supplyRows.map(row => row.targets.map(item => item.target))).toEqual([
      ['대학생', '청년 소득(×)', '기존거주자(철거민)'], ['고령자', '기존거주자(철거민)'], ['주거급여수급자'], ['고령자', '기존거주자(철거민)'], ['주거급여수급자']])
    expect(aligned.rows[1].proposal.reason).toContain('기존거주자(철거민)은(는) LH 공급대상 묶음에 없어 이 행에 넣었습니다.')
    expect(aligned.rows[1].proposal.needsConfirmation).toBe(true)
    // 대상 경로의 메모와 공급행 근거도 복사한 행의 새 번호로 옮긴다.
    expect(noteFor(aligned.extraction.notApplicable, 'supplyRows[1].targets[1].supplyRank')?.reason).toBe('순위 없음')
    expect(evidenceFor(aligned.extraction.evidence, 'supplyRows[4].targets[0].rentalDeposit')?.quote).toBe('행 2')
  })

  it('저장하면 LH 행을 그 단지·주택형으로 고치고 나눈 행과 LH 공급 수로 공통 모집 묶음을 만든다. 맞는 공고문 행이 없는 LH 행은 그대로 둔다', () => {
    const { aligned, workspace } = setup()
    const input = assembleReview(aligned, workspace, { reason: 'PDF 대조', sourceUrl: 'https://example.com/notice.pdf', sourcePage: '3' })
    const rows = input.correction.rows
    expect(rows.find(row => row.key === 'row:11')).toEqual(expect.objectContaining({ housingComplexId: 7, housingTypeId: 2, typeName: '26A', householdCount: null, excluded: false }))
    expect(rows.find(row => row.key === 'row:13')).toEqual(expect.objectContaining({ housingComplexId: null, excluded: false }))
    const added = rows.filter(row => row.id === null)
    expect(added.map(row => [row.typeName, row.housingTypeId, row.pnu, row.category, row.targets.map(item => item.target)])).toEqual([
      ['26B', 3, '2823710100100650017', 'NEW_SUPPLY', ['고령자', '기존거주자(철거민)']], ['26B', 3, '2823710100100650017', 'NEW_SUPPLY', ['주거급여수급자']]])
    expect(input.correction.newGroups).toEqual([{ sourceRowId: 11, rowKeys: ['row:11', added[0].key], sharedHouseholdCount: 4 },
      { sourceRowId: 12, rowKeys: ['row:12', added[1].key], sharedHouseholdCount: 42 }])
  })

  it('단지를 다시 고르면 맞추기 전 검토에서 다시 맞춰 행을 거듭 복사하지 않는다', () => {
    const { aligned, workspace } = setup()
    const again = alignSourceRows(aligned, workspace, '인천부평(도시재생뉴딜) 행복주택', { id: 8, name: '다른 단지' }, [])!
    expect(again.rows).toHaveLength(5)
    expect(again.rows.every(row => row.proposal.newComplex?.id === 8)).toBe(true)
  })
})
