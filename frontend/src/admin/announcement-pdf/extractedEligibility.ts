import { isRecord } from '../management/managementContract'
import { readExtractionDocument, type Evidence } from './extraction'

export type ExtractedRequirement = { kind: string; text: string; evidence: Evidence | null }
export type ExtractedGroup = { title: string; requirements: ExtractedRequirement[]; qualifications: string[] }
/** PDF 추출 결과에 든 신청자격을 보정 적용 전에 그대로 보이기 위한 모양. 판정 조건으로 바꾸는 일은 서버가 초안을 만들 때 한다. */
export type ExtractedEligibility = { groups: ExtractedGroup[]; exclusions: { title: string; body: string }[] }

const text = (value: unknown) => typeof value === 'string' ? value.trim() : ''
const records = (value: unknown) => Array.isArray(value) ? value.filter(isRecord) : []

/** 추출 결과에 신청자격이 없으면(이전 워커, 신청자격 단계 실패) null이다. */
export function readExtractedEligibility(source: string): ExtractedEligibility | null {
  const document = readExtractionDocument(source)
  if (!document.ok || !isRecord(document.value.data) || !isRecord(document.value.data.eligibility)) return null
  const eligibility = document.value.data.eligibility
  const evidence = new Map(records(document.value.evidence).flatMap(item => {
    const path = text(item.path)
    return path && typeof item.page === 'number' ? [[path, { path, page: item.page, quote: text(item.quote) }] as const] : []
  }))
  const group = (title: string, value: unknown, path: string): ExtractedGroup => {
    const item = isRecord(value) ? value : {}
    return {
      title,
      requirements: records(item.requirements).map((requirement, index) => ({
        kind: text(requirement.kind), text: text(requirement.asWritten), evidence: evidence.get(`${path}.requirements[${index}]`) ?? null,
      })).filter(requirement => requirement.text),
      qualifications: records(item.qualifications).map(qualification => text(qualification.exactName)).filter(Boolean),
    }
  }
  return {
    groups: [group('모든 신청자에게 적용', eligibility.common, 'eligibility.common'),
      ...records(eligibility.targets).map((target, index) => group(text(target.target) || `신청 대상 ${index + 1}`, target, `eligibility.targets[${index}]`))],
    exclusions: records(eligibility.exclusions).map(item => ({ title: text(item.title), body: text(item.body) })).filter(item => item.title),
  }
}
