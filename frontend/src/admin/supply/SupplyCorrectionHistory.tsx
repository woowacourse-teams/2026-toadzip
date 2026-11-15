import { isRecord } from '../management/managementContract'
import { StoredDataTable } from '../shared/StoredDataTable'
import { parseSupplyPreview } from './supplyCorrectionApi'
import { SupplyCorrectionPreview } from './SupplyCorrectionPreview'

/**
 * 공급정보 보정 한 건의 이력. 변경 전후의 공급표(sheet)를 공급정보 편집과 같은 표로 비교한다.
 * 공급표 이전에 공급행(rows)으로 저장한 기록은 같은 표로 비교할 수 없어 저장값을 그대로 펼쳐 보여 준다.
 */
export function SupplyCorrectionHistory({ before, after }: { before: unknown; after: unknown }) {
  if (!isRecord(before) || !isRecord(after)) return <p>이 기록의 형식을 읽지 못했습니다.</p>
  const evidence = <>
    <p>이유: {typeof after.reason === 'string' ? after.reason : '기록 없음'}</p>
    {typeof after.sourceUrl === 'string' && /^https?:\/\//.test(after.sourceUrl) && <p><a href={after.sourceUrl} target="_blank" rel="noreferrer">공식 근거 ↗</a>{typeof after.sourcePage === 'string' ? ` · ${after.sourcePage}` : ''}</p>}
  </>
  if (Array.isArray(before.rows) || Array.isArray(after.rows)) return <section aria-label="저장한 공급정보 보정">
    {evidence}
    <p>공급표 이전 형식(공급행)으로 저장한 기록이라 바뀐 값만 골라 보여 주지 못합니다.</p>
    <details><summary>저장 당시 공급행 전체</summary>
      <StoredDataTable label="변경 전 공급행" data={before.rows} readable />
      <StoredDataTable label="변경 후 공급행" data={after.rows} readable />
    </details>
  </section>
  let comparison
  try { comparison = parseSupplyPreview({ confirmationToken: 'history', before: before.sheet, after: after.sheet, warnings: [] }) }
  catch { return <p>이 기록의 공급표 형식을 읽지 못했습니다.</p> }
  return <section aria-label="저장한 공급정보 보정">
    {evidence}
    <SupplyCorrectionPreview value={comparison} historical />
    {typeof after.operationId === 'string' && <details><summary>보정 작업 번호</summary>
      <StoredDataTable label="보정 작업" data={{ operationId: after.operationId }} readable />
    </details>}
  </section>
}
