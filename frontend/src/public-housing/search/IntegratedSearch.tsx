import { useEffect, useRef, useState } from 'react'
import {
  integratedSearchRepository,
  type IntegratedSearchRepository,
  type IntegratedSearchResponse,
  type SearchResultItem,
  type SearchType,
} from './integratedSearchRepository.ts'

type SearchState =
  | { readonly kind: 'before' }
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly response: IntegratedSearchResponse }
  | { readonly kind: 'error' }

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
  const [preview, setPreview] = useState(true)
  const [page, setPage] = useState(0)
  const [retryRevision, setRetryRevision] = useState(0)
  const [state, setState] = useState<SearchState>({ kind: 'before' })
  const requestRevision = useRef(0)
  const normalizedQuery = normalizeQuery(query)
  const active = normalizedQuery.replaceAll(' ', '').length >= 2

  useEffect(() => {
    onActiveChange?.(active)
  }, [active, onActiveChange])

  useEffect(() => {
    const revision = requestRevision.current + 1
    requestRevision.current = revision
    if (!active) {
      setState({ kind: 'before' })
      return
    }
    const controller = new AbortController()
    setState({ kind: 'loading' })
    const timer = window.setTimeout(() => {
      repository.search(normalizedQuery, preview, page, controller.signal)
        .then((response) => {
          if (requestRevision.current === revision) {
            setState({ kind: 'ready', response })
          }
        })
        .catch((error: unknown) => {
          if (!isAbortError(error) && requestRevision.current === revision) {
            setState({ kind: 'error' })
          }
        })
    }, 200)
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [active, normalizedQuery, page, preview, repository, retryRevision])

  return (
    <section className={`integrated-search${active ? ' is-active' : ''}`} aria-label="통합 검색">
      <label className="integrated-search__input">
        <span className="visually-hidden">공고, 단지, 지역 검색</span>
        <input
          type="search"
          value={query}
          placeholder="공고, 단지, 지역 검색"
          onChange={(event) => {
            setQuery(event.target.value)
            setPage(0)
            setPreview(true)
          }}
        />
      </label>
      <SearchContent
        state={state}
        preview={preview}
        onSelect={onSelect}
        onRetry={() => setRetryRevision((current) => current + 1)}
      />
      {state.kind === 'ready' && preview && state.response.hasNext && (
        <button type="button" onClick={() => { setPreview(false); setPage(0) }}>
          전체 결과 보기
        </button>
      )}
      {state.kind === 'ready' && !preview && (
        <nav className="integrated-search__pagination" aria-label="검색 결과 페이지">
          <button
            type="button"
            disabled={page === 0}
            onClick={() => setPage((current) => Math.max(0, current - 1))}
          >
            이전
          </button>
          <span>{page + 1}페이지</span>
          <button
            type="button"
            disabled={!state.response.hasNext}
            onClick={() => setPage((current) => current + 1)}
          >
            다음
          </button>
        </nav>
      )}
    </section>
  )
}

function SearchContent({
  onRetry,
  onSelect,
  preview,
  state,
}: {
  readonly onRetry: () => void
  readonly onSelect: (item: SearchResultItem) => void
  readonly preview: boolean
  readonly state: SearchState
}) {
  if (state.kind === 'before') {
    return <p className="integrated-search__hint">두 글자 이상 입력해 주세요.</p>
  }
  if (state.kind === 'loading') {
    return <p role="status">검색 중입니다.</p>
  }
  if (state.kind === 'error') {
    return <SearchError title="검색 결과를 불러오지 못했습니다." onRetry={onRetry} />
  }
  const { response } = state
  const resultCount = response.announcements.length
    + response.complexes.length
    + response.regions.length
  if (resultCount === 0 && response.failures.length === 3) {
    return <SearchError title="전체 검색에 실패했습니다." onRetry={onRetry} />
  }
  return (
    <div className="integrated-search__results" data-mode={preview ? 'preview' : 'all'}>
      {resultCount === 0 && <p role="status">검색 결과가 없습니다.</p>}
      <SearchGroup
        title="공고"
        items={response.announcements}
        onSelect={onSelect}
      />
      <SearchGroup
        title="단지"
        items={response.complexes}
        onSelect={onSelect}
      />
      <SearchGroup
        title="지역"
        items={response.regions}
        onSelect={onSelect}
      />
      {response.failures.map((failure) => (
        <div className="integrated-search__partial-error" key={failure.type} role="alert">
          <span>{failure.message}</span>
          <button type="button" onClick={onRetry}>{typeLabel(failure.type)} 다시 시도</button>
        </div>
      ))}
    </div>
  )
}

function SearchGroup({
  items,
  onSelect,
  title,
}: {
  readonly items: readonly SearchResultItem[]
  readonly onSelect: (item: SearchResultItem) => void
  readonly title: string
}) {
  if (items.length === 0) {
    return null
  }
  return (
    <section aria-labelledby={`search-${title}`}>
      <h2 id={`search-${title}`}>{title}</h2>
      <ul>
        {items.map((item) => (
          <li key={`${item.type}-${item.id}`}>
            <button type="button" onClick={() => onSelect(item)}>
              <strong>{item.title}</strong>
              {item.subtitle && <span>{item.subtitle}</span>}
              {item.publishedAt && <time dateTime={item.publishedAt}>{item.publishedAt}</time>}
              {item.applicationStatus && <span>{statusLabel(item.applicationStatus)}</span>}
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}

function SearchError({ title, onRetry }: { readonly title: string; readonly onRetry: () => void }) {
  return (
    <div role="alert">
      <span>{title}</span>
      <button type="button" onClick={onRetry}>다시 시도</button>
    </div>
  )
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

function isAbortError(error: unknown) {
  return typeof error === 'object' && error !== null && 'name' in error && error.name === 'AbortError'
}
