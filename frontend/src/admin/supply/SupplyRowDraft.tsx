import { useEffect, useRef, useState } from 'react'
import { labels } from '../management/fields'
import { ComplexPicker } from '../management/ComplexPicker'
import { getManagementDetail } from '../management/api'
import { isRecord, type ManagementDetailData } from '../management/managementContract'
import { getMatchingHousingTypes, type MatchingHousingType } from '../ingest/supplyMatchingApi'
import { blankTarget, type CorrectionRow, type CorrectionSource } from './supplyCorrectionApi'
import { StoredDataTable } from '../shared/StoredDataTable'
import { SupplyTargetDraft } from './SupplyTargetDraft'
import styles from './SupplyCorrection.module.css'

/** 공개 결과에 남는 행에서 단지·주택형 연결이 빠진 항목. 검토 대기열의 ‘단지·주택형 연결’과 같은 기준이다. */
function linkIssues(row: CorrectionRow): string[] {
  if (row.excluded) return []
  return [...(row.housingComplexId ? [] : ['단지 미연결']), ...(row.housingTypeId || row.newHousingType ? [] : ['주택형 미연결'])]
}

export function SupplyRowDraft({ value, onChange, onRemove, onMove, first, last, sources = [], inline = false, sharedCount, onDelete, deleted = false }: {
  value: CorrectionRow; onChange: (value: CorrectionRow) => void; onRemove: () => void;
  onMove: (delta: number) => void; first: boolean; last: boolean
  inline?: boolean; sharedCount?: number
  /** 저장된 행 중 관리자가 추가한 행(원천 없음)만 받는다. 누르면 삭제 예정으로 표시하고, 다시 누르면 취소한다. */
  onDelete?: () => void; deleted?: boolean
  /** 이 행이 기록한 원천행(마이홈·LH)과 작업실 원천 목록에서 찾은 값. */
  sources?: { category: string; sourceKey: string; found: CorrectionSource | null }[]
}) {
  const [types, setTypes] = useState<MatchingHousingType[]>([])
  const [candidateComplex, setCandidateComplex] = useState<ManagementDetailData | null>(null)
  const [error, setError] = useState(''); const [loading, setLoading] = useState(false)
  const [lookup, setLookup] = useState(false)
  const request = useRef<AbortController | null>(null)
  const complexId = value.housingComplexId
  const selectedType = value.newHousingType ?? types.find(type => type.id === value.housingTypeId)
  const address = candidateComplex?.data.address
  const candidatePnu = isRecord(address) && typeof address.pnu === 'string' ? address.pnu : null
  useEffect(() => {
    if (!complexId || !lookup || candidateComplex?.summary.id === complexId) return
    const controller = new AbortController(); request.current = controller; setLoading(true); setError('')
    void Promise.all([getManagementDetail('complexes', String(complexId), controller.signal), getMatchingHousingTypes(complexId, controller.signal)]).then(([detail, candidates]) => {
      if (!controller.signal.aborted) { setTypes(candidates); setCandidateComplex(detail) }
    }).catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '주택형을 조회하지 못했습니다.') })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [complexId, lookup, candidateComplex])
  function field<K extends keyof CorrectionRow>(key: K, next: CorrectionRow[K]) { onChange({ ...value, [key]: next }) }
  async function chooseComplex(id: number, name: string) {
    request.current?.abort(); const controller = new AbortController(); request.current = controller
    setLoading(true); setError('')
    try {
      const [detail, candidates] = await Promise.all([getManagementDetail('complexes', String(id), controller.signal), getMatchingHousingTypes(id, controller.signal)])
      if (controller.signal.aborted) return
      const address = detail.data.address
      const pnu = address && typeof address === 'object' && !Array.isArray(address) ? address.pnu : null
      onChange({ ...value, housingComplexId: id, housingComplexName: name, housingTypeId: null, newHousingType: null,
        complexName: value.complexName || name, pnu: typeof pnu === 'string' ? pnu : value.pnu })
      setTypes(candidates); setCandidateComplex(detail); setLookup(true)
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '단지를 조회하지 못했습니다.') }
    finally { if (!controller.signal.aborted) setLoading(false) }
  }
  useEffect(() => () => request.current?.abort(), [])
  const issues = linkIssues(value)
  // 마이홈 매핑은 공급대상을 만들지 않으므로 흔한 상태다. 알리기만 하고 행을 펼치지는 않는다.
  const withoutTargets = !value.excluded && value.targets.every(target => target.excluded)
  return <details className={styles.row} data-inline={inline || undefined} open={inline || !value.id || issues.length > 0}>
    <summary>{value.complexName || '새 공급행'} · {value.typeName || '주택형 미입력'}{deleted ? ' · 삭제 예정' : value.excluded ? ' · 제외 예정' : ''}
      {(issues.length > 0 || withoutTargets) && <span className={styles.issues}>{issues.map(issue => <span key={issue}>{issue}</span>)}
        {withoutTargets && <span data-tone="note">임대조건 없음</span>}</span>}
      <small>{value.protectedValues ? '보정 보호 중' : '자동 갱신 대상'} · 대상 {value.targets.length}개</small></summary>
    <fieldset className={styles.rowBody} disabled={loading}><legend className={styles.srOnly}>공급행 보정</legend>
      <div className={styles.actions}><button type="button" disabled={first} onClick={() => onMove(-1)}>위로</button><button type="button" disabled={last} onClick={() => onMove(1)}>아래로</button>
        {value.id ? <>{!deleted && <button type="button" onClick={() => field('excluded', !value.excluded)}>{value.excluded ? '공급행 복구' : '공급행 제외'}</button>}
          {onDelete && <button type="button" onClick={onDelete}>{deleted ? '삭제 취소' : '공급행 삭제'}</button>}</>
          : <button type="button" onClick={onRemove}>추가한 행 지우기</button>}</div>
      {deleted ? <p>적용하면 관리자가 추가한 이 행과 하위 조건을 지웁니다. 복구할 수 없고 변경 이력에만 남습니다.</p>
        : value.excluded && <p>적용하면 이 행과 하위 조건이 공개 결과에서 제외됩니다. 기록을 보존하며 다시 복구할 수 있습니다.</p>}
      {value.id && !inline && <section className={styles.rowSource} aria-label="대응 원천">
        {!sources.length && <p>이 행에 대응하는 원천을 정할 수 없습니다. 직접 추가했거나 원천 식별자가 없는 행, 또는 같은 순번의 LH 원천이 여러 개인 행입니다. 공식 자료로 확인하세요.</p>}
        {sources.map(source => source.found?.rows.length ? <details key={`${source.category}:${source.sourceKey}`}><summary>대응 원천 · {source.category} · {source.sourceKey}</summary>
            {source.found.rows.map((item, index) => <div key={index}><p>수집 시각: {item.collectedAt ? new Date(item.collectedAt).toLocaleString('ko-KR') : '알 수 없음'}</p>
              <StoredDataTable data={item.raw} label={`${source.category} 원천 ${index + 1}`} /></div>)}</details>
          : <p key={`${source.category}:${source.sourceKey}`}>기록된 원천 {source.sourceKey}을 지금 원천 목록에서 찾을 수 없습니다. 보정값은 유지되며 공식 자료를 직접 확인하세요.</p>)}
      </section>}
      <fieldset disabled={loading}><legend>연결할 단지·공통 주택형</legend>
        <p>현재 선택: {value.housingComplexName || '단지 미연결'} · {value.newHousingType ? '새 주택형 생성 예정' : value.housingTypeId ? `주택형 #${value.housingTypeId}` : '주택형 미연결'}</p>
        {!value.groupId && <ComplexPicker disabled={loading} onSelect={item => void chooseComplex(item.id, item.name)} />}
        {/* 연결 확인과 해제는 같은 단지 연결에 대한 동작이라 한 줄에 둔다. */}
        {(value.housingComplexId || !value.groupId) && <div className={styles.actions}>
          {value.housingComplexId && <button type="button" onClick={() => setLookup(true)}>이 단지의 주택형 확인</button>}
          {!value.groupId && <button type="button" onClick={() => { setTypes([]); setLookup(false); onChange({ ...value, housingComplexId: null, housingComplexName: null, housingTypeId: null, newHousingType: null }) }}>단지·주택형 연결 해제</button>}
        </div>}
        {value.housingComplexId && <>
          {lookup && <label>공통 주택형<select value={value.newHousingType ? 'new' : value.housingTypeId ?? ''} onChange={event => {
            if (event.target.value === 'new') onChange({ ...value, housingTypeId: null, newHousingType: { name: value.typeName, exclusiveArea: value.exclusiveArea ?? 0, supplyArea: value.supplyArea } })
            else onChange({ ...value, housingTypeId: event.target.value ? Number(event.target.value) : null, newHousingType: null })
          }}><option value="">미연결</option>{types.map(type => <option key={type.id} value={type.id}>{type.name} · 전용 {type.exclusiveArea}㎡ · #{type.id}</option>)}<option value="new">누락된 주택형 만들기</option></select></label>}</>}
        {value.newHousingType && <div className={styles.fields}>
          <label>새 공통 주택형 이름<input required maxLength={255} value={value.newHousingType.name} onChange={event => field('newHousingType', { ...value.newHousingType!, name: event.target.value })} /></label>
          <label>새 공통 전용면적 (㎡)<input required type="number" min="0.0001" step="0.0001" value={value.newHousingType.exclusiveArea || ''} onChange={event => field('newHousingType', { ...value.newHousingType!, exclusiveArea: Number(event.target.value) })} /></label>
          <label>새 공통 공급면적 (㎡)<input type="number" min="0" step="0.0001" value={value.newHousingType.supplyArea ?? ''} onChange={event => field('newHousingType', { ...value.newHousingType!, supplyArea: event.target.value === '' ? null : Number(event.target.value) })} /></label>
          <p>공통 주택형 생성과 이 행의 연결을 함께 적용합니다. 기존 공통 주택형은 변경하지 않습니다.</p>
        </div>}
        {lookup && candidateComplex && <section aria-label="연결 후보 비교">
          <p>선택 단지: {candidateComplex.summary.name} · {candidateComplex.summary.subtitle} · {labels[candidateComplex.summary.rental] ?? candidateComplex.summary.rental}</p>
          <p>공고 공급 주소 식별자: {value.pnu || '미확인'} / 단지 주소 식별자: {candidatePnu ?? '미확인'}</p>
          {selectedType && <div className="admin-table-scroll"><table className="admin-table"><caption>공고 입력값과 선택한 공통 주택형</caption>
            <thead><tr><th scope="col">비교 항목</th><th scope="col">공고 입력값</th><th scope="col">선택한 공통 주택형</th></tr></thead><tbody>
              <tr><th scope="row">주택형명</th><td>{value.typeName}</td><td>{selectedType.name}</td></tr>
              <tr><th scope="row">전용면적 (㎡)</th><td>{value.exclusiveArea ?? '미확인'}</td><td>{selectedType.exclusiveArea}</td></tr>
              <tr><th scope="row">공급면적 (㎡)</th><td>{value.supplyArea ?? '미확인'}</td><td>{selectedType.supplyArea ?? '미확인'}</td></tr>
            </tbody></table></div>}
          <p>이름·주소·면적은 비교 근거입니다. 같은 이름도 별개 주택형일 수 있으므로 공식 원문으로 대응을 확인하세요. 공고 모집수와 주택형 전체 세대수는 다른 범위입니다.</p>
        </section>}
      </fieldset>
      {error && <p role="alert">{error}</p>}
      {(value.groupId || sharedCount !== undefined) && <p>공통 모집수 {sharedCount ?? value.sharedHouseholdCount ?? '미확정'}세대는 묶음에 한 번만 보관합니다. 단지·공통 모집수 변경은 위의 ‘원천별 공급행 보정’에서 처리하세요.</p>}
      <fieldset><legend>이 공고에만 적용할 공급정보</legend>
      {/* 기존 행의 원천 단지명은 정제가 관리한다. 원천이 없는 새 행만 처음 표기를 입력한다. */}
      {value.id ? <p className={styles.sourceName}>수집 원문 단지명: {value.complexName}</p> : null}
      <div className={styles.fields}>
        {!value.id && <label>공고의 단지명<input required maxLength={255} value={value.complexName} onChange={event => field('complexName', event.target.value)} /></label>}
        {/* 표기 설명은 입력칸 바로 아래에 둬 다른 입력칸과 같은 칸을 차지하지 않게 한다. */}
        <div className={styles.field}>
          <label>이 공고의 주택형 표기<input required maxLength={255} value={value.typeName} aria-describedby={`${value.key}-type-name`} onChange={event => field('typeName', event.target.value)} /></label>
          <p id={`${value.key}-type-name`} className={styles.sourceName}>
            공개 화면에 이 표기로 보이며 이 공고에만 적용됩니다. 연결한 단지 주택형 이름과 같으면 단지 이름을 따라갑니다.
            {value.housingComplexId ? <> 단지 주택형 이름 자체가 틀렸다면 <a href={`/admin/complexes/${value.housingComplexId}?section=relations`} target="_blank" rel="noreferrer">단지 주택형 이름 고치기 →</a></> : null}
          </p>
        </div>
        <label>공급 PNU<input required maxLength={255} value={value.pnu} onChange={event => field('pnu', event.target.value)} /></label>
        <label>공급 구분<select value={value.category} onChange={event => field('category', event.target.value as CorrectionRow['category'])}><option value="NEW_SUPPLY">신규 공급</option><option value="RESUPPLY">재공급</option></select></label>
        <label>입주 예정 연월<input type="month" value={value.moveInMonth ?? ''} onChange={event => field('moveInMonth', event.target.value || null)} /></label>
        <label>금회 모집수 (세대)<input type="number" min="0" step="1" disabled={!!value.groupId || sharedCount !== undefined} value={value.householdCount ?? ''} onChange={event => field('householdCount', event.target.value === '' ? null : Number(event.target.value))} /></label>
      </div><p>빈 수량·금액은 미확인, 숫자 0은 확인된 0입니다. 전체 세대수는 변경하지 않습니다.</p>
      <label className={styles.check}><input type="checkbox" checked={value.areaOverride} onChange={event => field('areaOverride', event.target.checked)} />이 공고의 면적을 별도로 보정·보호</label>
      <div className={styles.fields}>{(['exclusiveArea', 'supplyArea'] as const).map(key => <label key={key}>{key === 'exclusiveArea' ? '공고별 전용면적 (㎡)' : '공고별 공급면적 (㎡)'}<input type="number" min="0" step="0.0001" disabled={!value.areaOverride} value={value[key] ?? ''} onChange={event => field(key, event.target.value === '' ? null : Number(event.target.value))} /></label>)}</div>
      {!value.areaOverride && <p>연결한 공통 주택형의 면적을 참조합니다. 공고 당시 면적을 확인했다면 별도로 보정·보호해 주세요.</p>}
      </fieldset>
      <h4>공급대상별 수량·임대조건</h4>
      {value.targets.map(target => <SupplyTargetDraft key={target.key} value={target} onChange={next => field('targets', value.targets.map(item => item.key === next.key ? next : item))} onRemove={() => field('targets', value.targets.filter(item => item.key !== target.key))} />)}
      <button type="button" onClick={() => field('targets', [...value.targets, blankTarget(value.targets.length)])}>공급대상 추가</button>
      <label className={styles.check}><input type="checkbox" checked={value.targetCountsComparable} onChange={event => field('targetCountsComparable', event.target.checked)} />대상 모집수가 서로 중복되지 않고 행 모집수와 같은 범위임을 확인했습니다.</label>
    </fieldset>
  </details>
}
