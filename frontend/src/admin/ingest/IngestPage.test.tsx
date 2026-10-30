import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { IngestPage } from './IngestPage'
import { getPipelineHistory } from './api'

vi.mock('./DataPipelineControl', () => ({ DataPipelineControl: () => <section aria-label="단계 작업">단지 작업</section> }))
vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(), getPipelineHistory: vi.fn().mockResolvedValue([]) }))
beforeEach(() => vi.clearAllMocks())

it('이력은 제목 옆 버튼으로만 열고 본문의 마지막 결과는 남겨 둔다', async () => {
  render(<MemoryRouter><IngestPage domain="complex" /></MemoryRouter>)
  expect(getPipelineHistory).not.toHaveBeenCalled()
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '실행 이력' }))
  expect(await screen.findByText('실행 이력이 없습니다.')).toBeVisible()
  expect(screen.getByRole('dialog', { name: '실행 이력' })).toBeVisible()
  expect(getPipelineHistory).toHaveBeenCalledWith(0, 'complex')
  expect(screen.getByRole('region', { name: '단계 작업' })).toBeVisible()
  const close = screen.getByRole('button', { name: '이력 닫기' })
  expect(close).toHaveTextContent(/^$/)
  expect(close.querySelector('svg')).not.toBeNull()
  fireEvent.click(close)
  expect(screen.queryByRole('dialog', { name: '실행 이력' })).not.toBeInTheDocument()
})

it('모달 내부와 내부에서 시작한 드래그는 유지하고 바깥을 눌렀을 때만 닫는다', async () => {
  render(<MemoryRouter><IngestPage domain="complex" /></MemoryRouter>)
  fireEvent.click(screen.getByRole('button', { name: '실행 이력' }))
  await screen.findByText('실행 이력이 없습니다.')
  const dialog = screen.getByRole('dialog', { name: '실행 이력' })
  vi.spyOn(dialog, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 100, 300, 200))
  const press = (x: number, y: number) => fireEvent(dialog, new MouseEvent('pointerdown', { bubbles: true, clientX: x, clientY: y }))
  press(120, 120)
  fireEvent.click(dialog, { clientX: 120, clientY: 120 })
  expect(dialog).toBeVisible()
  fireEvent.click(screen.getByText('최근 실행 20건씩 확인할 수 있습니다.'))
  expect(dialog).toBeVisible()
  press(120, 120)
  fireEvent.click(dialog, { clientX: 20, clientY: 20 })
  expect(dialog).toBeVisible()
  press(20, 20)
  fireEvent.click(dialog, { clientX: 20, clientY: 20 })
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(screen.queryByText('실행 이력이 없습니다.')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '실행 이력' }))
  await screen.findByText('실행 이력이 없습니다.')
  expect(getPipelineHistory).toHaveBeenCalledTimes(2)
})
