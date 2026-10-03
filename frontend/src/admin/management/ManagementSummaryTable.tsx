import { Link } from 'react-router'
import type { ManagementResource, ManagementSummary } from './managementContract'
import { labels } from './fields'
import { ManagementStatus } from './ManagementStatus'

export function ManagementSummaryTable({ items, resource, returnTo }: {
  items: ManagementSummary[]
  resource: ManagementResource
  returnTo?: string
}) {
  if (!items.length) {
    return (
      <div className="admin-empty">
        <h2>표시할 {resource === 'complexes' ? '단지' : '공고'}가 없습니다.</h2>
        <p>검색 조건을 바꾸거나 새 데이터를 등록해 주세요.</p>
      </div>
    )
  }

  return (
    <div className="admin-table-scroll">
      <table className="admin-table">
        <thead>
          <tr><th>이름</th><th>{resource === 'complexes' ? '주소' : '게시일'}</th><th>기관·유형</th><th>데이터 상태</th></tr>
        </thead>
        <tbody>
          {items.map(item => (
            <tr key={item.id}>
              <td>
                <Link to={`/admin/${resource}/${item.id}${returnTo ? `?returnTo=${encodeURIComponent(returnTo)}` : ''}`}>
                  {item.name}
                </Link>
              </td>
              <td>{item.subtitle}</td>
              <td>{item.provider} · {labels[item.rental] ?? item.rental}</td>
              <td><ManagementStatus summary={item} /></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
