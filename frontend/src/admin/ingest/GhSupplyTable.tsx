import { isRecord } from '../management/managementContract'
import { storedDataValue } from '../shared/storedDataPresentation'
import { ghSupplyValueColumns } from './ghSupplyColumns'
import type { SourceRow } from './sourceApi'
import styles from './SourceDataPage.module.css'

type SupplyRow = { key: string; announcement: string; values: unknown[]; status: string }

/** 공고 한 건의 지구·주택형을 주택형당 한 행으로 펼친다. 주택형이 없는 공고도 한 행으로 남긴다. */
function ghSupplyRows(items: SourceRow[]): SupplyRow[] {
  return items.flatMap(item => {
    const districts = Array.isArray(item.raw.districts) ? item.raw.districts.filter(isRecord) : []
    const rows = districts.flatMap((district, districtIndex) => {
      const types = Array.isArray(district.housingTypes) ? district.housingTypes.filter(isRecord) : []
      return types.map((type, typeIndex): SupplyRow => {
        // 팝업을 받지 못한 주택형은 detail 키 자체가 없다. 빈칸과 구분해 드러낸다.
        const detail = isRecord(type.detail) ? type.detail : null
        const source = { district, housingType: type, detail: detail ?? {} }
        return { key: `${item.sourceKey}:${districtIndex}:${typeIndex}`, announcement: item.name,
          values: ghSupplyValueColumns.map(column => source[column.scope][column.key]),
          status: detail ? '수집됨' : '미수집' }
      })
    })
    return rows.length ? rows : [{ key: `${item.sourceKey}:none`, announcement: item.name,
      values: ghSupplyValueColumns.map(() => undefined), status: '공급정보 없음' }]
  })
}

export function GhSupplyTable({ items }: { items: SourceRow[] }) {
  const rows = ghSupplyRows(items)
  return <div className={styles.supply}>
    <h2>주택형별 공급 정보</h2>
    <p>현재 페이지 공고의 지구·주택형을 주택형당 한 행으로 펼칩니다. 일정·이미지·안내 등 공고 전체 정보는 위 공고 목록에서 확인합니다.</p>
    <div className={`admin-table-scroll ${styles.scroll}`} tabIndex={0} role="region" aria-label="주택형별 공급 정보 가로 스크롤">
      <table className={styles.table}>
        <caption className="sr-only">GH 주택형별 공급 정보</caption>
        <thead><tr><th scope="col">공고</th>
          {ghSupplyValueColumns.map(column => <th key={`${column.scope}.${column.key}`} scope="col">{column.label}</th>)}
          <th scope="col">상세 수집 상태</th></tr></thead>
        <tbody>{rows.map(row => <tr key={row.key} data-status={row.status}>
          <th scope="row" title={row.announcement}>{row.announcement}</th>
          {row.values.map((value, index) => {
            const content = storedDataValue(ghSupplyValueColumns[index].key, value)
            return <td key={index} title={typeof content === 'string' ? content : undefined}>{content}</td>
          })}
          <td>{row.status}</td>
        </tr>)}</tbody>
      </table>
    </div>
  </div>
}
