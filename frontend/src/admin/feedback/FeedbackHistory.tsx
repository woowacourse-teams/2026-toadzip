import { useEffect, useState } from 'react'
import type { ManagementChange } from '../management/managementContract'
import { feedbackStates, formatShortTime, getFeedbackChanges, relatedResources } from './api'
import styles from './FeedbackListPage.module.css'

function snapshot(value: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(value)
    return typeof parsed === 'object' && parsed !== null ? parsed as Record<string, unknown> : null
  } catch { return null }
}

// 지금은 처리 저장이 관련 대상을 바꾸지 않지만, 운영자가 직접 연결하던 때의 기록은 그대로 풀어 보인다.
function relation(value: Record<string, unknown>) {
  const resource = String(value.relatedResource ?? '')
  return Object.hasOwn(relatedResources, resource) && value.relatedId
    ? `${relatedResources[resource as keyof typeof relatedResources]} #${String(value.relatedId)}` : '없음'
}

/** 저장 전후 값을 견줘 바뀐 항목만 말로 풀어 쓴다. 읽을 수 없는 기록은 저장 사실만 남긴다. */
function describe(change: ManagementChange) {
  const before = snapshot(change.beforeValue); const after = snapshot(change.afterValue)
  if (!before || !after) return '처리 기록 저장'
  const parts: string[] = []
  if (before.status !== after.status) {
    const name = (value: unknown) => feedbackStates[value as keyof typeof feedbackStates] ?? String(value)
    parts.push(`상태 ${name(before.status)} → ${name(after.status)}`)
  }
  if (relation(before) !== relation(after)) parts.push(`관련 ${relation(before)} → ${relation(after)}`)
  if (before.note !== after.note) parts.push('메모 수정')
  return parts.length ? parts.join(', ') : '처리 기록 저장'
}

export function FeedbackHistory({ id, version }: { id: number; version: number }) {
  const [loaded, setLoaded] = useState<{ key: string; items: ManagementChange[] } | null>(null)
  const [failure, setFailure] = useState<{ key: string; message: string } | null>(null)
  const [attempt, setAttempt] = useState(0)
  const key = `${id}:${version}:${attempt}`
  useEffect(() => {
    const controller = new AbortController()
    getFeedbackChanges(id, controller.signal).then(items => {
      if (!controller.signal.aborted) setLoaded({ key, items })
    }).catch(cause => {
      if (controller.signal.aborted) return
      setFailure({ key, message: cause instanceof Error && !(cause instanceof TypeError)
        ? cause.message : '처리 이력을 불러오지 못했습니다.' })
    })
    return () => controller.abort()
  }, [id, key])
  const items = loaded?.key === key ? loaded.items : null
  const failed = failure?.key === key ? failure.message : ''
  return <section className={styles.history} aria-labelledby={`feedback-history-${id}`}>
    <h3 id={`feedback-history-${id}`}>처리 이력</h3>
    {failed ? <div className="admin-alert" role="alert"><p>{failed}</p>
      <button type="button" onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button></div> : null}
    {!failed && !items ? <p>처리 이력을 불러오는 중…</p> : null}
    {items?.length === 0 ? <p>아직 처리 기록이 없습니다.</p> : null}
    {items && items.length > 0 ? <ol>{items.map(item => <li key={item.id}>
      <time dateTime={item.occurredAt}>{formatShortTime(item.occurredAt)}</time>
      <span>{describe(item)} · {item.actor}</span></li>)}</ol> : null}
  </section>
}
