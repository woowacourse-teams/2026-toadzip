import { useEffect, useState } from 'react'
import { SupplyImportGuide } from './SupplyImportGuide'
import { StoredDataTable } from '../shared/StoredDataTable'
import type { CorrectionRow } from './supplyCorrectionApi'
import { mergeSupplyImport, parseSupplyImport, type ImportedSupply } from './supplyImport'
import styles from './SupplyCorrection.module.css'
export function SupplyImportPanel({ rows, disabled, onMerge, onDirtyChange }: { rows: CorrectionRow[]; disabled: boolean; onMerge: (rows: CorrectionRow[]) => void; onDirtyChange: (dirty: boolean) => void }) {
  const [text, setText] = useState(''); const [input, setInput] = useState<ImportedSupply | null>(null)
  const [destinations, setDestinations] = useState<string[]>([]); const [error, setError] = useState('')
  useEffect(() => { onDirtyChange(text.length > 0); return () => onDirtyChange(false) }, [text, onDirtyChange])
  return <details className={styles.row}><summary>구조화한 공급정보를 변경안으로 가져오기</summary><fieldset className={styles.rowBody} disabled={disabled}><legend className={styles.srOnly}>공급정보 가져오기</legend>
    <p>공식 자료에서 작성한 행마다 기존 공급행 또는 새 행을 직접 선택하세요. 생략한 값·행·조건은 유지하며, 가져온 뒤 일반 변경 미리보기와 근거 확인을 거칩니다.</p>
    <SupplyImportGuide rows={rows} />
    <label>공급정보 JSON<textarea rows={7} value={text} onChange={event => { setText(event.target.value); setInput(null); setError('') }} /></label>
    <button type="button" disabled={!text.trim()} onClick={() => { try { const value = parseSupplyImport(text); setInput(value); setDestinations(value.rows.map(() => '')); setError('') } catch (cause) { setError(cause instanceof Error ? cause.message : '입력 형식을 확인하세요.') } }}>입력행 확인</button>
    {input && <><ol>{input.rows.map((row, index) => <li key={index}><label>입력 {index + 1}의 적용 대상<select value={destinations[index]} onChange={event => setDestinations(current => current.map((value, i) => i === index ? event.target.value : value))}>
      <option value="">가져오지 않음</option><option value="new">새 공급행으로 추가</option>{rows.map(existing => <option key={existing.key} value={existing.key}>{existing.complexName} · {existing.typeName} · {existing.id ? `#${existing.id}` : '작성 중 새 행'}</option>)}</select></label>
      <StoredDataTable data={row} label={`입력 ${index + 1} 값`} readable /></li>)}</ol>
      <button type="button" disabled={!destinations.some(Boolean)} onClick={() => { try { onMerge(mergeSupplyImport(rows, input, destinations)); setText(''); setInput(null); setError('') } catch (cause) { setError(cause instanceof Error ? cause.message : '대응과 값을 확인하세요.') } }}>선택한 행을 변경안에 추가</button></>}
    {error && <p role="alert">{error}</p>}
  </fieldset></details>
}
