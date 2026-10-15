import { afterEach, expect, it, vi } from 'vitest'
import { pushInstallationHelp } from './pushInstallationHelp'
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
it('데스크톱 UA를 사용하는 구형 iPad는 홈 화면 설치 대신 업데이트를 안내한다', () => {
  vi.stubGlobal('navigator', { userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.3 Safari/605.1.15', platform: 'MacIntel', maxTouchPoints: 5 })
  expect(pushInstallationHelp()).toBe('ios-update')
})
it('홈 화면 앱에서는 설치 안내를 반복하지 않는다', () => {
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)')
  vi.stubGlobal('matchMedia', () => ({ matches: true }))
  expect(pushInstallationHelp()).toBeNull()
  vi.unstubAllGlobals()
})
