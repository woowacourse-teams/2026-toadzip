import { useEffect, useState } from 'react'
import { useParams } from 'react-router'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import { getLhSupplyLinks, releaseLhSupplyLink, rerunAnnouncement, saveLhSupplyLinks,
  type LhLinkMyHomeRow, type LhLinkSupplyRow, type LhSupplyLinkView } from './supplyMatchingApi'
import styles from './LhSupplyLinkPage.module.css'

const RELEASE = 'release'
const DEFAULT_REASON = '공고문 모집대상 주택 표와 LH 공급 원본을 대조'
type Outcome = { ok: boolean; text: string }
type Result = { text: string; tone: 'muted' | 'warn' | 'change' | 'add' }

const failureLabels: Record<string, string> = {
  HOUSING_TYPE_NOT_FOUND: '단지에 같은 주택형 없음', AMBIGUOUS_HOUSING_TYPE: '같은 주택형 행 여러 개',
  AMBIGUOUS_COMPLEX: '단지 후보 여러 개', LH_SUPPLY_LINK_REVIEW_REQUIRED: '다시 확인 필요',
}

export function LhSupplyLinkPage() {
  const { identifier = '' } = useParams()
  return <LhSupplyLinkWorkspace key={identifier} identifier={identifier} />
}

/**
 * LH 공급 원본 행마다 공고의 단지(마이홈 원천행)를 골라 잇고 이 공고만 다시 정제한다.
 * 정제는 한 원천행에 이은 LH 행 수만큼 공급행을 펼치므로, 같은 단지에 두 번째로 이은 LH 행은 공급행을 추가한다.
 */
function LhSupplyLinkWorkspace({ identifier }: { identifier: string }) {
  const [view, setView] = useState<LhSupplyLinkView | null>(null)
  const [loadError, setLoadError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [choices, setChoices] = useState<Record<string, string>>({})
  const [suggested, setSuggested] = useState<Set<string>>(new Set())
  const [touched, setTouched] = useState(false)
  const [sourceUrl, setSourceUrl] = useState('')
  const [reason, setReason] = useState(DEFAULT_REASON)
  const [saving, setSaving] = useState(false)
  const [outcome, setOutcome] = useState<Outcome | null>(null)
  useUnsavedChanges(touched && !saving)

  useEffect(() => {
    const controller = new AbortController()
    setLoadError('')
    void getLhSupplyLinks(identifier, controller.signal).then(result => {
      if (controller.signal.aborted) return
      const initial: Record<string, string> = {}, proposals = new Set<string>()
      for (const row of result.lhRows) {
        const value = current(row) || suggestion(row, result)
        initial[row.rowIdentifier] = value
        if (value && !current(row)) proposals.add(row.rowIdentifier)
      }
      setView(result); setChoices(initial); setSuggested(proposals); setTouched(false)
      setSourceUrl(previous => previous || result.announcement?.sourceUrl || '')
    }).catch(cause => { if (!controller.signal.aborted) setLoadError(message(cause)) })
    return () => controller.abort()
  }, [identifier, attempt])

  if (!view) return <section className={styles.page}><h1>LH 공급 원본 연결</h1>
    {loadError ? <div role="alert"><p>{loadError}</p><button type="button" onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button></div>
      : <p role="status">LH 공급 원본을 불러오는 중…</p>}</section>

  const changes = view.lhRows.filter(row => choices[row.rowIdentifier] && choices[row.rowIdentifier] !== current(row))
  // 제안으로 고른 값은 아직 저장 전이므로 남은 수는 저장된 연결 기준으로 센다.
  const pending = view.lhRows.filter(row => !current(row) || row.savedLink?.reviewRequired
    || view.failures?.some(item => item.lhRowIdentifier === row.rowIdentifier)).length
  const evidenceProblem = !/^https?:\/\/\S+$/i.test(sourceUrl.trim()) || !reason.trim()

  function choose(row: LhLinkSupplyRow, value: string) {
    setChoices(previous => ({ ...previous, [row.rowIdentifier]: value }))
    setSuggested(previous => { const next = new Set(previous); next.delete(row.rowIdentifier); return next })
    setTouched(true); setOutcome(null)
  }

  async function save() {
    if (!view || saving || changes.length === 0 || evidenceProblem) return
    setSaving(true); setOutcome(null)
    let latest = view
    // LH 행 구성이 바뀐 원천행의 저장 매칭은 서버가 함께 해제한다. 정제 결과와 함께 알린다.
    const released: string[] = []
    try {
      // 저장 API는 원천행 하나에 LH 행 여러 개를 받으므로 고른 단지별로 묶어 차례로 저장한다.
      const groups = new Map<string, string[]>()
      for (const row of changes) {
        const target = choices[row.rowIdentifier]
        if (target !== RELEASE) groups.set(target, [...(groups.get(target) ?? []), row.rowIdentifier])
      }
      for (const [target, rows] of groups) {
        latest = await saveLhSupplyLinks(identifier, latest, target, rows, sourceUrl.trim(), reason.trim())
        released.push(...latest.releasedSupplyMatches ?? [])
      }
      for (const row of changes.filter(item => choices[item.rowIdentifier] === RELEASE)) {
        latest = await releaseLhSupplyLink(identifier, latest, row.rowIdentifier, reason.trim())
        released.push(...latest.releasedSupplyMatches ?? [])
      }
    } catch (cause) {
      setOutcome({ ok: false, text: `${message(cause)} 저장된 연결을 다시 불러왔습니다.` })
      setSaving(false); setAttempt(value => value + 1)
      return
    }
    try {
      await rerunAnnouncement(identifier)
      setOutcome({ ok: true, text: `연결을 저장하고 다시 정제했습니다. 이 공고의 오류가 해결됐습니다.${releasedNotice(released)}` })
    } catch (cause) {
      setOutcome({ ok: false, text: `연결은 저장했습니다.${releasedNotice(released)} ${message(cause)}` })
    } finally { setSaving(false); setAttempt(value => value + 1) }
  }

  const announcement = view.announcement
  return <section className={styles.page} aria-labelledby="lh-link-title">
    <header className={styles.heading}>
      <div>
        <h1 id="lh-link-title">LH 공급 원본 연결</h1>
        <p>{announcement?.name ?? `공고 ${identifier}`}{announcement?.panId ? ` · LH ${announcement.panId}` : ''}
          {announcement?.sourceUrl && <> · <a href={announcement.sourceUrl} target="_blank" rel="noreferrer">공고 원문</a></>}</p>
      </div>
      <strong className={styles.count} data-done={pending === 0}>{pending === 0 ? `LH ${view.lhRows.length}행 모두 연결` : `연결 필요 ${pending} / ${view.lhRows.length}`}</strong>
    </header>
    <div className={styles.tableScroll} role="region" aria-label="LH 공급 원본 행" tabIndex={0}>
      <table className={styles.table}>
        <thead><tr><th scope="col">LH 단지</th><th scope="col">주택형</th><th scope="col">전용㎡</th><th scope="col">연결할 단지</th><th scope="col">결과</th></tr></thead>
        <tbody>{view.lhRows.map(row => {
          const outcomeOfRow = result(row, view, choices)
          return <tr key={row.rowIdentifier} data-tone={outcomeOfRow.tone}>
            <th scope="row">{row.complexLabel ?? '단지명 없음'}</th>
            <td>{row.typeName ?? '—'}</td>
            <td className={styles.number}>{row.exclusiveArea ?? '—'}</td>
            <td><div className={styles.choice}>
              <select aria-label={`${row.complexLabel ?? 'LH 행'} ${row.typeName ?? ''} 연결할 단지`} value={choices[row.rowIdentifier] ?? ''}
                disabled={saving} onChange={event => choose(row, event.target.value)}>
                <option value="">단지 선택</option>
                {view.myhomeRows.map(option => <option key={option.rowIdentifier} value={option.rowIdentifier} disabled={option.splitCorrected}>
                  {myhomeLabel(option, view.myhomeRows)}{option.splitCorrected ? ' (복수 공급행 보정)' : ''}</option>)}
                {row.savedLink && <option value={RELEASE}>저장한 연결 해제</option>}
              </select>
              {suggested.has(row.rowIdentifier) && <span className={styles.suggested}>제안</span>}
            </div></td>
            <td className={styles.result}>{outcomeOfRow.text}</td>
          </tr>
        })}</tbody>
      </table>
    </div>
    {outcome && <p role={outcome.ok ? 'status' : 'alert'} className={outcome.ok ? styles.done : styles.error}>{outcome.text}</p>}
    <footer className={styles.footer}>
      <fieldset className={styles.evidence} disabled={saving}>
        <legend className={styles.srOnly}>연결 근거</legend>
        <label>공식 근거 URL<input type="url" value={sourceUrl} maxLength={2048} onChange={event => setSourceUrl(event.target.value)} /></label>
        <label>확인 근거<input value={reason} maxLength={2000} onChange={event => setReason(event.target.value)} /></label>
      </fieldset>
      <button type="button" className="admin-primary" disabled={saving || changes.length === 0 || evidenceProblem} onClick={() => void save()}>
        {saving ? '저장·정제 중…' : `연결 ${changes.length}개 저장하고 다시 정제`}</button>
    </footer>
  </section>
}

function current(row: LhLinkSupplyRow): string {
  return row.savedLink?.myhomeRowIdentifier ?? row.linkedRowIdentifier ?? ''
}

// 정제는 원천행에 이은 LH 행을 공급 표 순서로 펼쳐 첫 행만 기존 공급행에 쓰고 나머지는 새 공급행으로 만든다.
function result(row: LhLinkSupplyRow, view: LhSupplyLinkView, choices: Record<string, string>): Result {
  const choice = choices[row.rowIdentifier] ?? ''
  if (choice === RELEASE) return { text: '저장한 연결 해제', tone: 'change' }
  if (!choice) return { text: '단지 선택 필요', tone: 'warn' }
  if (choice === current(row)) {
    if (row.savedLink?.reviewRequired) return { text: '원천이 바뀌어 다시 확인 필요', tone: 'warn' }
    const failure = view.failures?.find(item => item.lhRowIdentifier === row.rowIdentifier)
    if (failure) return { text: failureLabels[failure.reason] ?? failure.detail, tone: 'warn' }
    return { text: row.savedLink ? '저장한 연결' : '자동 연결', tone: 'muted' }
  }
  const sameTarget = view.lhRows.filter(other => (choices[other.rowIdentifier] ?? '') === choice)
  return sameTarget[0] === row ? { text: '기존 공급행에 연결', tone: 'change' } : { text: '공급행 추가', tone: 'add' }
}

// LH 단지명에서 공고 전체에 공통인 지역 접두어(예: 군산)를 빼고, 글자가 같은 순서로 들어 있는 단지가 하나뿐이면 제안한다.
function suggestion(row: LhLinkSupplyRow, view: LhSupplyLinkView): string {
  const labels = view.lhRows.map(item => normalized(item.complexLabel ?? '')).filter(Boolean)
  const prefix = labels.length > 1 ? commonPrefix(labels) : ''
  const token = normalized(row.complexLabel ?? '').slice(prefix.length)
  if (!token) return ''
  const matches = view.myhomeRows.filter(option => !option.splitCorrected && inOrder(token, normalized(option.complexName ?? '')))
  return matches.length === 1 ? matches[0].rowIdentifier : ''
}
function normalized(value: string) { return value.replace(/\s|아파트|단지$/g, '') }
function commonPrefix(values: string[]) {
  let prefix = values[0]
  for (const value of values) while (!value.startsWith(prefix)) prefix = prefix.slice(0, -1)
  return prefix
}
function inOrder(token: string, name: string) {
  let at = 0
  for (const char of name) if (char === token[at]) at += 1
  return at === token.length
}

function myhomeLabel(row: LhLinkMyHomeRow, rows: LhLinkMyHomeRow[]): string {
  const name = row.complexName ?? '단지명 없음'
  // 같은 단지가 원천에 여러 행이면 원천 유형으로 구분한다.
  return rows.filter(item => item.complexName === row.complexName).length > 1 ? `${name} · ${row.housingTypeName ?? '유형 없음'}` : name
}
function message(cause: unknown): string { return cause instanceof Error ? cause.message : 'LH 공급 연결을 처리하지 못했습니다.' }

function releasedNotice(rows: string[]) {
  return rows.length ? ` LH 행 구성이 바뀐 원천행의 저장 매칭 ${rows.length}개를 해제했습니다. PDF 보정에서 펼쳐진 공급행의 주택형을 확인하세요.` : ''
}
