import { display } from '../management/fields'
import { isRecord, type JsonValue } from '../management/managementContract'
import { SupplyCorrectionHistory } from '../supply/SupplyCorrectionHistory'
import { storedFieldLabel } from '../shared/storedFieldLabels'
import { choices, receptionFields, scheduleFields, shownDateTime } from './review'

type Change = { scope: string; label: string; before: unknown; after: unknown; added?: boolean }
const optionLabels = Object.fromEntries([...choices.method, ...choices.scheduleType])
function value(raw: unknown) {
  if (typeof raw === 'string' && optionLabels[raw]) return optionLabels[raw]
  if (typeof raw === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(raw)) return shownDateTime(raw)
  return display(raw as JsonValue | undefined)
}
function record(raw: unknown) { return isRecord(raw) ? raw : {} }
function differences(scope: string, specs: readonly (readonly [string, string, ...unknown[]])[], before: Record<string, unknown>, after: Record<string, unknown>): Change[] {
  return specs.filter(([field]) => JSON.stringify(before[field] ?? null) !== JSON.stringify(after[field] ?? null))
    .map(([field, label]) => ({ scope, label, before: before[field], after: after[field] }))
}

/** PDF 보정 한 건의 이력. 공급행은 공급정보 보정과 같은 표로, 기본정보·접수처·일정은 바뀐 값만 보여준다. */
export function PdfCorrectionHistory({ before, after }: { before: unknown; after: unknown }) {
  if (!isRecord(before) || !isRecord(after)) return <p>이 기록의 형식을 읽지 못했습니다.</p>
  const previous = record(record(before.detail).data), next = record(record(after.detail).data)
  const events = (raw: unknown) => Array.isArray(raw) ? raw.filter(isRecord) : []
  const oldEvents = events(before.events)
  const changes: Change[] = [
    // 기본정보는 값이 바뀐 단순 필드만 화면 이름으로 보여준다. 접수처는 아래에서 항목별로 나눈다.
    ...[...new Set([...Object.keys(previous), ...Object.keys(next)])].filter(field => !['version', 'receptionPlace', 'documentEvidence'].includes(field)
      && !isRecord(previous[field]) && !isRecord(next[field]) && !Array.isArray(previous[field]) && !Array.isArray(next[field])
      && JSON.stringify(previous[field] ?? null) !== JSON.stringify(next[field] ?? null))
      .map(field => ({ scope: '기본정보', label: storedFieldLabel(field), before: previous[field], after: next[field] })),
    ...differences('접수처', receptionFields, record(previous.receptionPlace), record(next.receptionPlace)),
    ...events(after.events).flatMap(event => {
      const old = oldEvents.find(item => item.id === event.id)
      const scope = `일정 #${String(event.id)}${typeof event.complexName === 'string' ? ` · ${event.complexName}` : ''}${old ? '' : ' (추가)'}`
      return differences(scope, scheduleFields, record(old?.data), record(event.data)).map(change => ({ ...change, added: !old }))
    }),
  ]
  return <>
    <SupplyCorrectionHistory before={before} after={after} />
    <section aria-label="저장한 기본정보·일정 보정">
      <h3>기본정보·접수처·일정 {changes.length}개</h3>
      {changes.length > 0 && <div className="admin-table-scroll"><table className="admin-table"><thead><tr><th scope="col">대상</th><th scope="col">항목</th><th scope="col">변경 전</th><th scope="col">변경 후</th></tr></thead>
        <tbody>{changes.map((change, index) => <tr key={index}><th scope="row">{change.scope}</th><td>{change.label}</td><td>{change.added ? '없음' : value(change.before)}</td><td>{value(change.after)}</td></tr>)}</tbody></table></div>}
    </section>
  </>
}
