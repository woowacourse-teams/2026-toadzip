import { useEffect, useRef, useState } from 'react'
import { labels } from '../management/fields'
import { getHouseholdComplexLink, releaseHouseholdComplexLink, saveHouseholdComplexLink,
  type HouseholdCandidate, type HouseholdComplexLinkView } from './householdComplexLinkApi'
import styles from './HouseholdComplexLinkCard.module.css'

/**
 * LH 세대수 보강이 실패한 원천과 공급유형·전체 세대수·지역이 같은 단지 후보를 나란히 보여 준다.
 * 후보가 하나면 관리자가 확인 이유를 적고 연결을 저장한다. 저장·해제는 다음 세대수 보강부터 반영된다.
 */
export function HouseholdComplexLinkCard({ failureId, disabled = false, onBusy }: {
  failureId: number; disabled?: boolean; onBusy?: (busy: boolean) => void
}) {
  const [view, setView] = useState<HouseholdComplexLinkView | null>(null)
  const [loadError, setLoadError] = useState('')
  const [reason, setReason] = useState('')
  const [sourceUrl, setSourceUrl] = useState('')
  const [releasing, setReleasing] = useState(false)
  const [releaseReason, setReleaseReason] = useState('')
  const [saving, setSaving] = useState(false)
  const [feedback, setFeedback] = useState<{ kind: 'notice' | 'error'; text: string } | null>(null)
  const pending = useRef(false)
  useEffect(() => {
    const controller = new AbortController()
    setView(null); setLoadError('')
    void getHouseholdComplexLink(failureId, controller.signal).then(result => {
      if (!controller.signal.aborted) setView(result)
    }).catch(cause => { if (!controller.signal.aborted) setLoadError(message(cause)) })
    return () => controller.abort()
  }, [failureId])

  async function run(action: (current: HouseholdComplexLinkView) => Promise<HouseholdComplexLinkView>, done: string) {
    if (pending.current || disabled || !view) return
    pending.current = true; setSaving(true); onBusy?.(true); setFeedback(null)
    try {
      setView(await action(view)); setReason(''); setReleaseReason(''); setReleasing(false)
      setFeedback({ kind: 'notice', text: done })
    } catch (cause) { setFeedback({ kind: 'error', text: message(cause) }) }
    finally { pending.current = false; setSaving(false); onBusy?.(false) }
  }

  if (loadError) return <p role="alert">비교 정보를 불러오지 못했습니다: {loadError}</p>
  if (!view) return <p role="status">LH 원천과 단지 후보를 비교하는 중…</p>
  const source = view.source
  const candidate = view.linkable ? view.candidates[0] : null
  const busy = disabled || saving
  return <section className={styles.card} aria-label="세대수 보강 단지 비교">
    <div>
      <span className={styles.eyebrow}>LH 임대 목록 원천</span>
      <h4>{source.complexName ?? '단지명 없음'} · {source.supplyTypeName ?? '공급유형 없음'}</h4>
      <p>{source.areaName ?? '지역 없음'} · 전체 {source.totalHouseholdCount ?? '미확인'}세대
        {source.housingTypes.length > 0 && ` · ${source.housingTypes.map(type => `전용 ${type.exclusiveArea}㎡ ${type.householdCount}세대`).join(', ')}`}</p>
    </div>
    {source.invalidReason && <p className={styles.warning}>원천을 세대수 보강 입력으로 바꾸지 못했습니다: {source.invalidReason}</p>}
    {view.candidates.length > 0 && <CompareTable view={view} />}
    <p><strong>왜 실패했나:</strong> {why(view)}</p>
    {view.savedLink && <SavedLinkStatus view={view} />}
    {candidate && <div className={styles.form}>
      <p><strong>판단할 것:</strong> 이름 외의 공급유형·전체 세대수·지역은 맞습니다. 주소와 주택형 면적이 같은 단지인지 확인하고,
        같다면 확인 이유를 적어 연결하세요.</p>
      <label>확인 이유<textarea value={reason} maxLength={2000} rows={2} disabled={busy}
        placeholder="예: 도로명 주소와 영구임대 240세대, 전용 21.97·26.9 대조" onChange={event => setReason(event.target.value)} /></label>
      <label>참고 URL (선택)<input type="url" value={sourceUrl} maxLength={2048} disabled={busy}
        onChange={event => setSourceUrl(event.target.value)} /></label>
      <div className={styles.actions}>
        <button type="button" disabled={busy || !reason.trim()} onClick={() => {
          if (sourceUrl.trim() && !/^https?:\/\//i.test(sourceUrl.trim())) {
            setFeedback({ kind: 'error', text: '참고 URL은 http 또는 https 주소로 입력해 주세요.' }); return
          }
          void run(current => saveHouseholdComplexLink(current, candidate.id, reason.trim(), sourceUrl.trim()),
            '연결을 저장했습니다. 단지 정제를 다시 실행하면 세대수 보강에 반영됩니다.')
        }}>{saving ? '저장 중…' : `${candidate.name}(으)로 연결 저장`}</button>
      </div>
    </div>}
    {view.savedLink && <div className={styles.form}>
      {releasing ? <>
        <label>해제 이유<textarea value={releaseReason} maxLength={2000} rows={2} disabled={busy}
          onChange={event => setReleaseReason(event.target.value)} /></label>
        <div className={styles.actions}>
          <button type="button" disabled={busy} onClick={() => setReleasing(false)}>취소</button>
          <button type="button" disabled={busy || !releaseReason.trim()} onClick={() => {
            void run(current => releaseHouseholdComplexLink(current, releaseReason.trim()),
              '연결을 해제했습니다. 다음 세대수 보강부터 이름 비교로 다시 찾습니다.')
          }}>해제</button>
        </div>
      </> : <div className={styles.actions}>
        <button type="button" disabled={busy} onClick={() => setReleasing(true)}>저장한 연결 해제</button>
      </div>}
    </div>}
    {feedback && (feedback.kind === 'error'
      ? <p role="alert">{feedback.text}</p>
      : <p className={styles.notice} role="status">{feedback.text}</p>)}
  </section>
}

function CompareTable({ view }: { view: HouseholdComplexLinkView }) {
  const source = view.source
  const sourceAreas = source.housingTypes.map(type => type.exclusiveArea)
  return <table className={styles.compare}>
    <thead><tr><th scope="col"></th><th scope="col">LH 원천</th>
      {view.candidates.map((candidate, index) => <th key={candidate.id} scope="col">
        등록 단지{view.candidates.length > 1 ? ` ${index + 1}` : ''} (#{candidate.id})</th>)}</tr></thead>
    <tbody>
      <tr><th scope="row">단지명</th><td>{source.complexName}</td>
        {view.candidates.map(candidate => <td key={candidate.id} className={styles.differs}>{candidate.name} (다름)</td>)}</tr>
      <tr><th scope="row">공급유형</th><td>{source.supplyTypeName}</td>
        {view.candidates.map(candidate => <td key={candidate.id}>{labels[candidate.supplyType] ?? candidate.supplyType}</td>)}</tr>
      <tr><th scope="row">전체 세대수</th><td>{source.totalHouseholdCount}세대</td>
        {view.candidates.map(candidate => <td key={candidate.id}>{candidate.totalHouseholdCount}세대</td>)}</tr>
      <tr><th scope="row">지역·주소</th><td>{source.areaName}</td>
        {view.candidates.map(candidate => <td key={candidate.id}>{candidate.roadAddress}</td>)}</tr>
      <tr><th scope="row">주택형 전용면적</th><td>{areas(sourceAreas)}</td>
        {view.candidates.map(candidate => <AreaCell key={candidate.id} candidate={candidate} sourceAreas={sourceAreas} />)}</tr>
    </tbody>
  </table>
}

function AreaCell({ candidate, sourceAreas }: { candidate: HouseholdCandidate; sourceAreas: number[] }) {
  // 보강은 전용면적이 같은 주택형이 정확히 하나일 때만 세대수를 채우므로, 채우지 못할 면적을 미리 보여 준다.
  const missing = sourceAreas.filter(area => !candidate.exclusiveAreas.includes(area))
  const shared = sourceAreas.filter(area => candidate.exclusiveAreas.filter(value => value === area).length > 1)
  const notes = [missing.length > 0 ? `LH ${areas(missing)} 없음` : null,
    shared.length > 0 ? `${areas(shared)} 주택형이 여러 개라 세대수를 채우지 않음` : null].filter(Boolean)
  return <td className={notes.length > 0 ? styles.differs : undefined}>
    {areas(candidate.exclusiveAreas)}{notes.length > 0 ? ` (${notes.join(', ')})` : ''}</td>
}

function SavedLinkStatus({ view }: { view: HouseholdComplexLinkView }) {
  const saved = view.savedLink
  if (!saved) return null
  return <div className={saved.reviewRequired ? styles.warning : styles.notice}>
    <p><strong>{saved.reviewRequired ? '저장한 연결 재확인 필요' : '저장한 연결 사용 중'}</strong>
      {' → '}{saved.complexName ?? '삭제된 단지'}{saved.complexId ? ` (#${saved.complexId})` : ''}</p>
    {saved.reviewRequired && <p>저장한 뒤 LH 원천이나 단지의 공급유형·세대수·주소가 바뀌어 이 연결을 쓰지 않습니다.
      후보가 하나로 맞으면 다시 확인해 저장하세요.</p>}
    <p>이유: {saved.reason} · {saved.actor} · {new Date(saved.updatedAt).toLocaleString('ko-KR')}
      {saved.sourceUrl && <> · <a href={saved.sourceUrl} target="_blank" rel="noreferrer">참고 URL</a></>}</p>
  </div>
}

function why(view: HouseholdComplexLinkView): string {
  if (view.source.invalidReason) return 'LH 원천 값이 서로 맞지 않아 후보를 찾지 않았습니다.'
  if (view.savedLink?.reviewRequired) return '관리자가 저장한 연결 이후 LH 원천이나 단지가 바뀌었습니다.'
  if (view.candidates.length === 0) {
    return '공급유형·전체 세대수·지역이 같은 단지가 없습니다. 단지가 등록되지 않았거나 세대수가 다릅니다.'
  }
  if (view.candidates.length === 1) {
    return '공급유형·전체 세대수·지역이 같은 단지가 하나 있지만, 단지명이 달라 자동으로 연결하지 않았습니다.'
  }
  return `공급유형·전체 세대수·지역이 같은 단지가 ${view.candidates.length}개라 하나로 정하지 못했습니다. 이 화면에서는 연결할 수 없습니다.`
}

function areas(values: number[]): string { return values.length === 0 ? '없음' : values.map(value => `${value}㎡`).join(', ') }
function message(cause: unknown): string { return cause instanceof Error ? cause.message : '세대수 보강 연결을 처리하지 못했습니다.' }
