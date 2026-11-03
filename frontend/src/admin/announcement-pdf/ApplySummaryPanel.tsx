import { Button } from '../../design-system/components/Button'
import type { ApplySummary, SummaryRow } from './applySummary'
import styles from './PdfCorrection.module.css'

const changeLabels: Record<SummaryRow['change'], string> = { same: '그대로', changed: '변경', new: '새 행', excluded: '적용 제외' }
const count = (value: number | null) => value === null ? '미확인 포함' : `${value}세대`
function RowList({ rows, label }: { rows: SummaryRow[]; label: string }) {
  return <ul aria-label={label}>{rows.map(row => <li key={row.key} data-change={row.change}>
    <span>{row.typeName}{row.id !== null ? ` #${row.id}` : ''}</span><span>{row.householdCount ?? '미확인'}세대</span>
    <span>{row.housingType}{row.linked.length ? ` + ${row.linked.join(', ')}` : ''}</span>{label.endsWith('후') && <small>{changeLabels[row.change]}</small>}</li>)}</ul>
}

/** 서버 미리보기로 만든 적용 전 확인. 단지별 변경 전후 공급행, 모집수 합계·중복, 통합행 처리, 임대조건 변경과 경고를 보여 준다. */
export function ApplySummaryPanel({ summary, busy, onApply, onBack }: { summary: ApplySummary; busy: boolean; onApply: () => void; onBack: () => void }) {
  return <section className={styles.applySummary} role="region" aria-label="적용 전 확인" tabIndex={-1}>
    <h2>적용 전 확인</h2>
    <p className={styles.hint}>{`서버가 검증한 저장 결과입니다. 기본정보 ${summary.fieldCount}개 · 일정 ${summary.scheduleCount}개도 함께 저장합니다.`}</p>
    {summary.warnings.length > 0 && <div className={styles.warningNote} role="note" aria-label="적용 경고"><p><strong>경고</strong> 저장은 되지만 확인할 내용입니다.</p><ul>{summary.warnings.map(item => <li key={item}>{item}</li>)}</ul></div>}
    {summary.unattached.length > 0 && <div className={styles.warningNote} role="note" aria-label="남는 LH 행"><p><strong>미연결</strong> 저장 뒤에도 공급행이 없는 LH 행입니다. 공고문 소유가 되어 정제가 이 행을 공급행으로 만들지 않습니다.</p><ul>{summary.unattached.map(item => <li key={item}>{item}</li>)}</ul></div>}
    {summary.splits.map(split => <div key={split.label} className={split.householdCount === split.lhHouseholdCount ? styles.notice : styles.warningNote} aria-label={`통합행 분리 ${split.label}`} role="group">
      <p><strong>통합행 분리</strong> {split.label}</p><p>{`${split.merged} → ${split.result}`}</p>
      <p>{`분리 합계 ${count(split.householdCount)} / LH ${count(split.lhHouseholdCount)}${split.householdCount === split.lhHouseholdCount ? ' · 일치' : ' · 다름'}`}</p></div>)}
    {summary.complexes.map(complex => <section key={complex.name} className={styles.summaryComplex} aria-label={`${complex.name} 적용 전후`}>
      <h3>{complex.name}<small>{`모집수 합계 ${count(complex.beforeTotal)} → ${count(complex.afterTotal)}`}</small></h3>
      <div className={styles.summaryColumns}><div><h4>변경 전</h4><RowList rows={complex.before} label={`${complex.name} 변경 전`} /></div><div><h4>변경 후</h4><RowList rows={complex.after} label={`${complex.name} 변경 후`} /></div></div>
      {complex.duplicates.map(item => <p key={item} className={styles.warningNote}><strong>중복 확인</strong> {item}</p>)}
    </section>)}
    {summary.targets.length > 0 && <section aria-label="임대조건 변경"><h3>임대조건 추가·변경 {summary.targets.length}건</h3><ul>{summary.targets.map((item, index) => <li key={index}>{`${item.row} · ${item.kind === 'new' ? '추가' : '변경'} · ${item.detail}`}</li>)}</ul></section>}
    <div className={styles.actions}><Button data-design-system disabled={busy} onClick={onApply}>{busy ? '적용 중…' : '이대로 적용'}</Button><Button data-design-system className={styles.secondaryButton} disabled={busy} onClick={onBack}>돌아가서 수정</Button></div>
  </section>
}
