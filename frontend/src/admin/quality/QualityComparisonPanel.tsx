import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { reviewTarget, targetChoices, type ReviewField } from './reviewTargets'
import { getSourceData, sourceCategories, type SourceCategory, type SourcePage } from '../ingest/sourceApi'
import { ManagementError, requestManagementApi } from '../management/api'
import { display, labels, valueAt } from '../management/fields'
import { type ManagementDetailData, type ManagementResource, type ManagementValues } from '../management/managementContract'
import { writeComparisonValue } from '../management/complexComparisonValues'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import { loadQualityComparison, parseQualityComparison, qualityComparisonPath, type QualityComparison, type QualitySource } from './qualityComparisonApi'
import { qualitySourceValue, qualityComparisonStatus } from './qualityComparisonValues'
import styles from '../management/ComplexComparison.module.css'
import layout from './QualityComparisonPanel.module.css'

type Props = { resource: ManagementResource; id: string; detail: ManagementDetailData; onChanged: () => void; onBusyChange: (busy: boolean) => void; onDirtyChange?: (dirty: boolean) => void }
export function QualityComparisonPanel({ resource, id, detail, onChanged, onBusyChange, onDirtyChange }: Props) {
  const choices = targetChoices(resource, detail).filter(choice => resource !== 'complexes' || choice.key.startsWith('type:'))
  const [selection, setSelection] = useState(choices[0]?.key ?? '')
  const [params] = useSearchParams()
  const [snapshot, setSnapshot] = useState<QualityComparison | null>(null)
  const [draft, setDraft] = useState<ManagementValues>({})
  const [note, setNote] = useState('')
  const [linkNote, setLinkNote] = useState('')
  const [category, setCategory] = useState<SourceCategory>(resource === 'complexes' ? 'MYHOME_COMPLEX' : 'MYHOME_ANNOUNCEMENT')
  const [keyword, setKeyword] = useState('')
  const [results, setResults] = useState<SourcePage | null>(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(false)
  const [conflict, setConflict] = useState(false)
  const [filter, setFilter] = useState<'all' | 'different' | 'manual'>(resource === 'announcements' ? 'different' : 'all')
  const [attempt, setAttempt] = useState(0)
  const request = useRef<AbortController | null>(null)
  const searchRequest = useRef<AbortController | null>(null)
  const target = snapshot ? reviewTarget(resource, snapshot.detail, selection) : null
  const changed = target?.fields.filter(field => JSON.stringify(valueAt(target.current, field.name)) !== JSON.stringify(valueAt(draft, field.name))) ?? []
  const dirty = changed.length > 0 || Boolean(note.trim() || linkNote.trim())
  useUnsavedChanges(dirty)
  useEffect(() => { onDirtyChange?.(dirty); return () => onDirtyChange?.(false) }, [dirty, onDirtyChange])
  useEffect(() => { onBusyChange(busy || loading); return () => onBusyChange(false) }, [busy, loading, onBusyChange])
  useEffect(() => {
    if (!selection) return
    const controller = new AbortController(); request.current = controller
    setLoading(true); setError(''); setSnapshot(null)
    void loadQualityComparison(resource, id, selection, controller.signal).then(next => {
      if (!controller.signal.aborted) { setSnapshot(next); setDraft(structuredClone(reviewTarget(resource, next.detail, selection).current)); setConflict(false); setNote(''); setLinkNote('') }
    }).catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '비교 자료를 읽지 못했습니다.') })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => { controller.abort(); request.current?.abort(); searchRequest.current?.abort() }
  }, [resource, id, selection, detail.data.version, attempt])
  async function mutate(action: 'save' | 'link' | 'unlink' | 'check', source?: { category: SourceCategory; sourceKey: string }) {
    if (!snapshot || !target || busy) return
    const controller = new AbortController(); request.current = controller; setBusy(true); setError(''); setNotice('')
    const path = qualityComparisonPath(resource, id)
    const body = { target: selection, snapshotToken: snapshot.snapshotToken, evidenceNote: action === 'save' || action === 'check' ? note : linkNote,
      ...(source ?? {}), ...(action === 'save' ? { values: Object.fromEntries(changed.map(field => { const key = field.name.split('.')[0]; return [key, draft[key]] })) } : {}) }
    try {
      const next = parseQualityComparison(await requestManagementApi(path + (action === 'check' ? '/checks' : action === 'save' ? '' : '/sources'), action === 'save' ? 'PUT' : action === 'unlink' ? 'DELETE' : 'POST', body, controller.signal))
      if (controller.signal.aborted) return
      setSnapshot(next); setDraft(structuredClone(reviewTarget(resource, next.detail, selection).current)); setNote(''); setLinkNote(''); setResults(null)
      setNotice(action === 'save' ? '선택한 값과 비교 근거를 저장했습니다.' : action === 'check' ? '현재 원천 확인을 기록했습니다. 재수집으로 원천이 바뀌면 검토 대상에 다시 표시됩니다.' : '비교 원천 연결을 변경했습니다.')
      if (action === 'save') onChanged()
    } catch (cause) {
      if (controller.signal.aborted) return
      setError(cause instanceof Error ? cause.message : '저장하지 못했습니다.')
      if (cause instanceof ManagementError && cause.status === 409) setConflict(true)
    } finally { if (!controller.signal.aborted) setBusy(false) }
  }
  async function search(page = 0) {
    searchRequest.current?.abort(); const controller = new AbortController(); searchRequest.current = controller
    setResults(null); setError('')
    try { const next = await getSourceData(category, keyword, page, controller.signal); if (!controller.signal.aborted) setResults(next) }
    catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '원천을 검색하지 못했습니다.') }
  }
  // 공고 보정은 전용 편집 화면에서 처리하고, 이력에서는 수집값과 확인 기록만 다룬다.
  const readOnly = resource === 'announcements'
  const editSearch = new URLSearchParams(params); editSearch.delete('comparison'); editSearch.set('section', /^(row|target):/.test(selection) ? 'relations' : 'info')
  const fields = target?.fields.map(field => ({ field, status: qualityComparisonStatus(field, snapshot?.sources ?? [], valueAt(target.current, field.name)) })) ?? []
  const visibleFields = fields.filter(item => filter === 'all' || item.status === filter)
  if (!choices.length) return <p>비교할 주택형이 없습니다. 주택형을 등록하거나 수집·정제 결과를 확인하세요.</p>
  return <section className={`${styles.panel} ${layout.panel}`} aria-label={resource === 'complexes' ? '주택형 원천 비교' : '공고·공급정보 원천 비교'}>
    <header className={styles.heading}><div><h2>{resource === 'complexes' ? '주택형 원천 비교·수정' : '수집 원천과 비교'}</h2><p>현재 등록값과 수집한 자료를 대조합니다. 원천값이 같아도 공고문 전체를 확인했다는 뜻은 아닙니다.</p></div><button disabled={busy || loading} onClick={() => { if (!dirty || window.confirm('미저장 입력을 버리고 다시 조회할까요?')) setAttempt(value => value + 1) }}>비교 다시 조회</button></header>
    <label>비교 대상<select value={selection} disabled={busy || loading} onChange={event => { if (!dirty || window.confirm('작성한 내용을 버리고 대상을 바꿀까요?')) { setSelection(event.target.value); setResults(null) } }}>{choices.map(choice => <option key={choice.key} value={choice.key}>{choice.label}</option>)}</select></label>
    {readOnly ? <p className={styles.hint}>수집 자료와 다른 값을 먼저 표시합니다. 값은 <Link to={`?${editSearch}`}>{/^(row|target):/.test(selection) ? '공급정보·단지 연결 탭에서 수정' : '기본정보에서 수정'}</Link>하거나 상단의 PDF 보정을 이용하세요.</p> : null}
    {loading ? <p role="status">DB 원천을 불러오는 중…</p> : null}{error ? <p role="alert" className="registration-error">{error}</p> : null}{notice ? <p role="status" className="registration-success">{notice}</p> : null}
    {snapshot && target ? <fieldset disabled={busy || conflict || detail.summary.deleted} className="admin-verification-group">
      <details className={styles.connections}><summary>원천 검색·연결 · {snapshot.sources.length}개</summary>
        <p className={styles.hint}>공고 식별자 또는 단지에 연결된 원천입니다. 이름이 비슷해도 같은 주택형·공급대상인지 확인해야 합니다.</p>
        <label>연결·해제 이유<input value={linkNote} maxLength={2000} onChange={event => setLinkNote(event.target.value)} /></label>
        <ul className={styles.sourceList}>{snapshot.sources.map((source, index) => <li key={`${source.category}:${source.sourceKey}:${index}`}><div><strong>원천 {index + 1} · {sourceCategories[source.category]}</strong><p>{source.rows[0]?.name ?? '현재 원천 없음'}</p><small>{source.evidenceNote}</small><details><summary>원천 전체 확인</summary><pre>{JSON.stringify(source.rows, null, 2)}</pre></details></div>{!source.automatic ? <button disabled={changed.length > 0 || !linkNote.trim()} onClick={() => void mutate('unlink', source)}>연결 해제</button> : null}</li>)}</ul>
        <div className={styles.search}><label>원천 분류<select value={category} onChange={event => { searchRequest.current?.abort(); setCategory(event.target.value as SourceCategory); setResults(null) }}>{Object.entries(sourceCategories).filter(([key]) => resource === 'complexes' ? ['MYHOME_COMPLEX', 'LH_LEASE_CATALOG'].includes(key) : !['MYHOME_COMPLEX', 'LH_LEASE_CATALOG'].includes(key)).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label><label>원천 이름·식별자<input value={keyword} maxLength={200} onChange={event => { searchRequest.current?.abort(); setKeyword(event.target.value); setResults(null) }} /></label><button onClick={() => void search()}>원천 검색</button></div>
        {results ? <><p>검색 결과 {results.totalElements}건</p>{results.items.map(row => <article key={row.id}><strong>{row.name}</strong><details><summary>연결 전 원천 확인</summary><pre>{JSON.stringify(row.raw, null, 2)}</pre></details><button disabled={changed.length > 0 || !linkNote.trim() || snapshot.sources.some(source => source.category === category && source.sourceKey === row.sourceKey)} onClick={() => void mutate('link', { category, sourceKey: row.sourceKey })}>이 자료 연결</button></article>)}<div className={styles.pagination}><button disabled={results.page === 0} onClick={() => void search(results.page - 1)}>이전 원천</button><button disabled={!results.hasNext} onClick={() => void search(results.page + 1)}>다음 원천</button></div></> : null}
      </details>
      <div className={styles.filters} aria-label="비교 항목 필터">
        <button aria-pressed={filter === 'all'} onClick={() => setFilter('all')}>모든 항목 · {fields.length}</button>
        <button aria-pressed={filter === 'different'} onClick={() => setFilter('different')}>다른 값만 · {fields.filter(item => item.status === 'different').length}</button>
        <button aria-pressed={filter === 'manual'} onClick={() => setFilter('manual')}>직접 대조 · {fields.filter(item => item.status === 'manual').length}</button>
        {!readOnly && <span>수정 중 {changed.length}개</span>}
      </div>
      <div className={styles.table} data-read-only={readOnly || undefined}><div className={styles.columns}><span>항목·단위</span><span>현재 등록값</span><span>수집 원천값</span>{!readOnly && <span>수정할 값</span>}</div>
        {visibleFields.map(({ field, status }) => <article key={field.name} className={styles.row} aria-label={`${field.label} 비교`}>
          <div className={styles.field}><strong>{field.label}</strong><small>{field.unit !== '없음' ? `${field.unit} · ` : ''}{field.scope}</small><span className={layout.status} data-status={status}>{statusLabels[status]}</span></div>
          <div className={styles.current}><small className={styles.mobileLabel}>현재 등록값</small>{display(valueAt(target.current, field.name))}</div>
          <div className={styles.source}><small className={styles.mobileLabel}>수집 원천값</small><SourceValues field={field} sources={snapshot.sources} readOnly={readOnly}
            onUse={value => setDraft(current => writeComparisonValue(current, field, String(value)))} /></div>
          {!readOnly && <div className={styles.edit}><QualityFieldInput field={field} draft={draft} onChange={value => setDraft(current => writeComparisonValue(current, field, value === null ? '' : String(value)))} /></div>}
        </article>)}
        {!visibleFields.length ? <p className={layout.empty}>{filter === 'different' ? '자동으로 비교할 수 있는 항목 중 다른 값이 없습니다. 비교 불가·직접 대조 항목은 별도로 확인하세요.' : '직접 대조할 원천값이 없습니다.'}</p> : null}
      </div>
      {changed.length ? <section className={styles.preview}><h3>저장할 변경 {changed.length}개</h3><ul>{changed.map(field => <li key={field.name}>{field.label}: {display(valueAt(target.current, field.name))} → {display(valueAt(draft, field.name))}</li>)}</ul></section> : null}
      <label>{readOnly ? '원천 확인 근거' : '수정·원천 확인 근거'}<textarea value={note} rows={3} maxLength={2000} onChange={event => setNote(event.target.value)} /></label>
      <p className={styles.hint}>{!readOnly && '금액은 원천의 단위·최소/전체 금액·공급대상을 확인한 뒤 원 단위로 직접 입력하세요. '}현재 원천 확인 기록은 다음 수집의 변경을 찾는 기준입니다. 값 수정이나 공고 전체의 검토 완료를 의미하지 않습니다.</p>
      <div className={styles.saveActions}>{!readOnly && <Button disabled={!changed.length || !note.trim()} onClick={() => void mutate('save')}>선택 수정 저장 · {changed.length}개</Button>}<button disabled={changed.length > 0 || !note.trim() || !snapshot.sources.length} onClick={() => void mutate('check')}>현재 원천 확인 기록</button><button disabled={!dirty} onClick={() => { if (window.confirm('작성한 변경과 메모를 취소할까요?')) { setDraft(structuredClone(target.current)); setNote(''); setLinkNote('') } }}>{readOnly ? '입력 취소' : '수정 취소'}</button></div>
    </fieldset> : null}
  </section>
}

function QualityFieldInput({ field, draft, onChange }: { field: ReviewField; draft: ManagementValues; onChange: (value: string | number | boolean | null) => void }) {
  const value = String(valueAt(draft, field.name) ?? '')
  return <label>{field.label} 수정
    {field.options || field.boolean ? <select value={value} onChange={event => onChange(event.target.value === '' ? null : field.boolean ? event.target.value === 'true' : event.target.value)}>
      <option value="">미확인</option>{(field.options ?? ['true', 'false']).map(option => <option key={option} value={option}>{labels[option] ?? option}</option>)}
    </select> : <input type={field.money ? 'text' : field.type === 'number' ? 'number' : field.type === 'date' ? 'date' : field.type === 'month' ? 'month' : 'text'}
      inputMode={field.money ? 'numeric' : undefined} min={field.min} step={field.step ?? (field.name === 'exclusiveArea' ? '0.0001' : '1')}
      maxLength={field.money ? 18 : 255} value={value}
      onChange={event => onChange(event.target.value === '' ? null : field.type === 'number' ? Number(event.target.value) : event.target.value)} />}
  </label>
}

const statusLabels = { different: '값 다름', same: '비교값 같음', manual: '직접 대조', unavailable: '비교 불가' }

function SourceValues({ field, sources, readOnly, onUse }: { field: ReviewField; sources: QualitySource[]; readOnly: boolean; onUse: (value: string | number) => void }) {
  const rows = sources.flatMap((source, index) => source.rows.map(row => ({
    key: `${source.category}:${source.sourceKey}:${row.id}`, label: `원천 ${index + 1} · ${sourceCategories[source.category]}`,
    ...qualitySourceValue(field, source, row.raw),
  })))
  const available = rows.filter(row => row.availability === 'present')
  const unsupported = rows.filter(row => row.availability === 'unsupported').length
  const missing = rows.filter(row => row.availability === 'missing').length
  const lost = sources.filter(source => !source.rows.length).length
  return <>
    {available.map(row => <div key={row.key}><small>{row.label}</small><p>{row.raw}</p>{row.value !== undefined && !readOnly ? <button onClick={() => onUse(row.value!)}>이 원천값 사용</button> : null}</div>)}
    {!available.length && <p className={layout.noValue}>{sources.length ? '비교 가능한 원천값이 없습니다.' : '연결된 원천이 없습니다.'}</p>}
    {unsupported + missing + lost > 0 ? <details className={layout.unavailable}><summary>비교하지 못한 자료 {unsupported + missing + lost}개</summary>
      {unsupported > 0 ? <p>자동 비교 미지원 {unsupported}개 · 원천 전체 확인에서 직접 대조하세요.</p> : null}
      {missing > 0 ? <p>원천에 값 없음 {missing}개 · 등록값을 그대로 유지합니다.</p> : null}
      {lost > 0 ? <p>현재 원천 없음 {lost}개 · 재수집 또는 연결을 확인하세요.</p> : null}
    </details> : null}
  </>
}
