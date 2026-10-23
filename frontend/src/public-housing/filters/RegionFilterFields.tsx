import { useEffect, useMemo, useRef, useState } from 'react'
import gridStyles from './RegionGrid.module.css'
import type { PublicHousingRegionRepository } from '../api/publicHousingRegionRepository.ts'
import {
  districtRegionOptionsForProvince,
  neighborhoodRegionOptionsForDistrict,
  neighborhoodLabel,
  findCatalogRegion,
  PUBLIC_HOUSING_PROVINCE_OPTIONS,
  provinceNameForRegionCode,
  type PublicHousingRegion,
} from '../model/publicHousingRegion.ts'

interface RegionFilterFieldsProps {
  readonly grid?: boolean
  readonly onRegionReset?: (regionCode: string) => void
  readonly initialRegionCode: string
  readonly messageId: string
  readonly loadingMessage: string
  readonly repository: PublicHousingRegionRepository
  readonly styles: {
    readonly regionFields: string
    readonly field: string
    readonly regionMessage?: string
    readonly regionError: string
  }
}

export function RegionFilterFields({
  initialRegionCode, messageId, loadingMessage, repository, styles, grid = false, onRegionReset,
}: RegionFilterFieldsProps) {
  const {
    provinceCode, districtCode, districtOptions, neighborhoodCode, neighborhoodOptions, loadStatus, fieldsRef,
    selectProvince, selectDistrict, selectNeighborhood,
  } = useRegionSelection(initialRegionCode, repository)

  const [stage, setStage] = useState(0)
  const stageButtons = useRef<(HTMLButtonElement | null)[]>([])
  const provinceShortNames = ['서울', '전남광주', '부산', '대구', '인천', '대전', '울산', '세종', '경기', '강원', '충북', '충남', '전북', '경북', '경남', '제주']
  const provinceLabel = provinceShortNames[PUBLIC_HOUSING_PROVINCE_OPTIONS.findIndex(([code]) => code === provinceCode)] ?? '전국'
  const districtLabel = districtOptions.find(item => item.regionCode === districtCode)?.districtName ?? '시·군·구'
  const neighborhood = neighborhoodOptions.find(item => item.regionCode === neighborhoodCode)
  const stages = [
    { name: 'provinceCode', title: '시·도', value: provinceCode, options: [['', '전체'], ...PUBLIC_HOUSING_PROVINCE_OPTIONS.map(([code], index) => [code, provinceShortNames[index]])] },
    { name: 'districtCode', title: '시·군·구', value: districtCode, options: [['', `${provinceLabel} 전체`], ...districtOptions.map(item => [item.regionCode, item.districtName ?? item.regionCode])] },
    { name: 'neighborhoodCode', title: '읍·면·동', value: neighborhoodCode, options: [['', '전체'], ...neighborhoodOptions.map(item => [item.regionCode, neighborhoodLabel(item, districtCode)])] },
  ]
  function chooseStage(next: number) {
    setStage(next)
    requestAnimationFrame(() => stageButtons.current[next]?.focus())
  }
  function navigateStage(next: number) {
    if (next < stage) {
      if (next === 0) {
        selectProvince(provinceCode)
        onRegionReset?.(provinceCode)
      } else if (next === 1) {
        selectDistrict(districtCode)
        onRegionReset?.(districtCode || provinceCode)
      }
    }
    chooseStage(next)
  }
  if (grid) return (
    <div className={gridStyles.root} ref={fieldsRef}>
      <p className={gridStyles.selectionStatus} role="status" aria-label="현재 지역">{provinceCode ? `${provinceLabel}${districtCode ? ` ${districtLabel}` : ' 전체'}${neighborhood ? ` ${neighborhoodLabel(neighborhood, districtCode)}` : ''}` : '전국'}</p>
      <div className={gridStyles.path} aria-label="지역 선택 단계">
        {[provinceLabel, districtLabel, neighborhood ? neighborhoodLabel(neighborhood, districtCode) : '읍·면·동'].map((label, index) => (
          <button key={index} ref={node => { stageButtons.current[index] = node }} type="button"
            aria-pressed={stage === index} aria-label={`${stages[index].title} 선택`}
            disabled={index === 1 ? !provinceCode : index === 2 ? !districtCode : false}
            onClick={() => navigateStage(index)}>{label}</button>
        ))}
      </div>
      {stage > 0 && <button type="button" className={gridStyles.back}
        aria-label={`${stages[stage - 1].title} 선택으로 돌아가기`}
        onClick={() => navigateStage(stage - 1)}>
        <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="m12 5-5 5 5 5" /></svg>
        이전 단계
      </button>}
      {stages.map((item, index) => (
        <fieldset key={item.name} hidden={stage !== index} className={gridStyles.group}
          aria-describedby={index > 0 && loadStatus === 'error' ? messageId : undefined}>
          <legend>{item.title} 선택</legend>
          <div className={gridStyles.options}>
            {item.options.map(([value, label]) => (
              <label key={value} className={gridStyles.option}>
                <input type="radio" name={item.name} value={value} checked={item.value === value}
                  onClick={() => {
                    // 이전 단계에서 같은 상위 지역을 다시 골라도 하위 목록을 연다.
                    if (item.value === value && value && index < 2) chooseStage(index + 1)
                  }}
                  onChange={() => {
                    if (index === 0) { selectProvince(value); if (value) chooseStage(1) }
                    else if (index === 1) { selectDistrict(value); if (value) chooseStage(2) }
                    else selectNeighborhood(value)
                  }} />
                <span>{label}</span>
              </label>
            ))}
          </div>
        </fieldset>
      ))}
      {loadStatus === 'loading' && stage > 0 && <p className={gridStyles.message} role="status">지역을 불러오고 있어요.</p>}
      {loadStatus === 'error' && <p id={messageId} role="alert" className={gridStyles.error}>지역을 불러오지 못했어요. 상위 지역 전체를 선택하거나 다른 시·도를 선택해 주세요.</p>}
      {loadStatus === 'idle' && stage > 0 && stages[stage].options.length === 1 && <p className={gridStyles.message}>선택할 하위 지역이 없어요. 상위 지역 전체로 검색할 수 있어요.</p>}
    </div>
  )

  return (
    <div className={styles.regionFields} ref={fieldsRef}>
      <label className={styles.field}>
        <span>시·도</span>
        <select
          name="provinceCode"
          value={provinceCode}
          onChange={(event) => selectProvince(event.currentTarget.value)}
        >
          <option value="">전체</option>
          {PUBLIC_HOUSING_PROVINCE_OPTIONS.map(([value, optionLabel]) => (
            <option key={value} value={value}>{optionLabel}</option>
          ))}
        </select>
      </label>

      <label className={styles.field}>
        <span>시·군·구</span>
        <select
          name="districtCode"
          value={districtCode}
          disabled={provinceCode === ''}
          aria-describedby={loadStatus === 'error'
            ? messageId
            : undefined}
          onChange={(event) => selectDistrict(event.currentTarget.value)}
        >
          <option value="">
            {provinceCode === '' ? '시·도를 먼저 선택' : '전체'}
          </option>
          {districtOptions.map(({ districtName, regionCode }) => (
            <option key={regionCode} value={regionCode}>
              {districtName ?? regionCode}
            </option>
          ))}
        </select>
      </label>

      <label className={styles.field}>
        <span>읍·면·동</span>
        <select name="neighborhoodCode" value={neighborhoodCode}
          disabled={districtCode === '' || (loadStatus !== 'idle' && neighborhoodCode === '')}
          onChange={(event) => selectNeighborhood(event.currentTarget.value)}>
          <option value="">{districtCode === '' ? '시·군·구를 먼저 선택' : '전체'}</option>
          {neighborhoodOptions.map((region) => (
            <option key={region.regionCode} value={region.regionCode}>
              {neighborhoodLabel(region, districtCode)}
            </option>
          ))}
        </select>
      </label>

      {loadStatus === 'loading' && (
        <small className={styles.regionMessage} role="status">
          {loadingMessage}
        </small>
      )}
      {loadStatus === 'error' && (
        <small
          className={styles.regionError}
          id={messageId}
          role="alert"
        >
          시·군·구를 불러오지 못했어요. 시·도만 적용할 수 있어요.
        </small>
      )}
    </div>
  )
}

function useRegionSelection(
  initialRegionCode: string,
  repository: PublicHousingRegionRepository,
) {
  const initialProvinceCode = provinceCodeFrom(initialRegionCode)
  const initialDistrictCode = initialRegionCode.length >= 5 ? initialRegionCode.slice(0, 5) : ''
  const initialNeighborhoodCode = initialRegionCode.length === 10 ? initialRegionCode : ''
  const [neighborhoodCode, setNeighborhoodCode] = useState(initialNeighborhoodCode)
  const [provinceCode, setProvinceCode] = useState(initialProvinceCode)
  const [districtCode, setDistrictCode] = useState(initialDistrictCode)
  const [regions, setRegions] = useState<readonly PublicHousingRegion[]>([])
  const [loadStatus, setLoadStatus] = useState<'idle' | 'loading' | 'error'>('idle')
  const fieldsRef = useRef<HTMLDivElement>(null)
  const provinceName = provinceNameForRegionCode(provinceCode)
  const districtOptions = useMemo(() => {
    const options = districtRegionOptionsForProvince(regions, provinceCode)
    if (districtCode === '' || options.some(({ regionCode }) => regionCode === districtCode)) {
      return options
    }
    const selectedRegion = regions.find(({ regionCode }) => regionCode === districtCode)
      ?? selectedRegionFallback(districtCode, provinceName)
    return [selectedRegion, ...options]
  }, [districtCode, provinceCode, provinceName, regions])

  const neighborhoodOptions = useMemo(() => {
    const options = neighborhoodRegionOptionsForDistrict(regions, districtCode)
    if (!neighborhoodCode || options.some((region) => region.regionCode === neighborhoodCode)) return options
    const selected = selectedRegionFallback(neighborhoodCode, provinceName)
    return [selected, ...options]
  }, [regions, districtCode, neighborhoodCode, provinceName])

  useEffect(() => {
    const form = fieldsRef.current?.closest('form')
    if (form === null || form === undefined) return
    const reset = () => {
      setProvinceCode(initialProvinceCode)
      setDistrictCode(initialDistrictCode)
      setNeighborhoodCode(initialNeighborhoodCode)
    }
    form.addEventListener('reset', reset)
    return () => form.removeEventListener('reset', reset)
  }, [initialDistrictCode, initialProvinceCode, initialNeighborhoodCode])

  useEffect(() => {
    if (provinceName === null) {
      setRegions([])
      setLoadStatus('idle')
      return
    }
    const controller = new AbortController()
    let active = true
    setLoadStatus('loading')
    repository.search(provinceName, controller.signal)
      .then((items) => {
        if (!active) return
        setRegions(items)
        setLoadStatus('idle')
      })
      .catch((error: unknown) => {
        if (!active || isAbortError(error)) return
        setRegions([])
        setLoadStatus('error')
      })
    return () => {
      active = false
      controller.abort()
    }
  }, [provinceName, repository])

  function selectProvince(code: string) {
    setProvinceCode(code)
    setDistrictCode('')
    setNeighborhoodCode('')
  }

  return {
    provinceCode, districtCode, districtOptions, neighborhoodCode, neighborhoodOptions, loadStatus, fieldsRef,
    selectProvince, selectDistrict: (code: string) => { setDistrictCode(code); setNeighborhoodCode('') },
    selectNeighborhood: setNeighborhoodCode,
  }
}

function provinceCodeFrom(regionCode: string) {
  const code = regionCode.slice(0, 2)
  return PUBLIC_HOUSING_PROVINCE_OPTIONS.some(([value]) => value === code) ? code : ''
}

function selectedRegionFallback(regionCode: string, provinceName: string | null): PublicHousingRegion {
  return findCatalogRegion(regionCode) ?? {
    regionCode,
    provinceName: provinceName ?? '',
    districtName: '지역 선택됨',
    displayName: '지역 선택됨',
  }
}

function isAbortError(error: unknown) {
  return typeof error === 'object'
    && error !== null
    && 'name' in error
    && error.name === 'AbortError'
}
