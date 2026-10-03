import { useEffect, useState } from 'react'
import type { PublicHousingRegionRepository } from '../api/publicHousingRegionRepository.ts'
import { provinceNameForRegionCode, type PublicHousingRegion } from '../model/publicHousingRegion.ts'

export function RegionSearchPath({ regionCode, onSelect, repository }: {
  regionCode: string
  onSelect: (code: string | null) => void
  repository: PublicHousingRegionRepository
}) {
  const [regions, setRegions] = useState<readonly PublicHousingRegion[]>([])
  const [browseCode, setBrowseCode] = useState(regionCode)
  const [failed, setFailed] = useState(false)
  const province = provinceNameForRegionCode(regionCode)
  useEffect(() => {
    setBrowseCode(regionCode)
  }, [regionCode])
  useEffect(() => {
    if (!province) return
    const controller = new AbortController()
    setFailed(false)
    setRegions([])
    repository.search(province, controller.signal).then((items) => {
      if (!controller.signal.aborted) setRegions(items)
    }).catch(() => { if (!controller.signal.aborted) setFailed(true) })
    return () => controller.abort()
  }, [province, repository])
  const selected = regions.find((item) => item.regionCode === regionCode)
  const browsing = regions.find((item) => item.regionCode === browseCode)
  const path = selected ? regions.filter((item) => item.regionCode === selected.regionCode
    || selected.displayName.startsWith(`${item.displayName} `))
    .sort((a, b) => a.displayName.length - b.displayName.length) : []
  const descendants = browsing ? regions.filter((item) => item.displayName.startsWith(`${browsing.displayName} `)) : []
  const children = descendants.filter((item) => !descendants.some((parent) =>
    item.regionCode !== parent.regionCode && item.displayName.startsWith(`${parent.displayName} `)))
  return <div className="housing-region-path">
    <nav aria-label="현재 검색 지역 경로">
      {path.map((item, index) => <span key={item.regionCode}>
        {index > 0 && <span aria-hidden="true"> › </span>}
        <button type="button" onClick={() => onSelect(item.regionCode)}
          aria-current={item.regionCode === regionCode ? 'location' : undefined}>
          {index === 0 ? item.provinceName : item.displayName.split(' ').at(-1)}
        </button>
      </span>)}
    </nav>
    {children.length > 0 && <details>
      <summary>하위 지역 둘러보기</summary>
      {browseCode !== regionCode && <button type="button" onClick={() => setBrowseCode(regionCode)}>상위 목록</button>}
      <ul>{children.map((item) => <li key={item.regionCode}>
        <button type="button" onClick={() => onSelect(item.regionCode)}>{item.districtName ?? item.provinceName} 전체 검색</button>
        {regions.some((child) => child.displayName.startsWith(`${item.displayName} `)) && <button type="button"
          aria-label={`${item.displayName} 하위 지역 펼치기`} onClick={() => setBrowseCode(item.regionCode)}>›</button>}
      </li>)}</ul>
    </details>}
    {failed && <small>지역 경로를 불러오지 못했습니다. 검색창으로 지역을 선택할 수 있습니다.</small>}
  </div>
}
