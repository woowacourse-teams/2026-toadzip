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
import { announcementPath } from '../../public-housing/navigation/detailLocation'

interface DiscoveryRow {
  readonly discovery: EligibilityDiscovery
  readonly title: string
  readonly options: readonly string[]
}

/** 마이페이지에서는 몇 건만 요약하고 나머지는 공고 목록에서 이어 보게 한다. */
const VISIBLE_ROWS = 3

/** In-page guidance only; no notification subscription or delivery policy is changed. */
export function EligibilityDiscoveries({ onBrowseAnnouncements }: { readonly onBrowseAnnouncements: () => void }) {
  const { repository, revision, sessionKey, stored, processing, mode } = useConditions()
  const heading = useId()
  const key = `${sessionKey ?? ''}:${stored?.version ?? 0}:${revision}`
  const [request, setRequest] = useState({ key, attempt: 0 })
  const [page, setPage] = useState<{ key: string; rows: readonly DiscoveryRow[]; unscanned: boolean } | null>(null)
  const [error, setError] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const ready = mode === 'member' && processing?.state === 'DONE' && processing.profileVersion === stored?.version
  // 다시 조회하는 동안에는 직전 목록을 그대로 보여 줘서 화면이 깜빡이지 않게 한다.
  const current = page?.key === key ? page : null
  const activeKey = useRef(key)
  activeKey.current = key

  useEffect(() => { setRequest((value) => value.key === key ? value : { key, attempt: 0 }); setPage(null); setMessage(''); setBusy(null); setError(false) }, [key])
  useEffect(() => {
    if (!ready || request.key !== key) return
    const controller = new AbortController()
    const toRow = async (discovery: EligibilityDiscovery): Promise<DiscoveryRow | null> => {
      const view = await repository.loadComparison({ announcementId: discovery.announcementId, title: null, rentalType: null, applicationStatus: null, targets: [] }, controller.signal)
      const options = view.freshness === 'CURRENT'
        ? view.types.filter((option) => discovery.optionIds.includes(option.key) && option.status === 'ELIGIBLE').map((option) => `${option.units} · ${option.label}`) : []
      if (!options.length) return null
      // Reading a discovery must not increment the announcement view count.
      const raw = await requestPublicHousingJson(fetch, `${getApiBaseUrl()}/api/v1/announcements/${encodeURIComponent(discovery.announcementId)}`, controller.signal)
      const detail = toAnnouncementDetail(decodeAnnouncementDetailEnvelope(raw))
      return { discovery, title: detail.title ?? '모집 공고', options }
    }
    const load = async () => {
      try {
        // 보여 줄 건수보다 한 건 넘게 모이면 멈춘다. 남은 건은 '더 보기' 판단에만 쓴다.
        const rows = new Map<string, DiscoveryRow>()
        let cursor = 0
        let unscanned = true
        while (unscanned && rows.size <= VISIBLE_ROWS) {
          const result = await repository.loadDiscoveries(cursor, controller.signal)
          let start = 0
          for (; start < result.items.length && rows.size <= VISIBLE_ROWS; start += 4) {
            const batch = await Promise.all(result.items.slice(start, start + 4).map(toRow))
            for (const row of batch) if (row && !rows.has(row.discovery.rootAnnouncementId)) rows.set(row.discovery.rootAnnouncementId, row)
          }
          unscanned = start < result.items.length || (result.hasMore && result.nextCursor !== cursor)
          cursor = result.nextCursor
        }
        if (controller.signal.aborted) return
        setPage({ key, rows: [...rows.values()], unscanned })
        setError(false)
      } catch { if (!controller.signal.aborted) setError(true) }
    }
    void load()
    return () => controller.abort()
  }, [ready, key, repository, request])

  useEffect(() => {
    if (!ready) return
    const timer = setInterval(() => setRequest((value) => ({ key, attempt: value.attempt + 1 })), 30_000)
    return () => clearInterval(timer)
  }, [ready, key])

  const refresh = () => setRequest((value) => ({ key, attempt: value.attempt + 1 }))
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
  const visible = current?.rows.slice(0, VISIBLE_ROWS) ?? []
  const more = current !== null && (current.rows.length > VISIBLE_ROWS || current.unscanned)
  return <section className={styles.section} aria-labelledby={heading}>
    <header><h3 id={heading}>새롭게 지원 가능한 공고</h3><Button size="sm" variant="secondary" onClick={refresh}>새로 확인</Button></header>
    <p>정보 수정으로 새로 확인된 모집 선택지예요. 아직 확인하지 않은 안내를 보여드려요.</p>
    {current ? <>
      {!visible.length && <p>지금 표시할 새 안내가 없어요.</p>}
      <ul>{visible.map(({ discovery, title, options }) => <li key={discovery.eventId}>
        <a href={announcementPath(encodeURIComponent(discovery.announcementId))}>{title}</a>
        <ul>{options.map((option) => <li key={option}>{option}</li>)}</ul>
        <Button size="sm" variant="secondary" disabled={busy !== null} onClick={() => { void acknowledge(discovery) }}>확인했어요</Button>
      </li>)}</ul>
      {more && <div className={styles.more}>
        <p>다른 공고는 공고 목록에서 확인해 주세요.</p>
        <Button size="sm" variant="secondary" onClick={onBrowseAnnouncements}>공고 목록 보기</Button>
      </div>}
      {error && <p role="status">새 안내를 다시 확인하지 못했어요. 잠시 후 다시 시도해 주세요.</p>}
    </> : error ? <RequestError title="새 지원 가능 공고를 불러오지 못했어요" onRetry={refresh} />
      : <p role="status">새로 확인된 공고를 불러오고 있어요.</p>}
    {message && <p role="status">{message}</p>}
  </section>
}
