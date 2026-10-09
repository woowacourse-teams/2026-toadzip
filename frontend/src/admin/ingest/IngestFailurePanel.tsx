import { Link, useSearchParams } from 'react-router'
import { useEffect, useRef, useState } from 'react'
import { collectionCause, failureTarget } from './failurePresentation'
import { failureCategories, type FailureCategory } from './api'
import { getFailureReviews, type FailureDomain, type FailureReview, type FailureReviewPageData, type FailureReviewStatus } from './failureReviewApi'
import { ListPagination } from '../management/ListPagination'
import { StoredDataTable } from '../shared/StoredDataTable'
import styles from './IngestFailurePanel.module.css'
import { AnnouncementSupplyMatchingForm } from './AnnouncementSupplyMatchingForm'

type Props = {
  domain?: FailureDomain; initialCategory?: FailureCategory; executionId: string | null; refreshToken?: number
  onRetry?: () => void; retryDisabled?: boolean; runMessage?: string; runError?: string
}

export function IngestFailurePanel({ domain: selectedDomain, initialCategory, executionId, refreshToken = 0,
  onRetry, retryDisabled = false, runMessage = '', runError = '' }: Props) {
  const domain = selectedDomain ?? (initialCategory === 'announcement' || initialCategory === 'enrichment' ? 'announcement' : 'complex')
  const [params, setParams] = useSearchParams()
  const allowed: (FailureCategory | 'all')[] = domain === 'complex' ? ['all','collection','complex','household'] : ['all','collection','announcement','enrichment']
  const rawCategory = params.get('category') ?? initialCategory ?? 'all'
  const category = allowed.includes(rawCategory as FailureCategory | 'all') ? rawCategory as FailureCategory | 'all' : 'all'
  const rawStatus = params.get('status') ?? 'PENDING'
  const status = ['PENDING','RESOLVED','ALL'].includes(rawStatus) ? rawStatus as FailureReviewStatus : 'PENDING'
  const pageText = params.get('page') ?? '0'
  const page = Number(pageText)
  const validQuery = allowed.includes(rawCategory as FailureCategory | 'all') && rawStatus === status
    && /^(0|[1-9][0-9]*)$/.test(pageText) && Number.isSafeInteger(page) && page <= 2_147_483_647
  const filterKey = `${domain}/${category}/${status}`
  const [revision, setRevision] = useState(0)
  const query = `${filterKey}/${page}/${revision}/${refreshToken}`
  const [loaded, setLoaded] = useState<{ query: string; filters: string; data: FailureReviewPageData } | null>(null)
  const [failure, setFailure] = useState<{ query: string; message: string } | null>(null)
  const matching = loaded?.filters === filterKey ? loaded.data : null
  const error = failure?.query === query ? failure.message : ''
  const pending = validQuery && !error && loaded?.query !== query
  const dialog = useRef<HTMLDialogElement>(null)
  const [selected, setSelected] = useState<FailureReview | null>(null)
  const [matchingOpen, setMatchingOpen] = useState(false)
  useEffect(() => { setMatchingOpen(false) }, [selected?.id])

  useEffect(() => {
    if (!validQuery) return
    const controller = new AbortController()
    void getFailureReviews(domain, category, status, page, controller.signal).then(data => {
      if (!controller.signal.aborted) { setLoaded({query,filters:filterKey,data}); setFailure(null) }
    }).catch(cause => {
      if (!controller.signal.aborted) setFailure({query,message:cause instanceof Error ? cause.message : '오류 목록을 불러오지 못했습니다.'})
    })
    return () => controller.abort()
  }, [domain, category, status, page, validQuery, query, filterKey])

  useEffect(() => { dialog.current?.close(); setSelected(null) }, [filterKey, page])

  useEffect(() => {
    if (!selected || loaded?.query !== query) return
    const current = loaded.data.items.find(row => row.id === selected.id && row.category === selected.category)
    if (!current) { dialog.current?.close(); setSelected(null) }
    else if (current !== selected) setSelected(current)
  }, [loaded, query, selected])

  function filter(key: string, value: string) {
    setParams(current => { current.set(key,value); current.delete('page'); return current })
  }
  function move(destination: number) {
    setParams(current => { current.set('page',String(destination)); return current })
  }
  const label = domain === 'complex' ? '단지' : '공고'
  return <section className={styles.panel} aria-label={`${label} 오류 목록`}>
    <div className={styles.filters}>
      <label>문제 단계<select value={category} onChange={event => filter('category',event.target.value)}>
        {allowed.map(value => <option key={value} value={value}>{value === 'all' ? '전체 단계' : failureCategories[value].label}</option>)}
      </select></label>
      <label>처리 상태<select value={status} onChange={event => filter('status',event.target.value)}>
        <option value="PENDING">미해결</option><option value="RESOLVED">해결됨</option><option value="ALL">전체 이력·건너뜀 포함</option>
      </select></label>
      <button type="button" disabled={pending} onClick={() => setRevision(value => value + 1)}>목록 새로고침</button>
    </div>
    {!validQuery ? <p role="alert">조회 조건이 올바르지 않습니다. <button type="button" onClick={() => setParams({domain})}>조회 조건 초기화</button></p> : null}
    {error ? <p role="alert">{error} <button type="button" onClick={() => setRevision(value => value + 1)}>다시 조회</button></p> : null}
    <div className={styles.results} aria-busy={pending}>
      <div className={styles.summary}>
        <span>{matching ? `총 ${matching.totalElements.toLocaleString()}건` : '오류 목록 조회'}
          {matching && executionId ? ` · 선택한 실행에서 발생 ${matching.items.filter(row => row.executionId === executionId).length}건` : ''}</span>
        <span>최근 발생순</span>
      </div>
      {pending ? <p role="status" className={styles.loading}>오류 목록을 불러오는 중입니다.</p> : null}
      <div className={styles.scroll} tabIndex={0} role="region" aria-label="실패 목록 가로 스크롤">
        {matching && matching.items.length === 0 ? <div className={styles.empty}><h3>{matching.totalElements === 0 ? '해당 상태의 오류가 없습니다.' : '이 페이지에 오류가 없습니다.'}</h3>
          <p>{status === 'PENDING' ? '해결됨 또는 전체 이력에서 이전 처리 기록을 확인할 수 있습니다.' : '문제 단계나 처리 상태를 바꿔 확인해 주세요.'}</p>
          {matching.totalElements > 0 ? <button type="button" onClick={() => move(0)}>첫 페이지로</button> : null}</div> : null}
        {matching && matching.items.length > 0 ? <table className={styles.table}>
          <caption className="sr-only">{label} 오류 목록 · {matching.page + 1}페이지</caption>
          <colgroup><col className={styles.targetColumn}/><col style={{width:260}}/><col style={{width:230}}/><col style={{width:140}}/><col className={styles.actionColumn}/></colgroup>
          <thead><tr><th scope="col">대상·작업</th><th scope="col">문제 원인·다음 행동</th><th scope="col">현재 서비스 데이터</th><th scope="col">처리 상태</th><th scope="col">확인·처리</th></tr></thead>
          <tbody>{matching.items.map(row => {
            const target = reviewTarget(row)
            return <tr key={`${row.category}-${row.id}`}>
              <th scope="row" title={target.title}>{row.product ? <Link to={`/admin/${row.product.resourceType}/${row.product.id}`}>{target.title}</Link> : <span>{target.title}</span>}
                <small>{failureCategories[row.category].label}{target.context ? ` · ${target.context}` : ''}</small>
                <time dateTime={row.occurredAt}>{formatTime(row.occurredAt)}</time></th>
              <td><strong>{reasonLabel(row)}</strong><p className={styles.clamp} title={row.detail}>{row.detail}</p><small>{nextAction(row)}</small></td>
              <td><ProductSummary row={row}/></td>
              <td><span className={styles.status} data-status={row.status}>{statusLabel(row.status)}</span><small>발생 {row.occurrenceCount}회{row.recurrenceCount ? ` · 재발 ${row.recurrenceCount}회` : ''}</small>
                {row.status === 'RESOLVED' && row.lastResolvedAt ? <time dateTime={row.lastResolvedAt}>{formatTime(row.lastResolvedAt)}</time> : null}</td>
              <td><button type="button" aria-label={`확인·처리: ${target.title}`} onClick={() => {setSelected(row);dialog.current?.showModal()}}>확인·처리</button></td>
            </tr>
          })}</tbody>
        </table> : null}
      </div>
      <ListPagination label="실패 목록 페이지" page={matching?.page ?? page} totalPages={matching?.totalPages ?? 0} onMove={move} disabled={pending || Boolean(error) || !validQuery}/>
    </div>
    <dialog ref={dialog} className={styles.dialog} aria-labelledby="failure-detail-title" onClose={() => setSelected(null)}>
      <header><h2 id="failure-detail-title">문제 확인·처리</h2><button type="button" onClick={() => dialog.current?.close()}>닫기</button></header>
      {selected ? <div className={styles.dialogBody}>
        <h3>{reviewTarget(selected).title}</h3><p><span className={styles.status} data-status={selected.status}>{statusLabel(selected.status)}</span> · {failureCategories[selected.category].label}</p>
        <section className={styles.detailSection}><h3>무엇이 문제인가요?</h3><strong>{reasonLabel(selected)}</strong><p>{selected.detail}</p><p>{nextAction(selected)}</p></section>
        <section className={styles.detailSection}><h3>현재 서비스에서는</h3><ProductSummary row={selected}/><ProductDetails row={selected}/>
          <p className={styles.hint}>현재 저장된 데이터 기준입니다. 오류의 해결 여부와 별개이며, 실제 목록은 지역·지도 범위·검색 조건에 따라 달라집니다.</p></section>
        <section className={styles.detailSection}><h3>다음 행동</h3><div className={styles.workflow}>
          {selected.product ? <Link to={`/admin/${selected.product.resourceType}/${selected.product.id}`}>{label} 확인·수정</Link> : null}
          {selected.product?.publicDetailAvailable ? <Link to={`/?${selected.product.resourceType === 'complexes' ? 'complexId' : 'announcementId'}=${selected.product.id}`} target="_blank" rel="noreferrer">사용자 상세 보기</Link> : null}
          <Link to={sourceLocation(selected)}>{selected.category === 'enrichment' ? 'LH 상세 원천 확인' : '원천 데이터 확인'}</Link>
          {selected.category === 'enrichment' ? <Link to={sourceLocation(selected, true)}>LH 공급 원천 확인</Link> : null}
          {selected.category === 'collection' ? <Link to={`/admin/ingest#${domain === 'complex' ? 'complex' : 'announcement'}-pipelines`}>API 키 입력·원천 수집</Link>
            : selected.status === 'PENDING' && onRetry ? <button type="button" disabled={retryDisabled} onClick={onRetry}>{label} 정제 다시 실행</button> : null}
        </div>
          {selected.productLinkStatus === 'UNKNOWN' && typeof selected.raw.complexName === 'string' ? <p><Link to={`/admin/complexes?keyword=${encodeURIComponent(selected.raw.complexName)}`}>단지명으로 후보 검색</Link></p> : null}
          {runMessage ? <p role="status">{runMessage}</p> : null}{runError ? <p role="alert">{runError}</p> : null}
          <p className={styles.hint}>값을 수정한 뒤 재처리 결과를 확인하세요. 등록 데이터가 존재한다는 이유만으로 오류를 해결 처리하지 않습니다.</p></section>
        {selected.category === 'announcement' && typeof selected.raw.sourceAnnouncementIdentifier === 'string'
          && (/COMPLEX|HOUSING_TYPE/.test(selected.reason) || selected.detail.includes('수동 매칭')) && <section>
            <button type="button" aria-expanded={matchingOpen} disabled={retryDisabled}
              onClick={() => setMatchingOpen(value => !value)}>단지·주택형 매칭</button>
            {matchingOpen && <AnnouncementSupplyMatchingForm identifier={selected.raw.sourceAnnouncementIdentifier}
              disabled={retryDisabled} />}
          </section>}
        <details className={styles.raw}><summary>실패 기록 원문·실행 정보</summary><StoredDataTable data={selected.raw} label="실패 기록 원문"/></details>
      </div> : null}
    </dialog>
  </section>
}

function ProductSummary({row}: {row:FailureReview}) {
  if (!row.product) return <><strong>{row.productLinkStatus === 'NOT_FOUND' ? '등록 데이터 없음' : '대상 연결 확인 필요'}</strong>
    <small>{row.productLinkStatus === 'NOT_FOUND' ? '이 원천 식별자에 연결된 서비스 데이터가 없습니다.' : '오류 기록만으로 현재 등록·노출 상태를 확정할 수 없습니다.'}</small></>
  const product=row.product
  return <><strong>{product.deleted ? '휴지통에 있는 데이터' : '등록 데이터 있음'}</strong>
    <small>{product.publicListEligible ? '기본 목록 조회 조건 충족' : product.listExclusionReasons.map(reason => exclusionLabels[reason] ?? reason).join(' · ')}</small>
    <small>{product.resourceType === 'complexes' ? `주택형 ${product.housingTypeCount ?? 0}개` : `공급행 ${product.supplyRowCount ?? 0}개 · 연결 단지 ${product.linkedComplexCount ?? 0}개`}</small></>
}

function ProductDetails({row}: {row:FailureReview}) {
  const product=row.product
  if (!product) return null
  return <div className={styles.facts}>
    <p>사용자 상세: {product.publicDetailAvailable ? '조회 가능' : '조회 불가'}</p>
    {product.resourceType === 'complexes' ? <>
      <p>지도 좌표: {product.hasCoordinates ? '있음' : '없음'}</p>
      {product.housingTypeCount === 0 ? <p>주택형 정보를 제공할 수 없습니다. 원천의 주택형 값과 정제 오류를 확인해 주세요.</p> : null}
    </> : <>
      {product.supplyRowCount === 0 ? <p>공급정보가 없어 단지·주택형 비교를 제공할 수 없습니다.</p> : null}
      <p>검토된 접수 일정 {product.applicationScheduleCount ?? 0}개 · 첨부파일 {product.attachmentCount ?? 0}개</p>
      {product.applicationScheduleReviewed ? product.applicationScheduleCount === 0 ? <p>검토된 접수 일정이 없습니다. 공고문 확인이 필요합니다.</p> : null
        : product.applicationStartDate && product.applicationEndDate ? <p>기존 접수 기간: {product.applicationStartDate} ~ {product.applicationEndDate}</p> : <p>기존 접수 기간이 없습니다.</p>}
    </>}
  </div>
}

const exclusionLabels: Record<string,string> = {
  ADMIN_DELETED:'휴지통 보관으로 목록 제외', MISSING_COORDINATES:'지도 좌표가 없어 지도 목록 제외',
  NOT_LISTABLE_PUBLICATION_TYPE:'공고 종류상 기본 목록 제외', MISSING_PREVIOUS_ANNOUNCEMENT:'정정 공고의 이전 공고 연결 없음',
  HAS_SUCCESSOR:'후속 공고가 있어 기본 목록 제외',
}
function statusLabel(status:string) { return status === 'RESOLVED' ? '해결됨' : status === 'SKIPPED' ? '건너뜀' : '미해결' }
function formatTime(value:string) {
  const date=new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR',{timeZone:'Asia/Seoul',hour12:false})
}
function reasonLabel(row:FailureReview) {
  if (row.category === 'collection') return collectionCause(row).title
  const labels:Record<string,string> = {
    COMPLEX_NOT_FOUND:'연결할 단지를 찾지 못함', AMBIGUOUS_COMPLEX:'일치하는 단지가 여러 개',
    MISSING_REQUIRED_VALUE:'필수 원천값 누락', INVALID_VALUE:'원천 값 확인 필요', INVALID_SOURCE:'원천 데이터 형식 오류',
    CONFLICTING_SOURCE_VALUE:'원천 값이 서로 충돌함', GEOCODING_ERROR:'주소 좌표 변환 실패',
    DUPLICATE_TARGET_COMPLEX:'같은 단지에 여러 원천이 연결됨', PREVIOUS_ANNOUNCEMENT_NOT_FOUND:'이전 공고를 찾지 못함',
    CYCLIC_ANNOUNCEMENT_REVISION:'이전 공고 참조가 순환함', ANNOUNCEMENT_NOT_FOUND:'연결할 공고를 찾지 못함',
    LH_COLLECTION_LINK_NOT_FOUND:'LH 수집 연결 없음', LH_COLLECTION_LINK_MISMATCH:'LH 수집 연결 불일치',
    LH_COLLECTION_REQUEST_UNSUPPORTED:'지원하지 않는 LH 수집 요청', PAN_ID_NOT_FOUND:'LH 공고 번호 누락',
    HOUSING_TYPE_NOT_FOUND:'연결할 주택형을 찾지 못함', AMBIGUOUS_HOUSING_TYPE:'일치하는 주택형이 여러 개',
    LH_DETAIL_SOURCE_NOT_FOUND:'LH 상세 원천 없음',
    LH_SUPPLY_SOURCE_NOT_FOUND:'LH 공급 원천 없음', UNSUPPORTED_SUPPLY_TYPE:'지원하지 않는 공급 유형',
  }
  return labels[row.reason] ?? row.reason
}
function nextAction(row:FailureReview) {
  if (row.status === 'RESOLVED') return '해결된 기록입니다. 다시 발생하면 미해결로 표시됩니다.'
  if (row.status === 'SKIPPED') return '건너뛴 사유와 원천 요청 조건을 확인하세요.'
  if (row.category === 'collection') return collectionCause(row).action
  if (/GEOCODING/.test(row.reason)) return '단지 주소·좌표와 주소 원천을 확인한 뒤 정제하세요.'
  if (/HOUSING_TYPE/.test(row.reason)) return '단지의 주택형과 원천의 면적·공급 유형을 비교한 뒤 정제하세요.'
  if (/COMPLEX/.test(row.reason)) return '단지 등록·주소·공급 유형과 원천 연결을 확인한 뒤 정제하세요.'
  if (/PREVIOUS|CYCLIC/.test(row.reason)) return '이전·후속 공고 연결을 확인한 뒤 정제하세요.'
  if (/LH_.*SOURCE|LH_COLLECTION/.test(row.reason)) return '연결된 LH 원천을 다시 수집한 뒤 정제하세요.'
  return '원천 값과 등록 데이터를 비교하고, 수정하거나 원천을 갱신한 뒤 정제하세요.'
}
function sourceLocation(row:FailureReview, supply = false) {
  const category=row.category === 'collection' ? row.source ?? 'MYHOME_COMPLEX'
    : row.category === 'enrichment' ? supply ? 'LH_ANNOUNCEMENT_SUPPLY' : 'LH_ANNOUNCEMENT_DETAIL'
    : ({complex:'MYHOME_COMPLEX',household:'LH_LEASE_CATALOG',announcement:'MYHOME_ANNOUNCEMENT'} as const)[row.category]
  const keyword=row.category === 'enrichment' ? typeof row.raw.panId === 'string' ? row.raw.panId : ''
    : typeof row.raw.sourceAnnouncementIdentifier === 'string' ? row.raw.sourceAnnouncementIdentifier
    : typeof row.raw.sourceComplexIdentifier === 'string' ? row.raw.sourceComplexIdentifier.split(':')[0]
    : typeof row.raw.complexName === 'string' ? row.raw.complexName : ''
  const params=new URLSearchParams({category})
  if (keyword) params.set('keyword',keyword.slice(0,200))
  return `/admin/sources?${params}`
}
function reviewTarget(row:FailureReview) {
  return row.category === 'collection' ? failureTarget(row) : {title:row.target,context:null,source:null}
}
