import { useUnsavedChanges } from './useUnsavedChanges'
import { useEffect, useState } from 'react'
import { getManagementDetail, requestManagementApi } from './api'
import { type ManagementDetailData, type ManagementValues } from './managementContract'
import { formValues, type Field } from './fields'
import styles from './ScheduleEditor.module.css'
import { confirmedSchedulesFromEvents, parseRange } from './scheduleText'
import { loadJsonWorkspace, type JsonWorkspace } from '../announcement-json/api'
import { choices } from '../announcement-pdf/review'

const rowFields: Field[] = [{name:'supplyRank',label:'공급 순위'}, {name:'state',label:'일정 상태',options:['CONFIRMED','CONDITIONAL'],required:true},
  {name:'condition',label:'접수 조건'}, {name:'startDate',label:'시작일',type:'date',required:true},{name:'endDate',label:'종료일',type:'date',required:true},
  {name:'startTime',label:'시작 시각',type:'time'},{name:'endTime',label:'종료 시각',type:'time'}]
const evidenceFields: Field[] = [{name:'sourceUrl',label:'근거 URL',type:'url',required:true},{name:'sourcePage',label:'공고문 페이지',type:'number',min:1}]

/** 입력창에 보여 줄 수 있는 값만 쓴다. 형식이 맞지 않는 외부 값은 빈 칸으로 보이고 그대로 빈 값으로 저장된다. */
function text(value: unknown, pattern?: RegExp): string {
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  if (typeof value !== 'string') return ''
  return !pattern || pattern.test(value) ? value : ''
}
const DATE = /^\d{4}-\d{2}-\d{2}$/, TIME = /^\d{2}:\d{2}(:\d{2})?$/

export function ScheduleEditor({ value, id, onSaved, onBusyChange }: {value:ManagementDetailData;id:string;onSaved:(value:ManagementDetailData)=>void;onBusyChange?:(busy:boolean)=>void}) {
  const [editing,setEditing] = useState(false)
  const [rows,setRows] = useState<ManagementValues[]>([])
  const [evidence,setEvidence] = useState({ sourceUrl: '', sourcePage: '' })
  const [pasted,setPasted] = useState('')
  const [pasteNotice,setPasteNotice] = useState('')
  const [busy,setBusy] = useState(false)
  const [error,setError] = useState('')
  // 수집·PDF 보정으로 저장된 일정. 확정 접수 일정의 초안으로 쓴다.
  const [events,setEvents] = useState<JsonWorkspace['events'] | null>(null)
  const [eventsError,setEventsError] = useState('')
  const [prefilled,setPrefilled] = useState(0)
  useEffect(() => { onBusyChange?.(busy) }, [busy, onBusyChange])
  useUnsavedChanges(editing)
  useEffect(() => {
    const controller = new AbortController()
    setEventsError('')
    void loadJsonWorkspace(id, controller.signal).then(next => { if (!controller.signal.aborted) setEvents(next.events) })
      .catch(() => { if (!controller.signal.aborted) { setEvents(null); setEventsError('수집·PDF 일정을 불러오지 못했습니다.') } })
    return () => controller.abort()
  }, [id, value.data.version])
  const drafts = events ? confirmedSchedulesFromEvents(events, value.supplyRows) : []
  const originalUrl = typeof value.data.originalUrl === 'string' && /^https?:\/\/\S+$/.test(value.data.originalUrl) ? value.data.originalUrl : ''
  // 확정한 일정이 없으면 수집·PDF 접수 일정을 공고문 URL을 근거로 바로 저장한다. 고칠 일이 있으면 저장 뒤 직접 입력에서 고친다.
  function confirmDrafts() {
    setBusy(true); setError('')
    void requestManagementApi(`/api/admin/announcements/${id}/application-schedules?version=${value.data.version}`, 'PUT',
      { schedules: drafts.map(row => ({ ...row, sourceUrl: originalUrl, sourcePage: null })) })
      .then(() => getManagementDetail('announcements', id)).then(onSaved)
      .catch(cause => setError(cause instanceof Error ? cause.message : '접수 일정을 확정하지 못했습니다.')).finally(() => setBusy(false))
  }
  const complexes = Array.from(new Map(value.supplyRows.filter(r => r.housingComplexId !== null).map(r => [r.housingComplexId,r])).values())
  function startEditing(fromEvents?: ManagementValues[]) {
    const next = fromEvents?.length ? fromEvents.map(row => ({ ...row, sourceUrl: value.data.originalUrl, sourcePage: null })) : value.schedules.length ? value.schedules : [{
      housingComplexId: null, supplyRank: null, state: 'CONFIRMED', condition: null,
      startDate: value.data.applicationStartDate, endDate: value.data.applicationEndDate,
      startTime: null, endTime: null, sourceUrl: value.data.originalUrl, sourcePage: null,
    }]
    setRows(next)
    setEvidence({ sourceUrl: text(next[0].sourceUrl), sourcePage: text(next[0].sourcePage, /^\d+$/) })
    setPasted(''); setPasteNotice(''); setError(''); setPrefilled(fromEvents?.length ?? 0)
    setEditing(true)
  }
  function update(index: number, key: string, input: string) {
    setRows(current => current.map((item,i) => i === index ? {...item,[key]: key === 'housingComplexId' ? (input ? Number(input) : null) : input === '' ? null : input} : item))
  }
  // 공고문 일정 문구를 줄마다 한 일정으로 읽어 날짜·시각을 채운다. 모자란 줄은 일정을 추가한다.
  function fillFromText() {
    const year = Number(String(value.data.applicationStartDate ?? value.data.postedDate ?? '').slice(0, 4)) || new Date().getFullYear()
    const ranges = pasted.split('\n').map(line => parseRange(line, year)).filter(range => range !== null)
    if (!ranges.length) { setPasteNotice('날짜를 찾지 못했습니다. 예: 2026.10.27(화) 10:00 ~ 10.30(금) 17:00'); return }
    setRows(current => {
      const next = [...current]
      ranges.forEach((range, index) => { next[index] = { ...(next[index] ?? {...current[0], housingComplexId:null, supplyRank:null, condition:null}), ...range } })
      return next
    })
    setPasteNotice(`일정 ${ranges.length}개의 날짜를 채웠습니다. 확인 후 저장하세요.`)
  }
  const differentPages = new Set(rows.map(row => text(row.sourcePage, /^\d+$/))).size > 1
  // 단지마다 같은 일정이 반복되므로 유형·이름·기간이 같은 일정은 한 줄로 묶는다.
  const groups = [...(events ?? []).reduce((map, event) => {
    const key = [event.data.scheduleType, event.data.name, event.data.startAt, event.data.endAt].map(String).join('|')
    const group = map.get(key) ?? { key, first: event, complexes: [] as string[] }
    if (event.complexName) group.complexes.push(event.complexName)
    return map.set(key, group)
  }, new Map<string, { key: string; first: JsonWorkspace['events'][number]; complexes: string[] }>()).values()]
  const label = (event: JsonWorkspace['events'][number]) => choices.scheduleType.find(([key]) => key === event.data.scheduleType)?.[1] ?? String(event.data.scheduleType ?? '')
  const when = (value: unknown) => typeof value === 'string' ? value.replace('T', ' ').replace(/:00$/, '').replace(' 00:00', '') : ''
  return <section className="admin-detail-section"><header className="admin-inline"><h2>확정 접수 일정</h2>{!value.summary.deleted && !editing ? <button onClick={() => startEditing()}>직접 입력</button> : null}</header>
    {!editing ? <>
      <p className={styles.hint}>접수 알림과 공개 화면의 접수 상태는 여기서 확정한 일정만 씁니다.</p>
      {!value.schedules.length ? <p>아직 확정한 접수 일정이 없습니다.</p> : value.schedules.map((row,index) => <p key={index}>{String(row.startDate)} ~ {String(row.endDate)} · {String(row.supplyRank ?? '전체 순위')} · {row.state === 'CONFIRMED' ? '확정' : '조건부'} · {String(row.condition ?? '')}</p>)}
      <section className={styles.collected} aria-labelledby="collected-schedules-title"><header className="admin-inline"><h3 id="collected-schedules-title">수집·PDF 일정 {events?.length ?? 0}개</h3>
        {drafts.length > 0 && !value.summary.deleted && (value.schedules.length || !originalUrl
          ? <button onClick={() => startEditing(drafts)}>수집 일정으로 다시 채우기</button>
          : <button className="admin-primary" disabled={busy} onClick={confirmDrafts}>{busy ? '확정하는 중…' : `접수 일정 ${drafts.length}개 확정`}</button>)}</header>
        {error && !editing ? <p role="alert" className="registration-error">{error}</p> : null}
        {eventsError ? <p role="alert">{eventsError}</p> : events === null ? <p role="status">불러오는 중…</p> : !events.length ? <p>수집되거나 PDF로 저장된 일정이 없습니다.</p>
          : <ul>{groups.map(group => <li key={group.key}><strong>{label(group.first)}</strong> {String(group.first.data.name ?? '')} · {when(group.first.data.startAt)}{group.first.data.endAt && group.first.data.endAt !== group.first.data.startAt ? ` ~ ${when(group.first.data.endAt)}` : ''}
            <span className={styles.complexes} title={group.complexes.join(', ')}> · {group.complexes.length > 1 ? `단지 ${group.complexes.length}곳` : group.complexes[0] ?? '공고 전체'}</span></li>)}</ul>}
      </section>
    </> :
      <form className={styles.form} onSubmit={event => {
        event.preventDefault()
        const formData = new FormData(event.currentTarget)
        const shared = formValues(formData, evidenceFields, {})
        const schedules = rows.map((row, index) => {
          const values = formValues(formData, rowFields.map(field => ({ ...field, name: `${index}.${field.name}` })), {})[String(index)]
          const complexId = formData.get(`${index}.housingComplexId`)
          return {
            ...row,
            ...(values && typeof values === 'object' && !Array.isArray(values) ? values : {}),
            ...shared,
            housingComplexId: complexId ? Number(complexId) : null,
          }
        })
        setBusy(true);setError('')
        void requestManagementApi(`/api/admin/announcements/${id}/application-schedules?version=${value.data.version}`,'PUT',{schedules})
          .then(() => getManagementDetail('announcements',id)).then(next => {onSaved(next);setEditing(false)})
          .catch(cause => setError(cause instanceof Error ? cause.message : '일정을 저장하지 못했습니다.')).finally(() => setBusy(false))
      }}>
        {prefilled > 0 && <p role="status" className={styles.hint}>수집·PDF 일정에서 접수 일정 {prefilled}개를 채웠습니다. 단지·기간을 확인하고 저장하세요.</p>}
        <fieldset className={styles.evidence} disabled={busy}><legend>근거 · 모든 일정에 적용</legend>
          <label>근거 URL<input name="sourceUrl" type="url" required value={evidence.sourceUrl} onChange={event => setEvidence(current => ({...current, sourceUrl: event.target.value}))} /></label>
          <label>공고문 페이지 (선택)<input name="sourcePage" type="number" min={1} value={evidence.sourcePage} onChange={event => setEvidence(current => ({...current, sourcePage: event.target.value}))} /></label>
          {differentPages && <p className={styles.hint}>일정마다 다른 페이지가 저장돼 있습니다. 저장하면 모두 이 페이지로 바뀝니다.</p>}
        </fieldset>
        <div className={styles.paste}>
          <label>공고문 일정 붙여넣기<textarea rows={2} value={pasted} placeholder="2026.10.27(화) 10:00 ~ 10.30(금) 17:00 (줄마다 일정 하나)" onChange={event => { setPasted(event.target.value); setPasteNotice('') }} /></label>
          <button type="button" disabled={busy || !pasted.trim()} onClick={fillFromText}>날짜 채우기</button>
          {pasteNotice && <p role="status" className={styles.hint}>{pasteNotice}</p>}
        </div>
        {rows.map((row,index) => <fieldset className={styles.row} disabled={busy} key={index}><legend>접수 일정 {index+1}</legend>
          <div className={styles.dates}>
            <label>시작일<input name={`${index}.startDate`} type="date" required value={text(row.startDate, DATE)} onChange={event => update(index, 'startDate', event.target.value)} /></label>
            <label>시작 시각<input name={`${index}.startTime`} type="time" value={text(row.startTime, TIME)} onChange={event => update(index, 'startTime', event.target.value)} /></label>
            <span className={styles.tilde} aria-hidden="true">~</span>
            <label>종료일<input name={`${index}.endDate`} type="date" required value={text(row.endDate, DATE)} onChange={event => update(index, 'endDate', event.target.value)} /></label>
            <label>종료 시각<input name={`${index}.endTime`} type="time" value={text(row.endTime, TIME)} onChange={event => update(index, 'endTime', event.target.value)} /></label>
            <label>일정 상태<select name={`${index}.state`} required value={text(row.state) || 'CONFIRMED'} onChange={event => update(index, 'state', event.target.value)}>
              <option value="CONFIRMED">확정</option><option value="CONDITIONAL">조건부</option></select></label>
            <button type="button" className="admin-danger" disabled={rows.length === 1} onClick={() => setRows(current => current.filter((_,i) => i !== index))}>일정 제거</button>
          </div>
          <details className={styles.more} open={Boolean(row.housingComplexId || text(row.supplyRank) || text(row.condition)) || undefined}><summary>단지·순위·조건</summary>
            <div className={styles.moreFields}>
              <label>적용 단지<select name={`${index}.housingComplexId`} value={complexes.some(r => r.housingComplexId === row.housingComplexId) ? String(row.housingComplexId) : ''} onChange={event => update(index, 'housingComplexId', event.target.value)}><option value="">공고 전체</option>
                {complexes.map(r => <option key={r.housingComplexId} value={r.housingComplexId ?? ''}>{r.housingComplexName}</option>)}</select></label>
              <label>공급 순위<input name={`${index}.supplyRank`} value={text(row.supplyRank)} onChange={event => update(index, 'supplyRank', event.target.value)} /></label>
              <label>접수 조건<input name={`${index}.condition`} value={text(row.condition)} onChange={event => update(index, 'condition', event.target.value)} /></label>
            </div>
          </details></fieldset>)}
        {error ? <p role="alert">{error}</p> : null}<div className="admin-inline"><button disabled={busy || rows.length >= 100} type="button" onClick={() => setRows(current => [...current,{...current[0],housingComplexId:null,supplyRank:null,condition:null}])}>일정 추가</button>
          <button disabled={busy} className="admin-primary">{busy ? '저장 중…' : '접수 일정 저장'}</button><button disabled={busy} type="button" onClick={() => setEditing(false)}>취소</button></div></form>}
  </section>
}
