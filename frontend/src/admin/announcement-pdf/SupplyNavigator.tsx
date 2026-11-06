import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import { placementShort } from './correspondence'
import type { SupplyView } from './supplyView'
import { assembledCount, rowOfReviewId, type PendingItem, type PlacementProblem, type Review } from './review'
import { RowStatusBadge } from './SupplyRowList'
import styles from './PdfCorrection.module.css'

/**
 * 검토 영역 위에 고정되는 공급행 이동 막대. 단지를 고르고, 지금 편집 중인 공급행이 무엇인지(형명·전용·모집수·저장할 곳·상태)를
 * 아래로 내려도 계속 보이며, 이전·다음으로 단지 순서대로 행을 옮긴다.
 */
export function SupplyNavigator({ review, workspace, view, pending, problems, onSelectComplex, onSelectRow, onJump }: { review: Review; workspace: JsonWorkspace; view: SupplyView
  pending: PendingItem[]; problems: Map<number, PlacementProblem>; onSelectComplex: (row: number) => void; onSelectRow: (row: number) => void; onJump: (target: string) => void }) {
  const { groups, groupIndex, group, shown, current, order } = view
  if (!group) return null
  const pendingCount = (indexes: number[]) => pending.filter(item => item.section === 'supply' && indexes.includes(rowOfReviewId(item.id) ?? -1)).length
  // 필터에 맞는 행이 이 단지에 없어도(마지막 확인 행을 해결한 직후 등) 앞뒤 단지의 행으로는 계속 옮길 수 있게 한다.
  const groupAt = (index: number) => groups.findIndex(item => item.indexes.includes(index)), at = order.indexOf(current)
  const previous = at >= 0 ? order[at - 1] ?? null : [...order].reverse().find(index => groupAt(index) < groupIndex) ?? null
  const next = at >= 0 ? order[at + 1] ?? null : order.find(index => groupAt(index) > groupIndex) ?? null
  const stepper = (label: string, target: number | null, text: string) => <Button key={label} data-design-system className={styles.stepButton} aria-label={label} disabled={target === null} onClick={() => onSelectRow(target!)}>{text}</Button>
  const source = current >= 0 ? review.extraction.supplyRows[current] : null
  const row = current >= 0 ? review.rows[current] : null
  const area = source?.values.exclusiveArea, count = row ? assembledCount(row) : null
  return <div className={styles.supplyNav} role="group" aria-label="공급행 이동">
    {groups.length > 1 ? <label className={styles.complexSelect}><span>단지</span>
      <select aria-label="단지" value={groupIndex} onChange={event => { const target = groups[Number(event.target.value)]; onSelectComplex(target.indexes.find(index => order.includes(index)) ?? target.indexes[0]) }}>
        {groups.map((item, index) => { const left = pendingCount(item.indexes); return <option key={item.name} value={index}>{`${item.name} · ${item.indexes.length}행${left ? ` · 확인 ${left}` : ''}`}</option> })}
      </select></label>
      : <p className={styles.complexSelect}><span>단지</span><strong>{group.name}</strong></p>}
    {/* 이전·다음은 행이 있든 없든 같은 자리에 둬 눌러도 포커스가 버튼에 남는다. 형명·사실·상태는 한 칸에 줄로 쌓아 좁아도 낱말이 쪼개지지 않는다. */}
    <div className={styles.currentRow}>
      {stepper('이전 공급행', previous, '‹')}
      {source && row ? <div className={styles.currentRowText}>
        <p aria-live="polite"><strong>{`${current + 1}. ${source.typeName ?? '주택형 미확인'}`}</strong>
          <span>{[typeof area === 'number' ? `전용 ${area}㎡` : '전용 미확인', count === null ? '모집 미확인' : `모집 ${count}세대`, placementShort(review, current, workspace)].join(' · ')}</span></p>
        <div className={styles.currentRowStatus}><RowStatusBadge review={review} workspace={workspace} index={current} problem={problems.get(current)} pending={pending} onJump={onJump} detailed />
          <span className={styles.rowPosition} aria-label={`이 단지 ${shown.length}행 중 ${shown.indexOf(current) + 1}번째`}>{`${shown.indexOf(current) + 1}/${shown.length}`}</span></div>
      </div> : <p className={`${styles.currentRowText} ${styles.hint}`}>{`이 단지에는 지금 필터에 맞는 공급행이 없습니다.${order.length ? ` 다른 단지에 ${order.length}행 남음` : ''}`}</p>}
      {stepper('다음 공급행', next, '›')}
    </div>
  </div>
}
