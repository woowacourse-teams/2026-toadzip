import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { ManagementDetail } from './ManagementDetail'
import type { ManagementDetailData } from './managementContract'

const api = vi.hoisted(() => ({ getManagementDetail: vi.fn(), getManagementHistory: vi.fn() }))
vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), ...api }))
const detail: ManagementDetailData = { summary: { id: 7, name: '검토 단지', subtitle: '서울', provider: 'LH', rental: 'HAPPY_HOUSING',
  deleted: false, modified: false, reviewRequired: false, updatedAt: null }, sourceIdentifier: 'source', data: { version: 1, name: '검토 단지' },
  housingTypes: [], announcements: [], supplyRows: [], scheduleReviewed: false, schedules: [] }
beforeEach(() => {
  vi.resetAllMocks()
  api.getManagementDetail.mockResolvedValue(detail)
  api.getManagementHistory.mockResolvedValue([])
})
function Location() {
  const location = useLocation(); const navigate = useNavigate()
  return <><output aria-label="현재 주소">{location.search}</output><button type="button" onClick={() => navigate('/admin/complexes/7?provider=LH&section=history')}>이력 주소로 이동</button></>
}
function show(path: string) {
  render(<MemoryRouter initialEntries={[path]}><Routes><Route path="/admin/complexes/:id" element={<><ManagementDetail resource="complexes" /><Location /></>} /></Routes></MemoryRouter>)
}

it('탭을 바꾸면 다른 조건을 유지한 채 주소의 섹션을 바꾸고, 주소가 바뀌면 해당 탭을 연다', async () => {
  show('/admin/complexes/7?provider=LH&section=history')
  expect(await screen.findByRole('button', { name: '출처·수정 이력' })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(screen.getByRole('button', { name: '기본정보' }))
  expect(screen.getByRole('button', { name: '기본정보' })).toHaveAttribute('aria-pressed', 'true')
  const search = new URLSearchParams(screen.getByLabelText('현재 주소').textContent ?? '')
  expect(search.get('section')).toBe('info')
  expect(search.get('provider')).toBe('LH')
  fireEvent.click(screen.getByRole('button', { name: '이력 주소로 이동' }))
  expect(screen.getByRole('button', { name: '출처·수정 이력' })).toHaveAttribute('aria-pressed', 'true')
})

it('알 수 없는 섹션 주소는 기본정보를 연다', async () => {
  show('/admin/complexes/7?section=json')
  expect(await screen.findByRole('button', { name: '기본정보' })).toHaveAttribute('aria-pressed', 'true')
})
