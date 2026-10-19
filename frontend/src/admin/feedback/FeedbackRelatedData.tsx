import { useEffect, useRef, useState } from 'react'
import { getManagementPage } from '../management/api'
import type { ManagementSummary } from '../management/managementContract'

export function FeedbackRelatedData({ resource, id, onSelect }: {
  resource: 'COMPLEX' | 'ANNOUNCEMENT'; id: number | null; onSelect: (id: number) => void
}) {
  const [keyword, setKeyword] = useState('')
  const [items, setItems] = useState<ManagementSummary[] | null>(null)
  const [selected, setSelected] = useState<ManagementSummary | null>(null)
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false)
  const request = useRef<AbortController | null>(null)
  const label = resource === 'COMPLEX' ? '단지' : '공고'
  useEffect(() => () => request.current?.abort(), [])
  async function search() {
    request.current?.abort(); const controller = new AbortController(); request.current = controller
    setBusy(true); setError('')
    try {
      const result = await getManagementPage(resource === 'COMPLEX' ? 'complexes' : 'announcements',
        new URLSearchParams({ keyword: keyword.trim(), size: '20' }), controller.signal)
      if (!controller.signal.aborted) setItems(result.items)
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '검색하지 못했습니다.') }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  return <div>
    <p>{id ? `선택한 ${label}: ${selected?.name ?? `#${id}`}` : `관련 ${label}를 검색해 선택하세요.`}</p>
    <div className="admin-inline"><label>{label} 이름으로 검색<input maxLength={200} value={keyword} onChange={event => setKeyword(event.target.value)}
      onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); if (!busy) void search() } }} /></label>
      <button type="button" disabled={busy} onClick={() => void search()}>{busy ? '검색 중…' : `${label} 검색`}</button></div>
    {error && <p role="alert">{error}</p>}
    {items?.length === 0 && <p role="status">일치하는 {label}가 없습니다.</p>}
    {items && <ul className="complex-picker-results">{items.map(item => <li key={item.id}><div><strong>{item.name}</strong><small>{item.subtitle}</small></div>
      <button type="button" onClick={() => { onSelect(item.id); setSelected(item); setItems(null) }}>선택: {item.name}</button></li>)}</ul>}
  </div>
}
