import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { expect, it, vi } from 'vitest'
import { DetailFeedbackLink } from './DetailFeedbackLink'
import { FeedbackPage } from './FeedbackPage'
import { submitFeedback } from './api'

vi.mock('./api', () => ({ submitFeedback: vi.fn() }))

function Where() {
  const location = useLocation()
  return <output data-testid="where">{location.pathname}{location.search}</output>
}

it('상세의 글자 버튼은 보던 화면을 배경으로 두고 대상을 붙인 의견 창을 열어 함께 보낸다', async () => {
  vi.mocked(submitFeedback).mockResolvedValue()
  render(<MemoryRouter initialEntries={['/explore?announcementId=12']}><Routes>
    <Route path="/explore" element={<><Where /><DetailFeedbackLink target={{ resource: 'ANNOUNCEMENT', id: 12, name: '가나 행복주택 입주자 모집' }} /></>} />
    <Route path="/feedback" element={<FeedbackPage />} />
  </Routes></MemoryRouter>)
  fireEvent.click(screen.getByRole('link', { name: '정보가 다르면 알려 주세요' }))
  expect(await screen.findByText('공고:')).toHaveTextContent('공고: 가나 행복주택 입주자 모집')
  fireEvent.change(screen.getByRole('textbox', { name: /의견 내용/ }), { target: { value: '모집 호수가 달라요' } })
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }))
  await screen.findByRole('heading', { name: '의견이 접수되었어요.' })
  expect(submitFeedback).toHaveBeenCalledWith('모집 호수가 달라요',
    { resource: 'ANNOUNCEMENT', id: 12, name: '가나 행복주택 입주자 모집' })
  fireEvent.click(screen.getByRole('button', { name: '닫기' }))
  expect(await screen.findByTestId('where')).toHaveTextContent('/explore?announcementId=12')
})
