import type { JsonWorkspace } from '../announcement-json/api'
import { pendingJumpTarget, placementShort } from './correspondence'
import { complexGroups, assembledCount, pendingSummary, rowPendingItems, rowStatus, type PendingItem, type PlacementProblem, type Review } from './review'
import styles from './PdfCorrection.module.css'

/**
 * 공급행 상태. 남은 확인이 있으면 무엇이 남았는지(저장할 곳·값·세부 타입·임대조건·주택형 가격) 함께 알리고,
 * 누르면 그 행의 첫 확인 항목으로 옮긴다. 확인 항목이 다른 구역에 있어도 찾아가지 않아도 된다.
 */
export function RowStatusBadge({ review, workspace, index, problem, pending, onJump, detailed = false }: { review: Review; workspace: JsonWorkspace; index: number; problem?: PlacementProblem
  pending: PendingItem[]; onJump?: (target: string) => void; detailed?: boolean }) {
  const items = rowPendingItems(pending, index), status = rowStatus(review.rows[index], problem, items.length > 0), summary = pendingSummary(items)
  // 저장할 곳 확인은 그 확인을 푸는 곳(행·단지 정하기·LH·PDF 대응)으로 옮긴다.
  const target = items[0] ? pendingJumpTarget(review, workspace, items[0].id) : undefined
  // 이름은 실제로 옮겨 가는 곳을 말한다.
  const label = target === 'complex' ? summary.replace('저장할 곳', '단지 정하기') : target?.startsWith('match:') ? summary.replace('저장할 곳', 'LH·PDF 대응') : summary
  if (!items.length || !onJump) return <span className={styles.statusBadge} data-tone={status.tone}>{status.label}</span>
  return <button type="button" data-design-system className={styles.statusBadge} data-tone={status.tone} title={label} aria-label={`공급행 ${index + 1} ${status.label}: ${label} 확인하러 가기`}
    onClick={() => onJump(target!)}>{detailed ? `${status.label} · ${label}` : status.label}</button>
}

/** 추출 공급행을 단지별로 한 줄씩 요약한다. 줄을 고르면 아래 편집 영역이 그 행을 보인다. */
export function SupplyRowList({ review, workspace, problems, pending, selected, visible, onSelect, onJump }: { review: Review; workspace: JsonWorkspace; problems: Map<number, PlacementProblem>; pending: PendingItem[]
  selected: number; visible: (index: number) => boolean; onSelect: (index: number) => void; onJump?: (target: string) => void }) {
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
          const area = source.values.exclusiveArea
          return <tr key={index} aria-current={index === selected || undefined} data-excluded={!row.include || undefined}>
            <th scope="row"><button type="button" className={styles.rowListButton} aria-pressed={index === selected} onClick={() => onSelect(index)}>{`${index + 1}. ${source.typeName ?? '주택형 미확인'}`}</button></th>
            <td>{typeof area === 'number' ? `${area}㎡` : '미확인'}</td><td>{assembledCount(row) ?? '미확인'}</td><td>{placementShort(review, index, workspace)}</td>
            <td><RowStatusBadge review={review} workspace={workspace} index={index} problem={problems.get(index)} pending={pending} onJump={onJump} /></td></tr>
        })}</tbody>
    })}
  </table></div>
}
