import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { SourceUrl } from './SourceUrl'

it('전체 공식 URL을 새 탭 링크로 표시한다', () => {
  const url = 'https://apply.example.com/notices/7?category=housing'
  render(<SourceUrl url={url} />)
  const link = screen.getByRole('link', { name: url })
  expect(link).toHaveAttribute('href', url)
  expect(link).toHaveAttribute('target', '_blank')
  expect(link).toHaveAttribute('rel', 'noreferrer')
})

it.each([null, undefined, '', '   '])('URL %s가 없으면 기록 없음을 표시한다', url => {
  render(<SourceUrl url={url} />)
  expect(screen.getByText('기록 없음')).toBeVisible()
  expect(screen.queryByRole('link')).not.toBeInTheDocument()
})

it.each(['javascript:alert(1)', 'data:text/html,unsafe', '/relative', '잘못된 주소', 7])('안전하지 않은 URL %s는 링크로 만들지 않는다', url => {
  render(<SourceUrl url={url} />)
  expect(screen.getByText('URL 형식 확인 필요')).toBeVisible()
  expect(screen.queryByRole('link')).not.toBeInTheDocument()
})

it('쿼리 인증키와 URL 인증정보를 표시와 링크에서 모두 제거한다', () => {
  render(<SourceUrl url="https://fixture-user:fixture-password@example.com/notice?id=7&ServiceKey=fixture-credential&category=house" />)
  const link = screen.getByRole('link')
  expect(link).toHaveAttribute('href', 'https://example.com/notice?id=7&category=house')
  expect(link).toHaveTextContent('https://example.com/notice?id=7&category=house')
  expect(link.outerHTML).not.toMatch(/fixture-user|fixture-password|fixture-credential/)
})
