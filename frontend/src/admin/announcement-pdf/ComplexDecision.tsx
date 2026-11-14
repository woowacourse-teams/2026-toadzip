import { useEffect, useRef, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import { ComplexPicker } from '../management/ComplexPicker'
import { getManagementDetail } from '../management/api'
import { isRecord } from '../management/managementContract'
import { getMatchingHousingTypes } from '../ingest/supplyMatchingApi'
import { complexGroups, decideComplex, needsComplex, type Review } from './review'
import { NewComplexRegistration } from './NewComplexRegistration'
import styles from './PdfCorrection.module.css'

/**
 * PDF 단지 이름마다 한 번 단지를 정한다. 고른 단지는 그 이름의 주택형 중 지금 공급표에서 단지를 정하지 않은 주택형에 함께 연결된다.
 * 연결 주택타입은 저장할 때 같은 이름의 주택타입에 잇거나 새로 만든다.
 */
export function ComplexDecisions({ review, workspace, only, onChange }: { review: Review; workspace: JsonWorkspace; onChange: (review: Review) => void
  /** 이 주택형들(지금 보이는 단지)의 단지만 정한다. 없으면 모든 주택형이다. */
  only?: number[] }) {
  // 정하지 않은 단지와, 이 화면에서 고른 단지(다시 고를 수 있게)를 보인다.
  const groups = complexGroups(review).filter(group => group.indexes.some(index => (!only || only.includes(index))
    && (needsComplex(review, index) || review.complexes[group.key]?.chosen)))
  if (!groups.length) return null
  return <section className={styles.complexDecisions} aria-label="PDF 단지 정하기" data-review-id="complex" tabIndex={-1}>
    <h3>단지 정하기</h3>
    {groups.map(group => <ComplexDecision key={group.key} groupKey={group.key} name={group.name} indexes={group.indexes} review={review} workspace={workspace} onChange={onChange} />)}
  </section>
}

function ComplexDecision({ groupKey, name, indexes, review, workspace, onChange }: { groupKey: string; name: string; indexes: number[]; review: Review; workspace: JsonWorkspace; onChange: (review: Review) => void }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [registering, setRegistering] = useState(false)
  // 같은 이름의 기존 단지가 없어 등록을 대신 열었다. 관리자가 '기존 단지에서 찾기'로 돌아가면 다시 열지 않는다.
  const [autoOpened, setAutoOpened] = useState(false), autoOpenDone = useRef(false)
  const request = useRef<AbortController | null>(null)
  const latest = useRef(review); latest.current = review
  useEffect(() => () => request.current?.abort(), [])
  const overview = review.extraction.complexes.find(item => item.name === groupKey)
  const decided = review.complexes[groupKey]?.chosen ? review.complexes[groupKey] : undefined
  const undecided = indexes.filter(index => needsComplex(review, index))
  const types = indexes.map(index => review.extraction.sheet.housingTypes[index].name ?? `주택형 ${index + 1}`).join(' · ')
  async function choose(id: number, complexName: string, created: boolean) {
    request.current?.abort()
    const controller = new AbortController(); request.current = controller
    setBusy(true); setError('')
    try {
      const [detail, candidates] = await Promise.all([getManagementDetail('complexes', String(id), controller.signal), getMatchingHousingTypes(id, controller.signal)])
      if (controller.signal.aborted) return
      const pnu = isRecord(detail.data.address) && typeof detail.data.address.pnu === 'string' ? detail.data.address.pnu : ''
      const current = latest.current.complexes[groupKey]
      onChange(decideComplex(latest.current, groupKey, { ...current, housingComplexId: id, housingComplexName: complexName, supplyPnu: pnu || null,
        category: created ? 'NEW_SUPPLY' : current.category ?? 'RESUPPLY', chosen: true }, candidates))
      setRegistering(false)
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '단지를 불러오지 못했습니다.') }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  return <div className={styles.complexDecision} role="group" aria-label={`PDF 단지 ${name}`} data-decided={decided ? true : undefined}>
    <header><strong>{name}</strong><span>{overview?.address ?? '주소 미확인'} · 주택형 {indexes.length}개 ({types})</span></header>
    {decided ? <p className={styles.decided}>연결 단지: {decided.housingComplexName} · 같은 단지 이름의 주택형에 연결했습니다.</p>
      : <p className={styles.hint}>{`기존 단지를 고르거나, 없으면 새 단지로 등록하세요. 고른 단지는 주택형 ${undecided.length}개에 함께 연결됩니다.`}</p>}
    {!registering && <ComplexPicker disabled={busy} initialQuery={groupKey} onSelect={item => void choose(item.id, item.name, false)}
      onInitialResults={items => { if (!items.length && !decided && !autoOpenDone.current) { autoOpenDone.current = true; setAutoOpened(true); setRegistering(true) } }} />}
    {error && <p role="alert" className={styles.error}>{error}</p>}
    {registering && autoOpened && <p className={styles.hint}>같은 이름의 등록된 단지가 없어 새 단지 등록을 열었습니다. 이름이 다른 기존 단지라면 기존 단지에서 찾으세요.</p>}
    {registering && <Button data-design-system className={styles.quietButton} disabled={busy} onClick={() => { autoOpenDone.current = true; setAutoOpened(false); setRegistering(false) }}>기존 단지에서 찾기</Button>}
    {registering
      ? <NewComplexRegistration index={indexes[0]} defaultName={groupKey} defaultAddress={overview?.address ?? ''} defaultHouseholds={overview?.totalHouseholdCount ?? null} defaultParking={overview?.parkingCount ?? null}
        rentalType={String(workspace.detail.data.rentalType ?? '')} agencyCode={String(workspace.detail.data.agencyCode ?? '')} onCreated={complex => void choose(complex.id, complex.name, true)}
        onSelectExisting={complex => void choose(complex.id, complex.name, false)} />
      : !decided && <Button data-design-system className={styles.secondaryButton} disabled={busy} onClick={() => setRegistering(true)}>찾는 단지가 없으면 새 단지로 등록</Button>}
  </div>
}
