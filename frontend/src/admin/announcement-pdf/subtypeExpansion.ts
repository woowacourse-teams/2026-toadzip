import { isRecord } from '../management/managementContract'
import type { JsonObject } from '../announcement-json/api'
import type { Evidence, ExtractedRow, ExtractionNote, SharedCount } from './extraction'

export type Notes = { sharedCounts: SharedCount[]; unresolved: ExtractionNote[]; notApplicable: ExtractionNote[]; evidence: Evidence[] }
const subtypeCountNote = '세부 타입이 함께 쓰는 모집수라 대상별로 저장하지 않습니다.'

export function subtypesOf(row: JsonObject) { return Array.isArray(row.subtypes) ? row.subtypes as JsonObject[] : [] }

/**
 * "26A/B/C"처럼 세부 타입을 묶은 공급행(subtypes)을 세부 타입마다 한 행으로 펼친다. 대상·금액은 행마다 복사하고,
 * 묶인 모집수는 펼친 행들의 공통 모집수로 옮긴다. 응답의 행 번호를 쓰는 경로(공통 모집수·메모·근거)는 펼친 행 번호로 바꾼다.
 * 공급면적은 전용 + 공용으로 계산한다.
 */
export function expandSubtypes(source: JsonObject[], parsed: Notes): Notes & { rows: ExtractedRow[] } {
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
export function supplyArea(values: JsonObject) {
  const exclusive = values.exclusiveArea, common = values.commonArea
  if (typeof exclusive !== 'number' || typeof common !== 'number') return values.supplyArea ?? null
  const digits = Math.max(...[exclusive, common].map(value => String(value).split('.')[1]?.length ?? 0))
  return Number((exclusive + common).toFixed(digits))
}

function text(value: unknown): string | null { return typeof value === 'string' && value.trim() ? value.trim() : null }
function count(value: unknown) { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null }
