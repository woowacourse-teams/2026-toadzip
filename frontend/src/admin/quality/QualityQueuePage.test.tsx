import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, useNavigate } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { QualityQueuePage } from './QualityQueuePage'

const mocks = vi.hoisted(() => ({ requestManagementApi: vi.fn() }))
vi.mock('../management/api', () => mocks)
beforeEach(() => vi.clearAllMocks())

function BackButton() {
  const navigate = useNavigate()
  return <button onClick={() => navigate(-1)}>뒤로가기</button>
}

it('뒤로가기로 조회 조건이 바뀌면 검색어 입력과 결과를 함께 복원한다', async () => {
  mocks.requestManagementApi.mockResolvedValue({ items: [], page: 0, totalElements: 0, totalPages: 0, hasNext: false })
  render(<MemoryRouter initialEntries={['/admin/quality?keyword=처음 단지']}><BackButton /><QualityQueuePage /></MemoryRouter>)
  expect(screen.getByLabelText('이름 검색')).toHaveValue('처음 단지')
  fireEvent.change(screen.getByLabelText('이름 검색'), { target: { value: '다음 단지' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  expect(mocks.requestManagementApi).toHaveBeenLastCalledWith(expect.stringContaining('keyword=%EB%8B%A4%EC%9D%8C+%EB%8B%A8%EC%A7%80'), 'GET', undefined, expect.any(AbortSignal))
  fireEvent.click(screen.getByRole('button', { name: '뒤로가기' }))
  expect(screen.getByLabelText('이름 검색')).toHaveValue('처음 단지')
  expect(mocks.requestManagementApi).toHaveBeenLastCalledWith(expect.stringContaining('keyword=%EC%B2%98%EC%9D%8C+%EB%8B%A8%EC%A7%80'), 'GET', undefined, expect.any(AbortSignal))
})

it('이전 담당 업무 주소로 들어와도 검토 목록을 조회하고 검색할 수 있다', async () => {
  mocks.requestManagementApi.mockResolvedValue({ items: [], page: 0, totalElements: 0, totalPages: 0, hasNext: false })
  render(<MemoryRouter initialEntries={['/admin/quality?view=tasks&taskMine=true&taskPage=2']}><QualityQueuePage /></MemoryRouter>)
  expect(await screen.findByText('현재 조건에 검토할 대상이 없습니다.')).toBeVisible()
  expect(screen.getByRole('form', { name: '검토 대상 검색' })).toBeVisible()
  expect(mocks.requestManagementApi).toHaveBeenCalledWith('/api/admin/quality/queue?', 'GET', undefined, expect.any(AbortSignal))
})

it('검토 사유와 빠진 항목을 보여주고 비교 화면으로 바로 연결한다', async () => {
  mocks.requestManagementApi.mockResolvedValue({ items: [{ resource: 'COMPLEX', id: 7, name: '검토 단지',
    reasons: ['MISSING', 'STALE'], missingFields: ['준공일'], updatedAt: null }], page: 0,
    totalElements: 1, totalPages: 1, hasNext: false })
  render(<MemoryRouter><QualityQueuePage /></MemoryRouter>)
  const reviewLink = await screen.findByRole('link', { name: '검토 단지 비교·검토' })
  expect(within(reviewLink.closest('li')!).getByText('확인값 변경')).toBeVisible()
  expect(screen.getByText('준공일')).toBeVisible()
  const target = new URL(reviewLink.getAttribute('href')!, 'http://localhost')
  expect(target.pathname).toBe('/admin/complexes/7')
  expect(target.searchParams.get('section')).toBe('verification')
  expect(target.searchParams.get('workReturnTo')).toBe('/admin/quality?focus=COMPLEX%3A7')
  fireEvent.change(screen.getByLabelText('검토 사유'), { target: { value: 'MISSING' } })
  expect(mocks.requestManagementApi).toHaveBeenLastCalledWith(expect.stringContaining('reason=MISSING'), 'GET', undefined, expect.any(AbortSignal))
})

it('조회 실패를 빈 결과로 표시하지 않고 재시도를 제공한다', async () => {
  mocks.requestManagementApi.mockRejectedValue(new Error('DB 조회 실패'))
  render(<MemoryRouter><QualityQueuePage /></MemoryRouter>)
  expect(await screen.findByRole('alert')).toHaveTextContent('DB 조회 실패')
  expect(screen.queryByText('현재 조건에 검토할 대상이 없습니다.')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '다시 조회' })).toBeEnabled()
})

it('검토 상세에 검색조건과 현재 페이지를 전달한다', async () => {
  mocks.requestManagementApi.mockResolvedValue({ items: [{ resource: 'ANNOUNCEMENT', id: 9, name: '한스빌', reasons: ['MISSING'], missingFields: [], updatedAt: null }], page: 2, totalElements: 41, totalPages: 3, hasNext: false })
  render(<MemoryRouter initialEntries={['/admin/quality?keyword=한스빌&reason=MISSING&page=2']}><QualityQueuePage /></MemoryRouter>)
  const link = await screen.findByRole('link', { name: '한스빌 비교·검토' })
  const target = new URL(link.getAttribute('href')!, 'http://localhost')
  const origin = new URL(target.searchParams.get('workReturnTo')!, 'http://localhost')
  expect(origin.searchParams.get('keyword')).toBe('한스빌')
  expect(origin.searchParams.get('reason')).toBe('MISSING')
  expect(origin.searchParams.get('page')).toBe('2')
})

it('식별자·기관·모집상태 검색을 적용하고 상세 복귀 주소와 뒤로가기에 보존한다', async () => {
  mocks.requestManagementApi.mockResolvedValue({ items: [{ resource: 'ANNOUNCEMENT', id: 9, name: '한스빌', reasons: ['MISSING'], missingFields: [], updatedAt: null }], page: 0, totalElements: 1, totalPages: 1, hasNext: false })
  render(<MemoryRouter initialEntries={['/admin/quality?keyword=한스빌']}><BackButton /><QualityQueuePage /></MemoryRouter>)
  fireEvent.click(screen.getByText('식별자·기관·모집상태로 좁히기', { selector: 'summary' }))
  fireEvent.change(screen.getByLabelText('원천 식별자'), { target: { value: '21395' } })
  fireEvent.change(screen.getByLabelText('공고 ID'), { target: { value: '9' } })
  fireEvent.change(screen.getByLabelText('단지 ID'), { target: { value: '7' } })
  fireEvent.change(screen.getByLabelText('공급기관'), { target: { value: 'LH' } })
  fireEvent.change(screen.getByLabelText('모집상태'), { target: { value: 'APPLYING' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  expect(mocks.requestManagementApi).toHaveBeenLastCalledWith(expect.stringContaining('sourceIdentifier=21395&announcementId=9&complexId=7&provider=LH&applicationStatus=APPLYING'), 'GET', undefined, expect.any(AbortSignal))
  const link = await screen.findByRole('link', { name: '한스빌 비교·검토' })
  const target = new URL(link.getAttribute('href')!, 'http://localhost')
  expect(target.searchParams.get('workReturnTo')).toContain('sourceIdentifier=21395')
  fireEvent.click(screen.getByRole('button', { name: '뒤로가기' }))
  expect(screen.getByLabelText('원천 식별자')).toHaveValue('')
  expect(screen.getByLabelText('공급기관')).toHaveValue('')
})
