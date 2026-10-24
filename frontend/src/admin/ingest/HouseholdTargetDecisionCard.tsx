import { useEffect, useRef, useState } from 'react'
import { labels } from '../management/fields'
import { getHouseholdTargetDecision, releaseHouseholdTargetDecision, saveHouseholdTargetDecision,
  type DecisionComplex, type DecisionSourceGroup, type HouseholdTargetDecisionView } from './householdTargetDecisionApi'
import styles from './HouseholdComplexLinkCard.module.css'

const SKIP = 'SKIP'

/**
 * 같은 단지에 매칭된 LH 묶음들을 단지 주택형별로 나란히 보여 준다. 각 열은 그 묶음을 고르면 주택형에 들어갈 세대수다.
 * 관리자는 한 묶음을 쓰거나 세대수 보강을 하지 않기로 확인 이유와 함께 정한다. 결정은 다음 단지 정제부터 반영된다.
 */
export function HouseholdTargetDecisionCard({ failureId, disabled = false, onBusy }: {
  failureId: number; disabled?: boolean; onBusy?: (busy: boolean) => void
}) {
  const [view, setView] = useState<HouseholdTargetDecisionView | null>(null)
  const [loadError, setLoadError] = useState('')
  const [choice, setChoice] = useState('')
  const [reason, setReason] = useState('')
  const [releasing, setReleasing] = useState(false)
  const [releaseReason, setReleaseReason] = useState('')
  const [saving, setSaving] = useState(false)
  const [feedback, setFeedback] = useState<{ kind: 'notice' | 'error'; text: string } | null>(null)
  const pending = useRef(false)
  useEffect(() => {
    const controller = new AbortController()
    setView(null); setLoadError('')
    void getHouseholdTargetDecision(failureId, controller.signal).then(result => {
      if (!controller.signal.aborted) setView(result)
    }).catch(cause => { if (!controller.signal.aborted) setLoadError(message(cause)) })
    return () => controller.abort()
  }, [failureId])

  async function run(action: (current: HouseholdTargetDecisionView) => Promise<HouseholdTargetDecisionView>, done: string) {
    if (pending.current || disabled || !view) return
    pending.current = true; setSaving(true); onBusy?.(true); setFeedback(null)
    try {
      setView(await action(view)); setChoice(''); setReason(''); setReleaseReason(''); setReleasing(false)
      setFeedback({ kind: 'notice', text: done })
    } catch (cause) { setFeedback({ kind: 'error', text: message(cause) }) }
    finally { pending.current = false; setSaving(false); onBusy?.(false) }
  }

  if (loadError) return <p role="alert">비교 정보를 불러오지 못했습니다: {loadError}</p>
  if (!view) return <p role="status">같은 단지에 매칭된 LH 묶음을 비교하는 중…</p>
  if (!view.complex) return <p className={styles.notice}>이 LH 묶음은 지금 어느 단지에도 매칭되지 않습니다. 단지 정제를 다시 실행하면 이 오류는 정리됩니다.</p>
  const complex = view.complex
  const busy = disabled || saving
  return <section className={styles.card} aria-label="중복 대상 단지 결정">
    <div>
      <span className={styles.eyebrow}>같은 단지에 매칭된 LH 묶음 {view.groups.length}개</span>
      <h4>{complex.name} (#{complex.id})</h4>
      <p>{labels[complex.supplyType] ?? complex.supplyType} · 전체 {complex.totalHouseholdCount}세대 · {complex.roadAddress}</p>
    </div>
    <EffectTable complex={complex} groups={view.groups} />
    <p><strong>왜 실패했나:</strong> {view.identical
      ? '묶음들의 세대수가 모두 같아 다음 단지 정제에서 한 번만 반영됩니다. 정제를 다시 실행하면 이 오류는 해결됩니다.'
      : '이름만 다른 LH 묶음 여러 개가 이 단지에 매칭됐고 세대수가 서로 달라, 어느 값을 쓸지 정하지 못해 건너뛰었습니다.'}</p>
    {view.decision && <SavedDecisionStatus view={view} />}
    {view.decidable && <fieldset className={styles.form} disabled={busy}>
      <legend><strong>판단할 것:</strong> 공고문·LH 단지 정보와 비교해 맞는 묶음을 고르세요. 표의 열이 고른 뒤 채워질 세대수입니다.</legend>
      {view.groups.map(group => <label key={group.sourceKey}>
        <span><input type="radio" name={`decision-${failureId}`} value={group.sourceKey} checked={choice === group.sourceKey}
          onChange={() => setChoice(group.sourceKey)} /> ‘{group.complexName}’ 세대수 사용 → 표의 이 열 값으로 주택형 세대수를 채웁니다</span>
      </label>)}
      <label><span><input type="radio" name={`decision-${failureId}`} value={SKIP} checked={choice === SKIP}
        onChange={() => setChoice(SKIP)} /> 이 단지는 세대수 보강을 하지 않음 → 주택형 세대수를 지금 값 그대로 둡니다</span></label>
      <label>확인 이유<textarea value={reason} maxLength={2000} rows={2}
        placeholder="예: 공고문 주택형별 공급호수와 '남해평리' 묶음이 일치" onChange={event => setReason(event.target.value)} /></label>
      <div className={styles.actions}>
        <button type="button" disabled={!choice || !reason.trim()} onClick={() => {
          const selected = choice === SKIP ? { decision: 'SKIP' as const } : { decision: 'USE_SOURCE' as const, chosenSourceKey: choice }
          void run(current => saveHouseholdTargetDecision(current, selected, reason.trim()),
            '결정을 저장했습니다. 단지 정제를 다시 실행하면 반영되고 이 단지의 중복 오류가 해결됩니다.')
        }}>{saving ? '저장 중…' : '결정 저장'}</button>
      </div>
    </fieldset>}
    {view.decision && <div className={styles.form}>
      {releasing ? <>
        <label>해제 이유<textarea value={releaseReason} maxLength={2000} rows={2} disabled={busy}
          onChange={event => setReleaseReason(event.target.value)} /></label>
        <div className={styles.actions}>
          <button type="button" disabled={busy} onClick={() => setReleasing(false)}>취소</button>
          <button type="button" disabled={busy || !releaseReason.trim()} onClick={() => {
            void run(current => releaseHouseholdTargetDecision(current, releaseReason.trim()),
              '결정을 해제했습니다. 다음 단지 정제부터 이 단지는 다시 중복 오류로 남습니다.')
          }}>해제</button>
        </div>
      </> : <div className={styles.actions}>
        <button type="button" disabled={busy} onClick={() => setReleasing(true)}>저장한 결정 해제</button>
      </div>}
    </div>}
    {feedback && (feedback.kind === 'error'
      ? <p role="alert">{feedback.text}</p>
      : <p className={styles.notice} role="status">{feedback.text}</p>)}
  </section>
}

function EffectTable({ complex, groups }: { complex: DecisionComplex; groups: DecisionSourceGroup[] }) {
  return <table className={styles.compare}>
    <thead><tr><th scope="col">우리 단지 주택형 (현재 세대수)</th>
      {groups.map(group => <th key={group.sourceKey} scope="col">‘{group.complexName}’{group.failureSource ? ' · 이 오류' : ''}</th>)}</tr></thead>
    <tbody>
      {complex.housingTypes.map(type => {
        const values = groups.map(group => effect(complex, group, type.exclusiveArea))
        const differs = new Set(values).size > 1
        return <tr key={type.id}><th scope="row">{type.name} · 전용 {type.exclusiveArea}㎡ ({type.totalHouseholdCount ?? '없음'})</th>
          {values.map((value, index) => <td key={groups[index].sourceKey} className={differs ? styles.differs : undefined}>{value}</td>)}</tr>
      })}
      <tr><th scope="row">전체 세대수</th>{groups.map(group => <td key={group.sourceKey}>{group.totalHouseholdCount}세대</td>)}</tr>
    </tbody>
  </table>
}

// 보강은 전용면적이 같은 주택형이 정확히 하나일 때만 그 주택형에 세대수를 쓴다.
function effect(complex: DecisionComplex, group: DecisionSourceGroup, area: number): string {
  if (complex.housingTypes.filter(type => type.exclusiveArea === area).length > 1) return '같은 면적 주택형이 여러 개라 채우지 않음'
  const source = group.housingTypes.find(type => type.exclusiveArea === area)
  return source ? `${source.householdCount}세대` : 'LH에 이 면적 없음'
}

function SavedDecisionStatus({ view }: { view: HouseholdTargetDecisionView }) {
  const saved = view.decision
  if (!saved) return null
  const chosen = view.groups.find(group => group.sourceKey === saved.chosenSourceKey)
  return <div className={saved.reviewRequired ? styles.warning : styles.notice}>
    <p><strong>{saved.reviewRequired ? '저장한 결정 재확인 필요' : '저장한 결정 사용 중'}</strong> → {saved.decision === 'SKIP'
      ? '이 단지는 세대수 보강을 하지 않음' : `‘${chosen?.complexName ?? '지금 없는 묶음'}’ 세대수 사용`}</p>
    {saved.reviewRequired && <p>저장한 뒤 경쟁하는 LH 묶음의 구성·세대수나 단지가 바뀌어 이 결정을 쓰지 않습니다. 다시 골라 저장하세요.</p>}
    <p>이유: {saved.reason} · {saved.actor} · {new Date(saved.updatedAt).toLocaleString('ko-KR')}</p>
  </div>
}

function message(cause: unknown): string { return cause instanceof Error ? cause.message : '세대수 보강 단지 결정을 처리하지 못했습니다.' }
