import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { FeedbackPage } from './FeedbackPage'
import { submitFeedback } from './api'
import { DISMISSED } from './feedbackPromptSession'

vi.mock('./api', () => ({ submitFeedback: vi.fn() }))
beforeEach(() => {
  vi.mocked(submitFeedback).mockReset()
  sessionStorage.clear()
})
function setup() {
  render(<MemoryRouter><FeedbackPage /></MemoryRouter>)
  return screen.getByRole('textbox', { name: /의견 내용/ })
}

it('서술형 의견 입력만 보이고 객관식 설문과 구글 폼은 없다', () => {
  setup()
  expect(screen.getByText('이용 중 불편했던 점이나 더 나아졌으면 하는 점을 자유롭게 남겨 주세요.')).toBeVisible()
  expect(screen.queryByRole('radio')).not.toBeInTheDocument()
  expect(screen.queryByTitle('의견 설문')).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: '새 창에서 열기' })).not.toBeInTheDocument()
})

it('의견을 보내고 접수 완료를 안내한다', async () => {
  vi.mocked(submitFeedback).mockResolvedValue()
  const input = setup()
  expect(input).toBeRequired()
  fireEvent.change(input, { target: { value: '  지도가 불편해요.\n개선해 주세요.  ' } })
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  const confirmation = await screen.findByRole('heading', { name: '의견이 접수되었어요.' })
  await waitFor(() => expect(confirmation).toHaveFocus())
  expect(submitFeedback).toHaveBeenCalledWith('지도가 불편해요.\n개선해 주세요.', null)
  expect(screen.queryByText(/^(공고|단지):/)).not.toBeInTheDocument()
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
})

it.each(['', ' \n\t ', ' ', ' ', '﻿'])('빈 의견은 보내지 않고 입력칸으로 초점을 옮긴다: %j', content => {
  const input = setup()
  fireEvent.change(input, { target: { value: content } })
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  expect(screen.getByRole('alert')).toHaveTextContent('의견을 1자 이상 2,000자 이하로 입력해 주세요.')
  expect(input).toHaveFocus()
  expect(input).toHaveAttribute('aria-invalid', 'true')
  expect(submitFeedback).not.toHaveBeenCalled()
})

it('실패하면 내용을 보존하고 다시 제출할 수 있다', async () => {
  vi.mocked(submitFeedback).mockRejectedValueOnce(new Error('SQL internal /api/v1/feedback failed')).mockResolvedValueOnce()
  const input = setup()
  fireEvent.change(input, { target: { value: '검색을 개선해 주세요.' } })
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('의견을 보내지 못했어요. 작성한 내용을 확인하고 다시 시도해 주세요.')
  expect(screen.queryByText(/SQL internal/)).not.toBeInTheDocument()
  expect(input).toHaveValue('검색을 개선해 주세요.')
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  expect(await screen.findByRole('heading', { name: '의견이 접수되었어요.' })).toBeVisible()
})

it('전송 중에는 중복 제출과 닫기를 막는다', async () => {
  let complete: () => void = () => undefined
  vi.mocked(submitFeedback).mockImplementation(() => new Promise<void>(resolve => { complete = resolve }))
  const input = setup()
  fireEvent.change(input, { target: { value: '개선 의견' } })
  const form = screen.getByRole('form', { name: '의견 접수' })
  fireEvent.submit(form)
  fireEvent.submit(form)
  expect(input).toBeDisabled()
  expect(screen.getByRole('button', { name: '의견 보내기 닫기' })).toBeDisabled()
  fireEvent(screen.getByRole('dialog', { name: '의견 보내기' }), new Event('cancel', { cancelable: true }))
  expect(screen.getByRole('dialog', { name: '의견 보내기' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '보내는 중…' })).toBeDisabled()
  expect(submitFeedback).toHaveBeenCalledTimes(1)
  await act(async () => complete())
  expect(screen.getByRole('heading', { name: '의견이 접수되었어요.' })).toBeVisible()
})

it('전송 중 브라우저가 대화상자를 닫아도 다시 열어 접수 결과를 보여준다', async () => {
  let complete: () => void = () => undefined
  vi.mocked(submitFeedback).mockImplementation(() => new Promise<void>(resolve => { complete = resolve }))
  const input = setup()
  fireEvent.change(input, { target: { value: '개선 의견' } })
  fireEvent.submit(screen.getByRole('form', { name: '의견 접수' }))
  const dialog = screen.getByRole('dialog', { name: '의견 보내기' }) as HTMLDialogElement
  dialog.close()
  fireEvent(dialog, new Event('close'))
  expect(dialog.open).toBe(true)
  await act(async () => complete())
  expect(screen.getByRole('heading', { name: '의견이 접수되었어요.' })).toBeVisible()
})

it('전송 중이 아닐 때 늦게 도착한 close 이벤트로 화면을 닫지 않는다', () => {
  render(<MemoryRouter initialEntries={['/feedback']}><Routes>
    <Route path="/feedback" element={<FeedbackPage />} />
    <Route path="*" element={<p>탐색 화면</p>} />
  </Routes></MemoryRouter>)
  fireEvent(screen.getByRole('dialog', { name: '의견 보내기' }), new Event('close'))
  expect(screen.queryByText('탐색 화면')).not.toBeInTheDocument()
})

it('최대 글자 수를 표시하고 초과 입력을 거절한다', async () => {
  const input = setup()
  expect(input).toHaveAttribute('maxlength', '2000')
  fireEvent.change(input, { target: { value: '가'.repeat(2001) } })
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('2,000자 이하'))
  expect(submitFeedback).not.toHaveBeenCalled()
})

it('의견 창을 열면 같은 세션에서 의견 요청 말풍선을 다시 띄우지 않는다', () => {
  setup()
  expect(sessionStorage.getItem(DISMISSED)).toBe('true')
})
