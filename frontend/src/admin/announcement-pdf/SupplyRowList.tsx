import { complexGroups, pendingJumpTarget, pendingSummary, rowPendingItems, rowStatus, type PendingItem, type Review } from './review'
import styles from './PdfCorrection.module.css'
import { groupCount, rowCountText, rowFacts, rowKind } from './supplyView'
import { AdminIcon } from '../shared/AdminIcon'

/**
 * 주택형 상태. 남은 확인이 있으면 무엇이 남았는지(단지 정하기·값·공급대상·함께 쓰는 수·주택형 가격) 함께 알리고,
 * 누르면 그 주택형의 첫 확인 항목으로 옮긴다.
 */
export function RowStatusBadge({ review, index, pending, onJump, detailed = false }: { review: Review; index: number
  pending: PendingItem[]; onJump?: (target: string) => void; detailed?: boolean }) {
  const items = rowPendingItems(pending, index), status = rowStatus(review, index, items.length > 0), summary = pendingSummary(items)
  if (!items.length || !onJump) return <span className={styles.statusBadge} data-tone={status.tone}>{status.label}</span>
  const target = pendingJumpTarget(items[0].id)
  return <button type="button" data-design-system className={styles.statusBadge} data-tone={status.tone} title={summary} aria-label={`주택형 ${index + 1} ${status.label}: ${summary} 확인하러 가기`}
    onClick={() => onJump(target)}>{detailed ? `${status.label} · ${summary}` : status.label}<AdminIcon name="chevronRight" /></button>
}

/** PDF 주택형을 단지별로 한 줄씩 요약한다. 줄을 고르면 아래 편집 영역이 그 주택형을 보인다. */
export function SupplyRowList({ review, pending, selected, visible, onSelect, onJump }: { review: Review; pending: PendingItem[]
  selected: number; visible: (index: number) => boolean; onSelect: (index: number) => void; onJump?: (target: string) => void }) {
  return <div className={styles.rowListScroll} role="region" aria-label="주택형 목록" tabIndex={0}><table className={styles.rowList}>
    <thead><tr><th scope="col">주택형</th><th scope="col">전용</th><th scope="col">모집수</th><th scope="col">기존/추가</th><th scope="col">상태</th></tr></thead>
    {complexGroups(review).map(group => {
      const indexes = group.indexes.filter(visible)
      if (!indexes.length) return null
      const sum = groupCount(review, group.indexes)
      return <tbody key={group.key}><tr><th colSpan={5} scope="rowgroup" className={styles.rowListGroup}>{group.name}<small>{` · 주택형 ${group.indexes.length}개 · 모집 합 ${sum ?? '미확인'}`}</small></th></tr>
        {indexes.map(index => {
          const { name, area, houseTypes } = rowFacts(review, index)
          return <tr key={index} aria-current={index === selected || undefined}>
            <th scope="row"><button type="button" className={styles.rowListButton} aria-pressed={index === selected} onClick={() => onSelect(index)}>{`${index + 1}. ${name}`}</button>
              {houseTypes.length > 0 && <small className={styles.rowHouseTypes}>{`주택타입 ${houseTypes.map(house => house.name).join(', ')}`}</small>}</th>
            <td>{area !== null ? `${area}㎡` : '미확인'}</td><td>{rowCountText(review, index)}</td><td>{rowKind(review, index)}</td>
            <td><RowStatusBadge review={review} index={index} pending={pending} onJump={onJump} /></td></tr>
        })}</tbody>
    })}
  </table></div>
}
