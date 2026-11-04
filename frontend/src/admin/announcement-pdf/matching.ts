import { isRecord } from '../management/managementContract'
import type { JsonObject, JsonWorkspace } from '../announcement-json/api'
import type { MatchingHousingType } from '../ingest/supplyMatchingApi'
import type { AnnouncementComplex, CorrectionRow, LhSupplyRow, SupplyWorkspace } from '../supply/supplyCorrectionApi'
import { evidenceFor, noteFor, text, type Extraction, type Subtype } from './extraction'
import { alignToSource, foldRows, inGroup, matchNote, normalizedName, parseSourceLabel, pdfTypes, sameTargets, targetOwners, type FoldGroup, type SourceMatch } from './subtypes'

export type HousingCandidates = Record<number, MatchingHousingType[]>
/** 단지 ID별 표시 이름(단지명 · 임대유형) */
export type ComplexLabels = Record<number, string>
export type RowProposal = {
  destination: string
  anchorKey: string
  complexId: number | null
  housingTypeId: number | null
  candidates: MatchingHousingType[]
  /** 저장할 위치로 보여 줄 기존 공급행. 같은 단지에서 주택형 표기·전용면적이 맞는 행이다. */
  recommended: string[]
  reason: string
  needsConfirmation: boolean
  sharedIndex: number | null
  newComplex?: { id: number; name: string; pnu: string; category: CorrectionRow['category'] }
  /**
   * LH 공급 원본 행에 놓는 방식. SPLIT은 통합 LH 행을 주택형별로 나누고(분리 묶음의 첫 행이 통합행 destination을 이어받고 나머지는 'new'),
   * ATTACH는 공급행이 없는 LH 행에 새 행을 붙인다(destination 'new'). 없으면 destination이 기존 행 키면 수정, 'new'면 별도 추가다.
   */
  saveMode?: 'SPLIT' | 'ATTACH'
  lhRow?: string
  /** 처음 제안한 통합행 분리의 LH 행. 운영자가 저장 방식을 바꿔도 남아, 분리로 되돌릴 때 이 LH 행을 먼저 고른다. */
  proposedLhRow?: string
  /** 표기·면적 근거 없이 남은 행 하나만 있어 자동으로 고르지 않은 경우 */
  ambiguous?: boolean
}
/** undated는 시작일이 없는 안내(계약 개별안내 등)라 일정으로 저장하지 않는다는 뜻이다. */
export type ScheduleProposal = { existingIds: number[]; needsConfirmation: boolean; reason: string; undated?: boolean }
/** 행 키별 LH 원천행의 주택형 표기("26A/B/C(고령자)"). 단지에 연결된 행의 typeName은 단지 주택형 이름("26A")이라 공급대상 묶음을 읽을 수 없다. */
export type SourceLabels = ReadonlyMap<string, string>

export function lhSourceLabels(supply: Pick<SupplyWorkspace, 'sources' | 'rowSources'>): SourceLabels {
  return new Map((supply.rowSources ?? []).filter(link => link.category === 'LH_ANNOUNCEMENT_SUPPLY').flatMap(link => {
    const rows = supply.sources.find(source => source.category === link.category && source.sourceKey === link.sourceKey)?.rows ?? []
    const names = [...new Set(rows.flatMap(row => isRecord(row.raw) && typeof row.raw.type_name === 'string' && row.raw.type_name.trim() ? [row.raw.type_name.trim()] : []))]
    return names.length === 1 ? [[link.rowKey, names[0]] as const] : []
  }))
}
const sourceLabel = (row: CorrectionRow, labels: SourceLabels) => labels.get(row.key) ?? row.typeName
/** 같은 단지에서 같은 세부 타입 묶음을 가진 LH 행끼리 같은 키다. 공급대상 묶음만 다른 LH 행("26A/B(고령자)"·"26A/B(주거급여수급자)")을 찾는다. */
const subtypeSetKey = (row: CorrectionRow, labels: SourceLabels) => `${row.housingComplexId}|${parseSourceLabel(sourceLabel(row, labels)).types.map(typeKey).sort().join('/')}`

/** 수집된 공고명에는 공고일·"공고" 표기가 붙어 있어 날짜·공고·기호를 지우고 포함 관계로 비교한다. */
function titleKey(value: unknown) {
  return titleWords(value).join('')
}
// 괄호 위치나 어순이 달라도 같은 공고인지 보려고 단어 단위로 나눈다(전주시(전주평화1,전주평화4) 영구임대 ↔ 전주시 영구임대 (전주평화1, 전주평화4)).
function titleWords(value: unknown) {
  return (text(value) ?? '').normalize('NFKC').replace(/\d{4}\s*[.\-/년]\s*\d{1,2}\s*[.\-/월]\s*\d{1,2}\s*일?\.?/g, ' ')
    .replace(/공고일|공고문?/g, ' ').split(/[\s\p{P}\p{S}]+/u).map(word => word.toLowerCase()).filter(Boolean)
}
// 단지명 뒤의 단지·아파트 같은 표기만 다르면 같은 단어로 본다(김제검산1 ↔ 김제검산1단지). 숫자가 이어지면 다른 단지다.
function sameTitleWord(left: string, right: string) {
  const [short, long] = left.length <= right.length ? [left, right] : [right, left]
  return short === long || long.startsWith(short) && /^(단지|아파트|주공|지구)$/.test(long.slice(short.length))
}
function sameTitle(pdf: unknown, current: unknown) {
  const pdfKey = titleKey(pdf), currentKey = titleKey(current)
  if (pdfKey.includes(currentKey) || currentKey.includes(pdfKey)) return true
  const pdfWords = new Set(titleWords(pdf)), currentWords = new Set(titleWords(current))
  const within = (small: Set<string>, large: Set<string>) => [...small].every(word => [...large].some(other => sameTitleWord(word, other)))
  return within(pdfWords, currentWords) || within(currentWords, pdfWords)
}
/** 정정공고 PDF에 적힌 앞선 공고일은 원공고 공고일일 수 있어 다른 공고의 PDF라고 판단하지 않는다. */
export function originalDate(pdfDate: string, current: JsonObject) {
  const currentDate = text(current.postedDate)
  return /정정/.test(text(current.name) ?? '') && !!currentDate && pdfDate.slice(0, 10) < currentDate.slice(0, 10)
}
export type AnnouncementCheck = { label: '공고명' | '공고일'; pdf: string | null; current: string | null; matches: boolean | null }
/** 붙여넣은 응답이 지금 공고의 PDF에서 나온 것인지 공고명·공고일로 확인한다. 어느 한쪽 값이 없으면 판단하지 않는다. */
export function checkAnnouncement(extraction: Extraction, current: JsonObject): AnnouncementCheck[] {
  const pdfName = text(extraction.announcement.name), currentName = text(current.name)
  const pdfDate = text(extraction.announcement.postedDate), currentDate = text(current.postedDate)
  return [
    { label: '공고명', pdf: pdfName, current: currentName, matches: titleKey(pdfName) && titleKey(currentName) ? sameTitle(pdfName, currentName) : null },
    { label: '공고일', pdf: pdfDate, current: currentDate, matches: !pdfDate || !currentDate || originalDate(pdfDate, current) ? null : pdfDate.slice(0, 10) === currentDate.slice(0, 10) },
  ]
}
export function sameComplex(left: unknown, right: unknown) {
  const a = normalizedName(left), b = normalizedName(right)
  return !!a && !!b && (a.includes(b) || b.includes(a))
}
/** 주택형 표기 비교용. 숫자 앞뒤의 0만 다른 표기(공고문 074.9900A ↔ LH 74.9900A)는 같다. */
export function typeKey(value: unknown) {
  return normalizedName(value).replace(/\d+(?:\.\d+)?/g, number => String(Number(number)))
}
/** 블록 번호(A40BL·A-40·A40블록·(2)A48 → a40). 블록 표기가 없거나 서로 다른 블록이 섞이면 null. */
export function blockKey(value: unknown) {
  const matches = (text(value) ?? '').normalize('NFKC').toLowerCase().matchAll(/(?<![a-z])([a-z])-?(\d{1,3})(?=bl|블록|블럭|[^a-z0-9]|$)/g)
  const keys = new Set([...matches].map(match => match[1] + String(Number(match[2]))))
  return keys.size === 1 ? [...keys][0] : null
}
const addressKey = (value: unknown) => (text(value) ?? '').normalize('NFKC').replace(/\s+/g, '')
/**
 * PDF 단지명이 가리키는 원천 단지명(공고 단지 이름). 이름이 포함 관계면 그 이름들이다.
 * 이름으로 찾지 못하면 공고문 블록 번호(동탄2 A40BL ↔ 화성동탄2A-40(공임리츠) A-40)와 공고문 단지 주소 ↔ 공고 단지 주소로 찾는다.
 * 둘 다 있으면 둘이 함께 가리키는 공고 단지여야 하고, 후보가 하나일 때만 정한다.
 * certain은 이름이나 블록 번호·주소가 함께 맞았는지다. 한쪽만 맞았으면 화면에서 대응을 확인하게 한다.
 */
export function sourceComplexNames(extraction: Extraction, pdfName: string | null, names: string[], complexes: Pick<AnnouncementComplex, 'name' | 'roadAddress'>[] = []) {
  const byName = names.filter(name => sameComplex(pdfName, name))
  if (byName.length) return { names: byName, certain: true }
  const block = blockKey(pdfName), address = addressKey(extraction.complexes.find(item => item.name === pdfName)?.address)
  const byBlock = block ? names.filter(name => blockKey(name) === block) : []
  const byAddress = address ? names.filter(name => addressKey(complexes.find(item => item.name === name)?.roadAddress) === address) : []
  const matched = byBlock.length && byAddress.length ? byBlock.filter(name => byAddress.includes(name)) : byBlock.length ? byBlock : byAddress
  return matched.length === 1 ? { names: matched, certain: byBlock.length > 0 && byAddress.length > 0 } : { names: [], certain: false }
}
export function equalArea(left: unknown, right: unknown) {
  return typeof left === 'number' && typeof right === 'number' && Math.abs(left - right) < 0.00005
}

export type TypePick = { housingTypeId: number | null; reason: string; options: MatchingHousingType[] }
/** 형명·면적 하나의 전용면적 → 건설호수 → 공급면적 → 이름 순서로 단지 주택형 후보를 좁힌다. options는 전용면적(면적이 없으면 이름)이 맞는 후보다. */
function pickType(extraction: Extraction, path: string, type: Subtype, allTypes: MatchingHousingType[]): TypePick {
  let candidates = allTypes.filter(item => equalArea(item.exclusiveArea, type.exclusiveArea))
  if (!candidates.length && type.exclusiveArea == null) {
    candidates = allTypes.filter(item => normalizedName(item.name) === normalizedName(type.name))
  }
  const options = candidates
  let reason = candidates.length ? `전용면적 ${String(type.exclusiveArea ?? '')}㎡`
    : allTypes.length && typeof type.exclusiveArea === 'number' ? `이 단지에 PDF 전용 ${type.exclusiveArea}㎡와 맞는 주택형이 없습니다.` : '전용면적에 맞는 주택형을 확인해 주세요.'
  if (candidates.length > 1 && typeof type.constructedHouseholdCount === 'number') {
    const byCount = candidates.filter(item => item.householdCount === type.constructedHouseholdCount)
    if (byCount.length) { candidates = byCount; reason += ` · 건설호수 ${type.constructedHouseholdCount}세대` }
  }
  if (candidates.length > 1 && !noteFor(extraction.unresolved, `${path}.supplyArea`)) {
    const byArea = candidates.filter(item => equalArea(item.supplyArea, type.supplyArea))
    if (byArea.length) { candidates = byArea; reason += ' · 공급면적 일치' }
  }
  if (candidates.length > 1) {
    const byName = candidates.filter(item => typeKey(item.name) === typeKey(type.name))
    if (byName.length) candidates = byName
  }
  const housingTypeId = candidates.length === 1 ? candidates[0].id : null
  return { housingTypeId, options, reason: housingTypeId === null && candidates.length ? `${reason} · 주택형을 하나로 특정하지 못했습니다.` : reason }
}

/** 세부 타입 묶음 행의 세부 타입마다 단지 주택형을 고른다. */
export function pickSubtypes(extraction: Extraction, index: number, allTypes: MatchingHousingType[]) {
  return extraction.supplyRows[index].subtypes.map((type, at) => pickType(extraction, `supplyRows[${index}].subtypes[${at}]`, type, allTypes))
}

/** PDF 행이 연결할 단지 주택형. 세부 타입 묶음 행은 첫 세부 타입의 주택형이고, 안내에 세부 타입마다 고른 근거를 적는다. */
export function pickHousingType(extraction: Extraction, index: number, allTypes: MatchingHousingType[]) {
  const row = extraction.supplyRows[index]
  if (!row.subtypes.length) return pickType(extraction, `supplyRows[${index}]`, pdfTypes(row)[0], allTypes)
  const picks = pickSubtypes(extraction, index, allTypes)
  return { ...picks[0], reason: `세부 타입 ${picks.length}개 · ${picks.map((pick, at) => `${row.subtypes[at].name}: ${pick.reason}`).join(' · ')}` }
}

/**
 * 단지는 정했지만 그 단지에 PDF 전용면적과 같은 주택형이 없으면 PDF 형명·면적을 돌려준다.
 * 이때만 'PDF 형명·면적으로 새 주택형 추가'를 고를 수 있다.
 */
/** 단지에 연결되지 않은 기존 공급행(최초 입주자 공고처럼 등록 단지가 없던 원천행). */
export function emptyRow(row: CorrectionRow) { return !row.excluded && row.housingComplexId === null && row.groupId === null }

/** 서버의 주택형 면적 형식(0 이상, 정수 6자리·소수 4자리까지) */
export const areaValue = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 999999.9999
  && Math.abs(value * 10000 - Math.round(value * 10000)) < .00001
export function missingHousingType(complexId: number | null, candidates: MatchingHousingType[], typeName: string, exclusiveArea: unknown, supplyArea: unknown) {
  // 새 주택형의 전용면적은 0보다 커야 한다. 공급면적은 형식이 맞지 않으면 비워 둔다.
  if (complexId === null || !areaValue(exclusiveArea) || exclusiveArea === 0 || candidates.some(type => equalArea(type.exclusiveArea, exclusiveArea))) return null
  return { name: typeName.trim(), exclusiveArea, supplyArea: areaValue(supplyArea) ? supplyArea : null }
}

// 공고문과 수집 원천의 단지명 표기가 다를 때(전주평화1단지 ↔ 평화주공1단지) 이번 모집수가 유일하게 같은 기존 행을 제안한다.
function sameRecruitment(row: Extraction['supplyRows'][number], current: CorrectionRow[]) {
  const count = row.values.totalSupplyHouseholdCount
  if (typeof count !== 'number') return []
  const matched = current.filter(item => !item.excluded && item.groupId === null && item.householdCount === count)
  return matched.length === 1 ? matched : []
}

/**
 * 같은 단지의 기존 공급행 중 이 PDF 행을 저장할 행. 주택형 표기 → 전용면적(또는 연결 주택형) 순서로 하나를 고르고,
 * 하나로 좁히지 못하면 전용면적이 맞는 행들을 추천으로 돌려준다. 다른 PDF 행이 이미 고른 행(used)은 고르지 않는다.
 * 저장할 위치 기본값은 고른 행, 추천이 여럿이면 미선택, 추천이 없으면 새 공급정보로 추가다.
 * 표기·면적 근거 없이 남은 행이 하나뿐이면 고르지 않고 그 행을 추천만 하며 ambiguous로 알린다(통합 LH 행 하나에 여러 PDF 행이 몰리는 경우).
 * lastRow가 false면 표기·면적이 맞지 않는 남은 행 모두를 추천해 운영자가 고르게 한다(단지 정하기에서 고른 단지의 행).
 */
export function chooseRow(row: Extraction['supplyRows'][number], related: CorrectionRow[], used: Set<string>, housingTypeId: number | null, lastRow = true, labels: SourceLabels = new Map(), count: number | null = null) {
  const available = related.filter(item => !used.has(item.key))
  const byName = available.filter(item => [item.typeName, sourceLabel(item, labels)].some(name => typeKey(name) === typeKey(row.typeName)))
  const byArea = available.filter(item => pdfTypes(row).some(type => equalArea(item.exclusiveArea, type.exclusiveArea))
    || housingTypeId !== null && item.housingTypeId === housingTypeId)
  // 남은 행 하나는 모집수(행 모집수나 공통 모집수)가 같을 때만 근거로 본다.
  const sameCount = lastRow && available.length === 1 && count !== null && available[0].householdCount === count
  const chosen = byName.length === 1 ? byName[0] : byArea.length === 1 ? byArea[0] : sameCount ? available[0] : undefined
  const ambiguous = !chosen && !byName.length && !byArea.length && lastRow && available.length === 1
  const recommended = (chosen ? [chosen] : byArea.length ? byArea : ambiguous || !lastRow ? available : []).map(item => item.key)
  return { chosen, recommended, ambiguous, destination: chosen?.key ?? (recommended.length ? '' : 'new') }
}

export function proposeRows(extraction: Extraction, all: CorrectionRow[], housing: HousingCandidates, complexes: Pick<AnnouncementComplex, 'name' | 'roadAddress'>[] = [], labels: SourceLabels = new Map()): RowProposal[] {
  const used = new Set<string>()
  // 단지가 연결되지 않은 기존 행은 단지를 정한 뒤 LH 원천행 맞추기(sourceAlignment)에서 연결·분할한다.
  const current = all.filter(item => !emptyRow(item) && !item.excluded)
  const names = [...new Set(all.filter(item => !item.excluded).map(item => item.complexName))]
  return extraction.supplyRows.map((row, index) => {
    const named = current.filter(item => sameComplex(row.complexName, item.complexName) || sameComplex(row.complexName, item.housingComplexName))
    const source = named.length ? { names: [], certain: true } : sourceComplexNames(extraction, row.complexName, names, complexes), sourceNames = source.names
    const byComplexName = named.length ? named : current.filter(item => sourceNames.includes(item.complexName))
    // 공고 단지는 찾았지만 아직 단지에 연결되지 않았으면 다른 단지의 행을 모집수로 고르지 않는다.
    const byRecruitment = byComplexName.length || sourceNames.length ? [] : sameRecruitment(row, current)
    const related = byComplexName.length ? byComplexName : byRecruitment
    const complexIds = [...new Set(related.map(item => item.housingComplexId).filter(id => id !== null))]
    const complexId = complexIds.length === 1 ? complexIds[0] : null
    const allTypes = complexId === null ? [] : housing[complexId] ?? []
    const picked = pickHousingType(extraction, index, allTypes)
    const housingTypeId = picked.housingTypeId
    const sharedIndex = extraction.sharedCounts.findIndex(group => group.paths.includes(`supplyRows[${index}].totalSupplyHouseholdCount`))
    const ownCount = row.values.totalSupplyHouseholdCount
    const evidenceCount = sharedIndex >= 0 ? extraction.sharedCounts[sharedIndex].count : typeof ownCount === 'number' ? ownCount : null
    const { chosen, recommended, destination, ambiguous } = chooseRow(row, related, used, housingTypeId, true, labels, evidenceCount)
    if (chosen) used.add(chosen.key)
    const anchor = chosen ?? (complexId === null ? undefined : related[0])
    const changedName = !!chosen && typeKey(chosen.typeName) !== typeKey(row.typeName)
    const recruitmentNote = byRecruitment.length ? `단지명 표기가 달라 이번 모집수 ${String(row.values.totalSupplyHouseholdCount)}세대가 같은 기존 행을 제안했습니다. · ` : ''
    // 제안 근거에 덧붙여도 떼어 낼 수 있게(proposePlacements) 문장 안에 '·'를 쓰지 않는다.
    const ambiguousNote = ambiguous ? `모호: 표기와 전용면적이 맞는 기존 행이 없어 남은 행(${recommended.length}개)을 고르지 않았습니다. 고칠 기존 공급정보를 고르거나 새 공급정보로 추가하세요. · ` : ''
    const blockNote = !sourceNames.length ? '' : source.certain ? `공고 단지 '${sourceNames[0]}'에 대응했습니다. · `
      : `공고 단지 '${sourceNames[0]}'에 블록 번호나 주소 한쪽만 맞아 대응했습니다. 같은 단지인지 확인하세요. · `
    return {
      destination, anchorKey: anchor?.key ?? '', complexId, housingTypeId: housingTypeId ?? chosen?.housingTypeId ?? null, candidates: allTypes, recommended,
      reason: complexId === null ? sourceNames.length ? `공고 단지 '${sourceNames[0]}'가 아직 단지에 연결되지 않았습니다. 단지 정하기에서 정하세요.` : '단지를 하나로 특정하지 못했습니다.'
        : ambiguousNote + blockNote + recruitmentNote + picked.reason, ...(ambiguous ? { ambiguous } : {}),
      needsConfirmation: !chosen || changedName || housingTypeId === null || byRecruitment.length > 0 || !source.certain || sharedIndex >= 0 && chosen.groupId === null,
      sharedIndex: sharedIndex < 0 ? null : sharedIndex,
    }
  })
}

/** 접은 행의 모집수. 공고문이 접는 행끼리만 묶은 공통 모집수가 있으면 그 수, 행마다 모집수가 모두 있으면 합이다. */
export function foldedCount(extraction: Extraction, indexes: number[]) {
  const paths = indexes.map(index => `supplyRows[${index}].totalSupplyHouseholdCount`)
  const shared = extraction.sharedCounts.find(group => group.paths.length === paths.length && paths.every(path => group.paths.includes(path)))
  if (shared) return shared.count
  const counts = indexes.map(index => extraction.supplyRows[index].values.totalSupplyHouseholdCount)
  return counts.every(value => typeof value === 'number') ? (counts as number[]).reduce((sum, value) => sum + value, 0) : null
}

/**
 * 단지에 연결된 기존 LH 행 하나("26A/B(고령자)")를 공고문이 세부 타입 행 여럿(26A·26B)으로 적었으면, 그 행들을 그 LH 행 표기의 세부 타입 묶음 행 하나로 접는다.
 * 대상·금액이 행마다 다르면 접지 않고 행마다 둔다(저장할 위치를 확인하게 된다).
 * LH 행이 공급대상 묶음("26A/B(고령자)")이면 공고문 행의 대상이 모두 그 묶음일 때만 접는다. 같은 세부 타입을 가진 LH 행이 여럿인데
 * 묶음으로 나눌 수 없으면 어느 LH 행의 대상·모집수인지 알 수 없어 접지 않는다.
 */
export function foldSameSourceRows(extraction: Extraction, rows: CorrectionRow[], labels: SourceLabels): Extraction {
  const used = new Set<number>(), groups: FoldGroup[] = []
  const linked = rows.filter(row => !row.excluded && !emptyRow(row))
  const sameComplexRow = (pdf: Extraction['supplyRows'][number], row: CorrectionRow) => sameComplex(pdf.complexName, row.complexName) || sameComplex(pdf.complexName, row.housingComplexName)
  for (const row of linked) {
    const label = parseSourceLabel(sourceLabel(row, labels)), types = label.types.map(typeKey)
    if (types.length < 2) continue
    const indexes = extraction.supplyRows.flatMap((pdf, index) => !used.has(index) && !pdf.subtypes.length && types.includes(typeKey(pdf.typeName))
      && sameComplexRow(pdf, row) ? [index] : [])
    const siblings = linked.filter(other => other !== row && subtypeSetKey(other, labels) === subtypeSetKey(row, labels))
    const owned = label.group ? indexes.every(index => extraction.supplyRows[index].targets.every(target => inGroup(target.target, label.group!))) : !siblings.length
    if (indexes.length < 2 || !owned || !sameTargets(indexes.map(index => extraction.supplyRows[index]))) continue
    indexes.forEach(index => used.add(index))
    groups.push({ indexes, typeName: sourceLabel(row, labels), count: foldedCount(extraction, indexes) })
  }
  return groups.length ? foldRows(extraction, groups).extraction : extraction
}

/**
 * 단지에 연결된 LH 행이 같은 주택형을 공급대상 묶음마다 따로 두면("26A/B/C(고령자)"·"26A/B/C(주거급여수급자)") 공급행은 주택형 × 공급대상 묶음이다.
 * 공고문 행 하나의 대상이 두 묶음 이상에 들면 그 주택형의 공고문 행을 LH 원천행 맞추기와 같은 규칙(alignToSource)으로 LH 행마다 나눈다.
 * 나눈 행의 형명(typeName)은 LH 행 표기라 저장할 위치가 그 LH 행으로 정해진다. 저장하는 공고 표기는 공고문 표기 그대로다. 대상을 받지 못한 LH 행은 건드리지 않는다.
 * 나눈 뒤 대상·금액이 달라 LH 행 하나로 접을 수 없는 주택형은 나누지 않는다. notes는 나눈 행 번호별 안내다.
 */
export function splitSameSourceRows(extraction: Extraction, rows: CorrectionRow[], labels: SourceLabels) {
  const label = (row: CorrectionRow) => parseSourceLabel(sourceLabel(row, labels))
  const linked = rows.filter(row => !row.excluded && !emptyRow(row) && label(row).group !== null)
  const clusters = new Map<string, CorrectionRow[]>()
  for (const row of linked) {
    const key = subtypeSetKey(row, labels)
    clusters.set(key, [...clusters.get(key) ?? [], row])
  }
  type Match = SourceMatch & { cluster: number }
  const candidates = [...clusters.values()].filter(cluster => cluster.length > 1).flatMap((cluster, at): Match[] => {
    const types = label(cluster[0]).types.map(typeKey), groups = cluster.map(row => label(row).group)
    const indexes = extraction.supplyRows.flatMap((pdf, index) => cluster.some(row => sameComplex(pdf.complexName, row.complexName) || sameComplex(pdf.complexName, row.housingComplexName))
      && [pdf.typeName, ...pdf.subtypes.map(subtype => subtype.name)].some(name => types.includes(typeKey(name))) ? [index] : [])
    const owners = new Map(indexes.map(index => [index, new Set(targetOwners(extraction.supplyRows[index], groups))]))
    if (![...owners.values()].some(owner => owner.size > 1)) return []
    return cluster.map((row, order) => ({ cluster: at, group: groups[order], typeName: sourceLabel(row, labels), count: row.householdCount,
      rows: indexes.filter(index => owners.get(index)!.has(order)) })).filter(match => match.rows.length > 0)
  })
  const first = alignToSource(extraction, candidates)
  const unfoldable = new Set(first.split.map(match => match.cluster))
  const result = unfoldable.size ? alignToSource(extraction, candidates.filter(match => !unfoldable.has(match.cluster))) : first
  const notes = new Map(result.plans.flatMap((plan, index) => plan.match
    ? [[index, [`같은 주택형의 LH 행이 공급대상 묶음마다 있어 공고문 행의 대상을 묶음별로 나눠 LH 행 '${plan.match.typeName}'에 맞췄습니다. 모집수는 이 LH 행의 이번 공급 ${plan.match.count ?? '미확인'}세대입니다.`, matchNote(plan)].filter(Boolean).join(' ')] as const]
    : []))
  return { extraction: result.extraction, notes }
}

/**
 * 공고문이 행 모집수를 공통 모집수로 묶은 행들이 기존 공급행 하나와 새로 추가할 행뿐이고 대상·금액이 같으면, LH 한 행을 세부 타입으로 나눠 적은 것으로 보고 접을 묶음을 돌려준다.
 * 기존 행 표기로 세부 타입을 읽을 수 없는 경우(한스빌 '아파트' ↔ 공고문 26A·26B)다. 저장할 위치는 proposeRows가 고른 결과를 그대로 쓴다.
 */
export function sharedFoldGroups(extraction: Extraction, proposals: RowProposal[], rows: CorrectionRow[]): FoldGroup[] {
  return extraction.sharedCounts.flatMap((group, at) => {
    const indexes = proposals.flatMap((proposal, index) => proposal.sharedIndex === at ? [index] : [])
    const existing = indexes.filter(index => proposals[index].destination !== 'new')
    const destination = rows.find(row => row.key === proposals[existing[0]]?.destination)
    const complexIds = new Set(indexes.map(index => proposals[index].complexId))
    if (indexes.length < 2 || group.paths.length !== indexes.length || !group.paths.every(path => path.endsWith('.totalSupplyHouseholdCount'))
      || existing.length !== 1 || !destination || destination.groupId !== null || complexIds.size !== 1 || complexIds.has(null)
      || !sameTargets(indexes.map(index => extraction.supplyRows[index]))) return []
    return [{ indexes, typeName: indexes.flatMap(index => pdfTypes(extraction.supplyRows[index]).map(type => type.name)).join('/'), count: foldedCount(extraction, indexes) }]
  })
}

const day = (value: unknown) => typeof value === 'string' ? value.slice(0, 10) : ''
function samePeriod(left: JsonObject, right: JsonObject) {
  return !!day(left.startAt) && day(left.startAt) === day(right.startAt) && day(left.endAt ?? left.startAt) === day(right.endAt ?? right.startAt)
}
function overlaps(left: JsonObject, right: JsonObject) {
  return !!day(left.startAt) && !!day(right.startAt) && day(left.startAt) <= day(right.endAt ?? right.startAt)
    && day(right.startAt) <= day(left.endAt ?? left.startAt)
}

/** 같은 날짜의 시간 보정은 자동 제안하고, 기간이 겹치기만 하는 경우 사람이 확인한다. */
export function proposeSchedules(extraction: Extraction, events: JsonWorkspace['events']): ScheduleProposal[] {
  const used = new Set<number>()
  return extraction.schedules.map((schedule, index) => {
    if (!day(schedule.startAt)) return { existingIds: [], needsConfirmation: false, undated: true, reason: '날짜가 없는 안내라 일정으로 저장하지 않습니다.' }
    const sameType = events.filter(event => !used.has(event.id) && event.data.scheduleType === schedule.scheduleType)
    const exact = sameType.filter(event => samePeriod(event.data, schedule))
    const matches = exact.length ? exact : sameType.filter(event => overlaps(event.data, schedule))
    // 같은 단지에 후보가 둘 이상이면 임의로 기존 일정을 선택하지 않는다.
    const ambiguous = new Set(matches.map(event => event.complexName)).size !== matches.length
    const chosen = ambiguous ? [] : matches
    chosen.forEach(event => used.add(event.id))
    const evidence = evidenceFor(extraction.evidence, `schedules[${index}]`)
    const weakEvidence = !evidence || !/\d/.test(evidence.quote)
    const needsConfirmation = ambiguous || weakEvidence || matches.length > 0 && exact.length === 0
    return { existingIds: chosen.map(event => event.id), needsConfirmation,
      reason: ambiguous ? '같은 단지에 겹치는 일정이 여러 개입니다. 기존 일정 대응을 선택해 주세요.'
        : weakEvidence ? '근거 문구에 날짜·시각이 없습니다.' : matches.length > 0 && !exact.length
          ? '기존 일정과 기간이 일부 다릅니다.' : chosen.length > 1 ? `단지별 기존 일정 ${chosen.length}건에 반영` : '' }
  })
}

/** 이름이 유일하게 대응할 때만 기존 조건을 갱신한다. PDF 보정은 공급 순위를 가져오지 않아 순위로 가르지 않는다. 미저장 조건도 key로 대응한다. */
export function defaultTargetDestinations(input: JsonObject, row: CorrectionRow | undefined) {
  const normalize = (value: unknown) => typeof value === 'string' ? value.normalize('NFKC').replace(/\s+/g, '').toLowerCase() : ''
  const targets = Array.isArray(input.targets) ? input.targets : []
  return targets.map((value: unknown) => {
    if (!isRecord(value) || !normalize(value.target)) return ''
    if (!row) return 'new'
    const matches = row.targets.filter(target => !target.excluded && normalize(target.target) === normalize(value.target))
    if (matches.length === 1) return matches[0].id ? String(matches[0].id) : matches[0].key
    return matches.length === 0 ? 'new' : ''
  })
}

/** 통합행을 나눌 수 있는 LH 행. 공급행 하나(통합행)가 붙어 있고 아직 나눈 적이 없어야 한다. */
export function splittableLhRows(supply: Pick<SupplyWorkspace, 'lhSupplyRows' | 'rows'>): LhSupplyRow[] {
  return (supply.lhSupplyRows ?? []).filter(lh => lh.attachedRowKey && !lh.splitRowKeys?.length
    && supply.rows.some(row => row.key === lh.attachedRowKey && !row.excluded))
}
/**
 * 새 공급행을 연결할 수 있는 LH 행. 어떤 공급행도 붙거나 나뉘지 않은 행이다. 공고문 소유 공고는 작업실의 unattachedLhSupplyRows가
 * 비어 있지만 서버는 같은 기준(lhSupplyRows)으로 연결을 받으므로 이 목록으로 고른다.
 */
export function attachableLhRows(supply: Pick<SupplyWorkspace, 'lhSupplyRows'>): LhSupplyRow[] {
  return (supply.lhSupplyRows ?? []).filter(lh => lh.attachedRowKey == null && !lh.splitRowKeys?.length)
}
/** LH 행 표기. LH 목록 몇 번째 행(1부터) · 단지 · 주택형 · 면적 · 이번 공급 수 */
export function lhRowLabel(lh: LhSupplyRow) {
  const order = /:(\d+)$/.exec(lh.rowIdentifier)?.[1]
  return [`LH 행 ${order === undefined ? lh.rowIdentifier : Number(order) + 1}`, lh.complexLabel, lh.typeName, lh.exclusiveArea ? `전용 ${lh.exclusiveArea}㎡` : '면적 미확인',
    lh.householdCount != null ? `모집 ${lh.householdCount}` : '모집수 미확인'].filter(Boolean).join(' · ')
}
/** 운영자에게 보일 LH 행 이름. 주택형 표기와 이번 공급 수다('59.19B 5세대'). */
export function lhName(lh: LhSupplyRow) {
  return `${lh.typeName?.trim() || '표기 없음'} ${lh.householdCount != null ? `${lh.householdCount}세대` : '모집수 미확인'}`
}
const rowCount = (row: Extraction['supplyRows'][number]) => typeof row.values.totalSupplyHouseholdCount === 'number' ? row.values.totalSupplyHouseholdCount : null

/**
 * 분리 묶음의 저장 위치를 맞춘다. 통합행을 이어받는 첫 행(head)은 포함 행 중 PDF 순서로 첫 행이고 나머지는 새 행('new')이다.
 * 편집 순서와 관계없이 같은 묶음이면 같은 행이 통합행을 이어받는다. 모두 통합행을 기준 단지로 쓴다. 바뀐 행 번호를 돌려준다.
 */
export function assignSplitDestinations(proposals: RowProposal[], include: (index: number) => boolean, supply: Pick<SupplyWorkspace, 'lhSupplyRows'>): number[] {
  const changed: number[] = []
  for (const lh of supply.lhSupplyRows ?? []) {
    const merged = lh.attachedRowKey
    const members = proposals.flatMap((proposal, index) => proposal.saveMode === 'SPLIT' && proposal.lhRow === lh.rowIdentifier ? [index] : [])
    if (!merged || !members.length) continue
    const head = members.find(include)
    for (const index of members) {
      const destination = index === head ? merged : 'new'
      if (proposals[index].destination === destination && proposals[index].anchorKey === merged) continue
      proposals[index] = { ...proposals[index], destination, anchorKey: merged }
      changed.push(index)
    }
  }
  return changed
}

/**
 * LH 공급 원본 행에 맞춰 저장 방식을 미리 고른다. 추출 행의 lhSourceRow(AI 워커가 돌려준 LH 행 식별자)를 lhSupplyRows[].rowIdentifier로 찾고,
 * 이 공고의 LH 행에 없는 식별자나 식별자가 아닌 이전 결과(목록 번호)는 쓰지 않는다. 같은 LH 통합행을 가리키는 행이 둘 이상이면 통합행 분리,
 * 공급행이 없는 LH 행이면 LH 행에 연결로 둔다. lhSourceRow가 없으면, 근거 없이 같은 단지의 통합행 하나에 몰린 행들의 모집수 합이
 * 그 LH 행의 이번 공급 수와 같을 때만(240 = 200 + 25 + 15) 분리를 제안한다. 모두 운영자가 확인하게 한다.
 */
export function proposePlacements(extraction: Extraction, proposals: RowProposal[], supply: Pick<SupplyWorkspace, 'lhSupplyRows' | 'rows'>, housing: HousingCandidates): RowProposal[] {
  const next = [...proposals], lhRows = supply.lhSupplyRows ?? []
  const splittable = new Set(splittableLhRows(supply).map(lh => lh.rowIdentifier))
  const unattached = new Set(attachableLhRows(supply).map(lh => lh.rowIdentifier))
  const decided = new Set<number>()
  const join = (index: number, patch: Partial<RowProposal>, note: string) => {
    const reason = next[index].ambiguous ? next[index].reason.replace(/^모호: [^·]*· /, '') : next[index].reason
    next[index] = { ...next[index], ambiguous: false, ...patch, needsConfirmation: true, reason: `${note} · ${reason}` }
    decided.add(index)
  }
  const merged = (lh: LhSupplyRow) => supply.rows.find(row => row.key === lh.attachedRowKey)
  const typed = (index: number, row: CorrectionRow | undefined) => next[index].complexId !== null || !row?.housingComplexId ? {}
    : { complexId: row.housingComplexId, candidates: housing[row.housingComplexId] ?? [] }
  const pointed = new Map<string, number[]>()
  extraction.supplyRows.forEach((row, index) => {
    const identifier = row.values.lhSourceRow
    const lh = typeof identifier === 'string' ? lhRows.find(item => item.rowIdentifier === identifier) : undefined
    if (lh) pointed.set(lh.rowIdentifier, [...pointed.get(lh.rowIdentifier) ?? [], index])
  })
  for (const [identifier, indexes] of pointed) {
    const lh = lhRows.find(item => item.rowIdentifier === identifier)!, row = merged(lh), label = lhRowLabel(lh)
    if (splittable.has(identifier) && indexes.length > 1) {
      const counts = indexes.map(index => rowCount(extraction.supplyRows[index]))
      const sum = counts.every(count => count !== null) ? (counts as number[]).reduce((total, count) => total + count, 0) : null
      const check = sum === null ? '모집수 합계를 확인하세요' : sum === lh.householdCount ? `모집수 합 ${sum} = LH ${lh.householdCount}` : `모집수 합 ${sum}이 LH ${lh.householdCount ?? '미확인'}과 다릅니다`
      indexes.forEach(index => join(index, { saveMode: 'SPLIT', lhRow: identifier, proposedLhRow: identifier, ...typed(index, row) }, `AI가 ${label}을(를) 주택형별로 나눈 행으로 표시해 통합행 분리를 제안합니다(${check})`))
    } else if (unattached.has(identifier)) {
      join(indexes[0], { saveMode: 'ATTACH', lhRow: identifier, destination: 'new' }, `AI가 공급행이 없는 ${label}에서 나온 행으로 표시해 LH 행에 연결을 제안합니다`)
    } else if (lh.attachedRowKey && indexes.length === 1 && ['', 'new', lh.attachedRowKey].includes(next[indexes[0]].destination)) {
      join(indexes[0], { destination: lh.attachedRowKey, anchorKey: lh.attachedRowKey, ...typed(indexes[0], row) }, `AI가 ${label}에서 나온 행으로 표시했습니다`)
    }
  }
  for (const lh of lhRows.filter(item => splittable.has(item.rowIdentifier) && item.householdCount != null && !pointed.has(item.rowIdentifier))) {
    const row = merged(lh)!
    const members = next.flatMap((proposal, index) => !decided.has(index) && !proposal.saveMode && (proposal.destination === row.key
      || proposal.recommended.includes(row.key) && ['', 'new'].includes(proposal.destination)) ? [index] : [])
    const counts = members.map(index => rowCount(extraction.supplyRows[index]))
    if (members.length < 2 || counts.some(count => count === null) || (counts as number[]).reduce((total, count) => total + count, 0) !== lh.householdCount) continue
    members.forEach(index => join(index, { saveMode: 'SPLIT', lhRow: lh.rowIdentifier, proposedLhRow: lh.rowIdentifier }, `PDF 행 ${members.length}개의 모집수 합 ${(counts as number[]).join(' + ')} = ${lh.householdCount}이 ${lhRowLabel(lh)}(통합행 #${String(row.id)})과 같아 통합행 분리를 제안합니다`))
  }
  assignSplitDestinations(next, () => true, supply)
  return next
}
