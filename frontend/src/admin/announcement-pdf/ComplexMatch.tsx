import { useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import type { LhSupplyRow } from '../supply/supplyCorrectionApi'
import { complexCorrespondence, offerResult, offerSummary, pdfCount, pdfType, type LeftoverLh } from './correspondence'
import { lhName, type HousingCandidates } from './matching'
import { keepLeftover, keepMerged, placeRow, splitMerged, choosePdfCounts } from './placement'
import { assembledCount, complexGroups, rowName, type Review } from './review'
import styles from './PdfCorrection.module.css'

const sum = (counts: (number | null | undefined)[]) => counts.every(count => typeof count === 'number') ? (counts as number[]).reduce((total, count) => total + count, 0) : null
const lhArea = (lh: LhSupplyRow) => lh.exclusiveArea ? `전용 ${Number(lh.exclusiveArea)}㎡` : '면적 없음'

/**
 * 단지 탭 위의 LH·PDF 대응. LH가 준 행과 PDF 행을 나란히 두고, LH 통합행 하나를 PDF가 여러 행으로 적었으면 나눌지 1행으로 둘지,
 * 공급행이 없는 LH 행에는 맞는 PDF 행을 붙일지 그대로 둘지를 단지 단위로 묻는다. 결정은 행들의 저장 방식(saveMode·lhRow)에 남는다.
 */
export function ComplexMatch({ review, workspace, housing, groupIndex, onChange }: { review: Review; workspace: JsonWorkspace; housing: HousingCandidates; groupIndex: number; onChange: (review: Review) => void }) {
  const [picking, setPicking] = useState<string | null>(null)
  const { lhRows, offers, leftovers } = complexCorrespondence(review, workspace, groupIndex)
  if (!lhRows.length) return null
  const indexes = complexGroups(review)[groupIndex].indexes, included = indexes.filter(index => review.rows[index].include)
  // 직접 입력한 칸은 운영자가 정한 값이므로 PDF 값과 달라도 다시 묻지 않는다.
  const countsDiffer = (members: number[]) => members.some(index => {
    const row = review.rows[index], cell = row.cells.find(item => item.field === 'totalSupplyHouseholdCount')
    return row.include && row.proposal.saveMode === 'SPLIT' && cell?.mode !== 'manual' && !['invalid', 'unresolved', 'notApplicable'].includes(cell?.extracted.state ?? '') && pdfCount(review, index) !== null && assembledCount(row) !== pdfCount(review, index)
  })
  function leftover({ lh, attachedTo, kept }: LeftoverLh) {
    const name = `LH 행 '${lhName(lh)}'`
    const reopen = <Button data-design-system className={styles.quietButton} onClick={() => onChange(attachedTo === null ? keepLeftover(review, lh.rowIdentifier, false)
      : placeRow(review, attachedTo, { mode: 'ADD', anchorKey: review.rows[attachedTo].proposal.anchorKey }, workspace, housing))}>{`${name} 다시 고르기`}</Button>
    if (attachedTo !== null) return <p>{`${name}에 ${rowName(review, attachedTo)}을 붙여 새 공급정보로 저장합니다.`} {reopen}</p>
    if (kept) return <p>{`${name}에는 이번 보정에서 공급정보를 붙이지 않고 그대로 둡니다.`} {reopen}</p>
    return <>
      <p><strong>{`${name}에 맞는 PDF 행이 없습니다.`}</strong> {lhArea(lh)}</p>
      <div className={styles.actions}>
        <Button data-design-system className={styles.secondaryButton} aria-expanded={picking === lh.rowIdentifier} onClick={() => setPicking(picking === lh.rowIdentifier ? null : lh.rowIdentifier)}>PDF 행 고르기</Button>
        <Button data-design-system className={styles.secondaryButton} onClick={() => onChange(keepLeftover(review, lh.rowIdentifier, true))}>이번 보정에서 그대로 두기</Button>
      </div>
      {picking === lh.rowIdentifier && <ul className={styles.placementOptions} aria-label={`${name}에 붙일 PDF 행`}>{included.map(index => <li key={index}>
        <button type="button" onClick={() => { setPicking(null); onChange(keepLeftover(placeRow(review, index, { mode: 'ATTACH', lhRow: lh.rowIdentifier, anchorKey: review.rows[index].proposal.anchorKey }, workspace, housing), lh.rowIdentifier, false)) }}>
          <span>{`${rowName(review, index)} · 모집 ${pdfCount(review, index) ?? '미확인'}`}</span></button></li>)}</ul>}
    </>
  }
  return <section className={styles.complexMatch} aria-label="LH·PDF 대응" data-review-id={`match:${groupIndex}`} tabIndex={-1}>
    <h3>LH·PDF 대응</h3>
    <div className={styles.matchColumns}>
      <div><h4>{`LH가 준 행 ${lhRows.length}개`}</h4><ul aria-label="LH가 준 행">{lhRows.map(lh => <li key={lh.rowIdentifier}>{`${lh.typeName?.trim() || '표기 없음'} · ${lhArea(lh)} · ${lh.householdCount ?? '미확인'}세대`}</li>)}</ul>
        <p>{`합계 ${sum(lhRows.map(lh => lh.householdCount)) ?? '미확인'}세대`}</p></div>
      <div><h4>{`PDF 행 ${indexes.length}개`}</h4><ul aria-label="PDF 행">{indexes.map(index => { const area = review.extraction.supplyRows[index].values.exclusiveArea
        return <li key={index} data-excluded={!review.rows[index].include || undefined}>{`${index + 1}. ${pdfType(review, index)} · ${typeof area === 'number' ? `전용 ${area}㎡` : '면적 없음'} · ${pdfCount(review, index) ?? '미확인'}세대${review.rows[index].include ? '' : ' · 반영 안 함'}`}</li> })}</ul>
        <p>{`합계 ${sum(included.map(index => pdfCount(review, index))) ?? '미확인'}세대`}</p></div>
    </div>
    {offers.map(offer => <div key={offer.lh.rowIdentifier} className={styles.matchDecision} role="group" aria-label={`LH 행 '${lhName(offer.lh)}' 대응`}>
      <p>{offerSummary(review, offer, lhRows.length === 1)}</p>
      <div className={styles.actions}>
        <Button data-design-system className={styles.secondaryButton} aria-pressed={offer.choice === 'split'} onClick={() => onChange(splitMerged(review, offer.lh.rowIdentifier, offer.members, workspace, housing))}>{`PDF대로 ${offer.members.length}행으로 나누기${offer.recommended ? ' (권장)' : ''}`}</Button>
        <Button data-design-system className={styles.secondaryButton} aria-pressed={offer.choice === 'keep'} onClick={() => onChange(keepMerged(review, offer.lh.rowIdentifier, offer.members, workspace, housing))}>LH대로 1행 유지</Button>
      </div>
      {offer.choice === 'split' && countsDiffer(offer.members) && <p className={styles.hint}>{'나눌 행 중 모집수를 아직 PDF 값으로 정하지 않은 행이 있습니다. '}
        <Button data-design-system className={styles.quietButton} onClick={() => onChange(choosePdfCounts(review, offer.members))}>PDF 모집수로 정하기</Button></p>}
      {offer.choice ? <p className={styles.decided}>{offerResult(review, offer)}</p> : !offer.recommended && <p className={styles.hint}>AI 표시가 없고 PDF 모집수 합이 LH와 같지 않아 권장하지 않습니다. 공고문과 대조해 고르세요.</p>}
    </div>)}
    {leftovers.map(item => <div key={item.lh.rowIdentifier} className={styles.matchDecision} role="group" aria-label={`LH 행 '${lhName(item.lh)}' 대응`}>{leftover(item)}</div>)}
  </section>
}
