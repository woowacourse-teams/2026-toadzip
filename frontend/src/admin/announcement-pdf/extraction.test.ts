/// <reference types="node" />

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import fixtures from './fixtures/scenarios.json'
// 백엔드가 모델에 보내는 프롬프트의 응답 예제를 이 화면이 그대로 읽는지 확인한다.
const prompt = readFileSync(resolve(process.cwd(), '../backend/src/main/resources/announcement/pdf-extraction-prompt.txt'), 'utf8')
import { evidenceFor, noteFor, pathCovers, readExtraction, type Extraction } from './extraction'

describe('AI 추출 응답 읽기', () => {
  it('단지 개요는 이름이 있는 항목만 읽고, 없는 응답(v4)도 그대로 읽는다', () => {
    const input = structuredClone(fixtures.hansville.extraction) as unknown as { data: Record<string, unknown> }
    input.data.complexes = [{ sourceComplexName: ' 인천부평 ', address: '인천광역시 부평구 부평동 65-17', totalHouseholdCount: 350, parkingCount: '112대' }, { address: '이름 없음' }]
    const result = readExtraction(JSON.stringify(input))
    expect(result.ok && result.value.complexes).toEqual([{ name: '인천부평', address: '인천광역시 부평구 부평동 65-17', totalHouseholdCount: 350, parkingCount: null }])
    const legacy = readExtraction(JSON.stringify(fixtures.hansville.extraction))
    expect(legacy.ok && legacy.value.complexes).toEqual([])
  })
  it.each(Object.entries(fixtures))('%s 실제 응답 형식을 읽는다', (_name, fixture) => {
    const result = readExtraction(JSON.stringify(fixture.extraction))
    expect(result.ok).toBe(true)
    if (!result.ok) throw new Error(result.error)
    expect(result.value.supplyRows).toHaveLength(fixture.rows.length === 1 && fixture.rows[0].id === 90 ? 2 : fixture.rows.length)
    expect(result.value.schedules).toHaveLength(fixture.extraction.data.schedules.length)
  })

  it('코드 블록과 잘못된 이스케이프를 복구하면서 금액 정밀도를 보존한다', () => {
    const body = JSON.stringify(fixtures.hansville.extraction).replace('4076000', '123456789012345678')
      .replace('현장 신청접수', String.raw`현장 \~ 신청\:접수\*`)
    const result = readExtraction(`\uFEFF\x60\x60\x60json\n${body}\n\x60\x60\x60`)
    expect(result.ok).toBe(true)
    if (!result.ok) throw new Error(result.error)
    expect(result.value.supplyRows[0].targets[0].rentalDeposit).toBe('123456789012345678')
    expect(result.value.schedules[0].name).toBe('현장 ~ 신청:접수*')
    expect(result.repairs).toHaveLength(4)
  })

  it('정상적인 역슬래시와 문자열 이스케이프를 보존한다', () => {
    const input = structuredClone(fixtures.hansville.extraction)
    input.evidence[0].quote = '경로 C:\\new\\test\n"공고"'
    const result = readExtraction(JSON.stringify(input))
    expect(result.ok && result.value.evidence[0].quote).toBe(input.evidence[0].quote)
    expect(result.repairs).toEqual([])
  })

  it('미확인 목록이 잘못되면 조용히 누락시키지 않고 응답을 거절한다', () => {
    const input = { ...fixtures.gongju.extraction, data: { ...fixtures.gongju.extraction.data, unresolvedFields: '공급면적 미확인' } }
    expect(readExtraction(JSON.stringify(input))).toMatchObject({ ok: false, error: expect.stringContaining('unresolvedFields') })
  })

  it('공통 모집수의 행이 없거나 겹치면 거절한다', () => {
    const input = structuredClone(fixtures.hansville.extraction)
    input.sharedCounts[0].paths[1] = 'supplyRows[99].totalSupplyHouseholdCount'
    expect(readExtraction(JSON.stringify(input))).toMatchObject({ ok: false, error: 'sharedCounts[0].paths의 supplyRows[99]가 없습니다. 공급행은 2개(0~1번)입니다.' })
  })

  it.each([
    ['쪽 번호가 없으면', (item: Record<string, unknown>) => { item.page = null }, 'sharedCounts[0].page는 1 이상의 쪽 번호여야 합니다. 현재 값: null'],
    ['모집수가 문자열이면', (item: Record<string, unknown>) => { item.count = '170' }, 'sharedCounts[0].count는 0 이상의 정수여야 합니다. 현재 값: "170"'],
    ['공급행이 하나뿐이면', (item: Record<string, unknown>) => { item.paths = ['supplyRows[0].totalSupplyHouseholdCount'] }, 'sharedCounts[0].paths에는 모집수를 함께 쓰는 공급행이 2개 이상 있어야 합니다.'],
    ['다른 공급행의 대상을 묶으면', (item: Record<string, unknown>) => { item.paths = ['supplyRows[0].targets[0].supplyHouseholdCount', 'supplyRows[1].targets[0].supplyHouseholdCount'] }, '같은 공급행의 대상끼리만 묶을 수 있습니다.'],
    ['공급행과 대상을 섞으면', (item: Record<string, unknown>) => { item.paths = ['supplyRows[0].totalSupplyHouseholdCount', 'supplyRows[0].targets[0].supplyHouseholdCount'] }, '공급행 모집수와 대상별 모집수가 섞여 있습니다.'],
    ['없는 대상을 가리키면', (item: Record<string, unknown>) => { item.paths = ['supplyRows[0].targets[0].supplyHouseholdCount', 'supplyRows[0].targets[5].supplyHouseholdCount'] }, 'supplyRows[0].targets[5]가 없습니다. 이 공급행의 대상은 1개입니다.'],
  ])('공통 모집수의 %s 어느 항목이 왜 틀렸는지 알린다', (_name, change, message) => {
    const input = structuredClone(fixtures.hansville.extraction) as unknown as { sharedCounts: Record<string, unknown>[] }
    change(input.sharedCounts[0])
    expect(readExtraction(JSON.stringify(input))).toMatchObject({ ok: false, error: expect.stringContaining(message) })
  })

  it('한 공급행 안의 대상들이 함께 쓰는 모집수(대학생·청년 통합)를 읽는다', () => {
    const input = structuredClone(fixtures.hansville.extraction) as unknown as { data: { supplyRows: { targets: Record<string, unknown>[] }[] }; sharedCounts: unknown[] }
    input.data.supplyRows[0].targets.push({ ...input.data.supplyRows[0].targets[0], target: '청년' })
    input.sharedCounts = [{ paths: ['supplyRows[0].targets[0].supplyHouseholdCount', 'supplyRows[0].targets[1].supplyHouseholdCount'], count: 39, page: 3, quote: '대학생 청년 39' }]
    const result = readExtraction(JSON.stringify(input))
    expect(result).toMatchObject({ ok: true })
    if (result.ok) expect(result.value.sharedCounts).toEqual([expect.objectContaining({ count: 39, paths: ['supplyRows[0].targets[0].supplyHouseholdCount', 'supplyRows[0].targets[1].supplyHouseholdCount'] })])
  })

  it('v1 응답은 새 프롬프트로 다시 요청하도록 안내한다', () => {
    expect(readExtraction('{"schemaVersion":"admin-announcement-import/v1"}')).toMatchObject({ ok: false, error: expect.stringContaining('v1') })
  })

  it('와일드카드 미확인은 해당 경로와 하위 항목에만 적용한다', () => {
    expect(pathCovers('supplyRows[*].targets[*].rentalDeposit', 'supplyRows[10].targets[2].rentalDeposit')).toBe(true)
    expect(pathCovers('supplyRows[1]', 'supplyRows[10].targets')).toBe(false)
    expect(pathCovers('supplyRows[0].targets', 'supplyRows[0].targets[1].target')).toBe(true)
    expect(noteFor([{ path: 'supplyRows[*].supplyArea', reason: '동별 값이 다름' }], 'supplyRows[0].supplyArea')?.reason).toBe('동별 값이 다름')
  })

  it('행 근거보다 필드 근거를 우선한다', () => {
    const row = { path: 'supplyRows[0]', page: 4, quote: '26A' }
    const field = { path: 'supplyRows[0].exclusiveArea', page: 5, quote: '26.5195' }
    expect(evidenceFor([row, field], field.path)).toEqual(field)
    expect(evidenceFor([row, field], 'supplyRows[0].supplyArea')).toEqual(row)
  })

  it('프롬프트의 응답 예제를 그대로 읽고 세부 타입 행을 펼친다', () => {
    const result = readExtraction(prompt.slice(prompt.indexOf('[응답 예제]')))
    if (!result.ok) throw new Error(result.error)
    expect(result.value.supplyRows.map(row => [row.typeName, row.values.supplyArea])).toEqual([['36A', 49.8], ['26A', 41.47], ['26B', 41.53]])
    // 가군·나군처럼 임대조건만 다른 대상은 공통 모집수가 아니라 notApplicable이고, 대학생·청년 통합 모집만 묶는다.
    expect(result.value.sharedCounts.map(group => group.count)).toEqual([77])
    expect(result.value.checks).toEqual([])
    // 임대조건 근거는 공급 표(3쪽)가 아니라 임대조건 표(4쪽)를 가리킨다.
    expect(evidenceFor(result.value.evidence, 'supplyRows[0].targets[0].rentalDeposit')?.page).toBe(4)
    expect(evidenceFor(result.value.evidence, 'supplyRows[0].supplyArea')?.page).toBe(3)
  })

  describe('세부 타입 펼치기와 검산', () => {
    const target = (name: string, count: number | null) => ({ target: name, supplyRank: null, supplyHouseholdCount: count, reserveCount: null, rentalDeposit: 31994000, monthlyRent: 138640, convertedDeposit: null, applicationCondition: null })
    const row = (name: string, values: Record<string, unknown>, targets = [target('청년', 10), target('신혼부부', 4)]) => ({
      complexReference: { sourceComplexName: '인천부평' }, sourceHousingTypeName: name, expectedMoveInMonth: '2027-03', totalSupplyHouseholdCount: null,
      exclusiveArea: null, commonArea: null, supplyArea: null, constructedHouseholdCount: null, targets, ...values })
    function read(overrides: Record<string, unknown> = {}) {
      const input = structuredClone(fixtures.hansville.extraction) as unknown as { data: Record<string, unknown> } & Record<string, unknown>
      input.data.complexes = [{ sourceComplexName: '인천부평', address: '인천광역시 부평구 부평동 65-17', totalHouseholdCount: 350, parkingCount: 112 }]
      input.data.supplyRows = [
        row('16A', { exclusiveArea: 16.37, commonArea: 11.2684, constructedHouseholdCount: 100 }),
        row('26A/B/C', { totalSupplyHouseholdCount: 77, targets: [target('청년', null), target('신혼부부', null)], subtypes: [
          { name: '26A', exclusiveArea: 26.37, commonArea: 15.1, constructedHouseholdCount: 50 },
          { name: '26B', exclusiveArea: 26.41, commonArea: 15.12, constructedHouseholdCount: 50 },
          { name: '26C', exclusiveArea: 26.5, commonArea: null, supplyArea: 41.7, constructedHouseholdCount: 30 }] }),
        row('36A', { exclusiveArea: 36.5, commonArea: 13.5, supplyArea: 50, constructedHouseholdCount: 50, totalSupplyHouseholdCount: 15 }),
      ]
      input.data.unresolvedFields = [{ path: 'supplyRows[1].subtypes[2].commonArea', reason: '공용면적 표기 없음' }]
      input.notApplicable = [{ path: 'supplyRows[*].targets[*].supplyRank', reason: '순위 없음' }]
      input.sharedCounts = [{ paths: ['supplyRows[1].targets[0].supplyHouseholdCount', 'supplyRows[1].targets[1].supplyHouseholdCount'], count: 20, page: 3, quote: '통합 20' }]
      input.evidence = [{ path: 'supplyRows[1]', page: 3, quote: '26A/B/C 77' }, { path: 'supplyRows[2]', page: 4, quote: '36A 15' }]
      Object.assign(input, overrides)
      const result = readExtraction(JSON.stringify(input))
      if (!result.ok) throw new Error(result.error)
      return result.value as Extraction
    }

    it('묶인 세부 타입을 행마다 펼치고 대상·금액을 복사하며 묶인 모집수는 공통 모집수로 옮긴다', () => {
      const value = read()
      expect(value.supplyRows.map(item => item.typeName)).toEqual(['16A', '26A', '26B', '26C', '36A'])
      expect(value.supplyRows.slice(1, 4).map(item => [item.values.exclusiveArea, item.values.totalSupplyHouseholdCount, item.values.subtypes])).toEqual([[26.37, null, undefined], [26.41, null, undefined], [26.5, null, undefined]])
      expect(value.supplyRows[2].targets.map(item => [item.target, item.rentalDeposit, item.supplyHouseholdCount])).toEqual([['청년', '31994000', null], ['신혼부부', '31994000', null]])
      expect(value.supplyRows[4].targets.map(item => item.supplyHouseholdCount)).toEqual([10, 4])
      // 묶음 안의 대상별 공통 모집수는 버리고, 묶인 모집수 77은 펼친 세 행의 공통 모집수가 된다.
      expect(value.sharedCounts).toEqual([{ paths: ['supplyRows[1].totalSupplyHouseholdCount', 'supplyRows[2].totalSupplyHouseholdCount', 'supplyRows[3].totalSupplyHouseholdCount'], count: 77, page: 3, quote: '26A/B/C 77' }])
      expect(noteFor(value.notApplicable, 'supplyRows[3].targets[1].supplyHouseholdCount')?.reason).toContain('세부 타입')
      expect(noteFor(value.notApplicable, 'supplyRows[4].targets[1].supplyHouseholdCount')).toBeNull()
    })

    it('공급면적은 전용 + 공용으로 계산하고, 응답 행 번호를 쓰는 메모·근거는 펼친 행으로 옮긴다', () => {
      const value = read()
      expect(value.supplyRows.map(item => item.values.supplyArea)).toEqual([27.6384, 41.47, 41.53, 41.7, 50])
      expect(noteFor(value.unresolved, 'supplyRows[3].supplyArea')?.reason).toBe('공용면적 표기 없음')
      expect(noteFor(value.notApplicable, 'supplyRows[2].targets[0].supplyRank')?.reason).toBe('순위 없음')
      expect(evidenceFor(value.evidence, 'supplyRows[2].targets[0].rentalDeposit')).toEqual({ path: 'supplyRows[2]', page: 3, quote: '26A/B/C 77' })
      expect(evidenceFor(value.evidence, 'supplyRows[4].exclusiveArea')?.page).toBe(4)
    })

    it('여러 공급행 공통 모집수에 묶인 세부 타입 행은 펼친 행 모두를 그 묶음에 넣는다', () => {
      const value = read({ sharedCounts: [{ paths: ['supplyRows[1].totalSupplyHouseholdCount', 'supplyRows[2].totalSupplyHouseholdCount'], count: 92, page: 3, quote: '26·36 92' }] })
      expect(value.sharedCounts).toEqual([{ paths: [1, 2, 3, 4].map(index => `supplyRows[${index}].totalSupplyHouseholdCount`), count: 92, page: 3, quote: '26·36 92' }])
    })

    it('건설호수·대상별 모집수 합계와 공급면적이 맞지 않으면 확인할 내용으로 알린다', () => {
      expect(read().checks).toEqual([
        '인천부평: 공급 표 건설호수 합계 280세대가 단지 전체 세대수 350세대와 다릅니다. 공급 표에 없는 주택형이 있는지 확인하세요.',
        '36A: 대상별 모집수 합계 14세대가 모집수 15세대와 다릅니다.',
      ])
      const wrongArea = structuredClone(fixtures.hansville.extraction) as unknown as { data: { supplyRows: Record<string, unknown>[] } }
      Object.assign(wrongArea.data.supplyRows[0], { exclusiveArea: 26.37, commonArea: 15.1, supplyArea: 42 })
      const result = readExtraction(JSON.stringify(wrongArea))
      expect(result.ok && result.value.checks.some(item => item.includes('공급면적 42㎡가 전용 + 공용 41.47㎡와 달라 계산값을 씁니다.'))).toBe(true)
    })

    it('세부 타입 목록 형식이 틀리면 그 행 번호로 다시 요청하게 한다', () => {
      const input = structuredClone(fixtures.hansville.extraction) as unknown as { data: { supplyRows: Record<string, unknown>[] } }
      input.data.supplyRows[0].subtypes = [{ exclusiveArea: 26.37 }]
      expect(readExtraction(JSON.stringify(input))).toEqual(expect.objectContaining({ ok: false, error: expect.stringContaining('supplyRows[0].subtypes') }))
    })
  })
})
