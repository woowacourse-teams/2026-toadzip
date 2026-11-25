import { useEffect, useRef, useState } from 'react'
import { labels } from '../management/fields'
import { ComplexPicker } from '../management/ComplexPicker'
import { getManagementDetail } from '../management/api'
import { isRecord, type ManagementDetailData } from '../management/managementContract'
import { getMatchingHousingTypes, type MatchingHousingType } from '../ingest/supplyMatchingApi'
import { cellsOf, type SheetHousingType, type SupplySheet } from './supplySheet'
import { countInput, updateHousingType, setMembersCount, wholeTypeQuota } from './sheetEdits'
import type { AnnouncementComplex } from './supplyCorrectionApi'
import { SharedCountsDraft, SupplyTargetDraft } from './SupplyTargetDraft'
import styles from './SupplyCorrection.module.css'
import { NewWindowMark } from '../shared/AdminIcon'

const number = (text: string) => text === '' ? null : Number(text)

/**
 * 공급표의 주택형(신청형) 하나를 고친다. 주택형 자체 값과 단지·주택형 연결, 주택형 모집수(모든 공급대상이 함께 쓰는 모집 수),
 * 공급대상 칸과 함께 쓰는 수를 한곳에서 고치고, 고칠 때마다 공급표 전체를 돌려준다.
 */
export function SupplyRowDraft({ sheet, typeKey, complexes, onChange, onRemove, onMove, first, last }: {
  sheet: SupplySheet; typeKey: string; complexes: AnnouncementComplex[]; onChange: (sheet: SupplySheet) => void
  onRemove: () => void; onMove: (delta: -1 | 1) => void; first: boolean; last: boolean
}) {
  const value = sheet.housingTypes.find(type => type.key === typeKey)!
  const [types, setTypes] = useState<MatchingHousingType[]>([])
  const [candidateComplex, setCandidateComplex] = useState<ManagementDetailData | null>(null)
  const [error, setError] = useState(''); const [loading, setLoading] = useState(false)
  const [lookup, setLookup] = useState(false)
  const request = useRef<AbortController | null>(null)
  const complexId = value.housingComplexId
  const selectedType = value.newHousingType ?? types.find(type => type.id === value.housingTypeId)
  const address = candidateComplex?.data.address
  const candidatePnu = isRecord(address) && typeof address.pnu === 'string' ? address.pnu : null
  const cells = cellsOf(sheet, typeKey)
  useEffect(() => {
    if (!complexId || !lookup || candidateComplex?.summary.id === complexId) return
    const controller = new AbortController(); request.current = controller; setLoading(true); setError('')
    void Promise.all([getManagementDetail('complexes', String(complexId), controller.signal), getMatchingHousingTypes(complexId, controller.signal)]).then(([detail, candidates]) => {
      if (!controller.signal.aborted) { setTypes(candidates); setCandidateComplex(detail) }
    }).catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '주택형을 조회하지 못했습니다.') })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [complexId, lookup, candidateComplex])
  useEffect(() => () => request.current?.abort(), [])
  function field(patch: Partial<SheetHousingType>) { onChange(updateHousingType(sheet, typeKey, patch)) }
  async function chooseComplex(id: number, name: string) {
    request.current?.abort(); const controller = new AbortController(); request.current = controller
    setLoading(true); setError('')
    try {
      const [detail, candidates] = await Promise.all([getManagementDetail('complexes', String(id), controller.signal), getMatchingHousingTypes(id, controller.signal)])
      if (controller.signal.aborted) return
      const address = detail.data.address
      const pnu = isRecord(address) && typeof address.pnu === 'string' ? address.pnu : null
      field({ housingComplexId: id, housingComplexName: name, housingTypeId: null, newHousingType: null, supplyPnu: pnu ?? value.supplyPnu })
      setTypes(candidates); setCandidateComplex(detail); setLookup(true)
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '단지를 조회하지 못했습니다.') }
    finally { if (!controller.signal.aborted) setLoading(false) }
  }
  function chooseAnnouncementComplex(id: string) {
    const complex = complexes.find(item => String(item.id) === id)
    if (!complex) { field({ announcementComplexId: null }); return }
    // 공고 단지가 등록 단지에 연결되어 있으면 그 단지를 연결 후보로 함께 정한다.
    field({ announcementComplexId: complex.id, complexName: complex.name, ...(complex.housingComplexId && !value.housingComplexId
      ? { housingComplexId: complex.housingComplexId, housingComplexName: complex.housingComplexName } : {}) })
  }
  const whole = wholeTypeQuota(sheet, typeKey)
  return <div className={styles.row} data-inline><fieldset className={styles.rowBody} disabled={loading}><legend className={styles.srOnly}>주택형 보정</legend>
    <div className={styles.actions}><button type="button" disabled={first} onClick={() => onMove(-1)}>위로</button><button type="button" disabled={last} onClick={() => onMove(1)}>아래로</button>
      <button type="button" onClick={onRemove}>주택형 지우기</button></div>
    {value.adminModified && <p className={styles.hint}>관리자가 고친 값이 있어 자동 갱신으로 덮어쓰지 않는 주택형입니다.</p>}
    <fieldset disabled={loading}><legend>연결할 단지·주택형</legend>
      <p>현재 선택: {value.housingComplexName || '단지 미연결'} · {value.newHousingType ? '새 주택형 생성 예정' : value.housingTypeId ? `주택형 #${value.housingTypeId}` : '주택형 미연결'}</p>
      <ComplexPicker disabled={loading} onSelect={item => void chooseComplex(item.id, item.name)} />
      <div className={styles.actions}>
        {value.housingComplexId && <button type="button" onClick={() => setLookup(true)}>이 단지의 주택형 확인</button>}
        <button type="button" onClick={() => { setTypes([]); setLookup(false); field({ housingComplexId: null, housingComplexName: null, housingTypeId: null, newHousingType: null }) }}>단지·주택형 연결 해제</button>
      </div>
      {value.housingComplexId && lookup && <label>단지 주택형<select value={value.newHousingType ? 'new' : value.housingTypeId ?? ''} onChange={event => {
        if (event.target.value === 'new') field({ housingTypeId: null, newHousingType: { name: value.name, exclusiveArea: value.exclusiveArea ?? 0, supplyArea: value.supplyArea } })
        else field({ housingTypeId: event.target.value ? Number(event.target.value) : null, newHousingType: null })
      }}><option value="">미연결</option>{types.map(type => <option key={type.id} value={type.id}>{type.name} · 전용 {type.exclusiveArea}㎡ · #{type.id}</option>)}<option value="new">누락된 주택형 만들기</option></select></label>}
      {value.newHousingType && <div className={styles.fields}>
        <label>새 주택형 이름<input required maxLength={255} value={value.newHousingType.name} onChange={event => field({ newHousingType: { ...value.newHousingType!, name: event.target.value } })} /></label>
        <label>새 주택형 전용면적 (㎡)<input required type="number" min="0.0001" step="0.0001" value={value.newHousingType.exclusiveArea || ''} onChange={event => field({ newHousingType: { ...value.newHousingType!, exclusiveArea: Number(event.target.value) } })} /></label>
        <label>새 주택형 공급면적 (㎡)<input type="number" min="0" step="0.0001" value={value.newHousingType.supplyArea ?? ''} onChange={event => field({ newHousingType: { ...value.newHousingType!, supplyArea: number(event.target.value) } })} /></label>
        <p>연결한 단지에 주택형을 새로 만들고 이 주택형을 연결합니다. 기존 단지 주택형은 바꾸지 않습니다.</p>
      </div>}
      {lookup && candidateComplex && <section aria-label="연결 후보 비교">
        <p>선택 단지: {candidateComplex.summary.name} · {candidateComplex.summary.subtitle} · {labels[candidateComplex.summary.rental ?? ''] ?? candidateComplex.summary.rental}</p>
        <p>공고 공급 주소 식별자: {value.supplyPnu || '미확인'} / 단지 주소 식별자: {candidatePnu ?? '미확인'}</p>
        {selectedType && <div className="admin-table-scroll"><table className="admin-table"><caption>공고 입력값과 선택한 단지 주택형</caption>
          <thead><tr><th scope="col">비교 항목</th><th scope="col">공고 입력값</th><th scope="col">선택한 단지 주택형</th></tr></thead><tbody>
            <tr><th scope="row">주택형명</th><td>{value.name}</td><td>{selectedType.name}</td></tr>
            <tr><th scope="row">전용면적 (㎡)</th><td>{value.exclusiveArea ?? '미확인'}</td><td>{selectedType.exclusiveArea}</td></tr>
            <tr><th scope="row">공급면적 (㎡)</th><td>{value.supplyArea ?? '미확인'}</td><td>{selectedType.supplyArea ?? '미확인'}</td></tr>
          </tbody></table></div>}
        <p>이름·주소·면적은 비교 근거입니다. 같은 이름도 별개 주택형일 수 있으므로 공식 원문으로 대응을 확인하세요. 공고 모집수와 주택형 전체 세대수는 다른 범위입니다.</p>
      </section>}
    </fieldset>
    {error && <p role="alert">{error}</p>}
    <fieldset><legend>이 공고에만 적용할 공급정보</legend>
      {value.id ? <p className={styles.sourceName}>수집 원문 단지명: {value.complexName}</p>
        : <div className={styles.fields}>
          {complexes.length > 0 && <label>공고 단지<select value={value.announcementComplexId ?? ''} onChange={event => chooseAnnouncementComplex(event.target.value)}>
            <option value="">새 공고 단지 직접 입력</option>{complexes.map(complex => <option key={complex.id} value={complex.id}>{complex.name}</option>)}</select></label>}
          {value.announcementComplexId === null && <label>공고의 단지명<input required maxLength={255} value={value.complexName} onChange={event => field({ complexName: event.target.value })} /></label>}
        </div>}
      <div className={styles.fields}>
        <div className={styles.field}>
          <label>이 공고의 주택형 표기<input required maxLength={255} value={value.name} aria-describedby={`${value.key}-type-name`} onChange={event => field({ name: event.target.value })} /></label>
          <p id={`${value.key}-type-name`} className={styles.sourceName}>
            공개 화면에 이 표기로 보이며 이 공고에만 적용됩니다.
            {value.housingComplexId ? <> 단지 주택형 이름 자체가 틀렸다면 <a href={`/admin/complexes/${value.housingComplexId}?section=relations`} target="_blank" rel="noreferrer">단지 주택형 이름 고치기<NewWindowMark /></a></> : null}
          </p>
        </div>
        <label>공급 PNU<input maxLength={255} placeholder="지번을 모르면 비움" value={value.supplyPnu ?? ''} onChange={event => field({ supplyPnu: event.target.value || null })} /></label>
        <label>공급 구분<select value={value.category ?? ''} onChange={event => field({ category: (event.target.value || null) as SheetHousingType['category'] })}>
          <option value="">미확인</option><option value="NEW_SUPPLY">신규 공급</option><option value="RESUPPLY">재공급</option></select></label>
        <label>입주 예정 연월<input type="month" value={value.expectedMoveInMonth ?? ''} onChange={event => field({ expectedMoveInMonth: event.target.value || null })} /></label>
        <label>공고별 전용면적 (㎡)<input type="number" min="0" step="0.0001" value={value.exclusiveArea ?? ''} onChange={event => field({ exclusiveArea: number(event.target.value) })} /></label>
        <label>공고별 공급면적 (㎡)<input type="number" min="0" step="0.0001" value={value.supplyArea ?? ''} onChange={event => field({ supplyArea: number(event.target.value) })} /></label>
        <label>주택형 모집수 (세대)<input type="number" min="0" step="1" disabled={!cells.length} value={whole?.count ?? ''}
          onChange={event => onChange(setMembersCount(sheet, cells.map(cell => cell.key), 'WINNER', countInput(event.target.value)))} /></label>
      </div>
      <p className={styles.hint}>주택형 모집수는 이 주택형의 모든 공급대상이 함께 쓰는 모집 수입니다. 대상별로 나뉘어 적혀 있으면 비우고 공급대상 표에 적으세요. 전체 세대수는 바꾸지 않습니다.</p>
    </fieldset>
    <SupplyTargetDraft sheet={sheet} typeKey={typeKey} onChange={onChange} />
    <SharedCountsDraft sheet={sheet} typeKey={typeKey} onChange={onChange} />
  </fieldset></div>
}
