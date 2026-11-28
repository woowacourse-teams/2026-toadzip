import { useEffect, useRef, useState } from 'react'
import { getManagementPage } from '../management/api'
import { relatedResources } from './api'
import type { ManagementSummary } from '../management/managementContract'

export function FeedbackRelatedData({ resource, onSelect, onCancel }: {
  resource: 'COMPLEX' | 'ANNOUNCEMENT'; onSelect: (item: ManagementSummary) => void; onCancel: () => void
}) {
  const [keyword, setKeyword] = useState('')
  const [items, setItems] = useState<ManagementSummary[] | null>(null)
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false)
  const request = useRef<AbortController | null>(null)
  const label = relatedResources[resource]
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
    <div className="admin-inline"><label>{label} 이름으로 검색<input maxLength={200} value={keyword} onChange={event => setKeyword(event.target.value)}
      onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); if (!busy) void search() } }} /></label>
      <button type="button" disabled={busy} onClick={() => void search()}>{busy ? '검색 중…' : `${label} 검색`}</button>
      <button type="button" onClick={onCancel}>취소</button></div>
    {error && <p role="alert">{error}</p>}
    {items?.length === 0 && <p role="status">일치하는 {label}가 없습니다.</p>}
    {items && <ul className="complex-picker-results">{items.map(item => <li key={item.id}><div><strong>{item.name}</strong><small>{item.subtitle}</small></div>
      <button type="button" onClick={() => onSelect(item)}>선택: {item.name}</button></li>)}</ul>}
  </div>
}
