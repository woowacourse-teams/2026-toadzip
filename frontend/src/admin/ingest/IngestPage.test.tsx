import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, it, vi } from 'vitest'
import { IngestPage } from './IngestPage'
import { getPipelineHistory } from './api'

vi.mock('./DataPipelineControl', () => ({ DataPipelineControl: () => <section aria-label="단계 작업">단지 작업</section> }))
vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(), getPipelineHistory: vi.fn().mockResolvedValue([]) }))

it('이력은 제목 옆 버튼으로만 열고 본문의 마지막 결과는 남겨 둔다', async () => {
  render(<MemoryRouter><IngestPage domain="complex" /></MemoryRouter>)
  expect(getPipelineHistory).not.toHaveBeenCalled()
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '실행 이력' }))
  expect(await screen.findByText('실행 이력이 없습니다.')).toBeVisible()
  expect(screen.getByRole('dialog', { name: '실행 이력' })).toBeVisible()
  expect(getPipelineHistory).toHaveBeenCalledWith(0, 'complex')
  expect(screen.getByRole('region', { name: '단계 작업' })).toBeVisible()
})
