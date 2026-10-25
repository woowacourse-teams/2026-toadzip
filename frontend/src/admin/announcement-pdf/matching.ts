import { isRecord } from '../management/managementContract'
import type { JsonObject, JsonWorkspace } from '../announcement-json/api'
import type { MatchingHousingType } from '../ingest/supplyMatchingApi'
import type { CorrectionRow } from '../supply/supplyCorrectionApi'
import { evidenceFor, noteFor, text, type Extraction } from './extraction'

export type HousingCandidates = Record<number, MatchingHousingType[]>
/** 단지 ID별 표시 이름(단지명 · 임대유형) */
export type ComplexLabels = Record<number, string>
export type RowProposal = {
  destination: string
  anchorKey: string
  complexId: number | null
  housingTypeId: number | null
  candidates: MatchingHousingType[]
  reason: string
  needsConfirmation: boolean
  sharedIndex: number | null
  newComplex?: { id: number; name: string; pnu: string; category: CorrectionRow['category'] }
}
export type ScheduleProposal = { existingIds: number[]; needsConfirmation: boolean; reason: string }

export function normalizedName(value: unknown) {
  return (text(value) ?? '').normalize('NFKC').toLowerCase().replace(/형|[\s·,]/g, '')
}
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
export type AnnouncementCheck = { label: '공고명' | '공고일'; pdf: string | null; current: string | null; matches: boolean | null }
/** 붙여넣은 응답이 지금 공고의 PDF에서 나온 것인지 공고명·공고일로 확인한다. 어느 한쪽 값이 없으면 판단하지 않는다. */
export function checkAnnouncement(extraction: Extraction, current: JsonObject): AnnouncementCheck[] {
  const pdfName = text(extraction.announcement.name), currentName = text(current.name)
  const pdfDate = text(extraction.announcement.postedDate), currentDate = text(current.postedDate)
  return [
    { label: '공고명', pdf: pdfName, current: currentName, matches: titleKey(pdfName) && titleKey(currentName) ? sameTitle(pdfName, currentName) : null },
    { label: '공고일', pdf: pdfDate, current: currentDate, matches: pdfDate && currentDate ? pdfDate.slice(0, 10) === currentDate.slice(0, 10) : null },
  ]
}
export function sameComplex(left: unknown, right: unknown) {
  const a = normalizedName(left), b = normalizedName(right)
  return !!a && !!b && (a.includes(b) || b.includes(a))
}
function equalArea(left: unknown, right: unknown) {
  return typeof left === 'number' && typeof right === 'number' && Math.abs(left - right) < 0.00005
}

/** PDF 행의 전용면적 → 건설호수 → 공급면적 → 이름 순서로 단지 주택형 후보를 좁힌다. */
export function pickHousingType(extraction: Extraction, index: number, allTypes: MatchingHousingType[]) {
  const row = extraction.supplyRows[index]
  let candidates = allTypes.filter(type => equalArea(type.exclusiveArea, row.values.exclusiveArea))
  if (!candidates.length && row.values.exclusiveArea == null) {
    candidates = allTypes.filter(type => normalizedName(type.name) === normalizedName(row.typeName))
  }
  let reason = candidates.length ? `전용면적 ${String(row.values.exclusiveArea ?? '')}㎡`
    : allTypes.length && typeof row.values.exclusiveArea === 'number' ? `이 단지에 PDF 전용 ${row.values.exclusiveArea}㎡와 맞는 주택형이 없습니다.` : '전용면적에 맞는 주택형을 확인해 주세요.'
  if (candidates.length > 1 && typeof row.values.constructedHouseholdCount === 'number') {
    const byCount = candidates.filter(type => type.householdCount === row.values.constructedHouseholdCount)
    if (byCount.length) { candidates = byCount; reason += ` · 건설호수 ${row.values.constructedHouseholdCount}세대` }
  }
  if (candidates.length > 1 && !noteFor(extraction.unresolved, `supplyRows[${index}].supplyArea`)) {
    const byArea = candidates.filter(type => equalArea(type.supplyArea, row.values.supplyArea))
    if (byArea.length) { candidates = byArea; reason += ' · 공급면적 일치' }
  }
  if (candidates.length > 1) {
    const byName = candidates.filter(type => normalizedName(type.name) === normalizedName(row.typeName))
    if (byName.length) candidates = byName
  }
  const housingTypeId = candidates.length === 1 ? candidates[0].id : null
  return { housingTypeId, reason: housingTypeId === null && candidates.length ? `${reason} · 주택형을 하나로 특정하지 못했습니다.` : reason }
}

/**
 * 단지는 정했지만 그 단지에 PDF 전용면적과 같은 주택형이 없으면 PDF 형명·면적을 돌려준다.
 * 이때만 'PDF 형명·면적으로 새 주택형 추가'를 고를 수 있다.
 */
/** 단지에 연결되지 않은 기존 공급행(최초 입주자 공고처럼 등록 단지가 없던 원천행). */
export function emptyRow(row: CorrectionRow) { return !row.excluded && row.housingComplexId === null && row.groupId === null }

export function missingHousingType(complexId: number | null, candidates: MatchingHousingType[], typeName: string, exclusiveArea: unknown, supplyArea: unknown) {
  if (complexId === null || typeof exclusiveArea !== 'number' || candidates.some(type => equalArea(type.exclusiveArea, exclusiveArea))) return null
  return { name: typeName.trim(), exclusiveArea, supplyArea: typeof supplyArea === 'number' ? supplyArea : null }
}

// 공고문과 수집 원천의 단지명 표기가 다를 때(전주평화1단지 ↔ 평화주공1단지) 이번 모집수가 유일하게 같은 기존 행을 제안한다.
function sameRecruitment(row: Extraction['supplyRows'][number], current: CorrectionRow[]) {
  const count = row.values.totalSupplyHouseholdCount
  if (typeof count !== 'number') return []
  const matched = current.filter(item => !item.excluded && item.groupId === null && item.householdCount === count)
  return matched.length === 1 ? matched : []
}

export function proposeRows(extraction: Extraction, all: CorrectionRow[], housing: HousingCandidates): RowProposal[] {
  const used = new Set<string>()
  // 단지가 연결되지 않은 기존 행은 단지를 정한 뒤 LH 원천행 맞추기(sourceAlignment)에서 연결·분할한다. 관리자가 직접 고를 수는 있다.
  const current = all.filter(item => !emptyRow(item))
  return extraction.supplyRows.map((row, index) => {
    const byComplexName = current.filter(item => !item.excluded && (sameComplex(row.complexName, item.complexName)
      || sameComplex(row.complexName, item.housingComplexName)))
    const byRecruitment = byComplexName.length ? [] : sameRecruitment(row, current)
    const related = byComplexName.length ? byComplexName : byRecruitment
    const complexIds = [...new Set(related.map(item => item.housingComplexId).filter(id => id !== null))]
    const complexId = complexIds.length === 1 ? complexIds[0] : null
    const allTypes = complexId === null ? [] : housing[complexId] ?? []
    const picked = pickHousingType(extraction, index, allTypes)
    const housingTypeId = picked.housingTypeId
    const available = related.filter(item => !used.has(item.key))
    const byName = available.filter(item => normalizedName(item.typeName) === normalizedName(row.typeName))
    const byArea = available.filter(item => equalArea(item.exclusiveArea, row.values.exclusiveArea)
      || housingTypeId !== null && item.housingTypeId === housingTypeId)
    const sharedIndex = extraction.sharedCounts.findIndex(group => group.paths.includes(`supplyRows[${index}].totalSupplyHouseholdCount`))
    const chosen = byName.length === 1 ? byName[0] : byArea.length === 1 ? byArea[0] : available.length === 1 ? available[0] : undefined
    if (chosen) used.add(chosen.key)
    const anchor = chosen ?? (complexId === null ? undefined : related[0])
    const destination = chosen?.key ?? (!current.length || anchor && available.length === 0 ? 'new' : '')
    const changedName = !!chosen && normalizedName(chosen.typeName) !== normalizedName(row.typeName)
    const recruitmentNote = byRecruitment.length ? `단지명 표기가 달라 이번 모집수 ${String(row.values.totalSupplyHouseholdCount)}세대가 같은 기존 행을 제안했습니다. · ` : ''
    return {
      destination, anchorKey: anchor?.key ?? '', complexId, housingTypeId: housingTypeId ?? chosen?.housingTypeId ?? null, candidates: allTypes,
      reason: complexId === null ? '단지를 하나로 특정하지 못했습니다.' : recruitmentNote + picked.reason,
      needsConfirmation: !chosen || changedName || housingTypeId === null || byRecruitment.length > 0 || sharedIndex >= 0 && chosen.groupId === null,
      sharedIndex: sharedIndex < 0 ? null : sharedIndex,
    }
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

/** 이름·순위가 유일하게 대응할 때만 기존 조건을 갱신한다. 미저장 조건도 key로 대응한다. */
export function defaultTargetDestinations(input: JsonObject, row: CorrectionRow | undefined) {
  const normalize = (value: unknown) => typeof value === 'string' ? value.normalize('NFKC').replace(/\s+/g, '').toLowerCase() : ''
  const targets = Array.isArray(input.targets) ? input.targets : []
  return targets.map((value: unknown) => {
    if (!isRecord(value) || !normalize(value.target)) return ''
    if (!row) return 'new'
    const matches = row.targets.filter(target => !target.excluded && normalize(target.target) === normalize(value.target)
      && normalize(target.supplyRank) === normalize(value.supplyRank))
    if (matches.length === 1) return matches[0].id ? String(matches[0].id) : matches[0].key
    return matches.length === 0 ? 'new' : ''
  })
}
