import { useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { housingTypeLabel } from '../ingest/housingTypeLabel'
import type { MatchingHousingType } from '../ingest/supplyMatchingApi'
import type { Evidence } from './extraction'
import { cellMatches, cellStates, choices, chooseCellValue, editCell, extractedBlockReason, pendingCell, pendingField, shown, shownDateTime, shownMoney, validCellValue, type Cell, type ReviewFilter, type ValueMode } from './review'
import styles from './PdfCorrection.module.css'

// 기존 값(지금 저장값) · PDF 추출값(추출 원본) · 직접 입력(운영자 값)을 고르고, 반영값 칸은 저장에 보낼 값을 그대로 보여 준다.
const modes: [ValueMode, string, string][] = [['keep', '기존 값', '기존 값 사용'], ['extracted', 'PDF 추출값', 'PDF 추출값 사용'], ['manual', '직접 입력', '직접 입력']]

/** 단지에 맞는 주택형이 없을 때 연결 주택형에서 고를 수 있는 'PDF 형명·면적으로 새 주택형 추가' */
export type NewHousingTypeChoice = { label: string; selected: boolean; onSelect: (selected: true) => void }

/**
 * housingTypes는 이 행의 단지에서 고를 수 있는 주택형, otherHousingTypes는 다른 단지 주택형이다. 저장 위치를 다른 단지 행으로 바꾸면
 * 기존·추출 값이 그 단지에 없는 주택형일 수 있어, 번호 대신 이름과 다른 단지라는 표시를 보인다.
 */
export function ReviewCells({ cells, scope, housingTypes = [], otherHousingTypes = [], newType, needs, filter = 'all', onChange, onEvidence }: { cells: Cell[]; scope: string; housingTypes?: MatchingHousingType[]; otherHousingTypes?: MatchingHousingType[]; newType?: NewHousingTypeChoice; needs?: Map<string, string>; filter?: ReviewFilter; onChange: (cells: Cell[]) => void; onEvidence: (evidence: Evidence, cell?: Cell) => void }) {
  const [all, setAll] = useState(false)
  const quiet = filter === 'all' ? cells.filter(cell => cell.mode !== 'manual' && !needs?.has(cell.path) && (cell.state === 'same' || cell.state === 'notApplicable')) : []
  const visible = cells.filter(cell => (cellMatches(cell, filter) || filter === 'pending' && pendingField(cell, needs)) && (all || !quiet.includes(cell)))
  function update(path: string, next: Cell) { onChange(cells.map(cell => cell.path === path ? next : cell)) }
  function display(cell: Cell, value: unknown) {
    if (cell.kind === 'housingType') {
      const type = housingTypes.find(item => item.id === value), other = otherHousingTypes.find(item => item.id === value)
      return value == null ? '미연결' : type ? housingTypeLabel(type, housingTypes) : other ? `${housingTypeLabel(other)} · 다른 단지` : `#${String(value)}`
    }
    if (cell.kind === 'method' || cell.kind === 'scheduleType') return choices[cell.kind].find(([key]) => key === value)?.[1] ?? shown(value)
    if (cell.kind === 'datetime') return shownDateTime(value)
    if (cell.kind === 'money') return shownMoney(value)
    return shown(value)
  }
  function manualField(cell: Cell) {
    const change = (value: string) => update(cell.path, editCell(cell, value))
    const value = cell.kind === 'housingType' && newType?.selected ? 'new' : cell.input ?? String(cell.value ?? '')
    return <label className={styles.manualField}><span className={styles.srOnly}>{scope} {cell.label} 수정</span>{cell.kind === 'housingType'
      ? <select value={value} aria-invalid={cell.state === 'invalid' || undefined} onChange={event => { if (event.target.value === 'new') newType?.onSelect(true); else change(event.target.value) }}><option value="">주택형 미연결</option>{housingTypes.map(type => <option key={type.id} value={type.id}>{display(cell, type.id)}</option>)}{newType && <option value="new">PDF 형명·면적으로 새 주택형 추가 ({newType.label})</option>}</select>
      : cell.kind === 'method' || cell.kind === 'scheduleType'
        ? <select value={value} onChange={event => change(event.target.value)}><option value="">미확인</option>{choices[cell.kind].map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select>
        : cell.field === 'applicationCondition' ? <textarea rows={3} value={value} aria-invalid={cell.state === 'invalid' || undefined} onChange={event => change(event.target.value)} />
          : <input type={cell.kind === 'date' ? 'date' : cell.kind === 'datetime' ? 'datetime-local' : cell.kind === 'month' ? 'month' : 'text'} inputMode={cell.kind === 'money' || cell.kind === 'count' ? 'numeric' : cell.kind === 'area' ? 'decimal' : undefined}
            value={value} aria-invalid={cell.state === 'invalid' || undefined} onChange={event => change(event.target.value)} />}</label>
  }
  if (!visible.length && !quiet.length) return null
  return <div className={styles.cells}>
    <div className={styles.cellHead}><span>항목</span><span>기존 값</span><span>PDF 추출값</span><span>반영값</span><span>PDF</span></div>
    {visible.map(cell => {
      const canUseExtracted = validCellValue(cell.kind, cell.extracted.value) && !['invalid', 'unresolved', 'notApplicable'].includes(cell.extracted.state)
      const blocked = extractedBlockReason(cell)
      const need = needs?.get(cell.path)
      return <div key={cell.path} className={styles.cell} data-state={cell.state} data-pending={pendingField(cell, needs) || undefined} data-review-id={`cell:${cell.path}`} tabIndex={-1}>
        <div className={styles.cellLabel}><strong>{cell.label}</strong><small>{pendingCell(cell) && cell.mode !== 'manual' ? `${cellStates[cell.extracted.state]} · 선택 전` : need ? '확인 필요' : cellStates[cell.state]}</small></div>
        <div className={styles.before}><span className={styles.mobileLabel}>기존 값</span>{display(cell, cell.before)}</div>
        <div className={styles.extracted}><span className={styles.mobileLabel}>PDF 추출값</span>{display(cell, cell.extracted.value)}</div>
        <div className={styles.cellResult}>{cell.locked ? <span>{cell.note || '기존 값 유지'}</span> : <>
          <div role="group" aria-label={`${scope} ${cell.label} 반영 방법`} className={styles.valueChoices}>{modes.map(([mode, label, name]) => <Button data-design-system key={mode} aria-label={mode === 'keep' && cell.before == null ? undefined : name} aria-pressed={cell.mode === mode} disabled={mode === 'extracted' && !canUseExtracted}
            onClick={() => update(cell.path, chooseCellValue(cell, mode))}>{mode === 'keep' && cell.before == null ? emptyKeepLabel(cell) : label}</Button>)}</div>
          {cell.kind === 'housingType' && newType?.selected && cell.mode !== 'manual' ? <strong className={styles.resultValue}>새 주택형 추가 · {newType.label}</strong> : cell.mode === 'manual' ? manualField(cell) : <strong className={styles.resultValue}>{cell.mode === 'keep' && cell.before == null ? emptyKeepResult(cell) : display(cell, cell.mode === 'keep' ? cell.before : cell.value)}</strong>}
          {cell.note && <small>{cell.note}</small>}
          {need && <div className={styles.blockReason}><small>{need}</small></div>}
          {!canUseExtracted && blocked && <div className={styles.blockReason}><small>{`PDF 추출값을 고를 수 없음: ${blocked}`}</small>
            {cell.evidence && <Button data-design-system className={styles.quietButton} onClick={() => onEvidence(cell.evidence!, cell)}>{`근거 p.${cell.evidence.page} 열기`}</Button>}
            {cell.mode !== 'manual' && <Button data-design-system className={styles.quietButton} onClick={() => update(cell.path, chooseCellValue(cell, 'manual'))}>{cell.extracted.value != null ? '추출값으로 직접 입력' : '값 직접 입력'}</Button>}</div>}
        </>}</div>
        <div className={styles.cellEvidence}>{cell.evidence && <Button data-design-system className={styles.evidenceButton} aria-label={`PDF ${cell.evidence.page}쪽 보기`} onClick={() => onEvidence(cell.evidence!, cell)} title={cell.evidence.quote}>p.{cell.evidence.page}</Button>}</div>
      </div>
    })}
    {quiet.length > 0 && <Button data-design-system className={styles.quietButton} aria-expanded={all} onClick={() => setAll(value => !value)}>{all ? '같음·해당 없음 접기' : `같음·해당 없음 ${quiet.length}개 보기`}</Button>}
  </div>
}

// 현재값이 비어 있을 때 '기존 값'을 고르면 새 값을 넣지 않고 비워 둔다는 뜻이다. 기존 행에도 맞도록 '추가'라는 말을 쓰지 않는다.
function emptyKeepLabel(cell: Cell) { return cell.kind === 'housingType' ? '미연결 유지' : '비워 두기' }
function emptyKeepResult(cell: Cell) { return cell.kind === 'housingType' ? '미연결' : '비어 있음' }
