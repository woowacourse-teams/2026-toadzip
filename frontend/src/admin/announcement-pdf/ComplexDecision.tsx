import { useEffect, useRef, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import { ComplexPicker } from '../management/ComplexPicker'
import { getManagementDetail } from '../management/api'
import { isRecord } from '../management/managementContract'
import { getMatchingHousingTypes } from '../ingest/supplyMatchingApi'
import { createRowReview, needsComplex, preserveCellChoice, remapTarget, type Review, type RowReview } from './review'
import { areaClaimedRows, chooseDestination, destinationReason, emptySourceRows, ghFrameReason, ghFrameRows, sameComplex, supersedeNote, takenRows, type RowProposal, lhTypeNamesByRow } from './matching'
import { NewComplexRegistration, type MyHomeComplexOverview } from './NewComplexRegistration'
import { createPdfHousingTypes } from './pdfHousingTypes'
import styles from './PdfCorrection.module.css'

/**
 * PDF 단지명마다 한 번 단지를 정한다. 새로 등록하면 공고문 주택형도 함께 만든다.
 * 단지를 정하면 그 단지에 연결된 공급행과 이 공고 단지의 단지 없는 원천행 중 전용면적이 겹치는 행에 PDF 공급행을 저장하고(chooseDestination),
 * 겹치는 행이 없으면 새 공급행으로 더한다. 주택형은 저장할 때 서버가 단지·전용면적으로 정한다.
 */
export function ComplexDecisions({ review, workspace, only, onChange }: { review: Review; workspace: JsonWorkspace; onChange: (review: Review) => void
  /** 이 공급행들(한 단지 탭)의 단지만 정한다. 없으면 모든 행이다. */
  only?: number[] }) {
  const groups = new Map<string, number[]>()
  review.rows.forEach((row, index) => { if (needsComplex(row, workspace) && (!only || only.includes(index))) groups.set(review.extraction.supplyRows[index].complexName ?? '', [...groups.get(review.extraction.supplyRows[index].complexName ?? '') ?? [], index]) })
  if (!groups.size) return null
  return <section className={styles.complexDecisions} aria-label="PDF 단지 정하기" data-review-id="complex" tabIndex={-1}>
    <h3>단지 정하기</h3>
    {[...groups].map(([name, indexes]) => <ComplexDecision key={name} name={name} indexes={indexes} review={review} workspace={workspace} onChange={onChange} />)}
  </section>
}

/** 수집한 마이홈 공고 원천에서 이 단지의 개요를 꺼낸다. 같은 단지명이 없으면 다른 단지의 정보를 사용하지 않는다. */
function myHomeOverview(workspace: JsonWorkspace, name: string): MyHomeComplexOverview | undefined {
  const raws = workspace.supply.sources.filter(source => source.category === 'MYHOME_ANNOUNCEMENT').flatMap(source => source.rows.map(row => row.raw))
    .filter((raw): raw is Record<string, unknown> => isRecord(raw))
  const raw = raws.find(item => sameComplex(item.hsmp_nm, name))
  if (!raw) return undefined
  const text = (value: unknown) => typeof value === 'string' && value.trim() ? value.trim() : null
  const households = Number(text(raw.tot_hshld_co))
  return { name: text(raw.hsmp_nm), address: text(raw.full_adres), pnu: text(raw.pnu), households: Number.isSafeInteger(households) && households > 0 ? households : null,
    heating: text(raw.heat_mthd_nm), building: text(raw.house_ty_nm) }
}

function ComplexDecision({ name, indexes, review, workspace, onChange }: { name: string; indexes: number[]; review: Review; workspace: JsonWorkspace; onChange: (review: Review) => void }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [registering, setRegistering] = useState(false)
  // 같은 이름의 기존 단지가 없어 등록을 대신 열었다. 관리자가 '기존 단지에서 찾기'로 돌아가면 다시 열지 않는다.
  const [autoOpened, setAutoOpened] = useState(false), autoOpenDone = useRef(false)
  const request = useRef<AbortController | null>(null)
  const latest = useRef(review); latest.current = review
  useEffect(() => () => request.current?.abort(), [])
  const overview = review.extraction.complexes.find(item => item.name === name)
  const createdComplex = review.rows[indexes[0]].proposal.createdComplex
  const selected = review.rows[indexes[0]].proposal.newComplex
  const decided = indexes.every(index => review.rows[index].proposal.newComplex?.id === selected?.id) ? selected : undefined
  const types = indexes.map(index => review.extraction.supplyRows[index].typeName ?? `공급행 ${index + 1}`).join(' · ')
  const sources = emptySourceRows(workspace, review.extraction, name)
  const unmatched = decided ? sources.filter(source => !review.rows.some(row => row.proposal.destination === source.key || row.proposal.supersedes?.includes(source.key))) : []
  async function choose(id: number, complexName: string, created: boolean) {
    request.current?.abort()
    const controller = new AbortController(); request.current = controller
    setBusy(true); setError('')
    try {
      // 새로 등록한 단지는 공고문 주택형을 함께 만든다.
      const pdfRows = created ? review.extraction.supplyRows.filter(row => row.complexName === name) : []
      if (pdfRows.length) await createPdfHousingTypes(id, pdfRows, await getMatchingHousingTypes(id, controller.signal), controller.signal)
      const [detail, candidates] = await Promise.all([getManagementDetail('complexes', String(id), controller.signal), getMatchingHousingTypes(id, controller.signal)])
      if (controller.signal.aborted) return
      // 이 단지의 연결을 마치면 재정렬 기준 검토에도 미완료 표식을 남기지 않는다.
      const complete = (input: Review): Review => ({ ...input,
        rows: input.rows.map(row => row.proposal.createdComplex?.id === id ? { ...row, proposal: { ...row.proposal, createdComplex: undefined } } : row) })
      const current = complete(latest.current)
      const currentIndexes = current.rows.flatMap((_, index) => current.extraction.supplyRows[index].complexName === name ? [index] : [])
      if (currentIndexes.every(index => current.rows[index].proposal.newComplex?.id === id
        && JSON.stringify(current.rows[index].proposal.candidates) === JSON.stringify(candidates))) {
        if (latest.current.rows.some(row => row.proposal.createdComplex?.id === id)) onChange(current)
        setRegistering(false); return
      }
      const pnu = isRecord(detail.data.address) && typeof detail.data.address.pnu === 'string' ? detail.data.address.pnu : ''
      // 고른 단지에 연결된 공급행과 이 공고 단지의 단지 없는 원천행에서 전용면적으로 저장할 곳을 다시 고른다. 다른 PDF 행이 고른 행은 빼고 고른다.
      const linked = workspace.supply.rows.filter(item => !item.excluded && item.housingComplexId === id)
      const related = [...linked, ...emptySourceRows(workspace, current.extraction, name)]
      const proposals: Pick<RowProposal, 'destination' | 'supersedes'>[] = current.rows.flatMap((row, index) => currentIndexes.includes(index) ? [] : [row.proposal])
      const rows = current.rows.map((old, index) => {
        if (!currentIndexes.includes(index)) return old
        const source = current.extraction.supplyRows[index], ownCount = source.values.totalSupplyHouseholdCount
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
        // 처음 제안(proposeRows)과 같은 순서로 고른다: 워커가 맞춘 GH 틀 행, 전용면적, 형명(다른 PDF 행이 면적으로 가리키는 행 제외).
        const used = takenRows(proposals), frames = ghFrameRows(source, workspace.supply, used)
        const { chosen, legacy, superseded } = frames.length ? { chosen: frames[0], legacy: false, superseded: frames.slice(1) }
          : chooseDestination(source, related, used, { [id]: candidates }, typeof ownCount === 'number' ? ownCount : null,
            lhTypeNamesByRow(workspace.supply.lhSupplyRows), areaClaimedRows(current.extraction, workspace.supply.rows, { [id]: candidates }))
        const anchor = chosen ?? linked[0]
        // 단지에 연결된 행을 기준으로 삼으면 그 행의 단지를 쓰고, 단지 없는 원천행을 고치거나 기준 행이 없으면 고른 단지를 연결한다.
        const newComplex = anchor?.housingComplexId === id ? undefined : { id, name: complexName, pnu: chosen?.pnu || pnu,
          category: chosen?.category ?? (created ? 'NEW_SUPPLY' as const : old.proposal.newComplex?.category ?? 'RESUPPLY' as const) }
        const proposal: RowProposal = { ...old.proposal, createdComplex: undefined, destination: chosen?.key ?? 'new', anchorKey: anchor?.key ?? '', complexId: id, candidates, newComplex,
          reason: frames.length ? ghFrameReason(frames[0], superseded, workspace.supply.rows)
            : [destinationReason(source, chosen, legacy), supersedeNote(workspace.supply.rows, superseded.map(item => item.key))].filter(Boolean).join(' '),
          needsConfirmation: false, supersedes: superseded.length ? superseded.map(item => item.key) : undefined }
        proposals.push(proposal)
        const next = createRowReview(current.extraction, index, proposal, workspace)
        return { ...next, key: chosen ? next.key : old.key, include: old.include, reviewed: false, cells: next.cells.map(retain), targets: retainTargets(next) }
      })
      onChange({ ...current, rows }); setRegistering(false)
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '단지를 불러오지 못했습니다.') }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  function registered(complex: { id: number; name: string }) {
    const pending = { ...review, rows: review.rows.map((row, index) => indexes.includes(index)
      ? { ...row, proposal: { ...row.proposal, createdComplex: complex } } : row) }
    onChange(pending)
    setRegistering(false)
    void choose(complex.id, complex.name, true)
  }
  return <div className={styles.complexDecision} role="group" aria-label={`PDF 단지 ${name || '단지명 없음'}`} data-decided={decided ? true : undefined}>
    <header><strong>{name || '단지명 없음'}</strong><span>{overview?.address ?? '주소 미확인'} · 공급행 {indexes.length}개 ({types})</span></header>
    {decided ? <p className={styles.decided}>연결 단지: {decided.name} · 같은 단지명의 공급행 {indexes.length}개에 연결했습니다.</p>
      : <p className={styles.hint}>{sources.length ? `단지에 연결되지 않은 원천 공급행 ${sources.length}개가 있습니다. 단지를 정하면 전용면적이 같은 원천 공급행을 공고문 공급행으로 고칩니다.` : `기존 단지를 고르거나, 없으면 새 단지로 등록하세요. 고른 단지는 공급행 ${indexes.length}개에 함께 연결됩니다.`}</p>}
    {unmatched.length > 0 && <p className={styles.hint}>{`공고문 공급행과 전용면적이 맞지 않는 원천 공급행 ${unmatched.length}개(${unmatched.map(row => row.typeName).join(' · ')})는 그대로 둡니다.`}</p>}
    {!createdComplex && !registering && <ComplexPicker disabled={busy} initialQuery={name} onSelect={item => void choose(item.id, item.name, false)}
      onInitialResults={items => { if (!items.length && !decided && !autoOpenDone.current) { autoOpenDone.current = true; setAutoOpened(true); setRegistering(true) } }} />}
    {error && <p role="alert" className={styles.error}>{error}</p>}
    {createdComplex && <div className={styles.pending} role="status"><p>{`${createdComplex.name} 단지 등록 완료. 주택형·공급행 연결을 마치면 됩니다.`}</p>
      <Button data-design-system className={styles.secondaryButton} disabled={busy} onClick={() => void choose(createdComplex.id, createdComplex.name, true)}>{busy ? '주택형·공급행 연결 중…' : '등록한 단지에 이어서 연결'}</Button></div>}
    {createdComplex && <details className={styles.registrationDetails}><summary>등록한 단지가 아니라 다른 단지에 연결</summary>
      <ComplexPicker disabled={busy} initialQuery={name} onSelect={item => void choose(item.id, item.name, false)} /></details>}
    {registering && !createdComplex && autoOpened && <p className={styles.hint}>같은 이름의 등록된 단지가 없어 새 단지 등록을 열었습니다. 이름이 다른 기존 단지라면 기존 단지에서 찾으세요.</p>}
    {registering && !createdComplex && <Button data-design-system className={styles.quietButton} disabled={busy} onClick={() => { autoOpenDone.current = true; setAutoOpened(false); setRegistering(false) }}>기존 단지에서 찾기</Button>}
    {registering && !createdComplex
      ? <NewComplexRegistration index={indexes[0]} defaultName={name} defaultAddress={overview?.address ?? ''} defaultPnu={sources[0]?.pnu ?? ''} myhome={myHomeOverview(workspace, name)} defaultHouseholds={overview?.totalHouseholdCount ?? null} defaultParking={overview?.parkingCount ?? null}
        rentalType={String(workspace.detail.data.rentalType ?? '')} agencyCode={String(workspace.detail.data.agencyCode ?? '')} onCreated={registered}
        onSelectExisting={complex => { setRegistering(false); void choose(complex.id, complex.name, false) }} />
      : !decided && !createdComplex && <Button data-design-system className={styles.secondaryButton} disabled={busy} onClick={() => setRegistering(true)}>찾는 단지가 없으면 새 단지로 등록</Button>}
  </div>
}
