import { describe, expect, it } from 'vitest'
import fixtures from './fixtures/scenarios.json'
import { readExtraction } from './extraction'
import { blockKey, canonicalTarget, checkAnnouncement, missingHousingType, proposeSchedules, sourceComplexNames } from './matching'

function scenario(name: keyof typeof fixtures) {
  const fixture = fixtures[name]
  const result = readExtraction(JSON.stringify(fixture.extraction))
  if (!result.ok) throw new Error(result.error)
  const events = fixture.events.map(event => ({ id: event.id, data: event, complexName: event.complexName }))
  return { extraction: result.value, events }
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

  it('소득 유무·가운뎃점·괄호 표기만 다른 공급대상 이름은 같은 이름으로 맞춘다', () => {
    expect(['청년(소득無)', '청년소득(○)', '청년(소득 X)', '고령자(주거약자용)', '신혼부부· 한부모가족', '신혼부부․ 한부모가족', '신혼부부ㆍ한부모가족', '「가」군'].map(canonicalTarget))
      .toEqual(['청년(소득 없음)', '청년(소득 있음)', '청년(소득 없음)', '고령자(주거약자용)', '신혼부부·한부모가족', '신혼부부·한부모가족', '신혼부부·한부모가족', '가군'])
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
  const types = [['동탄2 A40BL', '084.9600A', 84.96], ['동탄2 A40BL', '084.9600B', 84.96], ['동탄2 A63BL', '059.9800A', 59.98], ['동탄2 A40BL', '101.0000A', 101]] as const
  const result = readExtraction(JSON.stringify({ data: { announcement: { name: '화성동탄2 리츠' }, receptionPlace: null, schedules: [], unresolvedFields: [],
    complexes: [{ sourceComplexName: '동탄2 A40BL', address: '경기도 화성시 동탄대로12길 71' }, { sourceComplexName: '동탄2 A63BL', address: '경기도 화성시 동탄신리천로 786' }],
    supplySheet: { housingTypes: types.map(([complexName, name, exclusiveArea], at) => ({ key: `h${at}`, complexName, name, exclusiveArea, commonArea: null, supplyArea: null,
      constructedHouseholdCount: null, expectedMoveInMonth: null, houseTypes: [] })), targets: [], cells: [], quotas: [], leaseTerms: [] } },
  notApplicable: [], evidence: [] }))
  if (!result.ok) throw new Error(result.error)
  const complexes = [{ name: '화성동탄2A-40(공임리츠) A-40', roadAddress: '경기도 화성시 동탄대로12길 71' }, { name: '화성동탄2A-63(공임리츠) A63', roadAddress: '경기도 화성시 산척동' },
    { name: '화성동탄2 A-81(공임리츠) A-81', roadAddress: '경기도 화성시 산척동' }]
  return { extraction: result.value, complexes }
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

  it('블록 번호가 공고 단지 하나에 맞으면 주소 표기가 달라도 확인 없이 그 단지로 두고, 주소만 맞으면 대응을 확인하게 한다', () => {
    const { extraction, complexes } = dongtan()
    const names = complexes.map(item => item.name)
    expect(sourceComplexNames(extraction, '동탄2 A63BL', names, complexes).certain).toBe(true)
    const addressOnly = { ...extraction, complexes: extraction.complexes.map(item => item.name === '동탄2 A40BL' ? { ...item, name: '동탄 리츠' } : item) }
    expect(sourceComplexNames(addressOnly, '동탄 리츠', names, complexes).certain).toBe(false)
  })

  it('블록 번호는 맞지만 지구명이 다른 PDF 단지는 확정하지 않는다', () => {
    const { extraction, complexes } = dongtan()
    expect(sourceComplexNames(extraction, '고덕 A40BL', complexes.map(item => item.name), complexes).certain).toBe(false)
  })

})
