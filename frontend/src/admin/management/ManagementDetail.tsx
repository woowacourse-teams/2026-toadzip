import { useUnsavedChanges } from './useUnsavedChanges'
import { ScheduleEditor } from './ScheduleEditor'
import { useEffect, useRef, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { getManagementDetail, requestManagementApi, managementResourcePath, ManagementError } from './api'
import { parseManagementDetail, type ManagementDetailData, type ManagementResource } from './managementContract'
import { announcementSections, complexSections, display, formValues, valueAt } from './fields'
import { EditFields } from './EditFields'
import { ComplexRelationsEditor } from './ComplexRelationsEditor'
import { ManagementStatus } from './ManagementStatus'
import { ChangeHistory } from './ChangeHistory'
import { SupplyEditor } from './SupplyEditor'
import { SourceUrl } from '../shared/SourceUrl'
import { AddressPicker } from '../registration/AddressPicker'

export function ManagementDetail({resource, embedded = false, detailId, onChanged, onBusyChange}: {resource:ManagementResource; embedded?: boolean; detailId?: string; onChanged?: () => void; onBusyChange?: (busy: boolean) => void}) {
  const {id: routeId = ''} = useParams()
  const id = detailId ?? routeId
  const [params] = useSearchParams()
  const candidate = params.get('returnTo') ?? ''
  const back = candidate === `/admin/${resource}` || candidate.startsWith(`/admin/${resource}?`) ? candidate : `/admin/${resource}`
  const [value, setValue] = useState<ManagementDetailData | null>(null)
  const [error, setError] = useState('')
  const [errors, setErrors] = useState<Record<string,string>>({})
  const [editing, setEditing] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const [confirmation, setConfirmation] = useState(false)
  const [notice, setNotice] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [section, setSection] = useState('info')
  const formRef = useRef<HTMLFormElement>(null)
  const sections = resource === 'complexes' ? complexSections : announcementSections
  useEffect(() => { onBusyChange?.(busy) }, [busy, onBusyChange])
  useEffect(() => {
    const controller = new AbortController();setValue(null);setError('');setEditing(false);setDirty(false);setConfirmation(false)
    if (!/^[1-9][0-9]*$/.test(id)) { setError('올바른 관리 페이지 주소가 아닙니다.');return }
    void getManagementDetail(resource,id,controller.signal).then(next => { if (!controller.signal.aborted) { setValue(next); setEditing(embedded && !next.summary.deleted) } }).catch(cause => {
      if(!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '불러오지 못했습니다.')
    })
    return () => controller.abort()
  },[resource,id,attempt,embedded])
  useUnsavedChanges(dirty)
  function saved(next:ManagementDetailData) { setValue(next);setEditing(embedded && !next.summary.deleted);setDirty(false);setErrors({});setNotice('변경사항을 저장했습니다.');onChanged?.() }
  async function trash() {
    if(!value) return
    setBusy(true);setError('')
    try {
      await requestManagementApi(`${managementResourcePath(resource)}/${id}${value.summary.deleted ? '/restore' : ''}?version=${value.data.version}`,value.summary.deleted ? 'POST' : 'DELETE')
      const next = await getManagementDetail(resource,id)
      setValue(next);setEditing(embedded && !next.summary.deleted);setDirty(false);setConfirmation(false);setNotice(value.summary.deleted ? '복구했습니다.' : '휴지통으로 이동했습니다. 필요하면 복구할 수 있습니다.');onChanged?.()
    } catch(cause) { setError(cause instanceof Error ? cause.message : '처리하지 못했습니다.') }
    finally {setBusy(false)}
  }
  return <section className="management-page management-detail">{!embedded ? <Link to={back}>← 목록으로</Link> : null}
    {error ? <div className="registration-error" role="alert"><p>{error}</p><button type="button" disabled={busy} onClick={() => {if(!dirty || window.confirm('입력한 내용을 버리고 새로 조회할까요?')) setAttempt(v => v+1)}}>새로 조회</button></div> : null}
    {!value && !error ? <p role="status">상세 정보를 불러오는 중…</p> : null}
    {notice ? <p className="registration-success" role="status">{notice}</p> : null}
    {value ? <><header className="management-heading"><div>{embedded ? <h2>{value.summary.name}</h2> : <h1>{value.summary.name}</h1>}<div className="management-summary-meta"><p>{value.summary.subtitle}</p><ManagementStatus summary={value.summary} /></div></div>
      {!editing || embedded ? <div className="admin-inline">{!value.summary.deleted && !editing ? <button className="admin-primary" onClick={() => {setEditing(true);setSection('info');setError('');setNotice('')}}>수정</button> : null}
        <button data-admin-navigation disabled={busy || dirty} className={value.summary.deleted ? '' : 'admin-danger'} onClick={() => setConfirmation(true)}>{value.summary.deleted ? '복구' : '삭제'}</button></div> : null}</header>
      {value.summary.reviewRequired ? <p className="admin-warning">수집 원천과 관리자 수정값이 다릅니다. 공식 원문을 확인한 뒤 필요한 내용을 수정해 주세요. 저장한 관리자 값은 자동 정제로 덮어쓰지 않습니다.</p> : null}
      {confirmation ? <section className="admin-confirm" role="alertdialog" aria-label={value.summary.deleted ? '복구 확인' : '삭제 확인'}>
        <h2>{value.summary.deleted ? '복구할까요?' : '휴지통으로 이동할까요?'}</h2><p><strong>{value.summary.name}</strong></p>
        <p>{value.summary.deleted ? '다시 서비스 조회 대상이 됩니다.' : '서비스 검색·상세에서 제외됩니다. 원천과 수정 이력은 유지되며 다시 복구할 수 있습니다.'}</p>
        {resource === 'complexes' ? <p>연결 공고 {value.announcements.length}건 · 주택형 {value.housingTypes.length}건. 휴지통에 없는 연결 공고가 있으면 삭제할 수 없습니다.</p> : <p>연결 공급정보 {value.supplyRows.length}건은 함께 보존됩니다.</p>}
        <div className="admin-inline"><button disabled={busy} className="admin-danger" onClick={() => void trash()}>{busy ? '처리 중…' : value.summary.deleted ? '복구 확인' : '휴지통으로 이동'}</button><button disabled={busy} onClick={() => setConfirmation(false)}>취소</button></div></section> : null}
      {!editing || embedded ? <nav className="admin-section-tabs" aria-label="상세 섹션">{[['info','기본정보'], ...(embedded && resource === 'announcements' ? [['schedules', '접수 일정']] : []), ['relations',resource === 'complexes' ? '주택형·연결 공고' : '공급정보·단지 연결'],['history','출처·수정 이력']].map(([key,label]) =>
        <button data-admin-navigation={section !== key || undefined} disabled={busy} key={key} type="button" aria-pressed={section === key} onClick={() => {if (section !== key) {setSection(key);setDirty(false)}}}>{label}</button>)}</nav> : null}
      <div className="management-detail-content" role="region" aria-label={`${resource === 'complexes' ? '단지' : '공고'} 상세 내용`} tabIndex={0}>
      {section === 'info' && !editing ? <>{sections.map(group => <section className="admin-detail-section" key={group.title}><h2>{group.title}</h2>
        <dl className={`admin-data-grid${group.fields.some(field => field.name === 'address.roadAddress') ? ' admin-location-grid' : ''}`}>{group.fields.map(field => <div key={field.name}><dt>{field.label}</dt><dd>{field.type === 'url'
          ? <SourceUrl url={valueAt(value.data,field.name)} /> : display(valueAt(value.data,field.name))}</dd></div>)}</dl></section>)}</> : null}
      {!embedded && section === 'info' && !editing && resource === 'announcements' ? <ScheduleEditor key={String(value.data.version)} value={value} id={id} onSaved={saved} onBusyChange={setBusy} /> : null}
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
      {embedded && section === 'schedules' && resource === 'announcements' ? <ScheduleEditor key={String(value.data.version)} value={value} id={id} onSaved={saved} onBusyChange={setBusy} /> : null}
      {section === 'relations' ? resource === 'complexes' ? <ComplexRelationsEditor value={value} id={id} onSaved={saved} onBusyChange={setBusy} /> : <><h2>공급정보·단지 연결</h2>{value.supplyRows.length === 0 ? <p>등록된 공급정보가 없습니다.</p> : null}
          {value.supplyRows.map(row => <SupplyEditor key={`${row.id}-${value.data.version}`} row={row} announcementId={id} version={Number(value.data.version)} deleted={value.summary.deleted} onSaved={saved} onBusyChange={setBusy} />)}</> : null}
      {section === 'history' ? <><h2>출처</h2><dl className="admin-data-grid"><div><dt>공식 원문 URL</dt><dd><SourceUrl url={value.data.originalUrl} /></dd></div><div><dt>원천 식별자</dt><dd>{value.sourceIdentifier || '기록 없음'}</dd></div></dl><p>관리자 최종 변경: {value.summary.updatedAt ? new Date(value.summary.updatedAt).toLocaleString('ko-KR') : '변경 이력 없음'}</p>
        <ChangeHistory resource={resource} id={id} version={Number(value.data.version)} /></> : null}
      </div>
    </> : null}
  </section>
}
