import { useEffect, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { Evidence } from './extraction'
import { createEligibilityDraft, loadEligibilityDraft, moveToDirectCheck, publishEligibilityDraft, ruleText, saveEligibilityDraft, type Check, type Content, type RuleSet } from './eligibilityDraftApi'
import styles from './PdfCorrection.module.css'

const RECRUITMENT: Record<string, string> = { RECRUITING: '모집', UNKNOWN: '모집수 미확인', UPCOMING: '모집 예정', NOT_RECRUITING: '모집 안 함', CANCELLED: '취소' }

/**
 * PDF 보정의 신청자격 탭. 최신 추출의 AI 신청자격과 적용한 공급행으로 만든 초안을 보이고, 잘못 읽힌 조건은 지우지 않고 직접 확인으로 바꾼 뒤
 * 검토 완료로 표시해 공개한다. 조건 숫자 고치기는 이 화면에서 하지 않는다.
 */
export function EligibilityDraftPanel({ announcementId, pdfSha256, onEvidence }: { announcementId: string; pdfSha256: string | null; onEvidence: (item: Evidence) => void }) {
  const [draft, setDraft] = useState<RuleSet | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ kind: 'error' | 'notice'; text: string } | null>(null)
  const [notices, setNotices] = useState<string[]>([])

  useEffect(() => {
    const controller = new AbortController()
    loadEligibilityDraft(announcementId, controller.signal)
      .then(value => { setDraft(value); setLoaded(true) })
      .catch(error => { if (!controller.signal.aborted) { setMessage({ kind: 'error', text: errorText(error) }); setLoaded(true) } })
    return () => controller.abort()
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
    void run(async () => {
      const created = await createEligibilityDraft(announcementId, pdfSha256, draft)
      setNotices(created.notices)
      return created.ruleSet
    }, '최신 PDF 추출로 초안을 만들었어요. 조건을 확인해 주세요.')
  }

  /** 공개하면 초안이 공개본이 되어 사라지므로, 공개 뒤에는 초안을 다시 읽는다(없으면 비어 있음). */
  async function publish(current: RuleSet) {
    setBusy(true); setMessage(null)
    try {
      await publishEligibilityDraft(announcementId, current)
      setDraft(await loadEligibilityDraft(announcementId)); setNotices([])
      setMessage({ kind: 'notice', text: '공개했어요. 사용자 판정이 곧 다시 계산돼요.' })
    } catch (error) {
      setMessage({ kind: 'error', text: errorText(error) })
    } finally {
      setBusy(false)
    }
  }

  function save(content: Content, done: string) {
    if (draft) void run(() => saveEligibilityDraft(announcementId, draft, content), done)
  }

  const content = draft?.content
  const reviewed = content?.metadata.reviewStatus === 'REVIEWED'
  return <section aria-labelledby="eligibility-draft-title">
    <h2 id="eligibility-draft-title">신청자격</h2>
    <p>최신 PDF 추출의 신청자격과 지금 저장된 공급정보로 초안을 만들어요. 공급정보를 먼저 적용한 뒤 만드세요. 잘못 읽힌 조건은 직접 확인으로 바꾸면 그 신청 경로는 지원 가능으로 확정하지 않아요.</p>
    <div className={styles.actions}>
      <Button onClick={create} disabled={busy || !loaded || !pdfSha256}>{draft ? 'AI 추출로 다시 만들기' : 'AI 추출로 초안 만들기'}</Button>
      {content && <Button variant="secondary" disabled={busy || reviewed} onClick={() => save({ ...content, metadata: { ...content.metadata, reviewStatus: 'REVIEWED' } }, '검토 완료로 표시했어요. 공개할 수 있어요.')}>검토 완료로 표시</Button>}
      {draft && <Button variant="secondary" disabled={busy || !reviewed} onClick={() => void publish(draft)}>공개</Button>}
    </div>
    {!pdfSha256 && <p className={styles.hint}>PDF를 열면 초안을 만들 수 있어요.</p>}
    {message && <p role={message.kind === 'error' ? 'alert' : 'status'} className={message.kind === 'error' ? styles.warningNote : styles.hint}>{message.text}</p>}
    {notices.length > 0 && <div className={styles.warningNote}><p><strong>검토할 점</strong></p><ul>{notices.map(item => <li key={item}>{item}</li>)}</ul></div>}
    {loaded && !draft && !message && <p className={styles.hint}>아직 신청자격 초안이 없어요.</p>}
    {content && <>
      <p className={styles.hint}>{reviewed ? '검토 완료' : '검토 전'} · 기준일 {content.metadata.referenceDate} · 수정 {draft!.revision}회</p>
      <Scope title="모든 신청자에게 적용" scopeId="common" checks={content.commonChecks} content={content} busy={busy}
        onDirect={check => save(moveToDirectCheck(content, 'common', check.id), '직접 확인으로 바꿨어요.')} onEvidence={onEvidence} />
      {content.paths.map(path => <Scope key={path.id} title={path.label} scopeId={path.id} checks={path.checks} content={content} busy={busy}
        onDirect={check => save(moveToDirectCheck(content, path.id, check.id), '직접 확인으로 바꿨어요.')} onEvidence={onEvidence} />)}
      {content.guidances.some(item => item.kind === 'APPLICATION_EXCLUSION') && <><h3>신청 제한</h3><ul>{content.guidances.filter(item => item.kind === 'APPLICATION_EXCLUSION').map(item => <li key={item.id}><strong>{item.title}</strong> {item.body}</li>)}</ul></>}
    </>}
  </section>
}

function Scope({ title, scopeId, checks, content, busy, onDirect, onEvidence }: {
  title: string; scopeId: string; checks: Check[]; content: Content; busy: boolean; onDirect: (check: Check) => void; onEvidence: (item: Evidence) => void
}) {
  const common = scopeId === 'common'
  const path = content.paths.find(item => item.id === scopeId)
  const units = content.options.filter(option => option.pathId === scopeId)
    .map(option => `${content.supplyUnits.find(unit => unit.id === option.supplyUnitId)?.label ?? option.supplyUnitId} (${RECRUITMENT[option.recruitmentStatus] ?? option.recruitmentStatus})`)
  const qualifications = content.qualifications.filter(item => path?.qualificationIds.includes(item.id))
  const direct = content.guidances.filter(item => item.kind === 'REQUIRED_QUALIFICATION'
    && item.targets.some(target => common ? target.scope === 'ANNOUNCEMENT' : target.scope === 'PATH' && target.pathId === scopeId))
    .flatMap(item => item.items)
  function evidence(ids: string[]) {
    const item = content.evidences.find(value => ids.includes(value.id))
    return item ? <Button data-design-system className={styles.quietButton} onClick={() => onEvidence({ path: item.id, page: item.firstPage, quote: item.description ?? '' })}>{`근거 p.${item.firstPage} 열기`}</Button> : null
  }
  return <section aria-label={title}>
    <h3>{title}</h3>
    {units.length > 0 && <p className={styles.hint}>신청 단위: {units.join(', ')}</p>}
    {checks.length === 0 && qualifications.length === 0 && direct.length === 0 && <p className={styles.hint}>자동 판정 조건이 없어요.</p>}
    {checks.length > 0 && <ul>{checks.map(check => <li key={check.id}>
      <span>{ruleText(check.rule)}</span>{check.appliesWhen && <span className={styles.hint}> (가산 대상은 직접 확인)</span>} <span className={styles.hint}>원문: {check.label}</span> {evidence(check.evidenceIds)}
      {' '}<Button variant="secondary" disabled={busy} onClick={() => onDirect(check)}>직접 확인으로 바꾸기</Button>
    </li>)}</ul>}
    {qualifications.length > 0 && <p>직접 확인 자격: {qualifications.map(item => item.exactName).join(', ')} {evidence(qualifications.flatMap(item => item.evidenceIds))}</p>}
    {direct.length > 0 && <><p>공고문에서 직접 확인:</p><ul>{direct.map((item, index) => <li key={index}>{item}</li>)}</ul></>}
  </section>
}

function errorText(error: unknown) {
  return error instanceof Error ? error.message : '신청자격 초안을 처리하지 못했어요. 다시 시도해 주세요.'
}
