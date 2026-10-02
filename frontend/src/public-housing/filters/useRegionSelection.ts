import { useEffect, useMemo, useRef, useState } from 'react'
import type { PublicHousingRegionRepository } from '../api/publicHousingRegionRepository.ts'
import {
  districtRegionOptionsForProvince,
  PUBLIC_HOUSING_PROVINCE_OPTIONS,
  provinceNameForRegionCode,
  type PublicHousingRegion,
} from '../model/publicHousingRegion.ts'

export function useRegionSelection(
  initialRegionCode: string,
  repository: PublicHousingRegionRepository,
) {
  const initialProvinceCode = provinceCodeFrom(initialRegionCode)
  const initialDistrictCode = initialRegionCode.length === 5 ? initialRegionCode : ''
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

  useEffect(() => {
    const form = provinceSelectRef.current?.form
    if (form === null || form === undefined) return
    const reset = () => {
      setProvinceCode(initialProvinceCode)
      setDistrictCode(initialDistrictCode)
    }
    form.addEventListener('reset', reset)
    return () => form.removeEventListener('reset', reset)
  }, [initialDistrictCode, initialProvinceCode])

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
  }

  return {
    provinceCode, districtCode, districtOptions, loadStatus, provinceSelectRef,
    selectProvince, selectDistrict: setDistrictCode,
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
