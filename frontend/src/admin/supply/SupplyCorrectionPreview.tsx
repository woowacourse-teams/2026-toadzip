import { display } from '../management/fields'
import { cellsOf, type SheetHousingType, type SupplySheet } from './supplySheet'
import { cellName, cellValues } from './sheetEdits'
import type { SupplyPreview } from './supplyCorrectionApi'
import styles from './SupplyCorrection.module.css'

type Change = { scope: string; field: string; before: string; after: string }
const typeLabels: Partial<Record<keyof SheetHousingType, string>> = {
  complexName: '공고 단지', housingComplexName: '연결 단지', housingTypeId: '연결 주택형', newHousingType: '새 단지 주택형',
  name: '주택형 표기', exclusiveArea: '전용면적 (㎡)', supplyArea: '공급면적 (㎡)', expectedMoveInMonth: '입주 예정 연월',
  supplyPnu: '공급 PNU', category: '공급 구분', displayOrder: '표시 순서',
}
const cellLabels = { label: '공급대상', applicationCondition: '신청조건', winner: '모집 수', reserve: '예비 수',
  rentalDeposit: '보증금 (원)', monthlyRent: '월 임대료 (원)', convertedDeposit: '전환 보증금 (원)' } as const
const kindLabel = { WINNER: '모집', RESERVE: '예비' } as const
function text(value: unknown) {
  if (value && typeof value === 'object' && 'name' in value) {
    const type = value as { name: string; exclusiveArea: number; supplyArea: number | null }
    return `${type.name} · 전용 ${type.exclusiveArea}㎡ · 공급 ${type.supplyArea ?? '미확인'}㎡`
  }
  return display(value as string | number | null | undefined)
}
// 새로 추가한 항목은 실제로 입력한 값만 보여 준다. 빈 값과 범위 이름에 이미 나온 항목은 변경으로 세지 않는다.
const worthShowing = (value: unknown) => value !== null && value !== undefined && value !== ''
const typeName = (type: SheetHousingType) => `${type.complexName} · ${type.name || '이름 없는 주택형'}`
// 칸 여럿이 함께 쓰는 수(주택형 모집수 포함)를 종류와 멤버 칸으로 맞춘다. 칸 하나의 수는 칸 변경에서 보여 준다.
function sharedCounts(sheet: SupplySheet) {
  return new Map(sheet.quotas.filter(quota => quota.members.length > 1)
    .map(quota => [`${quota.kind}|${[...quota.members].sort().join(',')}`, quota]))
}

function supplyDifferences(before: SupplySheet, after: SupplySheet): Change[] {
  const changes: Change[] = []
  for (const removed of before.housingTypes.filter(type => !after.housingTypes.some(next => next.key === type.key))) {
    changes.push({ scope: typeName(removed), field: '주택형', before: `공급대상 ${cellsOf(before, removed.key).length}개`, after: '삭제' })
  }
  for (const next of [...after.housingTypes].sort((a, b) => a.displayOrder - b.displayOrder)) {
    const previous = before.housingTypes.find(type => type.key === next.key)
    const scope = typeName(next)
    if (!previous) changes.push({ scope, field: '주택형', before: '없음', after: '새 주택형 추가' })
    for (const key of Object.keys(typeLabels) as (keyof SheetHousingType)[]) {
      if (!previous && (!worthShowing(next[key]) || ['complexName', 'name', 'displayOrder'].includes(key))) continue
      if (JSON.stringify(previous?.[key] ?? null) !== JSON.stringify(next[key] ?? null)) {
        changes.push({ scope, field: typeLabels[key]!, before: previous ? text(previous[key]) : '없음', after: text(next[key]) })
      }
    }
    const previousCells = previous ? cellsOf(before, previous.key) : []
    for (const removed of previousCells.filter(cell => !after.cells.some(item => item.key === cell.key))) {
      changes.push({ scope: cellName(before, removed.key), field: '공급대상', before: '있음', after: '삭제' })
    }
    for (const cell of cellsOf(after, next.key)) {
      const old = previousCells.find(item => item.key === cell.key)
      const was = old ? cellValues(before, old) : null, now = cellValues(after, cell)
      const cellScope = cellName(after, cell.key)
      if (!was) changes.push({ scope: cellScope, field: '공급대상', before: '없음', after: '새 공급대상 추가' })
      for (const key of Object.keys(cellLabels) as (keyof typeof cellLabels)[]) {
        if (!was && (!worthShowing(now[key]) || key === 'label')) continue
        if (was?.[key] !== now[key]) changes.push({ scope: cellScope, field: cellLabels[key], before: was ? text(was[key]) : '없음', after: text(now[key]) })
      }
    }
  }
  const was = sharedCounts(before), now = sharedCounts(after)
  for (const [key, quota] of now) {
    const previous = was.get(key)
    if (previous?.count === quota.count) continue
    changes.push({ scope: quota.members.map(member => cellName(after, member)).join('·'), field: `함께 쓰는 ${kindLabel[quota.kind]} 수`,
      before: previous ? String(previous.count) : '없음', after: String(quota.count) })
  }
  for (const [key, quota] of was) {
    if (!now.has(key)) changes.push({ scope: quota.members.map(member => cellName(before, member)).join('·'), field: `함께 쓰는 ${kindLabel[quota.kind]} 수`, before: String(quota.count), after: '삭제' })
  }
  return changes
}

export function SupplyCorrectionPreview({ value, historical = false }: { value: SupplyPreview; historical?: boolean }) {
  const changes = supplyDifferences(value.before, value.after)
  return <section className={styles.preview} aria-label="공급정보 변경 미리보기"><h3>{changes.length ? `${historical ? '저장한 변경' : '적용할 변경'} ${changes.length}개` : '공급표 변경 없음'}</h3>
    {!historical && <p>이 공고의 공급표에 적용합니다. 새 주택형을 선택한 경우 단지 주택형 생성도 함께 처리합니다.</p>}
    {changes.length > 0 && <div className={styles.tableScroll} tabIndex={0} role="region" aria-label="변경 전후 비교"><table><thead><tr><th>대상</th><th>항목</th><th>{historical ? '변경 전' : '현재'}</th><th>{historical ? '변경 후' : '적용 후'}</th></tr></thead>
      <tbody>{changes.map((change, index) => <tr key={index}><th scope="row">{change.scope}</th><td>{change.field}</td><td>{change.before}</td><td>{change.after}</td></tr>)}</tbody></table></div>}
    {value.warnings.length > 0 && <section><h4>저장 후에도 확인할 항목</h4><ul>{value.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul></section>}
    {!historical && <p>저장 성공은 관리자 검토 완료와 별개입니다. 공개 결과와 남은 항목을 확인한 후 검토 결과를 기록하세요.</p>}
  </section>
}
