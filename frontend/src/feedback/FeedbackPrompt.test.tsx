import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { FeedbackPage } from './FeedbackPage'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { FeedbackPrompt } from './FeedbackPrompt'
import { dismissFeedbackPrompt } from './feedbackPromptSession'

beforeEach(() => { vi.useFakeTimers(); sessionStorage.clear() })
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })
const setup = () => render(<MemoryRouter><FeedbackPrompt /></MemoryRouter>)

it('10초 뒤 작은 의견 안내를 표시하고 닫으면 같은 세션에서 다시 표시하지 않는다', () => {
  const first = setup()
  act(() => vi.advanceTimersByTime(9_999))
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  act(() => vi.advanceTimersByTime(1))
  expect(screen.getByRole('complementary', { name: '의견 요청' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '의견 요청 닫기' }))
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  first.unmount(); setup()
  act(() => vi.advanceTimersByTime(10_000))
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
})

it('숨은 탭에는 표시하지 않고 다시 보이는 시점에 표시한다', () => {
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
  setup()
  act(() => vi.advanceTimersByTime(10_000))
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  visibility.mockReturnValue('visible')
  fireEvent(document, new Event('visibilitychange'))
  expect(screen.getByRole('complementary')).toBeVisible()
})

it('의견 제출 후에는 안내를 숨기고 같은 세션에서 재노출하지 않는다', () => {
  setup()
  act(() => vi.advanceTimersByTime(10_000))
  act(() => dismissFeedbackPrompt())
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  act(() => vi.advanceTimersByTime(10_000))
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
})

it('스토리지 접근이 차단되어도 안내와 닫기가 동작한다', () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
  setup()
  act(() => vi.advanceTimersByTime(10_000))
  fireEvent.click(screen.getByRole('button', { name: '의견 요청 닫기' }))
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
})

it('안내에서 설문 모달을 열고 선택한 안내를 같은 세션에서 다시 표시하지 않는다', async () => {
  render(<MemoryRouter initialEntries={['/explore']}><Routes>
    <Route path="/explore" element={<FeedbackPrompt />} />
    <Route path="/feedback" element={<FeedbackPage />} />
  </Routes></MemoryRouter>)
  act(() => vi.advanceTimersByTime(10_000))
  fireEvent.click(screen.getByRole('link', { name: '의견을 들려주세요' }))
  expect(screen.getByRole('dialog', { name: '의견 보내기' })).toBeVisible()
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기 닫기' }))
  act(() => vi.advanceTimersByTime(10_000))
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
})

it('다른 떠 있는 안내가 알린 위치 위에 뜬다', async () => {
  const { readFileSync } = await import('node:fs')
  const { resolve } = await import('node:path')
  const css = readFileSync(resolve(process.cwd(), 'src/feedback/FeedbackPrompt.module.css'), 'utf8')
  expect(css).toMatch(/\.prompt\s*\{[^}]*bottom:\s*var\(--floating-prompt-bottom, 96px\);/)
})
