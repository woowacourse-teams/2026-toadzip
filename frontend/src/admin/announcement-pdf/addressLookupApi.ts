import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

/** 관리자 주소 검색 후보. 좌표를 못 구한 주소(건물 없는 지번 등)는 위도·경도가 null이다. */
export type AddressCandidate = {
  roadAddress: string; jibunAddress: string; pnu: string; legalDongCode: string
  provinceCode: string; cityCountyDistrictCode: string; latitude: number | null; longitude: number | null
  /** 검색어와 같은 도로명주소·지번이다. 이런 후보가 하나뿐이면 관리자가 고르지 않아도 된다. */
  matchesKeyword: boolean
  /** 같은 필지이거나 같은 (시도, 도로명, 건물번호)에 이미 등록된 단지. 저장 주소 표기가 달라도 서버가 찾는다. */
  registeredComplexes: RegisteredComplex[]
}
export type RegisteredComplex = { housingComplexId: number; name: string; roadAddress: string | null; rentalType: string | null }

const registered = (value: unknown): RegisteredComplex[] => Array.isArray(value) ? value.filter(isRecord)
  .filter(item => typeof item.housingComplexId === 'number' && typeof item.name === 'string')
  .map(item => ({ housingComplexId: item.housingComplexId as number, name: item.name as string,
    roadAddress: typeof item.roadAddress === 'string' ? item.roadAddress : null, rentalType: typeof item.rentalType === 'string' ? item.rentalType : null })) : []

const text = (value: unknown) => typeof value === 'string' ? value : ''
const coordinate = (value: unknown) => typeof value === 'number' && Number.isFinite(value) ? value : null

/** sourcePnu를 주면 서버가 그 필지에 등록된 단지도 같은 곳의 단지로 알려 준다. */
export async function lookupAddresses(keyword: string, signal?: AbortSignal, sourcePnu = ''): Promise<AddressCandidate[]> {
  const result = await requestManagementApi(`/api/admin/addresses?${new URLSearchParams(sourcePnu ? { keyword, sourcePnu } : { keyword })}`, 'GET', undefined, signal)
  if (!Array.isArray(result)) throw new Error('주소 검색 응답 형식이 올바르지 않습니다.')
  return result.filter(isRecord).filter(item => /^\d{19}$/.test(text(item.pnu))).map(item => ({
    roadAddress: text(item.roadAddress), jibunAddress: text(item.jibunAddress), pnu: text(item.pnu),
    legalDongCode: text(item.legalDongCode), provinceCode: text(item.provinceCode), cityCountyDistrictCode: text(item.cityCountyDistrictCode),
    latitude: coordinate(item.latitude), longitude: coordinate(item.longitude), matchesKeyword: item.matchesKeyword === true, registeredComplexes: registered(item.registeredComplexes),
  }))
}
