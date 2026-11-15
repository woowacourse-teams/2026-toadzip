import { useState, type ReactNode } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import { type Evidence } from './extraction'
import { cellMatches, complexFor, expectedHousingType, editCell, housingTypePrice, labelOf, linkName, linkedHousingType, linkedHousingTypeId, pendingItems, pendingTarget,
  otherComplexes, removedHousingTypes, sharedOf, sharedRow, shown, shownMoney, showsComplexDecisions, targetName, type Cell, type Review, type ReviewFilter, type RowReview } from './review'
import { ComplexDecisions } from './ComplexDecision'
import { type ComplexLabels, type HousingCandidates } from './matching'
import { SupplyRowList } from './SupplyRowList'
import { ComplexTypesPanel } from './ComplexTypesPanel'
import { ReviewCells } from './ReviewCells'
import { TargetGroupPicker } from '../supply/TargetGroupPicker'
import type { TargetGroup } from '../supply/targetGroups'
import { houseTypesOf, supplyView, targetMatches } from './supplyView'
import styles from './PdfCorrection.module.css'

export function ReviewSupply({ review, workspace, housing, complexes, filter, empty, onChange, onEvidence, warnings = [], savesHousingTypes = true, selected, onSelect, onJump }: { warnings?: string[]
  /** 검토 항목(data-review-id)으로 이동한다. 상태 배지와 단지 정하기 안내가 쓴다. */
  onJump?: (target: string) => void
  /** 편집 영역에 보일 주택형 번호. 확인 필요 이동·오류 이동이 이 값을 바꾼다. */
  selected: number; onSelect: (index: number) => void
  /** PDF로 공급정보 편집표만 채울 때는 false. 주택타입 가격을 저장하지 않으므로 그 칸을 보이지 않는다. */
  savesHousingTypes?: boolean; review: Review; workspace: JsonWorkspace; housing: HousingCandidates; complexes: ComplexLabels; filter: ReviewFilter; empty: string; onChange: (review: Review) => void; onEvidence: (evidence: Evidence, cell?: Cell) => void }) {
  // 주택타입 패널은 편집 상태를 갖지 않아 닫았다 열어도 고른 값이 그대로다.
  const [panelOpen, setPanelOpen] = useState(false), [noticesChoice, setNoticesOpen] = useState<boolean | null>(null)
  const pending = pendingItems(review, savesHousingTypes, workspace)
  const { group, shown: visibleRows, current, order } = supplyView(review, selected, filter, pending)
  // 공급정보는 위 고정 막대에서 고른 PDF 단지 하나씩 보인다. 편집을 마친 자리에서 바로 다음 주택형으로 넘어가게 한다.
  const following = current >= 0 ? order[order.indexOf(current) + 1] : undefined
  const visible = (index: number) => visibleRows.includes(index)
  function update(index: number, next: RowReview) { onChange({ ...review, rows: review.rows.map((row, at) => at === index ? next : row) }) }
  const shownChecks = [...filter === 'all' ? review.extraction.checks : [], ...warnings]
  // PDF에 없어 저장하면 지울 주택형은 단지마다가 아니라 공고 전체에서 한 번 알린다.
  // 이 PDF에 없는 단지의 주택형은 그 단지 안내에서 고르므로, 주택형 안내에는 이 PDF 단지에서 빠진 주택형만 보인다.
  const others = otherComplexes(review, workspace), removing = others.filter(group => group.remove).length
  const otherKeys = new Set(others.flatMap(group => group.types.map(type => type.key)))
  const removed = removedHousingTypes(review, workspace).filter(type => !otherKeys.has(type.key))
  const decisions = group !== undefined && showsComplexDecisions(review, group.indexes[0])
  const noticesOpen = noticesChoice ?? false
  const notices: { key: string; label: string; tone: 'info' | 'warning'; body: ReactNode }[] = [
    ...shownChecks.length ? [{ key: 'checks', label: `검산 확인 ${shownChecks.length}건`, tone: 'warning' as const, body: <div className={`${styles.pending} ${styles.checks}`} role="note" aria-label="검산 결과"><p><strong>검산 확인</strong> 합계·면적·원문 대조가 맞지 않는 항목입니다. 공고문과 대조하세요.</p><ul>{shownChecks.map(check => <li key={check}>{check}</li>)}</ul></div> }] : [],
    ...others.length ? [{ key: 'others', label: `이 PDF에 없는 단지 ${others.length}곳 ${removing === 0 ? '그대로 둠' : removing === others.length ? '삭제' : `중 ${removing}곳 삭제`}`, tone: removing ? 'warning' as const : 'info' as const,
      // 그대로 두기로 한 단지는 경고가 아니라 결정 상태라 경고 선·색을 쓰지 않는다.
      body: <div className={styles.pending} data-tone={removing ? 'warning' : 'info'} role="group" aria-label="이 PDF에 없는 단지">
        <p><strong>이 PDF에 없는 단지</strong> 다른 공고문 PDF에 실린 단지일 수 있습니다. 그대로 두면 지금 공급정보를 유지하고, 지우기를 고르면 저장할 때 그 단지의 주택형을 모두 지웁니다.</p>
        <ul className={styles.otherComplexes}>{others.map(group => <li key={group.key}><label><input type="checkbox" checked={group.remove}
          onChange={event => onChange({ ...review, removeComplexes: { ...review.removeComplexes, [group.key]: event.target.checked } })} />{`${group.name} 주택형 ${group.types.length}개 지우기`}</label>
          <span>{group.types.map(type => type.name).join(', ')}</span></li>)}</ul></div> }] : [],
    ...removed.length ? [{ key: 'removed', label: `PDF에 없는 주택형 ${removed.length}개 삭제`, tone: 'warning' as const, body: <div className={styles.pending} role="note" aria-label="PDF에 없는 주택형">
      <p><strong>PDF에 없는 주택형</strong> 지금 공급정보에는 있지만 이 PDF 공급표에는 없어 저장하면 지웁니다. 추출에서 빠진 주택형이면 '응답 준비로 돌아가기'에서 응답에 넣고 다시 비교하세요.</p>
      <ul>{removed.map(type => <li key={type.key}>{`${type.complexName || '단지 이름 없음'} · ${type.name}`}</li>)}</ul></div> }] : [],
  ]
  return <>
    {/* 탭 이름과 같아 화면에서는 숨기고, 보조기술의 구역 제목으로만 남긴다. */}
    <h2 className={styles.srOnly}>공급정보</h2>
    {/* 안내(검산·지울 주택형)는 한 줄 요약으로 접어 주택형 목록과 편집 영역이 화면 위쪽에 남게 한다. */}
    {notices.length > 0 && <div className={styles.noticeStrip} role="group" aria-label="공급정보 안내" data-review-id="notices" tabIndex={-1}>
      <div className={styles.noticeSummary}><Button data-design-system className={styles.quietButton} aria-expanded={noticesOpen} aria-controls="supply-notices" onClick={() => setNoticesOpen(!noticesOpen)}>{noticesOpen ? '안내 접기' : '안내 펼치기'}</Button>
        <ul aria-label="안내 요약">{notices.map(notice => <li key={notice.key} data-tone={notice.tone}>{notice.label}</li>)}</ul></div>
      <div id="supply-notices" className={styles.noticeBody} hidden={!noticesOpen}>{notices.map(notice => <div key={notice.key}>{notice.body}</div>)}</div>
    </div>}
    {/* 단지 정하기는 저장 전에 꼭 할 결정이라 접지 않고 단지 영역 맨 위에 둔다. 어느 필터에서도 숨기지 않는다. */}
    {decisions && <ComplexDecisions review={review} workspace={workspace} only={group?.indexes} onChange={onChange} />}
    {review.rows.length > 0 && <div className={styles.supplyLayout} data-panel={panelOpen}>
      <SupplyRowList review={review} pending={pending} selected={current} visible={visible} onSelect={onSelect} onJump={onJump} />
      <div className={styles.rowEditor}>{current >= 0 && supplyRow(review.rows[current], current)}
        {following !== undefined && <div className={styles.rowFooter}><Button data-design-system className={styles.secondaryButton} onClick={() => onSelect(following)}>{`다음 주택형 · ${following + 1}. ${review.extraction.sheet.housingTypes[following].name ?? '주택형 미확인'}`}</Button></div>}</div>
      {panelOpen && current >= 0 && typesPanel(current)}
    </div>}
    {!review.rows.length ? <p>추출된 주택형이 없습니다. 현재 공급정보를 유지합니다.</p> : !visibleRows.length && <p className={styles.hint}>{empty}</p>}
  </>

  function typesPanel(index: number) {
    const row = review.rows[index], complex = complexFor(review, index), complexId = complex.housingComplexId
    const name = complexId === null ? '단지 미정' : complex.housingComplexName ?? complexes[complexId] ?? `단지 #${complexId}`
    const link = (typeId: number) => update(index, { ...row, priceTarget: undefined,
      cells: row.cells.map(cell => cell.field === 'housingTypeId' ? editCell(cell, String(typeId)) : cell) })
    return <ComplexTypesPanel complexName={name} complexId={complexId} review={review} workspace={workspace} types={complexId === null ? [] : housing[complexId] ?? row.candidates}
      rowIndex={index} linkedId={linkedHousingTypeId(row) ?? expectedHousingType(review, index)?.id ?? null} onLink={link} onClose={() => setPanelOpen(false)} />
  }
  function supplyRow(row: RowReview, index: number) {
    const source = review.extraction.sheet.housingTypes[index], complex = complexFor(review, index)
    const expected = expectedHousingType(review, index), houses = houseTypesOf(review, index)
    return <section className={styles.supplyBlock} key={index} aria-label={`주택형 ${index + 1} 검토`} data-review-id={`row:${index}`} tabIndex={-1}>
      <header><h3>{`${index + 1}. ${source.name ?? '주택형 미확인'}`}</h3><div className={styles.actions}><Button data-design-system className={styles.quietButton} aria-expanded={panelOpen} onClick={() => setPanelOpen(open => !open)}>{panelOpen ? '단지 주택타입 닫기' : '단지 주택타입 보기'}</Button></div></header>
      {/* 편집 영역은 단지 → 값 비교 → 대상별 칸 → 함께 쓰는 수 → 주택타입 가격 순서의 같은 모양 구역으로 나눈다. */}
      <div className={styles.rowPart}><h4>단지</h4>
        <p className={styles.hint}>{row.existing ? '지금 공급정보의 같은 주택형을 PDF 값으로 고칩니다.' : '지금 공급정보에 없는 주택형이라 새로 더합니다.'}</p>
        {/* 공고문은 주택형(신청형) 하나로 모집하고, 단지에는 그 아래 주택타입을 따로 둔다. 모집수·임대조건은 이 주택형 한 줄에 둔다. */}
        {houses.length > 0 && <p>{`함께 모집하는 단지 주택타입: ${houses.map(house => house.area === null ? house.name : `${house.name}(전용 ${house.area}㎡)`).join(', ')}`}</p>}
        {complex.housingComplexId === null
          ? <p className={styles.hint}>이 주택형의 단지를 아직 정하지 않았습니다. {onJump && <Button data-design-system className={styles.quietButton} onClick={() => onJump('complex')}>단지 정하기로 이동</Button>}</p>
          // 공급 구분은 서버가 공고명으로 정하고(지금 주택형은 그 값을 지킴), 소재지 PNU는 묻지 않는다.
          : <p>저장할 단지: {complex.housingComplexName ?? `단지 #${complex.housingComplexId}`}</p>}
      </div>
      <div className={styles.rowPart}><h4>값 비교</h4>
        {source.constructedHouseholdCount != null && <p className={styles.hint}>{`PDF 건설호수 ${String(source.constructedHouseholdCount)}세대`}</p>}
        {/* 연결 주택타입을 비워 두면 저장할 때 같은 이름의 주택타입에 잇고, 없으면 그 이름으로 새로 만든다. */}
        {linkedHousingTypeId(row) === null && complex.housingComplexId !== null && <p className={styles.hint}>{expected
          ? `연결 주택타입: 저장하면 '${expected.name}'(전용 ${expected.exclusiveArea}㎡)에 연결합니다.`
          : `연결 주택타입: 이 단지에 '${linkName(review, index)}' 주택타입이 없어 저장하면 새로 만듭니다.`}</p>}
        <ReviewCells housingTypes={row.candidates} otherHousingTypes={Object.values(housing).flat()} cells={row.cells} scope={`주택형 ${index + 1}`} filter={filter}
          onChange={cells => update(index, { ...row, cells,
            ...(linkedHousingTypeId({ ...row, cells }) !== linkedHousingTypeId(row) ? { priceTarget: undefined } : {}) })} onEvidence={onEvidence} />
      </div>
      <TargetBlock review={review} index={index} filter={filter} onChange={onChange} onEvidence={onEvidence} />
      <SharedBlock review={review} index={index} filter={filter} onChange={onChange} onEvidence={onEvidence} />
      {/* 저장하면 주택타입 가격이 바뀔 수 있으므로 어느 필터에서 저장해도 관리자가 고른 값을 보이게 한다. */}
      {savesHousingTypes && <HousingTypePrice review={review} index={index} update={next => update(index, next)} />}
    </section>
  }
}

/** 아직 고르지 않은 PDF 공급대상 이름. 지금 이름이 없을 때 표에 후보로 보인다. */
const extractedName = (review: Review, cell: number) => {
  const value = labelOf(review, cell)?.cells.find(item => item.field === 'label')?.value
  return typeof value === 'string' ? value.trim() : ''
}
const countText = (cell: Cell | undefined) => cell?.state === 'notApplicable' && cell.value === null ? '함께' : shown(cell?.value)

/** 대상별 칸(모집·예비·임대조건). 줄을 펼치면 공급대상 이름·신청 조건과 칸 값을 대조·수정한다. 공급대상 이름은 공고 전체에서 한 번 고친다. */
function TargetBlock({ review, index, filter, onChange, onEvidence }: { review: Review; index: number; filter: ReviewFilter; onChange: (review: Review) => void; onEvidence: (evidence: Evidence, cell?: Cell) => void }) {
  const [expanded, setExpanded] = useState<number | null>(null)
  const row = review.rows[index]
  const selected = expanded === null ? null : row.targets[expanded]
  if (!row.targets.some(target => targetMatches(review, target, filter))) return null
  const updateCells = (at: number, cells: Cell[]) => onChange({ ...review, rows: review.rows.map((item, rowAt) => rowAt === index ? { ...item, targets: item.targets.map((target, targetAt) => targetAt === at ? { ...target, cells } : target) } : item) })
  const updateLabel = (target: number, cells: Cell[]) => onChange({ ...review, labels: review.labels.map(entry => entry.target === target ? { ...entry, cells } : entry) })
  const updateGroups = (target: number, groups: TargetGroup[]) => onChange({ ...review, labels: review.labels.map(entry => entry.target === target ? { ...entry, groups, groupsEdited: true } : entry) })
  const label = selected ? labelOf(review, selected.cell) : undefined
  return <div className={styles.rowPart}>
    <h4>대상별 칸</h4>
    <div className={styles.targetScroll} role="region" aria-label={`주택형 ${index + 1} 대상별 칸 표`} tabIndex={0}><table className={styles.targetTable}>
      <caption>PDF 표의 대상 순서 · 금액 단위 원</caption><thead><tr><th>대상</th><th>모집</th><th>예비</th><th>보증금</th><th>월세</th><th>전환보증금</th><th>대조·수정</th></tr></thead>
      <tbody>{row.targets.map((target, at) => {
        if (!targetMatches(review, target, filter)) return null
        const cell = (field: string) => target.cells.find(item => item.field === field)
        const waiting = pendingTarget(review, target), name = targetName(review, target.cell), candidate = extractedName(review, target.cell)
        return <tr key={at} data-pending={waiting || undefined} data-review-id={`target:${index}:${at}`} tabIndex={-1}>
          <th scope="row">{name || (candidate ? <>{candidate}<small className={styles.pendingLabel}>이름 확인 필요</small></> : <span className={styles.pendingLabel}>이름 없음</span>)}</th>
          <td>{countText(cell('winner'))}</td><td>{countText(cell('reserve'))}</td>
          {['rentalDeposit', 'monthlyRent', 'convertedDeposit'].map(field => { const item = cell(field)!; return <td key={field}><strong>{shownMoney(item.value)}</strong><small>{item.before != null && item.before !== item.value ? `현재 ${shownMoney(item.before)}` : item.state === 'notApplicable' ? '해당 없음' : item.state === 'same' ? '같음' : item.selected ? '적용 선택' : '적용 안 함'}</small>
            {item.evidence && item.value != null && <Button data-design-system className={styles.evidenceButton} aria-label={`PDF ${item.evidence.page}쪽에서 대상 ${at + 1} ${item.label} 보기`} title={item.evidence.quote} onClick={() => onEvidence(item.evidence!, item)}>p.{item.evidence.page}</Button>}</td> })}
          <td><Button data-design-system className={styles.quietButton} aria-expanded={expanded === at} onClick={() => setExpanded(expanded === at ? null : at)}>{`대상 ${at + 1} 대조·수정`}</Button>{waiting && <small className={styles.pendingLabel}>확인 필요</small>}</td>
        </tr>
      })}</tbody>
    </table></div>
    {selected && expanded !== null && <section className={styles.target} aria-label={`주택형 ${index + 1} 대상 ${expanded + 1} 수정`}>
      <h4>대상 {expanded + 1} 대조·수정</h4>
      {label && <>
        {!targetName(review, selected.cell) && <p className={styles.pendingLabel} role="note">{extractedName(review, selected.cell)
          ? 'PDF에서 읽은 공급대상 이름을 원문과 대조해 고르세요. 고르거나 직접 넣어야 저장할 수 있습니다.'
          : '공급대상 이름을 원문에서 읽지 못했습니다. 이름을 넣어야 저장할 수 있습니다.'}</p>}
        <p className={styles.hint}>공급대상 이름·신청 조건·분류는 이 대상을 쓰는 모든 주택형에 함께 적용됩니다.</p>
        <ReviewCells cells={label.cells} scope={`공급대상 ${label.target + 1}`} onChange={cells => updateLabel(label.target, cells)} onEvidence={onEvidence} />
        <div className={styles.groups}><strong>공급대상 분류</strong>
          <TargetGroupPicker name={`공급대상 ${label.target + 1}`} groups={label.groups} onChange={groups => updateGroups(label.target, groups)} />
          <p className={styles.hint}>신청자격을 이 공급대상에 이을 때 씁니다. 공고문 표기에 담긴 대상을 모두 고르세요(신혼부부 예비신혼부부 한부모가족 → 셋).</p>
        </div>
      </>}
      <ReviewCells cells={selected.cells} scope={`주택형 ${index + 1} 대상 ${expanded + 1}`} onChange={cells => updateCells(expanded, cells)} onEvidence={onEvidence} />
    </section>}
  </div>
}

/** 이 주택형의 칸이 든, 칸 여럿이 함께 쓰는 수(49A·49B·49C 함께 모집 70). 여러 주택형에 걸친 수는 그 주택형 모두에서 같은 값을 고친다. */
function SharedBlock({ review, index, filter, onChange, onEvidence }: { review: Review; index: number; filter: ReviewFilter; onChange: (review: Review) => void; onEvidence: (evidence: Evidence, cell?: Cell) => void }) {
  const entries = sharedOf(review, index).filter(entry => cellMatches(entry.cell, filter))
  if (!entries.length) return null
  const update = (quota: number, cell: Cell) => onChange({ ...review, shared: review.shared.map(entry => entry.quota === quota ? { ...entry, cell } : entry) })
  return <div className={styles.rowPart}><h4>함께 쓰는 수</h4>
    {entries.map(entry => <div key={entry.quota} data-review-id={`shared:${sharedRow(review, entry)}:${entry.quota}`} tabIndex={-1}>
      <ReviewCells cells={[entry.cell]} scope={entry.cell.label} onChange={([cell]) => update(entry.quota, cell)} onEvidence={onEvidence} />
    </div>)}
  </div>
}

/** 주택타입 기본 임대조건(사용자 화면의 단지 가격·필터에 쓰는 값)을 이 공고의 어느 대상 금액으로 갱신할지 보여 주고 고르게 한다. */
function HousingTypePrice({ review, index, update }: { review: Review; index: number; update: (row: RowReview) => void }) {
  // 화면과 저장이 같은 주택타입을 보도록 저장 기준(연결 주택타입 칸 → 같은 이름 주택타입)으로 고른다.
  const row = review.rows[index], type = linkedHousingType(review, index), price = housingTypePrice(review, index, type)
  if (!price.options.length || complexFor(review, index).housingComplexId === null) return null
  const won = (value: number | null | undefined) => typeof value === 'number' ? `${value.toLocaleString('ko-KR')}원` : '미확인'
  const current = type ? `보증금 ${won(type.basicDeposit)} · 월 ${won(type.basicMonthlyRent)}` : '없음(새로 만들 주택타입)'
  const chosen = price.chosen === null ? null : price.options[price.chosen]
  const unchanged = chosen !== null && chosen.deposit === type?.basicDeposit && chosen.monthlyRent === type?.basicMonthlyRent
  return <section className={styles.rowPart} aria-label={`주택형 ${index + 1} 주택타입 가격`} data-review-id={`price:${index}`} tabIndex={-1}>
    <h4>주택타입 가격</h4>
    <p className={styles.hint}>사용자 화면의 단지 가격·보증금 필터에 쓰는 주택타입 기본 임대조건입니다. 지금: {current}</p>
    {price.confirm && <div className={styles.pending}><p><strong>확인 필요</strong> 지금 가격은 이전 PDF 보정에서 고른 공고문 값입니다. 이번 공고 값으로 바꾸려면 아래에서 대상을 고르세요.</p><Button data-design-system className={styles.secondaryButton} onClick={() => update({ ...row, priceTarget: null })}>{`주택형 ${index + 1} 지금 가격 유지`}</Button></div>}
    {/* 금액이 모두 같아도 관리자가 지금 값을 유지할 수 있게 고르는 칸을 둔다. */}
    <label>주택형 {index + 1} 주택타입 가격으로 쓸 대상<select value={chosen === null ? '' : String(chosen.index)} onChange={event => update({ ...row, priceTarget: event.target.value === '' ? null : Number(event.target.value) })}>
      <option value="">지금 값 유지</option>
      {(price.same ? price.options.slice(0, 1) : price.options).map(option => <option key={option.index} value={option.index}>{price.same ? '모든 대상' : option.label} · 보증금 {won(option.deposit)} · 월 {won(option.monthlyRent)}</option>)}
    </select></label>
    <p>{chosen === null ? (price.unknown ? '이 주택타입의 지금 가격을 불러오지 못해, 고르지 않으면 지금 값을 유지합니다.' : price.same || price.confirm ? '' : '대상별 금액이 다르거나 일부 대상의 금액이 없어, 고르지 않으면 지금 값을 유지합니다.') : unchanged ? '지금 값과 같습니다.' : `저장하면 보증금 ${won(chosen.deposit)} · 월 ${won(chosen.monthlyRent)}으로 갱신합니다. 더 최근 공고의 값은 유지합니다.`}</p>
  </section>
}
