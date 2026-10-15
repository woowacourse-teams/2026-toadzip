import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { getSourceData, type SourcePage } from '../ingest/sourceApi'
import { getDataPipelineStatus, startDataPipeline, type DataPipelineExecution } from '../ingest/api'
import { parseOfficialList, type OfficialComplexList } from '../documents/complexCoverage'
import styles from './QualityWorkspace.module.css'

const agencies: Record<string, string> = { 한국토지주택공사: 'LH', 서울주택도시공사: 'SH', SH공사: 'SH', 경기주택도시공사: 'GH' }
export function OfficialSourceWorkflow({ onImport, disabled }: { onImport: (list: OfficialComplexList) => void; disabled: boolean }) {
  const [provider, setProvider] = useState('LH')
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
    try { const result = await getSourceData('MYHOME_COMPLEX', keyword, number, controller.signal); if (!controller.signal.aborted) setPage(result) }
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
  const candidates = (page?.items ?? []).filter(row => (agencies[String(row.raw.instt_nm)] ?? String(row.raw.instt_nm)) === provider
    && typeof row.raw.hsmp_nm === 'string' && typeof row.raw.rn_adres === 'string' && row.raw.rn_adres.trim())
  function importPage() {
    try {
      const unique = [...new Map(candidates.map(row => [`${String(row.raw.hsmp_nm).trim()}:${String(row.raw.rn_adres).trim()}`, row])).values()]
      if (!unique.length || unique.some(row => !row.collectedAt)) throw new Error('수집 시각과 이름·도로명주소가 있는 해당 기관의 자료를 선택해 주세요.')
      const list = parseOfficialList(JSON.stringify({ sourceUrl: unique[0].sourceUrl,
        asOf: unique.map(row => row.collectedAt!.slice(0, 10)).sort().at(-1), agencyCode: provider,
        complexes: unique.map(row => ({ name: row.raw.hsmp_nm, roadAddress: row.raw.rn_adres })) }))
      onImport(list); setNotice(`${list.complexes.length}개 단지를 대조 목록으로 가져왔습니다. 기준일은 마지막 수집일입니다.`)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '목록을 가져오지 못했습니다.') }
  }
  return <details className={styles.workspace}>
    <summary><strong>공식 API 자료로 목록 준비하기</strong></summary>
    <p>마이홈 단지 API에서 수집한 이름·도로명주소를 바로 가져옵니다. 이름·주소가 없는 자료는 미등록으로 판정하지 않습니다.</p>
    <fieldset disabled={disabled || busy} className={styles.filters}><legend>1. 필요하면 공식 자료 갱신</legend>
      <button disabled={execution?.status === 'RUNNING'} onClick={() => void collection(true)}>공식 단지 API 수집</button><button onClick={() => void collection(false)}>수집 상태 확인</button><Link to="/admin/ingest">API 키 설정·수집 상세</Link>
    </fieldset>
    {execution ? <div role="status"><p>수집 상태: {({ IDLE: '대기', RUNNING: '진행 중', COMPLETED: '완료', FAILED: '실패', STOPPED: '중지', COMPLETED_WARNINGS: '일부 경고', COMPLETED_WITH_SKIPS: '일부 건너뜀' })[execution.status]}</p>{execution.currentStepName ? <p>{execution.currentStepName}</p> : null}{execution.failure ? <p>{execution.failure.message}</p> : null}{execution.status !== 'COMPLETED' ? <p>수집이 완전하게 끝나지 않았을 수 있습니다. 아래 목록은 현재 DB에 저장된 범위만 대조합니다.</p> : null}</div> : null}
    <Link to="/admin/quality?reason=SOURCE_CHANGED">수집 이후 원천 변경·소실 검토하기 →</Link>
    <fieldset disabled={disabled || busy} className={styles.filters}><legend>2. 저장된 공식 목록 확인</legend><label>대조할 기관<select value={provider} onChange={event => setProvider(event.target.value)}>{['LH', 'SH', 'GH', 'ETC'].map(value => <option key={value}>{value}</option>)}</select></label><label>단지명·식별자 검색<input value={keyword} maxLength={200} onChange={event => { request.current?.abort(); setKeyword(event.target.value); setPage(null); setBusy(false) }} /></label><button onClick={() => void fetchPage()}>저장 자료 불러오기</button></fieldset>
    {busy ? <p role="status">요청을 처리하는 중…</p> : null}{error ? <p role="alert">{error}</p> : null}{notice ? <p role="status">{notice}</p> : null}
    {page ? <><p>전체 검색 {page.totalElements}행 중 현재 페이지 {page.items.length}행 · {provider} 대조 가능 {candidates.length}행</p><ul>{candidates.map(row => <li key={row.id}>{String(row.raw.hsmp_nm)} · {String(row.raw.rn_adres)}</li>)}</ul><p className={styles.hint}>주택형별 중복 단지는 한 번만 가져옵니다. 다른 기관·주소 미제공 행은 제외합니다. 페이지마다 가져와 대조하며 전체 기관 목록의 완전성을 보증하지 않습니다.</p><div className={styles.links}><button disabled={disabled || busy || page.page === 0} onClick={() => void fetchPage(page.page - 1)}>이전 저장 자료</button><button disabled={disabled || busy || !page.hasNext} onClick={() => void fetchPage(page.page + 1)}>다음 저장 자료</button><button disabled={disabled || busy || !candidates.length} onClick={importPage}>이 페이지를 대조 목록으로 가져오기</button></div></> : null}
    <p className={styles.hint}>LH 임대 단지 목록의 주소 미제공 자료와 지원하지 않는 홈페이지는 기존 원천 조회·직접 입력으로 조사하세요. 공고 홈페이지 수집은 <Link to="/admin/ingest-v2">공고 수집·검토</Link>에서 지원합니다.</p>
  </details>
}
