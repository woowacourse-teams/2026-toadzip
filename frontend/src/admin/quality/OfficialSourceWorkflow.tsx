import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { getSourceData, type SourceCategory, type SourcePage } from '../ingest/sourceApi'
import { getDataPipelineStatus, startDataPipeline, type DataPipelineExecution } from '../ingest/api'
import type { OfficialComplexList } from '../documents/complexCoverage'
import { loadOfficialCoverage, prepareOfficialCoverage } from './officialCoverage'
import styles from './QualityWorkspace.module.css'

const agencies: Record<string, string> = { 한국토지주택공사: 'LH', 서울주택도시공사: 'SH', SH공사: 'SH', 경기주택도시공사: 'GH' }
export function OfficialSourceWorkflow({ onImport, disabled }: { onImport: (list: OfficialComplexList) => void; disabled: boolean }) {
  const [provider, setProvider] = useState('LH')
  const [category, setCategory] = useState<SourceCategory>('MYHOME_COMPLEX')
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState<SourcePage | null>(null)
  const [execution, setExecution] = useState<DataPipelineExecution | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const request = useRef<AbortController | null>(null)
  useEffect(() => () => request.current?.abort(), [])
  async function fetchPage(number = 0) {
    request.current?.abort(); const controller = new AbortController(); request.current = controller
    setBusy(true); setError(''); setPage(null)
    try { const result = await getSourceData(category, keyword, number, controller.signal); if (!controller.signal.aborted) setPage(result) }
    catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '공식 수집 자료를 읽지 못했습니다.') }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  async function collection(start: boolean) {
    request.current?.abort(); const controller = new AbortController(); request.current = controller
    setBusy(true); setError(''); setNotice('')
    try {
      const next = start ? await startDataPipeline('COMPLEX_COLLECTION') : await getDataPipelineStatus('COMPLEX_COLLECTION')
      if (!controller.signal.aborted) { setExecution(next); setNotice(start ? '공식 API 수집을 요청했습니다. 진행 상태를 확인하고 완료 후 저장 자료를 불러오세요.' : '수집 상태를 갱신했습니다.') }
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '수집 요청을 처리하지 못했습니다.') }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  const candidates = (page?.items ?? []).filter(row => category !== 'MYHOME_COMPLEX' || (agencies[String(row.raw.instt_nm)] ?? String(row.raw.instt_nm)) === provider)
  function importPage() {
    try {
      const result = prepareOfficialCoverage(category, provider, page?.items ?? [])
      onImport(result.list); setNotice(`${result.list.complexes.length}개 단지를 가져왔습니다. 중복 ${result.duplicates}행 · 주소·시각 등 미확인 ${result.skipped}행 제외. 기준일은 마지막 수집일입니다.`)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '목록을 가져오지 못했습니다.') }
  }
  async function importAll() {
    request.current?.abort(); const controller = new AbortController(); request.current = controller
    setBusy(true); setError(''); setNotice('전체 자료 조회 중…')
    try {
      const result = await loadOfficialCoverage(category, provider, keyword, controller.signal, setNotice)
      if (controller.signal.aborted) return
      onImport(result.list)
      setNotice(`저장 자료 ${result.rows}행 전체 조회 완료 · 대조할 단지 ${result.list.complexes.length}개 · 중복 ${result.duplicates}행 · 주소·시각 등 미확인 ${result.skipped}행 제외. 공식 전체 목록의 완전성을 보증하지 않습니다.`)
    } catch (cause) { if (!controller.signal.aborted) { setNotice(''); setError(cause instanceof Error ? cause.message : '전체 자료를 가져오지 못했습니다.') } }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  return <details className={styles.workspace}>
    <summary><strong>공식 API 자료로 목록 준비하기</strong></summary>
    <p>마이홈 단지와 LH 공고 상세에서 수집한 이름·도로명주소를 가져옵니다. 이름·주소가 없는 자료는 미등록으로 판정하지 않습니다.</p>
    <fieldset disabled={disabled || busy} className={styles.filters}><legend>1. 필요하면 공식 자료 갱신</legend>
      <button disabled={execution?.status === 'RUNNING'} onClick={() => void collection(true)}>공식 단지 API 수집</button><button onClick={() => void collection(false)}>수집 상태 확인</button><Link to="/admin/ingest">API 키 설정·수집 상세</Link>
    </fieldset>
    {execution ? <div role="status"><p>수집 상태: {({ IDLE: '대기', RUNNING: '진행 중', COMPLETED: '완료', FAILED: '실패', STOPPED: '중지', COMPLETED_WARNINGS: '일부 경고', COMPLETED_WITH_SKIPS: '일부 건너뜀' })[execution.status]}</p>{execution.currentStepName ? <p>{execution.currentStepName}</p> : null}{execution.failure ? <p>{execution.failure.message}</p> : null}{execution.status !== 'COMPLETED' ? <p>수집이 완전하게 끝나지 않았을 수 있습니다. 아래 목록은 현재 DB에 저장된 범위만 대조합니다.</p> : null}</div> : null}
    <Link to="/admin/quality?reason=SOURCE_CHANGED">수집 이후 원천 변경·소실 검토하기 →</Link>
    <fieldset disabled={disabled || busy} className={styles.filters}><legend>2. 저장된 공식 목록 확인</legend>
      <label>공식 자료 종류<select value={category} onChange={event => { setCategory(event.target.value as SourceCategory); setPage(null); setNotice(''); setProvider('LH') }}><option value="MYHOME_COMPLEX">마이홈 단지</option><option value="LH_ANNOUNCEMENT_DETAIL">LH 공고 상세 단지</option></select></label>
      <label>대조할 기관<select value={provider} onChange={event => { setProvider(event.target.value); setNotice('') }}>{(category === 'MYHOME_COMPLEX' ? ['LH', 'SH', 'GH', 'ETC'] : ['LH']).map(value => <option key={value}>{value}</option>)}</select></label><label>단지명·식별자 검색<input value={keyword} maxLength={200} onChange={event => { request.current?.abort(); setKeyword(event.target.value); setPage(null); setBusy(false) }} /></label><button onClick={() => void fetchPage()}>저장 자료 불러오기</button><button onClick={() => void importAll()}>검색 범위 전체 가져오기</button></fieldset>
    {busy ? <button onClick={() => { request.current?.abort(); setBusy(false); setNotice('가져오기를 중지했습니다. 불완전한 목록은 전달하지 않았습니다.') }}>가져오기 중지</button> : null}
    {busy ? <p role="status">요청을 처리하는 중…</p> : null}{error ? <p role="alert">{error}</p> : null}{notice ? <p role="status">{notice}</p> : null}
    {page ? <><p>전체 검색 {page.totalElements}행 중 현재 페이지 {page.items.length}행 · {provider} 자료 {candidates.length}행</p><ul>{candidates.map(row => <li key={`${row.id}:${row.sourceKey}`}>{String(row.raw.hsmp_nm ?? row.raw.complex_name ?? row.name)} · {String(row.raw.rn_adres ?? row.raw.address ?? '주소 미제공')}</li>)}</ul><p className={styles.hint}>전체 가져오기는 페이지 간 중복을 제거하고 변경 여부를 다시 확인합니다. 최대 250쪽이며 초과·중지·오류는 완료로 처리하지 않습니다.</p><div className={styles.links}><button disabled={disabled || busy || page.page === 0} onClick={() => void fetchPage(page.page - 1)}>이전 저장 자료</button><button disabled={disabled || busy || !page.hasNext} onClick={() => void fetchPage(page.page + 1)}>다음 저장 자료</button><button disabled={disabled || busy || !candidates.length} onClick={importPage}>이 페이지를 대조 목록으로 가져오기</button></div></> : null}
    <p className={styles.hint}>LH 임대 단지 목록의 주소 미제공 자료와 지원하지 않는 홈페이지는 기존 원천 조회·직접 입력으로 조사하세요. 공고 홈페이지 수집은 <Link to="/admin/ingest-v2">공고 수집·검토</Link>에서 지원합니다.</p>
  </details>
}
