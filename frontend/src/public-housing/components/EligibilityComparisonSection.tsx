import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { Button } from '../../design-system/components/Button'
import { useConditions } from '../../user/conditions/ConditionsContext'
import { FIELD_COPY } from '../../user/conditions/conditionFieldCopy'
import { formatDots, formatMonthDay, withJosa } from '../../user/conditions/conditionFormat'
import { DocIcon, InfoIcon, PencilIcon, PlusIcon, StatusMark, WarnIcon } from '../../user/conditions/conditionIcons'
import { markOfCheck, markOfType } from '../../user/conditions/conditionMarks'
import type { AnnouncementRef, CheckView, EvidenceRef, ProfileField, TypeView } from '../../user/conditions/conditionsModel'
import {
  CHECK_STATUS_LABEL,
  GUIDANCE_KIND_LABEL,
  evidenceLabel,
  missingNote,
  sortChecks,
  sortTypes,
  typeChipRows,
  typeStatusText,
  verdictCopy,
} from '../presentation/comparisonPresentation'
import { HouseCharacterIcon } from './HouseCharacterIcon'
import { RequestError } from './RequestError'
import { useConditionComparison } from './useConditionComparison'
import { hasLoginHandoff, markNudgeShown, nudgeShownThisVisit, nudgeSnoozed } from '../../user/conditions/conditionStorage'
import styles from './EligibilityComparisonSection.module.css'

export interface EligibilitySectionDetail {
  readonly announcementId: string
  readonly title: string | null
  readonly rentalTypeLabel: string
  readonly applicationStatus: string | null
  readonly targets: readonly string[]
}

export interface EvidenceTarget {
  readonly evidence: EvidenceRef
  readonly label: string
}

type Open = ReturnType<typeof useConditions>['openInput']

export function EligibilityComparisonSection({ detail, onOpenEvidence }: { readonly detail: EligibilitySectionDetail; readonly onOpenEvidence: (target: EvidenceTarget) => void }) {
  const conditions = useConditions()
  const headingId = useId()
  const ref = useMemo<AnnouncementRef>(() => ({
    announcementId: detail.announcementId, title: detail.title, rentalType: detail.rentalTypeLabel,
    applicationStatus: detail.applicationStatus, targets: detail.targets,
  }), [detail.announcementId, detail.title, detail.rentalTypeLabel, detail.applicationStatus, detail.targets])
  const { status, view, loadedRevision, retry } = useConditionComparison(conditions.mode === 'member' ? ref : null)
  const { mode, hasProfile, openNudge } = conditions
  const comparable = view?.availability === 'AVAILABLE' && detail.applicationStatus !== 'CLOSED'

  // 비교할 수 있는 공고를 처음 연 미입력 회원에게 이번 방문 동안 한 번만 권한다.
  useEffect(() => {
    if (mode !== 'member' || hasProfile || !comparable) return
    // 로그인하며 넘겨받은 입력을 저장하는 중이면 묻지 않는다.
    if (hasLoginHandoff() || nudgeShownThisVisit() || nudgeSnoozed() || document.querySelector('dialog[open]')) return
    markNudgeShown()
    openNudge('detail', { announcementId: detail.announcementId })
  }, [comparable, detail.announcementId, hasProfile, mode, openNudge])

  if (conditions.mode === 'disabled') return null
  const compared = conditions.mode === 'member' && conditions.hasProfile
  const closed = detail.applicationStatus === 'CLOSED'
  if (!compared && closed) return null

  const head = (extra: React.ReactNode = null, sub: string | null = null) => <div className={styles.head}>
    <h3 id={headingId}>내 조건 비교</h3>{extra}{sub && <p className={styles.sub}>{sub}</p>}
  </div>

  let body: React.ReactNode = null
  if (mode === 'guest') {
    body = <Invite detail={detail} labels={[]} />
  } else if (status === 'error') {
    body = <RequestError title="내 조건 비교를 불러오지 못했어요" onRetry={retry} />
  } else if (!view) {
    return null
  } else if (view.availability === 'NOT_STRUCTURED') {
    body = <div className={styles.soon}><span className={styles.soonIcon}><HouseCharacterIcon size={22} /></span><div><strong>자격 비교 준비 중</strong>신청 자격은 공고문에서 확인해 주세요.</div></div>
  } else if (view.availability === 'OUT_OF_SCOPE') {
    body = <div className={styles.soon}><div><strong>개인 비교 대상이 아니에요</strong>기업이 신청하는 공고라 개인 비교 대상이 아니에요.</div></div>
  } else if (!compared) {
    body = <Invite detail={detail} labels={view.types.map((type) => type.label)} />
  } else {
    const saved = conditions.stored?.savedAt ? formatMonthDay(conditions.stored.savedAt) : null
    return <section className={styles.section} aria-labelledby={headingId}>
      {head(<Button size="sm" variant="secondary" onClick={() => conditions.openInput({ mode: 'full', step: 1, origin: 'detail' }, { announcementId: detail.announcementId })}>내 조건 수정</Button>,
        `비교 기준일 ${formatDots(view.referenceDate)}${saved ? ` · ${saved} 저장한 내 조건 기준` : ''}`)}
      <Comparison key={detail.announcementId} announcementId={detail.announcementId} types={view.types} revision={loadedRevision}
        required={view.requiredQualification} onOpenEvidence={onOpenEvidence} />
      <p className={styles.note}><InfoIcon size={15} /><span>입력하신 정보로 비교한 기본 조건이에요. 최종 자격과 순위는 공고문과 공적 자료로 확정돼요.</span></p>
    </section>
  }
  return <section className={styles.section} aria-labelledby={headingId}>{head()}{body}</section>
}

function Invite({ detail, labels }: { readonly detail: EligibilitySectionDetail; readonly labels: readonly string[] }) {
  const { memberDraft, mode, openInput, restartInput } = useConditions()
  const draft = mode === 'member' ? memberDraft : null
  const done = draft !== null && draft.step > 3
  const [top, bottom] = typeChipRows(labels)
  const chip = (label: string) => <span key={label} className={styles.chip} role="listitem">{label}<i aria-label="미확인">?</i></span>
  return <div className={styles.invite}>
    <p className={styles.inviteTitle}>{done ? '입력 완료! 결과만 남았어요' : draft ? '작성하던 내 조건이 있어요' : '이 공고, 나도 신청할 수 있을까?'}</p>
    <p className={styles.inviteSub}>{done ? '저장하면 바로 비교해 드려요' : draft ? `${draft.step}단계까지 입력했어요` : '신청 유형별 기본 조건을 비교해 드려요'}</p>
    {labels.length > 0 && <div className={styles.chips} role="list" aria-label="이 공고의 신청 유형">
      <div className={styles.chipRow}>{top.map(chip)}</div>
      {bottom.length > 0 && <div className={styles.chipRow}>{bottom.map(chip)}</div>}
    </div>}
    <Button size="lg" className={styles.wide} onClick={() => openInput({ mode: 'full', step: 1, origin: 'detail' }, { announcementId: detail.announcementId, resume: true })}>
      {done ? '저장하고 결과 보기' : draft ? '이어서 입력하기' : '지금 확인해 보기'}
    </Button>
    {draft && <button type="button" className={styles.textButton} onClick={() => restartInput('detail', detail.announcementId)}>처음부터 다시 입력</button>}
  </div>
}

function openFix(openInput: Open, fields: readonly ProfileField[], announcementId: string) {
  // 가족 형태는 뒤 질문이 모두 바뀌므로 3단계 전체를 연다.
  if (fields.includes('FAMILY_TYPE')) openInput({ mode: 'full', step: 3, origin: 'detail' }, { announcementId })
  else openInput({ mode: 'partial', fields, origin: 'detail' }, { announcementId })
}

function Comparison({ announcementId, types: raw, revision, required, onOpenEvidence }: {
  readonly announcementId: string
  readonly types: readonly TypeView[]
  readonly revision: number
  readonly required: { readonly title: string; readonly body: string; readonly items: readonly string[]; readonly evidence: EvidenceRef | null } | null
  readonly onOpenEvidence: (target: EvidenceTarget) => void
}) {
  const { lastSave, notify } = useConditions()
  const types = useMemo(() => sortTypes(raw), [raw])
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const selected = types.find((type) => type.key === selectedKey) ?? types[0]
  // 항목 하나를 채우거나 고친 뒤에만 바뀐 줄을 짚어 준다.
  const just = useJustChanged(types, revision, lastSave?.kind === 'partial' && lastSave.revision === revision)
  const tabsId = useId()
  const tabRefs = useRef(new Map<string, HTMLButtonElement>())
  const notified = useRef(revision)

  useEffect(() => {
    if (!lastSave || lastSave.revision !== revision || notified.current === revision) return
    notified.current = revision
    if (lastSave.kind !== 'partial' || lastSave.origin !== 'detail' || lastSave.announcementId !== announcementId || !selected) return
    const label = lastSave.fields.length === 1 ? FIELD_COPY[lastSave.fields[0]].label : '추가 정보'
    notify(`${withJosa(label, '을/를')} 저장했어요 · ${selected.label}: ${typeStatusText(selected)}`)
  }, [announcementId, lastSave, notify, revision, selected])

  if (!selected) return null
  const onTabKey = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const next = event.key === 'ArrowRight' ? (index + 1) % types.length
      : event.key === 'ArrowLeft' ? (index - 1 + types.length) % types.length
      : event.key === 'Home' ? 0
      : event.key === 'End' ? types.length - 1
      : null
    if (next === null) return
    event.preventDefault()
    setSelectedKey(types[next].key)
    tabRefs.current.get(types[next].key)?.focus()
  }
  const requiredBox = required && <div className={styles.required} role="note">
    <div className={styles.requiredHead}><WarnIcon size={18} />{required.title}</div>
    <p>{required.body}</p>
    <ul>{required.items.map((item) => <li key={item}>{item}</li>)}</ul>
    {required.evidence && <EvidenceButton evidence={required.evidence} label={required.title} onOpen={onOpenEvidence} />}
  </div>
  if (types.length === 1) {
    return <>{requiredBox}<div className={`${styles.panel} ${styles.single}`}><TypePanel announcementId={announcementId} type={selected} just={just} onOpenEvidence={onOpenEvidence} /></div></>
  }
  return <>
    {requiredBox}
    <div className={styles.tabs} role="tablist" aria-label="신청 유형">
      {types.map((type, index) => <button key={type.key} type="button" role="tab" id={`${tabsId}-${type.key}`}
        ref={(node) => { if (node) tabRefs.current.set(type.key, node); else tabRefs.current.delete(type.key) }}
        aria-selected={type === selected} aria-controls={`${tabsId}-panel`} tabIndex={type === selected ? 0 : -1}
        className={styles.tab} onClick={() => setSelectedKey(type.key)} onKeyDown={(event) => onTabKey(event, index)}>
        <span className={styles.tabLabel}><StatusMark status={markOfType(type.status)} size={15} className={styles.mark} />{type.label}</span>
        <span className={styles.visuallyHidden}>, {typeStatusText(type)}</span>
      </button>)}
    </div>
    <div className={styles.panel} role="tabpanel" id={`${tabsId}-panel`} aria-labelledby={`${tabsId}-${selected.key}`}>
      <TypePanel announcementId={announcementId} type={selected} just={just} onOpenEvidence={onOpenEvidence} />
    </div>
  </>
}

/** 저장 뒤 상태가 바뀐 항목을 6초 동안 표시한다. */
function useJustChanged(types: readonly TypeView[], revision: number, enabled: boolean) {
  const previous = useRef<{ revision: number; statuses: Map<string, string> } | null>(null)
  const [just, setJust] = useState<ReadonlySet<string>>(new Set())
  useEffect(() => {
    const statuses = new Map(types.flatMap((type) => type.checks.map((check) => [`${type.key}:${check.id}`, check.status] as const)))
    const before = previous.current
    previous.current = { revision, statuses }
    if (!enabled || !before || before.revision === revision) return
    const changed = new Set([...statuses].filter(([key, status]) => before.statuses.has(key) && before.statuses.get(key) !== status).map(([key]) => key))
    if (!changed.size) return
    setJust(changed)
    const timer = setTimeout(() => setJust(new Set()), 6000)
    return () => clearTimeout(timer)
  }, [types, revision, enabled])
  return just
}

function EvidenceButton({ evidence, label, onOpen, inline = false }: { readonly evidence: EvidenceRef; readonly label: string; readonly onOpen: (target: EvidenceTarget) => void; readonly inline?: boolean }) {
  const text = evidenceLabel(evidence)
  return <button type="button" className={inline ? styles.evidenceInline : styles.evidence} aria-label={`근거 공고문 ${text} 열기`} onClick={() => onOpen({ evidence, label })}>
    <DocIcon size={inline ? 11 : 13} />{text}
  </button>
}

function TypePanel({ announcementId, type, just, onOpenEvidence }: { readonly announcementId: string; readonly type: TypeView; readonly just: ReadonlySet<string>; readonly onOpenEvidence: (target: EvidenceTarget) => void }) {
  const { openInput } = useConditions()
  const copy = verdictCopy(type)
  const checks = sortChecks(type.checks)
  const firstUnmet = checks.find((check) => check.status === 'NOT_SATISFIED')
  return <>
    <div className={styles.verdict} data-status={type.status}>
      <span className={styles.verdictIcon}><StatusMark status={markOfType(type.status)} size={26} className={styles.mark} /></span>
      <div>
        <strong>{copy.title}</strong>
        <p>{copy.message}</p>
        {(type.status === 'INPUT_NEEDED' && copy.missingFields.length > 1 || copy.showFix && firstUnmet) && <div className={styles.verdictActions}>
          {type.status === 'INPUT_NEEDED' && copy.missingFields.length > 1 && <Button size="sm" variant="secondary"
            onClick={() => openInput({ mode: 'partial', fields: copy.missingFields, origin: 'detail' }, { announcementId })}>△ 항목 한 번에 입력하기</Button>}
          {copy.showFix && firstUnmet && <Button size="sm" variant="secondary" onClick={() => openFix(openInput, firstUnmet.fields, announcementId)}>내 정보 수정하기</Button>}
        </div>}
      </div>
    </div>
    <div className={styles.body}>
      <ul className={styles.checks} aria-label={`${type.label} 항목별 비교`}>
        {checks.map((check) => <CheckRow key={check.id} announcementId={announcementId} check={check} type={type} just={just.has(`${type.key}:${check.id}`)} onOpenEvidence={onOpenEvidence} />)}
      </ul>
      {type.units && <p className={styles.units}>모집 주택형 · <b>{type.units}</b></p>}
      {type.guidances.length > 0 && <div className={styles.direct}>
        <h4>신청 전 직접 확인할 것</h4>
        <ul>{type.guidances.map((guidance) => <li key={`${guidance.kind}:${guidance.title}`}>
          <div><span className={styles.kind} data-kind={guidance.kind}>{GUIDANCE_KIND_LABEL[guidance.kind]}</span><strong>{guidance.title}</strong><p>{guidance.body}</p></div>
          {guidance.evidence && <EvidenceButton evidence={guidance.evidence} label={guidance.title} onOpen={onOpenEvidence} />}
        </li>)}</ul>
      </div>}
    </div>
  </>
}

function CheckRow({ announcementId, check, type, just, onOpenEvidence }: { readonly announcementId: string; readonly check: CheckView; readonly type: TypeView; readonly just: boolean; readonly onOpenEvidence: (target: EvidenceTarget) => void }) {
  const { openInput } = useConditions()
  const note = missingNote(check, type.status)
  const field = check.fields[0]
  return <li className={styles.check} data-status={check.status} data-just={just || undefined}>
    <span className={styles.rowMark} data-status={check.status}>
      <StatusMark status={markOfCheck(check.status)} size={18} bold label={CHECK_STATUS_LABEL[check.status]} className={styles.mark} />
    </span>
    <div className={styles.main}>
      <div className={styles.top}><strong>{check.label}</strong>{just && <span className={styles.justTag}>방금 확인했어요</span>}</div>
      <p className={styles.criterion}>{check.status === 'DIRECT_CHECK_REQUIRED' ? check.criterion : `기준 ${check.criterion}${check.basis ? ` (${check.basis})` : ''}`}</p>
      {note && <p className={note.quiet ? `${styles.missing} ${styles.quiet}` : styles.missing}>{note.text}</p>}
    </div>
    <div className={styles.side}>
      {check.status === 'SATISFIED' && <b className={styles.mine}>{check.mine}</b>}
      {check.status === 'NOT_SATISFIED' && field && <button type="button" className={styles.editChip} aria-label={`${FIELD_COPY[field].label} 수정, 지금 값 ${check.mine ?? ''}`}
        onClick={() => openFix(openInput, check.fields, announcementId)}>{check.mine}<PencilIcon size={14} /></button>}
      {check.status === 'INPUT_NEEDED' && field && <button type="button" className={styles.editChip} aria-label={`${FIELD_COPY[field].label} 입력하기`}
        onClick={() => openInput({ mode: 'partial', fields: check.fields, origin: 'detail' }, { announcementId })}>입력하기<PlusIcon size={14} /></button>}
      {check.evidence && <EvidenceButton evidence={check.evidence} label={check.label} onOpen={onOpenEvidence} inline />}
    </div>
  </li>
}
