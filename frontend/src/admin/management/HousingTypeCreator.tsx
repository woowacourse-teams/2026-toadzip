import { useState } from 'react'
import { ManagementError, managementResourcePath, requestManagementApi } from './api'
import { parseManagementDetail, type ManagementDetailData } from './managementContract'

/** 수집 원천에 주택형이 없는 단지(입주 전 신규 단지 등)에 관리자가 주택형을 직접 추가한다. */
export function HousingTypeCreator({ complexId, version, onSaved, onClose, onBusyChange }: {
  complexId: string; version: number; onSaved: (value: ManagementDetailData) => void; onClose: () => void; onBusyChange: (busy: boolean) => void
}) {
  const [values, setValues] = useState({ name: '', exclusiveArea: '', supplyArea: '', householdCount: '' })
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const set = (key: keyof typeof values) => (event: { target: { value: string } }) => setValues(current => ({ ...current, [key]: event.target.value }))
  async function save() {
    setBusy(true); onBusyChange(true); setError('')
    try {
      const result = await requestManagementApi(`${managementResourcePath('complexes')}/${complexId}/housing-types`, 'POST', {
        version, name: values.name.trim(), exclusiveArea: Number(values.exclusiveArea),
        supplyArea: values.supplyArea === '' ? null : Number(values.supplyArea), householdCount: values.householdCount === '' ? null : Number(values.householdCount) })
      onSaved(parseManagementDetail(result)); onClose()
    } catch (cause) {
      setError(cause instanceof ManagementError || cause instanceof Error ? cause.message : '주택형을 추가하지 못했습니다.')
    } finally { setBusy(false); onBusyChange(false) }
  }
  return <form className="admin-housing-type-create" aria-label="주택형 추가" onSubmit={event => { event.preventDefault(); void save() }}>
    <fieldset disabled={busy}>
      <label>주택형 이름<input value={values.name} maxLength={255} required onChange={set('name')} /></label>
      <label>전용면적 (㎡)<input value={values.exclusiveArea} type="number" min={0} step="0.0001" required onChange={set('exclusiveArea')} /></label>
      <label>공급면적 (㎡, 선택)<input value={values.supplyArea} type="number" min={0} step="0.0001" onChange={set('supplyArea')} /></label>
      <label>세대수 (선택)<input value={values.householdCount} type="number" min={0} step="1" onChange={set('householdCount')} /></label>
    </fieldset>
    {error ? <p role="alert" className="registration-error">{error}</p> : null}
    <div className="admin-inline"><button className="admin-primary" type="submit" disabled={busy || !values.name.trim() || values.exclusiveArea === ''}>{busy ? '추가하는 중…' : '주택형 추가'}</button>
      <button type="button" disabled={busy} onClick={onClose}>취소</button></div>
  </form>
}
