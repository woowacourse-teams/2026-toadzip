import { useEffect, useRef, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { labels, rentals } from '../management/fields'
import { AdminRegistrationApiError, createHousingComplex } from '../registration/api'
import { rentalTypeOptions } from '../registration/registrationOptions'
import { type AddressCandidate, type RegisteredComplex, lookupAddresses } from './addressLookupApi'
import styles from './PdfCorrection.module.css'

/** 수집한 마이홈 공고의 단지 정보. 공고문 개요에 없는 값을 채우고, 난방·주택유형은 그대로 함께 저장한다. 주차대수와 좌표는 마이홈에도 없다. */
export type MyHomeComplexOverview = { name: string | null; address: string | null; pnu: string | null; households: number | null; heating: string | null; building: string | null }
type Props = { index: number; defaultName: string; defaultAddress?: string; defaultHouseholds?: number | null; defaultParking?: number | null
  myhome?: MyHomeComplexOverview
  /** LH 원천행의 PNU. 같은 필지로 등록해야 다음 정제에서 원천행이 이 단지를 찾는다. */
  defaultPnu?: string
  /** 공고의 공급유형. 원천이 주지 않아 비어 있으면(SH 등) 운영자가 이 폼에서 고른다. */
  rentalType: string; agencyCode: string; onCreated: (complex: { id: number; name: string }) => void
  /** 같은 주소에 이미 있는 단지를 골랐다. 새로 만들지 않고 그 단지에 연결한다. */
  onSelectExisting: (complex: { id: number; name: string }) => void }
const PNU = /^\d{19}$/
/** 서버는 좌표를 소수 여섯 자리(약 0.1m)까지 받는다. 주소 검색 좌표는 여덟 자리라 맞춰 보낸다. */
const coordinate = (value: number) => Math.round(value * 1e6) / 1e6
const rentalLabel = (value: string | null) => rentalTypeOptions.find(option => option.value === value)?.label ?? null
const heatingTypes: Record<string, 'INDIVIDUAL' | 'CENTRAL' | 'DISTRICT'> = { 개별난방: 'INDIVIDUAL', 중앙난방: 'CENTRAL', 지역난방: 'DISTRICT' }
const buildingTypes: Record<string, 'APARTMENT' | 'OFFICETEL'> = { 아파트: 'APARTMENT', 오피스텔: 'OFFICETEL' }
const fieldLabels: Record<string, string> = { 'address.latitude': '위도', 'address.longitude': '경도', 'address.pnu': 'PNU', 'address.roadAddress': '주소',
  totalHouseholdCount: '전체 세대수', totalParkingCount: '주차대수', name: '단지명', completionDate: '준공일' }

/**
 * 공고에는 있지만 단지 DB에 없는 새 단지(예: 입주 전 최초 모집)를 PDF 검토 안에서 바로 등록한다.
 * 공급유형·기관은 공고 값을 쓰고, 법정동·시도·시군구 코드는 PNU 앞자리로, PNU가 없으면 고른 주소 검색 결과로 채운다. 준공일과 건물 정보는 비워 둘 수 있다.
 * 공고문 단지 개요(주소·세대수·주차대수)를 미리 채우고 그 주소로 바로 검색한다. 후보를 고르면 주소·PNU·행정구역 코드·좌표가
 * 채워지고, 검색을 쓸 수 없으면 직접 입력한다. 지번을 모르면 PNU는 비워 두고, 그때 행정구역 코드는 고른 검색 결과에서만 얻는다.
 */
export function NewComplexRegistration({ index, defaultName, defaultAddress = '', defaultHouseholds = null, defaultParking = null, defaultPnu = '', myhome, rentalType, agencyCode, onCreated, onSelectExisting }: Props) {
  const households = defaultHouseholds ?? myhome?.households ?? null, initialAddress = defaultAddress || myhome?.address || ''
  // 원천행(LH)·마이홈이 알려 준 필지. 주소 검색 후보가 같은 법정동이면 이 필지를 유지해야 원천행이 이 단지를 찾는다.
  const sourcePnu = PNU.test(defaultPnu) ? defaultPnu : PNU.test(myhome?.pnu ?? '') ? myhome!.pnu! : ''
  const [values, setValues] = useState({ name: defaultName, address: initialAddress, pnu: sourcePnu, latitude: '', longitude: '',
    households: households === null ? '' : String(households), parking: defaultParking === null ? '' : String(defaultParking), completionDate: '' })
  const heatingType = myhome?.heating ? heatingTypes[myhome.heating] ?? 'ETC' : null
  const buildingType = myhome?.building ? buildingTypes[myhome.building] ?? 'ETC' : null
  const lookupRequest = useRef<AbortController | null>(null)
  const [advanced, setAdvanced] = useState(false)
  const [addressStatus, setAddressStatus] = useState('')
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [type, setType] = useState(rentalType)
  const [keyword, setKeyword] = useState(initialAddress), [searching, setSearching] = useState(false)
  const [candidates, setCandidates] = useState<AddressCandidate[] | null>(null), [searchError, setSearchError] = useState('')
  // 고른 검색 결과의 행정구역 코드. 주소를 직접 고치면 그 주소의 코드가 아니므로 버린다.
  const [regionCodes, setRegionCodes] = useState<Pick<AddressCandidate, 'legalDongCode' | 'provinceCode' | 'cityCountyDistrictCode'> | null>(null)
  // 고른 주소에 이미 등록된 단지. 있으면 새로 만들기보다 그 단지에 연결하도록 먼저 보인다.
  const [existing, setExisting] = useState<RegisteredComplex[]>([])
  const set = (key: keyof typeof values) => (event: { target: { value: string } }) => {
    if (['address', 'pnu', 'latitude', 'longitude'].includes(key)) { lookupRequest.current?.abort(); setSearching(false) }
    if (key === 'address') {
      lookupRequest.current?.abort(); setSearching(false); setRegionCodes(null); setAddressStatus(''); clearExisting()
      setValues(current => ({ ...current, address: event.target.value, pnu: '', latitude: '', longitude: '' }))
      return
    }
    setValues(current => ({ ...current, [key]: event.target.value }))
  }
  const pnu = values.pnu.trim()
  // PNU가 있으면 그 앞자리가 행정구역 코드다. PNU가 없을 때만 고른 검색 결과의 코드를 쓴다.
  const codes = PNU.test(pnu) ? { legalDongCode: pnu.slice(0, 10), provinceCode: pnu.slice(0, 2), cityCountyDistrictCode: pnu.slice(0, 5) } : regionCodes
  const problem = !values.name.trim() ? '단지명을 입력하세요.' : !type ? '공급유형을 고르세요.' : !values.address.trim() ? '주소를 검색해 선택하거나 상세에서 직접 입력하세요.' : pnu && !PNU.test(pnu) ? 'PNU는 19자리 숫자로 입력하거나 비워 두세요.'
    : !codes ? '행정구역 코드를 정하려면 주소 검색 결과를 고르거나 PNU를 입력하세요.'
    : !values.latitude || !values.longitude ? '이 주소는 검색 결과에 좌표가 없습니다. 주소 상세에서 위도·경도를 입력하세요.'
    : !Number.isFinite(Number(values.latitude)) || Math.abs(Number(values.latitude)) > 90 || !Number.isFinite(Number(values.longitude)) || Math.abs(Number(values.longitude)) > 180 ? '위도·경도 범위를 확인하세요.'
    : [values.households, values.parking].some(value => value !== '' && (!Number.isSafeInteger(Number(value)) || Number(value) < 0 || Number(value) > 2147483647)) ? '세대수·주차대수는 0 이상의 정수로 입력하거나 비워 두세요.' : ''
  function clearExisting() { setExisting([]) }
  function choose(candidate: AddressCandidate, automatic = false) {
    // 같은 법정동이면 원천행 필지를 유지한다. 대표 필지와 건물 필지가 달라도 원천행과 같은 단지로 이어져야 한다.
    const pnu = sourcePnu && sourcePnu.slice(0, 10) === candidate.legalDongCode ? sourcePnu : candidate.pnu
    setRegionCodes({ legalDongCode: candidate.legalDongCode, provinceCode: candidate.provinceCode, cityCountyDistrictCode: candidate.cityCountyDistrictCode })
    setValues(current => ({ ...current, address: candidate.roadAddress || candidate.jibunAddress, pnu,
      latitude: candidate.latitude === null ? '' : String(coordinate(candidate.latitude)),
      longitude: candidate.longitude === null ? '' : String(coordinate(candidate.longitude)) }))
    const noCoordinate = candidate.latitude === null || candidate.longitude === null
    setAddressStatus(`${automatic ? '공고문 주소와 같은 주소를 찾아 채웠습니다.' : '고른 주소를 채웠습니다.'}${noCoordinate ? ' 좌표는 검색 결과에 없어 직접 입력해야 합니다.' : ''}`)
    if (noCoordinate) setAdvanced(true)
    setCandidates(null)
    setExisting(candidate.registeredComplexes ?? [])
  }
  function showResults(results: AddressCandidate[]) {
    // 서버가 검색어와 같은 도로명주소·지번이라고 판정한 후보가 하나뿐일 때만 대신 고른다.
    const matches = results.filter(candidate => candidate.matchesKeyword)
    if (matches.length === 1) choose(matches[0], true)
    else { setCandidates(results); if (!results.length) setAdvanced(true) }
  }
  async function search() {
    if (!keyword.trim() || searching) return
    lookupRequest.current?.abort()
    const controller = new AbortController(); lookupRequest.current = controller
    setSearching(true); setSearchError(''); setCandidates(null)
    try {
      const results = await lookupAddresses(keyword.trim(), controller.signal, sourcePnu)
      if (!controller.signal.aborted) showResults(results)
    } catch (cause) {
      if (!controller.signal.aborted) { setSearchError(`${cause instanceof Error ? cause.message : '주소를 검색하지 못했습니다.'} 주소·PNU·좌표를 직접 입력하세요.`); setAdvanced(true) }
    } finally { if (!controller.signal.aborted) setSearching(false) }
  }
  // 처음 검색 결과는 그때의 최신 처리 함수로 반영한다.
  const showLatest = useRef(showResults); showLatest.current = showResults
  // 공고문 주소로 검색하고, 서버가 같은 주소로 판정한 후보 하나만 자동 반영한다.
  useEffect(() => {
    if (!initialAddress.trim()) return
    const controller = new AbortController(); lookupRequest.current = controller
    setSearching(true)
    lookupAddresses(initialAddress.trim(), controller.signal, sourcePnu).then(results => {
      if (!controller.signal.aborted) showLatest.current(results)
    }, cause => {
      if (!controller.signal.aborted) { setSearchError(`${cause instanceof Error ? cause.message : '주소를 검색하지 못했습니다.'} 주소·PNU·좌표를 직접 입력하세요.`); setAdvanced(true) }
    }).finally(() => { if (!controller.signal.aborted) setSearching(false) })
    return () => controller.abort()
    // 최초 개요 주소만 자동 검색하고 사용자가 수정한 값은 덮어쓰지 않는다.
  }, [initialAddress, sourcePnu])
  useEffect(() => () => lookupRequest.current?.abort(), [])
  async function register() {
    if (problem || busy) return
    setBusy(true); setError('')
    try {
      const created = await createHousingComplex({ name: values.name.trim(), rentalType: type, agencyCode,
        address: { roadAddress: values.address.trim(), pnu: pnu || null, ...codes!, latitude: coordinate(Number(values.latitude)), longitude: coordinate(Number(values.longitude)) },
        totalHouseholdCount: values.households === '' ? null : Number(values.households), totalParkingCount: values.parking === '' ? null : Number(values.parking), completionDate: values.completionDate || null,
        heatingType, buildingType, corridorType: null, hasElevator: null, overviewImageUrl: null, moveOutCountLastYear: null })
      onCreated({ id: created.housingComplexId, name: created.name })
    } catch (cause) {
      // 서버가 항목별 사유를 주면 어느 칸이 틀렸는지 함께 보여 준다.
      const fields = cause instanceof AdminRegistrationApiError ? Object.entries(cause.fieldErrors).map(([field, message]) => `${fieldLabels[field] ?? field}: ${message}`) : []
      setError([cause instanceof Error ? cause.message : '단지를 등록하지 못했습니다.', ...fields].join(' '))
    }
    finally { setBusy(false) }
  }
  return <fieldset className={styles.newComplex} disabled={busy} aria-label={`공급행 ${index + 1} 새 단지 등록`}>
    <legend>새 단지로 등록</legend>
    <p className={styles.hint}>주소를 확인한 뒤 등록하세요. 전체 세대수·주차대수·준공일은 몰라도 등록할 수 있습니다.</p>
    <label>단지명<input value={values.name} maxLength={255} onChange={set('name')} /></label>
    <label>공급유형<select value={type} onChange={event => setType(event.target.value)}><option value="">선택하세요</option>{rentals.map(item => <option key={item} value={item}>{labels[item] ?? item}</option>)}</select></label>
    {myhome && (heatingType || buildingType) && <p className={styles.hint}>{`마이홈 공고 정보로 ${[myhome.heating, myhome.building].filter(Boolean).join(' · ')}을(를) 함께 저장합니다. 주차대수는 마이홈에도 없어 모르면 비워 두세요.`}</p>}
    <div className={styles.addressSearch}>
      <label>주소 검색<input value={keyword} maxLength={200} placeholder="공고문 단지 주소" onChange={event => {
          lookupRequest.current?.abort(); setSearching(false); setKeyword(event.target.value); setAddressStatus(''); setRegionCodes(null); setCandidates(null); setSearchError(''); clearExisting()
          setValues(current => ({ ...current, address: '', pnu: '', latitude: '', longitude: '' }))
        }}
        onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); void search() } }} /></label>
      <Button data-design-system className={styles.secondaryButton} disabled={!keyword.trim() || searching} onClick={() => void search()}>{searching ? '검색 중…' : '검색'}</Button>
    </div>
    {searchError && <p role="alert" className={styles.error}>{searchError}</p>}
    {candidates && (candidates.length ? <ul className={styles.addressCandidates} aria-label="주소 검색 결과">
      {candidates.map(candidate => <li key={candidate.pnu + candidate.roadAddress}><button type="button" onClick={() => choose(candidate)}>
        <strong>{candidate.roadAddress || candidate.jibunAddress}</strong>{candidate.roadAddress && <span>{candidate.jibunAddress}</span>}
        {candidate.latitude === null && <span>좌표 없음 · 직접 입력</span>}
      </button></li>)}
    </ul> : <p className={styles.hint}>검색 결과가 없습니다. 지번 주소로 다시 검색하거나 직접 입력하세요.</p>)}
    {addressStatus && <div role="status" className={styles.addressStatus}><strong>{values.address}</strong><span>{addressStatus}</span></div>}
    {existing.length > 0 && <div className={styles.pending} role="group" aria-label="같은 주소의 등록된 단지">
      <p><strong>이 주소에 이미 등록된 단지가 있습니다.</strong> 같은 단지라면 새로 만들지 말고 연결하세요.</p>
      <ul className={styles.existingComplexes}>{existing.map(item => <li key={item.housingComplexId}>
        <span>{item.name}{` · ${[rentalLabel(item.rentalType), item.roadAddress].filter(Boolean).join(' · ')}`}</span>
        <Button data-design-system className={styles.secondaryButton} disabled={busy} onClick={() => onSelectExisting({ id: item.housingComplexId, name: item.name })}>{`${item.name}에 연결`}</Button></li>)}</ul>
    </div>}
    <details className={styles.registrationDetails} open={advanced} onToggle={event => setAdvanced(event.currentTarget.open)}>
      <summary>주소 상세·추가 정보</summary>
      <div className={styles.registrationGrid}>
        <label className={styles.fullWidth}>주소(도로명 또는 지번)<input value={values.address} maxLength={255} onChange={set('address')} /></label>
        <label>PNU<input value={values.pnu} inputMode="numeric" maxLength={19} placeholder="모르면 비워 두세요" onChange={set('pnu')} /></label>
        <label>전체 세대수<input value={values.households} type="number" min={0} step={1} placeholder="미확인 · 비워 두어도 등록 가능" onChange={set('households')} /></label>
        <label>위도<input value={values.latitude} type="number" step="0.000001" onChange={set('latitude')} /></label>
        <label>경도<input value={values.longitude} type="number" step="0.000001" onChange={set('longitude')} /></label>
        <label>주차대수<input value={values.parking} type="number" min={0} step={1} placeholder="모르면 비워 두세요" onChange={set('parking')} /></label>
        <label>준공일 (선택)<input value={values.completionDate} type="date" onChange={set('completionDate')} /></label>
      </div>
    </details>
    {error && <p role="alert" className={styles.error}>{error}</p>}
    <Button data-design-system className={existing.length ? styles.secondaryButton : undefined} disabled={Boolean(problem) || busy} title={problem || undefined} onClick={() => void register()}>{busy ? '등록하는 중…' : existing.length ? '다른 단지라 새로 등록하고 연결' : '단지 등록하고 연결'}</Button>
    {problem && <p className={styles.hint}>{problem}</p>}
  </fieldset>
}
