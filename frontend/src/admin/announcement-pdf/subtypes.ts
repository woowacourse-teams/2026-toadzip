import { isRecord } from '../management/managementContract'
import type { JsonObject } from '../announcement-json/api'
import { applicationTypeKey, withoutApplicant } from '../supply/applicationType'
import { areaKey } from './areas'
import { type Evidence, type ExtractedRow, type Extraction, type ExtractionNote, type SharedCount, type Subtype } from './extraction'

export type Notes = { sharedCounts: SharedCount[]; unresolved: ExtractionNote[]; notApplicable: ExtractionNote[]; evidence: Evidence[] }
const groupedAreaNote = '세부 타입마다 주택타입이 달라 공급행 면적은 대표 주택타입을 따릅니다.'

export function subtypesOf(row: JsonObject) { return Array.isArray(row.subtypes) ? row.subtypes as JsonObject[] : [] }

/**
 * "26A/B/C"처럼 세부 타입을 묶은 공급행(subtypes)은 모집수 하나를 함께 쓰는 공급행 하나라 펼치지 않고 한 행으로 둔다. 모집수·대상은 그 행의 값이고,
 * 세부 타입마다 형명·면적·건설호수를 subtypes에 담는다. 세부 타입이 하나면 그 값을 행 값으로 쓰고, 그 이름(주택타입)은 values.houseTypeName에 둔다.
 * 세부 타입 경로의 공용면적(subtypes[n].commonArea)은 계산한 공급면적 경로로 옮긴다. 공급면적은 전용 + 공용으로 계산한다.
 */
export function readSubtypes(source: JsonObject[], parsed: Notes): Notes & { rows: ExtractedRow[] } {
  const notApplicable: ExtractionNote[] = []
  // 공고문 한 칸에 같은 면적의 단지 주택타입 여럿을 이어 적은 세부 타입('59A·59A1·59C')은 주택타입마다 나눈다. 공고에서는 신청형 한 행으로
  // 묶고 단지 주택타입은 따로 둔다. 건설호수는 주택타입별로 나눌 수 없어 비운다. origins[행][새 세부 타입] = 원래 세부 타입 번호다.
  const origins: number[][] = []
  const rows = source.map((row, index): ExtractedRow => {
    const reference = isRecord(row.complexReference) ? row.complexReference : {}
    const { subtypes: _subtypes, ...values } = row
    const split = subtypesOf(row).flatMap((subtype, at) => {
      const names = (text(subtype.name) ?? '').split('·').map(name => name.trim()).filter(Boolean)
      return names.length > 1 ? names.map(name => ({ at, subtype: { ...subtype, name, constructedHouseholdCount: null } })) : [{ at, subtype }]
    })
    origins[index] = split.map(item => item.at)
    const subtypes = split.map(item => item.subtype)
    // 세부 타입 하나는 그 값을 행 값으로 쓰되, 공고 표기는 신청형(행 표기) 그대로 두고 세부 타입 이름(주택타입)은 houseTypeName에 남긴다.
    const single = subtypes.length === 1 ? { ...values, sourceHousingTypeName: text(values.sourceHousingTypeName) ?? text(subtypes[0].name), houseTypeName: text(subtypes[0].name), exclusiveArea: subtypes[0].exclusiveArea ?? null,
      commonArea: subtypes[0].commonArea ?? null, supplyArea: subtypes[0].supplyArea ?? null, constructedHouseholdCount: subtypes[0].constructedHouseholdCount ?? row.constructedHouseholdCount ?? null } : values
    const grouped = subtypes.length > 1 ? subtypes.map(subtype => ({ name: text(subtype.name)!, exclusiveArea: area(subtype.exclusiveArea),
      supplyArea: area(supplyArea(subtype)), constructedHouseholdCount: count(subtype.constructedHouseholdCount) })) : []
    if (grouped.length) notApplicable.push(...['exclusiveArea', 'supplyArea'].map(field => ({ path: `supplyRows[${index}].${field}`, reason: groupedAreaNote })))
    const typeName = text(single.sourceHousingTypeName) ?? (grouped.length ? grouped.map(subtype => subtype.name).join('/') : null)
    return { complexName: text(reference.sourceComplexName), typeName, values: { ...single, sourceHousingTypeName: typeName, supplyArea: supplyArea(single) },
      targets: (row.targets as JsonObject[]).map(target => ({ ...target })), subtypes: grouped }
  })
  // 세부 타입이 하나뿐인 행은 그 값이 행 값이므로 세부 타입 경로를 행 경로로 옮긴다. 나눈 세부 타입의 경로는 나뉜 세부 타입 모두로 옮긴다.
  const remap = (path: string): string[] => {
    const match = /^supplyRows\[(\d+)\]\.subtypes\[(\d+|\*)\](.*)$/.exec(path)
    if (!match) return [path]
    const rest = match[3] === '.commonArea' ? '.supplyArea' : match[3]
    if (!rows[Number(match[1])]?.subtypes.length) return [`supplyRows[${match[1]}]${rest === '.name' ? '.sourceHousingTypeName' : rest}`]
    if (match[2] === '*') return [`supplyRows[${match[1]}].subtypes[*]${rest}`]
    return (origins[Number(match[1])] ?? []).flatMap((at, next) => at === Number(match[2]) ? [`supplyRows[${match[1]}].subtypes[${next}]${rest}`] : [])
  }
  const notes = (items: ExtractionNote[]) => items.flatMap(item => remap(item.path).map(path => ({ ...item, path })))
  return { rows, sharedCounts: parsed.sharedCounts, unresolved: notes(parsed.unresolved), notApplicable: [...notes(parsed.notApplicable), ...notApplicable],
    evidence: parsed.evidence.flatMap(item => remap(item.path).map(path => ({ ...item, path }))) }
}

/** 공급행이 연결할 주택형 값. 세부 타입 묶음이면 세부 타입마다, 아니면 행 하나의 형명·면적이다. */
export function pdfTypes(row: ExtractedRow): Subtype[] {
  if (row.subtypes.length) return row.subtypes
  // 세부 타입이 하나뿐인 행은 그 주택타입 이름(houseTypeName)이 단지 쪽 이름이다. 없으면 공고 표기를 쓴다.
  return [{ name: text(row.values.houseTypeName) ?? row.typeName ?? '', exclusiveArea: area(row.values.exclusiveArea), supplyArea: area(row.values.supplyArea), constructedHouseholdCount: count(row.values.constructedHouseholdCount) }]
}

/** 공급면적 = 주거전용 + 주거공용. 공용면적이 없으면 공고문의 공급면적을 그대로 쓴다. 자릿수는 두 값 중 긴 쪽에 맞춘다. */
export function supplyArea(values: JsonObject) {
  const exclusive = values.exclusiveArea, common = values.commonArea
  if (typeof exclusive !== 'number' || typeof common !== 'number') return values.supplyArea ?? null
  const digits = Math.max(...[exclusive, common].map(value => String(value).split('.')[1]?.length ?? 0))
  return Number((exclusive + common).toFixed(digits))
}

/** 끝에 붙은 괄호 묶음(중첩 포함)만 뗀다. "26(고령(주거약자용))" → "26", "(구)36(청년)" → "(구)36" */
const nameKey = (value: unknown) => typeof value === 'string' ? withoutTrailingParentheses(value).normalize('NFKC').replace(/\s+/g, '').toLowerCase() : ''
/**
 * 단지 주택타입 이름을 바꿀 PDF 주택타입 이름. 공고 표기는 주택형(신청형, 059.9000B)이고 단지 쪽 이름은 주택타입(59B)이다.
 * 그 전용면적의 주택타입이 하나일 때('·'로 잇지 않은 이름)만 쓰고, 신청형과 같은 이름(모델이 세부 타입을 신청형으로 낸 경우)은 쓰지 않는다.
 */
export function renamedTypeName(houseType: unknown, applyType: unknown): string | null {
  if (typeof houseType !== 'string' || !houseType.trim() || houseType.includes('·') || nameKey(houseType) === nameKey(applyType)) return null
  return withoutTrailingParentheses(houseType)
}
/** 새로 만들 단지 주택타입 이름. 한 면적에 주택타입이 여럿이면 첫 표기를 쓰고 끝의 신청자 표기( )·[ ]는 뗀다(서버 newTypeName과 같은 규칙). */
export function createdTypeName(name: string) { return withoutApplicant(name.split('·')[0]) }
export function withoutTrailingParentheses(value: string) {
  let result = value.trim()
  while (result.endsWith(')')) {
    let depth = 0, start = -1
    for (let at = result.length - 1; at >= 0; at--) {
      if (result[at] === ')') depth++
      if (result[at] === '(' && --depth === 0) { start = at; break }
    }
    if (start <= 0) break
    result = result.slice(0, start).trim()
  }
  return result
}

/** 접은 행의 모집수. 공고문이 접는 행끼리만 묶은 공통 모집수가 있으면 그 수(한 번만 센다), 행마다 모집수가 모두 있으면 합이다. */
export function foldedCount(extraction: Pick<Extraction, 'supplyRows' | 'sharedCounts'>, indexes: number[]) {
  const paths = indexes.map(index => `supplyRows[${index}].totalSupplyHouseholdCount`)
  const shared = extraction.sharedCounts.find(group => group.paths.length === paths.length && paths.every(path => group.paths.includes(path)))
  if (shared) return shared.count
  const counts = indexes.map(index => extraction.supplyRows[index].values.totalSupplyHouseholdCount)
  return counts.every(value => typeof value === 'number') ? (counts as number[]).reduce((sum, value) => sum + value, 0) : null
}

const targetKeys = ['target', 'rentalDeposit', 'monthlyRent', 'convertedDeposit', 'applicationCondition']
const sameTarget = (left: JsonObject, right: JsonObject) => targetKeys.every(field => JSON.stringify(left[field] ?? null) === JSON.stringify(right[field] ?? null))
const sum = (values: unknown[]) => values.every(value => typeof value === 'number') ? (values as number[]).reduce((total, value) => total + value, 0) : null
const rowTotal = /^supplyRows\[(\d+)\]\.totalSupplyHouseholdCount$/
const rowOf = /^supplyRows\[(\d+)\]/
const complexOf = (row: ExtractedRow) => row.complexName?.trim() || ''
const typeFields: Record<string, string> = { '.sourceHousingTypeName': '.name', '.exclusiveArea': '.exclusiveArea', '.supplyArea': '.supplyArea', '.commonArea': '.supplyArea', '.constructedHouseholdCount': '.constructedHouseholdCount' }

/**
 * 공급행은 공고 단지 × 주택형(신청형) 하나이고 신청자 구분(우선·일반·고령자·청년 등)은 그 행의 대상이다. 같은 PDF 단지에서
 * 신청형(applicationTypeKey, 끝의 신청자 표기를 뗀 형명)이 같은 공고문 행과, 행 모집수 하나를 함께 쓰는 행(공통 모집수)은 공급행 하나로 접는다.
 * 같은 전용면적이어도 신청형이 다르면(55A1·55C(주거약자)) 접지 않고, 저장할 때 같은 단지 주택타입을 함께 가리킨다.
 * 대상은 행 순서대로 잇되 이름·금액·신청 조건이 같은 대상은 하나로 두고 대상별 수를 더한다(모르는 수가 있으면 비운다). 모집수는 함께 쓰는 공통 모집수가 정확히 그 행들이면 그 수를 한 번, 아니면 행마다 모집수가 모두 있을 때 합이다.
 * 전용면적이 하나면 세부 타입 없이 그 면적의 행이 되고, 여럿이면 면적마다 세부 타입 하나다.
 * 표기는 끝 괄호(신청자 구분)를 뗀 형명을 신청형마다 하나씩 '·'로 잇는다. 행 번호가 바뀌어 근거·메모·공통 모집수 경로를 옮긴다.
 */
export function foldSameHousingType(extraction: Extraction): Extraction {
  const source = extraction.supplyRows
  const root = source.map((_, index) => index)
  const find = (index: number): number => root[index] === index ? index : (root[index] = find(root[index]))
  const join = (left: number, right: number) => { const a = find(left), b = find(right); if (a !== b) root[Math.max(a, b)] = Math.min(a, b) }
  const types = source.map(row => applicationTypeKey(row.typeName))
  // 단지가 둘 이상인 공고에서 단지명이 빈 행은 어느 단지인지 모른다. 신청형이 같아도 다른 블록의 행(광주선운2 A-1·A-3블록의
  // 55B-1)일 수 있어 접지 않는다. 행은 따로 두고 저장할 곳·단지 정하기에서 단지를 정한다.
  const complexNames = new Set([...extraction.complexes.map(complex => complex.name.trim()), ...source.map(complexOf)].filter(Boolean))
  const sameKnownComplex = (left: ExtractedRow, right: ExtractedRow) => complexOf(left) === complexOf(right) && (Boolean(complexOf(left)) || complexNames.size < 2)
  source.forEach((row, left) => source.forEach((other, right) => {
    if (left < right && types[left] && sameKnownComplex(row, other) && types[left] === types[right]) join(left, right)
  }))
  // 공고문이 여러 행에 걸쳐 한 번만 적은 모집수는 그 행들이 한 공급행이라는 뜻이다(면적이 달라도 세부 타입으로 접는다, 결정 1).
  // 대상 예비자 수만 여러 행에 걸친 경우는 행마다 자기 모집수·대상 공급 수가 없을 때만이다(GH 예비자 모집). 따로 있으면 행은 그대로 둔다.
  for (const group of extraction.sharedCounts) {
    const indexes = [...new Set(group.paths.map(path => rowOf.exec(path)?.[1]).map(Number))]
    if (indexes.length < 2 || indexes.some(index => !Number.isInteger(index) || !source[index])) continue
    const rowCount = group.paths.every(path => rowTotal.test(path))
    if (!rowCount && indexes.some(index => typeof source[index].values.totalSupplyHouseholdCount === 'number'
      || source[index].targets.some(target => typeof target.supplyHouseholdCount === 'number'))) continue
    if (indexes.every(index => sameKnownComplex(source[index], source[indexes[0]]))) indexes.forEach(index => join(indexes[0], index))
  }
  const members = new Map<number, number[]>()
  source.forEach((_, index) => members.set(find(index), [...members.get(find(index)) ?? [], index]))
  if ([...members.values()].every(group => group.length === 1)) return extraction

  // 행마다 새 번호·대상 시작 위치·세부 타입 번호(원래 형 순번 → 접은 행의 세부 타입 순번)
  type Place = { at: number; first: boolean; targets: number[]; own: number[]; types: number[]; grouped: boolean }
  const places: Place[] = [], rows: ExtractedRow[] = []
  for (const [, indexes] of [...members].sort(([left], [right]) => left - right)) {
    const at = rows.length, first = source[indexes[0]]
    if (indexes.length === 1) { rows.push(first); places[indexes[0]] = { at, first: true, targets: first.targets.map((_, item) => item), own: [], types: pdfTypes(first).map((_, item) => item), grouped: first.subtypes.length > 0 }; continue }
    const merged: (Subtype & { names: string[]; key: string | null; counts: (number | null)[] })[] = [], targets: JsonObject[] = []
    for (const index of indexes) {
      const types = pdfTypes(source[index]).map(type => {
        // 같은 신청형으로 접힌 행들의 세부 타입은 단지 주택타입 이름으로 합친다. 같은 면적이어도 이름이 다르면(59A·59A1) 따로 둔다.
        const name = withoutTrailingParentheses(type.name), nameKey = applicationTypeKey(name)
        const key = nameKey ? `name:${nameKey}` : areaKey(type.exclusiveArea) === null ? null : `area:${areaKey(type.exclusiveArea)}`
        let item = key === null ? undefined : merged.find(entry => entry.key === key)
        if (!item) { item = { ...type, names: [], key, counts: [] }; merged.push(item) }
        if (name && !item.names.includes(name)) item.names.push(name)
        item.supplyArea ??= type.supplyArea
        item.counts.push(type.constructedHouseholdCount)
        return merged.indexOf(item)
      })
      const own: number[] = []
      const positions = source[index].targets.map(target => {
        const same = targets.findIndex(item => sameTarget(item, target))
        if (same < 0) { targets.push({ ...target }); own.push(targets.length - 1); return targets.length - 1 }
        for (const field of ['supplyHouseholdCount', 'reserveCount']) targets[same][field] = sum([targets[same][field], target[field]])
        return same
      })
      places[index] = { at, first: index === indexes[0], targets: positions, own, types, grouped: false }
    }
    const subtypes = merged.map(({ names, key: _key, counts, ...type }) => ({ ...type, name: names.join('·') || type.name,
      constructedHouseholdCount: sum(counts) }))
    const names: string[] = []
    for (const name of indexes.map(index => withoutApplicant(source[index].typeName))) {
      if (name && !names.some(item => applicationTypeKey(item) === applicationTypeKey(name))) names.push(name)
    }
    const typeName = names.join('·') || null, single = subtypes.length === 1 ? subtypes[0] : null
    // 접은 행마다 워커가 맞춘 원천 공급행 틀(GH)이 따로 있으면 모두 남겨, 저장할 때 한 행을 고치고 나머지는 합친다.
    const sourceRows = [...new Set(indexes.map(index => source[index].values.sourceRow).filter((value): value is string => typeof value === 'string'))]
    indexes.forEach(index => { places[index].grouped = !single })
    rows.push({ ...first, typeName, subtypes: single ? [] : subtypes, targets,
      // 접은 행들의 주택타입 이름(houseTypeName)이 모두 같을 때만 남긴다. 다르면 단지 주택타입 이름을 바꾸지 않는다.
      values: { ...first.values, houseTypeName: new Set(indexes.map(index => extraction.supplyRows[index].values.houseTypeName ?? null)).size === 1 ? first.values.houseTypeName ?? null : null,
        sourceRow: sourceRows[0] ?? null, sourceRows, sourceHousingTypeName: typeName, totalSupplyHouseholdCount: foldedCount(extraction, indexes), commonArea: null,
        exclusiveArea: single?.exclusiveArea ?? null, supplyArea: single?.supplyArea ?? null, constructedHouseholdCount: single?.constructedHouseholdCount ?? null } })
  }
  const remap = (path: string): string[] => {
    const match = /^supplyRows\[(\d+)\](.*)$/.exec(path), place = match ? places[Number(match[1])] : undefined
    if (!match || !place) return [path]
    const row = source[Number(match[1])], next = `supplyRows[${place.at}]`, rest = match[2]
    const target = /^\.targets\[(\d+|\*)\](.*)$/.exec(rest)
    if (target) return [...new Set(target[1] === '*' ? place.targets : [place.targets[Number(target[1])]])].filter(at => at !== undefined).map(at => `${next}.targets[${at}]${target[2]}`)
    const subtype = /^\.subtypes\[(\d+|\*)\](.*)$/.exec(rest)
    if (subtype) return [...new Set(subtype[1] === '*' ? place.types : [place.types[Number(subtype[1])]])].filter(at => at !== undefined).map(at => `${next}.subtypes[${at}]${subtype[2]}`)
    // 뒤에 접힌 행의 행 근거·메모는 그 행이 새로 더한 대상의 것이다. 접은 행 전체에 붙이면 앞 행의 근거를 가린다.
    // 표기 대조용으로 그 행 근거를 `labels[원래 행]`에도 남긴다(접은 표기 26A·26B의 각 표기를 근거 인용과 대조할 때 쓴다).
    if (!rest) return place.first ? [next] : [...place.own.map(at => `${next}.targets[${at}]`), `${next}.labels[${match[1]}]`]
    if (typeFields[rest] && place.grouped && !row.subtypes.length) return [`${next}.subtypes[${place.types[0]}]${typeFields[rest]}`]
    return place.first ? [`${next}${rest}`] : []
  }
  const laterRow = (path: string) => { const match = /^supplyRows\[(\d+)\]$/.exec(path); return Boolean(match && places[Number(match[1])] && !places[Number(match[1])].first) }
  const sharedCounts: SharedCount[] = [], lost: ExtractionNote[] = [], moved: Evidence[] = []
  for (const group of extraction.sharedCounts) {
    const paths = [...new Set(group.paths.flatMap(remap))]
    if (paths.length > 1) { sharedCounts.push({ ...group, paths }); continue }
    const rowIndexes = group.paths.map(path => Number(rowTotal.exec(path)?.[1]))
    const folded = rowIndexes.every(Number.isInteger) && new Set(rowIndexes.map(index => places[index]?.at)).size === 1
    // 접은 행들만 함께 쓰던 행 모집수는 접은 행의 모집수가 됐다(foldedCount). 일부만 함께 썼으면 모집수를 정하지 못해 근거를 남긴다.
    if (folded) {
      const at = places[rowIndexes[0]].at, path = `supplyRows[${at}].totalSupplyHouseholdCount`
      if (rows[at].values.totalSupplyHouseholdCount !== group.count) lost.push({ path, reason: `공고문이 일부 주택형만 묶어 적은 공통 모집수 ${group.count ?? '미확인'}세대로는 이 공급행의 모집수를 정하지 못했습니다. PDF ${group.page ?? '쪽 미상'}쪽 “${group.quote}”를 확인하세요.` })
      if (group.page !== null) moved.push({ path, page: group.page, quote: group.quote })
      continue
    }
    const path = paths[0], field = path && /^supplyRows\[(\d+)\]\.targets\[(\d+)\]\.(supplyHouseholdCount|reserveCount)$/.exec(path)
    if (!field) continue
    // 대상 하나에만 남은 대상별 공통 수량은 그 대상의 값이다. 이미 다른 값이 있으면 비우고 확인하게 한다.
    const item = rows[Number(field[1])].targets[Number(field[2])], current = item?.[field[3]]
    if (item && (current == null || current === group.count)) item[field[3]] = group.count
    else {
      if (item) item[field[3]] = null
      lost.push({ path, reason: `행을 접으면서 공통 수량 ${group.count ?? '미확인'}이 대상 하나에 모였지만 그 대상의 값 ${String(current)}과 다릅니다. PDF ${group.page ?? '쪽 미상'}쪽 “${group.quote}”를 확인하세요.` })
    }
    if (group.page !== null) moved.push({ path, page: group.page, quote: group.quote })
  }
  const notes = (items: ExtractionNote[]) => items.flatMap(item => remap(item.path).map(path => ({ ...item, path })))
  const grouped = rows.flatMap((row, at) => row.subtypes.length ? ['exclusiveArea', 'supplyArea'].map(field => ({ path: `supplyRows[${at}].${field}`, reason: groupedAreaNote })) : [])
  return { ...extraction, supplyRows: rows, sharedCounts, unresolved: [...notes(extraction.unresolved), ...lost],
    notApplicable: [...notes(extraction.notApplicable).filter(item => !grouped.some(added => added.path === item.path)), ...grouped],
    // 뒤에 접힌 행의 행 근거는 대상 칸 자기 근거보다 뒤에 두어, 대상 칸은 자기 근거를 먼저 찾게 한다.
    evidence: [...[false, true].flatMap(borrowed => extraction.evidence.filter(item => borrowed === laterRow(item.path))).flatMap(item => remap(item.path).map(path => ({ ...item, path }))), ...moved] }
}

function text(value: unknown): string | null { return typeof value === 'string' && value.trim() ? value.trim() : null }
function count(value: unknown) { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null }
function area(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? value : null }
