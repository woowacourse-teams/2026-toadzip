import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it } from 'vitest'
import { FEEDBACK_FORM_URL, FeedbackPage } from './FeedbackPage'
import { DISMISSED } from './feedbackPromptSession'

beforeEach(() => sessionStorage.clear())

it('의견 창에 구글 폼을 임베드 주소로 띄우고 새 창 링크를 함께 준다', () => {
  render(<MemoryRouter><FeedbackPage /></MemoryRouter>)
  expect(screen.getByTitle('의견 설문')).toHaveAttribute('src', `${FEEDBACK_FORM_URL}?embedded=true`)
  expect(screen.getByRole('link', { name: '새 창에서 열기' })).toHaveAttribute('href', FEEDBACK_FORM_URL)
  expect(screen.getByRole('link', { name: '새 창에서 열기' })).toHaveAttribute('target', '_blank')
})

it('의견 창을 열면 같은 세션에서 의견 요청 말풍선을 다시 띄우지 않는다', () => {
  render(<MemoryRouter><FeedbackPage /></MemoryRouter>)
  expect(sessionStorage.getItem(DISMISSED)).toBe('true')
})

it('설문이 뜰 때까지 불러오는 중 안내를 보이고, 다 뜨면 안내를 지운다', () => {
  render(<MemoryRouter><FeedbackPage /></MemoryRouter>)
  expect(screen.getByRole('status')).toHaveTextContent('설문을 불러오는 중…')
  fireEvent.load(screen.getByTitle('의견 설문'))
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
})
