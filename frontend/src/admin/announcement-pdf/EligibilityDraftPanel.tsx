import { useEffect, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { Evidence } from './extraction'
import type { ExtractedEligibility } from './extractedEligibility'
import { checkedHow, checkTarget, createEligibilityDraft, isNumberWarning, itemTarget, KIND_NAMES, loadEligibilityDraft, moveToDirectCheck, previewEligibilityDraft, publishEligibilityDraft, requirementWarnings, ruleText, saveEligibilityDraft, unjudgedReason, unmatchedWarnings, type Check, type Content, type NumberCheck, type RuleSet } from './eligibilityDraftApi'
import styles from './PdfCorrection.module.css'

const RECRUITMENT: Record<string, string> = { RECRUITING: '모집', UNKNOWN: '모집수 미확인', UPCOMING: '모집 예정', NOT_RECRUITING: '모집 안 함', CANCELLED: '취소' }

/**
 * PDF 보정의 신청자격 탭. 최신 추출의 AI 신청자격과 적용한 공급행으로 만든 초안을 보이고, 잘못 읽힌 조건은 지우지 않고 직접 확인으로 바꾼 뒤
 * 검토 완료로 표시해 공개한다. 조건 숫자 고치기는 이 화면에서 하지 않는다.
 */
/**
 * onEvidence가 없으면(PDF 창이 없는 적용 결과 화면) 근거 버튼을 두지 않는다.
 * autoCreate면(보정 적용 직후) 초안이 없을 때 누르지 않아도 최신 PDF 추출로 초안을 만든다. 이미 있는 초안은 덮어쓰지 않는다.
 * extracted는 보정 적용 전 탭이 넘기는, PDF에서 이미 뽑은 신청자격이다(없으면 null). 초안이 없는 동안 서버 변환 미리보기를 보이고,
 * 미리보기를 받지 못하면 뽑은 내용을 그대로 보인다. 초안은 저장된 공급행에 경로를 이어 만들므로 적용 전에는 만들기 버튼을 두지 않는다.
 * warnings는 워커가 신청자격을 읽으며 남긴 경고('숫자 확인 필요' 등)다. 숫자가 원문과 맞지 않아도 판정에 쓰므로, 요건 문구가 같은 조건·목록 곁에
 * 어떻게 확인했는지와 함께 보이고, 어디에도 붙지 않은 경고만 위에 모은다. warnings가 없으면(적용 결과 화면) 숫자 대조 결과는 말하지 않는다.
 */
export function EligibilityDraftPanel({ announcementId, pdfSha256, onEvidence, autoCreate = false, extracted, warnings }: { announcementId: string; pdfSha256: string | null; onEvidence?: (item: Evidence) => void; autoCreate?: boolean; extracted?: ExtractedEligibility | null; warnings?: string[] }) {
  const [draft, setDraft] = useState<RuleSet | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ kind: 'error' | 'notice'; text: string } | null>(null)
  const [notices, setNotices] = useState<string[]>([])
  const [preview, setPreview] = useState<{ content: Content | null; error: string | null } | null>(null)
  const previewing = loaded && !draft && !autoCreate && !!extracted && !!pdfSha256

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
    loadEligibilityDraft(announcementId, controller.signal)
      .then(value => { setDraft(value); setLoaded(true); if (!value && autoCreate && pdfSha256) make(pdfSha256, null) })
      .catch(error => { if (!controller.signal.aborted) { setMessage({ kind: 'error', text: errorText(error) }); setLoaded(true) } })
    return () => controller.abort()
    // 적용 결과 화면은 공고와 PDF가 바뀌지 않는다. 자동 만들기는 첫 조회 때 한 번만 한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [announcementId])

  async function run(action: () => Promise<RuleSet>, done: string) {
    setBusy(true); setMessage(null)
    try {
      setDraft(await action())
      setMessage({ kind: 'notice', text: done })
    } catch (error) {
      setMessage({ kind: 'error', text: errorText(error) })
    } finally {
      setBusy(false)
    }
  }

  function create() {
    if (!pdfSha256) return
    if (draft && !window.confirm('지금 초안을 버리고 최신 PDF 추출로 다시 만들까요?')) return
    make(pdfSha256, draft)
  }

  function make(sha256: string, current: RuleSet | null) {
    setNotices([])
    void run(async () => {
      const created = await createEligibilityDraft(announcementId, sha256, current)
      setNotices(created.notices)
      return created.ruleSet
    }, '최신 PDF 추출로 초안을 만들었어요. 조건을 확인해 주세요.')
  }

  /** 공개하면 초안이 공개본이 되어 사라지므로, 공개 뒤에는 초안을 다시 읽는다(없으면 비어 있음). */
  async function publish(current: RuleSet) {
    setBusy(true); setMessage(null)
    try {
      await publishEligibilityDraft(announcementId, current)
    } catch (error) {
      setMessage({ kind: 'error', text: errorText(error) }); setBusy(false)
      return
    }
    // 공개는 이미 성공했으므로 초안을 바로 비우고, 다시 읽기가 실패해도 성공 문구는 유지한다.
    setDraft(null); setNotices([])
    setMessage({ kind: 'notice', text: '공개했어요. 사용자 판정이 곧 다시 계산돼요.' })
    try {
      setDraft(await loadEligibilityDraft(announcementId))
    } catch {
      setMessage({ kind: 'notice', text: '공개했어요. 사용자 판정이 곧 다시 계산돼요. 초안 상태는 화면을 새로 고치면 다시 보여요.' })
    } finally {
      setBusy(false)
    }
  }

  function save(content: Content, done: string) {
    if (draft) void run(() => saveEligibilityDraft(announcementId, draft, content), done)
  }

  const content = draft?.content
  const shown = content ?? (draft ? null : preview?.content ?? null)
  // 조건·목록 문구에 붙은 경고는 그 곁에 보인다. 초안·미리보기가 없으면 뽑은 요건 문구에 붙인다.
  const targets = shown ? [...shown.commonChecks, ...shown.paths.flatMap(item => item.checks)].map(checkTarget)
    .concat(shown.guidances.flatMap(item => item.items).map(itemTarget))
    : extracted?.groups.flatMap(group => group.requirements.map(item => ({ label: item.text, kind: KIND_NAMES[item.kind] }))) ?? []
  const loose = unmatchedWarnings(targets, warnings ?? [])
  const numbers: NumberCheck = warnings === undefined ? 'unknown' : loose.some(isNumberWarning) ? 'unmatched' : 'checked'
  const reviewed = content?.metadata.reviewStatus === 'REVIEWED'
  return <section aria-labelledby="eligibility-draft-title">
    <h2 id="eligibility-draft-title">신청자격</h2>
    {draft && <p>PDF에서 뽑은 신청자격과 저장된 공급정보로 만든 초안이에요. 잘못 읽힌 조건은 직접 확인으로 바꾸면 그 신청 경로는 지원 가능으로 확정하지 않아요.</p>}
    <div className={styles.actions}>
      {(draft || autoCreate) && <Button onClick={create} disabled={busy || !loaded || !pdfSha256}>{draft ? '뽑은 신청자격으로 다시 만들기' : '초안 만들기'}</Button>}
      {content && <Button variant="secondary" disabled={busy || reviewed} onClick={() => save({ ...content, metadata: { ...content.metadata, reviewStatus: 'REVIEWED' } }, '검토 완료로 표시했어요. 공개할 수 있어요.')}>검토 완료로 표시</Button>}
      {draft && <Button variant="secondary" disabled={busy || !reviewed} onClick={() => void publish(draft)}>공개</Button>}
    </div>
    {!pdfSha256 && <p className={styles.hint}>PDF를 열면 초안을 만들 수 있어요.</p>}
    {message && <p role={message.kind === 'error' ? 'alert' : 'status'} className={message.kind === 'error' ? styles.warningNote : styles.hint}>{message.text}</p>}
    {notices.length > 0 && <div className={styles.warningNote}><p><strong>검토할 점</strong></p><ul>{notices.map(item => <li key={item}>{item}</li>)}</ul></div>}
    {loose.length > 0 && <div className={styles.warningNote}><p><strong>PDF에서 읽으며 확인할 점</strong></p><ul>{loose.map(item => <li key={item}>{item}</li>)}</ul></div>}
    {loaded && !draft && !autoCreate && (extracted && preview?.content
      ? <>
        <p>보정 내용을 적용하면 아래 조건과 저장된 공급정보로 초안이 만들어져요.</p>
        <Converted content={preview.content} warnings={warnings} numbers={numbers} onEvidence={onEvidence} />
      </>
      : extracted
      ? <>
        <p>보정 내용을 적용하면 아래 내용과 저장된 공급정보로 초안이 만들어져요.</p>
        {preview?.error && <p role="alert" className={styles.warningNote}>{`자동 판정 조건 미리보기를 불러오지 못해 뽑은 내용을 그대로 보여요. ${preview.error}`}</p>}
        {extracted.groups.map((group, index) => <section key={index} aria-label={group.title}>
          <h3>{group.title}</h3>
          {group.requirements.length === 0 && group.qualifications.length === 0 && <p className={styles.hint}>뽑은 요건이 없어요.</p>}
          {group.requirements.length > 0 && <ul>{group.requirements.map((item, row) => <li key={row}>
            <span>{item.text}</span>{' '}{item.evidence && onEvidence && <Button data-design-system className={styles.quietButton} onClick={() => onEvidence(item.evidence!)}>{`근거 p.${item.evidence.page} 열기`}</Button>}
            <Reasons reasons={requirementWarnings({ label: item.text, kind: KIND_NAMES[item.kind] }, warnings ?? [])} />
          </li>)}</ul>}
          {group.qualifications.length > 0 && <p>직접 확인 자격: {group.qualifications.join(', ')}</p>}
        </section>)}
        {extracted.exclusions.length > 0 && <><h3>신청 제한</h3><ul>{extracted.exclusions.map((item, index) => <li key={index}><strong>{item.title}</strong> {item.body}</li>)}</ul></>}
      </>
      : <p className={styles.hint}>{extracted === null ? '이 PDF 추출 결과에는 신청자격이 없어요. PDF를 다시 추출해 주세요.' : '아직 신청자격 초안이 없어요.'}</p>)}
    {loaded && !draft && autoCreate && !message && !busy && <p className={styles.hint}>아직 신청자격 초안이 없어요.</p>}
    {content && <>
      <p className={styles.hint}>{reviewed ? '검토 완료' : '검토 전'} · 기준일 {content.metadata.referenceDate} · 수정 {draft!.revision}회</p>
      <Scope title="모든 신청자에게 적용" scopeId="common" checks={content.commonChecks} content={content} warnings={warnings} numbers={numbers} busy={busy}
        onDirect={check => save(moveToDirectCheck(content, 'common', check.id), '직접 확인으로 바꿨어요.')} onEvidence={onEvidence} />
      {content.paths.map(path => <Scope key={path.id} title={path.label} scopeId={path.id} checks={path.checks} content={content} warnings={warnings} numbers={numbers} busy={busy}
        onDirect={check => save(moveToDirectCheck(content, path.id, check.id), '직접 확인으로 바꿨어요.')} onEvidence={onEvidence} />)}
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

/** onDirect가 없으면 읽기 전용(미리보기)이다. */
function Scope({ title, scopeId, checks, content, warnings, numbers, busy = false, onDirect, onEvidence }: {
  title: string; scopeId: string; checks: Check[]; content: Content; warnings?: string[]; numbers: NumberCheck; busy?: boolean; onDirect?: (check: Check) => void; onEvidence?: (item: Evidence) => void
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
    {onDirect && units.length > 0 && <p className={styles.hint}>신청 단위: {units.join(', ')}</p>}
    {checks.length === 0 && qualifications.length === 0 && direct.length === 0 && listedItems.length === 0 && <p className={styles.hint}>자동 판정 조건이 없어요.</p>}
    {checks.length > 0 && <ul>{checks.map(check => <li key={check.id}>
      <span>{ruleText(check.rule)}</span>{check.appliesWhen && <span className={styles.hint}> (가산 대상은 직접 확인)</span>} <span className={styles.hint}>원문: {check.label}</span> {evidence(check.evidenceIds)}
      <CheckedHow check={check} content={content} warnings={warnings} numbers={numbers} />
      {onDirect && <>{' '}<Button variant="secondary" disabled={busy} onClick={() => onDirect(check)}>직접 확인으로 바꾸기</Button></>}
    </li>)}</ul>}
    {qualifications.length > 0 && <p>직접 확인 자격: {qualifications.map(item => item.exactName).join(', ')} {evidence(qualifications.flatMap(item => item.evidenceIds))}</p>}
    {direct.length > 0 && <><p>공고문에서 직접 확인:</p><ul>{direct.map((item, index) => <li key={index}>{item}<Reasons reasons={requirementWarnings(itemTarget(item), warnings ?? [])} /></li>)}</ul></>}
    {listedItems.length > 0 && <><p>공고문에서 확인할 자격 (판정에 넣지 않음): {evidence(listed.flatMap(item => item.evidenceIds))}</p><ul>{listedItems.map((item, index) => <li key={index}>{item}<Reasons reasons={requirementWarnings(itemTarget(item), warnings ?? [])} /></li>)}</ul></>}
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
  return error instanceof Error ? error.message : '신청자격 초안을 처리하지 못했어요. 다시 시도해 주세요.'
}
