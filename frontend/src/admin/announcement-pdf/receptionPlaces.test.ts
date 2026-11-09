import { expect, it } from 'vitest'
import { normalizedMethod, samePlace } from './receptionPlaces'

const place = (values: Record<string, unknown>) => ({ name: null, method: 'VISIT', address: null, contact: null, url: null, guidance: null, ...values })

it('LH 접수처와 공고문 접수처는 이름이 달라도 도로명·건물번호가 같으면 같은 곳이다', () => {
  const lh = place({ name: '관리사무소', address: '경기도 평택시 팽성읍 팽성송화로 163(주공아파트) 관리사무소', contact: '031-651-6776' })
  const pdf = place({ name: '평택송화단지 아파트 관리사무소', address: '경기도 평택시 팽성읍 팽성송화로 163' })
  expect(samePlace(lh, pdf)).toBe(true)
  expect(samePlace(lh, place({ name: '관리사무소', address: '경기도 평택시 팽성읍 팽성송화로 165' }))).toBe(false)
  // '번길'은 도로명의 일부라 건물번호가 아니다.
  expect(samePlace(place({ address: '성남시 분당구 성남대로 54번길 3' }), place({ address: '경기도 성남시 분당구 성남대로54번길 3 1층' }))).toBe(true)
  expect(samePlace(place({ address: '성남대로 54번길 3' }), place({ address: '성남대로 54번길 5' }))).toBe(false)
})

it('방식이 다르면 같은 곳이 아니고, 온라인은 URL 호스트로, 주소·URL이 없으면 띄어쓰기를 뺀 이름으로 가린다', () => {
  const online = place({ name: 'LH청약플러스', method: 'ONLINE', url: 'https://apply.lh.or.kr' })
  expect(samePlace(online, place({ name: '인터넷 및 모바일 신청 (LH청약플러스)', method: 'ONLINE', url: 'apply.lh.or.kr/' }))).toBe(true)
  expect(samePlace(online, place({ name: 'LH청약플러스', method: 'VISIT', url: 'https://apply.lh.or.kr' }))).toBe(false)
  expect(samePlace(place({ name: '우편 접수', method: 'MAIL' }), place({ name: '우편접수', method: 'MAIL' }))).toBe(true)
  // 서버 규칙표(ReceptionPlaceTest)와 같은 사례: 주소가 한쪽에 없으면 이름으로 가린다.
  expect(samePlace(place({ name: 'LH 접수처' }), place({ name: 'LH 현장 접수처', address: '서울시 1' }))).toBe(false)
})

it('주소만 있고 URL이 없으면 현장 접수, URL만 있으면 온라인 접수로 방식을 정리한다', () => {
  expect(normalizedMethod(place({ method: 'ONLINE', address: '경기도 성남시 분당구 성남대로 54번길 3' }))).toBe('VISIT')
  expect(normalizedMethod(place({ method: 'VISIT', url: 'https://apply.lh.or.kr' }))).toBe('ONLINE')
  expect(normalizedMethod(place({ method: null, address: '서울시 1', url: 'https://example.com' }))).toBeNull()
  expect(normalizedMethod(place({ method: 'MAIL', address: '사서함 1' }))).toBe('MAIL')
})
