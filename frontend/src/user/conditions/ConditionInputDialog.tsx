import { useEffect, useId, useRef, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { IconButton } from '../../design-system/components/IconButton'
import { ConditionConfirmDialog } from './ConditionConfirmDialog'
import { ConditionDoneSummary } from './ConditionDoneSummary'
import { ConditionField, FamilyGate } from './ConditionFields'
import { ConditionModal } from './ConditionModal'
import { ConditionStepProgress } from './ConditionStepProgress'
import { STEP_COPY, type StepNumber } from './conditionFieldCopy'
import { draftHasAnswers, toProfile, visibleItems, type ConditionDraft, type DraftItem } from './conditionDraft'
import { BackIcon, CloseIcon, TickIcon } from './conditionIcons'
import {
  partialErrors,
  partialFields,
  partialStartDraft,
  partialTitle,
  stepErrors,
  submitLabel,
  type FieldErrors,
  type InputRequest,
} from './conditionInput'
import type { ProfileField } from './conditionsModel'
import styles from './ConditionInputDialog.module.css'

export interface ConditionInputDialogProps {
  readonly request: InputRequest
  readonly initialDraft: ConditionDraft
  /** 저장된 내 조건을 고치는 중 */
  readonly editing: boolean
  readonly member: boolean
  readonly initialMaxStep: number
  readonly today: string
  readonly saving: boolean
  /** 저장하지 못한 이유. 창 안에서 보여 준다 */
  readonly error?: string | null
  readonly onDraftChange: (draft: ConditionDraft, step: StepNumber | 4, maxStep: number) => void
  readonly onSubmit: (draft: ConditionDraft) => void
  readonly onClose: () => void
}

export function ConditionInputDialog(props: ConditionInputDialogProps) {
  return props.request.mode === 'partial' ? <PartialDialog {...props} fields={props.request.fields} /> : <FullDialog {...props} startStep={props.request.step} />
}

function useFirstErrorFocus(bodyRef: React.RefObject<HTMLElement | null>) {
  const [tick, setTick] = useState(0)
  useEffect(() => {
    if (!tick) return
    const first = bodyRef.current?.querySelector<HTMLElement>('[data-error="true"]')
    first?.scrollIntoView?.({ block: 'center' })
    first?.querySelector<HTMLElement>('input:not(:disabled), [role="radio"]:not(:disabled), button[aria-haspopup]')?.focus({ preventScroll: true })
  }, [tick, bodyRef])
  return () => setTick((value) => value + 1)
}

/** 분기로 새로 나타난 칸에만 등장 효과를 준다. 단계를 처음 열 때는 주지 않는다. */
function useFreshItems(scope: string, items: readonly DraftItem[]) {
  // 본 칸 기록이 바뀌어도 다시 그리지 않아야 등장 효과가 끝까지 재생된다.
  const seenRef = useRef(new Map<string, Set<DraftItem>>())
  const scopeSeen = seenRef.current.get(scope)
  useEffect(() => {
    const set = seenRef.current.get(scope) ?? new Set<DraftItem>()
    for (const item of items) set.add(item)
    seenRef.current.set(scope, set)
  })
  return (item: DraftItem) => scopeSeen !== undefined && !scopeSeen.has(item)
}

function FullDialog({ startStep, initialDraft, editing, member, initialMaxStep, today, saving, error = null, request, onDraftChange, onSubmit, onClose }: ConditionInputDialogProps & { readonly startStep: StepNumber | 4 }) {
  const titleId = useId()
  const titleRef = useRef<HTMLHeadingElement>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const [draft, setDraft] = useState(initialDraft)
  const [step, setStep] = useState<StepNumber | 4>(startStep)
  const [maxStep, setMaxStep] = useState(Math.max(initialMaxStep, startStep))
  const [errors, setErrors] = useState<FieldErrors>({})
  const [confirm, setConfirm] = useState<'leave' | 'discard' | null>(null)
  const focusFirstError = useFirstErrorFocus(bodyRef)
  const items = step <= 3 ? visibleItems(step as StepNumber, draft) : []
  const isFresh = useFreshItems(`step-${step}`, items)
  const changeRef = useRef(onDraftChange)
  useEffect(() => { changeRef.current = onDraftChange })
  useEffect(() => { changeRef.current(draft, step, maxStep) }, [draft, step, maxStep])

  const goTo = (target: StepNumber | 4) => {
    if (step <= 3 && target > step) {
      const found = stepErrors(step as StepNumber, draft, errors)
      if (Object.keys(found).length) { setErrors(found); focusFirstError(); return }
    }
    setErrors({})
    setStep(target)
    setMaxStep((current) => Math.max(current, target))
    if (bodyRef.current) bodyRef.current.scrollTop = 0
    titleRef.current?.focus({ preventScroll: true })
  }
  const setFieldError = (field: ProfileField, message: string | null) => setErrors((current) => {
    const next = { ...current }
    if (message) next[field] = message
    else delete next[field]
    return next
  })
  const changed = () => JSON.stringify(toProfile(draft)) !== JSON.stringify(toProfile(initialDraft))
  const close = () => {
    if (!member && draftHasAnswers(draft)) { setConfirm('leave'); return }
    if (editing && changed()) { setConfirm('discard'); return }
    onClose()
  }

  const gateIndex = items.indexOf('FAMILY_GATE')
  const main = gateIndex < 0 ? items : items.slice(0, gateIndex)
  const family = gateIndex < 0 ? [] : items.slice(gateIndex + 1)
  const fieldOf = (item: DraftItem, grouped = false) => item === 'FAMILY_GATE' ? null
    : <ConditionField key={item} field={item} draft={draft} error={errors[item] ?? null} today={today} grouped={grouped} fresh={isFresh(item)}
      onChange={setDraft} onFieldError={setFieldError} />

  return <ConditionModal variant="full" labelledBy={titleId} focusRef={titleRef} onCancel={close}>
    <div className={styles.head}>
      <div className={styles.top}>
        <div className={styles.titleRow}>
          {step > 1 && <IconButton className={styles.back} label="이전 단계" onClick={() => goTo((step - 1) as StepNumber)}><BackIcon size={24} /></IconButton>}
          <h2 id={titleId} ref={titleRef} tabIndex={-1}>{editing ? '내 조건 수정' : '내 조건 입력'}</h2>
          {member && !editing && draftHasAnswers(draft) && <span className={styles.autosave} role="status"><TickIcon size={12} />자동 저장됨</span>}
        </div>
        <IconButton label="닫기" onClick={close}><CloseIcon size={24} /></IconButton>
      </div>
      <ConditionStepProgress step={step} maxStep={maxStep} draft={draft} onGo={goTo} />
    </div>
    <div ref={bodyRef} className={styles.body}>
      {step <= 3 ? <>
        <div className={styles.intro}><h3>{STEP_COPY[step as StepNumber].title}</h3><p>{STEP_COPY[step as StepNumber].description}</p></div>
        {main.map((item) => fieldOf(item))}
        {gateIndex >= 0 && <>
          <hr className={styles.divider} aria-hidden="true" />
          <FamilyGate draft={draft} error={errors.FAMILY_GATE ?? null} fresh={isFresh('FAMILY_GATE')} onChange={(next) => { setDraft(next); setErrors(({ FAMILY_GATE: _, ...rest }) => rest) }} />
          {family.map((item) => fieldOf(item, true))}
        </>}
      </> : <ConditionDoneSummary draft={draft} today={today} onEdit={goTo} />}
    </div>
    {step <= 3
      ? <div className={styles.foot}><Button size="lg" className={styles.wide} onClick={() => goTo((step + 1) as StepNumber | 4)}>{step === 3 ? '입력 완료' : '다음'}</Button></div>
      : <div className={`${styles.foot} ${styles.column}`}>
        {error && <p className={styles.saveError} role="alert">{error}</p>}
        {!member && <p className={styles.footNote}>카카오·Google 간편 로그인 후 바로 보여 드려요. 입력한 내용도 함께 저장돼요.</p>}
        <Button size="lg" className={styles.wide} disabled={saving} onClick={() => onSubmit(draft)}>{submitLabel(request, editing, member)}</Button>
      </div>}
    {confirm === 'leave' && <ConditionConfirmDialog title="입력을 그만둘까요?" body="로그인하지 않으면 입력한 내용은 저장되지 않아요."
      cancelLabel="그만두기" confirmLabel="계속 입력하기" onCancel={onClose} onConfirm={() => setConfirm(null)} onDismiss={() => setConfirm(null)} />}
    {confirm === 'discard' && <ConditionConfirmDialog title="수정한 내용을 저장할까요?" body="저장하지 않고 닫으면 이번에 고친 내용은 사라져요."
      cancelLabel="저장하지 않고 닫기" confirmLabel="저장하기" onCancel={onClose} onConfirm={() => { setConfirm(null); onSubmit(draft) }} onDismiss={() => setConfirm(null)} />}
  </ConditionModal>
}

function PartialDialog({ fields: requested, initialDraft, today, saving, error = null, request, onSubmit, onClose }: ConditionInputDialogProps & { readonly fields: readonly ProfileField[] }) {
  const titleId = useId()
  const titleRef = useRef<HTMLHeadingElement>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const [draft, setDraft] = useState(() => partialStartDraft(requested, initialDraft))
  const [errors, setErrors] = useState<FieldErrors>({})
  const focusFirstError = useFirstErrorFocus(bodyRef)
  const fields = partialFields(requested, draft)
  const isFresh = useFreshItems('partial', fields)
  const setFieldError = (field: ProfileField, message: string | null) => setErrors((current) => {
    const next = { ...current }
    if (message) next[field] = message
    else delete next[field]
    return next
  })
  const save = () => {
    const found = partialErrors(fields, draft, errors)
    if (Object.keys(found).length) { setErrors(found); focusFirstError(); return }
    onSubmit(draft)
  }
  return <ConditionModal variant="sheet" labelledBy={titleId} focusRef={titleRef} onCancel={onClose}>
    <div className={styles.head}>
      <div className={styles.top}>
        <div>
          <h2 id={titleId} ref={titleRef} tabIndex={-1}>{partialTitle(requested, initialDraft)}</h2>
          <p className={styles.subtitle}>{request.origin === 'detail' ? '저장하면 이 공고와 바로 다시 비교해요.' : '저장하면 공고 비교에 바로 반영돼요.'}</p>
        </div>
        <IconButton label="닫기" onClick={onClose}><CloseIcon size={24} /></IconButton>
      </div>
    </div>
    <div ref={bodyRef} className={`${styles.body} ${styles.partialBody}`}>
      {fields.map((field) => <ConditionField key={field} field={field} draft={draft} error={errors[field] ?? null} today={today} partial fresh={isFresh(field)}
        onChange={setDraft} onFieldError={setFieldError} />)}
    </div>
    {error && <p className={`${styles.saveError} ${styles.saveErrorBar}`} role="alert">{error}</p>}
    <div className={styles.foot}>
      <Button size="lg" variant="secondary" className={styles.cancel} onClick={onClose}>취소</Button>
      <Button size="lg" className={styles.wide} disabled={saving} onClick={save}>{submitLabel(request, false, true)}</Button>
    </div>
  </ConditionModal>
}
