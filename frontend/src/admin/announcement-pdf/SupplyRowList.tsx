import type { JsonWorkspace } from '../announcement-json/api'
import { placementShort } from './correspondence'
import { complexGroups, assembledCount, rowStatus, type PlacementProblem, type Review } from './review'
import styles from './PdfCorrection.module.css'

/** 추출 공급행을 단지별로 한 줄씩 요약한다. 줄을 고르면 아래 편집 영역이 그 행을 보인다. */
export function SupplyRowList({ review, workspace, problems, pending, selected, visible, onSelect }: { review: Review; workspace: JsonWorkspace; problems: Map<number, PlacementProblem>; pending: Set<number>
  selected: number; visible: (index: number) => boolean; onSelect: (index: number) => void }) {
  return <div className={styles.rowListScroll} role="region" aria-label="공급행 목록" tabIndex={0}><table className={styles.rowList}>
    <thead><tr><th scope="col">공급행</th><th scope="col">전용</th><th scope="col">모집수</th><th scope="col">저장할 곳</th><th scope="col">상태</th></tr></thead>
    {complexGroups(review).map(group => {
      const indexes = group.indexes.filter(visible)
      if (!indexes.length) return null
      const counts = group.indexes.filter(index => review.rows[index].include).map(index => assembledCount(review.rows[index]))
      const sum = counts.every(count => count !== null) ? (counts as number[]).reduce((total, count) => total + count, 0) : null
      return <tbody key={group.name}><tr><th colSpan={5} scope="rowgroup" className={styles.rowListGroup}>{group.name}<small>{` · 주택형 ${group.indexes.length}개 · 모집 합 ${sum ?? '미확인'}`}</small></th></tr>
        {indexes.map(index => {
          const row = review.rows[index], source = review.extraction.supplyRows[index]
          const status = rowStatus(row, problems.get(index), pending.has(index)), area = source.values.exclusiveArea
          return <tr key={index} aria-current={index === selected || undefined} data-excluded={!row.include || undefined}>
            <th scope="row"><button type="button" className={styles.rowListButton} aria-label={`공급행 ${index + 1} 편집`} aria-pressed={index === selected} onClick={() => onSelect(index)}>{`${index + 1}. ${source.typeName ?? '주택형 미확인'}`}</button></th>
            <td>{typeof area === 'number' ? `${area}㎡` : '미확인'}</td><td>{assembledCount(row) ?? '미확인'}</td><td>{placementShort(review, index, workspace)}</td>
            <td><span className={styles.statusBadge} data-tone={status.tone}>{status.label}</span></td></tr>
        })}</tbody>
    })}
  </table></div>
}
