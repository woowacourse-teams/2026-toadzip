import { useState } from 'react'
import { cellsOf, leaseOf, sharedQuotas, type QuotaKind, type SupplySheet } from './supplySheet'
import { addCell, addSharedQuota, cellName, cellValues, countInput, leaseFields, removeCells, removeQuota, setCellTarget, setLeaseAmount, setMembersCount, setQuotaCount, type LeaseField } from './sheetEdits'
import styles from './SupplyCorrection.module.css'

const kindLabel = { WINNER: '모집', RESERVE: '예비' } as const
const leaseLabels: Record<LeaseField, string> = { rentalDeposit: '보증금 (원)', monthlyRent: '월 임대료 (원)', convertedDeposit: '전환 보증금 (원)' }
const moneyInput = (text: string) => text.replace(/\D/g, '') || null

/** 주택형의 공급대상 칸 표. 칸마다 공급대상 이름·신청조건, 그 대상만의 모집·예비 수와 임대조건을 고친다. */
export function SupplyTargetDraft({ sheet, typeKey, onChange }: { sheet: SupplySheet; typeKey: string; onChange: (sheet: SupplySheet) => void }) {
  const cells = cellsOf(sheet, typeKey)
  return <fieldset><legend>공급대상별 수량·임대조건</legend>
    {cells.length ? <div className={styles.tableScroll}><table aria-label="공급대상 표"><thead><tr><th scope="col">공급대상</th><th scope="col">신청조건</th>
      <th scope="col">모집 (세대)</th><th scope="col">예비 (명)</th>{leaseFields.map(field => <th key={field} scope="col">{leaseLabels[field]}</th>)}<th scope="col">지우기</th></tr></thead><tbody>
      {cells.map((cell, index) => {
        const value = cellValues(sheet, cell), lease = leaseOf(sheet, cell.key), name = `공급대상 ${index + 1}`
        return <tr key={cell.key}>
          <td><input aria-label={`${name} 이름`} required maxLength={255} value={value.label} onChange={event => onChange(setCellTarget(sheet, cell.key, event.target.value, value.applicationCondition))} /></td>
          <td><input aria-label={`${name} 신청조건`} maxLength={255} value={value.applicationCondition} onChange={event => onChange(setCellTarget(sheet, cell.key, value.label, event.target.value))} /></td>
          {(['WINNER', 'RESERVE'] as const).map(kind => <td key={kind}><input aria-label={`${name} ${kindLabel[kind]} 수`} type="number" min="0" step="1"
            value={(kind === 'WINNER' ? value.winner : value.reserve) ?? ''} onChange={event => onChange(setMembersCount(sheet, [cell.key], kind, countInput(event.target.value)))} /></td>)}
          {leaseFields.map(field => <td key={field}><input aria-label={`${name} ${leaseLabels[field]}`} inputMode="numeric" maxLength={18} value={value[field] ?? ''}
            onChange={event => onChange(setLeaseAmount(sheet, cell.key, field, moneyInput(event.target.value)))} /></td>)}
          <td><button type="button" aria-label={`${name} 지우기`} onClick={() => onChange(removeCells(sheet, [cell.key]))}>지우기</button>
            {lease && lease.members.length > 1 && <small className={styles.hint}>임대조건을 함께 쓰는 칸: {lease.members.filter(member => member !== cell.key).map(member => cellName(sheet, member)).join(', ')}. 고치면 함께 바뀝니다.</small>}</td>
        </tr>
      })}
    </tbody></table></div> : <p>공급대상이 없습니다. 공급대상을 추가해 모집 수와 임대조건을 적으세요.</p>}
    <p className={styles.hint}>빈 수량·금액은 미확인, 숫자 0은 확인된 0입니다. 공급대상을 지우면 그 대상이 든 함께 쓰는 수에서도 빠집니다.</p>
    <div className={styles.actions}><button type="button" onClick={() => onChange(addCell(sheet, typeKey))}>공급대상 추가</button></div>
  </fieldset>
}

/**
 * 공급대상 칸 여럿이 함께 쓰는 수. 공고문에서 여러 대상·주택형이 한 칸으로 합쳐 적힌 수라 대상마다 나누지 않는다("49A·49B·49C 함께 모집 70").
 * 주택형의 모든 공급대상이 함께 쓰는 모집 수는 주택형 모집수로 따로 고친다.
 */
export function SharedCountsDraft({ sheet, typeKey, onChange }: { sheet: SupplySheet; typeKey: string; onChange: (sheet: SupplySheet) => void }) {
  const [adding, setAdding] = useState<{ kind: QuotaKind; members: string[]; count: string } | null>(null)
  const [error, setError] = useState('')
  const shared = sharedQuotas(sheet, typeKey)
  const types = [...sheet.housingTypes].sort((a, b) => a.displayOrder - b.displayOrder).filter(type => cellsOf(sheet, type.key).length)
  function add() {
    if (!adding) return
    const count = countInput(adding.count)
    if (count === null) { setError('함께 쓰는 수를 적으세요.'); return }
    const next = addSharedQuota(sheet, adding.kind, adding.members, count)
    if (typeof next === 'string') { setError(next); return }
    onChange(next); setAdding(null); setError('')
  }
  return <fieldset><legend>함께 쓰는 수</legend>
    <p className={styles.hint}>공고문에서 여러 공급대상이나 주택형이 한 칸으로 합쳐 적힌 수입니다. 대상마다 나누지 않고 한 번만 셉니다.</p>
    {shared.length ? <ul aria-label="함께 쓰는 수 목록">{shared.map(({ quota }) => {
      const label = `${quota.members.map(member => cellName(sheet, member)).join('·')} 함께 ${kindLabel[quota.kind]}`
      return <li key={quota.key} className={styles.actions}><span>{label}</span>
        <label>{label} 수<input type="number" min="0" step="1" value={quota.count} onChange={event => onChange(setQuotaCount(sheet, quota.key, countInput(event.target.value) ?? 0))} /></label>
        <button type="button" aria-label={`${label} 빼기`} onClick={() => onChange(removeQuota(sheet, quota.key))}>빼기</button></li>
    })}</ul> : <p>함께 쓰는 수가 없습니다.</p>}
    {adding ? <fieldset><legend>함께 쓰는 수 추가</legend>
      <label>종류<select value={adding.kind} onChange={event => setAdding({ ...adding, kind: event.target.value as QuotaKind })}><option value="WINNER">모집</option><option value="RESERVE">예비</option></select></label>
      {types.map(type => <fieldset key={type.key}><legend>{type.name || '이름 없는 주택형'}</legend>
        {cellsOf(sheet, type.key).map(cell => <label key={cell.key} className={styles.check}><input type="checkbox" checked={adding.members.includes(cell.key)}
          onChange={event => setAdding({ ...adding, members: event.target.checked ? [...adding.members, cell.key] : adding.members.filter(member => member !== cell.key) })} />{cellName(sheet, cell.key)}</label>)}
      </fieldset>)}
      <label>함께 쓸 수<input type="number" min="0" step="1" value={adding.count} onChange={event => setAdding({ ...adding, count: event.target.value })} /></label>
      {error && <p role="alert">{error}</p>}
      <div className={styles.actions}><button type="button" onClick={add}>함께 쓰는 수 더하기</button><button type="button" onClick={() => { setAdding(null); setError('') }}>취소</button></div>
    </fieldset> : <div className={styles.actions}><button type="button" onClick={() => setAdding({ kind: 'WINNER', members: [], count: '' })}>함께 쓰는 수 추가</button></div>}
  </fieldset>
}
