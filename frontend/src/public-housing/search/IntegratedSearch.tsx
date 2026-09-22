import { useEffect, useId, useRef, useState } from 'react'
import { DetailCloseButton } from '../components/DetailPrimitives.tsx'
import {
  integratedSearchRepository,
  type IntegratedSearchRepository,
  type IntegratedSearchResponse,
  type SearchResultItem,
  type SearchType,
} from './integratedSearchRepository.ts'
import styles from './IntegratedSearch.module.css'
import { findRegionBoundaryMetadata } from '../regions/regionBoundaryCatalog.ts'

interface GroupState {
  readonly items: readonly SearchResultItem[]
  readonly hasNext: boolean
  readonly totalCount: number | null
  readonly kind: 'loading' | 'ready' | 'error'
  readonly error: string | null
}

const searchTypes: readonly SearchType[] = ['REGION', 'ANNOUNCEMENT', 'COMPLEX']

export interface IntegratedSearchProps {
  readonly onActiveChange?: (active: boolean) => void
  readonly onSelect: (item: SearchResultItem) => void
  readonly repository?: IntegratedSearchRepository
}

export function IntegratedSearch({
  onActiveChange,
  onSelect,
  repository = integratedSearchRepository,
}: IntegratedSearchProps) {
  const [query, setQuery] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)
  const normalizedQuery = normalizeQuery(query)
  const active = normalizedQuery.replaceAll(' ', '').length >= 2

  useEffect(() => {
    onActiveChange?.(active)
  }, [active, onActiveChange])

  return (
    <section className={`integrated-search${active ? ' is-active' : ''}`} aria-label="통합 검색">
      <div className={styles.top}>
        <label className="integrated-search__input">
          <span className="visually-hidden">지역, 단지, 공고 검색</span>
          <svg
            className="integrated-search__icon"
            aria-hidden="true"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
          >
            <circle cx="10.5" cy="10.5" r="7" />
            <path d="m16 16 5 5" />
          </svg>
          <input
            ref={inputRef}
            type="search"
            value={query}
            placeholder="지역, 단지, 공고 검색"
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
        {active && (
          <div className={styles.header}>
            <h2>검색결과</h2>
            <DetailCloseButton
              label="검색결과 닫기"
              onClose={() => {
                setQuery('')
                inputRef.current?.focus({ preventScroll: true })
              }}
            />
          </div>
        )}
      </div>
      {active && (
        <div className="integrated-search__body">
          <div className="integrated-search__results" key={normalizedQuery}>
            {searchTypes.map((type) => (
              <SearchGroup
                key={type}
                onSelect={onSelect}
                query={normalizedQuery}
                repository={repository}
                type={type}
              />
            ))}
          </div>
        </div>
      )}
    </section>
  )
}

function SearchGroup({
  onSelect,
  query,
  repository,
  type,
}: {
  readonly onSelect: (item: SearchResultItem) => void
  readonly query: string
  readonly repository: IntegratedSearchRepository
  readonly type: SearchType
}) {
  const [page, setPage] = useState(0)
  const [retryRevision, setRetryRevision] = useState(0)
  const [state, setState] = useState<GroupState>({
    error: null, hasNext: false, items: [], kind: 'loading', totalCount: null,
  })
  const headingId = useId()
  const label = typeLabel(type)

  useEffect(() => {
    const controller = new AbortController()
    setState((current) => ({ ...current, error: null, kind: 'loading' }))
    const timer = window.setTimeout(() => {
      repository.search(query, false, page, controller.signal, type)
        .then((response) => {
          // Some repositories can finish after abort; never apply their stale result.
          if (controller.signal.aborted) {
            return
          }
          const failure = response.failures.find((candidate) => candidate.type === type)
          if (failure) {
            setState((current) => ({ ...current, error: failure.message, kind: 'error' }))
            return
          }
          setState((current) => ({
            error: null,
            hasNext: response.hasNext,
            totalCount: response.totalCount,
            items: page === 0
              ? responseItems(response, type)
              : appendUnique(current.items, responseItems(response, type)),
            kind: 'ready',
          }))
        })
        .catch(() => {
          if (!controller.signal.aborted) {
            setState((current) => ({
              ...current,
              error: `${label} 검색 결과를 불러오지 못했습니다.`,
              kind: 'error',
            }))
          }
        })
    }, page === 0 && retryRevision === 0 ? 200 : 0)
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [label, page, query, repository, retryRevision, type])

  return (
    <section className={styles.group} aria-labelledby={headingId} aria-busy={state.kind === 'loading'}>
      <h3 className={styles.groupHeading} id={headingId}>{label}</h3>
      <ul>
        {state.items.map((item) => {
          const unavailable = item.type === 'REGION'
            && (item.latitude === null || item.longitude === null)
            && !findRegionBoundaryMetadata(item.regionCode ?? item.id)
          return (
            <li key={`${item.type}-${item.id}`}>
              <button
                type="button"
                disabled={unavailable}
                className={styles.result}
                onClick={() => onSelect(item)}
              >
                <strong>{item.title}</strong>
                {item.subtitle && <span>{item.subtitle}</span>}
                {item.publishedAt && <time dateTime={item.publishedAt}>{item.publishedAt}</time>}
                {item.applicationStatus && <span>{statusLabel(item.applicationStatus)}</span>}
                {unavailable && <span className={styles.unavailable}>위치 정보 준비 중</span>}
              </button>
            </li>
          )
        })}
      </ul>
      {state.kind === 'loading' && (
        <p className={styles.message} role="status">
          {label} {page === 0 ? '검색 중입니다.' : '결과를 더 불러오는 중입니다.'}
        </p>
      )}
      {state.kind === 'ready' && state.items.length === 0 && (
        <p className={styles.message} role="status">{label} 검색 결과가 없습니다.</p>
      )}
      {state.kind === 'error' && (
        <div className="integrated-search__partial-error" role="alert">
          <span>{state.error}</span>
          <button type="button" onClick={() => setRetryRevision((current) => current + 1)}>
            {label} 다시 시도
          </button>
        </div>
      )}
      {state.hasNext && state.kind !== 'error' && (
        <button
          className={`housing-results__more ${styles.more}`}
          type="button"
          aria-label={`${label} 더보기, 현재 ${state.items.length}개, 전체 ${state.totalCount === null ? '확인 중' : `${state.totalCount}개`}`}
          disabled={state.kind === 'loading'}
          onClick={() => setPage((current) => current + 1)}
        >
          <span>{state.kind === 'loading' ? '불러오는 중' : '더보기'}</span>
          <span className="housing-results__progress">
            ({state.items.length.toLocaleString('ko-KR')} | {state.totalCount?.toLocaleString('ko-KR') ?? '—'})
          </span>
          <svg aria-hidden="true" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="m3 4.5 3 3 3-3" />
          </svg>
        </button>
      )}
      {page === 100 && state.kind === 'ready' && (
        <p className={styles.message}>더 많은 결과를 찾으려면 검색어를 구체적으로 입력해 주세요.</p>
      )}
    </section>
  )
}

function responseItems(response: IntegratedSearchResponse, type: SearchType) {
  return {
    ANNOUNCEMENT: response.announcements,
    COMPLEX: response.complexes,
    REGION: response.regions,
  }[type]
}

function appendUnique(current: readonly SearchResultItem[], next: readonly SearchResultItem[]) {
  const seen = new Set(current.map((item) => item.id))
  return [...current, ...next.filter((item) => !seen.has(item.id))]
}

function normalizeQuery(value: string) {
  return value.trim().replace(/\s+/g, ' ')
}

function typeLabel(type: SearchType) {
  return { ANNOUNCEMENT: '공고', COMPLEX: '단지', REGION: '지역' }[type]
}

function statusLabel(status: string) {
  return {
    APPLYING: '접수 중',
    BEFORE_APPLICATION: '접수 예정',
    CANCELLED: '취소',
    CLOSED: '접수 종료',
  }[status] ?? status
}
