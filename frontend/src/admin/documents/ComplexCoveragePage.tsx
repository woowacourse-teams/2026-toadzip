import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { compareOfficialComplex, parseOfficialList, type CoverageResult, type OfficialComplexList } from './complexCoverage'
import { downloadText } from './documentReview'
import { SourceUrl } from '../shared/SourceUrl'
import styles from './DocumentReview.module.css'

const example = JSON.stringify({ sourceUrl: 'https://공식기관의-단지목록-주소', asOf: '2026-10-12', agencyCode: 'LH', complexes: [{ name: '공식 단지명', roadAddress: '공식 도로명주소' }] }, null, 2)
const statusLabels = { MATCHED: '등록 확인', MISSING: '미등록 후보', REVIEW: '동일성 검토', DELETED: '휴지통 후보', ERROR: '조회 실패' }
type Entry = { key: number; name: string; roadAddress: string }

export function ComplexCoveragePage() {
  const [mode, setMode] = useState<'manual' | 'json'>('manual')
  const [input, setInput] = useState(''); const [list, setList] = useState<OfficialComplexList | null>(null)
  const [sourceUrl, setSourceUrl] = useState(''); const [asOf, setAsOf] = useState(''); const [agencyCode, setAgencyCode] = useState('LH')
  const [entries, setEntries] = useState<Entry[]>([{ key: 0, name: '', roadAddress: '' }]); const nextKey = useRef(1)
  const [results, setResults] = useState<CoverageResult[]>([]); const [filter, setFilter] = useState<keyof typeof statusLabels | 'ALL'>('ALL')
  const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  const request = useRef<AbortController | null>(null)
  useEffect(() => () => request.current?.abort(), [])
  function reset() { setList(null); setResults([]); setError(''); setNotice(''); setFilter('ALL') }
  function updateEntry(key: number, field: 'name' | 'roadAddress', value: string) {
    setEntries(current => current.map(entry => entry.key === key ? { ...entry, [field]: value } : entry)); reset()
  }
  async function compare() {
    if (busy) return
    let source: OfficialComplexList
    try { source = parseOfficialList(mode === 'json' ? input : JSON.stringify({ sourceUrl, asOf, agencyCode, complexes: entries.map(({ name, roadAddress }) => ({ name, roadAddress })) })) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '목록을 확인해 주세요.'); return }
    const controller = new AbortController(); request.current = controller
    setList(source); setResults([]); setFilter('ALL'); setError(''); setNotice(''); setBusy(true)
    try {
      for (const entry of source.complexes) {
        const result = await compareOfficialComplex(source, entry, controller.signal)
        if (controller.signal.aborted) return
        setResults(current => [...current, result])
      }
      setNotice('대조를 마쳤습니다. 확인이 필요한 후보를 선택해 상세 내용을 검토하세요.')
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '대조하지 못했습니다.') }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  async function readFile(file: File) {
    if (file.size > 1000000) { setError('공식 목록 JSON은 1MB 이하여야 합니다.'); return }
    const controller = new AbortController(); request.current = controller; setBusy(true)
    try { const text = await file.text(); if (!controller.signal.aborted) { setInput(text); reset() } }
    catch { if (!controller.signal.aborted) setError('목록 파일을 읽지 못했습니다.') }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  const visible = results.filter(result => filter === 'ALL' || result.status === filter)
  return <section className={`${styles.panel} ${styles.coverage} ph-no-capture`}>
    <header className={styles.pageHeading}><span className={styles.eyebrow}>데이터 품질 관리</span><h1>단지 누락 대조</h1><p>공식 목록에 있는 단지가 서비스에도 등록되어 있는지 확인하세요.</p></header>
    <div className={styles.workflow} aria-label="대조 순서"><span><b>1</b> 목록 입력</span><span><b>2</b> 등록 여부 대조</span><span><b>3</b> 확인할 후보 검토</span></div>
    <section className={styles.card} aria-label="대조할 목록 입력">
      <header className={styles.cardHeading}><div><h2>대조할 단지 목록</h2><p>공식 홈페이지·API·PDF에서 확인한 이름과 도로명주소를 입력하세요.</p></div><span className={styles.badge}>한 번에 최대 50개</span></header>
      <div className={styles.modeSwitch} aria-label="목록 입력 방식"><button type="button" disabled={busy} aria-pressed={mode === 'manual'} onClick={() => { setMode('manual'); reset() }}>직접 입력</button><button type="button" disabled={busy} aria-pressed={mode === 'json'} onClick={() => { setMode('json'); reset() }}>JSON 가져오기</button></div>
      <fieldset className={styles.inputs} disabled={busy}><legend className={styles.srOnly}>공식 단지 목록</legend>
        {mode === 'manual' ? <>
          <div className={styles.sourceGrid}><label>목록 출처 URL<input type="url" value={sourceUrl} maxLength={2048} placeholder="공식 단지 목록 페이지 주소" onChange={event => { setSourceUrl(event.target.value); reset() }} /></label><label>자료 기준일<input type="date" value={asOf} onChange={event => { setAsOf(event.target.value); reset() }} /></label><label>공급 기관<select value={agencyCode} onChange={event => { setAgencyCode(event.target.value); reset() }}>{['LH', 'SH', 'GH', 'ETC'].map(code => <option key={code}>{code}</option>)}</select></label></div>
          <div className={styles.entryList}>{entries.map((entry, index) => <div className={styles.entryRow} key={entry.key}><span className={styles.rowNumber}>{index + 1}</span><label>단지명<input aria-label={`${index + 1}번째 단지명`} value={entry.name} maxLength={200} placeholder="공식 목록의 단지명" onChange={event => updateEntry(entry.key, 'name', event.target.value)} /></label><label>도로명주소<input aria-label={`${index + 1}번째 도로명주소`} value={entry.roadAddress} maxLength={200} placeholder="시·군·구부터 건물번호까지" onChange={event => updateEntry(entry.key, 'roadAddress', event.target.value)} /></label><button type="button" aria-label={`${index + 1}번째 단지 삭제`} disabled={entries.length === 1} onClick={() => { setEntries(current => current.filter(item => item.key !== entry.key)); reset() }}>삭제</button></div>)}</div>
          <button type="button" disabled={entries.length >= 50} onClick={() => { setEntries(current => [...current, { key: nextKey.current++, name: '', roadAddress: '' }]); reset() }}>단지 한 줄 추가</button>
        </> : <>
          <label className={styles.upload}>공식 단지 목록 JSON 파일<input type="file" accept=".json,application/json" onChange={event => { const file = event.currentTarget.files?.[0]; event.currentTarget.value = ''; if (file) void readFile(file) }} /><span className={styles.hint}>에이전트나 수집 도구에서 준비한 목록 파일을 선택하세요.</span></label>
          <label>공식 단지 목록 JSON<textarea rows={6} value={input} spellCheck={false} placeholder={example} onChange={event => { setInput(event.target.value); reset() }} /></label>
          <details><summary>입력 형식 보기</summary><pre className={styles.text}>{example}</pre><p className={styles.hint}>sourceIdentifier는 서비스와 같은 원천 식별자일 때만 입력하세요.</p></details>
        </>}
        <div className={styles.footer}><p className={styles.hint}>등록 여부만 확인합니다. 단지를 자동으로 등록하거나 연결하지 않습니다.</p><Button data-design-system disabled={mode === 'json' && !input.trim()} onClick={() => void compare()}>공식 목록과 대조</Button></div>
      </fieldset>
    </section>
    {busy ? <div className={styles.callout} role="status"><p>대조 중… {results.length}/{list?.complexes.length ?? 0}</p><button type="button" onClick={() => { request.current?.abort(); setBusy(false); setNotice('대조를 중지했습니다. 완료된 항목만 표시합니다.') }}>대조 중지</button></div> : null}
    {error ? <p role="alert" className={styles.error}>{error}</p> : null}
    {notice ? <p role="status" className={styles.callout}>{notice}</p> : null}
    {list ? <section className={styles.card} aria-label="단지 대조 결과"><header className={styles.cardHeading}><div><h2>대조 결과</h2><p>공식 기준일: {list.asOf} · 기관: {list.agencyCode} · {results.length}/{list.complexes.length}개 확인</p></div>{!busy && results.length ? <button type="button" onClick={() => downloadText(JSON.stringify({ source: list, checkedAt: new Date().toISOString(), results }, null, 2), 'complex-coverage-result.json')}>대조 결과 다운로드</button> : null}</header>
      <SourceUrl url={list.sourceUrl} />
      <div className={styles.resultFilters} aria-label="대조 결과 필터"><button type="button" aria-pressed={filter === 'ALL'} onClick={() => setFilter('ALL')}>전체 <strong>{results.length}</strong></button>{Object.entries(statusLabels).map(([key, label]) => <button type="button" key={key} data-status={key} aria-pressed={filter === key} onClick={() => setFilter(key as keyof typeof statusLabels)}>{label} <strong>{results.filter(result => result.status === key).length}</strong></button>)}</div>
      {visible.map((result, index) => <article key={index} className={styles.proposal} data-status={result.status}><header className={styles.cardHeading}><h3>{result.entry.name} · {statusLabels[result.status]}</h3><span className={styles.badge}>{statusLabels[result.status]}</span></header><p>{result.entry.roadAddress}</p><p className={styles.hint}>{result.reason}</p>
        {result.candidates.map(candidate => <div className={styles.candidate} key={candidate.id}><Link to={`/admin/complexes/${candidate.id}`}>{candidate.name} · {candidate.subtitle} · 상세 확인</Link>{!candidate.deleted ? <Link to={`/explore?complexId=${candidate.id}`} target="_blank">공개 화면 확인 ↗</Link> : null}</div>)}
        <div className={styles.actions}><Link to={`/admin/complexes?${new URLSearchParams({ keyword: result.entry.name })}`}>관리자에서 다시 검색</Link>{result.status === 'MISSING' ? <Link to="/admin/complexes/new">미등록 확인 후 단지 추가</Link> : null}</div>
      </article>)}
      {!busy && !visible.length ? <p className={styles.empty}>이 분류에 해당하는 단지가 없습니다.</p> : null}
    </section> : <div className={styles.empty}><strong>목록을 입력하면 대조 결과가 여기에 표시됩니다.</strong><p>미등록 후보와 이름·주소가 비슷한 후보를 구분해서 확인할 수 있습니다.</p></div>}
  </section>
}
