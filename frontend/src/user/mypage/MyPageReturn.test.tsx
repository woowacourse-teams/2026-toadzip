import { PageReturnLink } from './MyPageReturn'
import { useMyPageReturnState, myPageReturnPath } from './mypageNavigation'
import { fireEvent, render, screen } from '@testing-library/react'
import { Link, MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { expect, it } from 'vitest'

function Child() {
  const state = useMyPageReturnState()
  return <><PageReturnLink /><Link to="/privacy/version" state={state}>다음 문서</Link></>
}
function Origin() { const location = useLocation(); return <h1>{location.pathname}</h1> }
it('문서 간 이동 후에도 출발한 마이페이지 탭으로 돌아온다', () => {
  render(<MemoryRouter initialEntries={[{ pathname: '/privacy', state: { mypageReturn: '/mypage/account' } }]}><Routes>
    <Route path="/privacy/*" element={<Child />} /><Route path="/mypage/account" element={<Origin />} />
  </Routes></MemoryRouter>)
  fireEvent.click(screen.getByRole('link', { name: '다음 문서' }))
  const close = screen.getByRole('link', { name: '마이페이지로 돌아가기' })
  expect(close.textContent).toBe('')
  expect(close.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  fireEvent.click(close)
  expect(screen.getByRole('heading')).toHaveTextContent('/mypage/account')
})
it('직접 진입은 지도 복귀를 제공하며 외부 주소와 임의 경로를 받지 않는다', () => {
  expect(myPageReturnPath({ mypageReturn: 'https://example.com' })).toBeNull()
  expect(myPageReturnPath({ mypageReturn: '/admin' })).toBeNull()
  expect(myPageReturnPath({ mypageReturn: '/mypage/bookmarks?type=ANNOUNCEMENT' })).toBe('/mypage/bookmarks?type=ANNOUNCEMENT')
  render(<MemoryRouter initialEntries={['/privacy']}><PageReturnLink /></MemoryRouter>)
  expect(screen.getByRole('link', { name: '지도로 돌아가기' })).toHaveAttribute('href', '/explore')
  expect(screen.queryByRole('link', { name: '마이페이지로 돌아가기' })).not.toBeInTheDocument()
})
