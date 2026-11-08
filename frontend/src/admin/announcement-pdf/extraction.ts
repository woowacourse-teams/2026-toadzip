import { isRecord } from '../management/managementContract'
import type { JsonObject } from '../announcement-json/api'
import { readSubtypes, subtypesOf, supplyArea } from './subtypes'

export type ExtractionNote = { path: string; reason: string }
export type SharedCount = { paths: string[]; count: number | null; page: number | null; quote: string }
export type Evidence = { path: string; page: number; quote: string }
/** 공고문 단지 개요. 단지를 찾거나 새로 등록할 때 쓰며, 없거나 형식이 틀리면 비워 둔다. */
export type ExtractedComplex = { name: string; address: string | null; totalHouseholdCount: number | null; parkingCount: number | null }
/** 세부 타입 묶음 행(26A/B/C)의 세부 타입 하나. 공급면적은 전용 + 공용으로 계산한 값이다. */
export type Subtype = { name: string; exclusiveArea: number | null; supplyArea: number | null; constructedHouseholdCount: number | null }
/** subtypes는 공급행 하나가 여러 전용면적을 모집수 하나로 함께 모집할 때만 둘 이상이고, 그 밖에는 비어 있다. */
export type ExtractedRow = { complexName: string | null; typeName: string | null; values: JsonObject; targets: JsonObject[]; subtypes: Subtype[] }
export type Extraction = {
  announcement: JsonObject
  /** 공고문 순서의 접수처(인터넷 접수, 현장 접수처 여러 곳). 접수처를 한 곳만 내던 예전 응답은 한 곳으로 읽는다. */
  receptionPlaces: JsonObject[]
  complexes: ExtractedComplex[]
  supplyRows: ExtractedRow[]
  schedules: JsonObject[]
  unresolved: ExtractionNote[]
  notApplicable: ExtractionNote[]
  sharedCounts: SharedCount[]
  evidence: Evidence[]
  /** 합계·면적 검산에서 다르게 나온 항목. 저장을 막지 않고 확인할 내용으로 보여 준다. */
  checks: string[]
  /** 날짜 없이 개별안내한 일정(notApplicable의 path "schedules")의 이유. 받은 일정 값에는 적용하지 않는다. */
  undatedSchedules: string[]
}
type ReadFailure = { ok: false; error: string; repairs: string[] }
export type ReadResult = { ok: true; value: Extraction; repairs: string[] } | ReadFailure
type DocumentResult = { ok: true; value: JsonObject; repairs: string[] } | ReadFailure

const jsonEscapes = new Set(['"', '\\', '/', 'b', 'f', 'n', 'r', 't', 'u'])
// 채팅 화면에서 복사하면 공백이 &#x20; 같은 HTML 문자 코드로 붙어 오는 경우가 있다.
const whitespaceEntities = /(?:&#[xX]0*(?:20|[aA]0|9|[aAdD]);|&#0*(?:32|160|9|10|13);|&nbsp;)+/g
const maximumBytes = 1_000_000

/** AI 추출 응답에서 JSON 객체를 읽는다. 값은 바꾸지 않고 형식 문제만 고치며 고친 내용을 모두 돌려준다. */
export function readExtraction(text: string): ReadResult {
  const document = readExtractionDocument(text)
  if (!document.ok) return document
  const shape = extraction(document.value)
  if (typeof shape === 'string') return { ok: false, error: shape, repairs: document.repairs }
  return { ok: true, value: shape, repairs: document.repairs }
}

/** 원문 교정도 같은 크기 제한·금액 정밀도 보존을 거친 JSON을 사용한다. */
export function readExtractionDocument(text: string): DocumentResult {
  const repairs: string[] = []
  let source = text.replace(/^﻿/, '').trim()
  if (!source) return { ok: false, error: '추출 결과가 비어 있습니다. 다시 추출하세요.', repairs }
  if (new TextEncoder().encode(source).length > maximumBytes) {
    return { ok: false, error: '응답이 UTF-8 기준 1MB를 넘습니다. 한 공고의 응답만 붙여넣어 주세요.', repairs }
  }
  if (source.startsWith('```')) {
    source = source.replace(/^```[a-zA-Z]*\s*/, '').replace(/```\s*$/, '').trim()
    repairs.push('코드 블록 표시(```)를 걷어냈습니다.')
  }
  const first = source.indexOf('{')
  const last = source.lastIndexOf('}')
  if (first < 0 || last < first) return { ok: false, error: 'JSON 객체({ … })를 찾지 못했습니다. 응답 전체를 다시 복사해 주세요.', repairs }
  if (first > 0 || last < source.length - 1) {
    source = source.slice(first, last + 1)
    repairs.push('JSON 앞뒤의 설명 문장을 걷어냈습니다.')
  }
  let entities = 0
  // 따옴표 옆이면 값의 앞뒤 공백이거나 JSON 사이 공백이라 지우고, 그 밖에는 공백 한 칸으로 바꾼다.
  source = source.replace(whitespaceEntities, (match: string, offset: number, whole: string) => {
    entities += 1
    return whole[offset - 1] === '"' || whole[offset + match.length] === '"' ? '' : ' '
  })
  if (entities) repairs.push(`HTML 공백 문자 코드(&#x20; 등) ${entities}곳을 공백으로 고쳤습니다.`)
  const dropped = new Map<string, number>()
  source = source.replace(/\\(.)/gs, (match, character: string) => {
    if (jsonEscapes.has(character)) return match
    dropped.set(character, (dropped.get(character) ?? 0) + 1)
    return character
  })
  for (const [character, count] of dropped) repairs.push(`잘못된 이스케이프 \\${character} ${count}곳을 ${character}로 고쳤습니다.`)
  // 18자리 금액이 JS 숫자로 반올림되지 않도록 파싱 전에 문자열로 바꾼다.
  const exact = source.replace(/("(?:rentalDeposit|monthlyRent|convertedDeposit)"\s*:\s*)(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)(?=\s*[,}\]])/g, '$1"$2"')
  let value: unknown
  try {
    value = JSON.parse(exact)
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : String(cause)
    const position = Number(/position (\d+)/.exec(message)?.[1])
    const near = Number.isFinite(position) ? ` ${position}번째 글자 근처: “${source.slice(Math.max(0, position - 20), position + 20)}”` : ''
    return { ok: false, error: `JSON 문법 오류입니다.${near}`, repairs }
  }
  if (!isRecord(value)) return { ok: false, error: '응답에 data 객체가 없습니다. 화면의 프롬프트로 받은 응답인지 확인해 주세요.', repairs }
  return { ok: true, value, repairs }
}

function extraction(value: unknown): Extraction | string {
  if (!isRecord(value) || !isRecord(value.data)) {
    if (isRecord(value) && value.schemaVersion === 'admin-announcement-import/v1') return 'v1 형식 응답입니다. 화면의 프롬프트를 복사해 다시 요청해 주세요.'
    return '응답에 data 객체가 없습니다. 화면의 프롬프트로 받은 응답인지 확인해 주세요.'
  }
  const data = value.data
  if (!isRecord(data.announcement)) return 'data.announcement 객체가 필요합니다.'
  // 접수처 형식이 틀려도 공급행까지 버리지 않는다. 객체가 아닌 접수처는 빈 접수처로 두어 순번을 지키고 20곳까지만 읽는다(receptionPlacesOf).
  if (data.receptionPlaces !== undefined && data.receptionPlaces !== null && !Array.isArray(data.receptionPlaces)) return 'data.receptionPlaces는 접수처 객체 배열이어야 합니다.'
  if (!Array.isArray(data.supplyRows) || !data.supplyRows.every(isRecord) || !Array.isArray(data.schedules) || !data.schedules.every(isRecord)) {
    return 'data.supplyRows와 data.schedules는 객체 배열이어야 합니다.'
  }
  if (data.supplyRows.length > 200 || data.schedules.length > 100) return '공급행은 200개, 일정은 100개까지 읽을 수 있습니다.'
  for (const [key, entries] of [['unresolvedFields', data.unresolvedFields], ['notApplicable', value.notApplicable]] as const) {
    if (!Array.isArray(entries) || entries.length > 1000 || !entries.every(item => isRecord(item)
      && typeof item.path === 'string' && validPath(normalizePath(item.path)) && typeof item.reason === 'string')) {
      return `${key}에는 path와 reason을 가진 목록이 필요합니다. 미확인 정보를 생략하고 가져올 수 없습니다.`
    }
  }
  if (!Array.isArray(value.evidence) || value.evidence.length > 5000 || !value.evidence.every(item => isRecord(item)
    && typeof item.path === 'string' && validPath(normalizePath(item.path)) && page(item.page) !== null && typeof item.quote === 'string')) {
    return 'evidence의 경로, 쪽 번호(1 이상), 인용문을 확인해 주세요.'
  }
  const sharedProblem = sharedCountProblem(value.sharedCounts, data.supplyRows.map(row => Array.isArray(row.targets) ? row.targets.length : 0))
  if (sharedProblem) return sharedProblem
  const sharedPaths = (value.sharedCounts as { paths: string[] }[]).flatMap(item => item.paths.map(normalizePath))
  if (new Set(sharedPaths).size !== sharedPaths.length) return '한 공급행을 여러 공통 모집수에 넣을 수 없습니다.'
  const sharedValueNotes: ExtractionNote[] = []
  for (const [groupIndex, group] of (value.sharedCounts as { paths: string[] }[]).entries()) {
    for (const path of group.paths) {
      const match = /^supplyRows\[(\d+)\]\.targets\[(\d+)\]\.(supplyHouseholdCount|reserveCount)$/.exec(normalizePath(path))
      if (!match) continue
      const row = data.supplyRows[Number(match[1])]
      const target = isRecord(row) && Array.isArray(row.targets) ? row.targets[Number(match[2])] : undefined
      if (!isRecord(target) || target[match[3]] == null) continue
      target[match[3]] = null
      sharedValueNotes.push({ path: normalizePath(path), reason: `공통 수량 묶음 sharedCounts[${groupIndex}]의 값과 대상별 수량이 함께 있어 대상별 수량은 미확인으로 남겼습니다.` })
    }
  }
  for (const [index, row] of data.supplyRows.entries()) {
    if (!Array.isArray(row.targets) || !row.targets.every(isRecord) || row.targets.length > 100) return '공급행마다 targets 객체 배열이 필요합니다.'
    if (row.subtypes != null && (!Array.isArray(row.subtypes) || !row.subtypes.every(item => isRecord(item) && text(item.name)))) {
      return `supplyRows[${index}].subtypes는 name이 있는 세부 타입 객체 목록이어야 합니다. 세부 타입이 없으면 빈 목록 []로 두세요.`
    }
  }
  const complexes = Array.isArray(data.complexes) ? data.complexes.filter(isRecord).flatMap(item => {
    const name = text(item.sourceComplexName)
    return name ? [{ name, address: text(item.address), totalHouseholdCount: count(item.totalHouseholdCount), parkingCount: count(item.parkingCount) }] : []
  }) : []
  const parsed = {
    sharedCounts: (value.sharedCounts as Record<string, unknown>[]).map(item => ({
      paths: (item.paths as string[]).map(normalizePath), count: count(item.count), page: page(item.page), quote: text(item.quote) ?? '',
    })),
    unresolved: [...notes(data.unresolvedFields), ...sharedValueNotes].reduce((result, item) => {
      const existing = result.find(note => note.path === item.path)
      if (existing) existing.reason += ` ${item.reason}`
      else result.push(item)
      return result
    }, [] as ExtractionNote[]),
    // 공통 수량으로 지정된 경로에는 “이 공고에 이 필드가 없음”이라는 넓은 notApplicable 안내가 맞지 않는다.
    // 겹치는 안내를 걷어 개별 대상 수량은 비어 있는 값(미확인)으로 검토하게 한다.
    notApplicable: notes(value.notApplicable).filter(note => note.path !== 'schedules' && !sharedPaths.some(path => pathCovers(note.path, path))),
    evidence: (value.evidence as Record<string, unknown>[]).map(item => ({ path: normalizePath(item.path as string), page: page(item.page)!, quote: text(item.quote) ?? '' })),
  }
  const read = readSubtypes(data.supplyRows, parsed)
  return {
    announcement: data.announcement,
    receptionPlaces: receptionPlacesOf(data),
    complexes,
    supplyRows: read.rows,
    schedules: data.schedules,
    unresolved: read.unresolved,
    notApplicable: read.notApplicable,
    sharedCounts: read.sharedCounts,
    evidence: read.evidence,
    checks: checks(data.supplyRows, complexes, parsed.sharedCounts),
    undatedSchedules: notes(value.notApplicable).filter(note => note.path === 'schedules' && note.reason).map(note => note.reason),
  }
}

/** 모델 실수를 잡는 검산. 응답의 행 기준으로 계산하며, 다르면 검토 화면에 확인할 내용으로 보여 준다. */
function checks(source: JsonObject[], complexes: ExtractedComplex[], shared: SharedCount[]) {
  const result: string[] = [], sharedPaths = new Set(shared.flatMap(group => group.paths))
  const constructed = (row: JsonObject) => count(row.constructedHouseholdCount) ?? (subtypesOf(row).length && subtypesOf(row).every(item => count(item.constructedHouseholdCount) !== null)
    ? subtypesOf(row).reduce((sum, item) => sum + count(item.constructedHouseholdCount)!, 0) : null)
  for (const complex of complexes) {
    const rows = source.filter(row => isRecord(row.complexReference) && text(row.complexReference.sourceComplexName) === complex.name)
    const counts = rows.map(constructed)
    if (complex.totalHouseholdCount === null || !rows.length || counts.some(value => value === null)) continue
    const sum = counts.reduce((total, value) => total! + value!, 0)!
    if (sum !== complex.totalHouseholdCount) result.push(`${complex.name}: 공급 표 건설호수 합계 ${sum}세대가 단지 전체 세대수 ${complex.totalHouseholdCount}세대와 다릅니다. 공급 표에 없는 주택형이 있는지 확인하세요.`)
  }
  for (const [index, row] of source.entries()) {
    const name = text(row.sourceHousingTypeName) ?? '형명 없음', total = count(row.totalSupplyHouseholdCount)
    const targets = (row.targets as JsonObject[]).map(target => count(target.supplyHouseholdCount))
    const sharedTargets = targets.some((_, at) => sharedPaths.has(`supplyRows[${index}].targets[${at}].supplyHouseholdCount`))
    if (total !== null && targets.length && !sharedTargets && targets.every(value => value !== null)) {
      const sum = targets.reduce((value, item) => value! + item!, 0)
      if (sum !== total) result.push(`${name}: 대상별 모집수 합계 ${sum}세대가 모집수 ${total}세대와 다릅니다.`)
    }
    for (const area of subtypesOf(row).length ? subtypesOf(row) : [row]) {
      const given = area.supplyArea
      const computed = supplyArea({ ...area, supplyArea: null })
      if (typeof given === 'number' && typeof computed === 'number' && Math.abs(given - computed) > 0.00005) {
        result.push(`${text(area.name) ?? name}: 공급면적 ${given}㎡가 전용 + 공용 ${computed}㎡와 달라 계산값을 씁니다.`)
      }
    }
  }
  return result
}

function notes(value: unknown): ExtractionNote[] {
  return Array.isArray(value) ? value.filter(isRecord).flatMap(item => typeof item.path === 'string'
    ? [{ path: normalizePath(item.path), reason: text(item.reason) ?? '' }] : []) : []
}
/** 교정 화면에 모든 잘못된 묶음을 표시한다. 개별 검사와 경로 중복 규칙은 엄격한 읽기와 같다. */
export function sharedCountIssues(document: JsonObject): { index: number; message: string }[] {
  if (!isRecord(document.data) || !Array.isArray(document.data.supplyRows) || !Array.isArray(document.sharedCounts)
    || document.sharedCounts.length > 200) return []
  const targets = document.data.supplyRows.map(row => isRecord(row) && Array.isArray(row.targets) ? row.targets.length : 0)
  const seen = new Set<string>()
  return document.sharedCounts.flatMap((item, index) => {
    let message = sharedCountProblem([item], targets)?.replace('sharedCounts[0]', `sharedCounts[${index}]`) ?? null
    if (isRecord(item) && Array.isArray(item.paths)) {
      for (const path of item.paths) {
        if (typeof path !== 'string') continue
        const normalized = normalizePath(path)
        if (seen.has(normalized)) message ??= `sharedCounts[${index}]: 한 공급행을 여러 공통 모집수에 넣을 수 없습니다.`
        seen.add(normalized)
      }
    }
    return message ? [{ index, message }] : []
  })
}

/** sharedCounts에서 처음 걸린 항목과 이유를 돌려준다. 값은 고치지 않고 다시 추출할 수 있게 위치를 알린다. */
/**
 * 공통 모집수는 여러 공급행의 행 합계, 또는 여러 공급행에 걸친 대상들의 같은 당첨자 수·예비자 수다.
 * 한 묶음에는 한 종류만 넣을 수 있다.
 */
function sharedCountProblem(items: unknown, targetCounts: number[]): string | null {
  const rowCount = targetCounts.length
  if (!Array.isArray(items)) return 'sharedCounts는 목록이어야 합니다. 공통 모집수가 없으면 빈 목록 []로 두세요.'
  if (items.length > 200) return 'sharedCounts는 200개까지 읽을 수 있습니다.'
  for (const [index, item] of items.entries()) {
    const at = `sharedCounts[${index}]`
    if (!isRecord(item)) return `${at}는 paths·count·page·quote를 가진 객체여야 합니다.`
    if (!Array.isArray(item.paths) || item.paths.length < 2) return `${at}.paths에는 모집수를 함께 쓰는 공급행이 2개 이상 있어야 합니다.`
    if (new Set(item.paths).size !== item.paths.length) return `${at}.paths에 같은 공급행이 두 번 들어 있습니다.`
    const locations = new Set<string>(), fields = new Set<string>()
    for (const path of item.paths) {
      const normalized = typeof path === 'string' ? normalizePath(path) : ''
      const match = /^supplyRows\[(\d+)\]\.(?:totalSupplyHouseholdCount|targets\[(\d+)\]\.(supplyHouseholdCount|reserveCount))$/.exec(normalized)
      if (!match) return `${at}.paths의 "${String(path)}"는 supplyRows[번호].totalSupplyHouseholdCount 또는 supplyRows[번호].targets[번호].supplyHouseholdCount/reserveCount 형식이어야 합니다.`
      const row = Number(match[1])
      if (row >= rowCount) return `${at}.paths의 supplyRows[${row}]가 없습니다. 공급행은 ${rowCount}개(0~${rowCount - 1}번)입니다.`
      if (match[2] !== undefined && Number(match[2]) >= targetCounts[row]) return `${at}.paths의 supplyRows[${row}].targets[${match[2]}]가 없습니다. 이 공급행의 대상은 ${targetCounts[row]}개입니다.`
      locations.add(match[2] === undefined ? 'row' : 'target')
      fields.add(match[2] === undefined ? 'totalSupplyHouseholdCount' : match[3])
    }
    if (locations.size > 1) return `${at}.paths에 공급행 모집수와 대상별 모집수가 섞여 있습니다. 한 묶음에는 한 종류만 넣어 주세요.`
    if (fields.size > 1) return `${at}.paths에 서로 다른 수량 필드가 섞여 있습니다. 당첨자 수와 예비자 수를 한 묶음에 넣지 마세요.`
    if (typeof item.count !== 'number' || !Number.isSafeInteger(item.count) || item.count < 0) return `${at}.count는 0 이상의 정수여야 합니다. 현재 값: ${JSON.stringify(item.count) ?? '없음'}`
    if (page(item.page) === null) return `${at}.page는 1 이상의 쪽 번호여야 합니다. 현재 값: ${JSON.stringify(item.page) ?? '없음'}`
    if (typeof item.quote !== 'string') return `${at}.quote에는 공고문에서 옮긴 근거 문구가 필요합니다.`
  }
  return null
}
function count(value: unknown) { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null }
function page(value: unknown) { return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : null }
export function text(value: unknown): string | null { return typeof value === 'string' && value.trim() ? value.trim() : null }
// 접수처를 한 곳만 내던 예전 응답의 경로(receptionPlace.address)는 첫 접수처 경로로 읽는다.
export function normalizePath(path: string) { return path.replace(/\\/g, '').replace(/^\$\.?/, '').replace(/^data\./, '').trim().replace(/^receptionPlace(?=$|[.[])/, 'receptionPlaces[0]') }

/**
 * 응답의 접수처 목록(20곳까지). 객체가 아닌 원소는 빈 접수처로 두어 순번을 지킨다(근거·미확인 경로가 원래 순번을 가리킨다).
 * 빈 접수처는 이름·방식을 읽지 못한 새 접수처라 기본으로 빠진다. 예전 응답의 접수처 객체는 값이 하나라도 있으면 접수처 한 곳이다.
 */
export function receptionPlacesOf(data: Record<string, unknown>): JsonObject[] {
  if (Array.isArray(data.receptionPlaces)) return data.receptionPlaces.slice(0, 20).map(place => isRecord(place) ? place as JsonObject : {})
  return isRecord(data.receptionPlace) && Object.values(data.receptionPlace).some(value => value !== null && value !== '') ? [data.receptionPlace as JsonObject] : []
}
function validPath(path: string) { return /^[A-Za-z][A-Za-z0-9]*(?:\[(?:\d+|\*)\]|\.[A-Za-z][A-Za-z0-9]*)*$/.test(path) }

/** 경로 패턴의 `[*]`는 모든 순번에 맞는다. 패턴이 경로와 같거나 상위 경로이면 맞는 것으로 본다. */
export function pathCovers(pattern: string, path: string) {
  const expression = pattern.split('[*]').map(part => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('\\[\\d+\\]')
  return new RegExp(`^${expression}(?:$|[.\\[])`).test(path)
}
export function noteFor(notes: ExtractionNote[], path: string) { return notes.find(note => pathCovers(note.path, path)) ?? null }

/**
 * PDF에서 강조할 문구. 네 자리 이상 금액 칸(money)은 원·천원·만원 표기(쉼표 있음·없음) 중 인용문에 낱말로 있는 것을 쓴다.
 * 인용문에 없으면(전환보증금 칸이 인용에 빠진 경우) PDF가 어느 단위로 적었는지 알 수 없어 원·천원·만원 표기를 함께 찾는다.
 * 금액은 배열로 돌려줘 PDF에서도 낱말 단위로 찾게 한다(8,100이 18,100 안에서 맞지 않게).
 * 그 밖의 값은 인용문에 그대로 있으면 그 값만, 없으면 인용문에서 고른 검색어를 강조한다.
 */
export function evidenceQuery(quote: string, value?: unknown, money = false, context: readonly string[] = []): string | string[] {
  const text = typeof value === 'string' || typeof value === 'number' ? String(value).trim() : ''
  // 행 문맥(주택형·면적)이 있으면 같은 줄의 그 값만 찾으므로, 세 자리 이하 숫자도 다른 숫자로 바꾸지 않고 낱말 단위로 찾는다.
  if (context.length && /^\d{1,3}$/.test(text)) return [text]
  if (money && /^\d{4,}$/.test(text)) {
    const amount = Number(text), forms = (unit: number) => amount % unit === 0 ? [(amount / unit).toLocaleString('en-US'), String(amount / unit)] : []
    // 세 자리 이하로 줄어든 숫자(150)는 세대수 등으로 쪽 곳곳에 있어 '만'이 붙은 표기로만 찾는다.
    const numbers = [1, 1000, 10000].flatMap(forms).filter(form => form.replace(/\D/g, '').length >= 4)
    // 표 칸에는 (+)·(-)·원이 붙기도 해서 떼고 낱말 단위로 비교한다. 8,100이 8,100,000 안에서 맞지 않게 한다.
    const words = quote.split(/\s+/).map(word => word.replace(/^[(+\-)]+|[원)]+$/g, ''))
    const found = [...numbers, ...forms(10000).map(form => `${form}만`)].find(form => words.includes(form))
    // PDF 글자에서 '150만원'의 '만' 뒤는 낱말 경계가 아니어서 '만원' 표기도 함께 찾는다.
    if (found) return found.endsWith('만') ? [`${found}원`, found] : [found]
    const alternatives = [...new Set([1, 1000, 10000].flatMap(unit => forms(unit).slice(0, 1)))].filter(form => form.replace(/\D/g, '').length >= 4)
    return forms(10000).length ? [...alternatives, `${forms(10000)[0]}만원`] : alternatives
  }
  // 세 자리 이하 숫자(세대수 등)는 쪽 곳곳에 있어 값만 강조하면 엉뚱한 곳이 강조된다.
  return text && !/^\d+$/.test(text) && quote.includes(text) ? text : searchText(quote)
}

/**
 * 공급행 칸(대상별 임대조건 제외)의 근거를 찾을 때 같은 줄에 있어야 하는 표기. 공고문 주택형 표기와 전용면적(051.9800A · 51.98)이다.
 * 공급행 밖의 칸은 문맥 없이 찾는다.
 */
export function evidenceContext(extraction: Pick<Extraction, 'supplyRows'>, path: string): string[] {
  const match = /^supplyRows\[(\d+)\](?:\.(?!targets)[A-Za-z]+)?$/.exec(path)
  const row = match ? extraction.supplyRows[Number(match[1])] : undefined
  if (!row) return []
  const area = row.values.exclusiveArea
  // 접은 행의 표기(26A·26B)는 공고문에 표기마다 따로 있다.
  return [...new Set([...row.typeName?.split('·') ?? [], ...row.subtypes.flatMap(subtype => subtype.name.split('·')), typeof area === 'number' ? String(area) : null].filter((item): item is string => Boolean(item?.trim())))]
}

/** 같은 경로 → 가까운 상위 경로 → 첫 하위 경로 순으로 근거를 찾는다. */
export function evidenceFor(evidence: Evidence[], path: string): Evidence | null {
  const exact = evidence.find(item => item.path === path)
  if (exact) return exact
  const ancestors = evidence.filter(item => pathCovers(item.path, path)).sort((left, right) => right.path.length - left.path.length)
  if (ancestors[0]) return ancestors[0]
  return evidence.find(item => pathCovers(path, item.path)) ?? null
}

/** 근거 문구에서 PDF 검색에 쓸 값을 고른다. 병합 셀 때문에 문구 전체는 원문과 다를 수 있어 숫자 토큰을 우선한다. */
export function searchText(quote: string) {
  const numbers = quote.match(/\d[\d,.:]*\d/g)?.filter(token => token.replace(/\D/g, '').length >= 3) ?? []
  if (numbers.length > 0) return numbers.reduce((longest, token) => token.length > longest.length ? token : longest)
  return quote.replace(/\s+/g, ' ').trim().slice(0, 24)
}
