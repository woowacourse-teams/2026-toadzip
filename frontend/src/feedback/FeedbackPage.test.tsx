import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { FeedbackPage } from './FeedbackPage'
import { submitFeedback } from './api'

vi.mock('./api', () => ({ submitFeedback: vi.fn() }))
beforeEach(() => { vi.mocked(submitFeedback).mockReset() })
function setup() {
  render(<MemoryRouter><FeedbackPage /></MemoryRouter>)
  fireEvent.click(screen.getByRole('radio', { name: '매우 실망' }))
  return screen.getByRole('textbox', { name: /의견 내용/ })
}

it('설문과 의견을 보내고 전송 성공을 안내한다', async () => {
  vi.mocked(submitFeedback).mockResolvedValue()
  const input = setup()
  expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  expect(screen.getAllByRole('radio')).toHaveLength(4)
  fireEvent.change(input, { target: { value: '  지도가 불편해요.\n개선해 주세요.  ' } })
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  const confirmation = await screen.findByRole('heading', { name: '의견이 접수되었어요.' })
  await waitFor(() => expect(confirmation).toHaveFocus())
  expect(submitFeedback).toHaveBeenCalledWith('지도가 불편해요.\n개선해 주세요.', 'VERY_DISAPPOINTED')
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
})

it.each(['', ' \n\t ', '\u00a0', '\u202f', '\ufeff'])('설문만 선택하면 빈 의견도 전송한다: %j', async content => {
  vi.mocked(submitFeedback).mockResolvedValue()
  const input = setup()
  fireEvent.change(input, { target: { value: content } })
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  expect(await screen.findByRole('heading', { name: '의견이 접수되었어요.' })).toBeVisible()
  expect(submitFeedback).toHaveBeenCalledWith('', 'VERY_DISAPPOINTED')
})

it('설문을 선택하지 않으면 전송하지 않고 선택지로 초점을 옮긴다', () => {
  render(<MemoryRouter><FeedbackPage /></MemoryRouter>)
  fireEvent.change(screen.getByRole('textbox', { name: /의견 내용/ }), { target: { value: '의견' } })
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  expect(screen.getByRole('alert')).toHaveTextContent('설문 답변을 선택해 주세요.')
  expect(screen.getByRole('radio', { name: '매우 실망' })).toHaveFocus()
  expect(submitFeedback).not.toHaveBeenCalled()
})

it('네 답변 중 하나만 선택하고 바꾼 답변을 전송한다', async () => {
  vi.mocked(submitFeedback).mockResolvedValue()
  setup()
  fireEvent.click(screen.getByRole('radio', { name: '더 이상 쓰지 않음' }))
  expect(screen.getByRole('radio', { name: '매우 실망' })).not.toBeChecked()
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  await screen.findByRole('heading', { name: '의견이 접수되었어요.' })
  expect(submitFeedback).toHaveBeenCalledWith('', 'NO_LONGER_USING')
})

it('실패하면 내용을 보존하고 다시 제출할 수 있다', async () => {
  vi.mocked(submitFeedback).mockRejectedValueOnce(new Error('SQL internal /api/v1/feedback failed')).mockResolvedValueOnce()
  const input = setup()
  fireEvent.change(input, { target: { value: '검색을 개선해 주세요.' } })
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('의견을 보내지 못했어요. 작성한 내용을 확인하고 다시 시도해 주세요.')
  expect(screen.queryByText(/SQL internal/)).not.toBeInTheDocument()
  expect(input).toHaveValue('검색을 개선해 주세요.')
  expect(screen.getByRole('radio', { name: '매우 실망' })).toBeChecked()
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  expect(await screen.findByRole('heading', { name: '의견이 접수되었어요.' })).toBeVisible()
})

it('전송 중에는 중복 제출과 내용 변경을 막는다', async () => {
  let complete: () => void = () => undefined
  vi.mocked(submitFeedback).mockImplementation(() => new Promise<void>(resolve => { complete = resolve }))
  const input = setup()
  fireEvent.change(input, { target: { value: '개선 의견' } })
  const form = screen.getByRole('form', { name: '의견 접수' })
  fireEvent.submit(form)
  fireEvent.submit(form)
  expect(input).toBeDisabled()
  expect(screen.getByRole('button', { name: '의견 보내기 닫기' })).toBeDisabled()
  expect(screen.getByRole('radio', { name: '매우 실망' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '보내는 중…' })).toBeDisabled()
  expect(submitFeedback).toHaveBeenCalledTimes(1)
  await act(async () => complete())
  expect(screen.getByRole('heading', { name: '의견이 접수되었어요.' })).toBeVisible()
})

it('최대 글자 수를 표시하고 초과 입력을 거절한다', async () => {
  const input = setup()
  expect(input).toHaveAttribute('maxlength', '2000')
  fireEvent.change(input, { target: { value: '가'.repeat(2001) } })
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('2,000자 이하'))
  expect(submitFeedback).not.toHaveBeenCalled()
})
