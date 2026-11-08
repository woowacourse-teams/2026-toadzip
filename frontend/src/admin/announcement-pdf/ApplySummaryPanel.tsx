import { Button } from '../../design-system/components/Button'
import type { ApplySummary, SummaryRow, ValueChange } from './applySummary'
import styles from './PdfCorrection.module.css'

const changeLabels: Record<SummaryRow['change'], string> = { same: '그대로', changed: '변경', new: '새 행', excluded: '적용 제외' }
const count = (value: number | null) => value === null ? '미확인 포함' : `${value}세대`
function RowList({ rows, label }: { rows: SummaryRow[]; label: string }) {
  return <ul aria-label={label}>{rows.map(row => <li key={row.key} data-change={row.change}>
    <span>{row.typeName}{row.id !== null ? ` #${row.id}` : ''}</span><span>{row.householdCount ?? '미확인'}세대</span>
    <span>{row.housingType}{row.linked.length ? ` + ${row.linked.join(', ')}` : ''}</span>{label.endsWith('후') && <small>{changeLabels[row.change]}</small>}</li>)}</ul>
}

/** 기본정보·일정·주택형처럼 항목마다 변경 전후 값이 있는 목록. 비어 있으면 그리지 않는다. */
function ChangeList({ title, items, hint }: { title: string; items: ValueChange[]; hint?: string }) {
  if (!items.length) return null
  return <section aria-label={title}><h3>{`${title} ${items.length}건`}</h3>{hint && <p className={styles.hint}>{hint}</p>}
    <ul className={styles.changeList}>{items.map((item, index) => <li key={index}><strong>{item.label}</strong><span>{item.before}</span><span>→</span><span>{item.after}</span></li>)}</ul></section>
}

/** 서버 미리보기로 만든 적용 전 확인. 단지별 변경 전후 공급행(서버가 정한 주택형 포함), 모집수 합계·중복, 임대조건 변경과 경고를 보여 준다. */
export function ApplySummaryPanel({ summary, busy, onApply, onBack }: { summary: ApplySummary; busy: boolean; onApply: () => void; onBack: () => void }) {
  return <section className={styles.applySummary} role="region" aria-label="적용 전 확인" tabIndex={-1}>
    <h2>적용 전 확인</h2>
    <p className={styles.hint}>서버가 검증한 저장 결과입니다. 여러 탭에서 고른 값이 실제로 어떻게 바뀌는지 여기서 한 번에 확인하세요.</p>
    {summary.warnings.length > 0 && <div className={styles.warningNote} role="note" aria-label="적용 경고"><p><strong>경고</strong> 저장은 되지만 확인할 내용입니다.</p><ul>{summary.warnings.map(item => <li key={item}>{item}</li>)}</ul></div>}
    {summary.complexes.map(complex => <section key={complex.name} className={styles.summaryComplex} aria-label={`${complex.name} 적용 전후`}>
      <h3>{complex.name}<small>{`모집수 합계 ${count(complex.beforeTotal)} → ${count(complex.afterTotal)}`}</small></h3>
      <div className={styles.summaryColumns}><div><h4>변경 전</h4><RowList rows={complex.before} label={`${complex.name} 변경 전`} /></div><div><h4>변경 후</h4><RowList rows={complex.after} label={`${complex.name} 변경 후`} /></div></div>
      {complex.duplicates.map(item => <p key={item} className={styles.warningNote}><strong>중복 확인</strong> {item}</p>)}
    </section>)}
    <ChangeList title="기본정보·접수처 변경" items={summary.fields} />
    <ChangeList title="일정 변경" items={summary.schedules} />
    <ChangeList title="단지 주택타입 갱신" items={summary.housingTypes} hint="연결한 단지 주택타입의 값이 바뀝니다. 사용자 화면의 단지 가격·검색 조건에도 쓰입니다." />
    {summary.targets.length > 0 && <section aria-label="임대조건 변경"><h3>임대조건 추가·변경 {summary.targets.length}건</h3><ul>{summary.targets.map((item, index) => <li key={index}>{`${item.row} · ${item.kind === 'new' ? '추가' : '변경'} · ${item.detail}`}</li>)}</ul></section>}
    <div className={styles.actions}><Button data-design-system disabled={busy} onClick={onApply}>{busy ? '적용 중…' : '이대로 적용'}</Button><Button data-design-system className={styles.secondaryButton} disabled={busy} onClick={onBack}>돌아가서 수정</Button></div>
  </section>
}
