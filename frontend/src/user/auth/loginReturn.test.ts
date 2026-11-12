import { afterEach, expect, it, vi } from 'vitest'
import { lastExplorerPath, readLoginReturn, rememberExplorerPath, rememberLoginReturn, safeLoginReturn } from './loginReturn'

afterEach(() => { sessionStorage.clear(); vi.useRealTimers() })
it.each(['https://evil.test', '//evil.test/explore', '/\\evil.test', '/login', '/administrator', '/privacy'])(
  '복귀 주소 %s는 공개 탐색으로 제한한다', value => expect(safeLoginReturn(value)).toBe('/explore'),
)
it('로그인 제어값만 지우고 필터, 위치, 해시를 유지한다', () => {
  expect(safeLoginReturn('/explore?login=failed&reason=expired&complexRentalTypes=HAPPY_HOUSING&mapZoom=12#map'))
    .toBe('/explore?complexRentalTypes=HAPPY_HOUSING&mapZoom=12#map')
})
it('로그인 시작 전과 만료된 복귀 정보는 성공 콜백에 사용하지 않는다', () => {
  vi.useFakeTimers()
  rememberLoginReturn('/explore?inbox=open')
  expect(readLoginReturn(true)).toBeNull()
  rememberLoginReturn('/explore?inbox=open', true)
  expect(readLoginReturn(true)).toBe('/explore?inbox=open')
  vi.advanceTimersByTime(30 * 60 * 1000)
  expect(readLoginReturn(true)).toBeNull()
})

it('마이페이지 내 조건 관리로 돌아올 수 있다', () => {
  expect(safeLoginReturn('/mypage/conditions')).toBe('/mypage/conditions')
})
it('관리자 화면 주소로 돌아오고 로그인 안내 쿼리만 지운다', () => {
  expect(safeLoginReturn('/admin/announcements?page=2&login=failed&reason=expired')).toBe('/admin/announcements?page=2')
  expect(safeLoginReturn('/admin')).toBe('/admin')
})
it('단지·공고 상세와 지역 주소로 돌아오고 지도 복귀 주소로도 기억한다', () => {
  expect(safeLoginReturn('/complexes/17?login=required&mapZoom=12')).toBe('/complexes/17?mapZoom=12')
  expect(safeLoginReturn('/announcements/201')).toBe('/announcements/201')
  expect(safeLoginReturn('/regions/28200/national-rental')).toBe('/regions/28200/national-rental')
  expect(safeLoginReturn('/regions/abc')).toBe('/explore')
  rememberExplorerPath('/announcements/201?mapZoom=12')
  expect(lastExplorerPath()).toBe('/announcements/201?mapZoom=12')
})
