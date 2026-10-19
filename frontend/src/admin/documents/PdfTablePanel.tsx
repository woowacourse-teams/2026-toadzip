import { useMemo, useState } from 'react'
import type { ExtractedDocument } from './pdfExtraction'
import type { Proposal, ReviewTarget } from './documentReview'
import { tableProposal, tableRows } from './pdfTable'
import styles from './DocumentReview.module.css'

export function PdfTablePanel({ document, target, disabled, onAdded }: {
  document: ExtractedDocument; target: ReviewTarget; disabled: boolean; onAdded: (proposal: Proposal) => void
}) {
  const pages = useMemo(() => document.pages.map(page => ({ page, rows: tableRows(page) })), [document])
  const [selected, setSelected] = useState<{ page: number; row: number; cell: number } | null>(null)
  const fields = target.fields.filter(field => field.money || field.type === 'number')
  const [fieldName, setFieldName] = useState(fields[0]?.name ?? '')
  const field = fields.find(field => field.name === fieldName)
  const [unit, setUnit] = useState(''); const [scope, setScope] = useState(''); const [confirmed, setConfirmed] = useState(false)
  const [error, setError] = useState('')
  const source = pages.find(item => item.page.page === selected?.page)
  const cell = selected && source?.rows[selected.row]?.cells[selected.cell]
  return <details className={styles.card}><summary>표의 행·셀로 수정안 만들기</summary>
    <p>위치로 묶은 행·셀 후보입니다. 병합 셀·열 제목·단위·각주와 적용 대상은 원문에서 확인하세요. OCR 자료는 좌표가 없어 수기로 검토합니다.</p>
    {pages.every(item => !item.rows.length) ? <p>위치를 확인할 수 있는 텍스트가 없습니다. 원문을 확인하며 직접 입력하세요.</p> :
      <fieldset disabled={disabled}><legend>근거 셀 선택</legend>{pages.map(({ page, rows }) => <div key={page.page}>
        <h4>{page.page}쪽</h4><div className={styles.tableScroll}><table><caption>원문 행·셀 후보</caption><tbody>{rows.map((row, rowIndex) => <tr key={rowIndex}><th scope="row">{rowIndex + 1}행</th>{row.cells.map((cell, cellIndex) => <td key={cellIndex}>
          <button type="button" aria-pressed={selected?.page === page.page && selected.row === rowIndex && selected.cell === cellIndex}
            aria-label={`${page.page}쪽 ${rowIndex + 1}행 ${cellIndex + 1}셀 ${cell.text}`} onClick={() => { setSelected({ page: page.page, row: rowIndex, cell: cellIndex }); setConfirmed(false); setError('') }}>{cell.text}</button>
        </td>)}</tr>)}</tbody></table></div></div>)}</fieldset>}
    {cell && source ? <fieldset className={styles.inputs} disabled={disabled}><legend>선택 셀의 단위·적용 대상 확인</legend>
      <blockquote>{cell.text}</blockquote><p>수정 대상: {target.label} · {field?.scope}</p>
      <label>표에서 수정할 항목<select value={fieldName} onChange={event => { setFieldName(event.target.value); setUnit(''); setConfirmed(false) }}>{fields.map(field => <option key={field.name} value={field.name}>{field.label}</option>)}</select></label>
      <label>표에 표시된 원문 단위<select value={unit} onChange={event => { setUnit(event.target.value); setConfirmed(false) }}><option value="">선택</option>{(field?.money ? ['원', '천원', '만원'] : [field?.unit ?? '']).map(unit => <option key={unit}>{unit}</option>)}</select></label>
      <label>표의 적용 단지·주택형·공급대상<input value={scope} maxLength={500} onChange={event => { setScope(event.target.value); setConfirmed(false) }} /></label>
      <label><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />열 제목·단위·각주와 현재 수정 대상의 일치를 원문에서 확인했습니다</label>
      <button type="button" disabled={!confirmed || !field || !unit || !scope.trim()} onClick={() => {
        if (!field) return
        try { onAdded(tableProposal(source.page, cell.text, field, unit, scope)); setError(''); setConfirmed(false) }
        catch (cause) { setError(cause instanceof Error ? cause.message : '표 셀을 확인해 주세요.') }
      }}>표 셀 수정안 추가</button>
    </fieldset> : null}
    {error ? <p role="alert">{error}</p> : null}
  </details>
}
