import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { privacyApi, type ConsentContext, type Notice } from '../../privacy/api'
import { consentStore } from '../../privacy/consentStore'
import { LoginModal } from './LoginModal'

const policy: Notice = {
  key: 'PRIVACY_POLICY', version: 'privacy-2026-10-10-v1', scopeVersion: null,
  effectiveAt: '2026-10-10T00:00:00Z', contentHash: 'hash',
  documentUrl: '/api/v1/privacy/notices/PRIVACY_POLICY/privacy-2026-10-10-v1',
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.com')
})

afterEach(() => {
  act(() => consentStore.transition())
  localStorage.clear()
  sessionStorage.clear()
  vi.restoreAllMocks()
  vi.unstubAllEnvs()
})

function renderLogin(path = '/') {
  return render(<MemoryRouter initialEntries={[path]}>
    <LoginModal loginFailed={path.includes('login=failed')} sessionError={false}
      onClose={() => {}} returnFocusRef={{ current: null }} />
  </MemoryRouter>)
}

function expectLegacyLoginLinks() {
  for (const [name, provider] of [['카카오톡으로 로그인', 'kakao'], ['Google로 로그인', 'google']]) {
    const link = screen.getByRole('link', { name })
    expect(link).toHaveAttribute('href', `https://api.example.com/api/auth/oauth2/authorization/${provider}`)
    expect(link).not.toHaveAttribute('aria-disabled', 'true')
  }
  expect(screen.getByRole('link', { name: '개인정보처리방침' })).toHaveAttribute('href', '/privacy')
}

it('정책 조회 중에도 기존 로그인 URL과 정책 링크를 바로 제공한다', () => {
  vi.spyOn(privacyApi, 'notices').mockReturnValue(new Promise(() => {}))
  renderLogin()
  expectLegacyLoginLinks()
  expect(screen.getByRole('link', { name: '카카오톡으로 로그인' })).toHaveFocus()
})

it('정책 조회 실패는 로그인과 분리해 안내하고 재조회한 버전의 링크를 표시한다', async () => {
  vi.spyOn(privacyApi, 'notices').mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce([policy])
  renderLogin()
  expect(await screen.findByRole('alert')).toHaveTextContent('개인정보처리방침 안내를 불러오지 못했어요. 로그인은 계속할 수 있어요.')
  expectLegacyLoginLinks()

  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  await waitFor(() => expect(screen.getByRole('link', { name: 'Google로 로그인' }))
    .toHaveAttribute('href', `https://api.example.com/api/auth/oauth2/authorization/google?policyVersion=${policy.version}`))
  expect(screen.getByRole('link', { name: '개인정보처리방침' })).toHaveAttribute('href', `/privacy/PRIVACY_POLICY/${policy.version}`)
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('정책 버전을 받지 못하면 임의의 버전을 채우지 않고 기존 로그인 URL을 쓴다', async () => {
  const notices = vi.spyOn(privacyApi, 'notices').mockResolvedValue([])
  renderLogin()
  await waitFor(() => expect(notices).toHaveBeenCalledOnce())
  expectLegacyLoginLinks()
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
})

it.each(['/?login=failed', '/?login=failed&reason=privacy-notice'])('%s 복귀는 정책 동의를 요구하지 않고 새 로그인 요청을 안내한다', path => {
  vi.spyOn(privacyApi, 'notices').mockReturnValue(new Promise(() => {}))
  renderLogin(path)
  const alert = screen.getByRole('alert')
  expect(alert).toHaveTextContent('로그인을 완료하지 못했습니다.')
  expect(alert).toHaveTextContent('인증이 취소되었거나 로그인 요청이 만료되었을 수 있어요.')
  expect(alert).toHaveTextContent('아래 버튼을 눌러 다시 시도해 주세요.')
  expect(alert).toHaveFocus()
  expect(alert).not.toHaveTextContent('로그인 안내가 변경')
  expectLegacyLoginLinks()
})

it('분석 거부 상태에서도 로그인 요청을 시작하며 분석 동의를 대신 생성하지 않는다', async () => {
  const context: ConsentContext = {
    subject: { kind: 'GUEST', contextId: 'guest-context' },
    consent: { decision: 'DENIED', effectiveStatus: 'DENIED', revision: 1, noticeVersion: null,
      scopeVersion: null, decidedAt: '2026-10-10T00:00:00Z', expiresAt: null },
    requiredNoticeVersion: 'analytics-v1', requiredScopeVersion: 'analytics-scope-1',
    collectionAllowed: false, checkedAt: '2026-10-10T00:00:00Z', maxAgeSeconds: 60,
  }
  vi.spyOn(privacyApi, 'context').mockResolvedValue(context)
  vi.spyOn(privacyApi, 'notices').mockResolvedValue([])
  const choose = vi.spyOn(privacyApi, 'choose')
  const begin = vi.spyOn(consentStore, 'beginAuthTransition').mockReturnValue('00000000-0000-4000-8000-000000000001')
  await consentStore.refresh()
  renderLogin()
  expect(consentStore.getSnapshot().context?.consent.effectiveStatus).toBe('DENIED')
  expectLegacyLoginLinks()
  const login = screen.getByRole('link', { name: 'Google로 로그인' })
  login.addEventListener('click', event => event.preventDefault(), { once: true })
  fireEvent.click(login)
  expect(begin).toHaveBeenCalledExactlyOnceWith('oauth')
  expect(choose).not.toHaveBeenCalled()
  expect(consentStore.allowed()).toBe(false)
})
