import { Link } from 'react-router'
import type { ManagementResource, ManagementSummary } from './managementContract'
import { labels } from './fields'
import { ManagementStatus } from './ManagementStatus'
import { SourceUrl } from '../shared/SourceUrl'
import styles from './ManagementList.module.css'

type Column = { key: string; label: string; width: number; read: (summary: ManagementSummary) => string | number | boolean | null | undefined; kind?: 'number' | 'url' | 'status' }
export function ManagementSummaryTable({ items, resource, returnTo, fixedHeight = false }: { items: ManagementSummary[]; resource: ManagementResource; returnTo?: string; fixedHeight?: boolean }) {
  if (!items.length) return <div className={`admin-empty ${fixedHeight ? styles.fixedHeight : ''}`}><h2>표시할 {resource === 'complexes' ? '단지' : '공고'}가 없습니다.</h2><p>검색 조건을 바꾸거나 새 데이터를 등록해 주세요.</p></div>
  const columns: Column[] = [
    ...(resource === 'complexes' ? [{ key: 'address.roadAddress', label: '도로명주소', width: 300, read: (item: ManagementSummary) => item.subtitle }] : []),
    { key: 'agencyCode', label: '공급 기관', width: 104, read: item => item.provider },
    { key: 'rentalType', label: '공급 유형', width: 144, read: item => item.rental },
    ...(resource === 'complexes' ? complexColumns : announcementColumns),
    { key: 'deleted·modified·reviewRequired', label: '관리 상태', width: 204, read: () => null, kind: 'status' },
    { key: 'updatedAt', label: '관리자 최종 변경', width: 260, read: item => adminChangeTime(item.updatedAt) },
    { key: 'sourceIdentifier', label: '원천 식별자', width: 220, read: item => resource === 'complexes' ? item.complex?.sourceIdentifier : item.announcement?.sourceIdentifier },
    ...(resource === 'announcements' ? [{ key: 'originalUrl', label: '공식 원문 URL', width: 360, read: (item: ManagementSummary) => item.announcement?.originalUrl, kind: 'url' as const }] : []),
  ]
  const title = resource === 'complexes' ? '단지' : '공고'
  return <div className={`${styles.scroll} ${fixedHeight ? styles.fixedHeight : ''}`} role="region" tabIndex={0} aria-label={`정제 ${title} 목록 가로 스크롤`}>
    <table className={styles.table} aria-label={`정제 ${title} 목록`} style={{ width: `calc(var(--name-width) + ${columns.reduce((width, column) => width + column.width, 0)}px)` }}>
      <colgroup><col className={styles.nameColumn} />{columns.map(column => <col key={column.key} style={{ width: column.width }} />)}</colgroup>
      <thead><tr><th scope="col">{title}명 <span>(name)</span></th>{columns.map(column => <th key={column.key} scope="col">{column.label} <span>({column.key})</span></th>)}</tr></thead>
      <tbody>{items.map(item => <tr key={item.id}>
        <th scope="row"><Link title={item.name} to={`/admin/${resource}/${item.id}${returnTo ? `?returnTo=${encodeURIComponent(returnTo)}` : ''}`}>{item.name}</Link></th>
        {columns.map(column => <td key={column.key} data-field={column.key} className={column.kind === 'number' ? styles.number : undefined}>
          {column.kind === 'status' ? <ManagementStatus summary={item} /> : column.kind === 'url' ? <SourceUrl url={column.read(item)} /> : <TextValue value={column.read(item)} />}
        </td>)}
      </tr>)}</tbody>
    </table>
  </div>
}
const complexColumns: Column[] = [
  { key: 'totalHouseholdCount', label: '전체 세대수', width: 160, read: item => item.complex?.totalHouseholdCount, kind: 'number' },
  { key: 'totalParkingCount', label: '주차대수', width: 160, read: item => item.complex?.totalParkingCount, kind: 'number' },
  { key: 'completionDate', label: '준공일', width: 156, read: item => item.complex?.completionDate },
  { key: 'heatingType', label: '난방 유형', width: 132, read: item => item.complex?.heatingType },
  { key: 'buildingType', label: '건물 유형', width: 132, read: item => item.complex?.buildingType },
  { key: 'corridorType', label: '복도 유형', width: 132, read: item => item.complex?.corridorType },
  { key: 'hasElevator', label: '엘리베이터', width: 132, read: item => item.complex?.hasElevator },
  { key: 'moveOutCountLastYear', label: '최근 1년 퇴거자 수', width: 176, read: item => item.complex?.moveOutCountLastYear, kind: 'number' },
]
const announcementColumns: Column[] = [
  { key: 'recruitmentType', label: '모집 유형', width: 160, read: item => item.announcement?.recruitmentType },
  { key: 'postedDate', label: '게시일', width: 156, read: item => item.announcement ? item.announcement.postedDate : item.subtitle },
  { key: 'applicationStartDate', label: '접수 시작일', width: 176, read: item => item.announcement?.applicationStartDate },
  { key: 'applicationEndDate', label: '접수 종료일', width: 176, read: item => item.announcement?.applicationEndDate },
  { key: 'winnerAnnouncementDate', label: '당첨자 발표일', width: 188, read: item => item.announcement?.winnerAnnouncementDate },
]
function TextValue({ value }: { value: string | number | boolean | null | undefined }) {
  const text = value === null || value === undefined || value === '' ? '미확인' : labels[String(value)] ?? String(value)
  return <span className={styles.value} title={text}>{text}</span>
}
function adminChangeTime(value: string | null) {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', hour12: false })
}
