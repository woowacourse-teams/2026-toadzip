import { captureProductEvent, createAnalyticsId } from '../../analytics/productAnalytics'
import { useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import logo from '../../assets/brand/bok-logo.png'
import { Button } from '../../design-system/components/Button.tsx'
import { SearchGroup } from '../search/IntegratedSearch.tsx'
import { integratedSearchRepository, type IntegratedSearchRepository, type SearchResultItem } from '../search/integratedSearchRepository.ts'
import styles from './FirstVisitWelcome.module.css'
import { starterPlaces, type StarterPlace } from './starterPlaces.ts'
import { useSearchSuggestionsKeyboard } from '../search/useSearchSuggestionsKeyboard.ts'

const COMPLETED_KEY = 'toadzip:welcome-completed'
const WELCOME_LIFETIME_MS = 7 * 24 * 60 * 60 * 1000

interface Props {
  readonly onPlaceSelect: (place: StarterPlace) => void
  readonly onRegionSelect: (region: SearchResultItem) => void
  readonly repository?: IntegratedSearchRepository
}

export function FirstVisitWelcome(props: Props) {
  const [open, setOpen] = useState(() => {
    try {
      const stored: unknown = JSON.parse(localStorage.getItem(COMPLETED_KEY) ?? 'null')
      return !(typeof stored === 'object' && stored !== null
        && 'expiresAt' in stored && typeof stored.expiresAt === 'number'
        && Number.isFinite(stored.expiresAt) && stored.expiresAt > Date.now())
    } catch {
      return true
    }
  })

  function complete() {
    try {
      localStorage.setItem(COMPLETED_KEY, JSON.stringify({
        expiresAt: Date.now() + WELCOME_LIFETIME_MS,
      }))
    } catch {
      // Storage restrictions must not prevent starting exploration.
    }
    setOpen(false)
  }

  return open ? <WelcomeDialog {...props} onComplete={complete} /> : null
}

function WelcomeDialog({
  onComplete,
  onPlaceSelect,
  onRegionSelect,
  repository = integratedSearchRepository,
}: Props & { readonly onComplete: () => void }) {
  const exposureId = useRef(createAnalyticsId()).current
  const dialogRef = useRef<HTMLDialogElement>(null)
  const primaryRef = useRef<HTMLButtonElement>(null)
  const [searching, setSearching] = useState(false)
  const [query, setQuery] = useState('')
  const { inputRef, suggestionsRef, onKeyDown } = useSearchSuggestionsKeyboard(() => setQuery(''))
  const suggestionsId = useId()
  const titleId = useId()
  const descriptionId = useId()
  const normalizedQuery = query.trim().replace(/\s+/g, ' ')
  const canSearch = normalizedQuery.replaceAll(' ', '').length >= 2

  useEffect(() => {
    const dialog = dialogRef.current
    dialog?.showModal()
    primaryRef.current?.focus({ preventScroll: true })
    let cancelled = false
    queueMicrotask(() => { if (!cancelled && dialog?.open) captureProductEvent('welcome_shown', { exposure_id: exposureId }, { dedupeKey: `welcome:${exposureId}` }) })
    return () => { cancelled = true; dialog?.close() }
  }, [exposureId])

  useEffect(() => {
    if (searching) inputRef.current?.focus({ preventScroll: true })
  }, [searching, inputRef])

  function complete(method: 'region_search' | 'starter_place' | 'browse_map' | 'escape') {
    captureProductEvent('welcome_completed', { exposure_id: exposureId, method })
    onComplete()
  }

  return createPortal(
    <dialog ref={dialogRef} className={styles.dialog}
      aria-labelledby={titleId}
      aria-describedby={searching ? undefined : descriptionId}
      onCancel={(event) => { event.preventDefault(); complete('escape') }}>
      <div className={styles.content} onKeyDown={searching ? onKeyDown : undefined}>
        {searching ? (
          <>
            <header className={styles.searchHeader}>
              <h2 id={titleId}>살고 싶은 지역 검색하기</h2>
              <label className={styles.input}>
                <span className="visually-hidden">살고 싶은 지역</span>
                <input ref={inputRef} type="search" value={query}
                  autoComplete="off" aria-autocomplete="list"
                  aria-controls={canSearch ? suggestionsId : undefined}
                  placeholder="예: 잠실, 강남, 판교"
                  onChange={(event) => setQuery(event.target.value)} />
              </label>
            </header>
            {canSearch ? (
              <div className={`${styles.results} integrated-search__results`} ref={suggestionsRef} id={suggestionsId}>
                <SearchGroup key={normalizedQuery} query={normalizedQuery}
                  repository={repository} type="REGION"
                  onSelect={(region) => { onRegionSelect(region); complete('region_search') }} />
              </div>
            ) : (
              <section className={styles.examples} aria-label="예시 지역">
                <p>이 동네부터 둘러보세요</p>
                <div className={styles.places}>
                  {starterPlaces.map((place, index) => (
                    <button type="button" key={place.name}
                      onClick={() => { captureProductEvent('starter_place_selected', { starter_place_id: ['jamsil', 'gangnam', 'pangyo'][index], surface: 'welcome' }); onPlaceSelect(place); complete('starter_place') }}>
                      <strong>{place.name}<span aria-hidden="true">↗</span></strong>
                      <span>{place.detail}</span>
                    </button>
                  ))}
                </div>
              </section>
            )}
          </>
        ) : (
          <div className={styles.intro}>
            <div className={styles.brand}>
              <img src={logo} alt="BOK 공공주택 복덕방 로고" width="204" height="94" />
            </div>
            <div>
              <h2 id={titleId}>살고 싶은 동네의<br />공공임대주택을 찾아보세요.</h2>
              <p id={descriptionId}>보증금·월세부터 모집 공고까지 한곳에서.</p>
            </div>
          </div>
        )}
        <div className={styles.actions}>
          {!searching && (
            <Button ref={primaryRef} onClick={() => { captureProductEvent('welcome_search_started', { exposure_id: exposureId }); setSearching(true) }}>
              살고 싶은 지역 검색하기
            </Button>
          )}
          <button className={styles.explore} type="button" onClick={() => complete('browse_map')}>
            바로 지도 둘러보기
          </button>
        </div>
      </div>
    </dialog>, document.body,
  )
}
