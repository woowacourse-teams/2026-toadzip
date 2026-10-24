import { useEffect, useState } from 'react'
import { getManagementPage } from './api'
import { type ManagementSummary } from './managementContract'
/** initialQuery를 주면 처음 열 때 그 이름으로 바로 검색해 후보를 보여 준다. */
export function ComplexPicker({ onSelect, disabled = false, initialQuery = '' }: { onSelect: (item: ManagementSummary) => void; disabled?: boolean; initialQuery?: string }) {
  const [query, setQuery] = useState(initialQuery)
  const [items, setItems] = useState<ManagementSummary[] | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  async function search() {
    setBusy(true); setError(''); setItems(null)
    try { setItems((await getManagementPage('complexes', new URLSearchParams({keyword:query, size:'20'}))).items) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '단지를 검색하지 못했습니다.') }
    finally { setBusy(false) }
  }
  useEffect(() => {
    if (!initialQuery.trim()) return
    const controller = new AbortController()
    setBusy(true)
    getManagementPage('complexes', new URLSearchParams({ keyword: initialQuery, size: '20' }), controller.signal).then(page => setItems(page.items), cause => {
      if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '단지를 검색하지 못했습니다.')
    }).finally(() => { if (!controller.signal.aborted) setBusy(false) })
    return () => controller.abort()
  }, [initialQuery])
  return <div className="complex-picker"><div className="admin-inline"><label>단지명·주소 검색<input value={query} disabled={disabled || busy} onChange={event => setQuery(event.target.value)}
    onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); if (!busy && !disabled) void search() } }} /></label>
    <button type="button" disabled={disabled || busy} onClick={() => void search()}>{busy ? '검색 중…' : '단지 검색'}</button></div>
    {error ? <p role="alert">{error}</p> : null}{items?.length === 0 ? <p role="status">일치하는 단지가 없습니다.</p> : null}
    {items ? <ul className="complex-picker-results">{items.map(item => <li key={item.id}><div><strong>{item.name}</strong><small>{item.subtitle}</small></div>
      <button type="button" disabled={disabled} onClick={() => { onSelect(item); setItems(null) }}>선택<span className="sr-only">: {item.name}</span></button></li>)}</ul> : null}
  </div>
}
