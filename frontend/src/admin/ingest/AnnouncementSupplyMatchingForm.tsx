import { Fragment, useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { getManagementDetail } from '../management/api'
import { getSupplyMatches, searchMatchingComplexes, getMatchingHousingTypes, refineSupplyMatches,
  releaseSupplyMatch, rerunAnnouncement, setSupplyExclusion,
  type SupplyMatch, type MatchingComplex, type MatchingHousingType } from './supplyMatchingApi'
import styles from './AnnouncementSupplyMatchingForm.module.css'
import { SupplySplitEditor } from './SupplySplitEditor'

/** focusFailed: 오류 상세에서 열 때 실패·재확인 행만 펼치고 이미 연결된 행은 접어 둔다. 정제는 공고 전체 행으로 실행한다. */
export function AnnouncementSupplyMatchingForm({ identifier, disabled = false, focusFailed = false, onApplied, onBusy }: {
  identifier: string; disabled?: boolean; focusFailed?: boolean; onApplied?: () => void; onBusy?: (busy: boolean) => void
}) {
  const [rows, setRows] = useState<SupplyMatch[]>([])
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [loading, setLoading] = useState(true)
  const [revision, setRevision] = useState(0)
  const [busyRows, setBusyRows] = useState(new Set<string>())
  const [choices, setChoices] = useState<Record<string, { complexId: number | null; housingTypeId: number | null; targetToken?: string }>>({})
  const [remember, setRemember] = useState(false)
  const [sourceUrl, setSourceUrl] = useState('')
  const [reason, setReason] = useState('')
  const [refining, setRefining] = useState(false)
  const [splitRow, setSplitRow] = useState<string | null>(null)
  const [splitBusy, setSplitBusy] = useState(false)
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

  const choose = useCallback((rowIdentifier: string, complexId: number | null, housingTypeId: number | null, targetToken?: string) => {
    setChoices(previous => {
      if (previous[rowIdentifier]?.complexId === complexId && previous[rowIdentifier]?.housingTypeId === housingTypeId
        && previous[rowIdentifier]?.targetToken === targetToken) return previous
      return { ...previous, [rowIdentifier]: { complexId, housingTypeId, ...(targetToken ? { targetToken } : {}) } }
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
    if (remember && (!reason.trim() || !/^https?:\/\//i.test(sourceUrl.trim()))) {
      setError('공식 출처 URL과 확인 근거를 입력해 주세요.'); return
    }
    for (const row of rows) {
      if (row.excluded) continue
      const choice = choices[row.rowIdentifier]
      if (!choice || choice.complexId === null) { setError('모든 공급행의 단지를 선택해 주세요.'); return }
      if (remember && (!choice.housingTypeId || !choice.targetToken)) {
        setError('저장할 모든 공급행의 주택형을 명시적으로 선택하고 최신 정보를 확인해 주세요.'); return
      }
      selections.push({ rowIdentifier: row.rowIdentifier, token: row.token,
        complexId: choice.complexId, housingTypeId: choice.housingTypeId,
        ...(remember ? { targetToken: choice.targetToken } : {}) })
    }
    // 바꾼 선택 없이 실행하면 이번에만 쓰는 선택이 없으므로 저장한 연결로 다시 정제한다. 그래야 LH 보강 실패가 오류 목록에 남는다.
    const unchanged = !remember && rows.every(row => row.excluded
      || choices[row.rowIdentifier]?.complexId === row.complexId && choices[row.rowIdentifier]?.housingTypeId === row.housingTypeId)
    pending.current = true; setRefining(true); onBusy?.(true); setError(''); setNotice('')
    try {
      if (unchanged) await rerunAnnouncement(identifier)
      else if (remember) await refineSupplyMatches(identifier, selections, { remember: true, sourceUrl: sourceUrl.trim(), reason: reason.trim() })
      else await refineSupplyMatches(identifier, selections)
      if (alive.current) { setNotice(unchanged ? '저장한 연결로 이 공고를 다시 정제했습니다.' : remember ? '매칭 선택과 근거를 저장하고 공고 정제를 완료했습니다. 같은 원천과 주택형에는 다음 정제에서도 이 연결을 사용합니다.' : '선택한 연결로 공고 정제·저장을 완료했습니다.'); onApplied?.() }
    } catch (cause) {
      if (alive.current) {
        setError(message(cause))
        // 다시 정제가 기록한 실패를 반영하도록 오류 목록과 남은 건수를 새로 고친다.
        if (unchanged) onApplied?.()
      }
    }
    finally { pending.current = false; if (alive.current) { setRefining(false); onBusy?.(false) } }
  }

  async function exclude(row: SupplyMatch, excluded: boolean, exclusionReason: string) {
    if (pending.current || disabled) return
    pending.current = true; setRefining(true); onBusy?.(true); setError(''); setNotice('')
    try {
      await setSupplyExclusion(identifier, row, excluded, exclusionReason)
      if (alive.current) setRevision(value => value + 1)
    } catch (cause) { if (alive.current) setError(message(cause)) }
    finally { pending.current = false; if (alive.current) { setRefining(false); onBusy?.(false) } }
  }

  async function release(row: SupplyMatch) {
    if (pending.current || disabled) return
    if (!reason.trim()) { setError('저장한 선택을 해제할 이유를 확인 근거에 입력해 주세요.'); return }
    if (!window.confirm('저장한 선택을 해제할까요? 현재 등록 데이터는 유지되며 다음 정제부터 자동 매칭합니다.')) return
    pending.current = true; setRefining(true); onBusy?.(true); setError('')
    try {
      await releaseSupplyMatch(identifier, row, reason.trim())
      if (alive.current) { setRevision(value => value + 1); onApplied?.() }
    } catch (cause) { if (alive.current) setError(message(cause)) }
    finally { pending.current = false; if (alive.current) { setRefining(false); onBusy?.(false) } }
  }

  const focused = focusFailed && rows.some(needsAction)
  const editor = (row: SupplyMatch, index: number) => <Fragment key={`${identifier}:${revision}:${row.rowIdentifier}:${row.token}`}>
    <SupplyMatchEditor row={row} index={index} disabled={disabled || refining || splitBusy || Boolean(splitRow && splitRow !== row.rowIdentifier)} onBusy={busy}
      onChange={choose} onRelease={() => { void release(row) }}
      onExclude={(excluded, exclusionReason) => { void exclude(row, excluded, exclusionReason) }}
      hideType={splitRow === row.rowIdentifier || Boolean(row.splitGroup)}
      autoSearch={focused && needsAction(row)}
      onSplit={() => setSplitRow(row.rowIdentifier)} />
    {splitRow === row.rowIdentifier && <SupplySplitEditor identifier={identifier} row={row}
      complexId={choices[row.rowIdentifier]?.complexId ?? row.complexId} disabled={disabled || refining}
      onBusy={value => { setSplitBusy(value); onBusy?.(value) }}
      onCancel={() => setSplitRow(null)} onSaved={() => { setSplitRow(null); setRevision(value => value + 1); onApplied?.() }} />}
  </Fragment>
  const indexed = rows.map((row, index) => ({ row, index }))
  const settled = indexed.filter(({ row }) => !needsAction(row))

  return <section className={styles.workspace} aria-label="공급행 수동 매칭">
    <header className={styles.heading}>
      <div><span className={styles.eyebrow}>공고 {identifier}</span><h4>단지·주택형 연결</h4></div>
      <button className={styles.secondary} type="button" disabled={loading || refining || busyRows.size > 0 || disabled || Boolean(splitRow)}
        onClick={() => { setNotice(''); setRevision(value => value + 1) }}>매칭 정보 새로고침</button>
    </header>
    <p className={styles.description}>원천과 주택형의 면적·세대수를 확인해 연결하세요. 근거와 함께 저장하면 다음 정제에서도 같은 연결을 사용합니다.</p>
    {loading && <p role="status">매칭 정보를 불러오는 중…</p>}
    {error && <p role="alert">{error}</p>}
    {notice && <p className={styles.notice} role="status">{notice} {!focusFailed && <Link to="/admin/announcements">공고 관리로 이동</Link>}</p>}
    {!loading && !error && rows.length === 0 && <p>매칭할 공급행이 없습니다.</p>}
    {!loading && !notice && (focused ? <>
      <p className={styles.description}>단지·주택형을 정하지 못한 공급행 {rows.length - settled.length}개입니다. 원천 단지명으로 후보를 미리 찾아 두었습니다.</p>
      {indexed.filter(({ row }) => needsAction(row)).map(({ row, index }) => editor(row, index))}
      {settled.length > 0 && <details className={styles.settled}>
        <summary>이미 연결했거나 제외한 공급행 {settled.length}개 · 지금 상태 그대로 정제합니다</summary>
        {settled.map(({ row, index }) => editor(row, index))}
      </details>}
    </> : indexed.map(({ row, index }) => editor(row, index)))}
    {!loading && !notice && !splitRow && !rows.some(row => row.splitGroup) && rows.length > 0 && <fieldset className={styles.evidence} disabled={disabled || refining}>
      <legend>선택 적용 방식과 근거</legend>
      <label className={styles.remember}><input type="checkbox" checked={remember} onChange={event => setRemember(event.target.checked)} />이 선택을 저장해 다음 정제에도 사용</label>
      <p>{remember ? '모든 공급행의 주택형과 공식 근거를 확인하세요. 원천이나 연결 대상이 달라지면 다시 확인해야 합니다.' : '선택은 이번 정제에만 사용합니다. 이미 저장한 매칭은 바뀌지 않습니다.'}</p>
      {remember && <label>공식 출처 URL<input type="url" value={sourceUrl} maxLength={2048} onChange={event => setSourceUrl(event.target.value)} /></label>}
      {(remember || rows.some(row => row.savedMatch)) && <label>확인 근거<textarea value={reason} maxLength={2000} rows={3} placeholder="공고문 쪽수, 주택형 대응 근거 또는 선택 해제 이유" onChange={event => setReason(event.target.value)} /></label>}
    </fieldset>}
    {!loading && !notice && !splitRow && !rows.some(row => row.splitGroup) && rows.length > 0 && <footer className={styles.footer}>
      <p>모든 공급행을 한 번에 정제·저장합니다. 실패하면 최종 데이터는 변경하지 않습니다.</p>
      <Button disabled={disabled || refining || busyRows.size > 0 || rows.some(row => !row.excluded && !choices[row.rowIdentifier]?.complexId)}
        onClick={() => { void refine() }}>{refining ? '정제·저장 중…' : remember ? '선택 저장·정제 실행' : '선택 적용·정제 실행'}</Button>
    </footer>}
  </section>
}

function SupplyMatchEditor({ row, index, disabled, onBusy, onChange, onRelease, onExclude, hideType, autoSearch, onSplit }: {
  row: SupplyMatch; index: number; disabled: boolean; autoSearch: boolean
  onExclude: (excluded: boolean, reason: string) => void
  onBusy: (rowIdentifier: string, busy: boolean) => void
  onChange: (rowIdentifier: string, complexId: number | null, housingTypeId: number | null, targetToken?: string) => void
  onRelease: () => void
  hideType: boolean; onSplit: () => void
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
  const selectedType = types.find(value => String(value.id) === housingTypeId)
  // 원천에서 같은 이름·주소로 나뉜 단지가 많아 원천과 같은 면적의 주택형이 있는 단지, PNU가 같은 단지,
  // 세대수가 큰 본 단지 순서로 올린다. 면적이 맞아야 고른 뒤 주택형이 없어 막히지 않는다.
  const sameArea = (value: MatchingComplex) => sameAreaType(value.housingTypes, row.exclusiveArea)
  const candidates = [...results].sort((left, right) =>
    Number(Boolean(sameArea(right))) - Number(Boolean(sameArea(left)))
    || Number(right.pnu === row.pnu) - Number(left.pnu === row.pnu) || right.totalHouseholdCount - left.totalHouseholdCount)
  const areaMissing = row.exclusiveArea !== null && complexId !== null && !loadingTypes && types.length > 0
    && !sameAreaType(types, row.exclusiveArea)
  useEffect(() => () => request.current?.abort(), [])
  // 오류 상세에서 연 미연결 행은 원천 단지명으로 후보를 바로 보여 줘 검색 버튼을 누르지 않아도 고를 수 있게 한다.
  useEffect(() => {
    if (autoSearch && row.complexId === null && row.sourceComplexName.trim()) void find(row.sourceComplexName.trim(), row.pnu)
  }, [autoSearch, row.rowIdentifier, row.complexId, row.sourceComplexName, row.pnu])
  useEffect(() => {
    onChange(row.rowIdentifier, complexId, housingTypeId === '' ? null : Number(housingTypeId), selectedType?.token)
  }, [row.rowIdentifier, complexId, housingTypeId, selectedType?.token, onChange])
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
    await find(query.trim(), row.pnu)
  }
  async function find(keyword: string, pnu: string) {
    request.current?.abort()
    const controller = new AbortController(); request.current = controller
    setSearching(true); setError(''); setResults([])
    try {
      const found = await searchMatchingComplexes(keyword, controller.signal, pnu)
      if (!controller.signal.aborted) setResults(found)
    } catch (cause) { if (!controller.signal.aborted) setError(message(cause)) }
    finally { if (!controller.signal.aborted) setSearching(false) }
  }
  if (row.excluded) return <fieldset className={styles.card} disabled={disabled}>
    <legend>원천행 {index + 1}</legend>
    <div className={styles.saved}>
      <strong>공개에서 제외됨</strong>
      <p>{row.sourceComplexName} · {row.sourceHousingTypeName} · 전용 {area(row.exclusiveArea)}</p>
      <p>등록 단지에 없는 주택형이라 공고에 표시하지 않습니다. 정제도 이 행을 오류로 남기지 않습니다.</p>
    </div>
    <ExclusionControl label="제외 취소" placeholder="되돌리는 이유" onSubmit={value => onExclude(false, value)} />
  </fieldset>
  return <fieldset className={styles.card} disabled={disabled}>
    <legend>원천행 {index + 1}</legend>
    {row.splitGroup && <div className={styles.saved}>
      <strong>{row.splitGroup.reviewRequired ? '보정 공급행 재확인 필요' : `보정 공급행 ${row.splitGroup.rows.length}개 유지 중`}</strong>
      <p>공통 모집수 {row.splitGroup.sharedHouseholdCount ?? '미확정'} · 개별 모집수 미확정</p>
      <ul>{row.splitGroup.rows.map(value => <li key={value.id}>{value.name} · 주택형 #{value.housingTypeId ?? '삭제됨'}</li>)}</ul>
    </div>}
    {row.savedMatch && <div className={styles.saved}>
      <strong>{row.savedMatch.reviewRequired ? '저장한 매칭 재확인 필요' : '저장한 매칭 사용 중'}</strong>
      <p>단지 #{row.savedMatch.complexId ?? '삭제됨'} · 주택형 #{row.savedMatch.housingTypeId ?? '삭제됨'} · {row.savedMatch.reason}</p>
      <a href={row.savedMatch.sourceUrl} target="_blank" rel="noreferrer">저장한 공식 근거 보기</a>
      <p>기록: {row.savedMatch.actor} · {new Date(row.savedMatch.updatedAt).toLocaleString('ko-KR')}</p>
      <button type="button" onClick={onRelease}>저장한 선택 해제</button>
    </div>}
    <div className={styles.columns}>
    <section className={styles.source} aria-label="원천 정보">
      <span className={styles.eyebrow}>수집된 원천</span>
      <h5>{row.sourceComplexName}</h5>
      <dl className={styles.facts}>
        <div><dt>원천에 표시된 유형</dt><dd>{row.sourceHousingTypeName}</dd></div>
        <div><dt>전용면적</dt><dd>{area(row.exclusiveArea)}</dd></div>
        <div><dt>공급면적</dt><dd>{area(row.supplyArea)}</dd></div>
        <div><dt>PNU</dt><dd>{row.pnu || '미제공'}</dd></div>
      </dl>
      <p className={row.failure ? styles.warning : styles.notice}>{row.failure ?? (row.splitGroup ? '공고 기준의 복수 공급행 보정을 사용합니다.' : '단지·주택형 매칭 완료')}</p>
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
      {candidates.length > 0 && <ul className={styles.results}>{candidates.map(value => <li key={value.id}>
        <button className={styles.result} type="button" onClick={() => {
          setComplexId(value.id); setComplexLabel(value.name); setComplexAddress(value.roadAddress)
          setHousingTypeId(String(sameArea(value)?.id ?? '')); setError(''); setResults([])
          setSearchOpen(false); request.current = null
        }}><strong>{value.name} <small>#{value.id}</small>{sameArea(value) && <em className={styles.match}>면적 일치</em>}
            {row.pnu && value.pnu === row.pnu && <em className={styles.match}>PNU 일치</em>}</strong>
          <span>{value.roadAddress} · {value.supplyType}</span>
          <span>{value.totalHouseholdCount.toLocaleString('ko-KR')}세대 · 주택형 {value.housingTypes.length}개 · PNU {value.pnu}</span>
          {row.exclusiveArea !== null && <span>{sameArea(value)
            ? `같은 면적 주택형: ${sameArea(value)!.name} (${area(sameArea(value)!.exclusiveArea)})`
            : `전용 ${area(row.exclusiveArea)} 주택형 없음`}</span>}</button>
      </li>)}</ul>}
      {!searching && !error && request.current && results.length === 0 && <p>검색 결과가 없습니다. 검색은 최대 20건입니다.</p>}
    </>}
    {!hideType && <><label className={styles.typeSelect}>매칭 주택형<select value={housingTypeId} disabled={complexId === null || loadingTypes}
      onChange={event => setHousingTypeId(event.target.value)}>
      <option value="">단지만 지정 (주택형 자동 매칭)</option>
      {types.map(value => <option key={value.id} value={value.id}>
        {value.name} · 전용 {area(value.exclusiveArea)} · 공급 {area(value.supplyArea)}
        {value.householdCount != null ? ` · ${value.householdCount}세대` : ''}
      </option>)}
    </select></label>
    {selectedType && <dl className={styles.typePreview} aria-label="선택한 주택형 면적">
      <div><dt>전용면적</dt><dd>{area(selectedType.exclusiveArea)}</dd></div>
      <div><dt>공급면적</dt><dd>{area(selectedType.supplyArea)}</dd></div>
      {selectedType.householdCount != null && <div><dt>전체 세대수</dt><dd>{selectedType.householdCount}세대 · 모집수와 별개</dd></div>}
    </dl>}</>}
    {loadingTypes && <p>선택 단지의 주택형을 불러오는 중…</p>}
    {!loadingTypes && complexId !== null && types.length === 0 && <p>이 단지에는 주택형이 없습니다. 단지 관리에서 먼저 등록해 주세요.</p>}
    {areaMissing && <p className={styles.warning} role="alert">이 단지에는 전용 {area(row.exclusiveArea)} 주택형이 없습니다. 다른 후보 단지를 확인하세요.
      어느 단지에도 없으면 아래에서 이 공급행을 제외할 수 있습니다.</p>}
    {error && <p role="alert">{error}</p>}
    </section>
    </div>
    <button type="button" className={styles.secondary} onClick={onSplit} disabled={disabled}>
      {row.splitGroup ? '보정 공급행 확인·수정' : '이 원천을 여러 공급행으로 나누기'}
    </button>
    {row.failure && !row.splitGroup && <ExclusionControl label="등록 단지에 없는 주택형이면 이 공급행 제외"
      placeholder="공고문 쪽수와 등록 단지에 없다고 판단한 근거" onSubmit={value => onExclude(true, value)} />}
  </fieldset>
}

/** 공급행 제외·취소는 근거를 남겨야 해서 사유를 적은 뒤에만 저장한다. */
function ExclusionControl({ label, placeholder, onSubmit }: { label: string; placeholder: string; onSubmit: (reason: string) => void }) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  if (!open) return <button type="button" className={styles.textButton} onClick={() => setOpen(true)}>{label}</button>
  return <div className={styles.exclusion}>
    {!label.endsWith('취소') && <p className={styles.warning}>제외하면 이 모집이 공고에 표시되지 않습니다. 이름·PNU가 같은 단지가 여러 개라면
      주택형이 다른 조각 단지에만 있을 수 있으니, 제외하지 말고 후보를 모두 확인하거나 단지 통합 후 다시 정제하세요.
      어느 단지에도 없는 모집일 때만 제외합니다.</p>}
    <label>제외 근거<textarea value={reason} maxLength={2000} rows={2} placeholder={placeholder}
      onChange={event => setReason(event.target.value)} /></label>
    <div className={styles.exclusionActions}>
      <button type="button" className={styles.secondary} disabled={!reason.trim()} onClick={() => onSubmit(reason.trim())}>
        {label.endsWith('취소') ? '제외 취소 저장' : '제외 저장'}</button>
      <button type="button" className={styles.textButton} onClick={() => { setOpen(false); setReason('') }}>닫기</button>
    </div>
  </div>
}

/** 원천과 전용면적이 같은 주택형. 원천은 소수 둘째 자리, 등록 주택형은 넷째 자리까지 있어 0.01㎡ 안을 같은 면적으로 본다. */
function sameAreaType<T extends { exclusiveArea: number }>(types: T[], exclusiveArea: number | null): T | undefined {
  if (exclusiveArea === null) return undefined
  return types.find(type => Math.abs(type.exclusiveArea - exclusiveArea) < 0.01)
}
function needsAction(row: SupplyMatch): boolean {
  if (row.excluded) return false
  return Boolean(row.failure || row.savedMatch?.reviewRequired || row.splitGroup?.reviewRequired)
}
function area(value: number | null): string { return value === null ? '미제공' : `${value}㎡` }
function message(cause: unknown): string { return cause instanceof Error ? cause.message : '매칭을 처리하지 못했습니다.' }
