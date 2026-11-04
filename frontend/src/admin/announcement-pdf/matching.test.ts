import { describe, expect, it } from 'vitest'
import fixtures from './fixtures/scenarios.json'
import { blankRow, blankTarget } from '../supply/supplyCorrectionApi'
import { readExtraction } from './extraction'
import { blockKey, checkAnnouncement, defaultTargetDestinations, missingHousingType, proposeRows, proposeSchedules, sourceComplexNames } from './matching'
import { sameTargets } from './subtypes'

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

  it('공급 순위만 다른 공고문 행도 대상이 같다고 보아 접기 제안을 막지 않는다', () => {
    const row = (supplyRank: unknown) => ({ complexName: null, typeName: null, values: {}, subtypes: [], targets: [{ target: '가군', supplyRank, rentalDeposit: 2520000, monthlyRent: 49180 }] })
    expect(sameTargets([row(1), row(null)])).toBe(true)
    expect(sameTargets([row(1), { ...row(1), targets: [{ target: '가군', supplyRank: 1, rentalDeposit: 2520001, monthlyRent: 49180 }] }])).toBe(false)
  })

  it('공급 순위는 대응에 쓰지 않아 순위 표기가 달라도 같은 이름의 기존 조건을 고치고, 같은 이름이 둘이면 고르게 둔다', () => {
    const row = blankRow(0)
    row.targets = [{ ...blankTarget(0), id: 13, target: '가군', supplyRank: '전체' }]
    expect(defaultTargetDestinations({ targets: [{ target: '가군', supplyRank: 1 }] }, row)).toEqual(['13'])
    row.targets.push({ ...blankTarget(1), id: 14, target: '가군', supplyRank: '2순위' })
    expect(defaultTargetDestinations({ targets: [{ target: '가군', supplyRank: 1 }] }, row)).toEqual([''])
  })

  it('한스빌의 한 행을 두 행으로 제안하고 서로 다른 면적의 주택형을 연결한다', () => {
    const { extraction, rows, housing } = scenario('hansville')
    expect(proposeRows(extraction, rows, housing)).toMatchObject([
      { destination: 'row:90', anchorKey: 'row:90', housingTypeId: 10353, sharedIndex: 0, needsConfirmation: true },
      { destination: 'new', anchorKey: 'row:90', housingTypeId: 10352, sharedIndex: 0, needsConfirmation: true },
    ])
  })

  it('광주첨단은 단지 포함 관계와 이름으로 같은 26형의 반대 순서를 구분한다', () => {
    const { extraction, rows, housing } = scenario('gwangju')
    const proposed = proposeRows(extraction, rows, housing)
    expect(proposed.map(row => row.destination)).toEqual(['row:22', 'row:23', 'row:24', 'row:25', 'row:27', 'row:26', 'row:28'])
    expect(proposed.map(row => row.housingTypeId)).toEqual([3039, 3032, 3034, 3035, 3036, 3036, 3037])
  })

  it('공주옥룡1은 공급면적이 미확인인 같은 전용면적의 두 후보 중 하나를 추측하지 않는다', () => {
    const { extraction, rows, housing } = scenario('gongju')
    expect(proposeRows(extraction, rows, housing)[0]).toMatchObject({ destination: 'row:131', housingTypeId: null, needsConfirmation: true })
  })

  it('같은 면적일 때 건설호수로 주택형을 구분한다', () => {
    const { extraction, rows, housing } = scenario('gongju')
    housing[2467][0].householdCount = 443
    housing[2467][1].householdCount = 20
    expect(proposeRows(extraction, rows, housing)[0].housingTypeId).toBe(8575)
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

  it('단지명 표기가 달라도 이번 모집수가 유일하게 같은 기존 행을 확인 필요로 제안한다', () => {
    const { extraction, rows, housing } = scenario('gongju')
    const source = extraction.supplyRows[0]
    const renamed = { ...extraction, supplyRows: [{ ...source, complexName: '표기가 다른 단지', values: { ...source.values, totalSupplyHouseholdCount: 450 } }] }
    const current = rows.map((row, at) => ({ ...row, householdCount: at === 0 ? 450 : 999 }))
    const [proposal] = proposeRows(renamed, current, housing)
    expect(proposal).toMatchObject({ destination: current[0].key, needsConfirmation: true })
    expect(proposal.reason).toContain('이번 모집수 450세대가 같은 기존 행')
    const twins = [current[0], { ...current[0], id: 9999, key: 'row:9999' }]
    expect(proposeRows(renamed, twins, housing)[0].reason).toBe('단지를 하나로 특정하지 못했습니다.')
  })
})

it('단지를 정했는데 같은 전용면적 주택형이 없을 때만 새 주택형 후보를 돌려준다', () => {
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
    // A63의 LH 주소(산척동)는 공고문 주소와 달라 블록 번호로만 대응한다.
    expect(sourceComplexNames(extraction, '동탄2 A63BL', names, complexes)).toEqual({ names: ['화성동탄2A-63(공임리츠) A63'], certain: false })
    const crossed = complexes.map(item => item.name.includes('A-81') ? { ...item, roadAddress: '경기도 화성시 동탄대로12길 71' } : item)
    expect(sourceComplexNames(extraction, '동탄2 A40BL', names, crossed.map(item => item.name.includes('A-40') ? { ...item, roadAddress: null } : item)).names).toEqual([])
  })

  it('PDF 행마다 같은 공고 단지의 기존 공급행을 추천하고, 맞는 행이 없으면 새 공급정보로 추가한다', () => {
    const { extraction, rows, complexes } = dongtan()
    const proposed = proposeRows(extraction, rows, {}, complexes)
    expect(proposed.map(item => item.destination)).toEqual(['row:881', 'row:882', 'row:903', 'new'])
    expect(proposed.map(item => item.recommended)).toEqual([['row:881'], ['row:882'], ['row:903'], []])
    expect(proposed[3]).toMatchObject({ anchorKey: 'row:881', complexId: 1723 })
    expect(proposed[0].reason).toContain("공고 단지 '화성동탄2A-40(공임리츠) A-40'에 대응했습니다.")
  })

  it('블록 번호와 주소가 함께 맞은 공고 단지는 확인 없이 두고, 블록 번호만 맞으면 대응을 확인하게 한다', () => {
    const { extraction, rows, complexes } = dongtan()
    const type = (id: number, name: string, exclusiveArea: number) => ({ id, name, exclusiveArea, supplyArea: null, householdCount: null })
    const housing = { 1723: [type(5823, '84.9600A', 84.96), type(5824, '84.9600B', 84.96)], 1621: [type(5705, '59.9800A', 59.98)] }
    expect(proposeRows(extraction, rows, housing, complexes).slice(0, 3).map(item => item.needsConfirmation)).toEqual([false, false, true])
    expect(proposeRows(extraction, rows, housing, complexes)[2].reason).toContain('한쪽만 맞아 대응했습니다')
  })

  it('같은 단지에 전용면적이 같은 행이 여럿이고 표기가 맞지 않으면 추천만 하고 저장할 위치는 고르게 한다', () => {
    const { extraction, rows, complexes } = dongtan()
    const renamed = { ...extraction, supplyRows: [{ ...extraction.supplyRows[0], typeName: '084.9600C' }] }
    expect(proposeRows(renamed, rows, {}, complexes)[0]).toMatchObject({ destination: '', recommended: ['row:881', 'row:882'] })
  })

  it('공고 단지가 아직 단지에 연결되지 않았으면 다른 단지의 행을 모집수로 추천하지 않고 단지 정하기로 보낸다', () => {
    const { extraction, rows, complexes } = dongtan()
    // A81 행만 이번 모집수(2세대)가 같아 모집수로 고르면 다른 단지 행을 추천하게 된다.
    const unlinked = rows.map(row => row.id === 903 ? { ...row, housingComplexId: null, housingTypeId: null } : row.id === 904 ? { ...row, householdCount: 2 } : row)
    const proposed = proposeRows(extraction, unlinked, {}, complexes)[2]
    expect(proposed).toMatchObject({ destination: 'new', anchorKey: '', recommended: [] })
    expect(proposed.reason).toContain('아직 단지에 연결되지 않았습니다')
  })
})
