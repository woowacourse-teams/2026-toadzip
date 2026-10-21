import { useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { Evidence } from './extraction'
import { cellStates, editCell, shown, type Cell } from './review'
import styles from './PdfCorrection.module.css'

const choices = {
  method: [['ONLINE', '온라인'], ['VISIT', '방문'], ['MAIL', '우편'], ['ETC', '기타']],
  scheduleType: [['APPLICATION', '접수'], ['DOCUMENT_SUBMISSION', '서류 제출'], ['WINNER_ANNOUNCEMENT', '당첨자 발표'], ['CONTRACT', '계약'], ['MOVE_IN', '입주'], ['ETC', '기타']],
}
export function ReviewCells({ cells, scope, onChange, onEvidence }: { cells: Cell[]; scope: string; onChange: (cells: Cell[]) => void; onEvidence: (evidence: Evidence) => void }) {
  const [all, setAll] = useState(false)
  const quiet = cells.filter(cell => cell.state === 'same' || cell.state === 'notApplicable')
  function update(path: string, next: Cell) { onChange(cells.map(cell => cell.path === path ? next : cell)) }
  return <div className={styles.cells}>
    <div className={styles.cellHead}><span>적용 · 항목</span><span>현재값</span><span>PDF 추출값 · 수정</span><span>상태 · 근거</span></div>
    {cells.filter(cell => all || !quiet.includes(cell)).map(cell => <div key={cell.path} className={styles.cell} data-state={cell.state}>
      <label><input type="checkbox" aria-label={`${scope} ${cell.label} 적용`} checked={cell.selected} disabled={cell.locked || ['invalid', 'unresolved', 'notApplicable'].includes(cell.state)}
        onChange={event => update(cell.path, { ...cell, selected: event.target.checked })} />{cell.label}</label>
      <div className={styles.before}><span className={styles.mobileLabel}>현재값</span>{shown(cell.before)}</div>
      <label><span className={styles.srOnly}>{scope} {cell.label} 수정</span>{cell.kind === 'method' || cell.kind === 'scheduleType'
        ? <select value={String(cell.value ?? '')} disabled={cell.locked} onChange={event => update(cell.path, editCell(cell, event.target.value))}><option value="">미확인</option>{choices[cell.kind].map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
        : cell.field === 'applicationCondition' ? <textarea rows={4} value={String(cell.value ?? '')} disabled={cell.locked} aria-invalid={cell.state === 'invalid' || undefined} onChange={event => update(cell.path, editCell(cell, event.target.value))} /> : <input type={cell.kind === 'date' ? 'date' : cell.kind === 'datetime' ? 'datetime-local' : cell.kind === 'month' ? 'month' : 'text'}
          inputMode={cell.kind === 'money' || cell.kind === 'count' ? 'numeric' : cell.kind === 'area' ? 'decimal' : undefined}
          value={cell.input ?? String(cell.value ?? '')} disabled={cell.locked} aria-invalid={cell.state === 'invalid' || undefined}
          onChange={event => update(cell.path, editCell(cell, event.target.value))} />}</label>
      <div className={styles.cellStatus}><span>{cell.locked ? '현재값 유지' : cellStates[cell.state]}</span>{cell.note && <small>{cell.note}</small>}{cell.evidence && <Button data-design-system className={styles.quietButton} onClick={() => onEvidence(cell.evidence!)} title={cell.evidence.quote}>{cell.evidence.page}쪽 근거</Button>}</div>
    </div>)}
    {quiet.length > 0 && <Button data-design-system className={styles.quietButton} aria-expanded={all} onClick={() => setAll(value => !value)}>{all ? '같음·해당 없음 접기' : `같음·해당 없음 ${quiet.length}개 보기`}</Button>}
  </div>
}
