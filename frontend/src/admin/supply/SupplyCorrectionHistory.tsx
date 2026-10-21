import { isRecord } from '../management/managementContract'
import { StoredDataTable } from '../shared/StoredDataTable'
import { parseSupplyPreview } from './supplyCorrectionApi'
import { SupplyCorrectionPreview } from './SupplyCorrectionPreview'

export function SupplyCorrectionHistory({ before, after }: { before: unknown; after: unknown }) {
  if (!isRecord(before) || !isRecord(after)) return <p>이 기록의 형식을 읽지 못했습니다.</p>
  let comparison
  try { comparison = parseSupplyPreview({ confirmationToken: 'history', before: before.rows, after: after.rows, warnings: [] }) }
  catch { return <p>이 기록의 공급행 형식을 읽지 못했습니다.</p> }
  return <section aria-label="저장한 공급정보 보정">
    <p>이유: {typeof after.reason === 'string' ? after.reason : '기록 없음'}</p>
    {typeof after.sourceUrl === 'string' && /^https?:\/\//.test(after.sourceUrl) && <p><a href={after.sourceUrl} target="_blank" rel="noreferrer">공식 근거 ↗</a>{typeof after.sourcePage === 'string' ? ` · ${after.sourcePage}` : ''}</p>}
    <SupplyCorrectionPreview value={comparison} historical />
    {Array.isArray(after.sources) && <details><summary>보정 당시 원천과 작업 번호</summary>
      <StoredDataTable label="보정 작업" data={{ operationId: after.operationId, sources: after.sources }} readable />
    </details>}
  </section>
}
