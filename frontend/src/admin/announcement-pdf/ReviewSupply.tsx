import { useEffect, useRef, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import { ComplexPicker } from '../management/ComplexPicker'
import { getManagementDetail } from '../management/api'
import { isRecord } from '../management/managementContract'
import { getMatchingHousingTypes } from '../ingest/supplyMatchingApi'
import { type Evidence } from './extraction'
import { cellMatches, createRowReview, pdfOrderChanges, pendingRow, pendingTarget, preserveCellChoice, remapTarget, shown, type Review, type ReviewFilter, type RowReview, type TargetReview } from './review'
import type { ComplexLabels, HousingCandidates } from './matching'
import { ReviewCells } from './ReviewCells'
import styles from './PdfCorrection.module.css'

// 표기 변경을 반영할 때만 공개 화면에 보일 이름을 미리 알려 준다.
function shownTypeName(row: RowReview) {
  const cell = row.cells.find(item => item.field === 'sourceHousingTypeName')
  return cell?.selected && typeof cell.value === 'string' ? cell.value : null
}

export function ReviewSupply({ review, workspace, housing, complexes, filter, empty, onChange, onEvidence }: { review: Review; workspace: JsonWorkspace; housing: HousingCandidates; complexes: ComplexLabels; filter: ReviewFilter; empty: string; onChange: (review: Review) => void; onEvidence: (evidence: Evidence) => void }) {
  function update(index: number, next: RowReview) { onChange({ ...review, rows: review.rows.map((row, at) => at === index ? next : row) }) }
  function mapping(index: number, destination: string, anchorKey: string) {
    const old = review.rows[index], anchor = workspace.supply.rows.find(row => row.key === anchorKey)
    const proposal = { ...old.proposal, destination, anchorKey, newComplex: undefined, complexId: anchor?.housingComplexId ?? null, housingTypeId: null, candidates: housing[anchor?.housingComplexId ?? 0] ?? [], needsConfirmation: true }
    const next = createRowReview(review.extraction, index, proposal, workspace)
    const oldCells = [...old.cells, ...old.targets.flatMap(target => target.cells)]
    const retain = (cell: typeof next.cells[number]) => { const previous = oldCells.find(item => item.path === cell.path); return preserveCellChoice(cell, previous) }
    next.cells = next.cells.map(retain); next.targets = next.targets.map(target => ({ ...target, cells: target.cells.map(retain) }))
    update(index, { ...next, include: old.include, reviewed: true })
  }
  // 같은 단지의 공급행은 하나의 기준 단지로 묶는다. 값은 그 단지의 첫 공급행이며 PNU·공급 구분을 가져온다.
  const linked = workspace.supply.rows.filter(item => !item.excluded && item.housingComplexId !== null)
  const anchors = linked.filter((item, at) => linked.findIndex(other => other.housingComplexId === item.housingComplexId) === at)
  const anchorFor = (key: string) => {
    const complexId = workspace.supply.rows.find(item => item.key === key)?.housingComplexId
    return anchors.find(item => item.housingComplexId === complexId)?.key ?? ''
  }
  return <>
    <div className={styles.sectionHeading}><div><h2>공급정보</h2><p>기존 값과 PDF에서 가져온 값을 비교하고, 반영할 항목을 고르세요.</p></div></div>
    {filter === 'all' && review.rows.filter(row => row.include).length > 1 && pdfOrderChanges(review, workspace) && <p className={styles.notice}>저장하면 공급행을 PDF 공급 표 순서({review.rows.flatMap((row, index) => row.include ? [review.extraction.supplyRows[index].typeName ?? `공급행 ${index + 1}`] : []).join(' → ')})로 표시합니다. PDF에 없는 기존 공급행은 그 뒤에 원래 순서대로 둡니다.</p>}
    {filter === 'all' && review.extraction.sharedCounts.map((group, index) => <div className={styles.shared} key={index}><strong>공통 모집 {group.count}세대</strong><span>{group.paths.map(path => review.extraction.supplyRows[Number(/\[(\d+)\]/.exec(path)?.[1])]?.typeName).join(' · ')} 전체의 모집수입니다. 각 행에 나누거나 중복 기입하지 않습니다.</span>{group.page && <Button data-design-system className={styles.quietButton} onClick={() => onEvidence({ page: group.page!, quote: group.quote, path: group.paths[0] })}>PDF {group.page}쪽 보기</Button>}</div>)}
    {review.rows.map((row, index) => {
      const source = review.extraction.supplyRows[index], current = workspace.supply.rows.find(item => item.key === row.proposal.destination)
      if (!rowMatches(row, filter)) return null
      return <section className={styles.supplyBlock} key={index} aria-label={`공급행 ${index + 1} 검토`} data-review-id={`row:${index}`} tabIndex={-1}>
        <header><div><h3>{source.complexName} · {source.typeName}</h3><p>{row.proposal.destination === 'new' ? '새 공급정보로 추가' : current ? `현재: ${current.complexName} · ${current.typeName}` : '수정할 기존 공급정보를 선택하세요'}</p></div><label><input type="checkbox" checked={row.include} onChange={event => update(index, { ...row, include: event.target.checked, reviewed: true })} />공급행 {index + 1} 적용</label></header>
        {pendingRow(row) && <div className={styles.pending}><p><strong>확인 필요</strong> {row.proposal.reason}</p><Button data-design-system className={styles.secondaryButton} disabled={!row.proposal.destination} onClick={() => update(index, { ...row, reviewed: true })}>{`공급행 ${index + 1} 대응 확인`}</Button></div>}
        {!row.include ? <p className={styles.hint}>이 행의 추출값을 제외합니다. 기존 공급정보는 유지합니다.</p> : <>
          <div className={styles.mapping}>
            <label>공급행 {index + 1} 저장할 위치<select value={row.proposal.destination} onChange={event => mapping(index, event.target.value, event.target.value === 'new' ? row.proposal.anchorKey : event.target.value)}><option value="">수정할 기존 항목을 선택하세요</option>{workspace.supply.rows.filter(item => !item.excluded).map(item => <option key={item.key} value={item.key}>{item.complexName} · {item.typeName} (#{item.id}) 수정</option>)}<option value="new">새 공급정보로 추가</option></select></label>
            {row.proposal.destination === 'new' && <div>
              {anchors.length > 0 && <label>공급행 {index + 1} 기준 단지<select value={anchorFor(row.proposal.anchorKey)} onChange={event => mapping(index, 'new', event.target.value)}><option value="">다른 단지 찾기</option>{anchors.map(item => <option key={item.key} value={item.key}>{complexes[item.housingComplexId!] ?? item.housingComplexName ?? `단지 #${item.housingComplexId}`}</option>)}</select></label>}
              {!row.proposal.anchorKey && <NewSupplyComplex row={row} index={index} onChange={next => update(index, next)} />}
            </div>}
          </div>
          <p className={styles.hint}>{row.proposal.reason}{source.values.constructedHouseholdCount != null ? ` · PDF 건설호수 ${String(source.values.constructedHouseholdCount)}세대` : ''}</p>
          {typeof shownTypeName(row) === 'string' && <p className={styles.hint}>{`저장하면 이 공고의 공개 화면에 '${String(shownTypeName(row))}'(으)로 표시됩니다. 다른 공고와 단지 주택형 이름은 바뀌지 않습니다.`}</p>}
          <ReviewCells housingTypes={row.proposal.candidates} cells={row.cells} scope={`공급행 ${index + 1}`} filter={filter} onChange={cells => update(index, { ...row, cells })} onEvidence={onEvidence} />
          <TargetBlock review={review} index={index} workspace={workspace} filter={filter} update={next => update(index, next)} onEvidence={onEvidence} />
        </>}
      </section>
    })}
    {!review.rows.length ? <p>추출된 공급행이 없습니다. 현재 공급정보를 유지합니다.</p> : !review.rows.some(row => rowMatches(row, filter)) && <p className={styles.hint}>{empty}</p>}
  </>
}

function targetMatches(target: TargetReview, filter: ReviewFilter) {
  return filter === 'all' || target.cells.some(cell => cellMatches(cell, filter)) || filter === 'pending' && pendingTarget(target)
}
function rowMatches(row: RowReview, filter: ReviewFilter) {
  if (filter === 'all') return true
  return row.include && (filter === 'pending' && pendingRow(row) || row.cells.some(cell => cellMatches(cell, filter)) || row.targets.some(target => targetMatches(target, filter)))
}

function TargetBlock({ review, index, workspace, filter, update, onEvidence }: { review: Review; index: number; workspace: JsonWorkspace; filter: ReviewFilter; update: (row: RowReview) => void; onEvidence: (evidence: Evidence) => void }) {
  const [expanded, setExpanded] = useState<number | null>(null)
  const row = review.rows[index], current = workspace.supply.rows.find(item => item.key === row.proposal.destination)
  const selected = expanded === null ? null : row.targets[expanded]
  const conditions = [...new Set(row.targets.map(target => target.cells.find(cell => cell.field === 'applicationCondition')?.value).filter(value => typeof value === 'string'))]
  if (!row.targets.some(target => targetMatches(target, filter))) return null
  return <>
    <h4>대상별 임대조건</h4>
    <div className={styles.targetScroll} role="region" aria-label={`공급행 ${index + 1} 임대조건 표`} tabIndex={0}><table className={styles.targetTable}>
      <caption>PDF 표의 대상 순서 · 금액 단위 원</caption><thead><tr><th>공급대상·순위</th><th>보증금</th><th>월 임대료</th><th>전환보증금</th><th>대조·수정</th></tr></thead>
      <tbody>{row.targets.map((target, at) => targetMatches(target, filter) && <tr key={at} data-excluded={target.destination === 'exclude'} data-pending={pendingTarget(target) || undefined} data-review-id={`target:${index}:${at}`} tabIndex={-1}>
        <th scope="row">{shown(target.cells.find(cell => cell.field === 'target')?.value)}<small>{target.cells.find(cell => cell.field === 'supplyRank')?.value as string ?? '순위 없음'}</small></th>
        {['rentalDeposit', 'monthlyRent', 'convertedDeposit'].map(field => { const cell = target.cells.find(item => item.field === field)!; return <td key={field}><strong>{shown(cell.value)}</strong><small>{cell.before != null && cell.before !== cell.value ? `현재 ${shown(cell.before)}` : cell.state === 'notApplicable' ? '해당 없음' : cell.state === 'same' ? '같음' : cell.selected ? '적용 선택' : '적용 안 함'}</small></td> })}
        <td><Button data-design-system className={styles.quietButton} aria-expanded={expanded === at} onClick={() => setExpanded(expanded === at ? null : at)}>{`조건 ${at + 1} ${target.destination === 'exclude' ? '제외됨' : '대조·수정'}`}</Button>{pendingTarget(target) && <small className={styles.pendingLabel}>확인 필요</small>}</td>
      </tr>)}</tbody>
    </table></div>
    {selected && expanded !== null && <section className={styles.target} aria-label={`공급행 ${index + 1} 조건 ${expanded + 1} 수정`}>
      <h4>조건 {expanded + 1} 대조·수정</h4>
      <label>공급행 {index + 1} 조건 {expanded + 1} 저장 방법<select value={selected.destination} onChange={event => update({ ...row, targets: row.targets.map((item, at) => at === expanded ? remapTarget(review, index, at, event.target.value, workspace) : item) })}><option value="">수정할 기존 항목을 선택하세요</option><option value="new">기존에 없는 임대조건으로 추가</option><option value="exclude">이번에는 반영하지 않음</option>{current?.targets.filter(item => !item.excluded).map(item => <option key={item.key} value={item.id ? String(item.id) : item.key}>{item.target} · {item.supplyRank ?? '순위 없음'} (#{item.id}) 수정</option>)}</select></label>
      {selected.destination === 'exclude' ? <p>이 조건은 적용하지 않습니다. 저장된 기존 조건은 유지합니다.</p> : <ReviewCells cells={selected.cells} scope={`공급행 ${index + 1} 조건 ${expanded + 1}`} onChange={cells => update({ ...row, targets: row.targets.map((item, at) => at === expanded ? { ...item, cells } : item) })} onEvidence={onEvidence} />}
    </section>}
    {conditions.length > 0 && <details className={styles.conditions}><summary>신청 조건 {conditions.length}종 확인</summary><ul>{conditions.map(condition => <li key={condition}>{condition}</li>)}</ul></details>}
  </>
}

function NewSupplyComplex({ row, index, onChange }: { row: RowReview; index: number; onChange: (row: RowReview) => void }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const request = useRef<AbortController | null>(null)
  useEffect(() => () => request.current?.abort(), [])
  async function choose(id: number, name: string) {
    request.current?.abort()
    const controller = new AbortController(); request.current = controller
    setBusy(true); setError('')
    try {
      const [detail, candidates] = await Promise.all([getManagementDetail('complexes', String(id), controller.signal), getMatchingHousingTypes(id, controller.signal)])
      if (controller.signal.aborted) return
      const address = detail.data.address
      const pnu = isRecord(address) && typeof address.pnu === 'string' ? address.pnu : ''
      onChange({ ...row, proposal: { ...row.proposal, complexId: id, candidates, housingTypeId: null,
        newComplex: { id, name, pnu, category: row.proposal.newComplex?.category ?? 'RESUPPLY' } },
        cells: row.cells.map(cell => cell.field === 'housingTypeId' ? { ...cell, value: null, selected: false, mode: 'keep', extracted: { ...cell.extracted, value: null } } : cell) })
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '단지를 불러오지 못했습니다.') }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  const selected = row.proposal.newComplex
  return <section aria-label={`공급행 ${index + 1} 새 단지 연결`}>
    <ComplexPicker disabled={busy} onSelect={item => void choose(item.id, item.name)} />
    {error && <p role="alert">{error}</p>}
    {selected && <><p>선택 단지: {selected.name}</p>
      <label>공급행 {index + 1} 소재지 PNU<input value={selected.pnu} onChange={event => onChange({ ...row, proposal: { ...row.proposal, newComplex: { ...selected, pnu: event.target.value } } })} /></label>
      <label>공급행 {index + 1} 공급 구분<select value={selected.category} onChange={event => onChange({ ...row, proposal: { ...row.proposal, newComplex: { ...selected, category: event.target.value as typeof selected.category } } })}><option value="NEW_SUPPLY">신규 공급</option><option value="RESUPPLY">재공급</option></select></label>
    </>}
  </section>
}
