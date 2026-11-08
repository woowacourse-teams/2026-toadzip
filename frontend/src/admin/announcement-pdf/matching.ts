import { isRecord } from '../management/managementContract'
import type { JsonObject, JsonWorkspace } from '../announcement-json/api'
import type { MatchingHousingType } from '../ingest/supplyMatchingApi'
import type { AnnouncementComplex, CorrectionRow, LhSupplyRow, SupplyWorkspace } from '../supply/supplyCorrectionApi'
import { announcementHousingType, areaKey, sameArea, sameTypeName, type AnnouncementType } from './areas'
import { evidenceFor, text, type ExtractedRow, type Extraction } from './extraction'
import { sameApplicationType } from '../supply/applicationType'
import { createdTypeName, pdfTypes } from './subtypes'

export type HousingCandidates = Record<number, MatchingHousingType[]>
/** 단지 ID별 표시 이름(단지명 · 임대유형) */
export type ComplexLabels = Record<number, string>
export type RowProposal = {
  /** 고칠 기존 공급행 키, 또는 새 공급행이면 'new' */
  destination: string
  /** 새 공급행이 단지·소재지·공급 구분을 이어받을 같은 단지의 기존 공급행 키. 없으면 '' */
  anchorKey: string
  complexId: number | null
  candidates: MatchingHousingType[]
  reason: string
  needsConfirmation: boolean
  sharedIndex: number | null
  /**
   * 저장할 곳과 같은 주택형(신청형)이고 같은 주택타입(전용면적)을 가리키는 같은 공고 단지의 다른 기존 공급행. 신청자 구분마다 나뉘어
   * 저장된 옛 공급행이라 저장하면 제외하고, 관리자가 추가해 지울 수 있는 행이면 지운다. 한 단지에서 공급행은 신청형마다 하나다.
   */
  supersedes?: string[]
  /** 단지 생성은 성공했지만 주택형 생성·공급행 연결을 마치지 못한 상태. 초안에도 보존한다. */
  createdComplex?: { id: number; name: string }
  newComplex?: { id: number; name: string; pnu: string; category: CorrectionRow['category'] }
  /** 관리자가 '바꾸기'로 저장할 곳을 고르기 전의 자동 제안. '추천' 표시와 추천으로 되돌리기에 쓴다. */
  automatic?: Omit<RowProposal, 'automatic'>
}
/** undated는 시작일이 없는 안내(계약 개별안내 등)라 일정으로 저장하지 않는다는 뜻이다. */
export type ScheduleProposal = { existingIds: number[]; needsConfirmation: boolean; reason: string; undated?: boolean }

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
 * certain은 이름이나 블록 번호가 맞았는지다. 주소만 맞았으면 화면에서 대응을 확인하게 한다.
 */
export function sourceComplexNames(extraction: Extraction, pdfName: string | null, names: string[], complexes: Pick<AnnouncementComplex, 'name' | 'roadAddress'>[] = []) {
  const byName = names.filter(name => sameComplex(pdfName, name))
  if (byName.length) return { names: byName, certain: true }
  const block = blockKey(pdfName), address = addressKey(extraction.complexes.find(item => item.name === pdfName)?.address)
  const byBlock = block ? names.filter(name => blockKey(name) === block) : []
  const byAddress = address ? names.filter(name => addressKey(complexes.find(item => item.name === name)?.roadAddress) === address) : []
  const matched = byBlock.length && byAddress.length ? byBlock.filter(name => byAddress.includes(name)) : byBlock.length ? byBlock : byAddress
  // 블록 번호(A40)는 한 공고 안에서 단지를 가르는 번호라, 공고 단지 하나에만 맞으면 주소 표기가 달라도(지번·도로명) 그 단지다.
  // 주소만 맞은 경우는 같은 주소에 단지가 여럿일 수 있어 확인하게 한다.
  // 블록 번호를 뗀 PDF 이름의 나머지(동탄2)가 공고 단지 이름에도 있어야 한다. 여러 지구 공고에서 다른 지구의 같은 블록 번호에 붙지 않게.
  const district = normalizedName((text(pdfName) ?? '').replace(/(?<![a-z])[a-z]-?\d{1,3}(?:bl|블록|블럭)?/gi, ''))
  const sameDistrict = matched.length === 1 && (!district || normalizedName(matched[0]).includes(district))
  return matched.length === 1 ? { names: matched, certain: byBlock.length > 0 && sameDistrict } : { names: [], certain: false }
}
export function normalizedName(value: unknown) {
  return (text(value) ?? '').normalize('NFKC').toLowerCase().replace(/형|[\s·,]/g, '')
}

/**
 * 이 공고의 같은 PDF 단지에서 다른 주택타입이 쓰는 공고문 이름(다른 행의 주택타입, 같은 행의 다른 세부 타입). 그 이름의 단지 주택타입은
 * 이 행·세부 타입이 면적만 같다고 가져가지 않는다(announcementHousingType). subtype은 이 행의 세부 타입 번호이고, 없으면 행 전체다.
 */
export function otherTypeNames(extraction: Pick<Extraction, 'supplyRows'>, index: number, subtype?: number): AnnouncementType[] {
  const complex = (row: ExtractedRow) => row.complexName?.trim() ?? ''
  const row = extraction.supplyRows[index], own = createdTypeName(pdfTypes(row)[subtype ?? 0]?.name ?? row.typeName ?? '')
  return extraction.supplyRows.flatMap((other, at) => complex(other) !== complex(row) ? []
    : pdfTypes(other).filter((_, item) => at !== index || subtype !== undefined && item !== subtype)
      .map(type => ({ name: createdTypeName(type.name), exclusiveArea: type.exclusiveArea })))
    // 같은 주택타입을 신청자마다 나눠 적은 행(접지 않은 경우)은 다른 주택타입이 아니다.
    .filter(type => type.name && !sameTypeName(type.name, own))
}
/** 세부 타입 묶음 행의 세부 타입마다 연결할 단지 주택형(announcementHousingType). 없으면 새로 만든다. 고를 수 있는 후보는 같은 면적이나 같은 이름의 주택형이다. */
export function pickSubtypes(extraction: Extraction, index: number, allTypes: MatchingHousingType[]) {
  return extraction.supplyRows[index].subtypes.map((type, at) => ({ housingTypeId: announcementHousingType(allTypes, createdTypeName(type.name), type.exclusiveArea, otherTypeNames(extraction, index, at))?.id ?? null,
    options: allTypes.filter(item => sameArea(item.exclusiveArea, type.exclusiveArea) || sameTypeName(item.name, createdTypeName(type.name), item.exclusiveArea, type.exclusiveArea)) }))
}

/** 단지에 연결되지 않은 기존 공급행(최초 입주자 공고처럼 등록 단지가 없던 원천행). */
export function emptyRow(row: CorrectionRow) { return !row.excluded && row.housingComplexId === null && row.groupId === null }

/** 서버의 주택형 면적 형식(0 이상, 정수 6자리·소수 4자리까지) */
export const areaValue = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 999999.9999
  && Math.abs(value * 10000 - Math.round(value * 10000)) < .00001
/**
 * 단지는 정했지만 그 단지에 이 이름의 주택타입이 없으면 새로 만들 형명·면적을 돌려준다. 단지 주택타입은 공고문 표기 이름으로 나누므로
 * 같은 면적 주택타입이 있어도 이름이 다르면 만든다(서버 checkNewTypes와 같은 기준).
 */
export function missingHousingType(complexId: number | null, candidates: MatchingHousingType[], typeName: string, exclusiveArea: unknown, supplyArea: unknown) {
  // 새 주택형의 전용면적은 0보다 커야 한다. 공급면적은 형식이 맞지 않으면 비워 둔다.
  if (complexId === null || !typeName.trim() || !areaValue(exclusiveArea) || exclusiveArea === 0 || candidates.some(type => sameTypeName(type.name, typeName, type.exclusiveArea, exclusiveArea))) return null
  return { name: typeName.trim(), exclusiveArea, supplyArea: areaValue(supplyArea) ? supplyArea : null }
}

/** PDF 행이 가리키는 전용면적 키(세부 타입 포함) */
export function pdfAreaKeys(row: ExtractedRow) {
  return new Set(pdfTypes(row).map(type => areaKey(type.exclusiveArea)).filter(key => key !== null))
}
/** 기존 공급행이 가리키는 전용면적 키. 공급행 면적, 연결 주택형, 함께 모집하는 주택형이다. types는 그 단지의 주택형이다. */
export function rowAreaKeys(row: CorrectionRow, types: MatchingHousingType[]) {
  const area = (id: number | null | undefined) => types.find(type => type.id === id)?.exclusiveArea
  const values = [row.exclusiveArea, area(row.housingTypeId), row.newHousingType?.exclusiveArea,
    ...(row.linkedHousingTypes ?? []).map(link => link.housingTypeId != null ? area(link.housingTypeId) : link.newHousingType?.exclusiveArea)]
  return new Set(values.map(areaKey).filter(key => key !== null))
}
/**
 * 저장할 곳 고르기에 쓰는 기존 공급행. 고르기에만 쓰고 저장 값은 바꾸지 않는다.
 * - 이름은 서버가 준 행의 신청형 이름(names: 공고 표기, 없으면 원천 형명)이다. 작업실 행 이름은 공고 표기가 없으면 연결 주택타입 이름이라
 *   (84.9600A·84.9600B가 모두 84A) 신청형을 가리지 못한다. 서버의 같은 신청형 판정과 같은 이름이다.
 * - 단지에 연결되지 않은 LH 원천 행은 주택형이 없어 전용면적이 비어 오므로, 그 행에 붙은 LH 줄의 전용면적을 그 행의 면적으로 본다.
 */
export function matchingRows(rows: CorrectionRow[], lhRows: LhSupplyRow[] = [], names: Record<string, string> = {}) {
  return rows.map(row => {
    const typeName = names[row.key] ?? row.typeName
    const area = row.exclusiveArea ?? lhRows.map(lh => lh.attachedRowKey === row.key ? Number(lh.exclusiveArea) : NaN).find(value => Number.isFinite(value) && value > 0) ?? null
    return area === row.exclusiveArea && typeName === row.typeName ? row : { ...row, exclusiveArea: area, typeName }
  })
}
const byDisplayOrder = (left: CorrectionRow, right: CorrectionRow) => left.displayOrder - right.displayOrder || (left.key < right.key ? -1 : left.key > right.key ? 1 : 0)

/** 기존 공급행이 PDF 행과 같은 단지 주택타입(전용면적, 소수 둘째 자리)을 가리키는지. 저장할 곳 후보를 좁힌다. */
function sameHousingType(row: ExtractedRow, item: CorrectionRow, types: MatchingHousingType[]) {
  const areas = pdfAreaKeys(row)
  return [...rowAreaKeys(item, types)].some(key => areas.has(key))
}
/** 같은 PDF 단지의 다른 PDF 행. 저장할 곳을 고를 때 그 행들이 신청형으로 가리키는 기존 행을 남겨 두려고 넘긴다(chooseDestination). */
export function otherPdfRows(extraction: Pick<Extraction, 'supplyRows'>, index: number) {
  const complex = (row: ExtractedRow) => row.complexName?.trim() ?? ''
  return extraction.supplyRows.filter((other, at) => at !== index && complex(other) === complex(extraction.supplyRows[index]))
}
/**
 * 같은 공고 단지의 기존 공급행 중 이 PDF 행을 저장할 행. 전용면적이 겹치는 행 중 신청형이 같은 행, 없으면 같은 PDF 단지의 다른 PDF 행
 * (others)이 신청형으로 가리키지 않는 행 중 표시 순서상 첫 행이다. 신청형이 같은 나머지 행은 면적이 조금 달라도(59.92 ↔ 59.93) 신청자
 * 구분마다 나뉘었거나 면적만 다르게 저장된 옛 공급행이라 저장하면 제외한다(supersedes). 서버가 한 단지에 같은 신청형 행을 하나만 두기 때문이다.
 * 신청형이 다른 같은 면적 행(55A1 ↔ 55C)은 제외하지 않는다. 다른 PDF 행이 고른 행(used)은 고르지 않는다. related는 matchingRows를 거친 행이다.
 * 옛 공통 모집 묶음 행도 겹치면 제외하고, 묶음 전체가 이 행에 합쳐지면 저장할 행이 묶음에서 빠진다(leavesGroup). 겹치는 행이 없으면 마이홈 '아파트' 통합행 하나만 모집수로 이어받고,
 * 그 밖에는 새 공급행이다.
 */
export function chooseDestination(row: ExtractedRow, related: CorrectionRow[], used: Set<string>, housing: HousingCandidates, count: number | null,
  others: ExtractedRow[]) {
  const available = related.filter(item => !item.excluded && !used.has(item.key)).sort(byDisplayOrder)
  // LH 공급 API는 참고만 하므로 면적이 조금 달라(59.92 ↔ 59.93) 겹치지 않는 행은 신청형이 같아도 고르지 않고 새 공급행을 추천한다.
  const overlapping = available.filter(item => sameHousingType(row, item, housing[item.housingComplexId ?? 0] ?? []))
  const legacyAggregate = !overlapping.length && available.length === 1 && normalizedName(available[0].typeName) === '아파트'
    && count !== null && available[0].householdCount === count
  const named = overlapping.filter(item => sameApplicationType(row.typeName, item.typeName))
  const reserved = (item: CorrectionRow) => others.some(other => sameApplicationType(other.typeName, item.typeName))
  const chosen = named[0] ?? overlapping.find(item => !reserved(item)) ?? (legacyAggregate ? available[0] : undefined)
  const superseded = available.filter(item => item !== chosen && sameApplicationType(row.typeName, item.typeName))
  return { chosen, legacy: !overlapping.length && legacyAggregate, superseded }
}
/**
 * 워커가 지구명·주택형으로 맞춘 GH 공급행 틀 행. GH는 청약구분·신청자마다 행을 나눠 두므로, 같은 전용면적이라 한 공급행으로 접힌
 * PDF 행들의 틀 행 중 첫 행을 고치고 나머지는 이 공급행에 합친다(supersedes). 제외했거나 다른 PDF 행이 이미 고른 틀 행은 쓰지 않는다.
 */
export function ghFrameRows(row: Pick<ExtractedRow, 'values'>, supply: Pick<SupplyWorkspace, 'rows' | 'rowSources'>, used: ReadonlySet<string>) {
  const frames = new Map((supply.rowSources ?? []).filter(link => link.category === 'GH_ANNOUNCEMENT').map(link => [link.sourceKey, link.rowKey]))
  return sourceRowsOf(row).flatMap(id => supply.rows.filter(item => item.key === frames.get(id) && !item.excluded && !used.has(item.key)))
}
export function ghFrameReason(frame: CorrectionRow, merged: CorrectionRow[], rows: CorrectionRow[]) {
  return [`GH 공급정보에서 지구·주택형이 같은 '${describeRow(frame)}'을 고칩니다.`, supersedeNote(rows, merged.map(item => item.key))].filter(Boolean).join(' ')
}
/**
 * 관리자가 '바꾸기'에서 고른 저장할 곳(기존 공급행 키 또는 'new')으로 제안을 만든다. 추천을 고르면 자동 제안으로 되돌린다.
 * 서버는 한 단지에 주택형(신청형)마다 공급행 하나만 두므로, 같은 단지에서 이 PDF 행과 신청형이 같은 다른 기존 행은 함께 제외한다
 * (supersedes). 다른 PDF 행이 고르거나 제외할 행(used)은 건드리지 않는다. rows는 matchingRows를 거친 행이다.
 */
export function chosenProposal(row: ExtractedRow, proposal: RowProposal, key: string, rows: CorrectionRow[], used: ReadonlySet<string>, housing: HousingCandidates): RowProposal {
  const automatic = proposal.automatic ?? proposal
  if (key === automatic.destination) {
    // 그 사이 다른 PDF 행이 고른 옛 행은 함께 제외하지 않는다(저장도 제외하지 않는다).
    const kept = automatic.supersedes?.filter(item => !used.has(item)) ?? []
    const reason = automatic.reason.replace(supersedeNote(rows, automatic.supersedes), supersedeNote(rows, kept)).trim()
    return { ...automatic, reason, supersedes: kept.length ? kept : undefined, automatic: undefined }
  }
  const picked = key === 'new' ? undefined : rows.find(item => item.key === key)
  // 단지·기준 행은 지금 제안이 아니라 자동 제안에서 이어받는다. 다른 단지 행을 거쳐 새로 추가로 바꿔도 새 행이 그 단지에 생기지 않게 한다.
  const anchor = picked ?? rows.find(item => item.key === automatic.anchorKey)
  const complexId = picked?.housingComplexId ?? automatic.complexId
  const types = complexId === null ? [] : housing[complexId] ?? []
  const superseded = complexId === null ? [] : rows.filter(item => item.key !== key && !item.excluded && !used.has(item.key) && item.housingComplexId === complexId
    && sameApplicationType(row.typeName, item.typeName)).map(item => item.key)
  const reason = [picked ? `관리자가 고른 기존 공급정보 '${describeRow(picked)}'을 고칩니다.` : '관리자가 새 공급정보로 추가하기로 했습니다.', supersedeNote(rows, superseded)].filter(Boolean).join(' ')
  return { ...proposal, destination: key, anchorKey: anchor?.key ?? '', complexId, candidates: picked?.housingComplexId != null || types.length ? types : automatic.candidates, reason, needsConfirmation: false,
    supersedes: superseded.length ? superseded : undefined, newComplex: picked?.housingComplexId != null ? undefined : automatic.newComplex, automatic }
}
/** 다른 PDF 행이 고르거나 제외할 기존 공급행 */
export function takenRows(proposals: Pick<RowProposal, 'destination' | 'supersedes'>[]) {
  return new Set(proposals.flatMap(proposal => [proposal.destination, ...proposal.supersedes ?? []]))
}
/** 저장할 곳과 함께 제외할 옛 공급행의 안내 */
const originLabels: Record<string, string> = { LH_ANNOUNCEMENT_SUPPLY: 'LH 공고에서 만든 행', MYHOME_ANNOUNCEMENT: '마이홈 공고에서 만든 행', GH_ANNOUNCEMENT: 'GH 공고에서 만든 행', SH_ANNOUNCEMENT: 'SH 공고에서 만든 행' }
/**
 * 기존 공급행을 관리자가 알아볼 수 있게 단지·표시 이름·전용면적·모집수·만든 곳으로 적는다. 번호는 맨 뒤에 둔다.
 * 만든 곳은 supply(행 원천·지울 수 있는 행)를 줄 때만 적는다.
 */
export function describeRow(row: CorrectionRow, supply?: Pick<SupplyWorkspace, 'rowSources' | 'deletableRowKeys'>) {
  const count = row.householdCount != null ? `모집 ${row.householdCount}세대` : row.groupId !== null ? '공통 모집' : '모집수 미확인'
  const area = row.exclusiveArea != null ? `전용 ${row.exclusiveArea}㎡` : '면적 미확인'
  const category = supply?.rowSources?.find(source => source.rowKey === row.key)?.category
  const origin = category ? originLabels[category] : supply?.deletableRowKeys?.includes(row.key) ? '관리자가 추가한 행' : undefined
  return [`${row.housingComplexName ?? row.complexName} ${row.typeName}`, area, count, origin, `#${String(row.id)}`].filter(Boolean).join(' · ')
}
export function supersedeNote(rows: CorrectionRow[], keys: string[] = []) {
  const names = keys.flatMap(key => rows.filter(row => row.key === key)).map(row => `'${describeRow(row)}'`)
  return names.length ? `같은 단지에서 같은 주택형(신청형)을 가리키는 기존 공급행 ${names.join(', ')}은 이 공급행에 합쳐지므로 저장하면 제외합니다(직접 추가한 행은 지웁니다). 한 단지에는 신청형마다 공급행 하나만 둡니다.` : ''
}
const areaText = (row: ExtractedRow) => {
  const areas = pdfTypes(row).map(type => type.exclusiveArea).filter(area => area !== null)
  return areas.length ? `전용 ${areas.join('·')}㎡` : '전용면적 미확인'
}
/** 저장할 곳을 고른 근거 */
export function destinationReason(row: ExtractedRow, chosen: CorrectionRow | undefined, legacy = false) {
  if (!chosen) return `${areaText(row)}와 같은 기존 공급정보가 없어 새로 추가합니다.`
  return legacy ? `기존 통합 공급정보 '${describeRow(chosen)}'의 모집수가 같아 그 행을 고칩니다.`
    : `${sameApplicationType(row.typeName, chosen.typeName) ? '주택형(신청형)과 ' : ''}${areaText(row)}가 같은 기존 공급정보 '${describeRow(chosen)}'을 고칩니다.`
}

/**
 * PDF 행마다 저장할 곳을 정한다. GH 공급행 틀에서 나온 행(sourceRow)은 그 틀 행, 아니면 같은 공고 단지에서 전용면적이 겹치는
 * 기존 공급행 중 신청형이 같은 행(chooseDestination), 없으면 새 공급행이다. 단지는 PDF 단지명으로, 이름이 다르면 블록 번호·주소로 찾는다.
 * LH 공급 원본 행은 공급행을 정하지 않는다. 주택형은 저장할 때 서버가 단지·전용면적으로 정하므로 고르게 하지 않는다.
 */
export function proposeRows(extraction: Extraction, supply: Pick<SupplyWorkspace, 'rows' | 'rowSources' | 'applicationTypeNames'> & { announcementComplexes?: Pick<AnnouncementComplex, 'name' | 'roadAddress'>[]; lhSupplyRows?: LhSupplyRow[] }, housing: HousingCandidates): RowProposal[] {
  const all = matchingRows(supply.rows, supply.lhSupplyRows, supply.applicationTypeNames), proposals: RowProposal[] = []
  // 단지가 연결되지 않은 기존 행은 단지를 정한 뒤 그 단지의 행으로 다시 맞춘다(proposeInComplex).
  const current = all.filter(item => !emptyRow(item) && !item.excluded)
  const names = [...new Set(all.filter(item => !item.excluded).map(item => item.complexName))]
  extraction.supplyRows.forEach((row, index) => {
    const used = takenRows(proposals)
    const named = current.filter(item => sameComplex(row.complexName, item.complexName) || sameComplex(row.complexName, item.housingComplexName))
    // 공급 표에 단지명 열이 없어 PDF 단지명이 비면(사천벌리1) 공고 단지가 하나뿐일 때 그 단지로 본다. 대조할 이름이 없어 고를 여지도 없다.
    const onlyComplex = !row.complexName?.trim() && names.length === 1
    const source = named.length ? { names: [], certain: true } : onlyComplex ? { names, certain: true }
      : sourceComplexNames(extraction, row.complexName, names, supply.announcementComplexes), sourceNames = source.names
    const related = named.length ? named : current.filter(item => sourceNames.includes(item.complexName))
    const complexIds = [...new Set(related.map(item => item.housingComplexId).filter(id => id !== null))]
    const complexId = complexIds.length === 1 ? complexIds[0] : null
    const candidates = complexId === null ? [] : housing[complexId] ?? []
    const sharedIndex = extraction.sharedCounts.findIndex(group => group.paths.includes(`supplyRows[${index}].totalSupplyHouseholdCount`))
    const ownCount = row.values.totalSupplyHouseholdCount
    const count = sharedIndex >= 0 ? extraction.sharedCounts[sharedIndex].count : typeof ownCount === 'number' ? ownCount : null
    // 워커가 지구명·주택형으로 틀 행 하나에 하나만 맞춘 GH 행이다. GH는 청약구분·신청자마다 행을 나눠 두므로, 같은 전용면적이라
    // 한 공급행으로 접힌 PDF 행들의 틀 행 중 첫 행을 고치고 나머지는 이 공급행에 합친다. 다른 PDF 행이 이미 고른 틀 행은 따르지 않는다.
    // 틀 행과 PDF 단지명이 같은 단지를 가리키면 워커가 지구·주택형으로 맞춘 것이라 따로 확인을 받지 않는다.
    const frames = ghFrameRows(row, supply, used)
    if (frames.length) {
      const [frame, ...merged] = frames
      proposals.push({ destination: frame.key, anchorKey: frame.key, complexId: frame.housingComplexId ?? complexId, candidates: housing[frame.housingComplexId ?? 0] ?? candidates,
        reason: ghFrameReason(frame, merged, all),
        needsConfirmation: !sameComplex(row.complexName, frame.complexName), sharedIndex: sharedIndex < 0 ? null : sharedIndex,
        ...(merged.length ? { supersedes: merged.map(item => item.key) } : {}) })
      return
    }
    const { chosen, legacy, superseded } = complexId === null ? { chosen: undefined, legacy: false, superseded: [] }
      : chooseDestination(row, related, used, housing, count, otherPdfRows(extraction, index))
    const blockNote = !sourceNames.length ? '' : source.certain ? `공고 단지 '${sourceNames[0]}'에 대응했습니다. `
      : `공고 단지 '${sourceNames[0]}'에 블록 번호나 주소 한쪽만 맞아 대응했습니다. 같은 단지인지 확인하세요. `
    proposals.push({ destination: chosen?.key ?? 'new', anchorKey: chosen?.key ?? (complexId === null ? '' : related[0]?.key ?? ''), complexId, candidates,
      reason: complexId === null ? sourceNames.length ? `공고 단지 '${sourceNames[0]}'가 아직 단지에 연결되지 않았습니다. 단지 정하기에서 정하세요.` : '단지를 하나로 특정하지 못했습니다.'
        : [blockNote + destinationReason(row, chosen, legacy), supersedeNote(all, superseded.map(item => item.key))].filter(Boolean).join(' '),
      needsConfirmation: !source.certain, sharedIndex: sharedIndex < 0 ? null : sharedIndex, ...(superseded.length ? { supersedes: superseded.map(item => item.key) } : {}) })
  })
  return proposals
}

/**
 * 단지에 연결되지 않은 LH·마이홈 원천행 중 이 공고문 단지명이 가리키는 공고 단지의 행. 블록 표기가 달라도 대응한다(sourceComplexNames).
 * 공급 표에 단지명이 없어 PDF 단지명이 비면 공고 단지가 하나뿐일 때 그 단지의 행이다(proposeRows와 같은 기준).
 */
export function emptySourceRows(workspace: JsonWorkspace, extraction: Extraction, complexName: string) {
  const all = [...new Set(workspace.supply.rows.filter(row => !row.excluded).map(row => row.complexName))]
  const names = !complexName.trim() && all.length === 1 ? all : sourceComplexNames(extraction, complexName, all, workspace.supply.announcementComplexes).names
  return workspace.supply.rows.filter(row => row.id !== null && !row.excluded && row.housingComplexId === null && row.groupId === null
    && names.includes(row.complexName))
}

/**
 * 공급행 하나에 붙은 LH 공급 원본 행(서버가 같은 공고 단지·같은 전용면적으로 계산한 attachedRowKey)과 그 이번 공급 수 합.
 * 세대수 검산과 식별에만 쓰는 정보다. LH 줄 수나 합계가 PDF와 달라도 저장을 막지 않는다.
 */
export function lhCrossCheck(lhRows: LhSupplyRow[], keys: string[], count: number | null) {
  const attached = lhRows.filter(lh => lh.attachedRowKey != null && keys.includes(lh.attachedRowKey))
  if (!attached.length) return null
  const counts = attached.map(lh => lh.householdCount)
  const sum = counts.every(value => typeof value === 'number') ? (counts as number[]).reduce((total, value) => total + value, 0) : null
  // LH는 예비자만 모집하는 공고 등에서 이번 공급 수를 0으로 준다. 0과 비교한 차이는 검산이 아니라 소음이다.
  const difference = sum === null ? 'LH 모집수를 모르는 줄이 있어 합계를 비교하지 않습니다.'
    : sum === 0 ? 'LH가 이번 공급 수를 0으로 줘 비교하지 않습니다.'
    : count === null ? '' : sum === count ? 'PDF 모집수와 같습니다.' : `PDF 모집수 ${count}세대와 ${Math.abs(sum - count)}세대 다릅니다. 공고문과 대조하세요(저장은 막지 않습니다).`
  return { rows: attached, sum, text: [`LH ${attached.length}줄 합 ${sum ?? '미확인'}세대`, difference,
    attached.some(lh => lh.ambiguous) ? '같은 면적 공급행이 여럿이라 첫 행에 붙인 LH 줄이 있습니다.' : ''].filter(Boolean).join(' · ') }
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

/**
 * 이름이 유일하게 대응할 때만 기존 조건을 갱신한다. PDF 보정은 공급 순위를 가져오지 않아 순위로 가르지 않는다. 미저장 조건도 key로 대응한다.
 * 접은 행처럼 같은 이름의 PDF 대상이 둘이면 앞 대상이 기존 조건을 고치고 뒤 대상은 새 조건으로 더한다.
 */
const income = /^(.*?)[\s(]*소득\s*\(?\s*(無|有|없음|있음|무|유|×|✕|[xX]|○|◯|[oO])\s*\)?\s*\)?$/
/**
 * 소득 유무로 나눈 공급대상 이름을 `청년(소득 없음)`·`청년(소득 있음)`으로 맞춘다. AI 워커(supply_assembly.canonical_target)와 같은 규칙이다.
 * 예전에 저장된 `청년 소득(×)`·`청년(소득無)`도 같은 대상으로 잇기 위해 화면도 같은 규칙으로 비교한다.
 */
export function canonicalTarget(name: string) {
  // 가운뎃점은 공고마다 다른 문자(·․‧・･ㆍ)와 띄어쓰기로 적어(신혼부부․ 한부모가족) 붙여 쓴 ·로 맞춘다.
  // 영구임대 대상 표기도 공고마다 「가」군·가군으로 달라 낫표를 뗀다.
  const compact = name.trim().replace(/\s*[·․‧・･ㆍ]\s*/g, '·').replace(/[「」『』]/g, '')
  const match = income.exec(compact)
  if (!match) return compact
  const label = ['無', '없음', '무', '×', '✕', 'x', 'X'].includes(match[2]) ? '소득 없음' : '소득 있음'
  const base = match[1].trim()
  return base ? `${base}(${label})` : label
}

export function defaultTargetDestinations(input: JsonObject, row: CorrectionRow | undefined) {
  const normalize = (value: unknown) => typeof value === 'string' ? canonicalTarget(value).normalize('NFKC').replace(/\s+/g, '').toLowerCase() : ''
  // 공고문이 대상 설명 괄호를 달거나 빼도(가군 ↔ 가군 (생계·의료급여수급자 등)) 같은 대상이다. 괄호를 뗀 이름이 양쪽에서 하나씩일 때만 잇는다.
  const core = (value: unknown) => normalize(value).replace(/\(.*$/, '')
  const targets = Array.isArray(input.targets) ? input.targets : [], used = new Set<string>()
  const pdfCores = targets.map(value => isRecord(value) ? core(value.target) : '')
  return targets.map((value: unknown, at: number) => {
    if (!isRecord(value) || !normalize(value.target)) return ''
    if (!row) return 'new'
    const exact = row.targets.filter(target => !target.excluded && normalize(target.target) === normalize(value.target))
    const byCore = row.targets.filter(target => !target.excluded && core(target.target) === pdfCores[at])
    const matches = exact.length || byCore.length !== 1 || pdfCores.filter(item => item === pdfCores[at]).length !== 1 ? exact : byCore
    const destination = matches.length === 1 ? matches[0].id ? String(matches[0].id) : matches[0].key : matches.length === 0 ? 'new' : ''
    if (destination && destination !== 'new' && used.has(destination)) return 'new'
    used.add(destination)
    return destination
  })
}

/** 추출 행이 나온 원천 행의 식별자(AI 워커의 sourceRow). 식별자가 아닌 이전 결과(목록 번호)는 쓰지 않는다. */
export function sourceRowOf(row: Pick<Extraction['supplyRows'][number], 'values'>): string | undefined {
  const value = row.values.sourceRow
  return typeof value === 'string' ? value : undefined
}
/** 접은 행이면 접힌 행마다의 원천 행 식별자(sourceRows), 아니면 sourceRow 하나 */
export function sourceRowsOf(row: Pick<Extraction['supplyRows'][number], 'values'>): string[] {
  const listed = Array.isArray(row.values.sourceRows) ? row.values.sourceRows.filter((value): value is string => typeof value === 'string') : []
  const first = sourceRowOf(row)
  return [...new Set([...first ? [first] : [], ...listed])]
}
