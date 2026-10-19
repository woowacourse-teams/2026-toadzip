import { getManagementPage } from '../management/api'
import { isRecord, type ManagementSummary } from '../management/managementContract'

export type OfficialComplex = { name: string; roadAddress: string; sourceIdentifier?: string }
export type OfficialComplexList = { sourceUrl: string; asOf: string; agencyCode: string; complexes: OfficialComplex[] }
export type CoverageResult = { entry: OfficialComplex; status: 'MATCHED' | 'MISSING' | 'REVIEW' | 'DELETED' | 'ERROR'; candidates: ManagementSummary[]; reason: string }
const normalize = (text: string) => text.normalize('NFKC').replace(/\s+/g, '').toLowerCase()
const text = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0 && value.length <= 200

export function parseOfficialList(input: string, maximum = 50): OfficialComplexList {
  if (new TextEncoder().encode(input).length > 1000000) throw new Error('공식 목록 JSON은 1MB 이하여야 합니다.')
  let value: unknown
  try { value = JSON.parse(input.replace(/^\uFEFF/, '')) as unknown } catch { throw new Error('공식 목록 JSON 문법을 확인해 주세요.') }
  if (!isRecord(value) || typeof value.sourceUrl !== 'string' || !safeDocumentUrl(value.sourceUrl)
    || typeof value.asOf !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value.asOf) || Number.isNaN(Date.parse(value.asOf))
    || new Date(value.asOf).toISOString().slice(0, 10) !== value.asOf
    || !['LH', 'SH', 'GH', 'ETC'].includes(String(value.agencyCode))
    || !Array.isArray(value.complexes) || !value.complexes.length || value.complexes.length > maximum) {
    throw new Error(`공식 URL·기준일·기관과 1~${maximum}개 단지 목록을 입력해 주세요.`)
  }
  const seen = new Set<string>()
  const complexes = value.complexes.map(entry => {
    if (!isRecord(entry) || !text(entry.name) || typeof entry.roadAddress !== 'string' || entry.roadAddress.length > 200
      || (entry.sourceIdentifier !== undefined && !text(entry.sourceIdentifier))) {
      throw new Error('각 단지의 이름을 입력하고 도로명주소 형식을 확인해 주세요. 미제공 주소는 빈칸으로 둡니다.')
    }
    const key = `${normalize(entry.name)}:${normalize(entry.roadAddress)}:${entry.sourceIdentifier ?? ''}`
    if (entry.roadAddress.trim() && seen.has(key)) throw new Error('중복 단지를 제거하고 다시 입력해 주세요.')
    seen.add(key)
    return { name: entry.name.trim(), roadAddress: entry.roadAddress.trim(),
      ...(typeof entry.sourceIdentifier === 'string' ? { sourceIdentifier: entry.sourceIdentifier.trim() } : {}) }
  })
  return { sourceUrl: value.sourceUrl, asOf: value.asOf, agencyCode: String(value.agencyCode), complexes }
}

async function candidates(list: OfficialComplexList, entry: OfficialComplex, deleted: boolean, signal: AbortSignal): Promise<ManagementSummary[]> {
  const queries = [...new Set([entry.sourceIdentifier, entry.roadAddress, entry.name].filter((value): value is string => Boolean(value?.trim())))]
  const found = new Map<number, ManagementSummary>()
  for (const keyword of queries) {
    for (let page = 0; page < 10; page++) {
      signal.throwIfAborted()
      const result = await getManagementPage('complexes', new URLSearchParams({ keyword, provider: list.agencyCode,
        deleted: String(deleted), size: '100', page: String(page) }), signal)
      result.items.forEach(item => found.set(item.id, item))
      if (!result.hasNext) break
      if (page === 9) throw new Error('검색 결과가 많아 전체 대조를 마치지 못했습니다. 더 구체적인 이름·주소로 확인하세요.')
    }
  }
  return [...found.values()]
}

function matches(entry: OfficialComplex, candidate: ManagementSummary): boolean {
  if (entry.sourceIdentifier) return normalize(candidate.complex?.sourceIdentifier ?? '') === normalize(entry.sourceIdentifier)
  return Boolean(entry.roadAddress.trim()) && normalize(entry.name) === normalize(candidate.name) && normalize(entry.roadAddress) === normalize(candidate.subtitle)
}

export async function compareOfficialComplex(list: OfficialComplexList, entry: OfficialComplex, signal: AbortSignal): Promise<CoverageResult> {
  try {
    const live = await candidates(list, entry, false, signal)
    const exact = live.filter(candidate => matches(entry, candidate))
    if (exact.length === 1) return { entry, status: 'MATCHED', candidates: exact, reason: '이름·주소 또는 원천 식별자가 일치합니다. 공개 화면의 노출도 확인하세요.' }
    if (live.length) return { entry, status: 'REVIEW', candidates: live, reason: '동일 주소·이름의 후보를 직접 확인하세요. 자동 연결하지 않습니다.' }
    const deleted = await candidates(list, entry, true, signal)
    if (deleted.length) return { entry, status: 'DELETED', candidates: deleted, reason: '휴지통 후보가 있습니다. 삭제 사유와 동일성을 확인한 뒤 복구 여부를 결정하세요.' }
    if (!entry.roadAddress.trim()) return { entry, status: 'REVIEW', candidates: [], reason: '도로명주소가 없어 등록 여부를 확정할 수 없습니다. 공식 원문에서 주소를 확인하고 다시 대조하세요.' }
    return { entry, status: 'MISSING', candidates: [], reason: '검색된 등록 단지가 없습니다. 수집 실패·주소 변경·다른 이름을 확인한 뒤 신규 등록하세요.' }
  } catch (cause) {
    if (signal.aborted) throw signal.reason
    return { entry, status: 'ERROR', candidates: [], reason: cause instanceof Error ? cause.message : '조회하지 못했습니다.' }
  }
}

function safeDocumentUrl(value: string): string | null {
  if (!value.trim() || value.length > 2048 || /\s/.test(value)) return null
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : null }
  catch { return null }
}
