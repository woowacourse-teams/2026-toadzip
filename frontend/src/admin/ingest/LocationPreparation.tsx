import { useEffect, useRef, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { ComplexIconButton } from './ComplexIconButton'
import { lookupAddresses, type AddressCandidate } from '../announcement-pdf/addressLookupApi'
import { getLocationPreparation, refreshInitialRegistration, supplementInitialRegistrationLocation,
  locationFailureReasons, type InitialRegistrationTarget, type LocationFailureReason, type LocationPreparationResponse } from './api'
import styles from './LocationPreparation.module.css'

const labels: Record<LocationFailureReason, string> = {
  INVALID_ADDRESS: '주소 누락·형식 오류', ADDRESS_NOT_FOUND: '주소 불일치',
  COORDINATE_NOT_FOUND: '좌표 미제공', COORDINATE_CONVERSION_ERROR: '좌표 변환 실패',
}

export function LocationPreparation({ disabled, revision, onBusyChange, onChanged }: {
  disabled: boolean; revision: string; onBusyChange?: (busy: boolean) => void; onChanged?: () => void
}) {
  const [result, setResult] = useState<LocationPreparationResponse | null>(null)
  const [page, setPage] = useState(0)
  const [reload, setReload] = useState(0)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [editing, setEditing] = useState<string | null>(null)
  const [notice, setNotice] = useState('')
  const noticeRef = useRef<HTMLDivElement>(null)
  const pending = useRef(false)
  useEffect(() => {
    if (!notice) return
    noticeRef.current?.scrollIntoView?.({ block: 'center' })
    noticeRef.current?.focus({ preventScroll: true })
  }, [notice])
  useEffect(() => {
    let active = true
    setLoading(true); setError('')
    void getLocationPreparation(page).then(value => { if (active) setResult(value) })
      .catch((cause: unknown) => { if (active) setError(cause instanceof Error ? cause.message : '주소·좌표 준비 상태를 조회하지 못했습니다.') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [page, revision, reload])

  async function refresh() {
    if (disabled || pending.current) return
    pending.current = true; setBusy(true); onBusyChange?.(true); setError(''); setNotice('')
    try {
      await refreshInitialRegistration()
      setPage(0); setReload(value => value + 1); onChanged?.()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '준비 상태를 확인하지 못했습니다.')
    } finally {
      pending.current = false; setBusy(false); onBusyChange?.(false)
    }
  }

  const hasCheckedTargets = result !== null && Object.values(result.counts).some(count => count > 0)
  const refreshing = busy && editing === null
  return <section className={styles.panel} aria-label="주소·좌표 준비 현황" aria-busy={loading || busy}>
    <header className={styles.header}><div className={styles.intro}><h3>단지별 준비 현황</h3>
      <p>원천 수집·ZIP 업로드 후에는 자동으로 확인합니다. 이전에 수집한 원천을 다시 비교할 때 상태 확인을 실행하세요.</p></div>
      <Button data-design-system variant="secondary" disabled={disabled || busy || editing !== null} onClick={() => void refresh()}>주소·좌표 상태 확인</Button>
    </header>
    {notice && <div ref={noticeRef} className={styles.saveNotice} role="status" aria-label="주소·좌표 저장 완료" aria-atomic="true" tabIndex={-1}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="m5 12 4 4L19 6" /></svg>
      <div><strong>{notice}</strong><p>다른 필수 정보가 모두 준비되면 ‘등록 가능’으로 표시됩니다.</p></div>
    </div>}
    {result && hasCheckedTargets && <div className={styles.overview}><dl className={styles.counts} aria-label="주소·좌표 준비 집계">
      <div><dt>좌표 준비 완료</dt><dd>{result.counts.READY}곳</dd></div>
      {locationFailureReasons.map(reason => <div key={reason}><dt>{labels[reason]}</dt><dd>{result.counts[reason]}곳</dd></div>)}
    </dl><p>주소가 일치해도 지도용 좌표가 없으면 보완이 필요합니다. 보완값은 원천 주소와 구분해 저장합니다.</p></div>}
    {error && <div role="alert"><p>{error}</p><Button data-design-system variant="secondary" onClick={() => setReload(value => value + 1)}>준비 현황 다시 조회</Button></div>}
    {refreshing && <p role="status">수집한 원천 주소와 저장된 좌표를 비교하고 있습니다.</p>}
    {loading && <p role="status">주소·좌표 준비 현황을 불러오고 있습니다.</p>}
    {!loading && !refreshing && !error && result && !hasCheckedTargets && <p>아직 주소·좌표를 확인한 신규 단지가 없습니다. 원천 수집 여부를 확인하거나 기존 원천의 주소·좌표 상태 확인을 실행하세요.</p>}
    {!loading && !refreshing && !error && result && hasCheckedTargets && <section className={styles.review} aria-label="주소·좌표 보완 목록">
      <h4>보완할 단지 {result.totalElements}곳</h4>
      {result.targets.length === 0 && <p>보완할 주소·좌표가 없습니다.</p>}
      <ul className={styles.targets}>{result.targets.map(target => <li key={target.sourceComplexIdentifier}>
        <div className={styles.header}><strong>{target.name || '단지명 미제공'}</strong>
          <Button data-design-system variant="secondary" disabled={disabled || busy} onClick={() => { setNotice(''); setEditing(target.sourceComplexIdentifier) }}>주소·좌표 보완</Button></div>
        <p>원천 주소: {target.roadAddress || '주소 미제공'}</p>
        <p>{target.location?.failure ? labels[target.location.failure] : target.detail}</p>
        {editing === target.sourceComplexIdentifier && <LocationEditor key={editing} target={target} disabled={disabled}
          onBusyChange={value => { setBusy(value); onBusyChange?.(value) }} onCancel={() => setEditing(null)}
          onSaved={() => { setEditing(null); setNotice(`${target.name || '선택한 단지'}의 주소·좌표 보완값을 저장했습니다.`); setPage(0); setReload(value => value + 1); onChanged?.() }} />}
      </li>)}</ul>
      <nav className={styles.pagination} aria-label="주소·좌표 보완 목록 페이지">
        <ComplexIconButton icon="previous" label="이전 페이지" disabled={page === 0 || busy} onClick={() => { setEditing(null); setPage(value => value - 1) }} />
        <span aria-live="polite" aria-atomic="true">{result.totalPages === 0 ? 0 : page + 1} / {result.totalPages}</span>
        <ComplexIconButton icon="next" label="다음 페이지" disabled={page + 1 >= result.totalPages || busy} onClick={() => { setEditing(null); setPage(value => value + 1) }} />
      </nav>
    </section>}
  </section>
}

function LocationEditor({ target, disabled, onBusyChange, onCancel, onSaved }: {
  target: InitialRegistrationTarget; disabled: boolean; onBusyChange: (busy: boolean) => void
  onCancel: () => void; onSaved: () => void
}) {
  const [address, setAddress] = useState(target.roadAddress ?? '')
  const [keyword, setKeyword] = useState(target.roadAddress ?? '')
  const [latitude, setLatitude] = useState('')
  const [longitude, setLongitude] = useState('')
  const [candidates, setCandidates] = useState<AddressCandidate[] | null>(null)
  const [error, setError] = useState('')
  const [searching, setSearching] = useState(false)
  const [saving, setSaving] = useState(false)
  const pending = useRef(false)
  const searchVersion = useRef(0)
  useEffect(() => () => { searchVersion.current++ }, [])
  async function search() {
    const version = ++searchVersion.current
    setSearching(true); setError(''); setCandidates(null)
    try {
      const result = await lookupAddresses(keyword.trim())
      if (version === searchVersion.current) setCandidates(result)
    } catch (cause) {
      if (version === searchVersion.current) setError(`${cause instanceof Error ? cause.message : '주소를 검색하지 못했습니다.'} 주소와 좌표를 직접 입력하세요.`)
    } finally { if (version === searchVersion.current) setSearching(false) }
  }
  function choose(candidate: AddressCandidate) {
    setAddress(candidate.roadAddress || candidate.jibunAddress)
    setLatitude(candidate.latitude === null ? '' : String(candidate.latitude))
    setLongitude(candidate.longitude === null ? '' : String(candidate.longitude))
    setCandidates(null)
  }
  async function save() {
    if (disabled || pending.current) return
    pending.current = true; setSaving(true); onBusyChange(true); setError('')
    try {
      await supplementInitialRegistrationLocation(target.sourceComplexIdentifier, address.trim(), Number(latitude), Number(longitude))
      onSaved()
    } catch (cause) { setError(cause instanceof Error ? cause.message : '보완값을 저장하지 못했습니다.') }
    finally { pending.current = false; setSaving(false); onBusyChange(false) }
  }
  const valid = address.trim() && latitude.trim() && longitude.trim()
    && Number.isFinite(Number(latitude)) && Math.abs(Number(latitude)) <= 90
    && Number.isFinite(Number(longitude)) && Math.abs(Number(longitude)) <= 180
  return <form className={styles.editor} aria-label="주소·좌표 보완 입력" onSubmit={event => { event.preventDefault(); if (valid) void save() }}>
    <fieldset disabled={disabled || saving}><legend>지도용 주소·좌표 보완</legend>
      <div className={styles.search}><label>주소 검색<input value={keyword} maxLength={200} onChange={event => {
        setKeyword(event.target.value); searchVersion.current++; setSearching(false); setCandidates(null)
      }} /></label>
        <Button data-design-system variant="secondary" disabled={!keyword.trim() || searching} onClick={() => void search()}>주소 찾기</Button></div>
      {searching && <p role="status">주소를 검색하고 있습니다.</p>}
      {candidates && (candidates.length ? <ul aria-label="주소 검색 결과">{candidates.map((candidate, index) => <li key={index}>
        <Button data-design-system variant="secondary" onClick={() => choose(candidate)}>{candidate.roadAddress || candidate.jibunAddress}{candidate.latitude === null || candidate.longitude === null ? ' (좌표 미제공)' : ''}</Button>
      </li>)}</ul> : <p>검색 결과가 없습니다. 주소와 좌표를 직접 입력하세요.</p>)}
      <label>보완 주소<input value={address} required maxLength={500} onChange={event => {
        setAddress(event.target.value); setLatitude(''); setLongitude('')
      }} /></label>
      <p>주소를 수정하면 좌표가 초기화됩니다. 위도·경도를 다시 확인해 입력하세요.</p>
      <div className={styles.coordinates}>
        <label>위도<input type="number" required min={-90} max={90} step="0.00000001" value={latitude} onChange={event => setLatitude(event.target.value)} /></label>
        <label>경도<input type="number" required min={-180} max={180} step="0.00000001" value={longitude} onChange={event => setLongitude(event.target.value)} /></label>
      </div>
      <div className={styles.header}><Button data-design-system type="submit" disabled={!valid}>{saving ? '저장 중…' : '보완값 저장'}</Button><Button data-design-system variant="secondary" onClick={onCancel}>취소</Button></div>
    </fieldset>
    {error && <p role="alert">{error}</p>}
  </form>
}
