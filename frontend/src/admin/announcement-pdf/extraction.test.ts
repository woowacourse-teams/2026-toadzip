import { describe, expect, it } from 'vitest'
import fixtures from './fixtures/scenarios.json'
import { evidenceFor, noteFor, pathCovers, readExtraction } from './extraction'

describe('ChatGPT 공고 응답 읽기', () => {
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
    expect(readExtraction(JSON.stringify(input))).toMatchObject({ ok: false, error: expect.stringContaining('sharedCounts') })
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
