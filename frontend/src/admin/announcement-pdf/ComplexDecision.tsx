import { useEffect, useRef, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import { ComplexPicker } from '../management/ComplexPicker'
import { getManagementDetail } from '../management/api'
import { isRecord } from '../management/managementContract'
import { getMatchingHousingTypes } from '../ingest/supplyMatchingApi'
import { createRowReview, editCell, needsComplex, newRepresentative, preserveCellChoice, remapTarget, type Review, type RowReview } from './review'
import { chooseRow, missingHousingType, pickHousingType, sameComplex, typeKey } from './matching'
import { NewComplexRegistration, type MyHomeComplexOverview } from './NewComplexRegistration'
import { alignSourceRows, emptySourceRows } from './sourceAlignment'
import { createPdfHousingTypes } from './pdfHousingTypes'
import type { ExtractedRow } from './extraction'
import styles from './PdfCorrection.module.css'

/**
 * PDF 단지명마다 한 번 단지를 정한다. 새로 등록하면 공고문 주택형도 함께 만든다.
 * 단지에 연결되지 않은 LH 원천행이 있으면 공고문 행에 맞춰 연결·분할하고(sourceAlignment), 없으면 같은 단지명의 공급행 전체를
 * 그 단지에 연결하며 주택형은 전용면적으로 맞춘다. 맞는 주택형이 없으면 PDF 형명·면적으로 새 주택형을 추가하도록 고른다.
 */
export function ComplexDecisions({ review, workspace, only, onChange }: { review: Review; workspace: JsonWorkspace; onChange: (review: Review) => void
  /** 이 공급행들(한 단지 탭)의 단지만 정한다. 없으면 모든 행이다. */
  only?: number[] }) {
  const groups = new Map<string, number[]>()
  review.rows.forEach((row, index) => { if (needsComplex(row, workspace) && (!only || only.includes(index))) groups.set(review.extraction.supplyRows[index].complexName ?? '', [...groups.get(review.extraction.supplyRows[index].complexName ?? '') ?? [], index]) })
  if (!groups.size) return null
  return <section className={styles.complexDecisions} aria-label="PDF 단지 정하기">
    <h3>단지 정하기</h3>
    {[...groups].map(([name, indexes]) => <ComplexDecision key={name} name={name} indexes={indexes} review={review} workspace={workspace} onChange={onChange} />)}
  </section>
}

/** 수집한 마이홈 공고 원천에서 이 단지의 개요를 꺼낸다. 같은 단지명이 없으면 공고의 첫 마이홈 원천을 쓴다. */
function myHomeOverview(workspace: JsonWorkspace, name: string): MyHomeComplexOverview | undefined {
  const raws = workspace.supply.sources.filter(source => source.category === 'MYHOME_ANNOUNCEMENT').flatMap(source => source.rows.map(row => row.raw))
    .filter((raw): raw is Record<string, unknown> => isRecord(raw))
  const raw = raws.find(item => sameComplex(item.hsmp_nm, name)) ?? raws[0]
  if (!raw) return undefined
  const text = (value: unknown) => typeof value === 'string' && value.trim() ? value.trim() : null
  const households = Number(text(raw.tot_hshld_co))
  return { name: text(raw.hsmp_nm), address: text(raw.full_adres), pnu: text(raw.pnu), households: Number.isSafeInteger(households) && households > 0 ? households : null,
    heating: text(raw.heat_mthd_nm), building: text(raw.house_ty_nm) }
}

function ComplexDecision({ name, indexes, review, workspace, onChange }: { name: string; indexes: number[]; review: Review; workspace: JsonWorkspace; onChange: (review: Review) => void }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [registering, setRegistering] = useState(false)
  const request = useRef<AbortController | null>(null)
  const latest = useRef(review); latest.current = review
  useEffect(() => () => request.current?.abort(), [])
  const overview = review.extraction.complexes.find(item => item.name === name)
  const selected = review.rows[indexes[0]].proposal.newComplex
  const decided = indexes.every(index => review.rows[index].proposal.newComplex?.id === selected?.id) ? selected : undefined
  const types = indexes.map(index => review.extraction.supplyRows[index].typeName ?? `공급행 ${index + 1}`).join(' · ')
  const sources = emptySourceRows(workspace, review.extraction, name)
  const unmatched = decided ? sources.filter(source => !review.rows.some(row => row.proposal.destination === source.key)) : []
  // 새로 만들 주택형 이름. 세부 타입 묶음 행은 새로 만들 세부 타입마다 센다. 이미 있는 이름은 만들 때 건너뛴다.
  const missingNames = (index: number) => review.rows[index].subtypes?.filter(subtype => subtype.choice === 'new').map(subtype => subtype.name)
    ?? (newRepresentative(review.rows[index]) ? [review.extraction.supplyRows[index].typeName ?? ''] : [])
  const missingIndexes = decided ? indexes.filter(index => missingNames(index).length) : []
  // 세부 타입 묶음 행은 새로 만들 세부 타입만 넘긴다. 면적으로 연결한 세부 타입을 이름이 다르다고 또 만들지 않게 한다.
  const missingTypes = missingIndexes.map(index => {
    const row = review.extraction.supplyRows[index], subtypes = review.rows[index].subtypes
    return subtypes ? { ...row, subtypes: row.subtypes.filter((_, at) => subtypes[at]?.choice === 'new') } : row
  }), missingLabels = missingIndexes.flatMap(missingNames)
  async function choose(id: number, complexName: string, created: boolean, createTypes: ExtractedRow[] = []) {
    request.current?.abort()
    const controller = new AbortController(); request.current = controller
    setBusy(true); setError('')
    try {
      // 새로 등록한 단지는 공고문 주택형을 함께 만든다. 그래야 LH 원천행을 나눌 때 서로 다른 주택형으로 묶을 수 있다.
      const base = review.unaligned ?? review
      const pdfRows = created ? base.extraction.supplyRows.filter(row => row.complexName === name) : createTypes
      if (pdfRows.length) await createPdfHousingTypes(id, pdfRows, (await getMatchingHousingTypes(id, controller.signal)).map(type => type.name), controller.signal)
      const [detail, candidates] = await Promise.all([getManagementDetail('complexes', String(id), controller.signal), getMatchingHousingTypes(id, controller.signal)])
      if (controller.signal.aborted) return
      const current = latest.current
      const currentIndexes = current.rows.flatMap((_, index) => current.extraction.supplyRows[index].complexName === name ? [index] : [])
      if (currentIndexes.every(index => current.rows[index].proposal.newComplex?.id === id
        && JSON.stringify(current.rows[index].proposal.candidates) === JSON.stringify(candidates))) {
        setRegistering(false); return
      }
      const aligned = alignSourceRows(current, workspace, name, { id, name: complexName }, candidates)
      if (aligned) { onChange(aligned); setRegistering(false); return }
      const pnu = isRecord(detail.data.address) && typeof detail.data.address.pnu === 'string' ? detail.data.address.pnu : ''
      // 고른 단지에 이미 연결된 공급행이 있으면 새 행을 만들지 않고 그 행을 추천한다. 다른 PDF 행이 저장할 위치로 고른 행은 빼고 고른다.
      const linked = workspace.supply.rows.filter(item => !item.excluded && item.housingComplexId === id)
      const used = new Set(current.rows.filter((_, index) => !currentIndexes.includes(index)).map(row => row.proposal.destination))
      const rows = current.rows.map((old, index) => {
        if (!currentIndexes.includes(index)) return old
        const source = current.extraction.supplyRows[index], picked = pickHousingType(current.extraction, index, candidates)
        const oldCells = [...old.cells, ...old.targets.flatMap(target => target.cells)]
        const retain = (cell: RowReview['cells'][number]) => cell.field === 'housingTypeId' ? cell : preserveCellChoice(cell, oldCells.find(item => item.path === cell.path))
        const retainTargets = (next: RowReview) => {
          const saved = workspace.supply.rows.find(row => row.key === next.proposal.destination)?.targets ?? []
          const nextReview = { ...current, rows: current.rows.map((row, at) => at === index ? { ...next, targets: old.targets } : row) }
          return next.targets.map((target, at) => {
            const previous = old.targets[at]?.destination
            return previous === 'exclude' || (previous === 'new' && old.proposal.destination === next.proposal.destination) || saved.some(item => (item.id ? String(item.id) : item.key) === previous)
              ? remapTarget(nextReview, index, at, previous, workspace)
              : { ...target, cells: target.cells.map(retain) }
          })
        }
        if (linked.length) {
          const { chosen, recommended, destination } = chooseRow(source, linked, used, picked.housingTypeId, false)
          if (chosen) used.add(chosen.key)
          const housingTypeId = picked.housingTypeId ?? chosen?.housingTypeId ?? null
          const next = createRowReview(current.extraction, index, { ...old.proposal, destination,
            anchorKey: chosen?.key ?? linked[0].key, newComplex: undefined, complexId: id, candidates, recommended, housingTypeId,
            reason: `${complexName}에 이미 연결된 공급정보 중에서 추천했습니다. · ${picked.reason}`,
            needsConfirmation: !chosen || typeKey(chosen.typeName) !== typeKey(source.typeName) || housingTypeId === null }, workspace)

          return { ...next, include: old.include, reviewed: false, cells: next.cells.map(retain), targets: retainTargets(next) }
        }
        const next = createRowReview(current.extraction, index, { ...old.proposal, complexId: id, candidates, housingTypeId: picked.housingTypeId, reason: picked.reason,
          newComplex: { id, name: complexName, pnu, category: created ? 'NEW_SUPPLY' : old.proposal.newComplex?.category ?? 'RESUPPLY' } }, workspace)

        const newType = !source.subtypes.length && picked.housingTypeId === null && Boolean(missingHousingType(id, candidates, source.typeName ?? '', source.values.exclusiveArea, source.values.supplyArea)?.name)
        return { ...next, key: old.key, include: old.include, reviewed: true, newHousingType: newType,
          cells: next.cells.map(retain).map(cell => newType && cell.field === 'housingTypeId' ? editCell(cell, '') : cell),
          targets: retainTargets(next) }
      })
      onChange({ ...current, rows }); setRegistering(false)
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '단지를 불러오지 못했습니다.') }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  return <div className={styles.complexDecision} role="group" aria-label={`PDF 단지 ${name || '단지명 없음'}`} data-decided={decided ? true : undefined}>
    <header><strong>{name || '단지명 없음'}</strong><span>{overview?.address ?? '주소 미확인'} · 공급행 {indexes.length}개 ({types})</span></header>
    {decided ? <p className={styles.decided}>연결 단지: {decided.name} · 같은 단지명의 공급행 {indexes.length}개에 연결했습니다.</p>
      : <p className={styles.hint}>{sources.length ? `단지에 연결되지 않은 LH 원천행 ${sources.length}개가 있습니다. 단지를 정하면 공고문 주택형에 맞춰 연결하거나 나눕니다.` : `기존 단지를 고르거나, 없으면 새 단지로 등록하세요. 고른 단지는 공급행 ${indexes.length}개에 함께 연결됩니다.`}</p>}
    {missingTypes.length > 0 && decided && <div className={styles.pending}><p><strong>주택형 없음</strong> {missingLabels.join(' · ')} 주택형이 단지에 없습니다. 저장하면 새로 만들며, 공통 모집수로 묶으려면 먼저 추가하세요.</p>
      <Button data-design-system className={styles.secondaryButton} disabled={busy} onClick={() => void choose(decided.id, decided.name, false, missingTypes)}>{`공고문 주택형 ${missingLabels.length}개 추가`}</Button></div>}
    {unmatched.length > 0 && <p className={styles.hint}>{`공고문에 대응하는 행이 없는 LH 원천행 ${unmatched.length}개(${unmatched.map(row => row.typeName).join(' · ')})는 그대로 둡니다.`}</p>}
    <ComplexPicker disabled={busy} initialQuery={name} onSelect={item => void choose(item.id, item.name, false)} />
    {error && <p role="alert" className={styles.error}>{error}</p>}
    {registering
      ? <NewComplexRegistration index={indexes[0]} defaultName={name} defaultAddress={overview?.address ?? ''} defaultPnu={sources[0]?.pnu ?? ''} myhome={myHomeOverview(workspace, name)} defaultHouseholds={overview?.totalHouseholdCount ?? null} defaultParking={overview?.parkingCount ?? null}
        rentalType={String(workspace.detail.data.rentalType ?? '')} agencyCode={String(workspace.detail.data.agencyCode ?? '')} onCreated={complex => void choose(complex.id, complex.name, true)} />
      : !decided && <Button data-design-system className={styles.secondaryButton} disabled={busy} onClick={() => setRegistering(true)}>찾는 단지가 없으면 새 단지로 등록</Button>}
  </div>
}
