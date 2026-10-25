import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { ManagementDetail } from './ManagementDetail'
import type { ManagementDetailData } from './managementContract'

const api = vi.hoisted(() => ({ getManagementDetail: vi.fn(), getManagementHistory: vi.fn() }))
vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), ...api }))
vi.mock('../quality/QualityComparisonPanel', () => ({ QualityComparisonPanel: () => <section aria-label="공고 원천 비교 내용">수집값 확인</section> }))
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
  render(<MemoryRouter initialEntries={[path]}><Routes><Route path="/admin/complexes/:id" element={<><ManagementDetail resource="complexes" /><Location /></>} /><Route path="/admin/announcements/:id" element={<><ManagementDetail resource="announcements" /><Location /></>} /></Routes></MemoryRouter>)
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

it('공고 보정 도구 줄을 없애고 출처·수정 이력 안에서 원천 비교를 접어 둔다', async () => {
  show('/admin/announcements/7?section=history')
  expect(await screen.findByRole('button', { name: '출처·수정 이력' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.queryByRole('navigation', { name: '공고 보정 도구' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'JSON으로 갱신' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '원천 비교' })).not.toBeInTheDocument()
  const comparison = screen.getByLabelText('공고 원천 비교 내용').closest('details')!
  expect(comparison).not.toHaveAttribute('open')
  fireEvent.click(screen.getByText('수집 원천과 비교'))
  expect(comparison).toHaveAttribute('open')
})
it('이전 공고 원천 비교 링크는 조회 조건을 보존하고 이력의 비교 영역을 펼친다', async () => {
  show('/admin/announcements/7?section=verification&provider=LH&workReturnTo=%2Fadmin%2Ffailures')
  expect(await screen.findByRole('button', { name: '출처·수정 이력' })).toHaveAttribute('aria-pressed', 'true')
  await waitFor(() => expect(screen.getByLabelText('현재 주소')).toHaveTextContent('section=history'))
  expect(screen.getByLabelText('공고 원천 비교 내용').closest('details')).toHaveAttribute('open')
  const params = new URLSearchParams(screen.getByLabelText('현재 주소').textContent ?? '')
  expect(params.get('provider')).toBe('LH')
  expect(params.get('workReturnTo')).toBe('/admin/failures')
  expect(params.get('comparison')).toBe('source')
})
it('폐기한 JSON 갱신 주소는 공고 기본정보로 이동한다', async () => {
  show('/admin/announcements/7?section=json&provider=LH')
  expect(await screen.findByRole('button', { name: '기본정보' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.queryByText('공고 갱신 JSON')).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'PDF로 보정' })).toBeVisible()
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent('section=info')
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent('provider=LH')
})
