import { useEffect, useRef, useState, type FormEvent } from 'react'
import { getMatchingHousingTypes, splitSupplyRow, type SupplyMatch, type MatchingHousingType } from './supplyMatchingApi'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import styles from './AnnouncementSupplyMatchingForm.module.css'

type DraftRow = { key: string; id?: number; name: string; housingTypeId: string }

export function SupplySplitEditor({ identifier, row, complexId, disabled, onBusy, onSaved, onCancel }: {
  identifier: string; row: SupplyMatch; complexId: number | null; disabled: boolean
  onBusy: (busy: boolean) => void; onSaved: () => void; onCancel: () => void
}) {
  const [drafts, setDrafts] = useState<DraftRow[]>(() => row.splitGroup?.rows.map(value => ({
    key: String(value.id), id: value.id, name: value.name, housingTypeId: String(value.housingTypeId ?? ''),
  })) ?? [{ key: 'first', name: '', housingTypeId: '' }, { key: 'second', name: '', housingTypeId: '' }])
  const [types, setTypes] = useState<MatchingHousingType[]>([])
  const [sourceUrl, setSourceUrl] = useState(row.splitGroup?.sourceUrl ?? row.savedMatch?.sourceUrl ?? '')
  const [reason, setReason] = useState(row.splitGroup?.reason ?? '')
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [error, setError] = useState('')
  const pending = useRef(false)
  const form = useRef<HTMLFormElement>(null)
  useUnsavedChanges(dirty)
  useEffect(() => {
    if (!dirty) return
    const dialog = form.current?.closest('dialog')
    const cancel = (event: Event) => {
      if (pending.current || !window.confirm('저장하지 않은 보정을 버리고 닫을까요?')) event.preventDefault()
    }
    dialog?.addEventListener('cancel', cancel)
    return () => dialog?.removeEventListener('cancel', cancel)
  }, [dirty])
  useEffect(() => {
    const controller = new AbortController()
    setTypes([])
    if (complexId === null) return () => controller.abort()
    setLoading(true)
    void getMatchingHousingTypes(complexId, controller.signal).then(setTypes)
      .catch(cause => { if (!controller.signal.aborted) setError(message(cause)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [complexId])

  async function save(event: FormEvent) {
    event.preventDefault()
    if (pending.current || disabled || loading || complexId === null) return
    const selections = drafts.map(draft => {
      const candidate = types.find(value => String(value.id) === draft.housingTypeId)
      return { ...(draft.id ? { id: draft.id } : {}), name: draft.name.trim(), complexId,
        housingTypeId: candidate?.id ?? 0, targetToken: candidate?.token ?? '' }
    })
    if (selections.some(value => !value.name || !value.housingTypeId || !value.targetToken)
      || new Set(selections.map(value => value.name.toLocaleLowerCase())).size !== selections.length
      || new Set(selections.map(value => value.housingTypeId)).size !== selections.length) {
      setError('각 공급행에 서로 다른 공고 주택형명과 연결할 주택형을 지정해 주세요.'); return
    }
    if (!/^https?:\/\//i.test(sourceUrl.trim()) || !reason.trim()) {
      setError('공식 출처 URL과 확인 근거를 입력해 주세요.'); return
    }
    pending.current = true; setSaving(true); setError(''); onBusy(true)
    try {
      await splitSupplyRow(identifier, row, selections, sourceUrl.trim(), reason.trim())
      setDirty(false); onSaved()
    } catch (cause) { setError(message(cause)) }
    finally { pending.current = false; setSaving(false); onBusy(false) }
  }

  const count = row.totalSupplyHouseholdCount ?? null
  return <form ref={form} className={styles.splitEditor} onSubmit={event => { void save(event) }}
    onChange={() => setDirty(true)} aria-label="원천별 복수 공급행 보정">
    <h5>{row.splitGroup ? '보정 공급행 확인·수정' : '이 원천에서 공급행 만들기'}</h5>
    <p><strong>공통 모집수: {count === null ? '미확정' : `${count}명`}</strong> · 각 행의 모집수는 미확정으로 보관합니다.</p>
    {row.splitGroup && row.splitGroup.sharedHouseholdCount !== count && <p role="status">
      저장한 공통 모집수는 {row.splitGroup.sharedHouseholdCount ?? '미확정'}입니다. 이번에 확인·저장하면 현재 원천의 {count ?? '미확정'}으로 변경합니다.
    </p>}
    <p>공고에 표시할 A/B 등의 형명과 연결 대상을 확인하세요. 공통 모집수를 나누거나 각 행에 복제하지 않습니다.
      공통 주택형의 이름·면적은 이 작업에서 바뀌지 않습니다.</p>
    {row.splitGroup?.reviewRequired && <p role="status">원천 또는 연결 대상이 변경되었습니다. 저장한 값과 최신 원문을 다시 비교해 주세요.</p>}
    {row.splitGroup && <p>기록: {row.splitGroup.actor} · {new Date(row.splitGroup.updatedAt).toLocaleString('ko-KR')}
      {' · '}<a href={row.splitGroup.sourceUrl} target="_blank" rel="noreferrer">공식 근거</a></p>}
    <fieldset disabled={disabled || saving || loading}>
      <legend>보정할 공급행</legend>
      {drafts.map((draft, index) => <div key={draft.key} className={styles.splitRow}>
        <label>공고 주택형명 {index + 1}<input maxLength={200} value={draft.name} placeholder={index === 0 ? '예: 26A' : '예: 26B'}
          onChange={event => setDrafts(values => values.map(value => value.key === draft.key ? { ...value, name: event.target.value } : value))} /></label>
        <label>연결 주택형 {index + 1}<select value={draft.housingTypeId} disabled={complexId === null}
          onChange={event => setDrafts(values => values.map(value => value.key === draft.key ? { ...value, housingTypeId: event.target.value } : value))}>
          <option value="">주택형 선택</option>
          {types.map(type => <option key={type.id} value={type.id}>{type.name} · 전용 {type.exclusiveArea}㎡
            {type.supplyArea == null ? '' : ` · 공급 ${type.supplyArea}㎡`}</option>)}
        </select></label>
        <span>개별 모집수 미확정</span>
        {!draft.id && drafts.length > 2 && <button type="button" onClick={() => { setDrafts(values => values.filter(value => value.key !== draft.key)); setDirty(true) }}>행 삭제</button>}
      </div>)}
      <button type="button" disabled={drafts.length >= 50} onClick={() => {
        setDrafts(values => [...values, { key: crypto.randomUUID(), name: '', housingTypeId: '' }]); setDirty(true)
      }}>공급행 추가</button>
      <label>보정 공식 출처 URL<input type="url" maxLength={2048} value={sourceUrl} onChange={event => setSourceUrl(event.target.value)} /></label>
      <label>보정 확인 근거<textarea rows={3} maxLength={2000} value={reason} placeholder="공고문 쪽수, A/B 연결 근거와 공통 모집수의 의미"
        onChange={event => setReason(event.target.value)} /></label>
    </fieldset>
    {complexId === null && <p>먼저 연결할 단지를 선택해 주세요.</p>}
    {error && <p role="alert">{error}</p>}
    <p>공고 한 건을 정제·검증하고 공급행·연결·공통 모집수·근거를 함께 저장합니다. 실패하면 모두 취소합니다.</p>
    <div className={styles.splitActions}>
      <button type="submit" disabled={disabled || saving || loading || complexId === null}>{saving ? '보정 저장 중…' : '공급행 보정·저장'}</button>
      <button type="button" disabled={saving} onClick={() => { if (!dirty || window.confirm('입력한 보정을 버릴까요?')) onCancel() }}>닫기</button>
    </div>
  </form>
}

function message(cause: unknown) { return cause instanceof Error ? cause.message : '공급행 보정을 저장하지 못했습니다.' }
