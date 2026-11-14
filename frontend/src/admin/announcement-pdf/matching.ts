import type { JsonObject, JsonWorkspace } from '../announcement-json/api'
import type { MatchingHousingType } from '../ingest/supplyMatchingApi'
import type { AnnouncementComplex } from '../supply/supplyCorrectionApi'
import { sameTypeName } from './areas'
import { evidenceFor, text, type Extraction } from './extraction'

export type HousingCandidates = Record<number, MatchingHousingType[]>
/** 단지 ID별 표시 이름(단지명 · 임대유형) */
export type ComplexLabels = Record<number, string>
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
