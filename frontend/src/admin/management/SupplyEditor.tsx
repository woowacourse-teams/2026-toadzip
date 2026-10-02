import { useUnsavedChanges } from './useUnsavedChanges'
import { useState } from 'react'
import { Link } from 'react-router'
import { ComplexPicker } from './ComplexPicker'
import { EditFields } from './EditFields'
import { getManagementDetail, requestManagementApi, managementResourcePath, ManagementError } from './api'
import { parseManagementDetail, type ManagementDetailData, type ManagementSupplyRow, type ManagementHousingType } from './managementContract'
import { display, formValues, supplyFields } from './fields'

export function SupplyEditor({ row, announcementId, version, deleted, onSaved }: {
  row: ManagementSupplyRow; announcementId: string; version: number; deleted: boolean; onSaved: (value: ManagementDetailData) => void
}) {
  const [editing, setEditing] = useState(false)
  const [complexId, setComplexId] = useState(row.housingComplexId)
  const [complexName, setComplexName] = useState(row.housingComplexName)
  const [typeId, setTypeId] = useState(row.housingTypeId)
  const [types, setTypes] = useState<ManagementHousingType[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [errors, setErrors] = useState<Record<string,string>>({})
  async function select(id: number, name: string) {
    setBusy(true); setError('')
    try { const value = await getManagementDetail('complexes',String(id)); setComplexId(id); setComplexName(name); setTypeId(null); setTypes(value.housingTypes) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '단지 정보를 확인하지 못했습니다.') }
    finally { setBusy(false) }
  }
  useUnsavedChanges(editing)
  return <article className="admin-supply"><header className="admin-inline"><h3>{String(row.data.sourceComplexName)} · {String(row.data.sourceHousingTypeName)}</h3>
    {!deleted && !editing ? <button onClick={() => { setEditing(true); if(row.housingComplexId) void getManagementDetail('complexes',String(row.housingComplexId)).then(v => setTypes(v.housingTypes)).catch(c => setError(String(c))) }}>공급정보 수정</button> : null}</header>
    {!editing ? <><p>연결 단지: {row.housingComplexId ? <Link to={`/admin/complexes/${row.housingComplexId}`}>{row.housingComplexName}</Link> : '미연결'}</p>
      <dl className="admin-data-grid">{supplyFields.map(field => <div key={field.name}><dt>{field.label}</dt><dd>{display(row.data[field.name])}</dd></div>)}</dl>
      {row.modified ? <p className="ingest-meta">관리자 수정값 보호 중</p> : null}</> :
      <form onSubmit={event => {
        event.preventDefault(); const body = { version, housingComplexId:complexId, housingTypeId:typeId, supplyRow:formValues(event.currentTarget,supplyFields,row.data) }
        setBusy(true);setError('');setErrors({})
        void requestManagementApi(`${managementResourcePath('announcements')}/${announcementId}/supply-rows/${row.id}`,'PUT',body)
          .then(value => { onSaved(parseManagementDetail(value));setEditing(false) }).catch(cause => {
            setError(cause instanceof Error ? cause.message : '저장하지 못했습니다.')
            if(cause instanceof ManagementError) setErrors(Object.fromEntries(Object.entries(cause.fields).map(([key,value]) => [key.replace('supplyRow.',''),value])))
          }).finally(() => setBusy(false))
      }}><fieldset disabled={busy}><legend>단지·주택형 연결</legend><p>{complexName ?? '미연결'}</p>
        <ComplexPicker onSelect={item => void select(item.id,item.name)} disabled={busy} />
        <label>주택형<select value={typeId ?? ''} onChange={event => setTypeId(event.target.value ? Number(event.target.value) : null)}><option value="">미연결</option>
          {types.map(type => <option key={type.id} value={type.id}>{type.name} · {type.exclusiveArea}㎡</option>)}</select></label>
        <button type="button" onClick={() => { setComplexId(null);setComplexName(null);setTypeId(null);setTypes([]) }}>단지 연결 해제</button>
        <EditFields fields={supplyFields} data={row.data} errors={errors} />
      </fieldset>{error ? <p role="alert" className="registration-error">{error}</p> : null}
        <div className="admin-inline"><button className="admin-primary" disabled={busy}>{busy ? '저장 중…' : '공급정보 저장'}</button>
          <button type="button" disabled={busy} onClick={() => { setEditing(false);setComplexId(row.housingComplexId);setComplexName(row.housingComplexName);setTypeId(row.housingTypeId) }}>취소</button></div></form>}
  </article>
}
