import { afterEach, expect, it, vi } from 'vitest'
import { lastUsedLabel, pushDeviceLabel, pushDeviceMetadata } from './pushDeviceMetadata'
afterEach(() => vi.restoreAllMocks())
it.each([
  ['Mozilla/5.0 Macintosh Chrome/143 Safari/537', 'MAC', 'CHROME'],
  ['Mozilla/5.0 (iPhone; CPU iPhone OS 18_0) CriOS/143 Mobile Safari/604', 'IOS', 'CHROME'],
  ['Mozilla/5.0 Android SamsungBrowser/28 Chrome/140', 'ANDROID', 'SAMSUNG'],
  ['Mozilla/5.0 Windows NT 10 Chrome/140 Edg/140', 'WINDOWS', 'EDGE'],
])('전체 사용자 에이전트를 저장하지 않고 분류만 남긴다: %s', (ua, platform, browser) => {
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(ua)
  expect(pushDeviceMetadata()).toEqual({ platform, browser })
})
it('데스크톱 식별자로 요청하는 iPad와 홈 화면 앱을 구분한다', () => {
  vi.spyOn(navigator, 'platform', 'get').mockReturnValue('MacIntel')
  Object.defineProperty(navigator, 'maxTouchPoints', { configurable: true, value: 5 })
  Object.defineProperty(navigator, 'standalone', { configurable: true, value: true })
  expect(pushDeviceMetadata()).toEqual({ platform: 'IPADOS', browser: 'HOME_SCREEN' })
  Reflect.deleteProperty(navigator, 'maxTouchPoints'); Reflect.deleteProperty(navigator, 'standalone')
})
it('기존 정보 없는 기기는 추측하지 않고 마지막 사용은 달력 날짜로 표시한다', () => {
  expect(pushDeviceLabel(null, null)).toBe('기기 정보 없음')
  const now = new Date(2026, 9, 15, 1)
  expect(lastUsedLabel(new Date(2026, 9, 15, 0).toISOString(), now)).toBe('오늘')
  expect(lastUsedLabel(new Date(2026, 9, 14, 23).toISOString(), now)).toBe('어제')
  expect(lastUsedLabel(new Date(2026, 9, 12, 22).toISOString(), now)).toBe('3일 전')
})
