import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { FAMILY_TYPE_COPY, FIELD_COPY } from './conditionFieldCopy'
import {
  LATER,
  NOT_APPLICABLE,
  isDateEvent,
  setAnswer,
  setFamilyDetails,
  validateDate,
  type ConditionDraft,
} from './conditionDraft'
import { formatAge, formatDigitsAsDate, formatDots, formatWon } from './conditionFormat'
import { districtOptions, provinceOptions } from './conditionRegions'
import { ChevronDownIcon, TickIcon } from './conditionIcons'
import type { DateEventKind, FamilyType, ProfileField } from './conditionsModel'
import styles from './ConditionFields.module.css'

export interface ConditionFieldProps {
  readonly field: ProfileField
  readonly draft: ConditionDraft
  readonly error: string | null
  readonly today: string
  /** 부분 입력 창: "나중에" 체크를 숨긴다 */
  readonly partial?: boolean
  /** 가족 정보 묶음 아래 질문 */
  readonly grouped?: boolean
  /** 분기로 새로 나타난 칸 */
  readonly fresh?: boolean
  readonly onChange: (next: ConditionDraft) => void
  readonly onFieldError: (field: ProfileField, message: string | null) => void
}

export function ConditionField(props: ConditionFieldProps) {
  const { field, error, grouped = false, fresh = false } = props
  const copy = FIELD_COPY[field]
  const questionId = useId()
  return <div id={`condition-field-${field}`} data-condition-field={field} data-error={error ? 'true' : undefined}
    className={[styles.field, grouped ? styles.grouped : '', fresh ? styles.reveal : ''].filter(Boolean).join(' ')}>
    <p className={styles.question} id={questionId}>{copy.question}{grouped && <small>선택</small>}</p>
    {copy.help && <p className={styles.help}>{copy.help}</p>}
    <div className={styles.control}><FieldControl {...props} questionId={questionId} /></div>
    {error && <p className={styles.error} role="alert">{error}</p>}
  </div>
}

function FieldControl(props: ConditionFieldProps & { readonly questionId: string }) {
  switch (FIELD_COPY[props.field].kind) {
    case 'bool': return <BoolControl {...props} />
    case 'familyType': return <FamilyTypeControl {...props} />
    case 'date': return <DateControl {...props} />
    case 'region': return <RegionControl {...props} />
    case 'money': return <MoneyControl {...props} />
    case 'count': return <CountControl {...props} />
    case 'dateEvent': return <DateEventControl {...props} />
  }
}

type ControlProps = ConditionFieldProps & { readonly questionId: string }

function Choice({ checked, disabled, onSelect, children }: { readonly checked: boolean; readonly disabled?: boolean; readonly onSelect: () => void; readonly children: React.ReactNode }) {
  return <button type="button" role="radio" aria-checked={checked} disabled={disabled} className={styles.option} onClick={onSelect}>{children}</button>
}

function BoolControl({ field, draft, questionId, onChange, onFieldError }: ControlProps) {
  const value = draft.answers[field]
  const lockedHouse = field === 'FAMILY_OWNS_HOUSE' && draft.answers.APPLICANT_OWNS_HOUSE === true
  const lockedCar = field === 'FAMILY_OWNS_CAR' && draft.answers.APPLICANT_OWNS_CAR === true
  const locked = lockedHouse || lockedCar
  const choose = (next: boolean) => { onFieldError(field, null); onChange(setAnswer(draft, field, next)) }
  return <>
    <div className={styles.pair} role="radiogroup" aria-labelledby={questionId}>
      <Choice checked={value === true} disabled={locked} onSelect={() => choose(true)}>예</Choice>
      <Choice checked={value === false} disabled={locked} onSelect={() => choose(false)}>아니요</Choice>
    </div>
    {locked && <p className={styles.note}>본인 명의 {lockedHouse ? '주택이' : '자동차가'} 있어서 '예'로 정했어요.</p>}
  </>
}

function FamilyTypeControl({ field, draft, questionId, onChange, onFieldError }: ControlProps) {
  const value = draft.answers[field]
  return <>
    <div className={styles.cards} role="radiogroup" aria-labelledby={questionId}>
      {(Object.keys(FAMILY_TYPE_COPY) as FamilyType[]).map((type) => (
        <Choice key={type} checked={value === type} onSelect={() => { onFieldError(field, null); onChange(setAnswer(draft, field, type)) }}>
          <b>{FAMILY_TYPE_COPY[type].label}</b><span>{FAMILY_TYPE_COPY[type].description}</span>
        </Choice>
      ))}
    </div>
    <p className={styles.help}>한부모를 골라도 법정 지원대상 한부모가족 자격은 공고에서 따로 확인해요.</p>
  </>
}

function digitsToIso(digits: string) {
  return `${digits.slice(0, 4)}-${digits.slice(4, 6)}-${digits.slice(6)}`
}

function DateControl({ field, draft, questionId, today, partial = false, onChange, onFieldError }: ControlProps) {
  const value = draft.answers[field]
  const [raw, setRaw] = useState(() => (typeof value === 'string' ? formatDots(value) : ''))
  const laterOn = value === LATER
  const notApplicableOn = value === NOT_APPLICABLE
  const change = (text: string) => {
    const digits = text.replace(/\D/g, '').slice(0, 8)
    setRaw(formatDigitsAsDate(digits))
    if (digits.length < 8) { onFieldError(field, null); onChange(setAnswer(draft, field, undefined)); return }
    const iso = digitsToIso(digits)
    const message = validateDate(field, iso, null, today)
    onFieldError(field, message)
    onChange(setAnswer(draft, field, message ? undefined : iso))
  }
  return <>
    <input className={styles.input} inputMode="numeric" placeholder={laterOn ? '나중에 입력해요' : '예) 1996.03.02'} aria-labelledby={questionId}
      disabled={laterOn || notApplicableOn} maxLength={10} value={raw} onChange={(event) => change(event.target.value)} />
    {typeof value === 'string' && field === 'BIRTH_DATE' && <p className={styles.reading}>{formatAge(value, today)}</p>}
    {(FIELD_COPY[field].skippable || FIELD_COPY[field].notApplicableLabel) && <div className={styles.foot}>
      {FIELD_COPY[field].notApplicableLabel && <Checkbox checked={notApplicableOn} onToggle={() => { onFieldError(field, null); setRaw(''); onChange(setAnswer(draft, field, notApplicableOn ? undefined : NOT_APPLICABLE)) }}>{FIELD_COPY[field].notApplicableLabel}</Checkbox>}
      {FIELD_COPY[field].skippable && !partial && <Checkbox checked={laterOn} onToggle={() => { onFieldError(field, null); setRaw(''); onChange(setAnswer(draft, field, laterOn ? undefined : LATER)) }}>나중에 입력할게요</Checkbox>}
    </div>}
  </>
}

function Checkbox({ checked, onToggle, children, className }: { readonly checked: boolean; readonly onToggle: () => void; readonly children: React.ReactNode; readonly className?: string }) {
  return <button type="button" role="checkbox" aria-checked={checked} className={[styles.checkbox, className].filter(Boolean).join(' ')} onClick={onToggle}>
    <span className={styles.box} aria-hidden="true">{checked && <TickIcon size={14} />}</span>{children}
  </button>
}

function MoneyControl({ field, draft, questionId, partial = false, onChange, onFieldError }: ControlProps) {
  const copy = FIELD_COPY[field]
  const value = draft.answers[field]
  const [raw, setRaw] = useState(() => (typeof value === 'number' ? String(value / 10_000) : ''))
  const [sameOn, setSameOn] = useState(false)
  const applicantIncome = draft.answers.APPLICANT_MONTHLY_INCOME
  const same = field === 'HOUSEHOLD_MONTHLY_INCOME' && draft.answers.REGISTERED_FAMILY_COUNT === 1 && typeof applicantIncome === 'number' ? applicantIncome : null
  const zeroOn = Boolean(copy.zeroLabel) && value === 0 && !sameOn
  const laterOn = value === LATER
  const sameChecked = same !== null && sameOn && value === same
  const locked = zeroOn || laterOn || sameChecked
  const shown = laterOn ? '' : locked && typeof value === 'number' ? String(value / 10_000) : raw
  const set = (next: number | typeof LATER | undefined) => { onFieldError(field, null); onChange(setAnswer(draft, field, next)) }
  const type = (text: string) => {
    const digits = text.replace(/\D/g, '').replace(/^0+(?=\d)/, '').slice(0, 7)
    setRaw(digits)
    set(digits === '' ? undefined : Number(digits) * 10_000)
  }
  const toggleFixed = (checked: boolean, fixed: number, isSame: boolean) => {
    setSameOn(!checked && isSame)
    setRaw(checked ? '' : String(fixed / 10_000))
    set(checked ? undefined : fixed)
  }
  const toggleLater = () => { setSameOn(false); setRaw(''); set(laterOn ? undefined : LATER) }
  const showFoot = Boolean(copy.zeroLabel) || same !== null || (copy.skippable && !partial)
  return <>
    <div className={styles.moneyWrap}>
      {typeof value === 'number' && value > 0 && <span className={styles.readingInside} aria-hidden="true">{copy.monthly ? '월 ' : ''}{formatWon(value)}</span>}
      <input className={`${styles.input} ${styles.money}`} inputMode="numeric" aria-labelledby={questionId}
        placeholder={laterOn ? '나중에 입력해요' : '0'} maxLength={7} value={shown} disabled={locked}
        onChange={(event) => type(event.target.value)} />
      <span className={styles.unit}>만원</span>
    </div>
    {showFoot && <div className={styles.foot}>
      <div className={styles.checkRow}>
        {copy.zeroLabel && <Checkbox checked={zeroOn} onToggle={() => toggleFixed(zeroOn, 0, false)}>{copy.zeroLabel}</Checkbox>}
        {same !== null && <Checkbox checked={sameChecked} onToggle={() => toggleFixed(sameChecked, same, true)}>본인 소득과 같아요</Checkbox>}
      </div>
      {copy.skippable && !partial && <Checkbox className={styles.later} checked={laterOn} onToggle={toggleLater}>나중에 입력할게요</Checkbox>}
    </div>}
  </>
}

function CountControl({ field, draft, questionId, partial = false, onChange, onFieldError }: ControlProps) {
  const min = FIELD_COPY[field].min ?? 1
  const value = draft.answers[field]
  const set = (next: number | typeof LATER | undefined) => { onFieldError(field, null); onChange(setAnswer(draft, field, next)) }
  if (field === 'CURRENT_REGION_RESIDENCE_MONTHS') {
    const laterOn = value === LATER
    return <>
      <input className={styles.input} type="number" inputMode="numeric" min={0} max={1800} placeholder={laterOn ? '나중에 입력해요' : '개월 수'} aria-labelledby={questionId}
      value={typeof value === 'number' ? value : ''} disabled={laterOn} onChange={(event) => {
        const raw = event.target.value
        const next = raw === '' ? undefined : Number(raw)
        set(next === undefined || Number.isInteger(next) && next >= 0 && next <= 1800 ? next : undefined)
      }} />
      {FIELD_COPY[field].skippable && !partial && <div className={`${styles.foot} ${styles.footEnd}`}><Checkbox checked={laterOn} onToggle={() => set(laterOn ? undefined : LATER)}>나중에 입력할게요</Checkbox></div>}
    </>
  }
  const count = typeof value === 'number' ? value : null
  return <div className={styles.stepper} role="group" aria-labelledby={questionId}>
    <button type="button" aria-label="한 명 줄이기" disabled={count === null || count <= min} onClick={() => set(Math.max(min, (count ?? min) - 1))}>−</button>
    <div className={styles.stepperValue}>
      <input type="number" inputMode="numeric" min={min} aria-labelledby={questionId} placeholder="인원" value={count ?? ''}
        onChange={(event) => { const raw = event.target.value; const next = Number(raw); set(raw !== '' && Number.isInteger(next) && next >= min ? next : undefined) }} />
      {count !== null && <span>명</span>}
    </div>
    <button type="button" aria-label="한 명 늘리기" onClick={() => set(count === null ? min : count + 1)}>+</button>
  </div>
}

const EVENT_DATE_LABEL: Record<DateEventKind, string> = { REGISTERED: '혼인신고일', PLANNED: '신고 예정일', BORN: '생년월일', EXPECTED: '출산 예정일' }

function DateEventControl({ field, draft, questionId, today, partial = false, onChange, onFieldError }: ControlProps) {
  const copy = FIELD_COPY[field]
  const value = draft.answers[field]
  const laterOn = value === LATER
  const current = isDateEvent(value) ? value : null
  const [raw, setRaw] = useState(() => (current?.date ? formatDots(current.date) : ''))
  const pickKind = (kind: DateEventKind) => {
    onFieldError(field, null)
    const keep = current?.date && !validateDate(field, current.date, kind, today) ? current.date : ''
    if (!keep) setRaw('')
    onChange(setAnswer(draft, field, { kind, date: keep }))
  }
  const typeDate = (text: string) => {
    if (!current) return
    const digits = text.replace(/\D/g, '').slice(0, 8)
    setRaw(formatDigitsAsDate(digits))
    const iso = digits.length === 8 ? digitsToIso(digits) : null
    const message = iso ? validateDate(field, iso, current.kind, today) : null
    onFieldError(field, message)
    onChange(setAnswer(draft, field, { kind: current.kind, date: iso && !message ? iso : '' }))
  }
  const toggleLater = () => { setRaw(''); onFieldError(field, null); onChange(setAnswer(draft, field, laterOn ? undefined : LATER)) }
  const dateLabel = current ? EVENT_DATE_LABEL[current.kind] : '날짜'
  return <>
    <div className={styles.pair} role="radiogroup" aria-labelledby={questionId}>
      {copy.kinds?.map(([kind, label]) => <Choice key={kind} checked={current?.kind === kind} disabled={laterOn} onSelect={() => pickKind(kind)}>{label}</Choice>)}
    </div>
    {current && <input className={`${styles.input} ${styles.eventDate}`} inputMode="numeric" aria-label={dateLabel}
      placeholder={`${dateLabel} 예) 2025.04.01`} maxLength={10} value={raw} onChange={(event) => typeDate(event.target.value)} />}
    {copy.skippable && !partial && <div className={styles.foot}><span />
      <Checkbox className={styles.later} checked={laterOn} onToggle={toggleLater}>날짜는 나중에 입력할게요</Checkbox></div>}
  </>
}

function RegionControl({ field, draft, error, onChange, onFieldError }: ControlProps) {
  const value = draft.answers[field]
  const code = typeof value === 'string' ? value : null
  const province = draft.provinceCode ?? code?.slice(0, 2) ?? null
  const [open, setOpen] = useState<'province' | 'district' | null>(null)
  const districtButton = useRef<HTMLButtonElement>(null)
  const pickProvince = (next: string) => {
    onChange({ ...setAnswer(draft, field, undefined), provinceCode: next })
    setOpen('district')
  }
  const pickDistrict = (next: string) => {
    onFieldError(field, null)
    onChange({ ...setAnswer(draft, field, next), provinceCode: next.slice(0, 2) })
    setOpen(null)
    districtButton.current?.focus()
  }
  return <div className={styles.region}>
    <Dropdown label="시·도" options={provinceOptions()} value={province} open={open === 'province'} invalid={Boolean(error) && !province}
      onOpenChange={(next) => setOpen(next ? 'province' : null)} onPick={pickProvince} />
    <Dropdown label="시·군·구" buttonRef={districtButton} options={province ? districtOptions(province) : []} value={code} disabled={!province}
      open={open === 'district'} invalid={Boolean(error) && Boolean(province) && !code}
      onOpenChange={(next) => setOpen(next ? 'district' : null)} onPick={pickDistrict} />
  </div>
}

function Dropdown({ label, options, value, open, disabled = false, invalid, buttonRef, onOpenChange, onPick }: {
  readonly label: string
  readonly options: readonly (readonly [string, string])[]
  readonly value: string | null
  readonly open: boolean
  readonly disabled?: boolean
  readonly invalid: boolean
  readonly buttonRef?: React.RefObject<HTMLButtonElement | null>
  readonly onOpenChange: (open: boolean) => void
  readonly onPick: (code: string) => void
}) {
  const listId = useId()
  const rootRef = useRef<HTMLDivElement>(null)
  const ownButton = useRef<HTMLButtonElement>(null)
  const button = buttonRef ?? ownButton
  const listRef = useRef<HTMLUListElement>(null)
  const selected = options.find(([code]) => code === value)
  const onOpenChangeRef = useRef(onOpenChange)
  useEffect(() => { onOpenChangeRef.current = onOpenChange })

  useEffect(() => {
    if (!open) return
    const items = [...(listRef.current?.querySelectorAll<HTMLElement>('[role="option"]') ?? [])]
    ;(items.find((item) => item.getAttribute('aria-selected') === 'true') ?? items[0])?.focus()
    const outside = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) onOpenChangeRef.current(false)
    }
    document.addEventListener('pointerdown', outside)
    return () => document.removeEventListener('pointerdown', outside)
  }, [open])

  const onListKey = (event: KeyboardEvent<HTMLUListElement>) => {
    const items = [...(listRef.current?.querySelectorAll<HTMLElement>('[role="option"]') ?? [])]
    const index = items.indexOf(document.activeElement as HTMLElement)
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); onOpenChange(false); button.current?.focus(); return }
    const next = event.key === 'ArrowDown' ? Math.min(items.length - 1, index + 1)
      : event.key === 'ArrowUp' ? Math.max(0, index - 1)
      : event.key === 'Home' ? 0
      : event.key === 'End' ? items.length - 1
      : null
    if (next === null) return
    event.preventDefault()
    items[next]?.focus()
  }

  return <div ref={rootRef} className={styles.dropdown} data-open={open || undefined}>
    <button ref={button} type="button" className={styles.dropdownButton} data-invalid={invalid || undefined} aria-label={label}
      aria-haspopup="listbox" aria-expanded={open} aria-controls={open ? listId : undefined} disabled={disabled}
      onClick={() => onOpenChange(!open)}>
      <span className={selected ? undefined : styles.placeholder}>{selected ? selected[1] : label}</span>
      <ChevronDownIcon size={16} />
    </button>
    {open && <ul ref={listRef} id={listId} role="listbox" aria-label={label} className={styles.list} onKeyDown={onListKey}>
      {options.map(([code, name]) => (
        <li key={code} role="option" aria-selected={code === value} tabIndex={-1}
          onClick={() => onPick(code)}
          onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onPick(code) } }}>
          {name}{code === value && <TickIcon size={14} />}
        </li>
      ))}
    </ul>}
  </div>
}

export function FamilyGate({ draft, error, fresh = false, onChange }: { readonly draft: ConditionDraft; readonly error: string | null; readonly fresh?: boolean; readonly onChange: (next: ConditionDraft) => void }) {
  const questionId = useId()
  const value = draft.familyDetails
  return <div id="condition-field-FAMILY_GATE" data-condition-field="FAMILY_GATE" data-error={error ? 'true' : undefined}
    className={[styles.gate, fresh ? styles.reveal : ''].filter(Boolean).join(' ')}>
    <p className={styles.question} id={questionId}>가족 정보도 입력할까요?<span className={styles.tag}>선택</span></p>
    <p className={styles.help}>가족의 주택·자동차·총자산과 세대 소득을 입력하면 세대 기준 조건까지 비교할 수 있어요.</p>
    <div className={styles.control}>
      <div className={styles.pair} role="radiogroup" aria-labelledby={questionId}>
        <Choice checked={value === 'PROVIDED'} onSelect={() => onChange(setFamilyDetails(draft, 'PROVIDED'))}>입력할게요</Choice>
        <Choice checked={value === 'SKIPPED'} onSelect={() => onChange(setFamilyDetails(draft, 'SKIPPED'))}>다음에 할게요</Choice>
      </div>
    </div>
    {value === 'SKIPPED' && <p className={styles.help}>마이페이지의 내 조건 관리나 공고의 △ 항목에서 언제든 추가할 수 있어요.</p>}
    {error && <p className={styles.error} role="alert">{error}</p>}
  </div>
}
