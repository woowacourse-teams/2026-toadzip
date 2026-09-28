import { ComplexPicker } from '../management/ComplexPicker'
import { detail } from '../management/api'
import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { AnnouncementImportForm } from './AnnouncementImportForm'
import { AnnouncementRegistrationForm } from './AnnouncementRegistrationForm'
import type { HousingComplexCreateResponse } from './api'

export function AnnouncementRegistrationPage() {
  const [params, setParams] = useSearchParams()
  const direct = params.get('mode') === 'direct'
  const complexId = params.get('complexId') ?? ''
  const [submitting, setSubmitting] = useState(false)

  function selectMode(mode: string) {
    setParams((current) => { current.set('mode', mode); return current })
  }

  return <section className="admin-registration-page">
    <header className="admin-registration-heading"><Link to="/admin/announcements">← 공고 목록</Link><h1>공고 등록</h1>
      <p>JSON을 검증해 가져오거나, 등록된 단지를 선택해 공고를 직접 입력합니다.</p></header>
    <div className="admin-mode-switch" aria-label="공고 입력 방식">
      <button type="button" aria-pressed={!direct} disabled={submitting} onClick={() => selectMode('json')}>JSON 가져오기</button>
      <button type="button" aria-pressed={direct} disabled={submitting} onClick={() => selectMode('direct')}>직접 입력</button>
    </div>
    {direct ? <DirectAnnouncement key={complexId} complexId={complexId} submitting={submitting}
      onSubmittingChange={setSubmitting} onSelect={(id) => setParams({ mode: 'direct', complexId: id })} />
      : <AnnouncementImportForm onSubmittingChange={setSubmitting} />}
  </section>
}

function DirectAnnouncement({ complexId, submitting, onSubmittingChange, onSelect }: {
  complexId: string, submitting: boolean,
  onSubmittingChange: (value: boolean) => void, onSelect: (id: string) => void,
}) {
  const [housingComplex, setHousingComplex] = useState<HousingComplexCreateResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(Boolean(complexId))
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    if (!complexId) return
    const controller = new AbortController()
    async function load() {
      setLoading(true)
      setError(null)
      try {
        if (!/^[1-9]\d*$/.test(complexId) || !Number.isSafeInteger(Number(complexId))) {
          throw new Error('유효한 단지 ID를 입력해 주세요.')
        }
        const found = await detail('complexes', complexId, controller.signal)
        if (found.summary.deleted) throw new Error('삭제된 단지는 연결할 수 없습니다.')
        if (!controller.signal.aborted) setHousingComplex({
          housingComplexId: Number(complexId), name: found.summary.name,
          roadAddress: found.summary.subtitle,
        })
      } catch (cause) {
        if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '단지를 불러오지 못했습니다.')
      } finally {
        if (!controller.signal.aborted) setLoading(false)
      }
    }
    void load()
    return () => controller.abort()
  }, [complexId, attempt])

  return <>
    <section className="registration-card" aria-labelledby="complex-selection-title">
      <h2 id="complex-selection-title">공고를 연결할 단지</h2>
      <ComplexPicker disabled={submitting} onSelect={item => onSelect(String(item.id))} />
      <Link to="/admin/complexes/new">새 단지 등록</Link>
      {loading ? <p role="status">선택 단지를 불러오는 중…</p> : null}
      {error ? <div><p className="form-error" role="alert">{error}</p>
        <button type="button" onClick={() => setAttempt((value) => value + 1)}>다시 시도</button></div> : null}
    </section>
    <AnnouncementRegistrationForm housingComplex={housingComplex} onSubmittingChange={onSubmittingChange} />
  </>
}
