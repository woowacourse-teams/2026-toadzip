import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { SourceUrl } from '../shared/SourceUrl'
import { ManagementError } from './api'
import { getComplexVerification, saveComplexReview } from './complexVerificationApi'
import {
  verificationFields,
  type ComplexVerification, type ReviewOutcome, type VerificationField,
} from './complexVerificationContract'
import {
  currentText, mapUrl, reviewTime, safeEvidenceUrl, sameValue, sourceMatches, sourceText,
  verificationFieldLabels, verificationStatusLabels,
} from './complexVerificationPresentation'
import { ComplexVerificationBadge } from './ComplexVerificationBadge'
import type { ManagementSummary } from './managementContract'
import { useUnsavedChanges } from './useUnsavedChanges'
import styles from './ComplexVerification.module.css'

type Props = {
  id: string
  version: number
  deleted: boolean
  announcements: ManagementSummary[]
  onEdit: () => void
  onReviewed: () => void
  onBusyChange: (busy: boolean) => void
}
export function ComplexVerificationPanel({ id, version, deleted, announcements, onEdit, onReviewed, onBusyChange }: Props) {
  const [value, setValue] = useState<ComplexVerification | null>(null)
  const [error, setError] = useState('')
  const [conflict, setConflict] = useState(false)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [attempt, setAttempt] = useState(0)
  const [fields, setFields] = useState<VerificationField[]>([])
  const [outcome, setOutcome] = useState<ReviewOutcome>('VERIFIED')
  const [evidenceUrl, setEvidenceUrl] = useState('')
  const [note, setNote] = useState('')
  const [source, setSource] = useState('')
  const [busy, setBusy] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [notice, setNotice] = useState('')
  const session = useRef<AbortController | null>(null)
  useUnsavedChanges(dirty)

  useEffect(() => {
    const controller = new AbortController()
    session.current = controller
    setValue(null); setError(''); setConflict(false); setFields([]); setNote(''); setEvidenceUrl('')
    setDirty(false); setSource(''); setFieldErrors({})
    void getComplexVerification(id, controller.signal).then(next => {
      if (!controller.signal.aborted) setValue(next)
    }).catch(cause => {
      if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '검증 정보를 불러오지 못했습니다.')
    })
    return () => controller.abort()
  }, [id, version, attempt])
  useEffect(() => {
    onBusyChange(busy)
    return () => onBusyChange(false)
  }, [busy, onBusyChange])

  function reload() {
    if (dirty && !window.confirm('작성한 검토 내용을 버리고 새로 조회할까요?')) return
    setNotice(''); setAttempt(current => current + 1)
  }
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!value || !fields.length || busy || deleted || conflict) return
    setBusy(true); setError(''); setNotice(''); setFieldErrors({})
    const controller = session.current
    try {
      const next = await saveComplexReview(id, {
        version: value.version, reviewId: value.latestReview?.id ?? 0, snapshotToken: value.snapshotToken,
        fields, outcome, evidenceUrl: evidenceUrl.trim(), evidenceNote: note.trim(),
      }, controller?.signal)
      if (controller?.signal.aborted) return
      setValue(next); setDirty(false); setFields([]); setNote(''); setEvidenceUrl('')
      setNotice('검토 기록을 저장했습니다.'); onReviewed()
    } catch (cause) {
      if (controller?.signal.aborted) return
      setError(cause instanceof Error ? cause.message : '검토 기록을 저장하지 못했습니다.')
      if (cause instanceof ManagementError) {
        setFieldErrors(cause.fields)
        setConflict(cause.status === 409)
      }
    } finally {
      if (!controller?.signal.aborted) setBusy(false)
    }
  }
  async function copySearch() {
    if (!value) return
    try {
      await navigator.clipboard.writeText(`${value.currentValues.NAME} ${value.currentValues.ADDRESS.roadAddress}`)
      if (!session.current?.signal.aborted) setNotice('단지명과 주소를 복사했습니다.')
    } catch {
      if (!session.current?.signal.aborted) setError('복사하지 못했습니다. 단지명과 주소를 직접 선택해 복사해 주세요.')
    }
  }

  const sources = value?.sources.filter(row => !source || row.sourceIdentifier === source) ?? []
  const sourceIds = [...new Set(value?.sources.map(row => row.sourceIdentifier) ?? [])]
  const latest = value?.latestReview
  return <section className={styles.panel} aria-label="단지 데이터 검증" aria-busy={busy}>
    {error ? <div className={styles.error} role="alert"><p>{error}</p>
      <button type="button" disabled={busy} onClick={reload}>새로 조회</button></div> : null}
    {notice ? <p className={styles.notice} role="status">{notice}</p> : null}
    {!value && !error ? <p role="status">검증 정보를 불러오는 중…</p> : null}
    {value ? <>
      <header className={styles.heading}><div><h2>등록 정보 확인</h2>
        <p>원천과 대조한 뒤, 직접 확인한 항목을 기록하세요.</p></div>
        <ComplexVerificationBadge status={value.status} count={latest?.fields.length} />
      </header>
      {value.status === 'STALE' ? <p className={styles.warning}>지난 검토 이후 확인한 값이 바뀌었습니다. 변경된 항목을 다시 확인해 주세요.</p> : null}
      <div className={styles.tools}>
        <a href="https://m.myhome.go.kr/hws/mbl/sch/selectCurrentSearchView.do" target="_blank" rel="noreferrer">마이홈 공식 검색 ↗</a>
        <button type="button" onClick={() => void copySearch()}>단지명·주소 복사</button>
        <a href={mapUrl(value.currentValues.LOCATION.join(','))} target="_blank" rel="noreferrer">저장 좌표 보기 ↗</a>
        <a href={mapUrl(value.currentValues.ADDRESS.roadAddress)} target="_blank" rel="noreferrer">주소 위치 보기 ↗</a>
        {!deleted ? <button type="button" data-admin-navigation disabled={busy} onClick={onEdit}>등록 정보 수정</button> : null}
      </div>
      {announcements.length ? <details className={styles.references}><summary>연결 공고에서 확인 <span>{announcements.length}</span></summary>
        <ul>{announcements.map(announcement => {
          const url = safeEvidenceUrl(announcement.announcement?.originalUrl)
          return <li key={announcement.id}><Link to={`/admin/announcements/${announcement.id}`}>{announcement.name}</Link>
            {url ? <a href={url} target="_blank" rel="noreferrer">공식 원문 ↗</a> : <span>원문 URL 미등록</span>}</li>
        })}</ul>
      </details> : null}
      <form onSubmit={event => void save(event)} className={styles.reviewForm}>
        <div className={styles.comparisonHeading}>
          <h3>항목별 비교</h3>
          {sourceIds.length > 1 ? <label>비교 원천<select value={source} onChange={event => setSource(event.target.value)}>
            <option value="">전체 원천 · {sourceIds.length}개</option>
            {sourceIds.map((identifier, index) => <option key={identifier} value={identifier}>원천 {index + 1} · 마이홈 단지 {identifier.split(':')[0]}</option>)}
          </select></label> : <span>{sourceIds.length ? '마이홈 수집 원천' : '연결된 원천 없음'}</span>}
        </div>
        {!value.sources.length ? <p className={styles.empty}>연결된 수집 원천이 없습니다. 공식 자료와 지도를 직접 확인해 주세요.</p> : null}
        <fieldset className={styles.comparison} disabled={busy || deleted}>
          <legend className={styles.srOnly}>직접 확인한 항목 선택</legend>
          <div className={styles.columnHead} aria-hidden="true"><span>확인 항목</span><span>현재 등록값</span><span>수집 원천값</span><span>비교 결과</span></div>
          {verificationFields.map(field => {
            const comparable = field !== 'LOCATION' && sources.length > 0
            const missing = comparable && sources.some(row => sourceText(field, row) === null
              || (field === 'ADDRESS' && row.pnu === null))
            const matches = comparable && !missing && sources.every(row => sourceMatches(field, value.currentValues, row))
            const comparison = field === 'LOCATION' ? '지도 확인' : !sources.length ? '원천 없음'
              : missing ? '원천값 미확인' : matches ? '원천 일치' : '값 다름'
            const oldValue = latest?.checkedValues[field]
            const changed = oldValue !== undefined && !sameValue(oldValue, value.currentValues[field])
            const grouped = new Map<string, { text: string | null; pnu: string | null; identifiers: string[] }>()
            sources.forEach(row => {
              const text = sourceText(field, row)
              const pnu = field === 'ADDRESS' ? row.pnu : null
              const key = JSON.stringify([text, pnu])
              const entry = grouped.get(key) ?? { text, pnu, identifiers: [] }
              if (!entry.identifiers.includes(row.sourceIdentifier)) entry.identifiers.push(row.sourceIdentifier)
              grouped.set(key, entry)
            })
            const entries = [...grouped.values()]
            return <div key={field} className={styles.row} data-different={comparable && !missing && !matches || undefined}>
              <label className={styles.field}><input type="checkbox" aria-label={`${verificationFieldLabels[field]} 확인`}
                checked={fields.includes(field)} onChange={event => {
                  setFields(current => event.target.checked ? [...current, field] : current.filter(item => item !== field))
                  setDirty(true)
                }} /><span>{verificationFieldLabels[field]}</span></label>
              <div className={styles.current}><span className={styles.mobileLabel}>현재 등록값</span>
                <strong>{currentText(field, value.currentValues)}</strong>
                {field === 'ADDRESS' ? <small>PNU {value.currentValues.ADDRESS.pnu}</small> : null}
                {changed ? <small className={styles.changed}>지난 확인: {currentText(field, latest?.checkedValues ?? {})}
                  {field === 'ADDRESS' ? ` · PNU ${latest?.checkedValues.ADDRESS?.pnu}` : ''}</small> : null}
              </div>
              <div className={styles.source}><span className={styles.mobileLabel}>수집 원천값</span>
                {field === 'LOCATION' ? <span className={styles.muted}>좌표 원천 없음</span>
                  : !entries.length ? <span className={styles.muted}>미확인</span>
                    : entries.map(entry => <div key={JSON.stringify([entry.text, entry.pnu])}>
                      <span>{entry.text ?? '미확인'}</span>
                      {entry.pnu ? <small>PNU {entry.pnu}</small> : null}
                      {sourceIds.length > 1 ? <small>{entry.identifiers.map(identifier => `원천 ${sourceIds.indexOf(identifier) + 1}`).join(' · ')}</small> : null}
                    </div>)}
              </div>
              <span className={styles.result} data-tone={matches ? 'match' : comparable && !missing ? 'different' : 'neutral'}>{comparison}</span>
            </div>
          })}
        </fieldset>
        <p className={styles.hint}>원천 일치는 실제 정보 확인 완료를 뜻하지 않습니다. 세대수는 단지 전체·공급유형·모집 범위를 구분해 확인하세요.</p>
        {value.sources.length ? <details className={styles.references}><summary>수집 정보·원천 보기</summary>
          <ul>{sourceIds.map(identifier => {
            const rows = value.sources.filter(row => row.sourceIdentifier === identifier)
            const times = [...new Set(rows.map(row => row.collectedAt).filter((time): time is string => time !== null))]
            return <li key={identifier}><div><strong>{identifier}</strong>
              <small>{times.length ? times.map(reviewTime).join(' · ') : '수집 시각 미확인'}</small></div>
              <Link to={`/admin/sources?${new URLSearchParams({ category: 'MYHOME_COMPLEX', keyword: identifier.split(':')[0] })}`}>원천 데이터 보기</Link></li>
          })}</ul>
          {sourceIds.length > 1 ? <p className={styles.hint}>여러 원천의 세대수는 합산하지 않고 각각 표시합니다.</p> : null}
        </details> : null}
        <section className={styles.record} aria-labelledby="complex-review-heading">
          <div className={styles.recordHeading}><h3 id="complex-review-heading">검토 기록</h3><span>{fields.length}개 항목 선택</span></div>
          {deleted ? <p className={styles.hint}>휴지통에서 복구한 뒤 검토를 기록할 수 있습니다.</p> : null}
          <fieldset className={styles.formFields} disabled={busy || deleted}>
            <legend className={styles.srOnly}>확인 결과와 근거</legend>
            <div className={styles.outcomes}>{(['VERIFIED', 'ON_HOLD'] as const).map(result => <label key={result}>
              <input type="radio" name="reviewOutcome" value={result} checked={outcome === result} onChange={() => { setOutcome(result); setDirty(true) }} />
              <span>{verificationStatusLabels[result]}</span></label>)}</div>
            <label>확인 근거·메모<textarea required maxLength={2000} rows={3} value={note}
              placeholder="예: 공급기관 단지 안내 2쪽에서 주소·세대수 확인" aria-invalid={Boolean(fieldErrors.evidenceNote)}
              onChange={event => { setNote(event.target.value); setDirty(true) }} /></label>
            {fieldErrors.evidenceNote ? <p className={styles.errorText}>{fieldErrors.evidenceNote}</p> : null}
            <label><span>근거 URL <span className={styles.optional}>선택</span></span><input type="url" maxLength={2048} value={evidenceUrl}
              placeholder="https://" aria-invalid={Boolean(fieldErrors.evidenceUrl)}
              onChange={event => { setEvidenceUrl(event.target.value); setDirty(true) }} /></label>
            {fieldErrors.evidenceUrl ? <p className={styles.errorText}>{fieldErrors.evidenceUrl}</p> : null}
          </fieldset>
          <div className={styles.saveBar}><p>선택한 항목의 값과 확인 근거를 기록합니다.</p>
            <Button data-design-system type="submit" disabled={busy || deleted || !fields.length || !note.trim() || conflict}>{busy ? '저장 중…' : '검토 저장'}</Button>
          </div>
        </section>
      </form>
      {value.history.length ? <details className={styles.history}><summary>검토 이력 <span>{value.history.length === 20 ? '최근 20건' : `${value.history.length}건`}</span></summary>
        <ol>{value.history.map(review => <li key={review.id}>
          <div className={styles.historyHeading}><strong>{verificationStatusLabels[review.outcome]}</strong>
            <span>{review.actor} · {reviewTime(review.reviewedAt)}</span></div>
          <p className={styles.scope}>{review.fields.map(field => verificationFieldLabels[field]).join(' · ')}</p>
          <p className={styles.reviewNote}>{review.evidenceNote}</p>
          {review.evidenceUrl ? <SourceUrl url={review.evidenceUrl} /> : null}
          <dl className={styles.checkedValues}>{review.fields.map(field => <div key={field}>
            <dt>{verificationFieldLabels[field]}</dt><dd>{currentText(field, review.checkedValues)}
              {field === 'ADDRESS' ? <small>PNU {review.checkedValues.ADDRESS?.pnu}</small> : null}</dd></div>)}</dl>
        </li>)}</ol>
      </details> : null}
    </> : null}
  </section>
}
