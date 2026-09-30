import { useState } from 'react'
import { list, type Summary } from './api'
export function ComplexPicker({ onSelect, disabled = false }: { onSelect: (item: Summary) => void; disabled?: boolean }) {
  const [query, setQuery] = useState('')
  const [items, setItems] = useState<Summary[] | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  async function search() {
    setBusy(true); setError(''); setItems(null)
    try { setItems((await list('complexes', new URLSearchParams({keyword:query, size:'20'}))).items) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '단지를 검색하지 못했습니다.') }
    finally { setBusy(false) }
  }
  return <div className="complex-picker"><div className="admin-inline"><label>단지명·주소 검색<input value={query} disabled={disabled || busy} onChange={event => setQuery(event.target.value)}
    onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); if (!busy && !disabled) void search() } }} /></label>
    <button type="button" disabled={disabled || busy} onClick={() => void search()}>{busy ? '검색 중…' : '단지 검색'}</button></div>
    {error ? <p role="alert">{error}</p> : null}{items?.length === 0 ? <p role="status">일치하는 단지가 없습니다.</p> : null}
    {items ? <ul className="complex-picker-results">{items.map(item => <li key={item.id}><div><strong>{item.name}</strong><small>{item.subtitle}</small></div>
      <button type="button" disabled={disabled} onClick={() => { onSelect(item); setItems(null) }}>선택<span className="sr-only">: {item.name}</span></button></li>)}</ul> : null}
  </div>
}
