import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { FeedbackPage } from './FeedbackPage'
import { submitFeedback } from './api'

vi.mock('./api', () => ({ submitFeedback: vi.fn() }))
beforeEach(() => { vi.mocked(submitFeedback).mockReset() })
function setup() {
  render(<MemoryRouter><FeedbackPage /></MemoryRouter>)
  return screen.getByRole('textbox', { name: '의견 내용' })
}

it('주관식 내용만 입력하고 전송 성공을 안내한다', async () => {
  vi.mocked(submitFeedback).mockResolvedValue()
  const input = setup()
  expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  expect(screen.queryByRole('radio')).not.toBeInTheDocument()
  fireEvent.change(input, { target: { value: '  지도가 불편해요.\n개선해 주세요.  ' } })
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  const confirmation = await screen.findByRole('heading', { name: '의견이 접수되었습니다.' })
  await waitFor(() => expect(confirmation).toHaveFocus())
  expect(submitFeedback).toHaveBeenCalledWith('지도가 불편해요.\n개선해 주세요.')
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
})

it.each([' \n\t ', '\u00a0', '\u202f', '\ufeff'])('공백만 입력한 의견을 전송하지 않는다: %j', content => {
  const input = setup()
  fireEvent.change(input, { target: { value: content } })
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  expect(screen.getByRole('alert')).toHaveTextContent('1자 이상 2,000자 이하')
  expect(input).toHaveFocus()
  expect(submitFeedback).not.toHaveBeenCalled()
})

it('실패하면 내용을 보존하고 다시 제출할 수 있다', async () => {
  vi.mocked(submitFeedback).mockRejectedValueOnce(new Error('전송 실패')).mockResolvedValueOnce()
  const input = setup()
  fireEvent.change(input, { target: { value: '검색을 개선해 주세요.' } })
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('전송 실패')
  expect(input).toHaveValue('검색을 개선해 주세요.')
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  expect(await screen.findByRole('heading', { name: '의견이 접수되었습니다.' })).toBeVisible()
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
  expect(screen.getByRole('button', { name: '보내는 중…' })).toBeDisabled()
  expect(submitFeedback).toHaveBeenCalledTimes(1)
  await act(async () => complete())
  expect(screen.getByRole('heading', { name: '의견이 접수되었습니다.' })).toBeVisible()
})

it('최대 글자 수를 표시하고 초과 입력을 거절한다', async () => {
  const input = setup()
  expect(input).toHaveAttribute('maxlength', '2000')
  fireEvent.change(input, { target: { value: '가'.repeat(2001) } })
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('2,000자 이하'))
  expect(submitFeedback).not.toHaveBeenCalled()
})
