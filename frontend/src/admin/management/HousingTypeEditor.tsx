import { useState } from 'react'
import { EditFields } from './EditFields'
import { ManagementError, managementResourcePath, requestManagementApi } from './api'
import { formValues, type Field } from './fields'
import { parseManagementDetail, type ManagementDetailData, type ManagementHousingType } from './managementContract'
import { useUnsavedChanges } from './useUnsavedChanges'

const fields: Field[] = [
  { name: 'name', label: '주택형 이름', required: true },
  { name: 'exclusiveArea', label: '전용면적 (㎡)', type: 'number', required: true, min: 0, max: 999999.9999, step: '0.0001' },
  { name: 'householdCount', label: '세대수', type: 'number', min: 0, step: '1' },
]

export function HousingTypeEditor({ type, complexId, version, onSaved, onClose, onBusyChange }: {
  type: ManagementHousingType; complexId: string; version: number; onSaved: (value: ManagementDetailData) => void;
  onClose: () => void; onBusyChange: (busy: boolean) => void;
}) {
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  useUnsavedChanges(dirty)
  return <form className="admin-relation-edit" aria-label="주택형 편집" onChange={() => setDirty(true)} onSubmit={event => {
    event.preventDefault()
    const body = { ...formValues(event.currentTarget, fields, {}), version }
    setBusy(true); onBusyChange(true); setError(''); setErrors({})
    void requestManagementApi(`${managementResourcePath('complexes')}/${complexId}/housing-types/${type.id}`, 'PUT', body)
      .then(result => { setDirty(false); onSaved(parseManagementDetail(result)); onClose() })
      .catch(cause => {
        setError(cause instanceof Error ? cause.message : '주택형을 저장하지 못했습니다.')
        if (cause instanceof ManagementError) setErrors(cause.fields)
      }).finally(() => { setBusy(false); onBusyChange(false) })
  }}>
    <fieldset disabled={busy}><legend>{type.name} 수정</legend>
      <EditFields fields={fields} data={{ name: type.name, exclusiveArea: type.exclusiveArea, householdCount: type.householdCount }} errors={errors} />
      <p className="ingest-meta">세대수가 확인되지 않았으면 비워 두세요.</p>
    </fieldset>
    {error ? <p className="registration-error" role="alert">{error}</p> : null}
    <div className="admin-inline"><button className="admin-primary" disabled={busy}>{busy ? '저장 중…' : '주택형 저장'}</button>
      <button type="button" disabled={busy} onClick={() => {
        if (!dirty || window.confirm('주택형 변경사항을 버릴까요?')) { setDirty(false); onClose() }
      }}>취소</button></div>
  </form>
}
