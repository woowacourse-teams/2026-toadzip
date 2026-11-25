import { useEffect, useRef, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import { ComplexPicker } from '../management/ComplexPicker'
import { getManagementDetail } from '../management/api'
import { isRecord } from '../management/managementContract'
import { getMatchingHousingTypes } from '../ingest/supplyMatchingApi'
import { findSameAddressComplexes, type SameAddressComplex } from '../supply/supplyCorrectionApi'
import { complexGroups, decideComplex, needsComplex, type Review } from './review'
import { NewComplexRegistration } from './NewComplexRegistration'
import styles from './PdfCorrection.module.css'

/**
 * PDF 단지 이름마다 한 번 단지를 정한다. 고른 단지는 그 이름의 주택형 중 지금 공급표에서 단지를 정하지 않은 주택형에 함께 연결된다.
 * 연결 주택타입은 저장할 때 같은 이름의 주택타입에 잇거나 새로 만든다.
 */
export function ComplexDecisions({ review, workspace, only, rentalType, onChange }: { review: Review; workspace: JsonWorkspace; onChange: (review: Review) => void
  /** 이 주택형들(지금 보이는 단지)의 단지만 정한다. 없으면 모든 주택형이다. */
  only?: number[]
  /** 관리자가 이 화면에서 고른 공고의 공급유형. 없으면 저장된 공고 값이다. 같은 주소 단지와 새 단지 등록이 이 유형을 쓴다. */
  rentalType?: string }) {
  // 정하지 않은 단지와, 이 화면에서 고른 단지(다시 고를 수 있게)를 보인다.
  const groups = complexGroups(review).filter(group => group.indexes.some(index => (!only || only.includes(index))
    && (needsComplex(review, index) || review.complexes[group.key]?.chosen)))
  if (!groups.length) return null
  return <section className={styles.complexDecisions} aria-label="PDF 단지 정하기" data-review-id="complex" tabIndex={-1}>
    <h3>단지 정하기</h3>
    {groups.map(group => <ComplexDecision key={group.key} groupKey={group.key} name={group.name} indexes={group.indexes} review={review} workspace={workspace} chosenRentalType={rentalType} onChange={onChange} />)}
  </section>
}

/**
 * 단지는 한 방향으로 정한다: 공고 단지와 주소가 같은 기존 단지 → 이름 검색 → 새 단지 등록. 같은 주소의 단지가 하나면 고르지 않아도
 * 그 단지에 잇고, 여럿이면 그 단지들을 먼저 보인다. 이름 검색 결과가 없으면 등록을 검색 아래에 바로 연다. 정한 뒤에는 '다른 단지로 바꾸기'로만
 * 같은 단계를 다시 연다.
 */
function ComplexDecision({ groupKey, name, indexes, review, workspace, chosenRentalType, onChange }: { groupKey: string; name: string; indexes: number[]; review: Review; workspace: JsonWorkspace; chosenRentalType?: string; onChange: (review: Review) => void }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [registering, setRegistering] = useState(false)
  const [nearby, setNearby] = useState<SameAddressComplex[] | null>(null), [changing, setChanging] = useState(false)
  // 같은 주소의 단지에 대신 이은 단지. 관리자가 다른 단지를 고르면 지운다.
  const [autoLinked, setAutoLinked] = useState<number | null>(null), [autoFailed, setAutoFailed] = useState(false), [lookupFailed, setLookupFailed] = useState(false)
  // 단지를 정하거나 바꾸기를 시작하면 바뀐 자리로 초점을 옮긴다(누른 버튼이 사라지므로).
  const decidedLine = useRef<HTMLParagraphElement>(null), keepButton = useRef<HTMLButtonElement>(null), focusNext = useRef<'decided' | 'keep' | null>(null)
  const request = useRef<AbortController | null>(null)
  const latest = useRef(review); latest.current = review
  useEffect(() => () => request.current?.abort(), [])
  // 단지명 열이 없는 공급표는 주택형의 단지명이 빈다. 그때 PDF 단지 개요가 하나뿐이면 그 단지다(서버 자동 공개와 같은 규칙).
  const overviews = review.extraction.complexes
  const overview = overviews.find(item => item.name === groupKey) ?? (!groupKey && overviews.length === 1 ? overviews[0] : undefined)
  const decided = review.complexes[groupKey]?.chosen ? review.complexes[groupKey] : undefined
  const undecided = indexes.filter(index => needsComplex(review, index))
  const types = indexes.map(index => review.extraction.sheet.housingTypes[index].name ?? `주택형 ${index + 1}`).join(' · ')
  // 기관 원천이 주소와 함께 알려 준 공고 단지. 그 이름이 공고의 단지명이고, 적용하면 연결한 기존 단지 이름을 그 이름으로 바꾼다.
  const source = workspace.supply.announcementComplexes.find(item => item.id === review.complexes[groupKey]?.announcementComplexId)
  const announcedName = source?.roadAddress ? source.name : null
  const rentalType = chosenRentalType || String(workspace.detail.data.rentalType ?? '')
  const addresses = [overview?.address, source?.roadAddress].filter((value): value is string => Boolean(value?.trim()))
  const addressKey = addresses.join('\n'), announcementId = String(workspace.detail.summary.id)

  async function choose(id: number, complexName: string, created: boolean, automatic = false) {
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
      setAutoLinked(automatic ? id : null); setRegistering(false); setChanging(false); setReleased(false)
      if (!automatic) focusNext.current = 'decided'
    } catch (cause) {
      if (controller.signal.aborted) return
      if (automatic) setAutoFailed(true)
      setError(cause instanceof Error ? cause.message : '단지를 불러오지 못했습니다.')
    }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  const chooseLatest = useRef(choose); chooseLatest.current = choose
  const autoLinkedRef = useRef(autoLinked); autoLinkedRef.current = autoLinked
  const onChangeLatest = useRef(onChange); onChangeLatest.current = onChange
  // 공급유형이 바뀌어 대신 이은 연결을 풀었다.
  const [released, setReleased] = useState(false)
  // 같은 주소의 기존 단지를 한 번 찾는다. 하나뿐이고 아직 정하지 않았으면 그 단지에 잇는다.
  useEffect(() => {
    if (!addressKey) { setNearby([]); return }
    setNearby(null); setLookupFailed(false)
    const controller = new AbortController()
    findSameAddressComplexes(announcementId, addressKey.split('\n'), rentalType, controller.signal).then(found => {
      if (controller.signal.aborted) return
      setNearby(found)
      // 관리자가 직접 정한 단지(이 화면에서 고르거나 초안에서 되살린 단지)는 덮지 않는다. 대신 이은 단지는 공급유형을 바꾸면 다시 정한다.
      const current = latest.current.complexes[groupKey]
      const replaceable = !current?.chosen || current.housingComplexId === autoLinkedRef.current
      if (found.length === 1 && replaceable) void chooseLatest.current(found[0].housingComplexId, found[0].name, false, true)
      else if (current?.chosen && current.housingComplexId === autoLinkedRef.current && !found.some(item => item.housingComplexId === current.housingComplexId)) {
        // 대신 이은 단지가 더는 같은 곳 단지가 아니다. 그대로 적용되지 않게 연결을 비우고 다시 고르게 한다.
        setAutoLinked(null); setChanging(false); setReleased(true)
        onChangeLatest.current(decideComplex(latest.current, groupKey, { ...current, housingComplexId: null, housingComplexName: null, supplyPnu: null, chosen: false }, []))
      }
    }, () => { if (!controller.signal.aborted) { setNearby([]); setLookupFailed(true) } })
    return () => controller.abort()
  }, [addressKey, announcementId, rentalType, groupKey])
  useEffect(() => {
    const target = focusNext.current === 'decided' ? decidedLine.current : focusNext.current === 'keep' ? keepButton.current : null
    if (target) { target.focus(); focusNext.current = null }
  })

  const decidedNearby = decided ? nearby?.find(item => item.housingComplexId === decided.housingComplexId) : undefined
  // 이름을 실제로 바꾸는지는 서버 미리보기 경고가 정한다(다른 공고의 단지명·관리자 수정까지 본다). 화면은 가능성만 알린다.
  const renames = Boolean(decided && announcedName && announcedName !== decided.housingComplexName && decidedNearby?.renamable !== false)
  const picking = !decided || changing
  // 같은 주소 단지 하나에 대신 잇는 중이다. 고를 수 없는 선택지를 잠깐 보이지 않는다.
  const autoLinking = !decided && !autoFailed && nearby?.length === 1
  return <div className={styles.complexDecision} role="group" aria-label={`PDF 단지 ${name}`} data-decided={decided ? true : undefined}>
    <header><strong>{name}</strong><span>{overview?.address ?? source?.roadAddress ?? '주소 미확인'} · 주택형 {indexes.length}개 ({types})</span></header>
    {decided && <div className={styles.decidedRow}>
      <p ref={decidedLine} tabIndex={-1} className={styles.decided}>{`연결 단지: ${decided.housingComplexName}${autoLinked === decided.housingComplexId ? ' · 공고 단지와 주소가 같아 자동으로 연결했습니다.' : ''}`}</p>
      {changing
        ? <Button ref={keepButton} data-design-system className={styles.quietButton} disabled={busy} onClick={() => { setChanging(false); setRegistering(false); focusNext.current = 'decided' }}>지금 단지 유지</Button>
        : <Button data-design-system className={styles.quietButton} disabled={busy} onClick={() => { setChanging(true); focusNext.current = 'keep' }}>다른 단지로 바꾸기</Button>}
    </div>}
    {renames && !changing && <p className={styles.hint}>{`적용하면 '${decided!.housingComplexName}'의 이름이 공고 단지명 '${announcedName}'(으)로 바뀔 수 있습니다. 실제로 바뀌는지는 적용 전 확인 창에서 알려 드립니다.`}</p>}
    {picking && (nearby === null || autoLinking
      ? <p role="status" className={styles.hint}>{autoLinking ? `같은 주소의 단지 '${nearby![0].name}'에 연결하는 중…` : '같은 주소의 등록된 단지를 찾는 중…'}</p>
      : <>
        {!decided && <p className={styles.hint}>{`기존 단지를 고르거나, 없으면 새 단지로 등록하세요. 고른 단지는 주택형 ${undecided.length}개에 함께 연결됩니다.`}</p>}
        {released && <p role="status" className={styles.hint}>공급유형이 바뀌어 같은 주소로 이었던 단지 연결을 풀었습니다. 단지를 다시 고르세요.</p>}
        {lookupFailed && <p className={styles.hint}>같은 주소의 단지를 확인하지 못했습니다. 이름으로 찾으세요.</p>}
        {/* 등록 폼은 고른 주소의 등록 단지를 따로 보이므로, 등록하는 동안에는 이 목록을 숨겨 같은 버튼이 두 번 나오지 않게 한다. */}
        {!registering && nearby.length > 0 && <div className={styles.pending} data-tone="info" role="group" aria-label="공고 단지와 주소가 같은 단지">
          <p><strong>공고 단지와 주소가 같은 단지</strong> · 하나를 고르세요</p>
          <ul className={styles.existingComplexes}>{nearby.map(item => <li key={item.housingComplexId}>
            <span>{item.name}{item.roadAddress ? ` · ${item.roadAddress}` : ''}</span>
            {item.housingComplexId === decided?.housingComplexId
              ? <span className={styles.hint}>연결됨</span>
              : <Button data-design-system className={styles.secondaryButton} disabled={busy} onClick={() => void choose(item.housingComplexId, item.name, false)}>{`${item.name}에 연결`}</Button>}</li>)}</ul>
        </div>}
        <ComplexPicker disabled={busy} initialQuery={groupKey}
          onSelect={item => void choose(item.id, item.name, false)}
          onInitialResults={items => { if (!items.length && !nearby.length && !decided) setRegistering(true) }} />
        {registering
          ? <NewComplexRegistration index={indexes[0]} defaultName={announcedName ?? (groupKey || overview?.name || '')} defaultAddress={overview?.address ?? source?.roadAddress ?? ''}
            defaultHouseholds={overview?.totalHouseholdCount ?? null} defaultParking={overview?.parkingCount ?? null}
            rentalType={rentalType} agencyCode={String(workspace.detail.data.agencyCode ?? '')} onCreated={complex => void choose(complex.id, complex.name, true)}
            onSelectExisting={complex => void choose(complex.id, complex.name, false)} />
          : <Button data-design-system className={styles.secondaryButton} disabled={busy} onClick={() => setRegistering(true)}>찾는 단지가 없으면 새 단지 등록</Button>}
      </>)}
    {error && <p role="alert" className={styles.error}>{error}</p>}
  </div>
}
