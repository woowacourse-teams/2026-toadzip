import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { DocumentArchivePanel } from './DocumentArchivePanel'

const mocks = vi.hoisted(() => ({ archiveDocument: vi.fn(), listArchivedDocuments: vi.fn() }))
vi.mock('./documentArchiveApi', async original => ({ ...await original<typeof import('./documentArchiveApi')>(), ...mocks }))
beforeEach(() => { vi.clearAllMocks(); mocks.archiveDocument.mockResolvedValue('a'.repeat(64)); mocks.listArchivedDocuments.mockResolvedValue([]) })
it('명시적으로 선택한 원본만 서버에 보관하고 보관 원본 다운로드 경로를 표시한다', async () => {
  const file = new File(['%PDF-1.7'], '공식.pdf', { type: 'application/pdf' })
  mocks.listArchivedDocuments.mockResolvedValue([{ sha256: 'a'.repeat(64), fileName: file.name, sourceUrl: 'https://example.com', byteCount: file.size, actor: 'admin', createdAt: '2026-10-16T00:00:00Z' }])
  render(<DocumentArchivePanel resource="complexes" id={7} file={file} sourceUrl="https://example.com" onBusyChange={vi.fn()} />)
  expect(mocks.archiveDocument).not.toHaveBeenCalled()
  fireEvent.click(screen.getByText('관리자 공유 원본 보관함'))
  fireEvent.click(screen.getByRole('button', { name: '선택 PDF 원본 서버 보관' }))
  expect(await screen.findByRole('link', { name: '공식.pdf 원본 다운로드' })).toHaveAttribute('href', expect.stringContaining('/api/admin/quality/COMPLEX/7/documents/' + 'a'.repeat(64)))
  expect(mocks.archiveDocument).toHaveBeenCalledWith('complexes', 7, file, 'https://example.com', expect.any(AbortSignal))
})
it('보관 실패를 성공으로 표시하지 않는다', async () => {
  mocks.archiveDocument.mockRejectedValue(new Error('저장 공간 오류'))
  render(<DocumentArchivePanel resource="complexes" id={7} file={new File(['%PDF'], '공식.pdf')} sourceUrl="https://example.com" onBusyChange={vi.fn()} />)
  fireEvent.click(screen.getByText('관리자 공유 원본 보관함'))
  fireEvent.click(screen.getByRole('button', { name: '선택 PDF 원본 서버 보관' }))
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('저장 공간 오류'))
  expect(screen.queryByText(/원본 PDF를 서버에 보관했습니다/)).not.toBeInTheDocument()
})
