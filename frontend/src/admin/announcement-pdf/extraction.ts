import { isRecord } from '../management/managementContract'
import type { JsonObject } from '../announcement-json/api'

export type ExtractionNote = { path: string; reason: string }
export type SharedCount = { paths: string[]; count: number | null; page: number | null; quote: string }
export type Evidence = { path: string; page: number; quote: string }
/** 공고문 단지 개요. 단지를 찾거나 새로 등록할 때 쓰며, 없거나 형식이 틀리면 비워 둔다. */
export type ExtractedComplex = { name: string; address: string | null; totalHouseholdCount: number | null; parkingCount: number | null }
export type ExtractedRow = { complexName: string | null; typeName: string | null; values: JsonObject; targets: JsonObject[] }
export type Extraction = {
  announcement: JsonObject
  receptionPlace: JsonObject
  complexes: ExtractedComplex[]
  supplyRows: ExtractedRow[]
  schedules: JsonObject[]
  unresolved: ExtractionNote[]
  notApplicable: ExtractionNote[]
  sharedCounts: SharedCount[]
  evidence: Evidence[]
  /** 합계·면적 검산에서 다르게 나온 항목. 저장을 막지 않고 확인할 내용으로 보여 준다. */
  checks: string[]
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
    unresolved: notes(data.unresolvedFields), notApplicable: notes(value.notApplicable),
    evidence: (value.evidence as Record<string, unknown>[]).map(item => ({ path: normalizePath(item.path as string), page: page(item.page)!, quote: text(item.quote) ?? '' })),
  }
  const expanded = expandSubtypes(data.supplyRows, parsed)
  if (expanded.rows.length > 200) return '세부 타입으로 펼친 공급행이 200개를 넘습니다. 한 공고의 응답만 붙여넣어 주세요.'
  return {
    announcement: data.announcement,
    receptionPlace: isRecord(data.receptionPlace) ? data.receptionPlace : {},
    complexes,
    supplyRows: expanded.rows,
    schedules: data.schedules,
    unresolved: expanded.unresolved,
    notApplicable: expanded.notApplicable,
    sharedCounts: expanded.sharedCounts,
    evidence: expanded.evidence,
    checks: checks(data.supplyRows, complexes, parsed.sharedCounts),
  }
}

type Notes = { sharedCounts: SharedCount[]; unresolved: ExtractionNote[]; notApplicable: ExtractionNote[]; evidence: Evidence[] }
const subtypeCountNote = '세부 타입이 함께 쓰는 모집수라 대상별로 저장하지 않습니다.'

function subtypesOf(row: JsonObject) { return Array.isArray(row.subtypes) ? row.subtypes as JsonObject[] : [] }

/**
 * "26A/B/C"처럼 세부 타입을 묶은 공급행(subtypes)을 세부 타입마다 한 행으로 펼친다. 대상·금액은 행마다 복사하고,
 * 묶인 모집수는 펼친 행들의 공통 모집수로 옮긴다. 응답의 행 번호를 쓰는 경로(공통 모집수·메모·근거)는 펼친 행 번호로 바꾼다.
 * 공급면적은 전용 + 공용으로 계산한다.
 */
function expandSubtypes(source: JsonObject[], parsed: Notes): Notes & { rows: ExtractedRow[] } {
  const rows: ExtractedRow[] = [], at: number[][] = [], notApplicable: ExtractionNote[] = [], generated: SharedCount[] = []
  const sharedRows = new Set(parsed.sharedCounts.flatMap(group => group.paths.map(path => /^supplyRows\[(\d+)\]\.totalSupplyHouseholdCount$/.exec(path)?.[1]).filter(Boolean).map(Number)))
  for (const [index, row] of source.entries()) {
    const reference = isRecord(row.complexReference) ? row.complexReference : {}
    const targets = row.targets as JsonObject[], subtypes = subtypesOf(row), grouped = subtypes.length > 1
    const variants: JsonObject[] = subtypes.length ? subtypes.map(subtype => ({ ...row, sourceHousingTypeName: text(subtype.name),
      exclusiveArea: subtype.exclusiveArea ?? null, commonArea: subtype.commonArea ?? null, supplyArea: subtype.supplyArea ?? null,
      constructedHouseholdCount: subtype.constructedHouseholdCount ?? (grouped ? null : row.constructedHouseholdCount ?? null),
      totalSupplyHouseholdCount: grouped ? null : row.totalSupplyHouseholdCount ?? null })) : [row]
    at[index] = variants.map(variant => {
      const { subtypes: _subtypes, ...values } = variant
      const copied = targets.map(target => grouped ? { ...target, supplyHouseholdCount: null } : { ...target })
      rows.push({ complexName: text(reference.sourceComplexName), typeName: text(values.sourceHousingTypeName), values: { ...values, supplyArea: supplyArea(values) }, targets: copied })
      if (grouped) notApplicable.push({ path: `supplyRows[${rows.length - 1}].targets[*].supplyHouseholdCount`, reason: subtypeCountNote })
      return rows.length - 1
    })
    const total = count(row.totalSupplyHouseholdCount)
    if (grouped && total !== null && !sharedRows.has(index)) {
      const evidence = parsed.evidence.find(item => item.path === `supplyRows[${index}]`)
      generated.push({ paths: at[index].map(row => `supplyRows[${row}].totalSupplyHouseholdCount`), count: total, page: evidence?.page ?? null, quote: evidence?.quote ?? '' })
    }
  }
  const remap = (path: string) => {
    const match = /^supplyRows\[(\d+|\*)\](?:\.subtypes\[(\d+|\*)\])?(.*)$/.exec(path)
    if (!match) return [path]
    const rest = match[3] === '.commonArea' ? '.supplyArea' : match[3]
    if (match[1] === '*') return [`supplyRows[*]${rest}`]
    const targets = at[Number(match[1])] ?? []
    return (match[2] === undefined || match[2] === '*' ? targets : targets.slice(Number(match[2]), Number(match[2]) + 1)).map(row => `supplyRows[${row}]${rest}`)
  }
  const note = (item: ExtractionNote) => remap(item.path).map(path => ({ ...item, path }))
  // 세부 타입 묶음 안의 대상별 공통 모집수는 대상별로 저장하지 않으므로 버린다.
  const sharedCounts = parsed.sharedCounts.filter(group => !group.paths.some(path => {
    const match = /^supplyRows\[(\d+)\]\.targets/.exec(path)
    return match !== null && subtypesOf(source[Number(match[1])]).length > 1
  })).map(group => ({ ...group, paths: group.paths.flatMap(remap) })).filter(group => group.paths.length > 1)
  return { rows, sharedCounts: [...sharedCounts, ...generated], unresolved: parsed.unresolved.flatMap(note),
    notApplicable: [...parsed.notApplicable.flatMap(note), ...notApplicable], evidence: parsed.evidence.flatMap(item => remap(item.path).map(path => ({ ...item, path }))) }
}

/** 공급면적 = 주거전용 + 주거공용. 공용면적이 없으면 공고문의 공급면적을 그대로 쓴다. 자릿수는 두 값 중 긴 쪽에 맞춘다. */
function supplyArea(values: JsonObject) {
  const exclusive = values.exclusiveArea, common = values.commonArea
  if (typeof exclusive !== 'number' || typeof common !== 'number') return values.supplyArea ?? null
  const digits = Math.max(...[exclusive, common].map(value => String(value).split('.')[1]?.length ?? 0))
  return Number((exclusive + common).toFixed(digits))
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
/** sharedCounts에서 처음 걸린 항목과 이유를 돌려준다. 값은 고치지 않고 ChatGPT에 다시 요청할 수 있게 위치를 알린다. */
/**
 * 공통 모집수는 두 가지다. 여러 공급행이 함께 쓰는 모집수(supplyRows[n].totalSupplyHouseholdCount)와,
 * 한 공급행 안의 여러 대상이 함께 쓰는 모집수(supplyRows[n].targets[m].supplyHouseholdCount, 예: 대학생·청년 통합 추첨)다.
 * 한 묶음에는 한 종류만, 대상 묶음은 같은 공급행의 대상만 넣을 수 있다.
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
    const kinds = new Set<string>(), rows = new Set<number>()
    for (const path of item.paths) {
      const normalized = typeof path === 'string' ? normalizePath(path) : ''
      const match = /^supplyRows\[(\d+)\]\.(?:totalSupplyHouseholdCount|targets\[(\d+)\]\.supplyHouseholdCount)$/.exec(normalized)
      if (!match) return `${at}.paths의 "${String(path)}"는 supplyRows[번호].totalSupplyHouseholdCount 또는 supplyRows[번호].targets[번호].supplyHouseholdCount 형식이어야 합니다.`
      const row = Number(match[1])
      if (row >= rowCount) return `${at}.paths의 supplyRows[${row}]가 없습니다. 공급행은 ${rowCount}개(0~${rowCount - 1}번)입니다.`
      if (match[2] !== undefined && Number(match[2]) >= targetCounts[row]) return `${at}.paths의 supplyRows[${row}].targets[${match[2]}]가 없습니다. 이 공급행의 대상은 ${targetCounts[row]}개입니다.`
      kinds.add(match[2] === undefined ? 'row' : 'target'); rows.add(row)
    }
    if (kinds.size > 1) return `${at}.paths에 공급행 모집수와 대상별 모집수가 섞여 있습니다. 한 묶음에는 한 종류만 넣어 주세요.`
    if (kinds.has('target') && rows.size > 1) return `${at}.paths의 대상별 모집수는 같은 공급행의 대상끼리만 묶을 수 있습니다.`
    if (typeof item.count !== 'number' || !Number.isSafeInteger(item.count) || item.count < 0) return `${at}.count는 0 이상의 정수여야 합니다. 현재 값: ${JSON.stringify(item.count) ?? '없음'}`
    if (page(item.page) === null) return `${at}.page는 1 이상의 쪽 번호여야 합니다. 현재 값: ${JSON.stringify(item.page) ?? '없음'}`
    if (typeof item.quote !== 'string') return `${at}.quote에는 공고문에서 옮긴 근거 문구가 필요합니다.`
  }
  return null
}
function count(value: unknown) { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null }
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
