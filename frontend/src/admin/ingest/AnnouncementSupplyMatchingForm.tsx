import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { getManagementDetail } from '../management/api'
import { getSupplyMatches, searchMatchingComplexes, getMatchingHousingTypes, refineSupplyMatches,
  type SupplyMatch, type MatchingComplex, type MatchingHousingType } from './supplyMatchingApi'
import styles from './AnnouncementSupplyMatchingForm.module.css'

export function AnnouncementSupplyMatchingForm({ identifier, disabled = false }: {
  identifier: string; disabled?: boolean
}) {
  const [rows, setRows] = useState<SupplyMatch[]>([])
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [loading, setLoading] = useState(true)
  const [revision, setRevision] = useState(0)
  const [busyRows, setBusyRows] = useState(new Set<string>())
  const [choices, setChoices] = useState<Record<string, { complexId: number | null; housingTypeId: number | null }>>({})
  const [refining, setRefining] = useState(false)
  const pending = useRef(false)
  const alive = useRef(false)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError(''); setNotice(''); setRows([]); setChoices({})
    void getSupplyMatches(identifier, controller.signal).then(result => {
      if (!controller.signal.aborted) {
        setRows(result)
        setChoices(Object.fromEntries(result.map(row => [row.rowIdentifier,
          { complexId: row.complexId, housingTypeId: row.housingTypeId }])))
      }
    }).catch(cause => { if (!controller.signal.aborted) setError(message(cause)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [identifier, revision])

  const choose = useCallback((rowIdentifier: string, complexId: number | null, housingTypeId: number | null) => {
    setChoices(previous => {
      if (previous[rowIdentifier]?.complexId === complexId && previous[rowIdentifier]?.housingTypeId === housingTypeId) return previous
      return { ...previous, [rowIdentifier]: { complexId, housingTypeId } }
    })
  }, [])
  const busy = useCallback((rowIdentifier: string, value: boolean) => setBusyRows(previous => {
    const next = new Set(previous)
    if (value) next.add(rowIdentifier)
    else next.delete(rowIdentifier)
    return next
  }), [])
  async function refine() {
    if (pending.current || disabled || loading || busyRows.size > 0 || rows.length === 0) return
    const selections = []
    for (const row of rows) {
      const choice = choices[row.rowIdentifier]
      if (!choice || choice.complexId === null) { setError('모든 공급행의 단지를 선택해 주세요.'); return }
      selections.push({ rowIdentifier: row.rowIdentifier, token: row.token,
        complexId: choice.complexId, housingTypeId: choice.housingTypeId })
    }
    pending.current = true; setRefining(true); setError(''); setNotice('')
    try {
      await refineSupplyMatches(identifier, selections)
      if (alive.current) setNotice('선택한 연결로 공고 정제·저장을 완료했습니다.')
    } catch (cause) { if (alive.current) setError(message(cause)) }
    finally { pending.current = false; if (alive.current) setRefining(false) }
  }

  return <section className={styles.workspace} aria-label="공급행 수동 매칭">
    <header className={styles.heading}>
      <div><span className={styles.eyebrow}>공고 {identifier}</span><h4>단지·주택형 연결</h4></div>
      <button className={styles.secondary} type="button" disabled={loading || refining || busyRows.size > 0 || disabled}
        onClick={() => { setNotice(''); setRevision(value => value + 1) }}>매칭 정보 새로고침</button>
    </header>
    <p className={styles.description}>단지·주택형을 선택한 뒤 정제를 실행하세요. 선택은 이번 정제에만 사용하며 원본 데이터는 변경하지 않습니다.</p>
    {loading && <p role="status">매칭 정보를 불러오는 중…</p>}
    {error && <p role="alert">{error}</p>}
    {notice && <p className={styles.notice} role="status">{notice} <Link to="/admin/announcements">공고 관리로 이동</Link></p>}
    {!loading && !error && rows.length === 0 && <p>매칭할 공급행이 없습니다.</p>}
    {!loading && !notice && rows.map((row, index) => <SupplyMatchEditor key={`${identifier}:${revision}:${row.rowIdentifier}:${row.token}`}
      row={row} index={index} disabled={disabled || refining} onBusy={busy} onChange={choose} />)}
    {!loading && !notice && rows.length > 0 && <footer className={styles.footer}>
      <p>모든 공급행을 한 번에 정제·저장합니다. 실패하면 최종 데이터는 변경하지 않습니다.</p>
      <Button disabled={disabled || refining || busyRows.size > 0 || rows.some(row => !choices[row.rowIdentifier]?.complexId)}
        onClick={() => { void refine() }}>{refining ? '정제·저장 중…' : '선택 적용·정제 실행'}</Button>
    </footer>}
  </section>
}

function SupplyMatchEditor({ row, index, disabled, onBusy, onChange }: {
  row: SupplyMatch; index: number; disabled: boolean
  onBusy: (rowIdentifier: string, busy: boolean) => void
  onChange: (rowIdentifier: string, complexId: number | null, housingTypeId: number | null) => void
}) {
  const [query, setQuery] = useState(row.sourceComplexName)
  const [results, setResults] = useState<MatchingComplex[]>([])
  const [complexId, setComplexId] = useState(row.complexId)
  const [searchOpen, setSearchOpen] = useState(row.complexId === null)
  const [complexLabel, setComplexLabel] = useState(row.complexId ? `단지 #${row.complexId}` : '')
  const [complexAddress, setComplexAddress] = useState('')
  const [complexDetailError, setComplexDetailError] = useState('')
  const [housingTypeId, setHousingTypeId] = useState(row.housingTypeId === null ? '' : String(row.housingTypeId))
  const [types, setTypes] = useState<MatchingHousingType[]>([])
  const [searching, setSearching] = useState(false)
  const [loadingTypes, setLoadingTypes] = useState(false)
  const [error, setError] = useState('')
  const request = useRef<AbortController | null>(null)
  useEffect(() => () => request.current?.abort(), [])
  useEffect(() => {
    onChange(row.rowIdentifier, complexId, housingTypeId === '' ? null : Number(housingTypeId))
  }, [row.rowIdentifier, complexId, housingTypeId, onChange])
  useEffect(() => {
    onBusy(row.rowIdentifier, loadingTypes || searching)
    return () => onBusy(row.rowIdentifier, false)
  }, [row.rowIdentifier, loadingTypes, searching, onBusy])
  useEffect(() => {
    if (complexId === null) return
    const controller = new AbortController()
    setComplexDetailError('')
    void getManagementDetail('complexes', String(complexId), controller.signal).then(value => {
      if (!controller.signal.aborted) {
        setComplexLabel(value.summary.name)
        setComplexAddress(value.summary.subtitle)
      }
    }).catch(() => {
      if (!controller.signal.aborted) setComplexDetailError('단지 정보를 불러오지 못했습니다. 단지 상세에서 확인해 주세요.')
    })
    return () => controller.abort()
  }, [complexId])
  useEffect(() => {
    const controller = new AbortController()
    setTypes([])
    if (complexId === null) { setLoadingTypes(false); return () => controller.abort() }
    setLoadingTypes(true)
    void getMatchingHousingTypes(complexId, controller.signal).then(value => {
      if (!controller.signal.aborted) setTypes(value)
    }).catch(cause => { if (!controller.signal.aborted) setError(message(cause)) })
      .finally(() => { if (!controller.signal.aborted) setLoadingTypes(false) })
    return () => controller.abort()
  }, [complexId])

  async function search(event: FormEvent) {
    event.preventDefault()
    if (!query.trim() || disabled) return
    request.current?.abort()
    const controller = new AbortController(); request.current = controller
    setSearching(true); setError(''); setResults([])
    try {
      const found = await searchMatchingComplexes(query.trim(), controller.signal)
      if (!controller.signal.aborted) setResults(found)
    } catch (cause) { if (!controller.signal.aborted) setError(message(cause)) }
    finally { if (!controller.signal.aborted) setSearching(false) }
  }
  const selectedType = types.find(value => String(value.id) === housingTypeId)
  return <fieldset className={styles.card} disabled={disabled}>
    <legend>공급행 {index + 1}</legend>
    <div className={styles.columns}>
    <section className={styles.source} aria-label="원천 정보">
      <span className={styles.eyebrow}>수집된 원천</span>
      <h5>{row.sourceComplexName}</h5>
      <dl className={styles.facts}>
        <div><dt>원천 주택형</dt><dd>{row.sourceHousingTypeName}</dd></div>
        <div><dt>전용면적</dt><dd>{area(row.exclusiveArea)}</dd></div>
        <div><dt>공급면적</dt><dd>{area(row.supplyArea)}</dd></div>
        <div><dt>PNU</dt><dd>{row.pnu || '미제공'}</dd></div>
      </dl>
      <p className={row.failure ? styles.warning : styles.notice}>{row.failure ?? '단지·주택형 매칭 완료'}</p>
    </section>
    <section className={styles.target} aria-label="연결 대상">
      <div className={styles.heading}>
        <span className={styles.eyebrow}>연결할 단지</span>
        {complexId !== null && <button className={styles.textButton} type="button" aria-expanded={searchOpen}
          onClick={() => setSearchOpen(value => !value)}>단지 다시 선택하기</button>}
      </div>
      <h5>{complexLabel || '단지를 선택해 주세요'}</h5>
      {complexAddress && <p className={styles.description}>{complexAddress}</p>}
      {complexDetailError && <p className={styles.description}>{complexDetailError}</p>}
    {searchOpen && <>
      <form className={styles.search} onSubmit={event => { void search(event) }}>
        <label>단지 검색<input maxLength={100} value={query} onChange={event => setQuery(event.target.value)} /></label>
        <button className={styles.secondary} type="submit" disabled={searching || !query.trim()}>{searching ? '검색 중…' : '검색'}</button>
      </form>
      {results.length > 0 && <ul className={styles.results}>{results.map(value => <li key={value.id}>
        <button className={styles.result} type="button" onClick={() => {
          setComplexId(value.id); setComplexLabel(value.name); setComplexAddress(value.roadAddress)
          setHousingTypeId(''); setError(''); setResults([])
          setSearchOpen(false); request.current = null
        }}><strong>{value.name}</strong><span>{value.roadAddress} · {value.supplyType}</span></button>
      </li>)}</ul>}
      {!searching && !error && request.current && results.length === 0 && <p>검색 결과가 없습니다. 검색은 최대 20건입니다.</p>}
    </>}
    <label className={styles.typeSelect}>매칭 주택형<select value={housingTypeId} disabled={complexId === null || loadingTypes}
      onChange={event => setHousingTypeId(event.target.value)}>
      <option value="">단지만 지정 (주택형 자동 매칭)</option>
      {types.map(value => <option key={value.id} value={value.id}>
        {value.name} · 전용 {area(value.exclusiveArea)} · 공급 {area(value.supplyArea)}
      </option>)}
    </select></label>
    {selectedType && <dl className={styles.typePreview} aria-label="선택한 주택형 면적">
      <div><dt>전용면적</dt><dd>{area(selectedType.exclusiveArea)}</dd></div>
      <div><dt>공급면적</dt><dd>{area(selectedType.supplyArea)}</dd></div>
    </dl>}
    {loadingTypes && <p>선택 단지의 주택형을 불러오는 중…</p>}
    {!loadingTypes && complexId !== null && types.length === 0 && <p>이 단지에는 주택형이 없습니다. 단지 관리에서 먼저 등록해 주세요.</p>}
    {error && <p role="alert">{error}</p>}
    </section>
    </div>
  </fieldset>
}

function area(value: number | null): string { return value === null ? '미제공' : `${value}㎡` }
function message(cause: unknown): string { return cause instanceof Error ? cause.message : '매칭을 처리하지 못했습니다.' }
