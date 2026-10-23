import { Link, useSearchParams } from 'react-router'
import { useEffect, useRef, useState } from 'react'
import { collectionCause, failureTarget } from './failurePresentation'
import { failureCategories, type FailureCategory } from './api'
import { getFailureReview, getFailureReviews, type FailureDomain, type FailureReview, type FailureReviewPageData, type FailureReviewStatus } from './failureReviewApi'
import { ListPagination } from '../management/ListPagination'
import { StoredDataTable } from '../shared/StoredDataTable'
import styles from './IngestFailurePanel.module.css'
import { AnnouncementSupplyMatchingForm } from './AnnouncementSupplyMatchingForm'
import { LhSupplyLinkEditor } from './LhSupplyLinkEditor'
import { failureReturnUrl } from './failureNavigation'
import { FailureRetryButton } from './FailureRetryButton'
import { FailureSourceRecovery } from './FailureSourceRecovery'

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
  const keyword = params.get('keyword') ?? ''
  const validQuery = allowed.includes(rawCategory as FailureCategory | 'all') && rawStatus === status
    && /^(0|[1-9][0-9]*)$/.test(pageText) && Number.isSafeInteger(page) && page <= 2_147_483_647
    && keyword.length <= 200
  const filterKey = JSON.stringify([domain, category, status, keyword, executionId])
  const [revision, setRevision] = useState(0)
  const query = `${filterKey}/${page}/${revision}/${refreshToken}`
  const [loaded, setLoaded] = useState<{ query: string; filters: string; data: FailureReviewPageData } | null>(null)
  const [failure, setFailure] = useState<{ query: string; message: string } | null>(null)
  const matching = loaded?.filters === filterKey ? loaded.data : null
  const error = failure?.query === query ? failure.message : ''
  const pending = validQuery && !error && loaded?.query !== query
  const dialog = useRef<HTMLDialogElement>(null)
  const selectionKey = params.get('failure') ?? ''
  const [selectionCategory, selectionIdText] = selectionKey.split(':')
  const selectionId = Number(selectionIdText)
  const validSelection = ['collection', 'complex', 'household', 'announcement', 'enrichment'].includes(selectionCategory)
    && allowed.includes(selectionCategory as FailureCategory)
    && /^[1-9][0-9]*$/.test(selectionIdText ?? '') && Number.isSafeInteger(selectionId)
    && selectionKey === `${selectionCategory}:${selectionIdText}`
  const detailQuery = `${selectionKey}/${revision}/${refreshToken}`
  const [detail, setDetail] = useState<{key: string; query: string; row: FailureReview; checkedAt: string} | null>(null)
  const [detailFailure, setDetailFailure] = useState<{query: string; message: string} | null>(null)
  const selected = detail?.key === selectionKey ? detail.row : null
  const detailError = detailFailure?.query === detailQuery ? detailFailure.message : ''
  const detailPending = validSelection && !detailError && detail?.query !== detailQuery
  const [actionBusy, setActionBusy] = useState(false)
  const resultHeading = useRef<HTMLHeadingElement>(null)
  const showResult = useRef(params.get('result') === '1')
  const returnParams = new URLSearchParams(params)
  returnParams.set('domain', domain)
  returnParams.set('result', '1')
  const returnTo = `/admin/failures?${returnParams}`
  const follow = (path: string, row?: FailureReview) => {
    if (!row) return failureReturnUrl(path, returnTo)
    const next = new URLSearchParams(returnParams)
    next.set('failure', `${row.category}:${row.id}`)
    return failureReturnUrl(path, `/admin/failures?${next}`)
  }
  const [matchingOpen, setMatchingOpen] = useState(false)
  const pdfGroups = new Map<number, FailureReview[]>()
  for (const row of matching?.items ?? []) {
    if (!canCorrectPdf(row)) continue
    const announcementId = row.product!.id
    pdfGroups.set(announcementId, [...pdfGroups.get(announcementId) ?? [], row])
  }
  const [lhLinkOpen, setLhLinkOpen] = useState(false)
  useEffect(() => { setMatchingOpen(false); setLhLinkOpen(false); setActionBusy(false) }, [selectionKey])

  useEffect(() => {
    if (!validQuery) return
    const controller = new AbortController()
    void getFailureReviews(domain, category, status, page, controller.signal, {keyword, executionId}).then(data => {
      if (!controller.signal.aborted) { setLoaded({query,filters:filterKey,data}); setFailure(null) }
    }).catch(cause => {
      if (!controller.signal.aborted) setFailure({query,message:cause instanceof Error ? cause.message : '오류 목록을 불러오지 못했습니다.'})
    })
    return () => controller.abort()
  }, [domain, category, status, page, validQuery, query, filterKey, keyword, executionId])

  useEffect(() => {
    if (!validSelection) { if (dialog.current?.open) dialog.current.close(); return }
    if (!dialog.current?.open) dialog.current?.showModal()
    const controller = new AbortController()
    void getFailureReview(selectionCategory as FailureCategory, selectionId, controller.signal).then(row => {
      if (!controller.signal.aborted) {
        setDetail({key: selectionKey, query: detailQuery, row, checkedAt: new Date().toISOString()})
        setDetailFailure(null)
      }
    }).catch(cause => {
      if (!controller.signal.aborted) setDetailFailure({query: detailQuery,
        message: cause instanceof Error ? cause.message : '오류의 최신 상태를 확인하지 못했습니다.'})
    })
    return () => controller.abort()
  }, [selectionKey, selectionCategory, selectionId, validSelection, detailQuery])

  useEffect(() => {
    if (detail?.query === detailQuery && showResult.current) {
      resultHeading.current?.focus()
      resultHeading.current?.scrollIntoView?.({block: 'start'})
      showResult.current = false
    }
  }, [detail, detailQuery])

  function processed() {
    showResult.current = true
    setRevision(value => value + 1)
  }
  function closeSelection() {
    if (!params.has('failure')) return
    setParams(current => { current.delete('failure'); current.delete('result'); return current }, {replace: true})
  }
  function openSelection(row: FailureReview) {
    const key = `${row.category}:${row.id}`
    setDetail({key, query: '', row, checkedAt: ''})
    setParams(current => { current.set('failure', key); current.set('domain', domain); return current })
  }

  function filter(key: string, value: string) {
    setParams(current => { current.set(key,value); current.delete('page'); current.delete('failure'); current.delete('result'); return current })
  }
  function move(destination: number) {
    setParams(current => { current.set('page',String(destination)); return current })
  }
  const label = domain === 'complex' ? '단지' : '공고'
  const actionDisabled = retryDisabled || actionBusy || detailPending || Boolean(detailError)
  return <section className={styles.panel} aria-label={`${label} 오류 목록`}>
    <form key={keyword} className={styles.search} onSubmit={event => {
      event.preventDefault()
      filter('keyword', String(new FormData(event.currentTarget).get('keyword') ?? '').trim())
    }}>
      <label>이름·원천 식별자<input name="keyword" defaultValue={keyword} maxLength={200} placeholder="단지명, 공고명, 마이홈 ID 또는 LH 공고 번호" /></label>
      <button type="submit">오류 검색</button>
      {keyword && <button type="button" onClick={() => filter('keyword', '')}>검색 초기화</button>}
    </form>
    {executionId && <div className={styles.executionFilter}>
      <p>선택한 실행에서 마지막으로 발생한 오류만 표시합니다. <span>{executionId}</span></p>
      <button type="button" onClick={() => setParams(current => { current.delete('executionId'); current.delete('page'); return current })}>실행 필터 해제</button>
    </div>}
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
      {!pending && !error && pdfGroups.size > 0 && <section className={styles.pdfGroups} aria-label="PDF로 보정할 공고">
        <h3>이 페이지의 주택형 대응 오류</h3><p>같은 공고의 주택형 대응 오류를 묶었습니다. PDF 보정 후 정제를 다시 실행하면 이 오류 때문에 보류된 LH 보강도 함께 다시 판정됩니다.</p>
        {[...pdfGroups.entries()].map(([announcementId, rows]) => <div key={announcementId}><strong>{rows[0].product!.name}</strong><Link to={follow(`/admin/announcements/${announcementId}/pdf-correction?${pdfFailureParams(rows)}`, rows[0])}>PDF로 보정 · {rows.length}건</Link></div>)}
      </section>}
      <div className={styles.summary}>
        <span>{matching ? `총 ${matching.totalElements.toLocaleString()}건` : '오류 목록 조회'}
          {matching && executionId ? ' · 실행 필터 적용' : ''}</span>
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
              <th scope="row" title={target.title}>{row.product ? <Link to={follow(`/admin/${row.product.resourceType}/${row.product.id}`, row)}>{target.title}</Link> : <span>{target.title}</span>}
                <small>{failureCategories[row.category].label}{target.context ? ` · ${target.context}` : ''}</small>
                <time dateTime={row.occurredAt}>{formatTime(row.occurredAt)}</time></th>
              <td><strong>{reasonLabel(row)}</strong><p className={styles.clamp} title={row.detail}>{row.detail}</p><small>{nextAction(row)}</small></td>
              <td><ProductSummary row={row}/></td>
              <td><span className={styles.status} data-status={row.status}>{statusLabel(row.status)}</span><small>발생 {row.occurrenceCount}회{row.recurrenceCount ? ` · 재발 ${row.recurrenceCount}회` : ''}</small>
                {row.status === 'RESOLVED' && row.lastResolvedAt ? <time dateTime={row.lastResolvedAt}>{formatTime(row.lastResolvedAt)}</time> : null}</td>
              <td><button type="button" aria-label={`확인·처리: ${target.title}`} onClick={() => openSelection(row)}>확인·처리</button></td>
            </tr>
          })}</tbody>
        </table> : null}
      </div>
      <ListPagination label="실패 목록 페이지" page={matching?.page ?? page} totalPages={matching?.totalPages ?? 0} onMove={move} disabled={pending || Boolean(error) || !validQuery}/>
    </div>
    {selectionKey && !validSelection && <p role="alert">오류 상세 주소가 올바르지 않습니다. <button type="button" onClick={closeSelection}>선택 해제</button></p>}
    <dialog ref={dialog} className={styles.dialog} aria-labelledby="failure-detail-title" onClose={closeSelection}
      onCancel={event => { if (actionBusy) event.preventDefault() }}>
      <header><h2 id="failure-detail-title">문제 확인·처리</h2><button type="button" data-admin-navigation disabled={actionBusy} onClick={() => dialog.current?.close()}>닫기</button></header>
      {detailPending && <p className={styles.detailNotice} role="status">오류와 서비스 데이터의 최신 상태를 확인하는 중입니다.</p>}
      {detailError && <div className={styles.detailNotice} role="alert"><p>{detailError}</p><p>이전 표시값을 최신 처리 결과로 판단하지 마세요.</p><button type="button" onClick={processed}>상세 다시 조회</button></div>}
      {selected ? <div className={styles.dialogBody}>
        <h3>{reviewTarget(selected).title}</h3><p><span className={styles.status} data-status={selected.status}>{statusLabel(selected.status)}</span> · {failureCategories[selected.category].label}</p>
        <section className={styles.detailSection}><h3>1. 원인과 근거 확인</h3><strong>{reasonLabel(selected)}</strong><p>{selected.detail}</p><p>{nextAction(selected)}</p>
          <div className={styles.workflow}>
            <Link to={follow(sourceLocation(selected))}>{selected.category === 'enrichment' ? 'LH 상세 원천 확인' : '원천 데이터 확인'}</Link>
            {selected.category === 'enrichment' && <Link to={follow(sourceLocation(selected, true))}>LH 공급 원천 확인</Link>}
          </div>
          {selected.category === 'enrichment' && !selected.raw.panId && <p className={styles.hint}>오류에 LH 공고 번호가 없어 원천 목록 전체로 이동합니다. 마이홈 공고의 원문에서 LH 연결을 먼저 확인하세요.</p>}
        </section>
        <section className={styles.detailSection}><h3>2. 필요한 조치</h3><div className={styles.workflow}>
          {selected.product ? <Link to={follow(`/admin/${selected.product.resourceType}/${selected.product.id}`)}>{label} 확인·수정</Link> : null}
          {canCorrectPdf(selected) && !actionDisabled && <Link to={follow(`/admin/announcements/${selected.product!.id}/pdf-correction`)}>PDF로 보정</Link>}
          <RecoveryLinks row={selected} follow={follow}/>
          {selected.category === 'collection' ? <Link to={follow(`/admin/ingest#${domain === 'complex' ? 'complex' : 'announcement'}-pipelines`)}>API 키 입력·원천 수집</Link>
            : selected.status === 'PENDING' && onRetry ? <FailureRetryButton label={label} disabled={actionDisabled}
              onRetry={() => { showResult.current = true; onRetry() }}/> : null}
        </div>
          {selected.category !== 'collection' && selected.status === 'PENDING' && onRetry ? <p id="failure-retry-scope" className={styles.hint}>
            수정한 뒤 이 화면으로 돌아와 정제를 다시 실행하세요. 현재 저장된 {label} 원천 전체가 대상이며, 다른 오류와 이미 처리한 데이터도 함께 정제합니다.
          </p> : null}
          {selected.category !== 'collection' && runMessage ? <p role="status">{runMessage}</p> : null}{runError ? <p role="alert">{runError}</p> : null}
          <FailureSourceRecovery key={selectionKey} row={selected} disabled={actionDisabled}
            onProcessed={() => setRevision(value => value + 1)} onBusy={setActionBusy}/>
        {(selected.category === 'announcement' || selected.category === 'enrichment') && typeof selected.raw.sourceAnnouncementIdentifier === 'string'
          && (/COMPLEX|HOUSING_TYPE|MANUAL_MATCH|LH_SUPPLY_LINK/.test(selected.reason) || selected.detail.includes('수동 매칭')) && <section>
            <button type="button" aria-expanded={matchingOpen} disabled={actionDisabled}
              onClick={() => setMatchingOpen(value => !value)}>단지·주택형 매칭</button>
            {selected.category === 'enrichment' && <button type="button" aria-expanded={lhLinkOpen} disabled={actionDisabled}
              onClick={() => setLhLinkOpen(value => !value)}>LH 공급 원본 연결</button>}
            {matchingOpen && <AnnouncementSupplyMatchingForm identifier={selected.raw.sourceAnnouncementIdentifier}
              disabled={retryDisabled || detailPending || Boolean(detailError)} onApplied={processed} onBusy={setActionBusy} />}
            {selected.category === 'enrichment' && lhLinkOpen && <LhSupplyLinkEditor identifier={selected.raw.sourceAnnouncementIdentifier}
              disabled={retryDisabled || detailPending || Boolean(detailError)} onBusy={setActionBusy} />}
          </section>}
        </section>
        <section className={styles.detailSection} aria-labelledby="failure-result-title">
          <h3 id="failure-result-title" ref={resultHeading} tabIndex={-1}>3. 처리 결과 확인</h3>
          <button type="button" disabled={detailPending || actionBusy} onClick={processed}>처리 결과 다시 확인</button>
          {!detailPending && !detailError && detail?.checkedAt && <p className={styles.hint}>조회 시각: {formatTime(detail.checkedAt)}</p>}
          <p>오류 기록: <strong>{statusLabel(selected.status)}</strong>{selected.lastResolvedAt ? ` · ${formatTime(selected.lastResolvedAt)}` : ''}</p>
          <p className={styles.hint}>실행 완료와 이 오류의 해결 여부는 다릅니다. 해결됨은 재처리 기록이며 공식 근거에 대한 관리자 검토 완료를 뜻하지 않습니다.</p>
          <ProductSummary row={selected}/><ProductDetails row={selected}/>
          <div className={styles.workflow}>
            {selected.product?.publicDetailAvailable && <Link to={`/?${selected.product.resourceType === 'complexes' ? 'complexId' : 'announcementId'}=${selected.product.id}`} target="_blank" rel="noreferrer">사용자 상세 보기</Link>}
            <button type="button" disabled={actionBusy} onClick={() => { setRevision(value => value + 1); dialog.current?.close() }}>목록으로 돌아가기</button>
          </div>
          <p className={styles.hint}>현재 저장된 데이터 기준입니다. 실제 목록은 지역·지도 범위·검색 조건에 따라 달라집니다.</p>
        </section>
        <details className={styles.raw}><summary>실패 기록 원문·실행 정보</summary><StoredDataTable data={selected.raw} label="실패 기록 원문"/></details>
      </div> : null}
    </dialog>
  </section>
}

function canCorrectPdf(row: FailureReview) {
  return (row.category === 'announcement' || row.category === 'enrichment') && row.status === 'PENDING'
    && ['AMBIGUOUS_HOUSING_TYPE', 'HOUSING_TYPE_NOT_FOUND'].includes(row.reason)
    && row.product?.resourceType === 'announcements' && !row.product.deleted
}

function RecoveryLinks({row, follow}: {row:FailureReview; follow: (path: string) => string}) {
  if (row.category === 'collection') return null
  const complexName = typeof row.raw.complexName === 'string' ? row.raw.complexName.trim() : ''
  const needsComplexReview = /COMPLEX|HOUSING_TYPE/.test(row.reason)
    || (row.productLinkStatus === 'UNKNOWN' && complexName !== '')
  return <>
    {/GEOCODING/.test(row.reason) ? <Link to={follow('/admin/locations')}>좌표 ZIP 업로드·단지 정제</Link> : null}
    {needsComplexReview && row.product?.resourceType !== 'complexes' ? <Link
      to={follow(complexName ? `/admin/complexes?keyword=${encodeURIComponent(complexName)}` : '/admin/complexes')}>
      {complexName ? '단지명으로 후보 검색' : '단지·주택형 확인'}
    </Link> : null}
  </>
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
// PDF 보정 화면이 고칠 공급행을 보여 줄 수 있게 오류마다 사유와 원천 공급행 키를 넘긴다.
function pdfFailureParams(rows: FailureReview[]) {
  const params = new URLSearchParams({ failureCount: String(rows.length) })
  rows.slice(0, 10).forEach(row => params.append('failureRow', `${row.reason}|${row.sourceKey}`))
  return params.toString()
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
    MANUAL_MATCH_REVIEW_REQUIRED:'저장한 매칭의 원천·주택형 재확인 필요',
    LH_SUPPLY_LINK_REVIEW_REQUIRED:'저장한 LH 공급 연결의 원천 재확인 필요',
    LH_DETAIL_SOURCE_NOT_FOUND:'LH 상세 원천 없음',
    LH_SUPPLY_SOURCE_NOT_FOUND:'LH 공급 원천 없음', UNSUPPORTED_SUPPLY_TYPE:'지원하지 않는 공급 유형',
  }
  return labels[row.reason] ?? row.reason
}
function nextAction(row:FailureReview) {
  if (row.status === 'RESOLVED') return '해결된 기록입니다. 다시 발생하면 미해결로 표시됩니다.'
  if (row.status === 'SKIPPED') return '건너뛴 사유와 원천 요청 조건을 확인하세요.'
  if (row.category === 'collection') return collectionCause(row).action
  if (row.reason === 'MANUAL_MATCH_REVIEW_REQUIRED') return '저장한 매칭의 근거와 현재 원천·주택형을 다시 비교하세요. 연결을 다시 선택해 근거와 함께 저장하거나 저장한 선택을 해제할 수 있습니다.'
  if (/GEOCODING/.test(row.reason)) return '단지 주소와 좌표 원천을 확인하세요. 좌표 ZIP을 업로드해도 일치하는 주소나 좌표가 없으면 별도 확인이 필요합니다.'
  if (/HOUSING_TYPE/.test(row.reason)) return row.product?.resourceType === 'announcements'
    ? '공고문으로 확인하려면 위의 PDF로 보정을 사용하세요. 단지 주택형과 원천의 면적·공급 유형을 비교해도 됩니다. 고친 뒤 정제하세요.'
    : '단지의 주택형과 원천의 면적·공급 유형을 비교한 뒤 정제하세요.'
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
