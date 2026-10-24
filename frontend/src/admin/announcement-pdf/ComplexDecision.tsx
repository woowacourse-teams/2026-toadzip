import { useEffect, useRef, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import { ComplexPicker } from '../management/ComplexPicker'
import { getManagementDetail } from '../management/api'
import { isRecord } from '../management/managementContract'
import { getMatchingHousingTypes } from '../ingest/supplyMatchingApi'
import { createRowReview, editCell, preserveCellChoice, type Review, type RowReview } from './review'
import { missingHousingType, pickHousingType } from './matching'
import { NewComplexRegistration } from './NewComplexRegistration'
import styles from './PdfCorrection.module.css'

/** 단지 DB에 연결되지 않은 공급행(새 행 또는 단지가 비어 있는 기존 행)인지. 단지를 정하면 newComplex가 생긴다. */
function needsComplex(row: RowReview, workspace: JsonWorkspace) {
  if (!row.include) return false
  if (row.proposal.destination === 'new') return !row.proposal.anchorKey
  return workspace.supply.rows.find(item => item.key === row.proposal.destination)?.housingComplexId === null
}

/**
 * PDF 단지명마다 한 번 단지를 정한다. 기존 단지를 고르거나 새로 등록하면 같은 단지명의 공급행 전체를 그 단지에 연결하고,
 * 주택형은 전용면적으로 맞추며 맞는 주택형이 없으면 PDF 형명·면적으로 새 주택형을 추가하도록 고른다.
 */
export function ComplexDecisions({ review, workspace, onChange }: { review: Review; workspace: JsonWorkspace; onChange: (review: Review) => void }) {
  const groups = new Map<string, number[]>()
  review.rows.forEach((row, index) => { if (needsComplex(row, workspace)) groups.set(review.extraction.supplyRows[index].complexName ?? '', [...groups.get(review.extraction.supplyRows[index].complexName ?? '') ?? [], index]) })
  if (!groups.size) return null
  return <section className={styles.complexDecisions} aria-label="PDF 단지 정하기">
    <h3>단지 정하기</h3>
    {[...groups].map(([name, indexes]) => <ComplexDecision key={name} name={name} indexes={indexes} review={review} workspace={workspace} onChange={onChange} />)}
  </section>
}

function ComplexDecision({ name, indexes, review, workspace, onChange }: { name: string; indexes: number[]; review: Review; workspace: JsonWorkspace; onChange: (review: Review) => void }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [registering, setRegistering] = useState(false)
  const request = useRef<AbortController | null>(null)
  useEffect(() => () => request.current?.abort(), [])
  const overview = review.extraction.complexes.find(item => item.name === name)
  const selected = review.rows[indexes[0]].proposal.newComplex
  const decided = indexes.every(index => review.rows[index].proposal.newComplex?.id === selected?.id) ? selected : undefined
  const types = indexes.map(index => review.extraction.supplyRows[index].typeName ?? `공급행 ${index + 1}`).join(' · ')
  async function choose(id: number, complexName: string, created: boolean) {
    request.current?.abort()
    const controller = new AbortController(); request.current = controller
    setBusy(true); setError('')
    try {
      const [detail, candidates] = await Promise.all([getManagementDetail('complexes', String(id), controller.signal), getMatchingHousingTypes(id, controller.signal)])
      if (controller.signal.aborted) return
      const pnu = isRecord(detail.data.address) && typeof detail.data.address.pnu === 'string' ? detail.data.address.pnu : ''
      const rows = review.rows.map((old, index) => {
        if (!indexes.includes(index)) return old
        const source = review.extraction.supplyRows[index], picked = pickHousingType(review.extraction, index, candidates)
        const next = createRowReview(review.extraction, index, { ...old.proposal, complexId: id, candidates, housingTypeId: picked.housingTypeId, reason: picked.reason,
          newComplex: { id, name: complexName, pnu, category: created ? 'NEW_SUPPLY' : old.proposal.newComplex?.category ?? 'RESUPPLY' } }, workspace)
        const oldCells = [...old.cells, ...old.targets.flatMap(target => target.cells)]
        const retain = (cell: RowReview['cells'][number]) => cell.field === 'housingTypeId' ? cell : preserveCellChoice(cell, oldCells.find(item => item.path === cell.path))
        const newType = picked.housingTypeId === null && Boolean(missingHousingType(id, candidates, source.typeName ?? '', source.values.exclusiveArea, source.values.supplyArea)?.name)
        return { ...next, key: old.key, include: old.include, reviewed: true, newHousingType: newType,
          cells: next.cells.map(retain).map(cell => newType && cell.field === 'housingTypeId' ? editCell(cell, '') : cell),
          targets: next.targets.map(target => ({ ...target, cells: target.cells.map(retain) })) }
      })
      onChange({ ...review, rows }); setRegistering(false)
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '단지를 불러오지 못했습니다.') }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  return <div className={styles.complexDecision} role="group" aria-label={`PDF 단지 ${name || '단지명 없음'}`} data-decided={decided ? true : undefined}>
    <header><strong>{name || '단지명 없음'}</strong><span>{overview?.address ?? '주소 미확인'} · 공급행 {indexes.length}개 ({types})</span></header>
    {decided ? <p className={styles.decided}>연결 단지: {decided.name} · 같은 단지명의 공급행 {indexes.length}개에 연결했습니다.</p>
      : <p className={styles.hint}>기존 단지를 고르거나, 없으면 새 단지로 등록하세요. 고른 단지는 공급행 {indexes.length}개에 함께 연결됩니다.</p>}
    <ComplexPicker disabled={busy} initialQuery={name} onSelect={item => void choose(item.id, item.name, false)} />
    {error && <p role="alert" className={styles.error}>{error}</p>}
    {registering
      ? <NewComplexRegistration index={indexes[0]} defaultName={name} defaultAddress={overview?.address ?? ''} defaultHouseholds={overview?.totalHouseholdCount ?? null} defaultParking={overview?.parkingCount ?? null}
        rentalType={String(workspace.detail.data.rentalType ?? '')} agencyCode={String(workspace.detail.data.agencyCode ?? '')} onCreated={complex => void choose(complex.id, complex.name, true)} />
      : !decided && <Button data-design-system className={styles.secondaryButton} disabled={busy} onClick={() => setRegistering(true)}>찾는 단지가 없으면 새 단지로 등록</Button>}
  </div>
}
