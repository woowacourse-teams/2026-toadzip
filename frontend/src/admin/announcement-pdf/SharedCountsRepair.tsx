import { useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonObject } from '../announcement-json/api'
import { isRecord } from '../management/managementContract'
import { normalizePath, readExtraction, readExtractionDocument, sharedCountIssues, type Evidence } from './extraction'
import styles from './PdfCorrection.module.css'

type Edit = { paths: string[]; count: string; page: string; quote: string; remove: boolean }
type Props = { text: string; onRepair: (text: string) => void; onEvidence: (item: Evidence) => void }

/** 교정은 화면 입력에만 반영한다. 저장된 워커 원본과 정상 묶음은 바꾸지 않는다. */
export function SharedCountsRepair({ text, onRepair, onEvidence }: Props) {
  const document = readExtractionDocument(text)
  if (!document.ok) return null
  const issues = sharedCountIssues(document.value)
  if (!issues.length) return null
  return <RepairForm key={text} document={document.value} issues={issues} onRepair={onRepair} onEvidence={onEvidence} />
}

function RepairForm({ document, issues, onRepair, onEvidence }: {
  document: JsonObject; issues: { index: number; message: string }[]
  onRepair: Props['onRepair']; onEvidence: Props['onEvidence']
}) {
  const groups = document.sharedCounts as unknown[]
  const [edits, setEdits] = useState<Record<number, Edit>>(() => Object.fromEntries(issues.map(({ index }) => {
    const group = isRecord(groups[index]) ? groups[index] : {}
    return [index, { paths: Array.isArray(group.paths) ? group.paths.map(String) : ['', ''], count: String(group.count ?? ''),
      page: String(group.page ?? ''), quote: typeof group.quote === 'string' ? group.quote : '', remove: false }]
  })))
  const options = pathOptions(document)
  const [error, setError] = useState('')
  const change = (index: number, patch: Partial<Edit>) => { setEdits(value => ({ ...value, [index]: { ...value[index], ...patch } })); setError('') }
  function confirm() {
    const next = { ...document, sharedCounts: groups.flatMap((group, index) => {
      const edit = edits[index]
      if (!edit) return [group]
      if (edit.remove) return []
      return [{ ...(isRecord(group) ? group : {}), paths: edit.paths.map(normalizePath),
        count: edit.count.trim() ? Number(edit.count) : null, page: edit.page.trim() ? Number(edit.page) : null, quote: edit.quote }]
    }) }
    const text = JSON.stringify(next), checked = readExtraction(text)
    if (!checked.ok) { setError(checked.error); return }
    onRepair(text)
  }
  return <section aria-label="공통 모집수 수정" className={styles.sharedRepair}>
    <h3>공통 모집수 {issues.length}개를 확인하세요</h3>
    <p>PDF를 보고 함께 쓰는 모집수의 위치·값·근거를 고치세요. 저장된 추출 원본은 유지하며, 수정한 입력만 검토에 사용합니다.</p>
    <p className={styles.hint}>PDF에서 모집수를 함께 쓰는 주택형 또는 대상을 고르세요. 대상별 모집수는 같은 공급행 안에서만 묶습니다.</p>
    {issues.map(({ index, message }) => {
      const edit = edits[index], original = isRecord(groups[index]) ? groups[index] : {}
      return <fieldset key={index}><legend>공통 모집수 {index + 1}</legend>
        <p className={styles.error}>{message}</p>
        <ul>{(Array.isArray(original.paths) ? original.paths : []).map((path, at) =>
          <li key={at}>{pathLabel(document, String(path))}</li>)}</ul>
        {typeof original.page === 'number' && original.page > 0 && <Button data-design-system className={styles.secondaryButton}
          onClick={() => onEvidence({ path: `sharedCounts[${index}]`, page: original.page as number, quote: String(original.quote ?? '') })}>PDF {original.page}쪽 근거 보기</Button>}
        <label className={styles.repairChoice}><input type="checkbox" checked={edit.remove} onChange={event => change(index, { remove: event.target.checked })} />이 묶음 해제 · 모집수는 개별 칸에서 직접 확인</label>
        {edit.remove ? <p className={styles.hint}>이 묶음의 모집수를 반영하지 않습니다. 각 행과 대상의 모집수는 검토 화면에서 직접 입력해야 합니다.</p> : <>
          {edit.paths.map((path, at) => <div key={at} className={styles.repairMember}>
            <label>함께 쓰는 주택형·대상 {index + 1}-{at + 1}<select value={normalizePath(path)} onChange={event => change(index, { paths: edit.paths.map((value, position) => position === at ? event.target.value : value) })}>
              <option value="">주택형 또는 대상 선택</option>
              {path && !options.some(group => group.options.some(option => option.path === normalizePath(path))) && <option value={path}>찾을 수 없는 기존 위치</option>}
              {options.map(group => <optgroup key={group.options[0].path} label={group.label}>{group.options.map(option => <option key={option.path} value={option.path}>{group.label} · {option.label}</option>)}</optgroup>)}
            </select><span className={styles.hint}>{path ? pathLabel(document, path) : '선택한 주택형·대상이 없습니다.'}</span></label>
            {edit.paths.length > 2 && <Button data-design-system className={styles.secondaryButton} onClick={() => change(index, { paths: edit.paths.filter((_, position) => position !== at) })} aria-label={`묶음 ${index + 1}의 위치 ${at + 1} 제거`}>제거</Button>}
          </div>)}
          <Button data-design-system className={styles.secondaryButton} onClick={() => change(index, { paths: [...edit.paths, ''] })}>주택형·대상 추가</Button>
          <label>공통 모집수 {index + 1}<input type="number" min="0" step="1" value={edit.count} onChange={event => change(index, { count: event.target.value })} /></label>
          <label>근거 쪽 {index + 1}<input type="number" min="1" step="1" value={edit.page} onChange={event => change(index, { page: event.target.value })} /></label>
          <label>근거 문구 {index + 1}<textarea value={edit.quote} onChange={event => change(index, { quote: event.target.value })} /></label>
        </>}
      </fieldset>
    })}
    {error && <p role="alert" className={styles.error}>{error}</p>}
    <Button data-design-system onClick={confirm}>수정한 묶음 확인</Button>
  </section>
}

function pathLabel(document: JsonObject, path: string) {
  const match = /^supplyRows\[(\d+)\](?:\.targets\[(\d+)\])?/.exec(normalizePath(path))
  const rows = isRecord(document.data) && Array.isArray(document.data.supplyRows) ? document.data.supplyRows : []
  const row = match && isRecord(rows[Number(match[1])]) ? rows[Number(match[1])] : null
  if (!isRecord(row)) return '대응하는 공급행을 찾지 못했습니다.'
  const reference = isRecord(row.complexReference) ? row.complexReference : {}
  const targets = Array.isArray(row.targets) ? row.targets : [], target = match?.[2] === undefined ? null : targets[Number(match[2])]
  return [reference.sourceComplexName, row.sourceHousingTypeName, isRecord(target) ? target.target : '공급행 전체 모집수'].filter(Boolean).join(' · ')
}

function pathOptions(document: JsonObject) {
  const rows = isRecord(document.data) && Array.isArray(document.data.supplyRows) ? document.data.supplyRows : []
  return rows.flatMap((row, index) => {
    if (!isRecord(row)) return []
    const path = `supplyRows[${index}].totalSupplyHouseholdCount`
    const targets = Array.isArray(row.targets) ? row.targets : []
    return [{ label: pathLabel(document, path).replace(' · 공급행 전체 모집수', ''), options: [
      { path, label: '공급행 전체 모집수' },
      ...targets.map((target, at) => ({ path: `supplyRows[${index}].targets[${at}].supplyHouseholdCount`, label: isRecord(target) ? String(target.target ?? `대상 ${at + 1}`) : `대상 ${at + 1}` })),
    ] }]
  })
}
