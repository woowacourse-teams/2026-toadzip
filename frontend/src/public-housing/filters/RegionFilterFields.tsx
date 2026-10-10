import { useEffect, useMemo, useRef, useState } from 'react'
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
  initialRegionCode, messageId, loadingMessage, repository, styles,
}: RegionFilterFieldsProps) {
  const {
    provinceCode, districtCode, districtOptions, neighborhoodCode, neighborhoodOptions, loadStatus, provinceSelectRef,
    selectProvince, selectDistrict, selectNeighborhood,
  } = useRegionSelection(initialRegionCode, repository)

  return (
    <div className={styles.regionFields}>
      <label className={styles.field}>
        <span>시·도</span>
        <select
          ref={provinceSelectRef}
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
          시·군·구를 불러오지 못했습니다. 시·도만 적용할 수 있습니다.
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
  const provinceSelectRef = useRef<HTMLSelectElement>(null)
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
    const selected = findCatalogRegion(neighborhoodCode) ?? selectedRegionFallback(neighborhoodCode, provinceName)
    return [selected, ...options]
  }, [regions, districtCode, neighborhoodCode, provinceName])

  useEffect(() => {
    const form = provinceSelectRef.current?.form
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
    provinceCode, districtCode, districtOptions, neighborhoodCode, neighborhoodOptions, loadStatus, provinceSelectRef,
    selectProvince, selectDistrict: (code: string) => { setDistrictCode(code); setNeighborhoodCode('') },
    selectNeighborhood: setNeighborhoodCode,
  }
}

function provinceCodeFrom(regionCode: string) {
  const code = regionCode.slice(0, 2)
  return PUBLIC_HOUSING_PROVINCE_OPTIONS.some(([value]) => value === code) ? code : ''
}

function selectedRegionFallback(regionCode: string, provinceName: string | null): PublicHousingRegion {
  return {
    regionCode,
    provinceName: provinceName ?? '',
    districtName: `선택 지역 (${regionCode})`,
    displayName: `선택 지역 (${regionCode})`,
  }
}

function isAbortError(error: unknown) {
  return typeof error === 'object'
    && error !== null
    && 'name' in error
    && error.name === 'AbortError'
}
