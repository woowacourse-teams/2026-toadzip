import { isRecord } from '../management/managementContract'
import type { JsonObject } from '../announcement-json/api'
import { evidenceFor, type Evidence, type ExtractedRow, type Extraction, type ExtractionNote, type SharedCount, type Subtype } from './extraction'

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

/** 대상 이름이 LH 공급대상 묶음("대학생/청년", "신혼부부·한부모")에 속하는지 */
export function inGroup(target: unknown, group: string) {
  const name = normalizedName(target)
  return group.split('/').map(normalizedName).filter(Boolean).some(token => name.includes(token) || token.includes(name))
}
export function normalizedName(value: unknown) {
  return (text(value) ?? '').normalize('NFKC').toLowerCase().replace(/형|[\s·,]/g, '')
}

const targetKeys = ['target', 'rentalDeposit', 'monthlyRent', 'convertedDeposit', 'applicationCondition']
/** 대상·금액·신청 조건이 행마다 같은지. 모집수는 세부 타입마다 다를 수 있고, 공급 순위는 PDF 보정이 가져오지 않아 비교하지 않는다. */
export function sameTargets(rows: ExtractedRow[]) {
  const key = (row: ExtractedRow) => JSON.stringify(row.targets.map(target => targetKeys.map(field => target[field] ?? null)))
  return rows.every(row => key(row) === key(rows[0]))
}

/** 공고문 행 여럿을 접을 LH 한 행. typeName은 저장할 위치를 고를 때 쓰는 접은 행의 형명, count는 그 행의 모집수다. */
export type FoldGroup = { indexes: number[]; typeName: string; count: number | null }

/**
 * 같은 LH 행을 가리키는 공고문 행 여럿(26A·26B)을 세부 타입 묶음 행 하나로 접는다. 대상은 첫 행의 것을 쓰고 대상별 수는 모두 있으면 더한다.
 * 공고 표기(sourceHousingTypeName)는 LH 행 표기가 아니라 접은 공고문 행의 형명을 이은 것("26A/26B")이다. 단지 주택형은 세부 타입마다 따로 연결한다.
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
    const subtypes = members.flatMap(pdfTypes)
    rows.push({ ...row, typeName: at.group.typeName, subtypes,
      values: { ...row.values, sourceHousingTypeName: subtypes.map(subtype => subtype.name).join('/'), totalSupplyHouseholdCount: at.group.count, exclusiveArea: null, commonArea: null, supplyArea: null, constructedHouseholdCount: null },
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

/** 공고문 행을 맞출 LH 행 하나(주택형 × 공급대상 묶음). rows는 그 LH 행에 맞춘 공고문 행 번호, typeName·count는 LH 행의 표기와 이번 공급 수다. */
export type SourceMatch = { rows: number[]; group: string | null; typeName: string; count: number | null }
/** 맞춘 뒤 행 하나의 출처. copied는 공고문 행 하나를 공급대상 묶음마다 나눴는지, extraTargets는 어느 묶음에도 없어 이 행에 넣은 대상이다. */
export type MatchPlan<T> = { from: number; match: T | null; extraTargets: string[]; copied: boolean }

/** 대상마다 맡을 LH 공급대상 묶음 순번. 묶음이 하나면 모두 그 묶음이고, 어느 묶음에도 없는 대상은 첫 묶음이 맡는다. */
export function targetOwners(row: ExtractedRow, groups: (string | null)[]) {
  return row.targets.map(target => groups.length === 1 ? 0 : Math.max(0, groups.findIndex(group => group !== null && inGroup(target.target, group))))
}

/**
 * 공고문 행을 LH 행(주택형 × 공급대상 묶음)에 맞춘다. LH 원천행 맞추기(alignOn)와 단지에 연결된 LH 행 나누기(splitSameSourceRows)가 같은 규칙을 쓴다.
 * - 공고문 행 하나가 여러 LH 행에 맞으면 행을 LH 행마다 복사하고 대상은 묶음 이름으로 나눈다. 공고문 모집수는 묶음을 합친 수라
 *   공고문 행 하나가 LH 행 하나 전체인 복사본은 LH 행의 이번 공급 수를 쓰고, 형명(typeName)은 저장할 LH 행을 고르도록 LH 행 표기로 둔다.
 *   공고 표기(sourceHousingTypeName)는 공고문 표기 그대로다.
 * - LH 행 하나에 맞춘 공고문 행 여럿은 대상·금액이 같으면 세부 타입 묶음 행 하나로 접고(모집수는 LH 행의 이번 공급 수),
 *   다르면 행마다 두고 LH 공급 수를 공통 모집수로 묶는다. split은 그렇게 묶은 LH 행이다.
 * 맞춘 행이 걸린 공고문 공통 모집수는 LH 행 단위 모집수로 바뀌므로 지운다. 행 번호가 바뀌어 메모·근거 경로를 옮긴다.
 */
export function alignToSource<T extends SourceMatch>(source: Extraction, matches: T[]) {
  const rows: ExtractedRow[] = [], copies = new Map<number, { index: number; targets: number[] }[]>(), plans: MatchPlan<T>[] = []
  source.supplyRows.forEach((row, from) => {
    const matched = matches.filter(match => match.rows.includes(from))
    if (!matched.length) {
      copies.set(from, [{ index: rows.length, targets: row.targets.map((_, at) => at) }])
      rows.push(row); plans.push({ from, match: null, extraTargets: [], copied: false })
      return
    }
    const owners = targetOwners(row, matched.map(match => match.group)), copied = matched.length > 1
    const extraTargets = copied ? row.targets.filter(target => !matched.some(match => match.group && inGroup(target.target, match.group))).map(target => String(target.target ?? '')) : []
    copies.set(from, matched.map((match, order) => {
      const kept = owners.flatMap((owner, at) => owner === order ? [at] : [])
      const whole = copied && match.rows.length === 1
      const values: JsonObject = match.rows.length > 1 ? { ...row.values, totalSupplyHouseholdCount: null }
        : whole ? { ...row.values, totalSupplyHouseholdCount: match.count } : { ...row.values }
      rows.push({ ...row, typeName: whole ? match.typeName : row.typeName, values, targets: kept.map(at => row.targets[at]) })
      plans.push({ from, match, extraTargets: order === 0 ? extraTargets : [], copied })
      return { index: rows.length - 1, targets: row.targets.map((_, at) => kept.indexOf(at)) }
    }))
  })
  const remap = (path: string): string[] => {
    const match = /^supplyRows\[(\d+)\](?:\.targets\[(\d+|\*)\])?(.*)$/.exec(path)
    if (!match) return [path]
    return (copies.get(Number(match[1])) ?? []).flatMap(copy => {
      if (match[2] === undefined) return [`supplyRows[${copy.index}]${match[3]}`]
      if (match[2] === '*') return [`supplyRows[${copy.index}].targets[*]${match[3]}`]
      const target = copy.targets[Number(match[2])]
      return target === undefined || target < 0 ? [] : [`supplyRows[${copy.index}].targets[${target}]${match[3]}`]
    })
  }
  const aligned = new Set(matches.flatMap(match => match.rows))
  // LH 행에 맞춘 행은 공고문의 묶음 대신 LH 행 단위 묶음을 쓴다. 한 행이 두 묶음에 들어갈 수 없다.
  const kept = source.sharedCounts.filter(group => !group.paths.some(path => {
    const row = Number(/^supplyRows\[(\d+)\]/.exec(path)?.[1])
    return aligned.has(row) && (path.endsWith('.totalSupplyHouseholdCount') || (copies.get(row)?.length ?? 0) > 1)
  })).map(group => ({ ...group, paths: group.paths.flatMap(remap) }))
  const planIndexes = (match: T) => match.rows.map(from => plans.findIndex(plan => plan.from === from && plan.match === match))
  const divided = matches.filter(match => match.rows.length > 1)
  const folds = divided.filter(match => sameTargets(planIndexes(match).map(index => rows[index])))
  const split = divided.filter(match => !folds.includes(match))
  const splitCounts: SharedCount[] = split.map(match => {
    const evidence = evidenceFor(source.evidence, `supplyRows[${match.rows[0]}]`)
    return { paths: planIndexes(match).map(index => `supplyRows[${index}].totalSupplyHouseholdCount`), count: match.count, page: evidence?.page ?? null, quote: evidence?.quote ?? '' }
  })
  const note = (item: ExtractionNote) => remap(item.path).map(path => ({ ...item, path }))
  const unfolded: Extraction = { ...source, supplyRows: rows, sharedCounts: [...kept, ...splitCounts],
    unresolved: source.unresolved.flatMap(note), notApplicable: source.notApplicable.flatMap(note),
    evidence: source.evidence.flatMap(item => remap(item.path).map(path => ({ ...item, path }))) }
  const folded = foldRows(unfolded, folds.map(match => ({ indexes: planIndexes(match), typeName: match.typeName, count: match.count })))
  // 접은 행은 첫 공고문 행의 계획만 남긴다.
  return { extraction: folded.extraction, plans: plans.filter((_, at) => folded.position.indexOf(folded.position[at]) === at), folds, split,
    remap: (path: string) => remap(path).flatMap(folded.remap) }
}

/** 공급대상 묶음으로 나눈 행의 안내 */
export function matchNote(plan: MatchPlan<SourceMatch>) {
  return [plan.copied && plan.match?.group ? `대상은 '${plan.match.group}' 묶음만 넣었습니다.` : '',
    plan.extraTargets.length ? `${plan.extraTargets.join(', ')}은(는) LH 공급대상 묶음에 없어 이 행에 넣었습니다. 확인하세요.` : ''].filter(Boolean).join(' ')
}

function text(value: unknown): string | null { return typeof value === 'string' && value.trim() ? value.trim() : null }
function count(value: unknown) { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null }
function area(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? value : null }
