import { useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { housingTypeLabel } from '../ingest/housingTypeLabel'
import type { MatchingHousingType } from '../ingest/supplyMatchingApi'
import type { Evidence } from './extraction'
import { cellMatches, cellStates, choices, chooseCellValue, editCell, pendingCell, shown, shownDateTime, validCellValue, type Cell, type ReviewFilter, type ValueMode } from './review'
import styles from './PdfCorrection.module.css'

const modes: [ValueMode, string, string][] = [['keep', '기존 값', '기존 값 사용'], ['extracted', '보정 값', '보정된 값 사용'], ['manual', '직접 수정', '직접 수정']]

export function ReviewCells({ cells, scope, housingTypes = [], filter = 'all', onChange, onEvidence }: { cells: Cell[]; scope: string; housingTypes?: MatchingHousingType[]; filter?: ReviewFilter; onChange: (cells: Cell[]) => void; onEvidence: (evidence: Evidence) => void }) {
  const [all, setAll] = useState(false)
  const quiet = filter === 'all' ? cells.filter(cell => cell.mode !== 'manual' && (cell.state === 'same' || cell.state === 'notApplicable')) : []
  const visible = cells.filter(cell => cellMatches(cell, filter) && (all || !quiet.includes(cell)))
  function update(path: string, next: Cell) { onChange(cells.map(cell => cell.path === path ? next : cell)) }
  function display(cell: Cell, value: unknown) {
    if (cell.kind === 'housingType') {
      const type = housingTypes.find(item => item.id === value)
      return value == null ? '미연결' : type ? housingTypeLabel(type, housingTypes) : `#${String(value)}`
    }
    if (cell.kind === 'method' || cell.kind === 'scheduleType') return choices[cell.kind].find(([key]) => key === value)?.[1] ?? shown(value)
    if (cell.kind === 'datetime') return shownDateTime(value)
    return shown(value)
  }
  function manualField(cell: Cell) {
    const change = (value: string) => update(cell.path, editCell(cell, value))
    const value = cell.input ?? String(cell.value ?? '')
    return <label className={styles.manualField}><span className={styles.srOnly}>{scope} {cell.label} 수정</span>{cell.kind === 'housingType'
      ? <select value={value} aria-invalid={cell.state === 'invalid' || undefined} onChange={event => change(event.target.value)}><option value="">주택형 미연결</option>{housingTypes.map(type => <option key={type.id} value={type.id}>{display(cell, type.id)}</option>)}</select>
      : cell.kind === 'method' || cell.kind === 'scheduleType'
        ? <select value={value} onChange={event => change(event.target.value)}><option value="">미확인</option>{choices[cell.kind].map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select>
        : cell.field === 'applicationCondition' ? <textarea rows={3} value={value} aria-invalid={cell.state === 'invalid' || undefined} onChange={event => change(event.target.value)} />
          : <input type={cell.kind === 'date' ? 'date' : cell.kind === 'datetime' ? 'datetime-local' : cell.kind === 'month' ? 'month' : 'text'} inputMode={cell.kind === 'money' || cell.kind === 'count' ? 'numeric' : cell.kind === 'area' ? 'decimal' : undefined}
            value={value} aria-invalid={cell.state === 'invalid' || undefined} onChange={event => change(event.target.value)} />}</label>
  }
  if (!visible.length && !quiet.length) return null
  return <div className={styles.cells}>
    <div className={styles.cellHead}><span>항목</span><span>기존 값</span><span>보정된 값</span><span>반영할 값</span><span>PDF</span></div>
    {visible.map(cell => {
      const canUseExtracted = validCellValue(cell.kind, cell.extracted.value) && !['invalid', 'unresolved', 'notApplicable'].includes(cell.extracted.state)
      return <div key={cell.path} className={styles.cell} data-state={cell.state} data-pending={pendingCell(cell) || undefined} data-review-id={`cell:${cell.path}`} tabIndex={-1}>
        <div className={styles.cellLabel}><strong>{cell.label}</strong><small>{pendingCell(cell) && cell.mode !== 'manual' ? `${cellStates[cell.extracted.state]} · 선택 전` : cellStates[cell.state]}</small></div>
        <div className={styles.before}><span className={styles.mobileLabel}>기존 값</span>{display(cell, cell.before)}</div>
        <div className={styles.extracted}><span className={styles.mobileLabel}>보정된 값</span>{display(cell, cell.extracted.value)}</div>
        <div className={styles.cellResult}>{cell.locked ? <span>{cell.note || '기존 값 유지'}</span> : <>
          <div role="group" aria-label={`${scope} ${cell.label} 반영 방법`} className={styles.valueChoices}>{modes.map(([mode, label, name]) => <Button data-design-system key={mode} aria-label={name} aria-pressed={cell.mode === mode} disabled={mode === 'extracted' && !canUseExtracted}
            onClick={() => update(cell.path, chooseCellValue(cell, mode))}>{mode === 'keep' && cell.before == null ? emptyKeepLabel(cell) : label}</Button>)}</div>
          {cell.mode === 'manual' ? manualField(cell) : <strong className={styles.resultValue}>{cell.mode === 'keep' && cell.before == null ? emptyKeepResult(cell) : display(cell, cell.mode === 'keep' ? cell.before : cell.value)}</strong>}
          {cell.note && <small>{cell.note}</small>}
        </>}</div>
        <div className={styles.cellEvidence}>{cell.evidence && <Button data-design-system className={styles.evidenceButton} aria-label={`PDF ${cell.evidence.page}쪽 보기`} onClick={() => onEvidence(cell.evidence!)} title={cell.evidence.quote}>p.{cell.evidence.page}</Button>}</div>
      </div>
    })}
    {quiet.length > 0 && <Button data-design-system className={styles.quietButton} aria-expanded={all} onClick={() => setAll(value => !value)}>{all ? '같음·해당 없음 접기' : `같음·해당 없음 ${quiet.length}개 보기`}</Button>}
  </div>
}

// 현재값이 비어 있을 때 '기존 값'을 고르면 새 값을 넣지 않고 비워 둔다는 뜻이다. 기존 행에도 맞도록 '추가'라는 말을 쓰지 않는다.
function emptyKeepLabel(cell: Cell) { return cell.kind === 'housingType' ? '미연결 유지' : '비워 두기' }
function emptyKeepResult(cell: Cell) { return cell.kind === 'housingType' ? '미연결' : '비어 있음' }
