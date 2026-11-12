import { describe, expect, it } from 'vitest'

import {
  explorerPagePath,
  parseDetailPath,
  toDetailPathLocation,
  toExplorerQueryLocation,
} from './detailLocation.ts'

describe('parseDetailPath', () => {
  it('단지·공고 상세 주소에서 ID를 읽는다', () => {
    expect(parseDetailPath('/complexes/123')).toEqual({ kind: 'complex', complexId: '123' })
    expect(parseDetailPath('/announcements/171')).toEqual({ kind: 'announcement', announcementId: '171' })
  })

  it.each(['/complexes/0', '/complexes/01', '/complexes/abc', '/complexes/9223372036854775808'])(
    '정규 ID가 아닌 %s는 invalid로 본다', (pathname) => {
      expect(parseDetailPath(pathname)).toEqual({ kind: 'invalid' })
    })

  it.each(['/explore', '/complexes', '/complexes/1/extra', '/mypage'])('상세 주소가 아닌 %s는 none이다', (pathname) => {
    expect(parseDetailPath(pathname)).toEqual({ kind: 'none' })
  })
})

describe('explorerPagePath', () => {
  it('상세 주소를 지도 화면 경로로 묶고 다른 경로는 그대로 둔다', () => {
    expect(explorerPagePath('/complexes/1')).toBe('/explore')
    expect(explorerPagePath('/announcements/2')).toBe('/explore')
    // 잘못된 상세 주소는 없는 페이지라 지도 화면으로 보지 않는다.
    expect(explorerPagePath('/complexes/abc')).toBe('/complexes/abc')
    expect(explorerPagePath('/explore')).toBe('/explore')
    expect(explorerPagePath('/feedback')).toBe('/feedback')
  })
})

describe('주소 변환', () => {
  it('상세 주소를 지도 화면 쿼리로 바꾸고 경로의 ID를 우선한다', () => {
    expect(toExplorerQueryLocation({ pathname: '/complexes/12', search: '?mapZoom=14&complexId=99&announcementId=3', hash: '#x' }))
      .toEqual({ pathname: '/explore', search: '?mapZoom=14&complexId=12', hash: '#x' })
    expect(toExplorerQueryLocation({ pathname: '/announcements/7', search: '' }))
      .toEqual({ pathname: '/explore', search: '?announcementId=7' })
  })

  it('지도 화면 쿼리의 상세를 경로 주소로 바꾸고 나머지 쿼리는 남긴다', () => {
    expect(toDetailPathLocation({ pathname: '/explore', search: '?complexRentalTypes=NATIONAL_RENTAL&complexId=12' }))
      .toEqual({ pathname: '/complexes/12', search: '?complexRentalTypes=NATIONAL_RENTAL' })
    expect(toDetailPathLocation({ pathname: '/explore', search: '?announcementId=7' }))
      .toEqual({ pathname: '/announcements/7', search: '' })
  })

  it('상세가 없거나 잘못됐거나 지도 화면이 아니면 그대로 둔다', () => {
    const cases = [
      { pathname: '/explore', search: '?mapZoom=14' },
      { pathname: '/explore', search: '?complexId=1&announcementId=2' },
      { pathname: '/explore', search: '?complexId=abc' },
      { pathname: '/mypage', search: '?complexId=1' },
    ]
    for (const location of cases) expect(toDetailPathLocation(location)).toBe(location)
  })

  it('두 변환은 서로 되돌린다', () => {
    const query = { pathname: '/explore', search: '?mapLat=37.5&mapLng=127&mapZoom=12&complexId=5' }
    expect(toExplorerQueryLocation(toDetailPathLocation(query))).toEqual(query)
  })
})
