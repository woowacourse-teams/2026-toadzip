import { useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import { evidenceFor, type Evidence } from './extraction'
import { createRowReview, editCell, remapTarget, shown, type Review, type RowReview } from './review'
import type { HousingCandidates } from './matching'
import { ReviewCells } from './ReviewCells'
import styles from './PdfCorrection.module.css'

export function ReviewSupply({ review, workspace, housing, onChange, onEvidence }: { review: Review; workspace: JsonWorkspace; housing: HousingCandidates; onChange: (review: Review) => void; onEvidence: (evidence: Evidence) => void }) {
  function update(index: number, next: RowReview) { onChange({ ...review, rows: review.rows.map((row, at) => at === index ? next : row) }) }
  function mapping(index: number, destination: string, anchorKey: string) {
    const old = review.rows[index], anchor = workspace.supply.rows.find(row => row.key === anchorKey)
    const proposal = { ...old.proposal, destination, anchorKey, complexId: anchor?.housingComplexId ?? null, housingTypeId: null, candidates: housing[anchor?.housingComplexId ?? 0] ?? [], needsConfirmation: true }
    const next = createRowReview(review.extraction, index, proposal, workspace)
    const oldCells = [...old.cells, ...old.targets.flatMap(target => target.cells)]
    const retain = (cell: typeof next.cells[number]) => { const previous = oldCells.find(item => item.path === cell.path); return previous?.edited ? editCell(cell, previous.input ?? String(previous.value ?? '')) : cell }
    next.cells = next.cells.map(retain); next.targets = next.targets.map(target => ({ ...target, cells: target.cells.map(retain) }))
    update(index, next)
  }
  return <>
    <div className={styles.sectionHeading}><div><h2>공급정보</h2><p>행 대응을 확인한 뒤 PDF 임대조건 표와 대조하세요.</p></div><span>블록 대조 {review.rows.filter(row => row.include && row.blockConfirmed).length} / {review.rows.filter(row => row.include).length}</span></div>
    {review.extraction.sharedCounts.map((group, index) => <div className={styles.shared} key={index}><strong>공통 모집 {group.count}세대</strong><span>{group.paths.map(path => review.extraction.supplyRows[Number(/\[(\d+)\]/.exec(path)?.[1])]?.typeName).join(' · ')} 전체의 모집수입니다. 각 행에 나누거나 중복 기입하지 않습니다.</span>{group.page && <Button data-design-system className={styles.quietButton} onClick={() => onEvidence({ page: group.page!, quote: group.quote, path: group.paths[0] })}>{group.page}쪽 근거</Button>}</div>)}
    {review.rows.map((row, index) => {
      const source = review.extraction.supplyRows[index], current = workspace.supply.rows.find(item => item.key === row.proposal.destination)
      const evidence = evidenceFor(review.extraction.evidence, `supplyRows[${index}]`)
      return <section className={styles.supplyBlock} key={index} aria-label={`공급행 ${index + 1} 검토`}>
        <header><div><h3>{source.complexName} · {source.typeName}</h3><p>{row.proposal.destination === 'new' ? '새 공급행 추가' : current ? `현재: ${current.complexName} · ${current.typeName}` : '기존 행 대응 필요'}</p></div><label><input type="checkbox" checked={row.include} onChange={event => update(index, { ...row, include: event.target.checked })} />공급행 {index + 1} 적용</label></header>
        {!row.include ? <p className={styles.hint}>이 행의 추출값을 제외합니다. 기존 공급정보는 유지합니다.</p> : <>
          <div className={styles.mapping}>
            <label>공급행 {index + 1} 적용 대상<select value={row.proposal.destination} onChange={event => mapping(index, event.target.value, event.target.value === 'new' ? row.proposal.anchorKey : event.target.value)}><option value="">대응 확인 필요</option>{workspace.supply.rows.filter(item => !item.excluded).map(item => <option key={item.key} value={item.key}>{item.complexName} · {item.typeName} (#{item.id})</option>)}<option value="new">새 공급행 추가</option></select></label>
            {row.proposal.destination === 'new' && <label>공급행 {index + 1} 기준 단지<select value={row.proposal.anchorKey} onChange={event => mapping(index, 'new', event.target.value)}><option value="">기존 단지 행 선택</option>{workspace.supply.rows.filter(item => !item.excluded).map(item => <option key={item.key} value={item.key}>{item.complexName} · {item.typeName}</option>)}</select></label>}
            <label>공급행 {index + 1} 연결 주택형<select value={row.proposal.housingTypeId ?? ''} onChange={event => update(index, { ...row, confirmed: false, blockConfirmed: false, proposal: { ...row.proposal, housingTypeId: event.target.value ? Number(event.target.value) : null } })}><option value="">주택형 미연결</option>{row.proposal.candidates.map(type => <option key={type.id} value={type.id}>{type.name} · 전용 {type.exclusiveArea}㎡ · 공급 {type.supplyArea ?? '미확인'}㎡{type.householdCount != null ? ` · ${type.householdCount}세대` : ''}</option>)}</select></label>
          </div>
          <p className={styles.hint}>{row.proposal.reason}{source.values.constructedHouseholdCount != null ? ` · PDF 건설호수 ${String(source.values.constructedHouseholdCount)}세대` : ''}</p>
          <label className={styles.confirm}><input type="checkbox" checked={row.confirmed} onChange={event => update(index, { ...row, confirmed: event.target.checked })} />공급행 {index + 1}의 행 대응을 확인함</label>
          <ReviewCells cells={row.cells} scope={`공급행 ${index + 1}`} onChange={cells => update(index, { ...row, cells, blockConfirmed: false })} onEvidence={onEvidence} />
          <h4>대상별 임대조건</h4>
          <TargetBlock review={review} index={index} workspace={workspace} update={next => update(index, next)} onEvidence={onEvidence} />
          <div className={styles.blockConfirm}>{evidence && <Button data-design-system className={styles.quietButton} onClick={() => onEvidence(evidence)}>{evidence.page}쪽 표 보기</Button>}<label><input type="checkbox" checked={row.blockConfirmed} onChange={event => update(index, { ...row, blockConfirmed: event.target.checked })} />공급행 {index + 1} 임대조건을 PDF와 대조함</label></div>
        </>}
      </section>
    })}
    {!review.rows.length && <p>추출된 공급행이 없습니다. 현재 공급정보를 유지합니다.</p>}
  </>
}

function TargetBlock({ review, index, workspace, update, onEvidence }: { review: Review; index: number; workspace: JsonWorkspace; update: (row: RowReview) => void; onEvidence: (evidence: Evidence) => void }) {
  const [expanded, setExpanded] = useState<number | null>(null)
  const row = review.rows[index], current = workspace.supply.rows.find(item => item.key === row.proposal.destination)
  const selected = expanded === null ? null : row.targets[expanded]
  const conditions = [...new Set(row.targets.map(target => target.cells.find(cell => cell.field === 'applicationCondition')?.value).filter(value => typeof value === 'string'))]
  return <>
    <div className={styles.targetScroll} role="region" aria-label={`공급행 ${index + 1} 임대조건 표`} tabIndex={0}><table className={styles.targetTable}>
      <caption>PDF 표의 대상 순서 · 금액 단위 원</caption><thead><tr><th>공급대상·순위</th><th>보증금</th><th>월 임대료</th><th>전환보증금</th><th>대조·수정</th></tr></thead>
      <tbody>{row.targets.map((target, at) => <tr key={at} data-excluded={target.destination === 'exclude'}>
        <th scope="row">{shown(target.cells.find(cell => cell.field === 'target')?.value)}<small>{target.cells.find(cell => cell.field === 'supplyRank')?.value as string ?? '순위 없음'}</small></th>
        {['rentalDeposit', 'monthlyRent', 'convertedDeposit'].map(field => { const cell = target.cells.find(item => item.field === field)!; return <td key={field}><strong>{shown(cell.value)}</strong><small>{cell.before != null && cell.before !== cell.value ? `현재 ${shown(cell.before)}` : cell.state === 'notApplicable' ? '해당 없음' : cell.state === 'same' ? '같음' : cell.selected ? '적용 선택' : '적용 안 함'}</small></td> })}
        <td><Button data-design-system className={styles.quietButton} aria-expanded={expanded === at} onClick={() => setExpanded(expanded === at ? null : at)}>{`조건 ${at + 1} ${target.destination === 'exclude' ? '제외됨' : '대조·수정'}`}</Button></td>
      </tr>)}</tbody>
    </table></div>
    {selected && expanded !== null && <section className={styles.target} aria-label={`공급행 ${index + 1} 조건 ${expanded + 1} 수정`}>
      <h4>조건 {expanded + 1} 대조·수정</h4>
      <label>공급행 {index + 1} 조건 {expanded + 1} 적용 대상<select value={selected.destination} onChange={event => update({ ...row, blockConfirmed: false, targets: row.targets.map((item, at) => at === expanded ? remapTarget(review, index, at, event.target.value, workspace) : item) })}><option value="">대응 확인 필요</option><option value="new">새 조건 추가</option><option value="exclude">이번 보정에서 제외</option>{current?.targets.filter(item => !item.excluded).map(item => <option key={item.key} value={item.id!}>{item.target} · {item.supplyRank ?? '순위 없음'} (#{item.id})</option>)}</select></label>
      {selected.destination === 'exclude' ? <p>이 조건은 적용하지 않습니다. 저장된 기존 조건은 유지합니다.</p> : <ReviewCells cells={selected.cells} scope={`공급행 ${index + 1} 조건 ${expanded + 1}`} onChange={cells => update({ ...row, blockConfirmed: false, targets: row.targets.map((item, at) => at === expanded ? { ...item, cells } : item) })} onEvidence={onEvidence} />}
    </section>}
    {conditions.length > 0 && <details className={styles.conditions}><summary>신청 조건 {conditions.length}종 확인</summary><ul>{conditions.map(condition => <li key={condition}>{condition}</li>)}</ul></details>}
  </>
}
