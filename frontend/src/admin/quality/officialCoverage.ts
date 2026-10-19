import { getSourceData, type SourceCategory, type SourceRow } from '../ingest/sourceApi'
import { parseOfficialList, type OfficialComplexList } from '../documents/complexCoverage'
export type OfficialCoverage = { list: OfficialComplexList; rows: number; skipped: number; duplicates: number }
const agencies: Record<string, string> = { 한국토지주택공사: 'LH', 서울주택도시공사: 'SH', 서울주택도시개발공사: 'SH', SH공사: 'SH', 경기주택도시공사: 'GH' }
const normalized = (value: string) => value.normalize('NFKC').replace(/\s+/g, '').toLowerCase()
export function prepareOfficialCoverage(category: SourceCategory, provider: string, rows: SourceRow[]): OfficialCoverage {
  const unique = new Map<string, { name: string; roadAddress: string }>()
  let skipped = 0; let duplicates = 0; let sourceUrl = ''; let asOf = ''
  for (const row of rows) {
    const agency = category === 'MYHOME_COMPLEX' ? agencies[String(row.raw.instt_nm)] ?? row.raw.instt_nm : 'LH'
    if (agency !== provider) continue
    const name = category === 'MYHOME_COMPLEX' ? row.raw.hsmp_nm : category === 'LH_LEASE_CATALOG' ? row.raw.complex_label : row.raw.complex_name
    const rawAddress = category === 'MYHOME_COMPLEX' ? row.raw.rn_adres : row.raw.address
    const address = typeof rawAddress === 'string' && (category === 'MYHOME_COMPLEX' || /[로길]\s+\d/.test(rawAddress)) ? rawAddress.trim() : ''
    if (row.raw.active === false || typeof name !== 'string' || !name.trim() || !row.collectedAt) {
      skipped++; continue
    }
    const key = `${normalized(name)}:${normalized(address) || `unlocated:${row.id}:${row.sourceKey}`}`
    if (unique.has(key)) duplicates++
    unique.set(key, { name: name.trim(), roadAddress: address.trim() })
    sourceUrl ||= row.sourceUrl
    asOf = [asOf, row.collectedAt.slice(0, 10)].sort().at(-1) ?? ''
  }
  if (!unique.size) throw new Error('이 기관에서 이름·수집 시각을 확인할 수 있는 자료가 없습니다.')
  const list = parseOfficialList(JSON.stringify({ sourceUrl, asOf, agencyCode: provider, complexes: [...unique.values()] }), 5000)
  return { list, rows: rows.length, skipped, duplicates }
}
export async function loadOfficialCoverage(category: SourceCategory, provider: string, keyword: string,
  signal: AbortSignal, progress: (message: string) => void): Promise<OfficialCoverage> {
  async function scan(verify: boolean): Promise<SourceRow[]> {
    const rows: SourceRow[] = []; const keys = new Set<string>(); let expected: number | undefined
    for (let number = 0; number < 250; number++) {
      signal.throwIfAborted()
      const page = await getSourceData(category, keyword, number, signal)
      signal.throwIfAborted()
      expected ??= page.totalElements
      if (page.page !== number || page.totalElements !== expected || (page.hasNext && !page.items.length)) {
        throw new Error('조회 중 공식 저장 자료가 변경되었습니다. 수집 완료 후 다시 가져오세요.')
      }
      for (const row of page.items) {
        const key = `${row.id}:${row.sourceKey}`
        if (keys.has(key)) throw new Error('페이지의 원천이 반복되거나 변경되었습니다. 다시 가져오세요.')
        keys.add(key); rows.push(row)
      }
      progress(`${verify ? '변경 여부 확인' : '전체 자료 조회'} ${number + 1}쪽 · ${rows.length}/${expected}행`)
      if (!page.hasNext) {
        if (rows.length !== expected) throw new Error('전체 행 수가 변경되었습니다. 다시 가져오세요.')
        return rows
      }
    }
    throw new Error('전체 조회 한도 250쪽을 초과했습니다. 검색어로 범위를 나누어 대조하세요.')
  }
  const rows = await scan(false)
  if (JSON.stringify(rows) !== JSON.stringify(await scan(true))) {
    throw new Error('조회 중 공식 저장 자료가 변경되었습니다. 다시 가져오세요.')
  }
  return prepareOfficialCoverage(category, provider, rows)
}
