import { isRecord } from '../management/managementContract'
import type { JsonObject } from '../announcement-json/api'

export type ExtractionNote = { path: string; reason: string }
export type SharedCount = { paths: string[]; count: number | null; page: number | null; quote: string }
export type Evidence = { path: string; page: number; quote: string }
export type ExtractedRow = { complexName: string | null; typeName: string | null; values: JsonObject; targets: JsonObject[] }
export type Extraction = {
  announcement: JsonObject
  receptionPlace: JsonObject
  supplyRows: ExtractedRow[]
  schedules: JsonObject[]
  unresolved: ExtractionNote[]
  notApplicable: ExtractionNote[]
  sharedCounts: SharedCount[]
  evidence: Evidence[]
}
export type ReadResult = { ok: true; value: Extraction; repairs: string[] } | { ok: false; error: string; repairs: string[] }

const jsonEscapes = new Set(['"', '\\', '/', 'b', 'f', 'n', 'r', 't', 'u'])
const maximumBytes = 1_000_000

/** ChatGPT 응답에서 JSON 객체를 읽는다. 값은 바꾸지 않고 형식 문제만 고치며 고친 내용을 모두 돌려준다. */
export function readExtraction(text: string): ReadResult {
  const repairs: string[] = []
  let source = text.replace(/^﻿/, '').trim()
  if (!source) return { ok: false, error: 'ChatGPT 응답을 붙여넣어 주세요.', repairs }
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
  const shape = extraction(value)
  if (typeof shape === 'string') return { ok: false, error: shape, repairs }
  return { ok: true, value: shape, repairs }
}

function extraction(value: unknown): Extraction | string {
  if (!isRecord(value) || !isRecord(value.data)) {
    if (isRecord(value) && value.schemaVersion === 'admin-announcement-import/v1') return 'v1 형식 응답입니다. 화면의 프롬프트를 복사해 다시 요청해 주세요.'
    return '응답에 data 객체가 없습니다. 화면의 프롬프트로 받은 응답인지 확인해 주세요.'
  }
  const data = value.data
  if (!isRecord(data.announcement) || !(isRecord(data.receptionPlace) || data.receptionPlace === null || data.receptionPlace === undefined)) {
    return 'data.announcement 객체가 필요합니다.'
  }
  if (!Array.isArray(data.supplyRows) || !data.supplyRows.every(isRecord) || !Array.isArray(data.schedules) || !data.schedules.every(isRecord)) {
    return 'data.supplyRows와 data.schedules는 객체 배열이어야 합니다.'
  }
  if (data.supplyRows.length > 200 || data.schedules.length > 100) return '공급행은 200개, 일정은 100개까지 읽을 수 있습니다.'
  const rowCount = data.supplyRows.length
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
  if (!Array.isArray(value.sharedCounts) || value.sharedCounts.length > 200 || !value.sharedCounts.every(item => isRecord(item)
    && Array.isArray(item.paths) && item.paths.length >= 2 && new Set(item.paths).size === item.paths.length
    && item.paths.every(path => typeof path === 'string' && /^supplyRows\[\d+\]\.totalSupplyHouseholdCount$/.test(normalizePath(path))
      && Number(/\[(\d+)\]/.exec(path)?.[1]) < rowCount)
    && typeof item.count === 'number' && Number.isSafeInteger(item.count) && item.count >= 0
    && page(item.page) !== null && typeof item.quote === 'string')) {
    return 'sharedCounts의 공급행 경로, 공통 모집수, 근거를 확인해 주세요.'
  }
  const sharedPaths = value.sharedCounts.flatMap(item => (item as { paths: string[] }).paths.map(normalizePath))
  if (new Set(sharedPaths).size !== sharedPaths.length) return '한 공급행을 여러 공통 모집수에 넣을 수 없습니다.'
  const rows: ExtractedRow[] = []
  for (const row of data.supplyRows) {
    if (!Array.isArray(row.targets) || !row.targets.every(isRecord) || row.targets.length > 100) return '공급행마다 targets 객체 배열이 필요합니다.'
    const reference = isRecord(row.complexReference) ? row.complexReference : {}
    rows.push({ complexName: text(reference.sourceComplexName), typeName: text(row.sourceHousingTypeName), values: row, targets: row.targets })
  }
  return {
    announcement: data.announcement,
    receptionPlace: isRecord(data.receptionPlace) ? data.receptionPlace : {},
    supplyRows: rows,
    schedules: data.schedules,
    unresolved: notes(data.unresolvedFields),
    notApplicable: notes(value.notApplicable),
    sharedCounts: Array.isArray(value.sharedCounts) ? value.sharedCounts.filter(isRecord).map(item => ({
      paths: Array.isArray(item.paths) ? item.paths.filter((path): path is string => typeof path === 'string').map(normalizePath) : [],
      count: typeof item.count === 'number' && Number.isSafeInteger(item.count) && item.count >= 0 ? item.count : null,
      page: page(item.page), quote: text(item.quote) ?? '',
    })) : [],
    evidence: Array.isArray(value.evidence) ? value.evidence.filter(isRecord).flatMap(item => {
      const at = page(item.page)
      return typeof item.path === 'string' && at !== null ? [{ path: normalizePath(item.path), page: at, quote: text(item.quote) ?? '' }] : []
    }) : [],
  }
}

function notes(value: unknown): ExtractionNote[] {
  return Array.isArray(value) ? value.filter(isRecord).flatMap(item => typeof item.path === 'string'
    ? [{ path: normalizePath(item.path), reason: text(item.reason) ?? '' }] : []) : []
}
function page(value: unknown) { return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : null }
export function text(value: unknown): string | null { return typeof value === 'string' && value.trim() ? value.trim() : null }
export function normalizePath(path: string) { return path.replace(/\\/g, '').replace(/^\$\.?/, '').replace(/^data\./, '').trim() }
function validPath(path: string) { return /^[A-Za-z][A-Za-z0-9]*(?:\[(?:\d+|\*)\]|\.[A-Za-z][A-Za-z0-9]*)*$/.test(path) }

/** 경로 패턴의 `[*]`는 모든 순번에 맞는다. 패턴이 경로와 같거나 상위 경로이면 맞는 것으로 본다. */
export function pathCovers(pattern: string, path: string) {
  const expression = pattern.split('[*]').map(part => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('\\[\\d+\\]')
  return new RegExp(`^${expression}(?:$|[.\\[])`).test(path)
}
export function noteFor(notes: ExtractionNote[], path: string) { return notes.find(note => pathCovers(note.path, path)) ?? null }

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
