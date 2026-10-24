import { describe, expect, it } from 'vitest'
import fixtures from './fixtures/scenarios.json'
import { evidenceFor, noteFor, pathCovers, readExtraction } from './extraction'

describe('ChatGPT 공고 응답 읽기', () => {
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
})
