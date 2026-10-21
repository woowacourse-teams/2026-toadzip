import { describe, expect, it } from 'vitest'
import fixtures from './fixtures/scenarios.json'
import { blankRow, blankTarget } from '../supply/supplyCorrectionApi'
import { readExtraction } from './extraction'
import { checkAnnouncement, defaultTargetDestinations, proposeRows, proposeSchedules } from './matching'

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
  })

  it('공급대상명은 주택형처럼 형을 생략하지 않고 미저장 조건도 정확히 대응한다', () => {
    const row = blankRow(0)
    row.targets = [{ ...blankTarget(0), key: 'draft-target', target: '가형', supplyRank: '1순위' }]
    expect(defaultTargetDestinations({ targets: [{ target: '가', supplyRank: '1순위' }] }, row)).toEqual(['new'])
    expect(defaultTargetDestinations({ targets: [{ target: '가 형', supplyRank: '1 순위' }] }, row)).toEqual(['draft-target'])
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
})
