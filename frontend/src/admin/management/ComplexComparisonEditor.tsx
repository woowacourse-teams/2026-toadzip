import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { getSourceData, type SourcePage } from '../ingest/sourceApi'
import { StoredDataTable } from '../shared/StoredDataTable'
import { AddressPicker } from '../registration/AddressPicker'
import { ManagementError, requestManagementApi } from './api'
import { type Field, labels, valueAt } from './fields'
import { isRecord, type ManagementValues } from './managementContract'
import { getComparison, linkComparisonSource, saveComparison, unlinkComparisonSource, type Comparison, type ComparisonCategory } from './complexComparisonApi'
import { categoryLabels, changedComparisonFields, comparisonFields, comparisonText, sourceValue, writeComparisonValue } from './complexComparisonValues'
import { useUnsavedChanges } from './useUnsavedChanges'
import styles from './ComplexComparison.module.css'

type Props = { id: string; version: number; deleted: boolean; onChanged: () => void; onBusyChange: (busy: boolean) => void; onDirtyChange?: (dirty: boolean) => void }
export function ComplexComparisonEditor({ id, version, deleted, onChanged, onBusyChange, onDirtyChange }: Props) {
  const [snapshot, setSnapshot] = useState<Comparison | null>(null)
  const [draft, setDraft] = useState<ManagementValues>({})
  const [editing, setEditing] = useState<string[]>([])
  const [note, setNote] = useState('')
  const [addressConfirmed, setAddressConfirmed] = useState(false)
  const [differentOnly, setDifferentOnly] = useState(true)
  const [category, setCategory] = useState<ComparisonCategory>('MYHOME_COMPLEX')
  const [keyword, setKeyword] = useState('')
  const [results, setResults] = useState<SourcePage | null>(null)
  const [linkNote, setLinkNote] = useState('')
  const [searching, setSearching] = useState(false)
  const [busy, setBusy] = useState(false)
  const [conflict, setConflict] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const session = useRef<AbortController | null>(null)
  const searchSession = useRef<AbortController | null>(null)
  const changed = snapshot ? changedComparisonFields(snapshot.current, draft) : []
  const addressChanged = changed.some(field => field.name.startsWith('address.'))
  const dirty = changed.length > 0 || Boolean(note.trim() || linkNote.trim())
  useUnsavedChanges(dirty)
  useEffect(() => { onDirtyChange?.(dirty); return () => onDirtyChange?.(false) }, [dirty, onDirtyChange])
  useEffect(() => {
    const controller = new AbortController(); session.current = controller
    setSnapshot(null); setError(''); setConflict(false); setEditing([]); setNote(''); setLinkNote(''); setResults(null); setAddressConfirmed(false)
    void getComparison(id, controller.signal).then(value => {
      if (!controller.signal.aborted) { setSnapshot(value); setDraft(structuredClone(value.current)); setKeyword(String(value.current.name ?? '')) }
    }).catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '원천 비교를 불러오지 못했습니다.') })
    return () => { controller.abort(); searchSession.current?.abort() }
  }, [id, version, attempt])
  useEffect(() => { onBusyChange(busy); return () => onBusyChange(false) }, [busy, onBusyChange])

  function reload() {
    if (dirty && !window.confirm('작성한 수정값과 근거를 버리고 다시 조회할까요?')) return
    setNotice(''); setAttempt(value => value + 1)
  }
  function write(field: Field, text: string) {
    setDraft(value => writeComparisonValue(value, field, text))
    setFieldErrors({}); setNotice('')
    if (field.name.startsWith('address.')) setAddressConfirmed(false)
  }
  async function mutate(action: 'save' | 'link' | 'unlink', source?: { sourceKey: string; category: ComparisonCategory }) {
    if (!snapshot || !session.current || busy || deleted || conflict) return
    if (action === 'save' && (!changed.length || !note.trim() || addressChanged && !addressConfirmed)) return
    if (action !== 'save' && (!source || !linkNote.trim() || changed.length)) return
    const controller = session.current; setBusy(true); setError(''); setNotice(''); setFieldErrors({})
    try {
      const value = action === 'save'
        ? await saveComparison(id, { snapshotToken: snapshot.snapshotToken, current: draft, evidenceNote: note.trim(), addressConfirmed }, controller.signal)
        : await (action === 'link' ? linkComparisonSource : unlinkComparisonSource)(id, { snapshotToken: snapshot.snapshotToken,
          category: source?.category ?? category, sourceKey: source?.sourceKey ?? '', evidenceNote: linkNote.trim() }, controller.signal)
      if (controller.signal.aborted) return
      setSnapshot(value); setDraft(structuredClone(value.current)); setEditing([]); setAddressConfirmed(false)
      if (action === 'save') setNote('')
      else setLinkNote('')
      setNotice(action === 'save' ? '수정값과 비교 근거를 저장했습니다. 확인 완료 기록은 아래에서 남길 수 있습니다.' : action === 'link' ? '비교 원천을 연결했습니다. 아래에서 현재값과 대조하세요.' : '직접 연결한 비교 원천을 해제했습니다.')
      if (action === 'save') onChanged()
    } catch (cause) {
      if (controller.signal.aborted) return
      setError(cause instanceof Error ? cause.message : '저장하지 못했습니다.')
      if (cause instanceof ManagementError) { setConflict(cause.status === 409); setFieldErrors(cause.fields) }
    } finally { if (!controller.signal.aborted) setBusy(false) }
  }
  async function search(page = 0) {
    searchSession.current?.abort(); const controller = new AbortController(); searchSession.current = controller
    setSearching(true); setResults(null); setError('')
    try {
      const value = await getSourceData(category, keyword.trim(), page, controller.signal)
      if (!controller.signal.aborted) setResults(value)
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '원천 검색에 실패했습니다.') }
    finally { if (!controller.signal.aborted) setSearching(false) }
  }
  async function recordSourceCheck() {
    if (!snapshot || !session.current || busy || changed.length || !note.trim()) return
    const controller = session.current; setBusy(true); setError(''); setNotice('')
    try {
      await requestManagementApi(`/api/admin/quality/COMPLEX/${encodeURIComponent(id)}/comparison/checks/legacy`, 'POST',
        { target: 'basic', snapshotToken: snapshot.snapshotToken, evidenceNote: note.trim() }, controller.signal)
      if (!controller.signal.aborted) { setNote(''); setNotice('현재 원천 확인을 기록했습니다. 원천값이 바뀌거나 사라지면 검토 목록에 표시됩니다. 등록값의 확인 범위 기록은 별도로 남겨 주세요.') }
    } catch (cause) {
      if (!controller.signal.aborted) { setError(cause instanceof Error ? cause.message : '원천 확인을 기록하지 못했습니다.'); if (cause instanceof ManagementError && cause.status === 409) setConflict(true) }
    } finally { if (!controller.signal.aborted) setBusy(false) }
  }
  function differs(field: Field) {
    return snapshot?.sources.some(source => {
      const candidate = sourceValue(field, source)
      return candidate.value !== undefined && JSON.stringify(candidate.value) !== JSON.stringify(valueAt(snapshot.current, field.name))
    }) ?? false
  }
  const ordinaryFields = comparisonFields.filter(field => !field.name.startsWith('address.'))
  const addressFields = comparisonFields.filter(field => field.name.startsWith('address.'))
  const ordinaryDifferenceCount = ordinaryFields.filter(differs).length
  const addressDifferenceCount = addressFields.filter(differs).length
  const differenceCount = ordinaryDifferenceCount + addressDifferenceCount
  // 원천마다 같은 값을 반복하지 않도록 같은 값을 낸 원천을 한 칸으로 묶는다.
  function sourceGroups(field: Field) {
    const groups = new Map<string, { key: string; candidate: ReturnType<typeof sourceValue>; indexes: number[]; categories: Set<string>; missing: boolean }>()
    snapshot?.sources.forEach((source, index) => {
      const candidate = sourceValue(field, source)
      const key = `${candidate.raw}|${JSON.stringify(candidate.value) ?? ''}|${!source.data}`
      const group = groups.get(key) ?? { key, candidate, indexes: [], categories: new Set<string>(), missing: !source.data }
      group.indexes.push(index + 1); group.categories.add(categoryLabels[source.category]); groups.set(key, group)
    })
    const total = snapshot?.sources.length ?? 0
    return [...groups.values()].map(group => ({ ...group,
      label: `${total > 1 && group.indexes.length === total ? `원천 ${total}개 모두` : `원천 ${group.indexes.join('·')}`} · ${[...group.categories].join('·')}${group.missing ? ' · 현재 원천 없음' : ''}` }))
  }
  function comparisonRow(field: Field) {
    if (!snapshot) return null
    const current = valueAt(snapshot.current, field.name)
    const selected = editing.includes(field.name)
    return <div key={field.name} className={styles.row} role="group" aria-label={`${field.label} 비교`} data-different={differs(field) || undefined}>
      <div className={styles.field}><strong>{field.label}</strong>{differs(field) ? <span className={styles.difference}>값 다름</span> : null}</div>
      <div className={styles.current}><span className={styles.mobileLabel}>현재 등록값</span><strong>{comparisonText(current)}</strong></div>
      <div className={styles.source}><span className={styles.mobileLabel}>DB 원천값</span>{snapshot.sources.length ? sourceGroups(field).map(group =>
        <div key={group.key}><span>{group.candidate.raw}</span><small>{group.label}</small>
          {group.candidate.value !== undefined && !field.name.startsWith('address.') && JSON.stringify(group.candidate.value) !== JSON.stringify(current)
            ? <button type="button" disabled={busy || deleted || conflict} onClick={() => {
              write(field, group.candidate.value === null ? '' : String(group.candidate.value)); setEditing(value => [...new Set([...value, field.name])])
            }}>이 원천값 사용</button> : null}
        </div>) : <span className={styles.muted}>연결된 원천 없음</span>}</div>
      <div className={styles.edit}>{selected ? <label>{field.label} 수정값{field.options ? <select aria-label={`${field.label} 수정값`} value={String(valueAt(draft, field.name) ?? '')}
          onChange={event => write(field, event.target.value)} disabled={busy || deleted || conflict} required={field.required}>
          <option value="">{field.required ? '선택해 주세요' : '미확인'}</option>
          {[...new Set([...(field.options.includes(String(valueAt(draft, field.name))) ? [] : valueAt(draft, field.name) ? [String(valueAt(draft, field.name))] : []), ...field.options])]
            .map(option => <option key={option} value={option}>{labels[option] ?? option}</option>)}
        </select> : <input aria-label={`${field.label} 수정값`} type={field.type ?? 'text'} value={String(valueAt(draft, field.name) ?? '')} required={field.required}
          min={field.min} max={field.max} step={field.step ?? (field.type === 'number' ? '1' : undefined)} maxLength={field.type ? undefined : 255}
          disabled={busy || deleted || conflict} onChange={event => write(field, event.target.value)} />}
        {fieldErrors[`current.${field.name}`] ? <small role="alert">{fieldErrors[`current.${field.name}`]}</small> : null}
      </label> : <button type="button" aria-label={`${field.label} 수정`} disabled={busy || deleted || conflict} onClick={() => setEditing(value => [...value, field.name])}>수정</button>}</div>
    </div>
  }
  return <section className={styles.panel} aria-label="DB 원천 비교·수정" aria-busy={busy}>
    <header className={styles.heading}><div><h2>원천과 비교하고 바로 수정</h2><p>DB에 저장된 자료를 불러옵니다. 원천값의 적용 범위를 확인한 뒤 필요한 항목만 수정하세요.</p></div>
      <button type="button" disabled={busy} onClick={reload}>비교 데이터 다시 조회</button></header>
    {error ? <div className={styles.error} role="alert">{error}{conflict ? <p>입력한 수정값은 유지됩니다. 메모해 둔 뒤 다시 조회해 주세요.</p> : null}</div> : null}
    {notice ? <p className={styles.notice} role="status">{notice}</p> : null}
    {!snapshot && !error ? <p role="status">DB 원천과 현재 등록값을 불러오는 중…</p> : null}
    {snapshot ? <>
      <details className={styles.connections}><summary>원천 검색·연결 <span>{snapshot.sources.length}개 연결</span></summary>
        <p className={styles.hint}>비교 근거만 연결합니다. 자동 정제의 단지 연결·통합 관계는 바뀌지 않습니다. 이름·주소·공급 범위를 직접 확인하세요.</p>
        <ul className={styles.sourceList}>{snapshot.sources.map((source, index) => <li key={`${source.category}:${source.sourceKey}:${index}`}>
          <div><strong>원천 {index + 1} · {categoryLabels[source.category]} · {source.data?.name ?? '현재 DB에 없는 원천'}</strong>
            <small>{source.automatic ? '자동 정제 연결' : `직접 연결 · ${source.actor ?? ''}`} · {source.data?.collectedAt ? new Date(source.data.collectedAt).toLocaleString('ko-KR') : '수집 시각 미확인'}</small>
            <p>{source.evidenceNote}</p>
            <Link to={`/admin/sources?${new URLSearchParams({ category: source.category, keyword: source.sourceKey })}`}>전체 원천 데이터 보기</Link>
            {source.data ? <details><summary>저장된 원천 항목</summary><StoredDataTable data={source.data.raw} label={`원천 ${index + 1} 저장값`} /></details> : null}
          </div>
          {!source.automatic ? <button type="button" disabled={busy || deleted || conflict || changed.length > 0 || !linkNote.trim()}
            onClick={() => void mutate('unlink', source)}>연결 해제</button> : null}
        </li>)}</ul>
        {!snapshot.sources.length ? <p className={styles.empty}>연결된 원천이 없습니다. 아래에서 DB 자료를 찾아 연결하거나 공식 자료를 보고 직접 수정할 수 있습니다.</p> : null}
        <form className={styles.search} onSubmit={event => { event.preventDefault(); void search() }}>
          <label>원천 분류<select value={category} onChange={event => {
            searchSession.current?.abort(); setSearching(false); setResults(null); setCategory(event.target.value as ComparisonCategory)
          }}><option value="MYHOME_COMPLEX">마이홈 단지</option><option value="LH_LEASE_CATALOG">LH 임대 단지</option></select></label>
          <label>단지명·원천 식별자<input value={keyword} maxLength={200} onChange={event => { searchSession.current?.abort(); setSearching(false); setResults(null); setKeyword(event.target.value) }} /></label>
          <button type="submit" disabled={searching || busy || deleted}>{searching ? '검색 중…' : '원천 검색'}</button>
        </form>
        <label>연결 근거<textarea maxLength={2000} rows={2} value={linkNote} disabled={busy || deleted || conflict}
          placeholder="같은 단지와 공급 범위인지 확인한 근거. 연결 해제 시에도 이유를 남겨 주세요." onChange={event => setLinkNote(event.target.value)} /></label>
        {changed.length ? <p className={styles.hint}>작성한 수정사항을 저장하거나 취소한 뒤 원천을 연결·해제해 주세요.</p> : null}
        {results ? <div aria-label="원천 검색 결과"><p>{results.totalElements}건 · {results.page + 1}페이지</p>
          {!results.items.length ? <p>검색된 원천이 없습니다. 다른 이름이나 식별자로 검색해 주세요.</p> : null}
          <ul className={styles.sourceList}>{results.items.map(row => <li key={row.id}><div><strong>{row.name}</strong>
            <small>{row.sourceKey} · {row.collectedAt ? new Date(row.collectedAt).toLocaleString('ko-KR') : '수집 시각 미확인'}</small>
            <details><summary>연결 전 원천 확인</summary><StoredDataTable data={row.raw} label={`${row.name} 연결 후보`} /></details></div>
            <button type="button" disabled={busy || deleted || conflict || changed.length > 0 || !linkNote.trim() || snapshot.sources.some(source => source.category === category && source.sourceKey === row.sourceKey)}
              onClick={() => void mutate('link', { category, sourceKey: row.sourceKey })}>이 원천 연결</button></li>)}</ul>
          <div className={styles.pagination}><button type="button" disabled={searching || results.page === 0} onClick={() => void search(results.page - 1)}>이전 원천</button>
            <button type="button" disabled={searching || !results.hasNext} onClick={() => void search(results.page + 1)}>다음 원천</button></div>
        </div> : null}
      </details>
      <div className={styles.filters}><button type="button" aria-pressed={!differentOnly} onClick={() => setDifferentOnly(false)}>모든 항목</button>
        <button type="button" aria-pressed={differentOnly} onClick={() => setDifferentOnly(true)}>값이 다른 항목 {differenceCount}</button>
        <span>수정 중 {changed.length}개</span></div>
      <form onSubmit={event => { event.preventDefault(); void mutate('save') }}>
        <div className={styles.table}><div className={styles.columns} aria-hidden="true"><span>항목</span><span>현재 등록값</span><span>DB 원천값</span><span>수정</span></div>
          {ordinaryFields.filter(field => !differentOnly || differs(field) || changed.some(item => item.name === field.name)).map(comparisonRow)}
          {differentOnly && !ordinaryDifferenceCount && !changed.length ? <p className={styles.empty}>{addressDifferenceCount
            ? `주소 원천값이 다른 항목 ${addressDifferenceCount}개가 있습니다. 아래 주소·위치 영역을 펼쳐 확인하세요.`
            : `원천 ${snapshot.sources.length}개와 비교 가능한 값이 모두 같습니다. 원천에 없는 항목은 모든 항목에서 확인하세요.`}</p> : null}</div>
        <details className={styles.address}><summary>주소·위치 함께 확인·수정{addressDifferenceCount ? <span className={styles.difference}> · 값 다름 {addressDifferenceCount}개</span> : null}</summary><p className={styles.hint}>도로명주소·PNU·지역 코드·좌표를 함께 확인합니다. 주소만 원천값으로 덮어쓰지 않습니다.</p>
          <AddressPicker onSelect={address => {
            setDraft(value => ({ ...value, address: { ...(isRecord(value.address) ? value.address : {}), ...address } }))
            setEditing(value => [...new Set([...value, ...addressFields.map(field => field.name)])]); setAddressConfirmed(false)
          }} />
          <div className={styles.table}>{addressFields.map(comparisonRow)}</div>
          {addressChanged ? <label className={styles.confirm}><input type="checkbox" checked={addressConfirmed} onChange={event => setAddressConfirmed(event.target.checked)} disabled={busy || deleted || conflict} />주소·PNU·지역 코드·좌표를 함께 확인했습니다.</label> : null}
        </details>
        {changed.length ? <div className={styles.preview}><h3>저장할 수정사항 {changed.length}개</h3><ul>{changed.map(field => <li key={field.name}><strong>{field.label}</strong> {comparisonText(valueAt(snapshot.current, field.name))} → {comparisonText(valueAt(draft, field.name))}</li>)}</ul></div> : null}
        <div className={styles.save}><label>수정 근거·메모<textarea rows={2} maxLength={2000} value={note} disabled={busy || deleted || conflict} onChange={event => setNote(event.target.value)} placeholder="확인한 자료와 값의 적용 범위, 수정 이유를 적어 주세요." /></label>
          <p className={styles.hint}>원천에 없는 값은 임의로 0으로 채우지 않습니다. 단지·주택형·모집 세대수는 범위가 다를 수 있습니다.</p>
          <div className={styles.saveActions}><button type="button" disabled={busy || deleted || conflict || changed.length > 0 || !note.trim() || !snapshot.sources.length || snapshot.sources.some(source => !source.data)} onClick={() => void recordSourceCheck()}>현재 원천 확인 기록</button><button type="button" disabled={busy || !dirty} onClick={() => {
            if (window.confirm('작성한 수정값과 근거를 취소할까요?')) { setDraft(structuredClone(snapshot.current)); setEditing([]); setNote(''); setLinkNote(''); setAddressConfirmed(false) }
          }}>수정 취소</button><Button data-design-system type="submit" disabled={busy || deleted || conflict || !changed.length || !note.trim() || addressChanged && !addressConfirmed}>{busy ? '저장 중…' : `수정사항 저장 · ${changed.length}개`}</Button></div>
        </div>
      </form>
    </> : null}
  </section>
}
