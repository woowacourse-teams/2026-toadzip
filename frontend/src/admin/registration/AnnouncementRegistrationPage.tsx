import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { publicHousingRepository } from '../../public-housing/api/publicHousingRepository'
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
    <header className="admin-registration-heading"><h1>공고 입력</h1>
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
        const detail = await publicHousingRepository.findComplexDetail(complexId, controller.signal)
        if (!controller.signal.aborted) setHousingComplex({
          housingComplexId: Number(complexId), name: detail.name ?? `단지 ${complexId}`,
          roadAddress: detail.address?.roadAddress ?? '주소 정보 없음',
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
      <form className="admin-complex-select" onSubmit={(event) => {
        event.preventDefault()
        const id = new FormData(event.currentTarget).get('complexId')
        if (typeof id === 'string') onSelect(id.trim())
      }}>
        <label>단지 ID<input name="complexId" type="text" inputMode="numeric" pattern="[1-9][0-9]*"
          required defaultValue={complexId} disabled={submitting} /></label>
        <button type="submit" disabled={submitting}>단지 확인</button>
        <Link to="/admin/complexes">새 단지 입력</Link>
      </form>
      {loading ? <p role="status">선택 단지를 불러오는 중…</p> : null}
      {error ? <div><p className="form-error" role="alert">{error}</p>
        <button type="button" onClick={() => setAttempt((value) => value + 1)}>다시 시도</button></div> : null}
    </section>
    <AnnouncementRegistrationForm housingComplex={housingComplex} onSubmittingChange={onSubmittingChange} />
  </>
}
