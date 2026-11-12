import type { JsonObject } from '../announcement-json/api'

/**
 * 접수처 정규화. LH 원천과 공고문은 같은 곳을 다르게 적는다(이름이 없거나 다름, 주소 끝 괄호 속 단지명, URL 끝 '/').
 * 같은 곳 판정은 서버(ReceptionPlace.isSamePlace)와 같은 기준이다: 접수 방식이 같고, 둘 다 주소가 있으면 도로명+건물번호
 * (없으면 괄호를 뺀 주소)로, 둘 다 URL이 있으면 호스트로, 그 밖에는 띄어쓰기·기호를 뺀 이름으로 가린다.
 */
const parentheses = /\([^)]*\)/g
// 도로명(…로·…길, '성남대로 54번길'처럼 번길까지)과 건물번호. 번호 뒤에 '번길'이 붙으면 도로명의 일부라 건물번호가 아니다.
const roadAddress = /([가-힣A-Za-z0-9]+(?:로|길)(?:\s*\d+번?길)?)\s*(\d+(?:-\d+)?)(?![\d가-힣])/u
const present = (value: unknown): value is string => typeof value === 'string' && value.trim() !== ''
const compact = (value: unknown) => typeof value === 'string' ? value.replace(/[^\p{L}\p{N}]/gu, '').toLowerCase() : ''

function addressKey(value: string) {
  const plain = value.replace(parentheses, ' ')
  const road = roadAddress.exec(plain)
  return road ? `${compact(road[1])}${road[2]}` : compact(plain)
}
const urlHost = (value: string) => value.trim().toLowerCase().replace(/^(?:https?:\/\/)?(?:www\.)?/, '').split('/')[0]

export function samePlace(left: JsonObject, right: JsonObject) {
  if (left.method !== right.method) return false
  if (present(left.address) && present(right.address)) return addressKey(left.address) === addressKey(right.address)
  if (present(left.url) && present(right.url)) return urlHost(left.url) === urlHost(right.url)
  return compact(left.name) !== '' && compact(left.name) === compact(right.name)
}

/**
 * 추출한 접수처의 방식. 주소만 있고 URL이 없으면 현장(VISIT), URL만 있고 주소가 없으면 인터넷(ONLINE)이다. 워커가 지사 주소가 있는
 * 현장 접수처를 ONLINE으로 낸 적이 있어(같은 곳인 LH 현장 접수처와 짝을 못 지음) 칸 값을 이 방식으로 읽는다. 우편·기타와 둘 다 있거나 없는 경우는 그대로 둔다.
 */
export function normalizedMethod(place: JsonObject): unknown {
  if (place.method !== 'ONLINE' && place.method !== 'VISIT' && place.method != null) return place.method
  if (present(place.address) && !present(place.url)) return 'VISIT'
  if (present(place.url) && !present(place.address)) return 'ONLINE'
  return place.method ?? null
}

/**
 * 지금 접수처 가운데 index가 다른 접수처와 같은 곳이면 그 접수처 순번. 남길 접수처(PDF와 짝지은 접수처)나 앞 순번과 같은 곳이면 중복이다.
 * 예전 PDF 보정이 LH 접수처 곁에 같은 곳을 따로 더해 둔 것을 찾는다.
 */
export function duplicateOf(places: JsonObject[], index: number, kept: (at: number) => boolean): number | null {
  const twin = places.findIndex((other, at) => at !== index && (kept(at) || at < index) && samePlace(other, places[index]))
  return twin < 0 ? null : twin
}
