import { useEffect, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { createHousingComplex } from '../registration/api'
import { type AddressCandidate, lookupAddresses } from './addressLookupApi'
import styles from './PdfCorrection.module.css'

type Props = { index: number; defaultName: string; defaultAddress?: string; defaultHouseholds?: number | null; defaultParking?: number | null
  /** LH 원천행의 PNU. 같은 필지로 등록해야 다음 정제에서 원천행이 이 단지를 찾는다. */
  defaultPnu?: string
  rentalType: string; agencyCode: string; onCreated: (complex: { id: number; name: string }) => void }
const PNU = /^\d{19}$/

/**
 * 공고에는 있지만 단지 DB에 없는 새 단지(예: 입주 전 최초 모집)를 PDF 검토 안에서 바로 등록한다.
 * 공급유형·기관은 공고 값을 쓰고, 법정동·시도·시군구 코드는 PNU 앞자리로 채운다. 준공일과 건물 정보는 비워 둘 수 있다.
 * 공고문 단지 개요(주소·세대수·주차대수)를 미리 채우고 그 주소로 바로 검색한다. 후보를 고르면 주소·PNU·좌표가 채워지고,
 * 검색을 쓸 수 없으면 직접 입력한다.
 */
export function NewComplexRegistration({ index, defaultName, defaultAddress = '', defaultHouseholds = null, defaultParking = null, defaultPnu = '', rentalType, agencyCode, onCreated }: Props) {
  const [values, setValues] = useState({ name: defaultName, address: defaultAddress, pnu: defaultPnu, latitude: '', longitude: '',
    households: defaultHouseholds === null ? '' : String(defaultHouseholds), parking: defaultParking === null ? '' : String(defaultParking), completionDate: '' })
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const [keyword, setKeyword] = useState(defaultAddress), [searching, setSearching] = useState(false)
  const [candidates, setCandidates] = useState<AddressCandidate[] | null>(null), [searchError, setSearchError] = useState('')
  const set = (key: keyof typeof values) => (event: { target: { value: string } }) => setValues(current => ({ ...current, [key]: event.target.value }))
  const problem = !values.name.trim() ? '단지명을 입력하세요.' : !values.address.trim() ? '주소를 입력하세요.' : !PNU.test(values.pnu) ? 'PNU 19자리를 입력하세요.'
    : !values.latitude || !values.longitude ? '위도·경도를 입력하세요.' : values.households === '' ? '전체 세대수를 입력하세요.' : values.parking === '' ? '주차대수를 입력하세요.' : ''
  async function search() {
    if (!keyword.trim() || searching) return
    setSearching(true); setSearchError(''); setCandidates(null)
    try { setCandidates(await lookupAddresses(keyword.trim())) }
    catch (cause) { setSearchError(`${cause instanceof Error ? cause.message : '주소를 검색하지 못했습니다.'} 주소·PNU·좌표를 직접 입력하세요.`) }
    finally { setSearching(false) }
  }
  // 공고문 주소가 있으면 열 때 그 주소로 한 번 검색한다.
  useEffect(() => {
    if (!defaultAddress.trim()) return
    const controller = new AbortController()
    setSearching(true)
    lookupAddresses(defaultAddress.trim(), controller.signal).then(setCandidates, cause => {
      if (!controller.signal.aborted) setSearchError(`${cause instanceof Error ? cause.message : '주소를 검색하지 못했습니다.'} 주소·PNU·좌표를 직접 입력하세요.`)
    }).finally(() => { if (!controller.signal.aborted) setSearching(false) })
    return () => controller.abort()
  }, [defaultAddress])
  function choose(candidate: AddressCandidate) {
    setValues(current => ({ ...current, address: candidate.roadAddress || candidate.jibunAddress, pnu: candidate.pnu,
      latitude: candidate.latitude === null ? current.latitude : String(candidate.latitude),
      longitude: candidate.longitude === null ? current.longitude : String(candidate.longitude) }))
    setCandidates(null)
  }
  async function register() {
    if (problem || busy) return
    setBusy(true); setError('')
    try {
      const created = await createHousingComplex({ name: values.name.trim(), rentalType, agencyCode,
        address: { roadAddress: values.address.trim(), pnu: values.pnu, legalDongCode: values.pnu.slice(0, 10), provinceCode: values.pnu.slice(0, 2),
          cityCountyDistrictCode: values.pnu.slice(0, 5), latitude: Number(values.latitude), longitude: Number(values.longitude) },
        totalHouseholdCount: Number(values.households), totalParkingCount: Number(values.parking), completionDate: values.completionDate || null,
        heatingType: null, buildingType: null, corridorType: null, hasElevator: null, overviewImageUrl: null, moveOutCountLastYear: null })
      onCreated({ id: created.housingComplexId, name: created.name })
    } catch (cause) { setError(cause instanceof Error ? cause.message : '단지를 등록하지 못했습니다.') }
    finally { setBusy(false) }
  }
  return <fieldset className={styles.newComplex} disabled={busy} aria-label={`공급행 ${index + 1} 새 단지 등록`}>
    <legend>새 단지로 등록</legend>
    <label>단지명<input value={values.name} maxLength={255} onChange={set('name')} /></label>
    <div className={styles.addressSearch}>
      <label>주소 검색<input value={keyword} maxLength={200} placeholder="공고문 단지 주소" onChange={event => setKeyword(event.target.value)}
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
    <label>주소(도로명 또는 지번)<input value={values.address} maxLength={255} onChange={set('address')} /></label>
    <label>PNU<input value={values.pnu} inputMode="numeric" maxLength={19} placeholder="19자리 필지 번호" onChange={set('pnu')} /></label>
    <label>위도<input value={values.latitude} type="number" step="0.000001" onChange={set('latitude')} /></label>
    <label>경도<input value={values.longitude} type="number" step="0.000001" onChange={set('longitude')} /></label>
    <label>전체 세대수<input value={values.households} type="number" min={0} onChange={set('households')} /></label>
    <label>주차대수<input value={values.parking} type="number" min={0} onChange={set('parking')} /></label>
    <label>준공일 (선택)<input value={values.completionDate} type="date" onChange={set('completionDate')} /></label>
    {error && <p role="alert" className={styles.error}>{error}</p>}
    <Button data-design-system disabled={Boolean(problem) || busy} title={problem || undefined} onClick={() => void register()}>{busy ? '등록하는 중…' : '단지 등록하고 연결'}</Button>
    {problem && <p className={styles.hint}>{problem}</p>}
  </fieldset>
}
