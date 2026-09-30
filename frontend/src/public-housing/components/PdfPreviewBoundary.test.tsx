import { render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { PdfPreviewBoundary } from './PdfPreviewBoundary.tsx'

it('뷰어 모듈 로딩 실패는 상세나 다운로드를 없애지 않고 안내로 격리한다', () => {
  const error = vi.spyOn(console, 'error').mockImplementation(() => {})
  function BrokenViewer(): never { throw new Error('viewer chunk load failed') }
  try {
    render(<><button>다운로드</button><PdfPreviewBoundary><BrokenViewer /></PdfPreviewBoundary></>)
    expect(screen.getByRole('alert')).toHaveTextContent('PDF 뷰어를 불러오지 못했습니다')
    expect(screen.getByRole('button', { name: '다운로드' })).toBeVisible()
  } finally { error.mockRestore() }
})
