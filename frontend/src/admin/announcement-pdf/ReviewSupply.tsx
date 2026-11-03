import { useState } from 'react'
import { Link } from 'react-router'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import { type Evidence } from './extraction'
import { cellMatches, chooseSubtype, complexGroups, createRowReview, housingTypePrice, linkedHousingType, linkedHousingTypeId, newRepresentative, pendingPrice, pendingSubtypes, destinationLabel, editCell, representativeSubtype, sharedGroupStorable, pdfOrderChanges, pendingRow, pendingTarget, preserveCellChoice, remapTarget, shown, type Cell, type Review, type ReviewFilter, type RowReview, type SubtypeChoice, type TargetReview } from './review'
import { ComplexDecisions } from './ComplexDecision'
import { missingHousingType, pickHousingType, type ComplexLabels, type HousingCandidates } from './matching'
import { ReviewCells } from './ReviewCells'
import { housingTypeLabel } from '../ingest/housingTypeLabel'
import { possibleLhDuplicates, unattachedLhRows } from '../supply/lhSupplyOwnership'
import styles from './PdfCorrection.module.css'

// 표기 변경을 반영할 때만 공개 화면에 보일 이름을 미리 알려 준다.
function shownTypeName(row: RowReview) {
  const cell = row.cells.find(item => item.field === 'sourceHousingTypeName')
  return cell?.selected && typeof cell.value === 'string' ? cell.value : null
}

export function ReviewSupply({ review, workspace, housing, complexes, filter, empty, onChange, onEvidence, editorHref, savesHousingTypes = true }: { editorHref?: string
  /** PDF로 공급정보 편집표만 채울 때는 false. 주택형 이름·가격을 저장하지 않으므로 그 안내와 가격 칸을 보이지 않는다. */
  savesHousingTypes?: boolean; review: Review; workspace: JsonWorkspace; housing: HousingCandidates; complexes: ComplexLabels; filter: ReviewFilter; empty: string; onChange: (review: Review) => void; onEvidence: (evidence: Evidence, cell?: Cell) => void }) {
  // 공급정보에 연결되지 않은 LH 공급 원본 행이 있으면 새 공급정보로 추가하지 않는다.
  const lhLocked = unattachedLhRows(workspace.supply).length > 0, duplicates = possibleLhDuplicates(workspace.supply)
  function update(index: number, next: RowReview) { onChange({ ...review, rows: review.rows.map((row, at) => at === index ? next : row) }) }
  function mapping(index: number, destination: string, anchorKey: string) {
    const old = review.rows[index], anchor = workspace.supply.rows.find(row => row.key === anchorKey)
    const candidates = housing[anchor?.housingComplexId ?? 0] ?? []
    // 직접 고른 위치에 맞춰 주택형 후보와 안내를 다시 계산한다. 처음 제안의 실패 문구를 남기지 않는다.
    const picked = pickHousingType(review.extraction, index, candidates)
    const proposal = { ...old.proposal, destination, anchorKey, newComplex: undefined, complexId: anchor?.housingComplexId ?? null,
      housingTypeId: picked.housingTypeId, candidates, needsConfirmation: true,
      reason: anchor ? `저장 위치를 직접 선택했습니다. · ${picked.reason}` : old.proposal.reason }
    const next = createRowReview(review.extraction, index, proposal, workspace)
    const oldCells = [...old.cells, ...old.targets.flatMap(target => target.cells)]
    const retain = (cell: typeof next.cells[number]) => { const previous = oldCells.find(item => item.path === cell.path); return preserveCellChoice(cell, previous) }
    next.cells = next.cells.map(retain); next.targets = next.targets.map(target => ({ ...target, cells: target.cells.map(retain) }))
    update(index, { ...next, include: old.include, reviewed: true })
  }
  // 저장할 위치는 추천 공급정보와 새 공급정보로 추가만 보여 준다. 단지 정하기·LH 원천행 맞추기로 정한 행도 함께 보인다.
  // 연결되지 않은 LH 행이 있어 새로 추가할 수 없는데 추천도 없으면, 같은 단지의 공급정보를 추천으로 넓혀 고를 수 있게 한다.
  const destinations = (row: RowReview) => workspace.supply.rows.filter(item => !item.excluded
    && (row.proposal.recommended.includes(item.key) || item.key === row.proposal.destination
      || lhLocked && !row.proposal.recommended.length && row.proposal.complexId !== null && item.housingComplexId === row.proposal.complexId))
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
    {filter === 'all' && review.extraction.checks.length > 0 && <div className={`${styles.pending} ${styles.checks}`} role="note" aria-label="검산 결과"><p><strong>검산 확인</strong> 합계·면적이 맞지 않는 항목입니다. 공고문과 대조하세요.</p><ul>{review.extraction.checks.map(check => <li key={check}>{check}</li>)}</ul></div>}
    {filter === 'all' && duplicates.length > 0 && <div className={styles.pending} role="note" aria-label="LH 원본과 중복 가능"><p><strong>LH 원본과 중복 가능</strong> LH 공급 원본으로 펼친 행이 있는 단지에 직접 추가한 행이 있습니다. 같은 주택형이면 공급정보 편집표의 ‘펼친 행으로 합치기’로 임대조건과 표기를 옮긴 뒤 지우세요.{editorHref && <> <Link to={editorHref}>공급정보 편집표 열기</Link></>}</p><ul>{duplicates.map(row => <li key={row.key}>{row.housingComplexName ?? row.complexName} · {row.typeName} (#{row.id})</li>)}</ul></div>}
    {filter === 'all' && <ComplexDecisions review={review} workspace={workspace} onChange={onChange} />}
    {filter === 'all' && review.extraction.sharedCounts.map((group, index) => <div className={styles.shared} key={index}><strong>공통 모집 {group.count}세대</strong><span>{sharedLabel(review, group.paths)}{group.paths.every(path => path.endsWith('.totalSupplyHouseholdCount')) && !sharedGroupStorable(review, index) ? sharedUnstorable(review, index) : ''}</span>{group.page && <Button data-design-system className={styles.quietButton} onClick={() => onEvidence({ page: group.page!, quote: group.quote, path: group.paths[0] })}>PDF {group.page}쪽 보기</Button>}</div>)}
    {/* 한 단지의 주택형을 한곳에서 대조하도록 PDF 단지 이름별로 묶는다. 저장 순서는 PDF 표 순서 그대로이고, 확인 필요 이동은 이 묶음 순서를 따른다. */}
    {complexGroups(review).map(group => {
      const visible = group.indexes.filter(index => rowMatches(review.rows[index], filter, savesHousingTypes))
      if (!visible.length) return null
      return <section className={styles.complexGroup} key={group.name} aria-label={`${group.name} 주택형`}><h3 className={styles.complexGroupTitle}>{group.name}<small>{`주택형 ${group.indexes.length}개${visible.length < group.indexes.length ? ` 중 ${visible.length}개 표시` : ''}`}</small></h3>{visible.map(index => supplyRow(review.rows[index], index))}</section>
    })}
    {!review.rows.length ? <p>추출된 공급행이 없습니다. 현재 공급정보를 유지합니다.</p> : !review.rows.some(row => rowMatches(row, filter, savesHousingTypes)) && <p className={styles.hint}>{empty}</p>}
  </>

  function supplyRow(row: RowReview, index: number) {
      const source = review.extraction.supplyRows[index], current = workspace.supply.rows.find(item => item.key === row.proposal.destination)
      const complexId = current?.housingComplexId ?? workspace.supply.rows.find(item => item.key === row.proposal.anchorKey)?.housingComplexId ?? row.proposal.newComplex?.id ?? row.proposal.complexId
      // 세부 타입 묶음 행은 세부 타입마다 새 주택형을 정하므로(SubtypeLinks) 행 단위 '맞는 주택형 없음'을 두지 않는다.
      const missing = row.include && !row.subtypes ? missingHousingType(complexId, row.proposal.candidates, String(row.cells.find(cell => cell.field === 'sourceHousingTypeName')?.value ?? source.typeName ?? ''), source.values.exclusiveArea, source.values.supplyArea) : null
      const missingLabel = missing ? `${missing.name || '형명 없음'} · 전용 ${missing.exclusiveArea}㎡` : ''
      // 새 주택형은 연결 주택형을 직접 수정·미연결로 둔 동안만 유지한다. 다른 주택형이나 기존 값을 고르면 풀린다.
      const keepsNewType = (cells: typeof row.cells) => cells.some(cell => cell.field === 'housingTypeId' && cell.mode === 'manual' && cell.value === null)
      const chooseNewType = () => update(index, { ...row, reviewed: true, newHousingType: true, cells: row.cells.map(cell => cell.field === 'housingTypeId' ? editCell(cell, '') : cell) })
      return <section className={styles.supplyBlock} key={index} aria-label={`공급행 ${index + 1} 검토`} data-review-id={`row:${index}`} tabIndex={-1}>
        <header><div><h4>{source.typeName ?? `공급행 ${index + 1}`}</h4><p>{row.proposal.destination === 'new' ? '새 공급정보로 추가' : current ? `현재: ${current.complexName} · ${current.typeName}` : '수정할 기존 공급정보를 선택하세요'}</p></div><label><input type="checkbox" checked={row.include} onChange={event => update(index, { ...row, include: event.target.checked, reviewed: true })} />공급행 {index + 1} 적용</label></header>
        {pendingRow(row) && <div className={styles.pending}><p><strong>확인 필요</strong> {row.proposal.reason}</p><Button data-design-system className={styles.secondaryButton} disabled={!row.proposal.destination} onClick={() => update(index, { ...row, reviewed: true })}>{`공급행 ${index + 1} 대응 확인`}</Button></div>}
        {!row.include ? <p className={styles.hint}>이 행의 추출값을 제외합니다. 기존 공급정보는 유지합니다.</p> : <>
          <div className={styles.mapping}>
            <label>공급행 {index + 1} 저장할 위치<select value={row.proposal.destination} onChange={event => mapping(index, event.target.value, event.target.value === 'new' ? row.proposal.anchorKey : event.target.value)}>{!row.proposal.destination && <option value="">추천 공급정보 중에서 고르세요</option>}{destinations(row).map(item => <option key={item.key} value={item.key}>추천: {destinationLabel(item)}</option>)}<option value="new" disabled={lhLocked}>{lhLocked ? '새 공급정보로 추가 (연결되지 않은 LH 행이 있어 불가)' : '새 공급정보로 추가'}</option></select></label>
            {/* 단지에 연결되지 않은 기존 공급행도 위 '단지 정하기'에서 단지를 정한다. */}
            {current && current.housingComplexId === null && <NewSupplyComplex row={row} index={index} onChange={next => update(index, next)} />}
            {row.proposal.destination === 'new' && <div>
              {anchors.length > 0 && <label>공급행 {index + 1} 기준 단지<select value={anchorFor(row.proposal.anchorKey)} onChange={event => mapping(index, 'new', event.target.value)}><option value="">다른 단지 찾기</option>{anchors.map(item => <option key={item.key} value={item.key}>{complexes[item.housingComplexId!] ?? item.housingComplexName ?? `단지 #${item.housingComplexId}`}</option>)}</select></label>}
              {!row.proposal.anchorKey && <NewSupplyComplex row={row} index={index} onChange={next => update(index, next)} />}
            </div>}
          </div>
          <p className={styles.hint}>{row.proposal.reason}{source.values.constructedHouseholdCount != null ? ` · PDF 건설호수 ${String(source.values.constructedHouseholdCount)}세대` : ''}</p>
          {savesHousingTypes && typeof shownTypeName(row) === 'string' && <p className={styles.hint}>{`저장하면 이 공고의 공개 화면에 '${String(shownTypeName(row))}'(으)로 표시됩니다. 연결한 단지 주택형 이름도 이 표기로 갱신합니다(끝 괄호는 떼고, 면적만 남으면 갱신하지 않음). 관리자가 고친 이름과 더 최근 공고의 이름은 유지합니다.`}</p>}
          {missing && <div className={styles.pending}><p><strong>맞는 주택형 없음</strong> 연결할 단지에 PDF 전용 {missing.exclusiveArea}㎡ 주택형이 없습니다.{row.newHousingType ? ` 저장하면 '${missingLabel}' 주택형을 이 단지에 추가합니다.` : ''}</p>
            {!row.newHousingType && <Button data-design-system className={styles.secondaryButton} disabled={!missing.name} onClick={chooseNewType}>PDF 형명·면적으로 새 주택형 추가</Button>}</div>}
          <ReviewCells housingTypes={row.proposal.candidates} cells={row.cells} scope={`공급행 ${index + 1}`} filter={filter}
            newType={missing && missing.name ? { label: missingLabel, selected: Boolean(row.newHousingType), onSelect: chooseNewType } : undefined}
            onChange={cells => update(index, { ...row, cells, newHousingType: Boolean(row.newHousingType && missing && keepsNewType(cells)),
              ...(linkedHousingTypeId({ ...row, cells }) !== linkedHousingTypeId(row) ? { priceTarget: undefined } : {}) })} onEvidence={onEvidence} />
          <SubtypeLinks row={row} index={index} update={next => update(index, next)} />
          <TargetBlock review={review} index={index} workspace={workspace} filter={filter} update={next => update(index, next)} onEvidence={onEvidence} />
          {/* 저장하면 주택형 가격이 바뀔 수 있으므로 어느 필터에서 저장해도 관리자가 고른 값을 보이게 한다. */}
          {savesHousingTypes && <HousingTypePrice row={row} index={index} update={next => update(index, next)} />}
        </>}
      </section>
  }
}


function targetMatches(target: TargetReview, filter: ReviewFilter) {
  return filter === 'all' || target.cells.some(cell => cellMatches(cell, filter)) || filter === 'pending' && pendingTarget(target)
}
function rowMatches(row: RowReview, filter: ReviewFilter, savesHousingTypes: boolean) {
  if (filter === 'all') return true
  return row.include && (filter === 'pending' && (pendingRow(row) || pendingSubtypes(row).length > 0 || savesHousingTypes && pendingPrice(row)) || row.cells.some(cell => cellMatches(cell, filter)) || row.targets.some(target => targetMatches(target, filter)))
}

function TargetBlock({ review, index, workspace, filter, update, onEvidence }: { review: Review; index: number; workspace: JsonWorkspace; filter: ReviewFilter; update: (row: RowReview) => void; onEvidence: (evidence: Evidence, cell?: Cell) => void }) {
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
        {['rentalDeposit', 'monthlyRent', 'convertedDeposit'].map(field => { const cell = target.cells.find(item => item.field === field)!; return <td key={field}><strong>{shown(cell.value)}</strong><small>{cell.before != null && cell.before !== cell.value ? `현재 ${shown(cell.before)}` : cell.state === 'notApplicable' ? '해당 없음' : cell.state === 'same' ? '같음' : cell.selected ? '적용 선택' : '적용 안 함'}</small>
          {cell.evidence && cell.value != null && <Button data-design-system className={styles.evidenceButton} aria-label={`PDF ${cell.evidence.page}쪽에서 조건 ${at + 1} ${cell.label} 보기`} title={cell.evidence.quote} onClick={() => onEvidence(cell.evidence!, cell)}>p.{cell.evidence.page}</Button>}</td> })}
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

/** 주택형 기본 임대조건(사용자 화면의 단지 가격·필터에 쓰는 값)을 이 공고의 어느 대상 금액으로 갱신할지 보여 주고 고르게 한다. */
function HousingTypePrice({ row, index, update }: { row: RowReview; index: number; update: (row: RowReview) => void }) {
  // 화면과 저장이 같은 주택형을 보도록 저장 기준(연결 주택형 칸 → 저장 위치의 주택형)으로 고른다.
  const type = linkedHousingType(row), price = housingTypePrice(row, type)
  if (!price.options.length || linkedHousingTypeId(row) === null && !newRepresentative(row)) return null
  const won = (value: number | null | undefined) => typeof value === 'number' ? `${value.toLocaleString('ko-KR')}원` : '미확인'
  const current = `보증금 ${won(type?.basicDeposit)} · 월 ${won(type?.basicMonthlyRent)}`
  const chosen = price.chosen === null ? null : price.options[price.chosen]
  const unchanged = chosen !== null && chosen.deposit === type?.basicDeposit && chosen.monthlyRent === type?.basicMonthlyRent
  return <section className={styles.housingTypePrice} aria-label={`공급행 ${index + 1} 주택형 가격`} data-review-id={`price:${index}`} tabIndex={-1}>
    <h4>주택형 가격</h4>
    <p className={styles.hint}>사용자 화면의 단지 가격·보증금 필터에 쓰는 주택형 기본 임대조건입니다. 지금: {current}</p>
    {price.confirm && <div className={styles.pending}><p><strong>확인 필요</strong> 지금 가격은 이전 PDF 보정에서 고른 공고문 값입니다. 이번 공고 값으로 바꾸려면 아래에서 대상을 고르세요.</p><Button data-design-system className={styles.secondaryButton} onClick={() => update({ ...row, priceTarget: null })}>{`공급행 ${index + 1} 지금 가격 유지`}</Button></div>}
    {/* 금액이 모두 같아도 관리자가 지금 값을 유지할 수 있게 고르는 칸을 둔다. */}
    <label>공급행 {index + 1} 주택형 가격으로 쓸 대상<select value={chosen === null ? '' : String(chosen.index)} onChange={event => update({ ...row, priceTarget: event.target.value === '' ? null : Number(event.target.value) })}>
      <option value="">지금 값 유지</option>
      {(price.same ? price.options.slice(0, 1) : price.options).map(option => <option key={option.index} value={option.index}>{price.same ? '모든 대상' : option.label} · 보증금 {won(option.deposit)} · 월 {won(option.monthlyRent)}</option>)}
    </select></label>
    <p>{chosen === null ? (price.unknown ? '이 주택형의 지금 가격을 불러오지 못해, 고르지 않으면 지금 값을 유지합니다.' : price.same || price.confirm ? '' : '대상별 금액이 다르거나 일부 대상의 금액이 없어, 고르지 않으면 지금 값을 유지합니다.') : unchanged ? '지금 값과 같습니다.' : `저장하면 보증금 ${won(chosen.deposit)} · 월 ${won(chosen.monthlyRent)}으로 갱신합니다. 더 최근 공고의 값은 유지합니다.`}</p>
  </section>
}

/**
 * 세부 타입 묶음 행(26A/B/C)이 세부 타입마다 연결할 주택형. 전용면적으로 하나가 정해지면 읽기만 하고, 같은 면적 후보가 둘 이상이거나
 * 면적이 없을 때만 고르게 한다. 정하지 못한 세부 타입은 확인 필요로 남아 저장을 막는다.
 */
function SubtypeLinks({ row, index, update }: { row: RowReview; index: number; update: (row: RowReview) => void }) {
  if (!row.subtypes) return null
  const candidates = row.proposal.candidates, representative = representativeSubtype(row)
  const typeName = (id: number) => { const type = candidates.find(item => item.id === id); return type ? housingTypeLabel(type, candidates) : `#${id}` }
  const result = (choice: SubtypeChoice) => typeof choice === 'number' ? typeName(choice) : choice === 'new' ? '새 주택형 추가' : choice === 'none' ? '연결 안 함'
    : row.proposal.complexId === null ? '단지를 정하면 연결합니다' : '연결할 주택형을 고르세요'
  const count = row.cells.find(cell => cell.field === 'totalSupplyHouseholdCount')
  return <section className={styles.subtypeLinks} aria-label={`공급행 ${index + 1} 세부 타입 연결`}>
    <h4>함께 모집하는 주택형</h4>
    <p className={styles.hint}>{`모집수 ${shown(count?.mode === 'keep' ? count.before : count?.value)}세대는 이 공급행 하나에 두고, 세부 타입 주택형을 모두 이 행에 연결합니다. 공통 모집수로 나누지 않습니다.`}</p>
    <ul>{row.subtypes.map((subtype, at) => {
      const selectable = subtype.options.length > 1 || subtype.exclusiveArea === null
      const options = subtype.exclusiveArea === null ? candidates.map(type => type.id) : subtype.options
      const label = `${subtype.name} · 전용 ${subtype.exclusiveArea ?? '미확인'}㎡`
      return <li key={at} data-review-id={`subtype:${index}:${at}`} tabIndex={-1} data-pending={subtype.choice === null || undefined}>
        {selectable ? <label>{`${label} → `}<select aria-label={`공급행 ${index + 1} 세부 타입 ${subtype.name} 연결 주택형`} value={subtype.choice === null ? '' : String(subtype.choice)}
          onChange={event => update(chooseSubtype(row, at, event.target.value === 'new' || event.target.value === 'none' ? event.target.value : Number(event.target.value)))}>
          {subtype.choice === null && <option value="" disabled>주택형을 고르세요</option>}
          {options.map(id => <option key={id} value={id}>{typeName(id)}</option>)}
          {subtype.exclusiveArea !== null && !subtype.options.length && <option value="new">새 주택형 추가</option>}
          <option value="none">연결 안 함</option>
        </select></label> : `${label} → ${result(subtype.choice)}`}
        {at === representative && <small className={styles.pendingDone}> 대표 주택형</small>}
        {subtype.choice === null && <small className={styles.pendingLabel}>확인 필요 · {result(null)}</small>}
      </li>
    })}</ul>
  </section>
}

// 단지는 위 '단지 정하기'에서 단지명마다 한 번 정한다. 여기서는 정한 단지와 이 행의 PNU·공급 구분만 확인한다.
function NewSupplyComplex({ row, index, onChange }: { row: RowReview; index: number; onChange: (row: RowReview) => void }) {
  const selected = row.proposal.newComplex
  if (!selected) return <p className={styles.hint}>위 단지 정하기에서 이 행의 단지를 정하세요.</p>
  return <section aria-label={`공급행 ${index + 1} 새 단지 연결`}>
    <p>선택 단지: {selected.name}</p>
    <label>공급행 {index + 1} 소재지 PNU<input value={selected.pnu} onChange={event => onChange({ ...row, proposal: { ...row.proposal, newComplex: { ...selected, pnu: event.target.value } } })} /></label>
    <label>공급행 {index + 1} 공급 구분<select value={selected.category} onChange={event => onChange({ ...row, proposal: { ...row.proposal, newComplex: { ...selected, category: event.target.value as typeof selected.category } } })}><option value="NEW_SUPPLY">신규 공급</option><option value="RESUPPLY">재공급</option></select></label>
  </section>
}

/** 공통 모집수를 저장하지 못하는 까닭. 세부 타입 묶음 행이 들었으면 그 행의 모집수를 직접 받는다. */
function sharedUnstorable(review: Review, index: number) {
  return review.rows.some(row => row.include && row.proposal.sharedIndex === index && row.subtypes)
    ? ' 세부 타입 묶음 행은 공통 모집 묶음에 넣을 수 없어 공통 모집수를 저장하지 않습니다. 세부 타입 묶음 행의 모집수는 직접 입력하고, 나머지 행의 모집수는 비워 둡니다.'
    : ' 기존 공급행 없이 새로 만드는 행끼리는 공통 모집수를 저장할 수 없어 각 행을 비워 둡니다.'
}
/** 공통 모집수가 묶은 대상을 읽기 쉽게 적는다. 대상별 묶음은 저장할 곳이 없어 각 대상의 모집수를 비워 둔다. */
function sharedLabel(review: Review, paths: string[]) {
  const at = paths.map(path => /^supplyRows\[(\d+)\](?:\.targets\[(\d+)\])?/.exec(path)).filter(match => match !== null)
  const row = review.extraction.supplyRows[Number(at[0]?.[1])]
  if (at.length && at.every(match => match[2] !== undefined)) {
    const targets = at.map(match => String(row?.targets[Number(match[2])]?.target ?? `대상 ${Number(match[2]) + 1}`))
    return `${row?.typeName ?? '공급행'}에서 ${targets.join(' · ')} 대상이 함께 쓰는 모집수입니다. 대상별 모집수는 비워 둡니다.`
  }
  return `${at.map(match => review.extraction.supplyRows[Number(match[1])]?.typeName).join(' · ')} 전체의 모집수입니다. 각 행에 나누거나 중복 기입하지 않습니다.`
}
