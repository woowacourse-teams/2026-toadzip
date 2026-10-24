import { useUnsavedChanges } from './useUnsavedChanges'
import { ScheduleEditor } from './ScheduleEditor'
import { useEffect, useRef, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { getManagementDetail, requestManagementApi, managementResourcePath, ManagementError } from './api'
import { parseManagementDetail, type ManagementDetailData, type ManagementResource } from './managementContract'
import { announcementSections, complexSections, display, formValues, valueAt } from './fields'
import { EditFields } from './EditFields'
import { ComplexRelationsEditor } from './ComplexRelationsEditor'
import { ManagementStatus, PublicationType } from './ManagementStatus'
import { ChangeHistory } from './ChangeHistory'
import { SupplyCorrectionWorkbench } from '../supply/SupplyCorrectionWorkbench'
import { AnnouncementSupplyMatchingForm } from '../ingest/AnnouncementSupplyMatchingForm'
import { SourceUrl } from '../shared/SourceUrl'
import { ComplexVerificationPanel } from './ComplexVerificationPanel'
import { ComplexVerificationBadge } from './ComplexVerificationBadge'
import { AddressPicker } from '../registration/AddressPicker'
import { AnnouncementDocuments } from './AnnouncementDocuments'
import { ComplexComparisonEditor } from './ComplexComparisonEditor'
import { QualityComparisonPanel } from '../quality/QualityComparisonPanel'
import { AnnouncementDetailNavigation } from './AnnouncementDetailNavigation'

export function ManagementDetail({resource, embedded = false, detailId, onChanged, onBusyChange}: {resource:ManagementResource; embedded?: boolean; detailId?: string; onChanged?: () => void; onBusyChange?: (busy: boolean) => void}) {
  const [sourceMatchesOpen, setSourceMatchesOpen] = useState(false)
  const {id: routeId = ''} = useParams()
  const id = detailId ?? routeId
  const [params, setParams] = useSearchParams()
  const candidate = params.get('returnTo') ?? ''
  const back = candidate === `/admin/${resource}` || candidate.startsWith(`/admin/${resource}?`) ? candidate : `/admin/${resource}`
  const [value, setValue] = useState<ManagementDetailData | null>(null)
  const [error, setError] = useState('')
  const [errors, setErrors] = useState<Record<string,string>>({})
  const [editing, setEditing] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const [verificationBusy, setVerificationBusy] = useState(false)
  const [comparisonDirty, setComparisonDirty] = useState(false)
  const [reviewDirty, setReviewDirty] = useState(false)
  const [extendedDirty, setExtendedDirty] = useState(false)
  const [extendedBusy, setExtendedBusy] = useState(false)
  const [confirmation, setConfirmation] = useState(false)
  const [notice, setNotice] = useState('')
  const [attempt, setAttempt] = useState(0)
  const requestedSection = params.get('section') ?? ''
  const resolvedSection = resource === 'announcements' && requestedSection === 'verification' ? 'history' : requestedSection
  const sectionKeys = ['info', 'schedules', 'relations', 'history', ...(resource === 'announcements' ? ['documents'] : ['verification'])]
  const knownSection = sectionKeys.includes(resolvedSection)
  const [section, setSection] = useState(knownSection ? resolvedSection : 'info')
  // 주소의 섹션이 바뀌면(뒤로 가기·작업 링크) 해당 탭을 연다. 탭 이동은 아래 openSection이 주소에도 남긴다.
  useEffect(() => { setSection(knownSection ? resolvedSection : 'info') }, [knownSection, resolvedSection])
  useEffect(() => {
    if (resource !== 'announcements' || !['verification', 'json'].includes(requestedSection)) return
    setParams(current => {
      const next = new URLSearchParams(current)
      next.set('section', requestedSection === 'verification' ? 'history' : 'info')
      if (requestedSection === 'verification') next.set('comparison', 'source')
      return next
    }, { replace: true })
  }, [resource, requestedSection, setParams])
  function openSection(key: string) {
    setSection(key); setDirty(false)
    setParams(current => { const next = new URLSearchParams(current); next.set('section', key); return next }, { replace: true })
  }
  const formRef = useRef<HTMLFormElement>(null)
  const sections = resource === 'complexes' ? complexSections : announcementSections
  useEffect(() => { onBusyChange?.(busy || verificationBusy || extendedBusy) }, [busy, verificationBusy, extendedBusy, onBusyChange])
  useEffect(() => {
    const controller = new AbortController();setValue(null);setError('');setEditing(false);setDirty(false);setConfirmation(false)
    if (!/^[1-9][0-9]*$/.test(id)) { setError('올바른 관리 페이지 주소가 아닙니다.');return }
    void getManagementDetail(resource,id,controller.signal).then(next => { if (!controller.signal.aborted) { setValue(next); setEditing(false) } }).catch(cause => {
      if(!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '불러오지 못했습니다.')
    })
    return () => controller.abort()
  },[resource,id,attempt,embedded])
  useUnsavedChanges(dirty)
  function saved(next:ManagementDetailData) { setValue(next);setEditing(false);setDirty(false);setErrors({});setNotice('변경사항을 저장했습니다.');onChanged?.() }
  async function trash() {
    if(!value) return
    setBusy(true);setError('')
    try {
      await requestManagementApi(`${managementResourcePath(resource)}/${id}${value.summary.deleted ? '/restore' : ''}?version=${value.data.version}`,value.summary.deleted ? 'POST' : 'DELETE')
      const next = await getManagementDetail(resource,id)
      setValue(next);setEditing(false);setDirty(false);setConfirmation(false);setNotice(value.summary.deleted ? '복구했습니다.' : '휴지통으로 이동했습니다. 필요하면 복구할 수 있습니다.');onChanged?.()
    } catch(cause) { setError(cause instanceof Error ? cause.message : '처리하지 못했습니다.') }
    finally {setBusy(false)}
  }
  return <section className="management-page management-detail" data-resource={resource}>{!embedded ? <Link to={back}>← 목록으로</Link> : null}
    {error ? <div className="registration-error" role="alert"><p>{error}</p><button type="button" disabled={busy} onClick={() => {if(!dirty || window.confirm('입력한 내용을 버리고 새로 조회할까요?')) setAttempt(v => v+1)}}>새로 조회</button></div> : null}
    {!value && !error ? <p role="status">상세 정보를 불러오는 중…</p> : null}
    {notice ? <p className="registration-success" role="status">{notice}</p> : null}
    {value ? <><header className="management-heading"><div>{embedded ? <h2>{value.summary.name}</h2> : <h1>{value.summary.name}</h1>}<div className="management-summary-meta"><p>{value.summary.subtitle}</p>{value.summary.announcement?.publicationType !== 'ORIGINAL' && <PublicationType summary={value.summary} />}<ManagementStatus summary={value.summary} />
      {resource === 'complexes' && section !== 'verification' && value.summary.complex?.verificationStatus ? <ComplexVerificationBadge status={value.summary.complex.verificationStatus} count={value.summary.complex.reviewedFieldCount} /> : null}</div></div>
      {!editing || embedded ? <div className="admin-inline">{resource === 'announcements' && !value.summary.deleted && !editing && <Link className="admin-tonal" to={`/admin/announcements/${id}/pdf-correction?${params}`} aria-disabled={busy || verificationBusy || extendedBusy} onClick={event => { if (busy || verificationBusy || extendedBusy) event.preventDefault() }}>PDF로 보정</Link>}{!value.summary.deleted && !editing ? <button type="button" data-admin-navigation disabled={busy || verificationBusy || extendedBusy} className="admin-tonal" onClick={() => {setEditing(true);setSection('info');setError('');setNotice('')}}>{resource === 'announcements' ? '기본정보 수정' : '수정'}</button> : null}
        <button type="button" data-admin-navigation disabled={busy || verificationBusy || extendedBusy || dirty} className={value.summary.deleted ? '' : 'admin-danger'} onClick={() => setConfirmation(true)}>{value.summary.deleted ? '복구' : '삭제'}</button></div> : null}</header>
      {value.summary.reviewRequired ? <p className="admin-warning">수집 원천과 관리자 수정값이 다릅니다. 공식 원문을 확인한 뒤 필요한 내용을 수정해 주세요. 저장한 관리자 값은 자동 정제로 덮어쓰지 않습니다.</p> : null}
      {confirmation ? <section className="admin-confirm" role="alertdialog" aria-label={value.summary.deleted ? '복구 확인' : '삭제 확인'}>
        <h2>{value.summary.deleted ? '복구할까요?' : '휴지통으로 이동할까요?'}</h2><p><strong>{value.summary.name}</strong></p>
        <p>{value.summary.deleted ? '다시 서비스 조회 대상이 됩니다.' : '서비스 검색·상세에서 제외됩니다. 원천과 수정 이력은 유지되며 다시 복구할 수 있습니다.'}</p>
        {resource === 'complexes' ? <p>연결 공고 {value.announcements.length}건 · 주택형 {value.housingTypes.length}건. 휴지통에 없는 연결 공고가 있으면 삭제할 수 없습니다.</p> : <p>연결 공급정보 {value.supplyRows.length}건은 함께 보존됩니다.</p>}
        <div className="admin-inline"><button disabled={busy} className="admin-danger" onClick={() => void trash()}>{busy ? '처리 중…' : value.summary.deleted ? '복구 확인' : '휴지통으로 이동'}</button><button disabled={busy} onClick={() => setConfirmation(false)}>취소</button></div></section> : null}
      {!editing || embedded ? resource === 'announcements' ? <AnnouncementDetailNavigation section={section} disabled={busy || verificationBusy || extendedBusy} onSelect={openSection} /> : <nav className="admin-section-tabs" aria-label="상세 섹션">{[['info','기본정보'], ['verification', '데이터 검증'], ['relations', '주택형·연결 공고'], ['history','출처·수정 이력']].map(([key,label]) =>
        <button data-admin-navigation={section !== key || undefined} disabled={busy || verificationBusy || extendedBusy} key={key} type="button" aria-pressed={section === key} onClick={() => {if (section !== key) openSection(key)}}>{label}</button>)}</nav> : null}
      <div className="management-detail-content" role="region" aria-label={`${resource === 'complexes' ? '단지' : '공고'} 상세 내용`} tabIndex={0}>
      {section === 'documents' && resource === 'announcements' ? <AnnouncementDocuments key={id} id={id} detail={value} /> : null}
      {section === 'verification' && (!editing || embedded) && resource === 'complexes' ? <>
        {reviewDirty ? <p className="admin-warning">작성 중인 검토 기록을 저장하거나 취소한 뒤 값을 수정할 수 있습니다.</p> : null}
        <fieldset className="admin-verification-group" disabled={reviewDirty || extendedDirty}>
          <ComplexComparisonEditor id={id} version={Number(value.data.version)} deleted={value.summary.deleted}
            onChanged={() => { setNotice('수정값과 비교 근거를 저장했습니다.'); setAttempt(current => current + 1); onChanged?.() }} onBusyChange={setVerificationBusy} onDirtyChange={setComparisonDirty} />
        </fieldset>
        <details className="admin-verification-review"><summary>확인 범위·검토 기록</summary>
        {comparisonDirty ? <p className="admin-warning">작성 중인 수정사항을 저장하거나 취소한 뒤 확인 기록을 남겨 주세요.</p> : null}
        <fieldset className="admin-verification-group" disabled={comparisonDirty || extendedDirty}><ComplexVerificationPanel key={id} reviewOnly onDirtyChange={setReviewDirty}
        id={id} version={Number(value.data.version)} deleted={value.summary.deleted} announcements={value.announcements}
        onBusyChange={setVerificationBusy}
        onReviewed={() => { setNotice('검토 기록을 저장했습니다.'); setAttempt(current => current + 1); onChanged?.() }} /></fieldset></details>
        {value.housingTypes.length ? <details className="admin-verification-review"><summary>주택형 원천 비교·수정</summary><fieldset className="admin-verification-group" disabled={comparisonDirty || reviewDirty}>
          <QualityComparisonPanel key={`${resource}:${id}`} resource={resource} id={id} detail={value} onDirtyChange={setExtendedDirty} onBusyChange={setExtendedBusy}
            onChanged={() => { setAttempt(current => current + 1); onChanged?.() }} />
        </fieldset></details> : null}</> : null}
      {section === 'info' && !editing ? <>{sections.map(group => <section className="admin-detail-section" key={group.title}><h2>{group.title}</h2>
        <dl className={`admin-data-grid${group.fields.some(field => field.name === 'address.roadAddress') ? ' admin-location-grid' : ''}`}>{group.fields.map(field => <div key={field.name}><dt>{field.label}</dt><dd>{field.type === 'url'
          ? <SourceUrl url={valueAt(value.data,field.name)} /> : display(valueAt(value.data,field.name))}</dd></div>)}</dl></section>)}</> : null}
      {editing && section === 'info' ? <form ref={formRef} key={String(value.data.version)} onChange={() => setDirty(true)} onSubmit={event => {
        event.preventDefault();setBusy(true);setError('');setErrors({})
        const body = formValues(event.currentTarget,sections.flatMap(group => group.fields),value.data)
        void requestManagementApi(`${managementResourcePath(resource)}/${id}`,'PUT',body).then(result => saved(parseManagementDetail(result))).catch(cause => {
          setError(cause instanceof Error ? cause.message : '저장하지 못했습니다.');if(cause instanceof ManagementError) setErrors(cause.fields)
        }).finally(() => setBusy(false))
      }}>
        {value.scheduleReviewed ? <p className="admin-warning">확인된 접수 기간입니다. 세부 접수 일정 관리에서 변경해 주세요.</p> : null}
        {sections.map(group => <fieldset disabled={busy} key={group.title}><legend>{group.title}</legend>
          {group.title === '주소·위치' ? <AddressPicker onSelect={address => {
            for (const [key, fieldValue] of Object.entries(address)) {
              const input = formRef.current?.elements.namedItem(`address.${key}`)
              if (input instanceof HTMLInputElement && (typeof fieldValue === 'string' || typeof fieldValue === 'number')) input.value = String(fieldValue)
            }
            setDirty(true)
          }} /> : null}
          <EditFields fields={group.fields} data={value.data} errors={errors} scheduleReviewed={value.scheduleReviewed} /></fieldset>)}
        <div className="admin-save-bar"><button className="admin-primary" disabled={busy}>{busy ? '저장 중…' : '변경사항 저장'}</button><button type="button" disabled={busy} onClick={() => {if(!dirty || window.confirm('변경사항을 버릴까요?')) {if (embedded) setAttempt(current => current + 1); else setEditing(false);setDirty(false);setError('')}}}>{embedded ? '변경 취소' : '취소'}</button></div></form> : null}
      {section === 'schedules' && resource === 'announcements' ? <ScheduleEditor key={String(value.data.version)} value={value} id={id} onSaved={saved} onBusyChange={setBusy} /> : null}
      {section === 'relations' ? resource === 'complexes' ? <ComplexRelationsEditor value={value} id={id} onSaved={saved} onBusyChange={setBusy} /> : <><h2>공급정보·단지 연결</h2>
          {value.supplyRows.some(row => row.sharedRecruitment) && <details onToggle={event => setSourceMatchesOpen(event.currentTarget.open)}>
            <summary>원천별 공급행 보정</summary>
            {sourceMatchesOpen && <AnnouncementSupplyMatchingForm identifier={value.sourceIdentifier} disabled={busy || value.summary.deleted}
              onBusy={setBusy} onApplied={() => { void getManagementDetail(resource, id).then(saved).catch(cause => setError(String(cause))) }} />}
          </details>}
          {!value.summary.deleted && <SupplyCorrectionWorkbench key={id} id={id} sourceUrl={String(value.data.originalUrl ?? '')}
            onChanged={() => { void getManagementDetail(resource, id).then(saved).catch(cause => setError(String(cause))) }}
            onBusyChange={setExtendedBusy} onDirtyChange={setExtendedDirty} />}</> : null}
      {section === 'history' ? <><h2>출처</h2><dl className="admin-data-grid"><div className="admin-data-wide"><dt>공식 원문 URL</dt><dd><SourceUrl url={value.data.originalUrl} /></dd></div><div><dt>원천 식별자</dt><dd>{value.sourceIdentifier || '기록 없음'}</dd></div></dl><p>관리자 최종 변경: {value.summary.updatedAt ? new Date(value.summary.updatedAt).toLocaleString('ko-KR') : '변경 이력 없음'}</p>
        {resource === 'announcements' && <details className="admin-verification-review" open={params.get('comparison') === 'source' || requestedSection === 'verification' || undefined}><summary>수집 원천과 비교</summary>
          <QualityComparisonPanel key={`${resource}:${id}`} resource={resource} id={id} detail={value} onDirtyChange={setExtendedDirty} onBusyChange={setExtendedBusy}
            onChanged={() => { setAttempt(current => current + 1); onChanged?.() }} />
        </details>}
        <ChangeHistory resource={resource} id={id} version={Number(value.data.version)} /></> : null}
      </div>
    </> : null}
  </section>
}
