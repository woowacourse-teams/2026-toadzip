import { useEffect, useRef, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { getRegistrationInformation, supplementRegistrationInformation, type RegistrationInformation } from './api'
import styles from './RegistrationRecovery.module.css'

export function RegistrationInformationEditor({ identifier, disabled, onBusyChange, onSaved, onCancel }: {
  identifier: string; disabled: boolean; onBusyChange: (busy: boolean) => void
  onSaved: () => void; onCancel: () => void
}) {
  const [information, setInformation] = useState<RegistrationInformation | null>(null)
  const [name, setName] = useState('')
  const [names, setNames] = useState<Record<string, string>>({})
  const [evidence, setEvidence] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [reload, setReload] = useState(0)
  const pending = useRef(false)
  useEffect(() => {
    let active = true
    setInformation(null); setError('')
    void getRegistrationInformation(identifier).then(value => {
      if (!active) return
      setInformation(value); setName(value.supplement?.complexName ?? '')
      setNames(value.supplement?.housingTypeNames ?? {}); setEvidence(value.supplement?.evidence ?? '')
    }).catch((cause: unknown) => { if (active) setError(cause instanceof Error ? cause.message : '정보를 조회하지 못했습니다.') })
    return () => { active = false }
  }, [identifier, reload])

  async function save() {
    if (disabled || pending.current || !information || !evidence.trim()) return
    pending.current = true; setSaving(true); onBusyChange(true); setError('')
    const housingTypeNames = Object.fromEntries(information.housingTypes.filter(row => row.name === null)
      .flatMap(row => names[row.sourceKey]?.trim() ? [[row.sourceKey, names[row.sourceKey].trim()]] : []))
    try {
      await supplementRegistrationInformation({ sourceComplexIdentifier: identifier, fingerprint: information.fingerprint,
        complexName: information.complexName === null ? name.trim() || null : null, housingTypeNames, evidence: evidence.trim() })
      onSaved()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '보완값을 저장하지 못했습니다.')
    } finally { pending.current = false; setSaving(false); onBusyChange(false) }
  }

  return <form className={styles.editor} aria-label="등록 정보 보완" onSubmit={event => { event.preventDefault(); void save() }}>
    {!information && !error && <p role="status">현재 원천과 보완값을 확인하고 있습니다.</p>}
    {information && <fieldset disabled={disabled || saving}>
      <legend>누락된 이름 보완</legend>
      <p>공식 공고문 등에서 확인한 값만 입력하세요. 확인할 수 없는 값은 비워 두면 보완 필요 상태로 유지됩니다.</p>
      {information.supplement && <p>이전 보완: {information.supplement.checkedBy} · {new Date(information.supplement.checkedAt).toLocaleString('ko-KR')}</p>}
      {information.supplement?.current === false && <p role="status">원천이 변경되어 이전 보완값을 적용하지 않습니다. 현재 원천과 근거를 다시 확인한 뒤 저장하세요.</p>}
      {information.complexName === null && <label>보완 단지명<input maxLength={255} value={name} onChange={event => setName(event.target.value)} /></label>}
      {information.housingTypes.filter(row => row.name === null).map((row, index) => <label key={row.sourceKey}>
        보완 주택형명 {index + 1} ({row.exclusiveArea === null ? '면적 미제공' : `전용 ${row.exclusiveArea}㎡`})
        <input maxLength={255} value={names[row.sourceKey] ?? ''} onChange={event => setNames(current => ({ ...current, [row.sourceKey]: event.target.value }))} />
      </label>)}
      <label>보완 근거<input required maxLength={2000} value={evidence} placeholder="공식 공고문 URL·페이지와 확인한 내용"
        onChange={event => setEvidence(event.target.value)} /></label>
      <div className={styles.actions}>
        <Button data-design-system type="submit" disabled={!evidence.trim()}>{saving ? '저장 중…' : '보완값 저장·검증'}</Button>
        <Button data-design-system variant="secondary" onClick={onCancel}>취소</Button>
      </div>
    </fieldset>}
    {error && <div role="alert"><p>{error}</p><Button data-design-system variant="secondary" disabled={saving}
      onClick={() => setReload(value => value + 1)}>현재 정보 다시 조회</Button></div>}
    {!information && <Button data-design-system variant="secondary" onClick={onCancel}>닫기</Button>}
  </form>
}
