import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import { type Evidence } from './extraction'
import { housingTypeNameOf, needsComplex, chooseSubtype, sameTypeNote, sameTypeSubtypes, complexDecisionCount, housingTypePrice, pendingItems, linkedHousingType, linkedHousingTypeId, newRepresentative, editCell, representativeSubtype, pdfOrderChanges, pendingRow, pendingTarget, remapTarget, shown, shownMoney, assembledCount, autoHousingType, expectedHousingType, leftoverRows, type Cell, type Review, type ReviewFilter, type RowReview, type SubtypeChoice } from './review'
import { ComplexDecisions } from './ComplexDecision'
import { DestinationChooser } from './DestinationChooser'
import { lhCrossCheck, supersedeNote, type ComplexLabels, type HousingCandidates } from './matching'
import { SupplyRowList } from './SupplyRowList'
import { ComplexTypesPanel } from './ComplexTypesPanel'
import { ReviewCells } from './ReviewCells'
import { supplyView, targetMatches } from './supplyView'
import { housingTypeLabel } from '../ingest/housingTypeLabel'
import { possibleLhDuplicates } from '../supply/lhSupplyOwnership'
import styles from './PdfCorrection.module.css'

// 표기 변경을 반영할 때만 공개 화면에 보일 이름을 미리 알려 준다.
function shownTypeName(row: RowReview) {
  const cell = row.cells.find(item => item.field === 'sourceHousingTypeName')
  return cell?.selected && typeof cell.value === 'string' ? cell.value : null
}

export function ReviewSupply({ review, workspace, housing, complexes, filter, empty, onChange, onEvidence, editorHref, warnings = [], savesHousingTypes = true, selected, onSelect, onJump }: { editorHref?: string; warnings?: string[]
  /** 검토 항목(data-review-id)으로 이동한다. 상태 배지와 단지 정하기 안내가 쓴다. */
  onJump?: (target: string) => void
  /** 편집 영역에 보일 공급행 번호. 확인 필요 이동·오류 이동이 이 값을 바꾼다. */
  selected: number; onSelect: (index: number) => void
  /** PDF로 공급정보 편집표만 채울 때는 false. 주택형 이름·가격을 저장하지 않으므로 그 안내와 가격 칸을 보이지 않는다. */
  savesHousingTypes?: boolean; review: Review; workspace: JsonWorkspace; housing: HousingCandidates; complexes: ComplexLabels; filter: ReviewFilter; empty: string; onChange: (review: Review) => void; onEvidence: (evidence: Evidence, cell?: Cell) => void }) {
  const duplicates = possibleLhDuplicates(workspace.supply)
  // 주택형 패널은 편집 상태를 갖지 않아 닫았다 열어도 고른 값이 그대로다.
  const [panelOpen, setPanelOpen] = useState(false), [noticesChoice, setNoticesOpen] = useState<boolean | null>(null)
  const pending = pendingItems(review, savesHousingTypes, workspace)
  const { group, shown, current, order } = supplyView(review, selected, filter, pending)
  // 공급정보는 위 고정 막대에서 고른 단지 하나씩 보인다. 그 단지의 행만 목록·편집 영역에 둔다.
  // 편집을 마친 자리에서 바로 다음 행으로 넘어가게 한다. 좁은 화면에서는 위 이동 막대가 고정되지 않는다.
  const following = current >= 0 ? order[order.indexOf(current) + 1] : undefined
  const visible = (index: number) => shown.includes(index)
  function update(index: number, next: RowReview) { onChange({ ...review, rows: review.rows.map((row, at) => at === index ? next : row) }) }
  // 지금 단지의 PDF 행이 가리키지 않아 그대로 남을 기존 공급행. 전용면적을 몰라 맞추지 못한 통합행이면 두 번 셀 수 있다.
  const groupComplexes = new Set((group?.indexes ?? []).flatMap(index => workspace.supply.rows.filter(row => [review.rows[index].proposal.anchorKey, review.rows[index].proposal.destination].includes(row.key)).map(row => row.complexName)))
  const leftovers = leftoverRows(review, workspace).filter(row => groupComplexes.has(row.complexName)), excluded = new Set(review.excludedRows ?? [])
  const toggleLeftover = (key: string) => onChange({ ...review, excludedRows: excluded.has(key) ? [...excluded].filter(item => item !== key) : [...excluded, key] })
  const shownChecks = [...filter === 'all' ? review.extraction.checks : [], ...warnings]
  const inGroup = (paths: string[]) => !group || paths.some(path => group.indexes.includes(Number(/^supplyRows\[(\d+)\]/.exec(path)?.[1] ?? -1)))
  // 단지를 정해야 저장할 수 있으므로 어느 필터에서도 단지 정하기를 숨기지 않는다.
  const decisions = complexDecisionCount(review, workspace, group?.indexes)
  const ordered = filter === 'all' && review.rows.filter(row => row.include).length > 1 && pdfOrderChanges(review, workspace)
  const noticesOpen = noticesChoice ?? false
  const notices: { key: string; label: string; tone: 'info' | 'warning'; body: ReactNode }[] = [
    ...shownChecks.length ? [{ key: 'checks', label: `검산 확인 ${shownChecks.length}건`, tone: 'warning' as const, body: <div className={`${styles.pending} ${styles.checks}`} role="note" aria-label="검산 결과"><p><strong>검산 확인</strong> 합계·면적·원문 대조가 맞지 않는 항목입니다. 공고문과 대조하세요.</p><ul>{shownChecks.map(check => <li key={check}>{check}</li>)}</ul></div> }] : [],
    ...filter === 'all' && duplicates.length ? [{ key: 'duplicates', label: `LH 원본과 중복 가능 ${duplicates.length}행`, tone: 'warning' as const, body: <div className={styles.pending} role="note" aria-label="LH 원본과 중복 가능"><p><strong>LH 원본과 중복 가능</strong> LH 공급 원본으로 펼친 행이 있는 단지에 직접 추가한 행이 있습니다. 같은 주택형이면 공급정보 편집표의 ‘펼친 행으로 합치기’로 임대조건과 표기를 옮긴 뒤 지우세요.{editorHref && <> <Link to={editorHref}>공급정보 편집표 열기</Link></>}</p><ul>{duplicates.map(row => <li key={row.key}>{row.housingComplexName ?? row.complexName} · {row.typeName} (#{row.id})</li>)}</ul></div> }] : [],
    // 공통 모집 묶음은 공고 전체에 걸쳐 있으므로 지금 단지의 행이 든 묶음만 보인다.
    ...filter === 'all' ? review.extraction.sharedCounts.flatMap((group, index) => {
      if (!inGroup(group.paths)) return []
      const reserve = group.paths.every(path => path.endsWith('.reserveCount'))
      const label = `공통 ${reserve ? '예비자 모집' : '모집'} ${group.count}${reserve ? '명' : '세대'}`
      return [{ key: `shared:${index}`, label, tone: 'info' as const, body: <div className={styles.shared}><strong>{label}</strong><span>{sharedLabel(review, group.paths)}{group.paths.every(path => path.endsWith('.totalSupplyHouseholdCount')) ? sharedUnstored : ''}</span>{group.page && <Button data-design-system className={styles.quietButton} onClick={() => onEvidence({ page: group.page!, quote: group.quote, path: group.paths[0] })}>PDF {group.page}쪽 보기</Button>}</div> }]
    }) : [],
    ...leftovers.length ? [{ key: 'leftovers', label: `PDF에 없는 기존 공급행 ${leftovers.length}행`, tone: 'warning' as const, body: <div className={styles.pending} role="note" aria-label="PDF에 없는 기존 공급행">
      <p><strong>PDF에 없는 기존 공급행</strong> PDF 공급행과 전용면적이 맞지 않아 그대로 두는 행입니다. 전용면적을 모르는 통합행처럼 PDF 공급행과 같은 세대를 담은 행이면 두 번 세니 이번 보정에서 제외하세요.</p>
      <ul>{leftovers.map(row => <li key={row.key}>{`#${String(row.id)} ${row.typeName} · ${row.householdCount ?? '모집수 미확인'}${row.householdCount == null ? '' : '세대'} · ${row.exclusiveArea == null ? '면적 미확인' : `전용 ${row.exclusiveArea}㎡`}`}
        <Button data-design-system className={styles.quietButton} aria-pressed={excluded.has(row.key)} onClick={() => toggleLeftover(row.key)}>{excluded.has(row.key) ? `#${String(row.id)} 제외 취소` : `#${String(row.id)} 이번 보정에서 제외`}</Button></li>)}</ul></div> }] : [],
    ...ordered ? [{ key: 'order', label: '저장하면 PDF 표 순서로 표시', tone: 'info' as const, body: <p className={styles.notice}>저장하면 공급행을 PDF 공급 표 순서({review.rows.flatMap((row, index) => row.include ? [review.extraction.supplyRows[index].typeName ?? `공급행 ${index + 1}`] : []).join(' → ')})로 표시합니다. PDF에 없는 기존 공급행은 그 뒤에 원래 순서대로 둡니다.</p> }] : [],
  ]
  return <>
    {/* 탭 이름과 같아 화면에서는 숨기고, 보조기술의 구역 제목으로만 남긴다. */}
    <h2 className={styles.srOnly}>공급정보</h2>
    {/* 안내(표시 순서·검산·중복 가능·단지 정하기·공통 모집)는 한 줄 요약으로 접어 공급행 목록과 편집 영역이 화면 위쪽에 남게 한다.
        워커 경고는 확인할 행으로 잡히지 않으므로 접혀 있어도 건수를 요약에 보인다. */}
    {notices.length > 0 && <div className={styles.noticeStrip} role="group" aria-label="공급정보 안내">
      <div className={styles.noticeSummary}><Button data-design-system className={styles.quietButton} aria-expanded={noticesOpen} aria-controls="supply-notices" onClick={() => setNoticesOpen(!noticesOpen)}>{noticesOpen ? '안내 접기' : '안내 펼치기'}</Button>
        <ul aria-label="안내 요약">{notices.map(notice => <li key={notice.key} data-tone={notice.tone}>{notice.label}</li>)}</ul></div>
      {/* 접어도 지우지 않아 단지 정하기 중 입력이 남는다. */}
      <div id="supply-notices" className={styles.noticeBody} hidden={!noticesOpen}>{notices.map(notice => <div key={notice.key}>{notice.body}</div>)}</div>
    </div>}
    {/* 한 단지의 주택형을 한곳에서 대조하도록 PDF 단지 이름별로 묶는다. 저장 순서는 PDF 표 순서 그대로이고, 확인 필요 이동은 이 묶음 순서를 따른다. */}
    {/* 단지 정하기는 안내가 아니라 저장 전에 꼭 할 결정이라 접지 않고 단지 영역 맨 위에 둔다. */}
    {decisions > 0 && <ComplexDecisions review={review} workspace={workspace} only={group?.indexes} onChange={onChange} />}
    {review.rows.length > 0 && <div className={styles.supplyLayout} data-panel={panelOpen}>
      <SupplyRowList review={review} workspace={workspace} pending={pending} selected={current} visible={visible} onSelect={onSelect} onJump={onJump} />
      <div className={styles.rowEditor}>{current >= 0 && supplyRow(review.rows[current], current)}
        {following !== undefined && <div className={styles.rowFooter}><Button data-design-system className={styles.secondaryButton} onClick={() => onSelect(following)}>{`다음 공급행 · ${following + 1}. ${review.extraction.supplyRows[following].typeName ?? '주택형 미확인'}`}</Button></div>}</div>
      {panelOpen && current >= 0 && typesPanel(current)}
    </div>}
    {!review.rows.length ? <p>추출된 공급행이 없습니다. 현재 공급정보를 유지합니다.</p> : !shown.length && <p className={styles.hint}>{empty}</p>}
  </>

  function complexOf(row: RowReview) {
    const current = workspace.supply.rows.find(item => item.key === row.proposal.destination)
    return current?.housingComplexId ?? workspace.supply.rows.find(item => item.key === row.proposal.anchorKey)?.housingComplexId ?? row.proposal.newComplex?.id ?? row.proposal.complexId
  }
  function typesPanel(index: number) {
    const row = review.rows[index], complexId = complexOf(row)
    const name = complexId === null ? row.proposal.newComplex?.name ?? '단지 미정' : complexes[complexId] ?? workspace.supply.rows.find(item => item.housingComplexId === complexId)?.housingComplexName ?? `단지 #${complexId}`
    const link = (typeId: number) => update(index, { ...row, reviewed: true, priceTarget: undefined,
      cells: row.cells.map(cell => cell.field === 'housingTypeId' ? editCell(cell, String(typeId)) : cell) })
    return <ComplexTypesPanel complexName={name} complexId={complexId} review={review} workspace={workspace} types={complexId === null ? [] : housing[complexId] ?? row.proposal.candidates}
      rowIndex={index} linkedId={linkedHousingTypeId(row) ?? expectedHousingType(row)?.id ?? null} canLink={row.include && !row.subtypes}
      onLink={link} onClose={() => setPanelOpen(false)} />
  }
  function supplyRow(row: RowReview, index: number) {
      const source = review.extraction.supplyRows[index]
      const superseded = supersedeNote(workspace.supply.rows, row.proposal.supersedes)
      // LH 공급 원본 행은 저장할 곳과 같은 전용면적이라 붙은 줄을 검산 정보로만 보인다. 저장하면 함께 제외할 옛 행에 붙은 줄도 이 행의 몫이다.
      const lh = row.proposal.destination === 'new' ? null : lhCrossCheck(workspace.supply.lhSupplyRows ?? [], [row.proposal.destination, ...row.proposal.supersedes ?? []], assembledCount(row))
      const expected = expectedHousingType(row)
      return <section className={styles.supplyBlock} key={index} aria-label={`공급행 ${index + 1} 검토`} data-review-id={`row:${index}`} tabIndex={-1}>
        <header><h3>{`${index + 1}. ${source.typeName ?? '주택형 미확인'}`}</h3><div className={styles.actions}><Button data-design-system className={styles.quietButton} aria-expanded={panelOpen} onClick={() => setPanelOpen(open => !open)}>{panelOpen ? '단지 주택타입 닫기' : '단지 주택타입 보기'}</Button><label className={styles.inlineCheck}><input type="checkbox" checked={row.include} onChange={event => update(index, { ...row, include: event.target.checked, reviewed: true })} />공급행 {index + 1} 적용</label></div></header>
        {/* 편집 영역은 저장할 곳 → 값 비교 → 세부 타입 → 대상별 임대조건 → 주택형 가격 순서의 같은 모양 구역으로 나눈다. */}
        <div className={styles.rowPart}><h4>저장할 곳</h4>
          {/* 자동 대응 근거는 접어 두고, 사람이 확인해야 할 대응(GH 틀·블록 번호나 주소 한쪽만 맞은 단지)만 확인을 받는다. */}
          {pendingRow(row) && <div className={styles.pending}><p><strong>확인 필요</strong> 저장할 곳이 맞는지 확인하세요.</p>
            <div className={styles.actions}><Button data-design-system className={styles.secondaryButton} onClick={() => update(index, { ...row, reviewed: true })}>{`공급행 ${index + 1} 대응 확인`}</Button></div></div>}
          {!row.include ? <p className={styles.hint}>이 행의 추출값을 제외합니다. 기존 공급정보는 유지합니다.</p> : <>
            <DestinationChooser review={review} index={index} workspace={workspace} housing={housing} onChange={next => update(index, next)} />
            {superseded && <p className={styles.hint} role="note">{superseded}</p>}

            {/* 단지에 연결되지 않은 기존 공급행도 위 '단지 정하기'에서 단지를 정한다. */}
            {needsComplex(row, workspace) && <NewSupplyComplex row={row} index={index} onChange={next => update(index, next)} onJump={onJump} />}
            {/* LH 공급 API는 참고만 하므로 저장할 곳 문장 옆에 두지 않고 대응 근거와 함께 접어 둔다. */}
            {(row.proposal.reason || lh) && <details className={styles.conditions}><summary>{lh ? '자동 대응 근거·LH 참고' : '자동 대응 근거'}</summary>
              {row.proposal.reason && <p className={styles.hint}>{row.proposal.reason}</p>}
              {lh && <p className={styles.hint} role="note" aria-label={`공급행 ${index + 1} LH 검산`}>{`LH 참고: ${lh.text}`}</p>}</details>}
          </>}
        </div>
        {row.include && <>
          <div className={styles.rowPart}><h4>값 비교</h4>
            {source.values.constructedHouseholdCount != null && <p className={styles.hint}>{`PDF 건설호수 ${String(source.values.constructedHouseholdCount)}세대`}</p>}
            {savesHousingTypes && typeof shownTypeName(row) === 'string' && <p className={styles.hint}>{`저장하면 이 공고의 주택형(신청형)을 '${String(shownTypeName(row))}'(으)로 표시합니다. 단지 주택타입 이름은 바꾸지 않습니다(세부 타입의 주택타입이 면적마다 하나일 때만 그 이름으로 바꿈).`}</p>}
            {/* 주택형은 공고문 주택타입 이름으로 정한다(expectedHousingType). 저장할 때 그 주택형을, 없으면 그 이름으로 새로 만들어 보낸다. */}
            {autoHousingType(row) && complexOf(row) !== null && <p className={styles.hint}>{expected
              ? `연결 주택타입: 저장하면 '${expected.name}'(전용 ${expected.exclusiveArea}㎡)에 연결합니다.`
              : `연결 주택타입: 이 단지에 '${housingTypeNameOf(row)}' 주택타입이 없어 저장하면 새로 만듭니다.`}</p>}
            <ReviewCells housingTypes={row.proposal.candidates} otherHousingTypes={Object.values(housing).flat()} cells={row.cells} scope={`공급행 ${index + 1}`} filter={filter}
              onChange={cells => update(index, { ...row, cells,
                ...(linkedHousingTypeId({ ...row, cells }) !== linkedHousingTypeId(row) ? { priceTarget: undefined } : {}) })} onEvidence={onEvidence} />
          </div>
          <SubtypeLinks row={row} index={index} update={next => update(index, next)} />
          <TargetBlock review={review} index={index} workspace={workspace} filter={filter} update={next => update(index, next)} onEvidence={onEvidence} />
          {/* 저장하면 주택형 가격이 바뀔 수 있으므로 어느 필터에서 저장해도 관리자가 고른 값을 보이게 한다. */}
          {savesHousingTypes && <HousingTypePrice row={row} index={index} update={next => update(index, next)} />}
        </>}
      </section>
  }
}


function TargetBlock({ review, index, workspace, filter, update, onEvidence }: { review: Review; index: number; workspace: JsonWorkspace; filter: ReviewFilter; update: (row: RowReview) => void; onEvidence: (evidence: Evidence, cell?: Cell) => void }) {
  const [expanded, setExpanded] = useState<number | null>(null)
  const row = review.rows[index], current = workspace.supply.rows.find(item => item.key === row.proposal.destination)
  const selected = expanded === null ? null : row.targets[expanded]
  const conditions = [...new Set(row.targets.map(target => target.cells.find(cell => cell.field === 'applicationCondition')?.value).filter(value => typeof value === 'string'))]
  if (!row.targets.some(target => targetMatches(target, filter))) return null
  return <div className={styles.rowPart}>
    <h4>대상별 임대조건</h4>
    <div className={styles.targetScroll} role="region" aria-label={`공급행 ${index + 1} 임대조건 표`} tabIndex={0}><table className={styles.targetTable}>
      <caption>PDF 표의 대상 순서 · 금액 단위 원</caption><thead><tr><th>공급대상</th><th>보증금</th><th>월 임대료</th><th>전환보증금</th><th>대조·수정</th></tr></thead>
      <tbody>{row.targets.map((target, at) => targetMatches(target, filter) && <tr key={at} data-excluded={target.destination === 'exclude'} data-pending={pendingTarget(target) || undefined} data-review-id={`target:${index}:${at}`} tabIndex={-1}>
        <th scope="row">{shown(target.cells.find(cell => cell.field === 'target')?.value)}</th>
        {['rentalDeposit', 'monthlyRent', 'convertedDeposit'].map(field => { const cell = target.cells.find(item => item.field === field)!; return <td key={field}><strong>{shownMoney(cell.value)}</strong><small>{cell.before != null && cell.before !== cell.value ? `현재 ${shownMoney(cell.before)}` : cell.state === 'notApplicable' ? '해당 없음' : cell.state === 'same' ? '같음' : cell.selected ? '적용 선택' : '적용 안 함'}</small>
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
  </div>
}

/** 주택형 기본 임대조건(사용자 화면의 단지 가격·필터에 쓰는 값)을 이 공고의 어느 대상 금액으로 갱신할지 보여 주고 고르게 한다. */
function HousingTypePrice({ row, index, update }: { row: RowReview; index: number; update: (row: RowReview) => void }) {
  // 화면과 저장이 같은 주택형을 보도록 저장 기준(연결 주택형 칸 → 저장 위치의 주택형)으로 고른다.
  const type = linkedHousingType(row), price = housingTypePrice(row, type)
  if (!price.options.length || linkedHousingTypeId(row) === null && !newRepresentative(row) && !autoHousingType(row)) return null
  const won = (value: number | null | undefined) => typeof value === 'number' ? `${value.toLocaleString('ko-KR')}원` : '미확인'
  const current = `보증금 ${won(type?.basicDeposit)} · 월 ${won(type?.basicMonthlyRent)}`
  const chosen = price.chosen === null ? null : price.options[price.chosen]
  const unchanged = chosen !== null && chosen.deposit === type?.basicDeposit && chosen.monthlyRent === type?.basicMonthlyRent
  return <section className={styles.rowPart} aria-label={`공급행 ${index + 1} 주택타입 가격`} data-review-id={`price:${index}`} tabIndex={-1}>
    <h4>주택타입 가격</h4>
    <p className={styles.hint}>사용자 화면의 단지 가격·보증금 필터에 쓰는 주택타입 기본 임대조건입니다. 지금: {current}</p>
    {price.confirm && <div className={styles.pending}><p><strong>확인 필요</strong> 지금 가격은 이전 PDF 보정에서 고른 공고문 값입니다. 이번 공고 값으로 바꾸려면 아래에서 대상을 고르세요.</p><Button data-design-system className={styles.secondaryButton} onClick={() => update({ ...row, priceTarget: null })}>{`공급행 ${index + 1} 지금 가격 유지`}</Button></div>}
    {/* 금액이 모두 같아도 관리자가 지금 값을 유지할 수 있게 고르는 칸을 둔다. */}
    <label>공급행 {index + 1} 주택타입 가격으로 쓸 대상<select value={chosen === null ? '' : String(chosen.index)} onChange={event => update({ ...row, priceTarget: event.target.value === '' ? null : Number(event.target.value) })}>
      <option value="">지금 값 유지</option>
      {(price.same ? price.options.slice(0, 1) : price.options).map(option => <option key={option.index} value={option.index}>{price.same ? '모든 대상' : option.label} · 보증금 {won(option.deposit)} · 월 {won(option.monthlyRent)}</option>)}
    </select></label>
    <p>{chosen === null ? (price.unknown ? '이 주택타입의 지금 가격을 불러오지 못해, 고르지 않으면 지금 값을 유지합니다.' : price.same || price.confirm ? '' : '대상별 금액이 다르거나 일부 대상의 금액이 없어, 고르지 않으면 지금 값을 유지합니다.') : unchanged ? '지금 값과 같습니다.' : `저장하면 보증금 ${won(chosen.deposit)} · 월 ${won(chosen.monthlyRent)}으로 갱신합니다. 더 최근 공고의 값은 유지합니다.`}</p>
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
  const result = (choice: SubtypeChoice) => typeof choice === 'number' ? typeName(choice) : choice === 'new' ? '새 주택타입 추가' : choice === 'none' ? '연결 안 함'
    : row.proposal.complexId === null ? '단지를 정하면 연결합니다' : '연결할 주택타입을 고르세요'
  const count = row.cells.find(cell => cell.field === 'totalSupplyHouseholdCount')
  const sameType = sameTypeSubtypes(row)
  return <section className={`${styles.rowPart} ${styles.subtypeLinks}`} aria-label={`공급행 ${index + 1} 세부 타입 연결`}>
    <h4>함께 모집하는 주택타입</h4>
    <p className={styles.hint}>{`모집수 ${shown(count?.mode === 'keep' ? count.before : count?.value)}세대는 이 공급행 하나에 두고, 세부 타입 주택타입을 모두 이 행에 연결합니다. 공통 모집수로 나누지 않습니다.`}</p>
    <ul>{row.subtypes.map((subtype, at) => {
      const selectable = subtype.options.length > 1 || subtype.exclusiveArea === null || sameType.includes(at)
      const options = subtype.exclusiveArea === null ? candidates.map(type => type.id) : subtype.options
      const label = `${subtype.name} · 전용 ${subtype.exclusiveArea ?? '미확인'}㎡`
      return <li key={at} data-review-id={`subtype:${index}:${at}`} tabIndex={-1} data-pending={subtype.choice === null || sameType.includes(at) || undefined}>
        {selectable ? <label>{`${label} → `}<select aria-label={`공급행 ${index + 1} 세부 타입 ${subtype.name} 연결 주택타입`} value={subtype.choice === null ? '' : String(subtype.choice)}
          onChange={event => update(chooseSubtype(row, at, event.target.value === 'new' || event.target.value === 'none' ? event.target.value : Number(event.target.value)))}>
          {subtype.choice === null && <option value="" disabled>주택타입을 고르세요</option>}
          {options.map(id => <option key={id} value={id}>{typeName(id)}</option>)}
          {subtype.exclusiveArea !== null && !subtype.options.length && <option value="new">새 주택타입 추가</option>}
          <option value="none">연결 안 함</option>
        </select></label> : `${label} → ${result(subtype.choice)}`}
        {at === representative && <small className={styles.pendingDone}> 대표 주택타입</small>}
        {subtype.choice === null && <small className={styles.pendingLabel}>확인 필요 · {result(null)}</small>}
        {sameType.includes(at) && <small className={styles.pendingLabel}>확인 필요 · {sameTypeNote}</small>}
      </li>
    })}</ul>
  </section>
}

// 단지는 위 '단지 정하기'에서 단지명마다 한 번 정한다. 여기서는 정한 단지와 이 행의 PNU·공급 구분만 확인한다.
function NewSupplyComplex({ row, index, onChange, onJump }: { row: RowReview; index: number; onChange: (row: RowReview) => void; onJump?: (target: string) => void }) {
  const selected = row.proposal.newComplex
  if (!selected) return <p className={styles.hint}>이 행의 단지를 아직 정하지 않았습니다. {onJump && <Button data-design-system className={styles.quietButton} onClick={() => onJump('complex')}>단지 정하기로 이동</Button>}</p>
  return <section aria-label={`공급행 ${index + 1} 새 단지 연결`}>
    <p>선택 단지: {selected.name}</p>
    <label>공급행 {index + 1} 소재지 PNU<input value={selected.pnu} onChange={event => onChange({ ...row, proposal: { ...row.proposal, newComplex: { ...selected, pnu: event.target.value } } })} /></label>
    <label>공급행 {index + 1} 공급 구분<select value={selected.category} onChange={event => onChange({ ...row, proposal: { ...row.proposal, newComplex: { ...selected, category: event.target.value as typeof selected.category } } })}><option value="NEW_SUPPLY">신규 공급</option><option value="RESUPPLY">재공급</option></select></label>
  </section>
}

/** 단지가 다른 공급행끼리 함께 쓰는 행 모집수는 공통 모집 묶음을 새로 만들지 않아 저장하지 않는다. 같은 단지의 행은 공급행 하나로 접힌다. */
const sharedUnstored = ' 단지가 다른 공급행끼리 함께 쓰는 모집수라 공급행 모집수로 정하지 않습니다. 필요하면 행마다 직접 입력하세요.'
/** 공통 모집수가 묶은 대상을 읽기 쉽게 적는다. 대상별 묶음은 저장할 곳이 없어 각 대상의 모집수를 비워 둔다. */
function sharedLabel(review: Review, paths: string[]) {
  const at = paths.map(path => /^supplyRows\[(\d+)\](?:\.targets\[(\d+)\])?/.exec(path)).filter(match => match !== null)
  if (at.length && at.every(match => match[2] !== undefined)) {
    const reserve = paths.every(path => path.endsWith('.reserveCount'))
    const byRow = new Map<number, string[]>()
    for (const match of at) {
      const rowIndex = Number(match[1]), row = review.extraction.supplyRows[rowIndex]
      const targets = byRow.get(rowIndex) ?? []
      targets.push(String(row?.targets[Number(match[2])]?.target ?? `대상 ${Number(match[2]) + 1}`))
      byRow.set(rowIndex, targets)
    }
    const quantity = reserve ? '예비자 모집수' : '모집수'
    const detail = [...byRow].map(([index, targets]) => {
      const row = review.extraction.supplyRows[index]
      return byRow.size === 1 ? `${row?.typeName ?? '공급행'}에서 ${targets.join(' · ')} 대상` : `${row?.typeName ?? '공급행'}의 ${targets.join(' · ')} 대상`
    }).join('과 ')
    return `${detail}이 함께 쓰는 ${quantity}입니다. 대상별 ${reserve ? '예비자 수' : '모집수'}는 비워 둡니다.`
  }
  return `${at.map(match => review.extraction.supplyRows[Number(match[1])]?.typeName).join(' · ')} 전체의 모집수입니다. 각 행에 나누거나 중복 기입하지 않습니다.`
}
