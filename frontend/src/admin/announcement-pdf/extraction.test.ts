/// <reference types="node" />

import { describe, expect, it } from 'vitest'
import fixtures from './fixtures/scenarios.json'
import { daegu } from './fixtures/workspace'
import managerExample from './fixtures/manager-extraction-example.json'
import { evidenceContext, evidenceFor, evidenceQuery, noteFor, pathCovers, readExtraction, searchText } from './extraction'

describe('AI 추출 응답 읽기', () => {
  it('PDF에서 강조할 문구는 금액 칸이면 그 금액 표기를 낱말 단위로, 글자면 인용문에 있는 값을 고른다', () => {
    const quote = '2,395,000 119,750 2,275,250 47,630'
    // 금액은 배열로 돌려줘 PDF에서도 낱말 단위로 찾는다.
    expect(evidenceQuery(quote, '2395000', true)).toEqual(['2,395,000'])
    expect(evidenceQuery(quote, '47630', true)).toEqual(['47,630'])
    expect(evidenceQuery('연락처 055-954-9818', '055-954-9818')).toBe('055-954-9818')
    // 금액이 인용문에 없으면(전환보증금 칸이 인용에 빠진 경우) PDF 단위를 알 수 없어 원·천원·만원 표기를 함께 찾는다.
    // 공고 187은 전환보증금을 천원 단위 '(+) 4,000'으로만 적어 원 단위만 찾으면 강조되지 않았다.
    expect(evidenceQuery('대학생 180 12,920 646 12,274 80,750', '4000000', true)).toEqual(['4,000,000', '4,000', '400만원'])
    expect(evidenceQuery(quote, '16000000', true)).toEqual(['16,000,000', '16,000', '1,600', '1,600만원'])
    expect(evidenceQuery('보증금 2395000원', '2395000', true)).toEqual(['2395000'])
    // 공고문이 천원·만원 단위로 적으면 그 표기로 찾는다. 원 단위 표기가 다른 금액 안에 겹쳐 있어도 낱말로만 맞춘다.
    expect(evidenceQuery('8,100 405 7,695 156', '8100000', true)).toEqual(['8,100'])
    expect(evidenceQuery('(+)1,500 (-)300', '15000000', true)).toEqual(['1,500'])
    expect(evidenceQuery('81,000,000 8,100', '8100000', true)).toEqual(['8,100'])
    // '150만원'의 '만' 뒤는 PDF 낱말 경계가 아니라 '만원' 표기도 함께 찾는다.
    expect(evidenceQuery('보증금 810만원 임대료 15.6만원', '8100000', true)).toEqual(['810만원', '810만'])
    // 세 자리 이하로 줄어든 숫자는 세대수 등과 겹쳐 그대로 찾지 않고 '만원'이 붙은 표기로만 찾는다(대학생 150명, 청년 20세대).
    expect(evidenceQuery('보증금 810 월 15', '8100000', true)).toEqual(['8,100,000', '8,100', '810만원'])
    expect(evidenceQuery('대학생 150 12,920 646', '1500000', true)).toEqual(['1,500,000', '1,500', '150만원'])
    expect(evidenceQuery('청년 20 1 3,000 120', '20000', true)).toEqual(['20,000', '2만원'])
    // 금액 칸이 아닌 숫자(세대수 1200)는 단위를 바꿔 찾지 않고 인용문에서 고른 검색어로 강조한다.
    expect(evidenceQuery('일반 1200 3,000', 1200)).toBe(searchText('일반 1200 3,000'))
    // 글자 값이 인용문에 없거나 짧은 숫자면 인용문에서 고른 검색어로 강조한다. 짧은 숫자는 쪽 곳곳에 있어 엉뚱한 곳을 강조한다.
    expect(evidenceQuery(quote, '공공임대')).toBe(searchText(quote))
    expect(searchText(quote)).toBe('2,395,000')
    expect(evidenceQuery('26A 16 26.70 17.6211', 16)).toBe('17.6211')
    expect(evidenceQuery(quote)).toBe('2,395,000')
  })
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
    expect(result.value.sheet.housingTypes).toHaveLength(fixture.extraction.data.supplySheet.housingTypes.length)
    expect(result.value.schedules).toHaveLength(fixture.extraction.data.schedules.length)
  })

  it('코드 블록과 잘못된 이스케이프를 복구하면서 금액 정밀도를 보존한다', () => {
    const body = JSON.stringify(fixtures.hansville.extraction).replace('4076000', '123456789012345678')
      .replace('현장 신청접수', String.raw`현장 \~ 신청\:접수\*`)
    const result = readExtraction(`\uFEFF\x60\x60\x60json\n${body}\n\x60\x60\x60`)
    expect(result.ok).toBe(true)
    if (!result.ok) throw new Error(result.error)
    expect(result.value.sheet.leaseTerms[0].rentalDeposit).toBe('123456789012345678')
    expect(result.value.schedules[0].name).toBe('현장 ~ 신청:접수*')
    expect(result.repairs).toHaveLength(4)
  })

  it('복사하며 붙은 HTML 공백 문자 코드를 값 앞뒤에서는 지우고 값 사이와 JSON 사이에서는 공백으로 고친다', () => {
    const body = JSON.stringify(fixtures.hansville.extraction, null, 2).replace(/,\n/g, ',&#x20;\n')
      .replace('"현장 신청접수"', '"&#x20;현장&#x20;신청접수&nbsp;"')
    const result = readExtraction(body)
    expect(result.ok).toBe(true)
    if (!result.ok) throw new Error(result.error)
    expect(result.value.schedules[0].name).toBe('현장 신청접수')
    expect(result.repairs).toEqual([expect.stringContaining('HTML 공백 문자 코드')])
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

  it('공급표의 칸·수량 묶음·임대조건 멤버(주택형·공급대상 키 쌍)를 칸 순번으로 읽고, 공급표 안의 근거·미확인도 읽는다', () => {
    const result = readExtraction(JSON.stringify(fixtures.hansville.extraction))
    if (!result.ok) throw new Error(result.error)
    const { sheet } = result.value
    expect(sheet.housingTypes.map(type => [type.complexName, type.name, type.exclusiveArea])).toEqual([['익산한스빌', '26A', 26.5195], ['익산한스빌', '26B', 26.4673]])
    expect(sheet.targets.map(target => target.label)).toEqual(['일반'])
    expect(sheet.cells).toEqual([{ housingType: 0, target: 0 }, { housingType: 1, target: 0 }])
    expect(sheet.quotas).toEqual([{ kind: 'WINNER', count: 170, members: [0, 1] }])
    expect(sheet.leaseTerms.map(lease => [lease.rentalDeposit, lease.members])).toEqual([['4076000', [0]], ['4076000', [1]]])
    expect(evidenceFor(result.value.evidence, 'housingTypes[1].exclusiveArea')).toMatchObject({ path: 'housingTypes[1]' })
  })

  it('예전 공급행 응답(supplySheet 없음)은 다시 추출하게 하고, 공급표 칸·묶음이 없는 주택형·칸을 가리키면 거절한다', () => {
    const legacy = structuredClone(fixtures.hansville.extraction) as unknown as { data: Record<string, unknown> }
    delete legacy.data.supplySheet
    expect(readExtraction(JSON.stringify(legacy))).toMatchObject({ ok: false, error: expect.stringContaining('이전 형식') })
    const strayCell = structuredClone(fixtures.hansville.extraction)
    strayCell.data.supplySheet.cells[0].housingType = 'h9'
    expect(readExtraction(JSON.stringify(strayCell))).toMatchObject({ ok: false, error: expect.stringContaining('없는 주택형이나 공급대상') })
    const strayMember = structuredClone(fixtures.hansville.extraction)
    strayMember.data.supplySheet.quotas[0].members[1] = { housingType: 'h1', target: 't9' }
    expect(readExtraction(JSON.stringify(strayMember))).toMatchObject({ ok: false, error: expect.stringContaining('수량 묶음') })
    const badKind = structuredClone(fixtures.hansville.extraction)
    badKind.data.supplySheet.quotas[0].kind = 'TOTAL'
    expect(readExtraction(JSON.stringify(badKind))).toMatchObject({ ok: false })
  })

  it('v1 응답은 새 프롬프트로 다시 요청하도록 안내한다', () => {
    expect(readExtraction('{"schemaVersion":"admin-announcement-import/v1"}')).toMatchObject({ ok: false, error: expect.stringContaining('v1') })
  })

  it('와일드카드 미확인은 해당 경로와 하위 항목에만 적용한다', () => {
    expect(pathCovers('housingTypes[*].cells[*].rentalDeposit', 'housingTypes[10].cells[2].rentalDeposit')).toBe(true)
    expect(pathCovers('housingTypes[1]', 'housingTypes[10].cells')).toBe(false)
    expect(pathCovers('housingTypes[0].cells', 'housingTypes[0].cells[1].winner')).toBe(true)
    expect(noteFor([{ path: 'housingTypes[*].supplyArea', reason: '동별 값이 다름' }], 'housingTypes[0].supplyArea')?.reason).toBe('동별 값이 다름')
  })

  it('행 근거보다 필드 근거를 우선한다', () => {
    const row = { path: 'housingTypes[0]', page: 4, quote: '26A' }
    const field = { path: 'housingTypes[0].exclusiveArea', page: 5, quote: '26.5195' }
    expect(evidenceFor([row, field], field.path)).toEqual(field)
    expect(evidenceFor([row, field], 'housingTypes[0].supplyArea')).toEqual(row)
  })
  it('관리자 형식의 응답 예제를 읽고, 공급면적은 전용 + 공용으로 계산하며 근거는 공급표 경로대로 찾는다', () => {
    const result = readExtraction(JSON.stringify(managerExample))
    if (!result.ok) throw new Error(result.error)
    expect(result.value.sheet.housingTypes.map(type => [type.name, type.houseTypes.map(house => [house.name, house.supplyArea])])).toEqual([['36A', []], ['26A/B', [['26A', 41.47], ['26B', 41.53]]]])
    expect(result.value.sheet.quotas.map(quota => quota.count)).toEqual([77, 40])
    expect(result.value.checks).toEqual([])
    // 임대조건 근거는 공급 표(3쪽)가 아니라 임대조건 표(4쪽)를 가리킨다.
    expect(evidenceFor(result.value.evidence, 'leaseTerms[0].rentalDeposit')?.page).toBe(4)
    expect(evidenceFor(result.value.evidence, 'housingTypes[0].supplyArea')?.page).toBe(3)
  })

  it('건설호수 합계와 공급면적이 맞지 않으면 확인할 내용으로 알린다', () => {
    const input = structuredClone(fixtures.hansville.extraction) as unknown as { data: { complexes?: unknown[]; supplySheet: { housingTypes: Record<string, unknown>[] } } }
    input.data.complexes = [{ sourceComplexName: '익산한스빌', address: '익산시', totalHouseholdCount: 350, parkingCount: null }]
    Object.assign(input.data.supplySheet.housingTypes[0], { exclusiveArea: 26.37, commonArea: 15.1, supplyArea: 42, constructedHouseholdCount: 100 })
    Object.assign(input.data.supplySheet.housingTypes[1], { constructedHouseholdCount: 180 })
    const result = readExtraction(JSON.stringify(input))
    if (!result.ok) throw new Error(result.error)
    expect(result.value.checks).toEqual([
      '익산한스빌: 공급 표 건설호수 합계 280세대가 단지 전체 세대수 350세대와 다릅니다. 공급 표에 없는 주택형이 있는지 확인하세요.',
      '26A: 공급면적 42㎡가 전용 + 공용 41.47㎡와 다릅니다. 공고문과 대조하세요.',
    ])
  })
})

it('주택형 칸의 근거는 그 주택형 표기·전용면적을 문맥으로 두고, 대상별 칸·수량 묶음은 문맥 없이 찾으며, 세 자리 이하 값도 인용문의 다른 숫자 대신 그 값을 찾는다', () => {
  const { review } = daegu()
  expect(evidenceContext(review.extraction, 'housingTypes[0].count')).toEqual(['051.9800A', '51.98'])
  expect(evidenceContext(review.extraction, 'housingTypes[0]')).toEqual(['051.9800A', '51.98'])
  expect(evidenceContext(review.extraction, 'housingTypes[0].cells[0].rentalDeposit')).toEqual([])
  expect(evidenceContext(review.extraction, 'quotas[0].count')).toEqual([])
  expect(evidenceContext(review.extraction, 'schedules[0].startAt')).toEqual([])
  // 문맥이 없으면 예전처럼 인용문에서 가장 긴 숫자를 쓰고, 문맥이 있으면 모집수 200을 같은 줄에서 찾는다.
  expect(evidenceQuery('051.9800A 468 200', 200)).toBe('051.9800')
  expect(evidenceQuery('051.9800A 468 200', 200, false, ['051.9800A'])).toEqual(['200'])
  // 수량 묶음 칸은 그 묶음의 근거(공급표 경로)를 찾는다.
  expect(evidenceFor(review.extraction.evidence, 'quotas[1].count')).toMatchObject({ path: 'quotas[1].count', page: 3 })
})
