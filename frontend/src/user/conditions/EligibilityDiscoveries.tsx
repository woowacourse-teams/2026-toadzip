import { useEffect, useId, useRef, useState } from 'react'
import { getApiBaseUrl } from '../../api/apiBaseUrl'
import { Button } from '../../design-system/components/Button'
import { decodeAnnouncementDetailEnvelope } from '../../public-housing/api/publicHousingContract'
import { toAnnouncementDetail } from '../../public-housing/api/publicHousingMapper'
import { requestPublicHousingJson } from '../../public-housing/api/publicHousingRepository'
import { RequestError } from '../../public-housing/components/RequestError'
import { useConditions } from './ConditionsContext'
import { ConditionsConflictError, type EligibilityDiscovery } from './conditionsModel'
import styles from './EligibilityDiscoveries.module.css'

interface DiscoveryRow {
  readonly discovery: EligibilityDiscovery
  readonly title: string
  readonly options: readonly string[]
}

/** In-page guidance only; no notification subscription or delivery policy is changed. */
export function EligibilityDiscoveries() {
  const { repository, revision, sessionKey, stored, processing, mode } = useConditions()
  const heading = useId()
  const key = `${sessionKey ?? ''}:${stored?.version ?? 0}:${revision}`
  const [request, setRequest] = useState({ key, cursor: 0, attempt: 0 })
  const [page, setPage] = useState<{ key: string; cursor: number; attempt: number; rows: readonly DiscoveryRow[]; next: number; more: boolean } | null>(null)
  const [error, setError] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const ready = mode === 'member' && processing?.state === 'DONE' && processing.profileVersion === stored?.version
  const current = page?.key === key && page.attempt === request.attempt ? page : null
  const activeKey = useRef(key)
  activeKey.current = key

  useEffect(() => { setRequest((value) => value.key === key ? value : { key, cursor: 0, attempt: 0 }); setPage(null); setMessage(''); setBusy(null); setError(false) }, [key])
  useEffect(() => {
    if (!ready || request.key !== key) return
    const controller = new AbortController()
    setError(false)
    const load = async () => {
      try {
        const result = await repository.loadDiscoveries(request.cursor, controller.signal)
        const rows: DiscoveryRow[] = []
        for (let start = 0; start < result.items.length; start += 4) {
          const batch = await Promise.all(result.items.slice(start, start + 4).map(async (discovery) => {
            const view = await repository.loadComparison({ announcementId: discovery.announcementId, title: null, rentalType: null, applicationStatus: null, targets: [] }, controller.signal)
            const options = view.freshness === 'CURRENT'
              ? view.types.filter((option) => discovery.optionIds.includes(option.key) && option.status === 'ELIGIBLE').map((option) => `${option.units} · ${option.label}`) : []
            if (!options.length) return null
            // Reading a discovery must not increment the announcement view count.
            const raw = await requestPublicHousingJson(fetch, `${getApiBaseUrl()}/api/v1/announcements/${encodeURIComponent(discovery.announcementId)}`, controller.signal)
            const detail = toAnnouncementDetail(decodeAnnouncementDetailEnvelope(raw))
            return { discovery, title: detail.title ?? '모집 공고', options }
          }))
          rows.push(...batch.filter((row) => row !== null))
        }
        if (controller.signal.aborted) return
        setPage((previous) => {
          const before = request.cursor > 0 && previous?.key === key ? previous.rows : []
          const unique = new Map([...before, ...rows].map((row) => [row.discovery.rootAnnouncementId, row]))
          return { key, cursor: request.cursor, attempt: request.attempt, rows: [...unique.values()], next: result.nextCursor, more: result.hasMore }
        })
      } catch { if (!controller.signal.aborted) setError(true) }
    }
    void load()
    return () => controller.abort()
  }, [ready, key, repository, request])

  useEffect(() => {
    if (!ready) return
    const timer = setInterval(() => setRequest((value) => ({ key, cursor: 0, attempt: value.attempt + 1 })), 30_000)
    return () => clearInterval(timer)
  }, [ready, key])

  const refresh = () => setRequest((value) => ({ key, cursor: 0, attempt: value.attempt + 1 }))
  const acknowledge = async (discovery: EligibilityDiscovery) => {
    setBusy(discovery.eventId)
    setMessage('')
    try {
      await repository.acknowledgeDiscovery(discovery)
      if (activeKey.current !== key) return
      setPage((value) => value?.key === key ? { ...value, rows: value.rows.filter((row) => row.discovery.eventId !== discovery.eventId) } : value)
    } catch (caught) {
      if (activeKey.current !== key) return
      setMessage(caught instanceof ConditionsConflictError ? '정보나 공고가 바뀌어 목록을 다시 확인해요.' : '확인 처리를 저장하지 못했어요. 다시 시도해 주세요.')
      if (caught instanceof ConditionsConflictError) refresh()
    } finally { if (activeKey.current === key) setBusy(null) }
  }
  if (!ready) return null
  return <section className={styles.section} aria-labelledby={heading}>
    <header><h3 id={heading}>새롭게 지원 가능한 공고</h3><Button size="sm" variant="secondary" onClick={refresh}>새로 확인</Button></header>
    <p>정보 수정으로 새로 확인된 모집 선택지예요. 아직 확인하지 않은 안내를 보여드려요.</p>
    {error ? <RequestError title="새 지원 가능 공고를 불러오지 못했어요" onRetry={() => setRequest((value) => ({ ...value, attempt: value.attempt + 1 }))} />
      : !current || current.cursor !== request.cursor ? <p role="status">새로 확인된 공고를 불러오고 있어요.</p>
      : <>
        {!current.rows.length && <p>이번 조회에 표시할 새 안내가 없어요.{current.more ? ' 다음 안내도 확인해 주세요.' : ''}</p>}
        <ul>{current.rows.map(({ discovery, title, options }) => <li key={discovery.eventId}>
          <a href={`/explore?announcementId=${encodeURIComponent(discovery.announcementId)}`}>{title}</a>
          <ul>{options.map((option) => <li key={option}>{option}</li>)}</ul>
          <Button size="sm" variant="secondary" disabled={busy !== null} onClick={() => { void acknowledge(discovery) }}>확인했어요</Button>
        </li>)}</ul>
        {current.more && <Button size="sm" variant="secondary" onClick={() => setRequest((value) => ({ key, cursor: current.next, attempt: value.attempt + 1 }))}>다음 안내 보기</Button>}
      </>}
    {message && <p role="status">{message}</p>}
  </section>
}
