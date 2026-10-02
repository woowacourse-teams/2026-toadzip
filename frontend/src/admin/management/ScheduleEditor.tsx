import { useUnsavedChanges } from './useUnsavedChanges'
import { useState } from 'react'
import { getManagementDetail, requestManagementApi } from './api'
import { type ManagementDetailData, type ManagementValues } from './managementContract'
import { EditFields } from './EditFields'
import { formValues, type Field } from './fields'
const fields: Field[] = [{name:'supplyRank',label:'공급 순위'}, {name:'state',label:'일정 상태',options:['CONFIRMED','CONDITIONAL'],required:true},
  {name:'condition',label:'접수 조건'}, {name:'startDate',label:'시작일',type:'date',required:true},{name:'endDate',label:'종료일',type:'date',required:true},
  {name:'startTime',label:'시작 시각',type:'time'},{name:'endTime',label:'종료 시각',type:'time'},
  {name:'sourceUrl',label:'근거 URL',type:'url',required:true},{name:'sourcePage',label:'공고문 페이지',type:'number',min:1,required:true}]
export function ScheduleEditor({ value, id, onSaved }: {value:ManagementDetailData;id:string;onSaved:(value:ManagementDetailData)=>void}) {
  const [editing,setEditing] = useState(false)
  const [rows,setRows] = useState<ManagementValues[]>(value.schedules.length ? value.schedules : [{housingComplexId:null,supplyRank:null,state:'CONFIRMED',condition:null,
    startDate:value.data.applicationStartDate,endDate:value.data.applicationEndDate,startTime:null,endTime:null,sourceUrl:value.data.originalUrl,sourcePage:null}])
  const [busy,setBusy] = useState(false)
  const [error,setError] = useState('')
  useUnsavedChanges(editing)
  return <section className="admin-detail-section"><header className="admin-inline"><h2>세부 접수 일정</h2>{!value.summary.deleted && !editing ? <button onClick={() => setEditing(true)}>접수 일정 관리</button> : null}</header>
    {!editing ? <>{!value.schedules.length ? <p>아직 공고문으로 확인한 세부 일정이 없습니다.</p> : value.schedules.map((row,index) => <p key={index}>{String(row.startDate)} ~ {String(row.endDate)} · {String(row.supplyRank ?? '전체 순위')} · {row.state === 'CONFIRMED' ? '확정' : '조건부'} · {String(row.condition ?? '')}</p>)}</> :
      <form onSubmit={event => {
        event.preventDefault();const form = event.currentTarget
        const schedules = rows.map((row,index) => {
          const extracted = formValues(form,fields.map(field => ({...field,name:`${index}.${field.name}`})),{})
          const values = extracted[String(index)]
          const complex = new FormData(form).get(`${index}.housingComplexId`)
          return {...row,...(values && typeof values === 'object' && !Array.isArray(values) ? values : {}),housingComplexId:complex ? Number(complex) : null}
        })
        setBusy(true);setError('')
        void requestManagementApi(`/api/admin/announcements/${id}/application-schedules?version=${value.data.version}`,'PUT',{schedules})
          .then(() => getManagementDetail('announcements',id)).then(next => {onSaved(next);setEditing(false)})
          .catch(cause => setError(cause instanceof Error ? cause.message : '일정을 저장하지 못했습니다.')).finally(() => setBusy(false))
      }}><p>전체 일정을 함께 저장합니다. 공고문 근거와 페이지를 입력해 주세요.</p>
        {rows.map((row,index) => <fieldset disabled={busy} key={index}><legend>접수 일정 {index+1}</legend>
          <label>적용 단지<select name={`${index}.housingComplexId`} value={String(row.housingComplexId ?? '')} onChange={event => setRows(current => current.map((item,i) => i === index ? {...item,housingComplexId:event.target.value ? Number(event.target.value) : null} : item))}><option value="">공고 전체</option>
            {Array.from(new Map(value.supplyRows.filter(r => r.housingComplexId !== null).map(r => [r.housingComplexId,r])).values()).map(r => <option key={r.housingComplexId} value={r.housingComplexId ?? ''}>{r.housingComplexName}</option>)}</select></label>
          <EditFields fields={fields.map(field => ({...field,name:`${index}.${field.name}`}))} data={{[String(index)]:row}} errors={{}} onValueChange={(name,text) => {
            const key = name.split('.')[1]
            setRows(current => current.map((item,i) => i === index ? {...item,[key]:text === '' ? null : key === 'sourcePage' ? Number(text) : text} : item))
          }} />
          <button type="button" disabled={rows.length === 1} onClick={() => setRows(current => current.filter((_,i) => i !== index))}>일정 제거</button></fieldset>)}
        {error ? <p role="alert">{error}</p> : null}<div className="admin-inline"><button disabled={busy || rows.length >= 100} type="button" onClick={() => setRows(current => [...current,{...current[0],housingComplexId:null,supplyRank:null}])}>일정 추가</button>
          <button disabled={busy} className="admin-primary">{busy ? '저장 중…' : '접수 일정 저장'}</button><button disabled={busy} type="button" onClick={() => setEditing(false)}>취소</button></div></form>}
  </section>
}
