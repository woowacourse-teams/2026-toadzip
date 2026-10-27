import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

/** 관리자 주소 검색 후보. 좌표를 못 구한 주소(건물 없는 지번 등)는 위도·경도가 null이다. */
export type AddressCandidate = {
  roadAddress: string; jibunAddress: string; pnu: string; legalDongCode: string
  provinceCode: string; cityCountyDistrictCode: string; latitude: number | null; longitude: number | null
}

const text = (value: unknown) => typeof value === 'string' ? value : ''
const coordinate = (value: unknown) => typeof value === 'number' && Number.isFinite(value) ? value : null

export async function lookupAddresses(keyword: string, signal?: AbortSignal): Promise<AddressCandidate[]> {
  const result = await requestManagementApi(`/api/admin/addresses?${new URLSearchParams({ keyword })}`, 'GET', undefined, signal)
  if (!Array.isArray(result)) throw new Error('주소 검색 응답 형식이 올바르지 않습니다.')
  return result.filter(isRecord).filter(item => /^\d{19}$/.test(text(item.pnu))).map(item => ({
    roadAddress: text(item.roadAddress), jibunAddress: text(item.jibunAddress), pnu: text(item.pnu),
    legalDongCode: text(item.legalDongCode), provinceCode: text(item.provinceCode), cityCountyDistrictCode: text(item.cityCountyDistrictCode),
    latitude: coordinate(item.latitude), longitude: coordinate(item.longitude),
  }))
}
