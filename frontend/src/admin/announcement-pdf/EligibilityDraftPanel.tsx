import { useEffect, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { Evidence } from './extraction'
import type { ExtractedEligibility } from './extractedEligibility'
import { EligibilityEditor } from './EligibilityEditor'
import { applyEligibility, checkedHow, checkTarget, createEligibilityDraft, isNumberWarning, itemTarget, KIND_NAMES, loadEligibility, moveToDirectCheck, previewEligibilityDraft, requirementWarnings, ruleText, unjudgedReason, unmatchedWarnings, type Check, type Content, type NumberCheck, type RuleSet } from './eligibilityDraftApi'
import styles from './PdfCorrection.module.css'

const RECRUITMENT: Record<string, string> = { RECRUITING: '모집', UNKNOWN: '모집수 미확인', UPCOMING: '모집 예정', NOT_RECRUITING: '모집 안 함', CANCELLED: '취소' }

/**
 * PDF 보정의 신청자격 탭. 보정을 적용하면 최신 추출의 AI 신청자격과 적용한 공급행으로 신청자격을 만들어 곧바로 사용자에게 반영하고(공고가
 * 공개 전이면 저장만 한다), 잘못 읽힌 조건은 지우지 않고 '판정에서 빼기'로 공고문에서 확인할 자격으로 옮겨 곧바로 반영한다.
 * '직접 수정'을 누르면 탭 이름·조건 값·공고문에서 확인할 자격을 고쳐 곧바로 반영할 수 있다(EligibilityEditor).
 *
 * pdfActions가 false면(관리자 공고 상세의 신청자격 탭) PDF로 다시 반영하는 버튼과 PDF 안내를 두지 않는다.
 * onEvidence가 없으면(PDF 창이 없는 적용 결과 화면) 근거 버튼을 두지 않는다.
 * autoCreate면(보정 적용 직후) 누르지 않아도 최신 PDF 추출로 신청자격을 다시 만들어 반영한다. 적용한 공급행이 바뀌었을 수 있어 이미 있는 것도 바꾼다.
 * extracted는 보정 적용 전 탭이 넘기는, PDF에서 이미 뽑은 신청자격이다(없으면 null). 아직 신청자격이 없는 동안 서버 변환 미리보기를 보이고,
 * 미리보기를 받지 못하면 뽑은 내용을 그대로 보인다. 신청자격은 저장된 공급행에 경로를 이어 만들므로 적용 전에는 만들기 버튼을 두지 않는다.
 * warnings는 워커가 신청자격을 읽으며 남긴 경고('숫자 확인 필요' 등)다. 숫자가 원문과 맞지 않아도 판정에 쓰므로, 요건 문구가 같은 조건·목록 곁에
 * 어떻게 확인했는지와 함께 보이고, 어디에도 붙지 않은 경고만 위에 모은다. warnings가 없으면(적용 결과 화면) 숫자 대조 결과는 말하지 않는다.
 */
export function EligibilityDraftPanel({ announcementId, pdfSha256, onEvidence, autoCreate = false, extracted, warnings, pdfActions = true }: { announcementId: string; pdfSha256: string | null; onEvidence?: (item: Evidence) => void; autoCreate?: boolean; extracted?: ExtractedEligibility | null; warnings?: string[]; pdfActions?: boolean }) {
  const [current, setCurrent] = useState<RuleSet | null>(null)
  const [editing, setEditing] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ kind: 'error' | 'notice'; text: string } | null>(null)
  const [notices, setNotices] = useState<string[]>([])
  const [preview, setPreview] = useState<{ content: Content | null; error: string | null } | null>(null)
  const previewing = loaded && !current && !autoCreate && !!extracted && !!pdfSha256

  useEffect(() => {
    if (!previewing) return
    const controller = new AbortController()
    previewEligibilityDraft(announcementId, pdfSha256!, controller.signal)
      .then(value => setPreview({ content: value, error: null }))
      .catch(error => { if (!controller.signal.aborted) setPreview({ content: null, error: errorText(error) }) })
    return () => controller.abort()
  }, [announcementId, pdfSha256, previewing])

  useEffect(() => {
    const controller = new AbortController()
    loadEligibility(announcementId, controller.signal)
      .then(value => { setCurrent(value); setLoaded(true); if (autoCreate && pdfSha256) make(pdfSha256, value) })
      .catch(error => { if (!controller.signal.aborted) { setMessage({ kind: 'error', text: errorText(error) }); setLoaded(true) } })
    return () => controller.abort()
    // 적용 결과 화면은 공고와 PDF가 바뀌지 않는다. 자동 반영은 첫 조회 때 한 번만 한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [announcementId])

  async function run(action: () => Promise<string>) {
    setBusy(true); setMessage(null)
    try {
      setMessage({ kind: 'notice', text: await action() })
    } catch (error) {
      setMessage({ kind: 'error', text: errorText(error) })
    } finally {
      setBusy(false)
    }
  }

  function create() {
    if (!pdfSha256) return
    if (current && !window.confirm('최신 PDF 추출로 신청자격을 다시 만들어 사용자 화면에 반영할까요? 판정에서 뺀 조건도 처음 상태로 돌아가요.')) return
    make(pdfSha256, current)
  }

  function make(sha256: string, base: RuleSet | null) {
    setNotices([])
    void run(async () => {
      const created = await createEligibilityDraft(announcementId, sha256, base)
      setCurrent(created.ruleSet); setNotices(created.notices)
      return created.published ? '신청자격을 사용자 화면에 반영했어요.' : '신청자격을 저장했어요.'
    })
  }

  function exclude(content: Content) {
    if (!current) return
    void run(async () => {
      const applied = await applyEligibility(announcementId, current, content)
      setCurrent(applied.ruleSet)
      if (applied.published) return '판정에서 뺐어요. 사용자 화면에 반영했어요.'
      return applied.saved ? `판정에서 뺀 내용을 저장했지만 사용자 화면에는 아직 반영하지 못했어요. ${applied.reason}`
        : `판정에서 빼지 못했어요. ${applied.reason}`
    })
  }

  const content = current?.content
  const shown = content ?? preview?.content ?? null
  // 조건·목록 문구에 붙은 경고는 그 곁에 보인다. 신청자격·미리보기가 없으면 뽑은 요건 문구에 붙인다.
  const targets = shown ? [...shown.commonChecks, ...shown.paths.flatMap(item => item.checks)].map(checkTarget)
    .concat(shown.guidances.flatMap(item => item.items).map(itemTarget))
    : extracted?.groups.flatMap(group => group.requirements.map(item => ({ label: item.text, kind: KIND_NAMES[item.kind] }))) ?? []
  const loose = unmatchedWarnings(targets, warnings ?? [])
  const numbers: NumberCheck = warnings === undefined ? 'unknown' : loose.some(isNumberWarning) ? 'unmatched' : 'checked'
  const live = current?.status === 'PUBLISHED'
  if (current && editing) {
    return <section aria-labelledby="eligibility-draft-title">
      <h2 id="eligibility-draft-title">신청자격</h2>
      <EligibilityEditor announcementId={announcementId} ruleSet={current} onCancel={latest => { setCurrent(latest); setEditing(false) }} onSaved={applied => {
        setCurrent(applied.ruleSet); setEditing(false); setNotices([])
        setMessage(applied.published ? { kind: 'notice', text: '고친 신청자격을 사용자 화면에 반영했어요.' }
          : { kind: 'error', text: `고친 내용을 저장했지만 사용자 화면에는 아직 반영하지 못했어요. ${applied.reason}` })
      }} />
    </section>
  }
  return <section aria-labelledby="eligibility-draft-title">
    <h2 id="eligibility-draft-title">신청자격</h2>
    {current && <p>PDF에서 뽑은 신청자격과 저장된 공급정보로 만들었어요. 탭 이름·조건 값이 틀렸으면 &lsquo;직접 수정&rsquo;에서 고쳐요. 잘못 읽힌 조건은 &lsquo;판정에서 빼기&rsquo;를 누르면 그 조건으로 판정하지 않고, 사용자에게는 공고문에서 확인할 자격으로 보여요. 그 신청 경로는 &lsquo;지원 가능&rsquo;으로 확정하지 않아요.</p>}
    <div className={styles.actions}>
      {current && <Button variant={pdfActions ? 'secondary' : 'primary'} onClick={() => { setMessage(null); setEditing(true) }} disabled={busy}>직접 수정</Button>}
      {pdfActions && (current || autoCreate) && <Button onClick={create} disabled={busy || !loaded || !pdfSha256}>{current ? 'PDF 추출로 다시 반영' : '신청자격 반영하기'}</Button>}
    </div>
    {pdfActions && !pdfSha256 && <p className={styles.hint}>PDF를 열면 신청자격을 반영할 수 있어요.</p>}
    {message && <p role={message.kind === 'error' ? 'alert' : 'status'} className={message.kind === 'error' ? styles.warningNote : styles.hint}>{message.text}</p>}
    {notices.length > 0 && <div className={styles.warningNote}><p><strong>확인할 점</strong></p><ul>{notices.map(item => <li key={item}>{item}</li>)}</ul></div>}
    {loose.length > 0 && <div className={styles.warningNote}><p><strong>PDF에서 읽으며 확인할 점</strong></p><ul>{loose.map(item => <li key={item}>{item}</li>)}</ul></div>}
    {loaded && !current && !autoCreate && (extracted && preview?.content
      ? <>
        <p>보정 내용을 적용하면 아래 조건과 저장된 공급정보로 신청자격이 만들어져 사용자 화면에 곧바로 반영돼요.</p>
        <Converted content={preview.content} warnings={warnings} numbers={numbers} onEvidence={onEvidence} />
      </>
      : extracted
      ? <>
        <p>보정 내용을 적용하면 아래 내용과 저장된 공급정보로 신청자격이 만들어져 사용자 화면에 곧바로 반영돼요.</p>
        {preview?.error && <p role="alert" className={styles.warningNote}>{`판정 조건 미리보기를 불러오지 못해 뽑은 내용을 그대로 보여요. ${preview.error}`}</p>}
        {extracted.groups.map((group, index) => <section key={index} aria-label={group.title}>
          <h3>{group.title}</h3>
          {group.requirements.length === 0 && group.qualifications.length === 0 && <p className={styles.hint}>뽑은 요건이 없어요.</p>}
          {group.requirements.length > 0 && <ul>{group.requirements.map((item, row) => <li key={row}>
            <span>{item.text}</span>{' '}{item.evidence && onEvidence && <Button data-design-system className={styles.quietButton} onClick={() => onEvidence(item.evidence!)}>{`근거 p.${item.evidence.page} 열기`}</Button>}
            <Reasons reasons={requirementWarnings({ label: item.text, kind: KIND_NAMES[item.kind] }, warnings ?? [])} />
          </li>)}</ul>}
          {group.qualifications.length > 0 && <p>해당하는지 공고문에서 확인할 자격: {group.qualifications.join(', ')}</p>}
        </section>)}
        {extracted.exclusions.length > 0 && <><h3>신청 제한</h3><ul>{extracted.exclusions.map((item, index) => <li key={index}><strong>{item.title}</strong> {item.body}</li>)}</ul></>}
      </>
      : <p className={styles.hint}>{extracted === null ? '이 PDF 추출 결과에는 신청자격이 없어요. PDF를 다시 추출해 주세요.' : pdfActions ? '아직 신청자격이 없어요.' : '아직 신청자격이 없어요. \'PDF로 보정\'에서 PDF를 추출하고 보정을 적용하면 만들어져요.'}</p>)}
    {loaded && !current && autoCreate && !message && !busy && <p className={styles.hint}>아직 신청자격이 없어요.</p>}
    {content && <>
      <p className={live ? styles.hint : styles.pendingLabel}>{live
        ? `사용자 화면에 반영됨 · 기준일 ${content.metadata.referenceDate}`
        : `아직 사용자 화면에 반영되지 않았어요. 공고가 공개 전이면 공고를 공개한 뒤 보정을 다시 적용하면 반영돼요. · 기준일 ${content.metadata.referenceDate}`}</p>
      <Scope title="모든 신청자에게 적용" scopeId="common" checks={content.commonChecks} content={content} warnings={warnings} numbers={numbers} busy={busy}
        onExclude={check => exclude(moveToDirectCheck(content, 'common', check.id))} onEvidence={onEvidence} />
      {content.paths.map(path => <Scope key={path.id} title={path.label} scopeId={path.id} checks={path.checks} content={content} warnings={warnings} numbers={numbers} busy={busy}
        onExclude={check => exclude(moveToDirectCheck(content, path.id, check.id))} onEvidence={onEvidence} />)}
      <Exclusions content={content} />
    </>}
  </section>
}

/** 보정 적용 전 미리보기. 공급행에 잇기 전이라 신청 단위는 보이지 않고 조건을 바꿀 수 없다. */
function Converted({ content, warnings, numbers, onEvidence }: { content: Content; warnings?: string[]; numbers: NumberCheck; onEvidence?: (item: Evidence) => void }) {
  return <>
    <Scope title="모든 신청자에게 적용" scopeId="common" checks={content.commonChecks} content={content} warnings={warnings} numbers={numbers} onEvidence={onEvidence} />
    {content.paths.map(path => <Scope key={path.id} title={path.label} scopeId={path.id} checks={path.checks} content={content} warnings={warnings} numbers={numbers} onEvidence={onEvidence} />)}
    <Exclusions content={content} />
  </>
}

function Exclusions({ content }: { content: Content }) {
  const items = content.guidances.filter(item => item.kind === 'APPLICATION_EXCLUSION')
  return items.length > 0 ? <><h3>신청 제한</h3><ul>{items.map(item => <li key={item.id}><strong>{item.title}</strong> {item.body}</li>)}</ul></> : null
}

/** onExclude가 없으면 읽기 전용(미리보기)이다. */
function Scope({ title, scopeId, checks, content, warnings, numbers, busy = false, onExclude, onEvidence }: {
  title: string; scopeId: string; checks: Check[]; content: Content; warnings?: string[]; numbers: NumberCheck; busy?: boolean; onExclude?: (check: Check) => void; onEvidence?: (item: Evidence) => void
}) {
  const common = scopeId === 'common'
  const path = content.paths.find(item => item.id === scopeId)
  const units = content.options.filter(option => option.pathId === scopeId)
    .map(option => `${content.supplyUnits.find(unit => unit.id === option.supplyUnitId)?.label ?? option.supplyUnitId} (${RECRUITMENT[option.recruitmentStatus] ?? option.recruitmentStatus})`)
  const qualifications = content.qualifications.filter(item => path?.qualificationIds.includes(item.id))
  const inScope = content.guidances.filter(item =>
    item.targets.some(target => common ? target.scope === 'ANNOUNCEMENT' : target.scope === 'PATH' && target.pathId === scopeId))
  const direct = inScope.filter(item => item.kind === 'REQUIRED_QUALIFICATION').flatMap(item => item.items)
  // 어느 조건의 대체 안내도 아닌 필수 확인은 판정을 막지 않고 신청 전 확인으로만 보인다.
  const fallbacks = new Set([...content.commonChecks, ...content.paths.flatMap(item => item.checks)].map(check => check.fallbackGuidanceId))
  const listed = inScope.filter(item => item.kind === 'REQUIRED_CHECK' && !fallbacks.has(item.id))
  const listedItems = listed.flatMap(item => item.items)
  function evidence(ids: string[]) {
    const item = content.evidences.find(value => ids.includes(value.id))
    return item && onEvidence ? <Button data-design-system className={styles.quietButton} onClick={() => onEvidence({ path: item.id, page: item.firstPage, quote: item.description ?? '' })}>{`근거 p.${item.firstPage} 열기`}</Button> : null
  }
  return <section aria-label={title}>
    <h3>{title}</h3>
    {onExclude && units.length > 0 && <p className={styles.hint}>신청 단위: {units.join(', ')}</p>}
    {checks.length === 0 && qualifications.length === 0 && direct.length === 0 && listedItems.length === 0 && <p className={styles.hint}>자동 판정 조건이 없어요.</p>}
    {checks.length > 0 && <ul>{checks.map(check => <li key={check.id}>
      <span>{ruleText(check.rule)}</span>{check.appliesWhen && <span className={styles.hint}> (가산 대상은 공고문에서 확인)</span>} <span className={styles.hint}>원문: {check.label}</span> {evidence(check.evidenceIds)}
      <CheckedHow check={check} content={content} warnings={warnings} numbers={numbers} />
      {onExclude && <>{' '}<Button variant="secondary" disabled={busy} onClick={() => onExclude(check)}>판정에서 빼기</Button></>}
    </li>)}</ul>}
    {qualifications.length > 0 && <p>해당하는지 공고문에서 확인할 자격: {qualifications.map(item => item.exactName).join(', ')} {evidence(qualifications.flatMap(item => item.evidenceIds))}</p>}
    {direct.length > 0 && <><p>판정하지 않고 공고문에서 확인할 조건:</p><ul>{direct.map((item, index) => <li key={index}>{item}<Reasons reasons={requirementWarnings(itemTarget(item), warnings ?? [])} /></li>)}</ul></>}
    {listedItems.length > 0 && <><p>신청 전에 확인할 자격 (판정에 넣지 않음): {evidence(listed.flatMap(item => item.evidenceIds))}</p><ul>{listedItems.map((item, index) => <li key={index}>{item}<Reasons reasons={requirementWarnings(itemTarget(item), warnings ?? [])} /></li>)}</ul></>}
  </section>
}

/** 조건을 어떻게 확인했는지. 원문 대조에서 확인하지 못한 점이 있으면 확인 필요로 눈에 띄게 보인다. */
function CheckedHow({ check, content, warnings, numbers }: { check: Check; content: Content; warnings?: string[]; numbers: NumberCheck }) {
  const reasons = requirementWarnings(checkTarget(check), warnings ?? [])
  const text = checkedHow(check, content, reasons, numbers)
  return reasons.length > 0
    ? <p className={styles.pendingLabel}><strong>확인 필요</strong> {text}</p>
    : <p className={styles.hint}>확인: {text}</p>
}

/** 판정에 쓰지 않는 항목(목록·직접 확인)이 왜 그렇게 됐는지(근거 쪽에서 문구를 못 찾음 등). */
function Reasons({ reasons }: { reasons: string[] }) {
  return reasons.length > 0 ? <p className={styles.pendingLabel}>{`어떻게 확인했나: ${reasons.map(unjudgedReason).join(' ')}`}</p> : null
}

function errorText(error: unknown) {
  return error instanceof Error ? error.message : '신청자격을 처리하지 못했어요. 다시 시도해 주세요.'
}
