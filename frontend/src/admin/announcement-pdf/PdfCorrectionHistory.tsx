import { display } from '../management/fields'
import { isRecord, type JsonValue } from '../management/managementContract'
import { SupplyCorrectionHistory } from '../supply/SupplyCorrectionHistory'
import { choices, metadataFields, receptionFields, scheduleFields } from './review'

type Change = { scope: string; label: string; before: unknown; after: unknown }
const optionLabels = Object.fromEntries([...choices.method, ...choices.scheduleType])
function value(raw: unknown) {
  return typeof raw === 'string' && optionLabels[raw] ? optionLabels[raw] : display(raw as JsonValue | undefined)
}
function record(raw: unknown) { return isRecord(raw) ? raw : {} }
function differences(scope: string, specs: readonly (readonly [string, string, ...unknown[]])[], before: Record<string, unknown>, after: Record<string, unknown>): Change[] {
  return specs.filter(([field]) => JSON.stringify(before[field] ?? null) !== JSON.stringify(after[field] ?? null))
    .map(([field, label]) => ({ scope, label, before: before[field], after: after[field] }))
}

/** PDF 보정 한 건의 이력. 공급행은 공급정보 보정과 같은 표로, 기본정보·접수처·일정은 바뀐 값만 보여준다. */
export function PdfCorrectionHistory({ before, after }: { before: unknown; after: unknown }) {
  if (!isRecord(before) || !isRecord(after)) return <p>상세 형식을 확인할 수 없습니다. 전체 기록을 확인하세요.</p>
  const previous = record(record(before.detail).data), next = record(record(after.detail).data)
  const events = (raw: unknown) => Array.isArray(raw) ? raw.filter(isRecord) : []
  const oldEvents = events(before.events)
  const changes = [
    ...differences('기본정보', metadataFields, previous, next),
    ...differences('접수처', receptionFields, record(previous.receptionPlace), record(next.receptionPlace)),
    ...events(after.events).flatMap(event => {
      const old = oldEvents.find(item => item.id === event.id)
      const scope = `일정 #${String(event.id)}${typeof event.complexName === 'string' ? ` · ${event.complexName}` : ''}${old ? '' : ' (추가)'}`
      return differences(scope, scheduleFields, record(old?.data), record(event.data))
    }),
  ]
  return <>
    <SupplyCorrectionHistory before={before} after={after} />
    <section aria-label="저장한 기본정보·일정 보정">
      <h3>기본정보·접수처·일정 {changes.length}개</h3>
      {changes.length > 0 && <div className="admin-table-scroll"><table className="admin-table"><thead><tr><th scope="col">대상</th><th scope="col">항목</th><th scope="col">변경 전</th><th scope="col">변경 후</th></tr></thead>
        <tbody>{changes.map((change, index) => <tr key={index}><th scope="row">{change.scope}</th><td>{change.label}</td><td>{value(change.before)}</td><td>{value(change.after)}</td></tr>)}</tbody></table></div>}
    </section>
  </>
}
