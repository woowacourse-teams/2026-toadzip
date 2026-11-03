import { isRecord } from '../management/managementContract'
import type { JsonObject } from '../announcement-json/api'
import type { Evidence, ExtractedRow, Extraction, ExtractionNote, SharedCount, Subtype } from './extraction'

export type Notes = { sharedCounts: SharedCount[]; unresolved: ExtractionNote[]; notApplicable: ExtractionNote[]; evidence: Evidence[] }
const groupedAreaNote = '세부 타입마다 면적이 달라 공급행 면적은 대표 주택형을 따릅니다.'

export function subtypesOf(row: JsonObject) { return Array.isArray(row.subtypes) ? row.subtypes as JsonObject[] : [] }

/**
 * "26A/B/C"처럼 세부 타입을 묶은 공급행(subtypes)은 LH 한 행이라 펼치지 않고 한 행으로 둔다. 모집수·대상은 그 행의 값이고,
 * 세부 타입마다 형명·면적·건설호수를 subtypes에 담는다. 세부 타입이 하나면 그 값을 행 값으로 쓴다.
 * 세부 타입 경로의 공용면적(subtypes[n].commonArea)은 계산한 공급면적 경로로 옮긴다. 공급면적은 전용 + 공용으로 계산한다.
 */
export function readSubtypes(source: JsonObject[], parsed: Notes): Notes & { rows: ExtractedRow[] } {
  const notApplicable: ExtractionNote[] = []
  const rows = source.map((row, index): ExtractedRow => {
    const reference = isRecord(row.complexReference) ? row.complexReference : {}
    const { subtypes: _subtypes, ...values } = row, subtypes = subtypesOf(row)
    const single = subtypes.length === 1 ? { ...values, sourceHousingTypeName: text(subtypes[0].name), exclusiveArea: subtypes[0].exclusiveArea ?? null,
      commonArea: subtypes[0].commonArea ?? null, supplyArea: subtypes[0].supplyArea ?? null, constructedHouseholdCount: subtypes[0].constructedHouseholdCount ?? row.constructedHouseholdCount ?? null } : values
    const grouped = subtypes.length > 1 ? subtypes.map(subtype => ({ name: text(subtype.name)!, exclusiveArea: area(subtype.exclusiveArea),
      supplyArea: area(supplyArea(subtype)), constructedHouseholdCount: count(subtype.constructedHouseholdCount) })) : []
    if (grouped.length) notApplicable.push(...['exclusiveArea', 'supplyArea'].map(field => ({ path: `supplyRows[${index}].${field}`, reason: groupedAreaNote })))
    const typeName = text(single.sourceHousingTypeName) ?? (grouped.length ? grouped.map(subtype => subtype.name).join('/') : null)
    return { complexName: text(reference.sourceComplexName), typeName, values: { ...single, sourceHousingTypeName: typeName, supplyArea: supplyArea(single) },
      targets: (row.targets as JsonObject[]).map(target => ({ ...target })), subtypes: grouped }
  })
  // 세부 타입이 하나뿐인 행은 그 값이 행 값이므로 세부 타입 경로를 행 경로로 옮긴다.
  const remap = (path: string) => {
    const match = /^supplyRows\[(\d+)\]\.subtypes\[(\d+|\*)\](.*)$/.exec(path)
    if (!match) return path
    const rest = match[3] === '.commonArea' ? '.supplyArea' : match[3]
    return rows[Number(match[1])]?.subtypes.length ? `supplyRows[${match[1]}].subtypes[${match[2]}]${rest}` : `supplyRows[${match[1]}]${rest === '.name' ? '.sourceHousingTypeName' : rest}`
  }
  const note = (item: ExtractionNote) => ({ ...item, path: remap(item.path) })
  return { rows, sharedCounts: parsed.sharedCounts, unresolved: parsed.unresolved.map(note), notApplicable: [...parsed.notApplicable.map(note), ...notApplicable],
    evidence: parsed.evidence.map(item => ({ ...item, path: remap(item.path) })) }
}

/** 공급행이 연결할 주택형 값. 세부 타입 묶음이면 세부 타입마다, 아니면 행 하나의 형명·면적이다. */
export function pdfTypes(row: ExtractedRow): Subtype[] {
  if (row.subtypes.length) return row.subtypes
  return [{ name: row.typeName ?? '', exclusiveArea: area(row.values.exclusiveArea), supplyArea: area(row.values.supplyArea), constructedHouseholdCount: count(row.values.constructedHouseholdCount) }]
}

/** 공급면적 = 주거전용 + 주거공용. 공용면적이 없으면 공고문의 공급면적을 그대로 쓴다. 자릿수는 두 값 중 긴 쪽에 맞춘다. */
export function supplyArea(values: JsonObject) {
  const exclusive = values.exclusiveArea, common = values.commonArea
  if (typeof exclusive !== 'number' || typeof common !== 'number') return values.supplyArea ?? null
  const digits = Math.max(...[exclusive, common].map(value => String(value).split('.')[1]?.length ?? 0))
  return Number((exclusive + common).toFixed(digits))
}

export type SourceLabel = { types: string[]; group: string | null }

/** "26A/B/C(고령자)" → 26A·26B·26C와 공급대상 묶음 "고령자". "26A,B"처럼 쉼표로 묶어도 같다. */
export function parseSourceLabel(label: string): SourceLabel {
  const match = /^(.*?)\s*(?:\(([^)]*)\))?\s*$/.exec(label.trim())
  const tokens = (match?.[1] ?? '').split(/[/,]/).map(token => token.trim()).filter(Boolean)
  const prefix = /^\d+/.exec(tokens[0] ?? '')?.[0] ?? ''
  return { types: tokens.map((token, at) => at > 0 && /^[A-Za-z]+$/.test(token) ? prefix + token : token), group: match?.[2]?.trim() || null }
}

const targetKeys = ['target', 'supplyRank', 'rentalDeposit', 'monthlyRent', 'convertedDeposit', 'applicationCondition']
/** 대상·순위·금액·신청 조건이 행마다 같은지. 모집수는 세부 타입마다 다를 수 있어 비교하지 않는다. */
export function sameTargets(rows: ExtractedRow[]) {
  const key = (row: ExtractedRow) => JSON.stringify(row.targets.map(target => targetKeys.map(field => target[field] ?? null)))
  return rows.every(row => key(row) === key(rows[0]))
}

/** 공고문 행 여럿을 접을 LH 한 행. typeName은 접은 행의 표기, count는 그 행의 모집수다. */
export type FoldGroup = { indexes: number[]; typeName: string; count: number | null }

/**
 * 같은 LH 행을 가리키는 공고문 행 여럿(26A·26B)을 세부 타입 묶음 행 하나로 접는다. 대상은 첫 행의 것을 쓰고 대상별 수는 모두 있으면 더한다.
 * 행 번호가 바뀌므로 근거·메모·공통 모집수 경로를 옮긴다. 접은 행의 형명·면적·건설호수 경로는 세부 타입 경로가 되고,
 * 그 밖의 경로는 첫 행 것만 남긴다(대상이 같아 나머지는 겹친다). 접은 행 안에서만 쓰던 공통 모집수는 행 모집수가 되어 지운다.
 */
export function foldRows(extraction: Extraction, groups: FoldGroup[]) {
  const position: number[] = [], rows: ExtractedRow[] = [], offsets = new Map<number, { group: FoldGroup; member: number; offset: number }>()
  for (const group of groups) {
    let offset = 0
    group.indexes.forEach((index, member) => { offsets.set(index, { group, member, offset }); offset += pdfTypes(extraction.supplyRows[index]).length })
  }
  extraction.supplyRows.forEach((row, index) => {
    const at = offsets.get(index)
    if (!at) { position[index] = rows.length; rows.push(row); return }
    if (at.member > 0) { position[index] = position[at.group.indexes[0]]; return }
    const members = at.group.indexes.map(item => extraction.supplyRows[item])
    const sum = (values: unknown[]) => values.every(value => typeof value === 'number') ? (values as number[]).reduce((total, value) => total + value, 0) : null
    position[index] = rows.length
    rows.push({ ...row, typeName: at.group.typeName, subtypes: members.flatMap(pdfTypes),
      values: { ...row.values, sourceHousingTypeName: at.group.typeName, totalSupplyHouseholdCount: at.group.count, exclusiveArea: null, commonArea: null, supplyArea: null, constructedHouseholdCount: null },
      targets: row.targets.map((target, item) => ({ ...target, supplyHouseholdCount: sum(members.map(member => member.targets[item]?.supplyHouseholdCount)),
        reserveCount: sum(members.map(member => member.targets[item]?.reserveCount)) })) })
  })
  const typeFields: Record<string, string> = { '.sourceHousingTypeName': '.name', '.exclusiveArea': '.exclusiveArea', '.supplyArea': '.supplyArea', '.commonArea': '.supplyArea', '.constructedHouseholdCount': '.constructedHouseholdCount' }
  const remap = (path: string): string[] => {
    const match = /^supplyRows\[(\d+)\](.*)$/.exec(path)
    if (!match) return [path]
    const index = Number(match[1]), rest = match[2], at = offsets.get(index), next = `supplyRows[${position[index]}]`
    if (!at) return [`${next}${rest}`]
    const types = pdfTypes(extraction.supplyRows[index]).map((_, item) => `${next}.subtypes[${at.offset + item}]`)
    if (!rest) return [...at.member === 0 ? [next] : [], ...types]
    const subtype = /^\.subtypes\[(\d+|\*)\](.*)$/.exec(rest)
    if (subtype) return subtype[1] === '*' ? types.map(type => `${type}${subtype[2]}`) : [`${next}.subtypes[${at.offset + Number(subtype[1])}]${subtype[2]}`]
    if (typeFields[rest] && !extraction.supplyRows[index].subtypes.length) return [`${types[0]}${typeFields[rest]}`]
    return at.member === 0 ? [`${next}${rest}`] : []
  }
  const note = (item: ExtractionNote) => remap(item.path).map(path => ({ ...item, path }))
  const sharedCounts = extraction.sharedCounts.map(group => ({ ...group, paths: [...new Set(group.paths.flatMap(remap))] })).filter(group => group.paths.length > 1)
  const folded = groups.map(group => position[group.indexes[0]])
  const notApplicable = folded.flatMap(index => ['exclusiveArea', 'supplyArea'].map(field => ({ path: `supplyRows[${index}].${field}`, reason: groupedAreaNote })))
  return { position, remap, extraction: { ...extraction, supplyRows: rows, sharedCounts, unresolved: extraction.unresolved.flatMap(note),
    notApplicable: [...extraction.notApplicable.flatMap(note).filter(item => !notApplicable.some(added => added.path === item.path)), ...notApplicable],
    evidence: extraction.evidence.flatMap(item => remap(item.path).map(path => ({ ...item, path }))) } satisfies Extraction }
}

function text(value: unknown): string | null { return typeof value === 'string' && value.trim() ? value.trim() : null }
function count(value: unknown) { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null }
function area(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? value : null }
