import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { expect, it } from 'vitest'
import { FailureReturnLink } from './FailureReturnLink'
import { failureReturnUrl } from './failureNavigation'
import { managementListParams, managementUrl } from '../management/managementNavigation'

it.each([false,true])('제품 목록·상세를 거쳐도 선택한 오류와 필터로 돌아간다 (기존 목록 경로: %s)', legacy => {
  const returnTo='/admin/failures?domain=announcement&keyword=한스빌&page=2&failure=announcement:7&result=1'
  const candidate = new URL(failureReturnUrl('/admin/complexes?keyword=한스빌',returnTo),'https://test.invalid')
  const params=managementListParams(candidate.searchParams,'complexes')
  const detail=legacy ? `/admin/complexes/1144?returnTo=${encodeURIComponent(candidate.pathname+candidate.search)}` : managementUrl('complexes', params, '1144')
  render(<MemoryRouter initialEntries={[detail]}><Routes>
    <Route path="/admin/complexes/:id" element={<FailureReturnLink/>}/>
    <Route path="/admin/failures" element={<h1>오류 확인·처리</h1>}/>
  </Routes></MemoryRouter>)
  const link=screen.getByRole('link',{name:'오류 처리로 돌아가 결과 확인'})
  expect(link).toHaveAttribute('href',returnTo)
  fireEvent.click(link)
  expect(screen.getByRole('heading',{name:'오류 확인·처리'})).toBeVisible()
})

it.each(['https://outside.invalid', '//outside.invalid', '/admin/failures/other', '/admin/users', '/admin/failures#bad'])('외부 또는 다른 경로를 복귀 링크로 사용하지 않는다: %s', returnTo => {
  render(<MemoryRouter initialEntries={[failureReturnUrl('/admin/complexes',returnTo)]}><FailureReturnLink/></MemoryRouter>)
  expect(screen.queryByRole('link')).not.toBeInTheDocument()
})
