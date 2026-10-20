import { useState } from 'react'
import { Link } from 'react-router'
import { EditFields } from './EditFields'
import { ManagementError, managementResourcePath, requestManagementApi } from './api'
import { formValues, type Field } from './fields'
import { parseManagementDetail, type ManagementDetailData, type ManagementHousingType } from './managementContract'
import { useUnsavedChanges } from './useUnsavedChanges'
import { loadHousingTypeImpact, type HousingTypeImpact } from './housingTypeImpactApi'

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
  const [preview, setPreview] = useState<{ impact: HousingTypeImpact; body: Record<string, unknown> } | null>(null)
  useUnsavedChanges(dirty)
  async function inspect(form: HTMLFormElement) {
    const body = { ...formValues(form, fields, {}), version }
    setBusy(true); onBusyChange(true); setError('')
    try { setPreview({ impact: await loadHousingTypeImpact(complexId, type.id), body }) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '수정 영향을 확인하지 못했습니다.') }
    finally { setBusy(false); onBusyChange(false) }
  }
  async function save() {
    if (!preview) return
    setBusy(true); onBusyChange(true); setError(''); setErrors({})
    try {
      const result = await requestManagementApi(`${managementResourcePath('complexes')}/${complexId}/housing-types/${type.id}`, 'PUT', { ...preview.body, impactToken: preview.impact.token })
      setDirty(false); onSaved(parseManagementDetail(result)); onClose()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '주택형을 저장하지 못했습니다.')
      if (cause instanceof ManagementError) { setErrors(cause.fields); if (cause.status === 409) setPreview(null) }
    } finally { setBusy(false); onBusyChange(false) }
  }
  return <form className="admin-relation-edit" aria-label="주택형 편집" onChange={() => { setDirty(true); setPreview(null) }} onSubmit={event => { event.preventDefault(); void inspect(event.currentTarget) }}>
    <fieldset disabled={busy}><legend>{type.name} 수정</legend>
      <EditFields fields={fields} data={{ name: type.name, exclusiveArea: type.exclusiveArea, householdCount: type.householdCount }} errors={errors} />
      <p className="ingest-meta">단지 공통정보를 수정합니다. 특정 공고에만 적용할 명칭·면적은 해당 공고의 공급정보에서 보정하세요. 세대수가 확인되지 않았으면 비워 두세요.</p>
    </fieldset>
    {error ? <p className="registration-error" role="alert">{error}</p> : null}
    {preview && <section aria-label="공통 주택형 수정 영향"><h3>연결된 공급행 {preview.impact.rows.length}개</h3>
      <p>이름: {type.name} → {String(preview.body.name)} · 전용면적: {type.exclusiveArea} → {String(preview.body.exclusiveArea)}㎡ · 전체 세대수: {type.householdCount ?? '미확인'} → {preview.body.householdCount === null ? '미확인' : String(preview.body.householdCount)}</p>
      {preview.impact.rows.length === 0 ? <p>연결된 공고가 없습니다.</p> : <ul>{preview.impact.rows.map(row => <li key={row.supplyRowId}>
        <Link to={`/admin/announcements/${row.announcementId}?section=relations`} target="_blank" rel="noreferrer">{row.announcementName} · 공급행 #{row.supplyRowId} ↗</Link>
        {' · '}{row.areaProtected ? '공고별 면적 보호' : row.reviewedAreas ? '검토한 면적을 공고에서 먼저 보호해야 함' : '공통 면적 변경이 반영됨'}{row.excluded ? ' · 공개 제외 중' : ''}
      </li>)}</ul>}
      <button type="button" className="admin-primary" disabled={busy} onClick={() => void save()}>영향을 확인한 주택형 저장</button>
    </section>}
    <div className="admin-inline"><button className="admin-primary" disabled={busy}>{busy ? '확인 중…' : '수정 영향 확인'}</button>
      <button type="button" disabled={busy} onClick={() => {
        if (!dirty || window.confirm('주택형 변경사항을 버릴까요?')) { setDirty(false); onClose() }
      }}>취소</button></div>
  </form>
}
