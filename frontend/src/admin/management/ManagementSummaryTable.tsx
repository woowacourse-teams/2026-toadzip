import { Link, useMatch } from 'react-router'
import type { ManagementResource, ManagementSummary } from './managementContract'
import { labels } from './fields'
import { ManagementStatus } from './ManagementStatus'
import styles from './ManagementList.module.css'

type Column = { key: string; label: string; width: number; read: (summary: ManagementSummary) => string | number | boolean | null | undefined; kind?: 'number' | 'status' }
export function ManagementSummaryTable({ items, resource, returnTo, fixedHeight = false, inlineSearch }: { items: ManagementSummary[]; resource: ManagementResource; returnTo?: string; fixedHeight?: boolean; inlineSearch?: string }) {
  const selected = useMatch(`/admin/${resource}/:id`)?.params.id
  if (!items.length) return <div className={`admin-empty ${fixedHeight ? styles.fixedHeight : ''}`}><h2>표시할 {resource === 'complexes' ? '단지' : '공고'}가 없습니다.</h2><p>검색 조건을 바꾸거나 새 데이터를 등록해 주세요.</p></div>
  const columns: Column[] = [
    ...(resource === 'complexes' ? [{ key: 'address.roadAddress', label: '도로명주소', width: 300, read: (item: ManagementSummary) => item.subtitle }] : []),
    { key: 'agencyCode', label: '공급 기관', width: 104, read: item => item.provider },
    { key: 'rentalType', label: '공급 유형', width: 144, read: item => item.rental },
    ...(resource === 'complexes' ? complexColumns : announcementColumns),
    { key: 'deleted·modified·reviewRequired', label: '관리 상태', width: 204, read: () => null, kind: 'status' },
  ]
  const title = resource === 'complexes' ? '단지' : '공고'
  return <div className={`${styles.scroll} ${fixedHeight ? styles.fixedHeight : ''}`} role="region" tabIndex={0} aria-label={`정제 ${title} 목록 가로 스크롤`}>
    <table className={styles.table} aria-label={`정제 ${title} 목록`}>
      <colgroup><col className={styles.nameColumn} />{columns.map(column => <col key={column.key} style={{ width: column.width }} />)}</colgroup>
      <thead><tr><th scope="col">{title}명</th>{columns.map(column => <th key={column.key} scope="col">{column.label}</th>)}</tr></thead>
      <tbody>{items.map(item => <tr key={item.id} data-selected={selected === String(item.id) || undefined}>
        <th scope="row"><Link aria-current={selected === String(item.id) ? 'page' : undefined} title={item.name} to={`/admin/${resource}/${item.id}${inlineSearch !== undefined ? inlineSearch ? `?${inlineSearch}` : '' : returnTo ? `?returnTo=${encodeURIComponent(returnTo)}` : ''}`}>{item.name}</Link></th>
        {columns.map(column => <td key={column.key} data-field={column.key} className={column.kind === 'number' ? styles.number : undefined}>
          {column.kind === 'status' ? <ManagementStatus summary={item} /> : <TextValue value={column.read(item)} />}
        </td>)}
      </tr>)}</tbody>
    </table>
  </div>
}
const complexColumns: Column[] = [
  { key: 'totalHouseholdCount', label: '세대수', width: 88, read: item => item.complex?.totalHouseholdCount, kind: 'number' },
]
const announcementColumns: Column[] = [
  { key: 'recruitmentType', label: '모집 유형', width: 110, read: item => item.announcement?.recruitmentType },
  { key: 'postedDate', label: '게시일', width: 110, read: item => item.announcement ? item.announcement.postedDate : item.subtitle },
  { key: 'applicationPeriod', label: '접수 기간', width: 205, read: item => item.announcement ? `${item.announcement.applicationStartDate} ~ ${item.announcement.applicationEndDate}` : null },
]
function TextValue({ value }: { value: string | number | boolean | null | undefined }) {
  const text = value === null || value === undefined || value === '' ? '미확인' : labels[String(value)] ?? String(value)
  return <span className={styles.value} title={text}>{text}</span>
}
