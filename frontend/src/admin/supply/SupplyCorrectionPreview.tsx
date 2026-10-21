import { display } from '../management/fields'
import type { CorrectionRow, CorrectionTarget, SupplyPreview } from './supplyCorrectionApi'
import styles from './SupplyCorrection.module.css'

const rowLabels: Partial<Record<keyof CorrectionRow, string>> = {
  housingComplexId: '연결 단지 ID', housingComplexName: '연결 단지', housingTypeId: '공통 주택형 ID', newHousingType: '새 공통 주택형',
  excluded: '공개 제외', displayOrder: '표시 순서', complexName: '공고 단지명', typeName: '공고 주택형명',
  pnu: 'PNU', moveInMonth: '입주 예정 연월', category: '공급 구분', householdCount: '금회 모집수 (세대)',
  areaOverride: '공고별 면적 보호', exclusiveArea: '전용면적 (㎡)', supplyArea: '공급면적 (㎡)',
  targetCountsComparable: '수량 합계 검증 범위 확인',
}
const targetLabels: Partial<Record<keyof CorrectionTarget, string>> = {
  excluded: '공개 제외', target: '공급대상', supplyRank: '공급순위', householdCount: '모집수 (세대)',
  reserveCount: '예비자수 (명)', deposit: '보증금 (원)', rent: '월 임대료 (원)', convertedDeposit: '전환 보증금 (원)', condition: '신청조건',
}
function text(value: unknown) {
  if (typeof value === 'boolean') return value ? '예' : '아니오'
  if (value && typeof value === 'object' && 'name' in value) {
    const type = value as { name: string; exclusiveArea: number; supplyArea: number | null }
    return `${type.name} · 전용 ${type.exclusiveArea}㎡ · 공급 ${type.supplyArea ?? '미확인'}㎡`
  }
  return display(value as string | number | null | undefined)
}
// 이력에서는 수집 원천에서 온 주택형명이 바뀌었음을 드러낸다.
const historicalRowLabels: Partial<Record<keyof CorrectionRow, string>> = { ...rowLabels, typeName: '원천 주택형명' }
function supplyDifferences(before: CorrectionRow[], after: CorrectionRow[], labels = rowLabels) {
  const changes: { scope: string; field: string; before: string; after: string }[] = []
  for (const next of after) {
    const previous = before.find(row => row.id !== null && row.id === next.id)
    const scope = `${next.complexName} · ${next.typeName}`
    if (!previous) changes.push({ scope, field: '공급행', before: '없음', after: '새 행 추가' })
    for (const key of Object.keys(labels) as (keyof CorrectionRow)[]) {
      if (JSON.stringify(previous?.[key]) !== JSON.stringify(next[key])) changes.push({ scope, field: labels[key]!, before: previous ? text(previous[key]) : '없음', after: text(next[key]) })
    }
    for (const target of next.targets) {
      const old = previous?.targets.find(item => item.id !== null && item.id === target.id)
      const targetScope = `${scope} / ${target.target}`
      if (!old) changes.push({ scope: targetScope, field: '공급대상', before: '없음', after: '새 대상 추가' })
      for (const key of Object.keys(targetLabels) as (keyof CorrectionTarget)[]) {
        if (JSON.stringify(old?.[key]) !== JSON.stringify(target[key])) changes.push({ scope: targetScope, field: targetLabels[key]!, before: old ? text(old[key]) : '없음', after: text(target[key]) })
      }
    }
  }
  return changes
}
export function SupplyCorrectionPreview({ value, historical = false }: { value: SupplyPreview; historical?: boolean }) {
  const changes = supplyDifferences(value.before, value.after, historical ? historicalRowLabels : rowLabels)
  return <section className={styles.preview} aria-label="공급정보 변경 미리보기"><h3>{historical ? '저장한 변경' : '적용할 변경'} {changes.length}개</h3>
    {!historical && <p>이 공고의 공급행·대상 조건에 적용합니다. 새 주택형을 선택한 경우 공통 주택형 생성도 함께 처리합니다.</p>}
    <div className={styles.tableScroll} tabIndex={0} role="region" aria-label="변경 전후 비교"><table><thead><tr><th>대상</th><th>항목</th><th>{historical ? '변경 전' : '현재'}</th><th>{historical ? '변경 후' : '적용 후'}</th></tr></thead>
      <tbody>{changes.map((change, index) => <tr key={index}><th scope="row">{change.scope}</th><td>{change.field}</td><td>{change.before}</td><td>{change.after}</td></tr>)}</tbody></table></div>
    {value.warnings.length > 0 && <section><h4>저장 후에도 확인할 항목</h4><ul>{value.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul></section>}
    {!historical && <p>저장 성공은 관리자 검토 완료와 별개입니다. 공개 결과와 남은 항목을 확인한 후 검토 결과를 기록하세요.</p>}
  </section>
}
