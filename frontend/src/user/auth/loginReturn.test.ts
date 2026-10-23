import { afterEach, expect, it, vi } from 'vitest'
import { readLoginReturn, rememberLoginReturn, safeLoginReturn } from './loginReturn'

afterEach(() => { sessionStorage.clear(); vi.useRealTimers() })
it.each(['https://evil.test', '//evil.test/explore', '/\\evil.test', '/login', '/admin', '/privacy'])(
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
