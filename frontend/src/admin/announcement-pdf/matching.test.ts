import { describe, expect, it } from 'vitest'
import fixtures from './fixtures/scenarios.json'
import { blankRow, blankTarget, type CorrectionRow } from '../supply/supplyCorrectionApi'
import { readExtraction, type Extraction } from './extraction'
import type { JsonWorkspace } from '../announcement-json/api'
import { blockKey, canonicalTarget, checkAnnouncement, defaultTargetDestinations, emptySourceRows, lhCrossCheck, matchingRows, missingHousingType, proposeRows, proposeSchedules, sourceComplexNames, type HousingCandidates } from './matching'
import { foldSameHousingType } from './subtypes'

function scenario(name: keyof typeof fixtures) {
  const fixture = fixtures[name]
  const result = readExtraction(JSON.stringify(fixture.extraction))
  if (!result.ok) throw new Error(result.error)
  const rows = fixture.rows.map(row => ({ ...blankRow(row.displayOrder), id: row.id, key: `row:${row.id}`,
    complexName: row.sourceComplexName, typeName: row.housingTypeName, pnu: row.pnu,
    housingComplexId: row.complexId, housingTypeId: row.housingTypeId, householdCount: row.householdCount,
    exclusiveArea: row.exclusiveArea, supplyArea: row.supplyArea }))
  const housing = Object.fromEntries(fixture.complexes.map(complex => [complex.id, complex.housingTypes]))
  const events = fixture.events.map(event => ({ id: event.id, data: event, complexName: event.complexName }))
  return { extraction: result.value, rows, housing, events }
}
// 화면과 같이 같은 주택형의 공고문 행을 접은 뒤 저장할 곳을 정한다.
const propose = (extraction: Extraction, rows: CorrectionRow[], housing: HousingCandidates, announcementComplexes: { name: string; roadAddress: string | null }[] = []) =>
  proposeRows(foldSameHousingType(extraction), { rows, announcementComplexes }, housing)

describe('공고 보정 대응 제안', () => {
  it('수집 공고명의 공고일·공고 표기를 무시하고 PDF 공고명·공고일이 현재 공고와 같은지 확인한다', () => {
    const { extraction } = scenario('hansville')
    expect(checkAnnouncement(extraction, { name: '익산한스빌 국민임대주택 예비입주자 자격완화 모집 공고(2026.10.08)', postedDate: '2026-10-08' }).map(check => check.matches)).toEqual([true, true])
    expect(checkAnnouncement(extraction, { name: '광주첨단 H-1,2,3BL 행복주택 예비입주자 모집공고', postedDate: '2026-10-15' }).map(check => check.matches)).toEqual([false, false])
    expect(checkAnnouncement({ ...extraction, announcement: {} }, { name: '익산한스빌', postedDate: null }).map(check => check.matches)).toEqual([null, null])
    // 정정공고 PDF에 적힌 원공고 공고일(앞선 날짜)은 다른 공고의 PDF라고 판단하지 않는다.
    expect(checkAnnouncement(extraction, { name: '[정정공고]익산한스빌 국민임대주택 예비입주자 자격완화 모집 공고', postedDate: '2026-10-10' })[1].matches).toBeNull()
    expect(checkAnnouncement(extraction, { name: '익산한스빌 국민임대주택 예비입주자 자격완화 모집 공고', postedDate: '2026-10-10' })[1].matches).toBe(false)
  })

  it('괄호 위치와 어순이 달라도 같은 단어로 된 공고명은 같은 공고로 본다', () => {
    const { extraction } = scenario('hansville')
    const jeonju = { ...extraction, announcement: { ...extraction.announcement, name: '전주시 영구임대주택 (전주평화1, 전주평화4) 예비입주자 모집' } }
    const current = { name: '전주시(전주평화1,전주평화4) 영구임대주택 예비입주자 모집 공고(공고일:2026.02.10.)' }
    expect(checkAnnouncement(jeonju, current)[0].matches).toBe(true)
    expect(checkAnnouncement(jeonju, { name: '전주시(전주평화1,전주평화5) 영구임대주택 예비입주자 모집 공고' })[0].matches).toBe(false)
    const gimje = { ...extraction, announcement: { ...extraction.announcement, name: '김제검산1 영구임대주택 예비입주자 모집' } }
    expect(checkAnnouncement(gimje, { name: '김제검산1단지 영구임대주택 예비입주자 모집 공고(상시모집)' })[0].matches).toBe(true)
    expect(checkAnnouncement(gimje, { name: '김제검산10단지 영구임대주택 예비입주자 모집 공고' })[0].matches).toBe(false)
  })

  it('공급대상명은 주택형처럼 형을 생략하지 않고 미저장 조건도 정확히 대응한다', () => {
    const row = blankRow(0)
    row.targets = [{ ...blankTarget(0), key: 'draft-target', target: '가형', supplyRank: '1순위' }]
    expect(defaultTargetDestinations({ targets: [{ target: '가', supplyRank: '1순위' }] }, row)).toEqual(['new'])
    expect(defaultTargetDestinations({ targets: [{ target: '가 형', supplyRank: '1 순위' }] }, row)).toEqual(['draft-target'])
  })

  it('대상 설명 괄호만 다른 기존 대상(가군 ↔ 가군 (생계·의료급여수급자 등))은 같은 대상으로 고치고, 괄호를 떼면 겹치는 대상은 잇지 않는다', () => {
    const stored = (id: number, name: string) => ({ ...blankTarget(id), id, key: `target:${id}`, target: name })
    const row = { targets: [stored(1, '가군 (생계·의료급여수급자 등)'), stored(2, '나군 (일반 등)'), stored(3, '청년')] } as unknown as CorrectionRow
    expect(defaultTargetDestinations({ targets: [{ target: '가군' }, { target: '나군' }] }, row)).toEqual(['1', '2'])
    expect(defaultTargetDestinations({ targets: [{ target: '청년(소득無)' }, { target: '청년(소득有)' }] }, row)).toEqual(['new', 'new'])
  })

  it('소득 유무 표기만 다른 기존 대상(청년 소득(×) ↔ 청년(소득 없음))은 같은 대상으로 고친다', () => {
    const stored = (id: number, name: string) => ({ ...blankTarget(id), id, key: `target:${id}`, target: name })
    const row = { targets: [stored(1, '청년 소득(×)'), stored(2, '청년 소득(○)'), stored(3, '대학생')] } as unknown as CorrectionRow
    expect(defaultTargetDestinations({ targets: [{ target: '청년(소득 없음)' }, { target: '청년(소득 있음)' }, { target: '대학생' }] }, row)).toEqual(['1', '2', '3'])
    expect(['청년(소득無)', '청년소득(○)', '청년(소득 X)', '고령자(주거약자용)', '신혼부부· 한부모가족', '신혼부부․ 한부모가족', '신혼부부ㆍ한부모가족', '「가」군'].map(canonicalTarget))
      .toEqual(['청년(소득 없음)', '청년(소득 있음)', '청년(소득 없음)', '고령자(주거약자용)', '신혼부부·한부모가족', '신혼부부·한부모가족', '신혼부부·한부모가족', '가군'])
  })

  it('공급 순위는 대응에 쓰지 않아 순위 표기가 달라도 같은 이름의 기존 조건을 고치고, 같은 이름이 둘이면 고르게 둔다', () => {
    const row = blankRow(0)
    row.targets = [{ ...blankTarget(0), id: 13, target: '가군', supplyRank: '전체' }]
    expect(defaultTargetDestinations({ targets: [{ target: '가군', supplyRank: 1 }] }, row)).toEqual(['13'])
    // 접은 행처럼 같은 이름의 PDF 대상이 둘이면 앞 대상만 기존 조건을 고치고 뒤 대상은 새 조건으로 더한다.
    expect(defaultTargetDestinations({ targets: [{ target: '가군' }, { target: '가군' }] }, row)).toEqual(['13', 'new'])
    row.targets.push({ ...blankTarget(1), id: 14, target: '가군', supplyRank: '2순위' })
    expect(defaultTargetDestinations({ targets: [{ target: '가군', supplyRank: 1 }] }, row)).toEqual([''])
  })

  it('한스빌은 공고문이 모집수 170을 함께 적은 26A·26B를 공급행 하나로 접어 기존 아파트 통합행에 저장한다', () => {
    const { extraction, rows, housing } = scenario('hansville')
    expect(propose(extraction, rows, housing)).toMatchObject([{ destination: 'row:90', anchorKey: 'row:90', complexId: 3055, sharedIndex: null, needsConfirmation: false }])
  })

  it('광주첨단은 신청자 구분마다 나뉜 같은 면적 26형 PDF 행을 공급행 하나로 접고, 같은 면적의 옛 공급행 둘 중 앞 행을 고치며 뒤 행을 제외한다', () => {
    const { extraction, rows, housing } = scenario('gwangju')
    const proposed = propose(extraction, rows, housing)
    expect(proposed.map(row => row.destination)).toEqual(['row:22', 'row:23', 'row:24', 'row:25', 'row:26', 'row:28'])
    expect(proposed[4].supersedes).toEqual(['row:27'])
    expect(proposed[4].reason).toContain('26형(주거급여수급자) · 면적 미확인 · 모집 10세대 · #27')
    expect(proposed.every(row => !row.needsConfirmation)).toBe(true)
  })

  it('공주옥룡1은 같은 면적 주택형이 둘이어도 고르게 하지 않고 기존 아파트 통합행을 고친다', () => {
    const { extraction, rows, housing } = scenario('gongju')
    expect(propose(extraction, rows, housing)[0]).toMatchObject({ destination: 'row:131', needsConfirmation: false })
  })

  it('GH PDF 24B는 모집수가 같아도 전용면적이 다른 기존 행을 고치지 않고 확인 없이 새 공급정보로 추가한다', () => {
    const { extraction } = scenario('hansville')
    const source = extraction.supplyRows[0]
    const pdf = { ...extraction, sharedCounts: [], supplyRows: [{ ...source, complexName: 'GH 단지', typeName: '24B',
      values: { ...source.values, exclusiveArea: 24.2, totalSupplyHouseholdCount: 3 } }] }
    const existing = { ...blankRow(1), id: 34, key: 'row:34', complexName: 'GH 단지', typeName: '29B 고령자',
      housingComplexId: 7, householdCount: 3, exclusiveArea: 29.1 }
    expect(propose(pdf, [existing], {})[0]).toMatchObject({ destination: 'new', anchorKey: 'row:34', complexId: 7, needsConfirmation: false })
  })

  it('광주첨단의 단지별 일정을 이름이 아닌 유형과 기간으로 대응한다', () => {
    const { extraction, events } = scenario('gwangju')
    const proposed = proposeSchedules(extraction, events)
    expect(proposed[0].existingIds).toHaveLength(3)
    expect(proposed[1].existingIds).toEqual([])
    expect(proposed[2].existingIds).toHaveLength(3)
    expect(proposed[3]).toMatchObject({ existingIds: expect.any(Array), needsConfirmation: true })
    expect(proposed[3].existingIds).toHaveLength(3)
    expect(new Set(proposed.flatMap(item => item.existingIds)).size).toBe(12)
  })

  it('표 머리글뿐인 일정 근거는 확인이 필요하다', () => {
    const { extraction, events } = scenario('hansville')
    expect(proposeSchedules(extraction, events)[1]).toMatchObject({ existingIds: [], needsConfirmation: true })
  })

  it('단지명 대응이 없으면 모집수가 유일하게 같아도 기존 행을 고침으로 제안하지 않는다', () => {
    const { extraction, rows, housing } = scenario('gongju')
    const source = extraction.supplyRows[0]
    const renamed = { ...extraction, supplyRows: [{ ...source, complexName: '표기가 다른 단지', values: { ...source.values, totalSupplyHouseholdCount: 450 } }] }
    const current = rows.map((row, at) => ({ ...row, householdCount: at === 0 ? 450 : 999 }))
    const [proposal] = propose(renamed, current, housing)
    expect(proposal).toMatchObject({ destination: 'new', complexId: null })
    expect(proposal.reason).toBe('단지를 하나로 특정하지 못했습니다.')
    const twins = [current[0], { ...current[0], id: 9999, key: 'row:9999' }]
    expect(propose(renamed, twins, housing)[0].reason).toBe('단지를 하나로 특정하지 못했습니다.')
  })

  it('공급행에 붙은 LH 줄은 줄 수·합계를 검산 정보로만 보이고, PDF 모집수와 다르면 차이를 알린다', () => {
    const lh = (order: number, attachedRowKey: string | null, householdCount: number | null, ambiguous = false) =>
      ({ rowIdentifier: `LH:P:SUPPLY:${order}`, complexLabel: '단지', typeName: '36A', exclusiveArea: '36.0', householdCount, attachedRowKey, ambiguous })
    const rows = [lh(1, 'row:1', 300), lh(2, 'row:1', 200), lh(3, 'row:9', 66), lh(4, 'row:2', 10), lh(5, null, 7)]
    expect(lhCrossCheck(rows, ['row:1', 'row:9'], 560)).toMatchObject({ sum: 566, text: 'LH 3줄 합 566세대 · PDF 모집수 560세대와 6세대 다릅니다. 공고문과 대조하세요(저장은 막지 않습니다).' })
    expect(lhCrossCheck(rows, ['row:2'], 10)?.text).toBe('LH 1줄 합 10세대 · PDF 모집수와 같습니다.')
    expect(lhCrossCheck([lh(1, 'row:1', null, true)], ['row:1'], 3)?.text).toContain('합계를 비교하지 않습니다')
    expect(lhCrossCheck([lh(1, 'row:1', null, true)], ['row:1'], 3)?.text).toContain('첫 행에 붙인 LH 줄')
    expect(lhCrossCheck(rows, ['row:7'], 3)).toBeNull()
    // 예비자만 모집하는 공고처럼 LH가 이번 공급 수를 0으로 주면 차이를 알리지 않는다.
    expect(lhCrossCheck([lh(1, 'row:1', 0)], ['row:1'], 34)?.text).toBe('LH 1줄 합 0세대 · LH가 이번 공급 수를 0으로 줘 비교하지 않습니다.')
  })
})

it('단지를 정했는데 같은 전용면적 주택형이 없을 때만 새 주택타입 후보를 돌려준다', () => {
  const types = [{ id: 1, name: '26', exclusiveArea: 26.37, supplyArea: null, householdCount: null }]
  expect(missingHousingType(7, types, ' 39.8500 ', 39.85, 55.2)).toEqual({ name: '39.8500', exclusiveArea: 39.85, supplyArea: 55.2 })
  expect(missingHousingType(7, types, '26', 26.37, null)).toBeNull()
  expect(missingHousingType(null, types, '39', 39.85, null)).toBeNull()
  expect(missingHousingType(7, types, '39', null, null)).toBeNull()
  // 서버 주택형 면적 형식: 전용면적은 0보다 크고 소수 넷째 자리까지, 공급면적은 형식이 맞지 않으면 비운다.
  expect(missingHousingType(7, types, '39', 0, null)).toBeNull()
  expect(missingHousingType(7, types, '39', 39.85125, null)).toBeNull()
  expect(missingHousingType(7, types, '39', 39.85, 55.21255)).toEqual({ name: '39', exclusiveArea: 39.85, supplyArea: null })
})

// 화성동탄2 리츠 정정공고(공고 162): 공고문은 "동탄2 A40BL", LH 공고 단지는 "화성동탄2A-40(공임리츠) A-40"으로 적는다.
function dongtan() {
  const row = (complexName: string, typeName: string, exclusiveArea: number) => ({ complexReference: { sourceComplexName: complexName }, sourceHousingTypeName: typeName,
    exclusiveArea, supplyArea: null, totalSupplyHouseholdCount: 2, expectedMoveInMonth: null, targets: [] })
  const result = readExtraction(JSON.stringify({ data: { announcement: { name: '화성동탄2 리츠' }, receptionPlace: null, schedules: [], unresolvedFields: [],
    complexes: [{ sourceComplexName: '동탄2 A40BL', address: '경기도 화성시 동탄대로12길 71' }, { sourceComplexName: '동탄2 A63BL', address: '경기도 화성시 동탄신리천로 786' }],
    supplyRows: [row('동탄2 A40BL', '084.9600A', 84.96), row('동탄2 A40BL', '084.9600B', 84.96), row('동탄2 A63BL', '059.9800A', 59.98), row('동탄2 A40BL', '101.0000A', 101)] },
  notApplicable: [], evidence: [], sharedCounts: [] }))
  if (!result.ok) throw new Error(result.error)
  const existing = (id: number, complexName: string, typeName: string, exclusiveArea: number, complexId: number) => ({ ...blankRow(id), id, key: `row:${id}`,
    complexName, typeName, exclusiveArea, housingComplexId: complexId, housingTypeId: 5823 })
  const rows = [existing(881, '화성동탄2A-40(공임리츠) A-40', '84.9600A', 84.96, 1723), existing(882, '화성동탄2A-40(공임리츠) A-40', '84.9600B', 84.96, 1723),
    existing(903, '화성동탄2A-63(공임리츠) A63', '59.9800A', 59.98, 1621), existing(904, '화성동탄2 A-81(공임리츠) A-81', '59.9500A', 59.95, 1681)]
  const complexes = [{ name: '화성동탄2A-40(공임리츠) A-40', roadAddress: '경기도 화성시 동탄대로12길 71' }, { name: '화성동탄2A-63(공임리츠) A63', roadAddress: '경기도 화성시 산척동' },
    { name: '화성동탄2 A-81(공임리츠) A-81', roadAddress: '경기도 화성시 산척동' }]
  return { extraction: result.value, rows, complexes }
}

describe('공고문과 LH의 블록 표기가 다른 단지', () => {
  it('블록 번호는 BL·블록·하이픈·앞자리 0 표기를 같게 보고 서로 다른 블록이 섞이면 정하지 않는다', () => {
    expect(['동탄2 A40BL', '화성동탄2A-40(공임리츠) A-40', '화성동탄2 A40블록', '화성동탄(2)A-040'].map(blockKey)).toEqual(['a40', 'a40', 'a40', 'a40'])
    expect(blockKey('(NHF제10호)화성동탄2 A83블럭(28단지 엘크루)')).toBe('a83')
    expect(blockKey('센트럴힐즈')).toBeNull()
    expect(blockKey('광주첨단 H-1 H-2')).toBeNull()
  })

  it('블록 번호와 주소가 함께 가리키는 공고 단지 하나만 대응하고, 둘이 다른 단지를 가리키면 정하지 않는다', () => {
    const { extraction, complexes } = dongtan()
    const names = complexes.map(item => item.name)
    expect(sourceComplexNames(extraction, '동탄2 A40BL', names, complexes)).toEqual({ names: ['화성동탄2A-40(공임리츠) A-40'], certain: true })
    // A63의 LH 주소(산척동)는 공고문 주소와 달라도 블록 번호가 공고 단지 하나에만 맞아 그 단지로 정한다.
    expect(sourceComplexNames(extraction, '동탄2 A63BL', names, complexes)).toEqual({ names: ['화성동탄2A-63(공임리츠) A63'], certain: true })
    const crossed = complexes.map(item => item.name.includes('A-81') ? { ...item, roadAddress: '경기도 화성시 동탄대로12길 71' } : item)
    expect(sourceComplexNames(extraction, '동탄2 A40BL', names, crossed.map(item => item.name.includes('A-40') ? { ...item, roadAddress: null } : item)).names).toEqual([])
  })

  it('같은 면적이어도 신청형이 다르면(084.9600A·084.9600B) 접지 않고 신청형이 같은 기존 행을 각각 고치며, 맞는 행이 없으면 새 공급정보로 추가한다', () => {
    const { extraction, rows, complexes } = dongtan()
    const proposed = propose(extraction, rows, {}, complexes)
    expect(proposed.map(item => item.destination)).toEqual(['row:881', 'row:882', 'row:903', 'new'])
    expect(proposed.map(item => item.supersedes)).toEqual([undefined, undefined, undefined, undefined])
    expect(proposed[3]).toMatchObject({ anchorKey: 'row:881', complexId: 1723 })
    expect(proposed[1].reason).toContain('주택형(신청형)과 전용 84.96㎡가 같은 기존 공급정보')
    expect(proposed[0].reason).toContain("공고 단지 '화성동탄2A-40(공임리츠) A-40'에 대응했습니다.")
  })

  it('블록 번호가 공고 단지 하나에 맞으면 주소 표기가 달라도 확인 없이 그 단지로 두고, 주소만 맞으면 대응을 확인하게 한다', () => {
    const { extraction, rows, complexes } = dongtan()
    const type = (id: number, name: string, exclusiveArea: number) => ({ id, name, exclusiveArea, supplyArea: null, householdCount: null })
    const housing = { 1723: [type(5823, '84.9600A', 84.96), type(5824, '84.9600B', 84.96)], 1621: [type(5705, '59.9800A', 59.98)] }
    expect(propose(extraction, rows, housing, complexes).map(item => item.needsConfirmation)).toEqual([false, false, false, false])
    const names = complexes.map(item => item.name)
    const addressOnly = { ...extraction, complexes: extraction.complexes.map(item => item.name === '동탄2 A40BL' ? { ...item, name: '동탄 리츠' } : item) }
    expect(sourceComplexNames(addressOnly, '동탄 리츠', names, complexes).certain).toBe(false)
  })

  it('신청형이 같은 기존 행이 없으면 전용면적이 같은(소수 둘째 자리) 첫 기존 행을 고치고, 신청형이 다른 같은 면적 행은 제외하지 않는다', () => {
    const { extraction, rows, complexes } = dongtan()
    const renamed = { ...extraction, supplyRows: [{ ...extraction.supplyRows[0], typeName: '084.9600C', values: { ...extraction.supplyRows[0].values, exclusiveArea: 84.9600 } }] }
    const proposed = propose(renamed, rows, {}, complexes)[0]
    expect(proposed).toMatchObject({ destination: 'row:881' })
    expect(proposed.supersedes).toBeUndefined()
  })

  it('신청형이 같은 기존 행이 없어도 다른 PDF 행이 신청형으로 가리키는 같은 면적 행은 가져가지 않는다', () => {
    const { extraction, rows, complexes } = dongtan()
    const first = extraction.supplyRows[0]
    // 084.9600X가 먼저 오지만 #881은 뒤의 084.9600A가 가리키므로 X는 남은 #882를 고친다.
    const pdf = { ...extraction, supplyRows: [{ ...first, typeName: '084.9600X' }, first] }
    expect(propose(pdf, rows, {}, complexes).map(item => item.destination)).toEqual(['row:882', 'row:881'])
  })

  it('신청자 구분마다 나뉘어 저장된 같은 신청형의 옛 행만 함께 제외한다', () => {
    const { extraction, rows, complexes } = dongtan()
    const split = [...rows, { ...rows[0], id: 885, key: 'row:885', typeName: '84.9600A(고령자)', displayOrder: rows[0].displayOrder + 100 }]
    expect(propose(extraction, split, {}, complexes).slice(0, 2).map(item => [item.destination, item.supersedes])).toEqual([['row:881', ['row:885']], ['row:882', undefined]])
  })

  it('LH 공급 API는 참고만 하므로 면적이 조금 다르면 LH 형명이 같아도 기존 행을 고르지 않고 확인 없이 새 행을 추천한다', () => {
    const { extraction, rows, complexes } = dongtan()
    // 기존 행 #903은 LH 정제가 만든 59(전용 59.92)이고, 붙은 LH 줄 형명은 PDF와 같은 59.9000B다. PDF 행은 59.93이다.
    const shown = rows.map(row => row.key === 'row:903' ? { ...row, exclusiveArea: 59.92, typeName: '59' } : row)
    const lh = [{ rowIdentifier: 'LH:P:SUPPLY:9', complexLabel: 'x', typeName: '59.9000B', exclusiveArea: '59.92', householdCount: 1, attachedRowKey: 'row:903', ambiguous: false }]
    const pdf = (area: number) => ({ ...extraction, supplyRows: [{ ...extraction.supplyRows[2], typeName: '059.9000B', values: { ...extraction.supplyRows[2].values, exclusiveArea: area } }] })
    expect(proposeRows(pdf(59.93), { rows: shown, announcementComplexes: complexes, lhSupplyRows: lh }, {})[0]).toMatchObject({ destination: 'new', needsConfirmation: false })
    // 전용면적이 같으면(소수 둘째 자리) 그 행을 고친다.
    expect(proposeRows(pdf(59.92), { rows: shown, announcementComplexes: complexes, lhSupplyRows: lh }, {})[0]).toMatchObject({ destination: 'row:903' })
  })

  it('블록 번호는 맞지만 지구명이 다른 PDF 단지는 확정하지 않는다', () => {
    const { extraction, complexes } = dongtan()
    expect(sourceComplexNames(extraction, '고덕 A40BL', complexes.map(item => item.name), complexes).certain).toBe(false)
  })

  it('공고 단지가 아직 단지에 연결되지 않았으면 다른 단지의 행을 모집수로 추천하지 않고 단지 정하기로 보낸다', () => {
    const { extraction, rows, complexes } = dongtan()
    // A81 행만 이번 모집수(2세대)가 같아 모집수로 고르면 다른 단지 행을 추천하게 된다.
    const unlinked = rows.map(row => row.id === 903 ? { ...row, housingComplexId: null, housingTypeId: null } : row.id === 904 ? { ...row, householdCount: 2 } : row)
    const proposed = propose(extraction, unlinked, {}, complexes)[2]
    expect(proposed).toMatchObject({ destination: 'new', anchorKey: '', complexId: null })
    expect(proposed.reason).toContain('아직 단지에 연결되지 않았습니다')
  })
})

it('공급 표에 단지명이 없어 PDF 단지명이 비면 공고 단지가 하나뿐일 때 그 단지의 단지 없는 원천행을 저장할 곳 후보로 준다', () => {
  const { extraction } = dongtan()
  const row = (id: number, complexName: string) => ({ ...blankRow(id), id, key: `row:${id}`, complexName, housingComplexId: null })
  const single = { supply: { rows: [row(1, '의왕청계2 A1블록'), row(2, '의왕청계2 A1블록')], announcementComplexes: [] } } as unknown as JsonWorkspace
  expect(emptySourceRows(single, extraction, '').map(item => item.key)).toEqual(['row:1', 'row:2'])
  // 공고 단지가 여럿이면 이름 없이 고르지 않는다.
  const several = { supply: { rows: [row(1, '의왕청계2 A1블록'), row(3, '다른 단지')], announcementComplexes: [] } } as unknown as JsonWorkspace
  expect(emptySourceRows(several, extraction, '')).toEqual([])
})

it('주택형이 없어 전용면적이 빈 LH 원천 행은 그 행에 붙은 LH 줄의 면적으로 저장할 곳을 고르고, 같은 면적이면 신청형이 같은 행을 고친다', () => {
  const { extraction, complexes } = dongtan()
  const row = (id: number, typeName: string) => ({ ...blankRow(id), id, key: `row:${id}`, complexName: '화성동탄2A-40(공임리츠) A-40', typeName, exclusiveArea: null, housingComplexId: 1723, housingTypeId: null })
  const lh = (key: string, typeName: string) => ({ rowIdentifier: `LH:P:SUPPLY:${key}`, complexLabel: 'x', typeName, exclusiveArea: '84.9600', householdCount: 1, attachedRowKey: key, ambiguous: false })
  const rows = [row(881, '84.9600A'), row(882, '84.9600B')]
  const lhSupplyRows = [lh('row:881', '84.9600A'), lh('row:882', '84.9600B')]
  expect(matchingRows(rows, lhSupplyRows).map(item => item.exclusiveArea)).toEqual([84.96, 84.96])
  // 저장 값은 그대로 두고 고르기에만 쓴다.
  expect(rows.map(item => item.exclusiveArea)).toEqual([null, null])
  const proposed = proposeRows(extraction, { rows, announcementComplexes: complexes, lhSupplyRows }, {})
  expect(proposed.slice(0, 2).map(item => item.destination)).toEqual(['row:881', 'row:882'])
})

describe('작업실 행 이름이 연결 주택타입 이름인 기존 행(서버가 공고 표기 없이 보내는 모양)', () => {
  // 정제는 신청형이 다른 55A1·55C(주거약자) 행을 같은 주택타입 '55A1'(55.72㎡)에 잇는다. 작업실 이름은 둘 다 '55A1'이고,
  // 서버는 행별 신청형 이름(applicationTypeNames)을 따로 준다. 표시 순서는 PDF 순서와 거꾸로 둬 순서 덕에 맞는 경우를 거른다.
  const setup = () => {
    const complexName = '의왕청계2 A1블록'
    const row = (id: number, order: number) => ({ ...blankRow(id), id, key: `row:${id}`, complexName, typeName: '55A1', exclusiveArea: 55.72, housingComplexId: 7, housingTypeId: 70, displayOrder: order })
    const pdf = (name: string) => ({ complexReference: { sourceComplexName: complexName }, sourceHousingTypeName: name, exclusiveArea: 55.72, supplyArea: null, totalSupplyHouseholdCount: 1, expectedMoveInMonth: null, targets: [] })
    const read = readExtraction(JSON.stringify({ data: { announcement: {}, receptionPlace: null, schedules: [], unresolvedFields: [], supplyRows: [pdf('55A1'), pdf('55C (주거약자)')] }, notApplicable: [], evidence: [], sharedCounts: [] }))
    if (!read.ok) throw new Error(read.error)
    const housing = { 7: [{ id: 70, name: '55A1', exclusiveArea: 55.72, supplyArea: null, householdCount: null }] }
    return { extraction: read.value, housing, rows: [row(2477, 0), row(2473, 1)], applicationTypeNames: { 'row:2473': '55A1', 'row:2477': '55C(주거약자)' } }
  }

  it('서버가 준 신청형 이름으로 가려 PDF 55A1·55C가 각자의 행을 고치고 서로를 제외하지 않는다(LH 줄이 없어도)', () => {
    const { extraction, housing, rows, applicationTypeNames } = setup()
    const proposed = proposeRows(extraction, { rows, applicationTypeNames }, housing)
    expect(proposed.map(item => [item.destination, item.supersedes])).toEqual([['row:2473', undefined], ['row:2477', undefined]])
  })

  it('모호하게 첫 행에 붙은 LH 줄이 있어도 신청형 이름은 서버 값을 쓴다', () => {
    const { extraction, housing, rows, applicationTypeNames } = setup()
    const lh = (typeName: string) => ({ rowIdentifier: `LH:P:SUPPLY:${typeName}`, complexLabel: 'x', typeName, exclusiveArea: '55.7200', householdCount: 1, attachedRowKey: 'row:2477', ambiguous: true })
    const proposed = proposeRows(extraction, { rows, applicationTypeNames, lhSupplyRows: [lh('55A1'), lh('55C(주거약자)')] }, housing)
    expect(proposed.map(item => [item.destination, item.supersedes])).toEqual([['row:2473', undefined], ['row:2477', undefined]])
  })
})

it('신청형이 같은데 면적만 조금 다른 기존 행(59.92 ↔ 59.93)은 고르지 않고 새로 추가하면서 함께 제외한다(서버는 같은 신청형 행을 하나만 둔다)', () => {
  const { extraction, rows, complexes } = dongtan()
  const shown = rows.map(row => row.key === 'row:903' ? { ...row, exclusiveArea: 59.92, typeName: '59.9800A' } : row)
  const pdf = { ...extraction, supplyRows: [{ ...extraction.supplyRows[2], values: { ...extraction.supplyRows[2].values, exclusiveArea: 59.93 } }] }
  expect(proposeRows(pdf, { rows: shown, announcementComplexes: complexes }, {})[0]).toMatchObject({ destination: 'new', supersedes: ['row:903'] })
})
