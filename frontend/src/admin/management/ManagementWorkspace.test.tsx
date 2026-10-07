import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { ManagementWorkspace } from './ManagementWorkspace'
import { ManagementError } from './api'
import type { ManagementDetailData } from './managementContract'

const api = vi.hoisted(() => ({ getManagementPage: vi.fn(), getManagementDetail: vi.fn(), requestManagementApi: vi.fn() }))
vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), ...api }))
const summary = { id: 7, name: '두꺼비 단지', subtitle: '서울 중구 세종대로 1', provider: 'LH', rental: 'HAPPY_HOUSING', deleted: false, modified: false, reviewRequired: false, updatedAt: null }
function detail(): ManagementDetailData {
  return { summary, sourceIdentifier: 'TEST-7', data: { version: 2, name: summary.name, agencyCode: 'LH', rentalType: 'HAPPY_HOUSING', address: { roadAddress: summary.subtitle, pnu: '1114010100100010000', legalDongCode: '1114010100', provinceCode: '11', cityCountyDistrictCode: '11140', latitude: 37.5, longitude: 127 }, totalHouseholdCount: 10, totalParkingCount: 5 }, housingTypes: [], announcements: [], supplyRows: [], scheduleReviewed: false, schedules: [] }
}
function renderPage(path = '/admin/complexes?keyword=두꺼비&provider=LH') {
  render(<MemoryRouter initialEntries={[path]}><Routes><Route path="/admin/complexes/:id?" element={<ManagementWorkspace resource="complexes" />} /><Route path="/admin/announcements/:id?" element={<ManagementWorkspace resource="announcements" />} /></Routes></MemoryRouter>)
}
beforeEach(() => {
  vi.clearAllMocks()
  api.getManagementPage.mockResolvedValue({ items: [summary], page: 0, totalPages: 1, totalElements: 1, hasNext: false })
  api.getManagementDetail.mockResolvedValue(detail())
})

it('행을 열어도 목록과 검색을 유지하며 바로 수정하고 닫는다', async () => {
  renderPage()
  const table = await screen.findByRole('table')
  fireEvent.click(screen.getByRole('link', { name: summary.name }))
  expect(await screen.findByLabelText('이름')).toHaveValue(summary.name)
  expect(screen.getByRole('table')).toBe(table)
  expect(screen.getByLabelText('단지명·주소')).toHaveValue('두꺼비')
  expect(screen.getByLabelText('기관')).toHaveValue('LH')
  expect(screen.getByRole('region', { name: '단지 상세·수정' })).toHaveFocus()
  expect(api.getManagementPage).toHaveBeenCalledOnce()
  fireEvent.click(screen.getByRole('link', { name: '편집 닫기' }))
  expect(screen.queryByLabelText('이름')).not.toBeInTheDocument()
  expect(screen.getByRole('table')).toBe(table)
})

it('저장하면 수정된 행을 다시 조회하고 실패하면 입력을 보존한다', async () => {
  renderPage('/admin/complexes/7?provider=LH')
  await screen.findByLabelText('이름')
  api.requestManagementApi.mockRejectedValueOnce(new ManagementError('다른 관리자가 수정했습니다.', 409))
  fireEvent.change(screen.getByLabelText('이름'), { target: { value: '수정한 단지' } })
  fireEvent.submit(screen.getByRole('button', { name: '변경사항 저장' }).closest('form')!)
  expect(await screen.findByRole('alert')).toHaveTextContent('다른 관리자가 수정했습니다.')
  expect(screen.getByLabelText('이름')).toHaveValue('수정한 단지')
  const next = { ...detail(), summary: { ...summary, name: '수정한 단지' }, data: { ...detail().data, name: '수정한 단지', version: 3 } }
  api.requestManagementApi.mockResolvedValueOnce(next)
  api.getManagementPage.mockResolvedValue({ items: [next.summary], page: 0, totalPages: 1, totalElements: 1, hasNext: false })
  fireEvent.submit(screen.getByRole('button', { name: '변경사항 저장' }).closest('form')!)
  expect(await screen.findByRole('link', { name: '수정한 단지' })).toBeVisible()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(api.getManagementPage.mock.lastCall?.[1].get('provider')).toBe('LH')
})

it('미저장 수정의 닫기와 검색을 취소하면 입력과 목록 조건을 유지한다', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  renderPage('/admin/complexes/7?provider=LH')
  await screen.findByLabelText('이름')
  fireEvent.change(screen.getByLabelText('이름'), { target: { value: '저장 전' } })
  fireEvent.click(screen.getByRole('link', { name: '편집 닫기' }))
  expect(screen.getByLabelText('이름')).toHaveValue('저장 전')
  fireEvent.change(screen.getByLabelText('단지명·주소'), { target: { value: '부산' } })
  fireEvent.submit(screen.getByRole('button', { name: '검색' }).closest('form')!)
  expect(confirm).toHaveBeenCalledTimes(2)
  expect(api.getManagementPage).toHaveBeenCalledOnce()
  confirm.mockRestore()
})

it('추가 화면도 조회 표를 유지하고 JSON 및 직접 입력을 제공한다', async () => {
  renderPage('/admin/announcements/new?keyword=행복')
  expect(await screen.findByRole('table')).toBeVisible()
  expect(screen.getByRole('heading', { name: '공고 관리', level: 1 })).toBeVisible()
  const editor = screen.getByRole('region', { name: '공고 추가' })
  expect(within(editor).getByRole('heading', { name: 'JSON 가져오기' })).toBeVisible()
  fireEvent.click(within(editor).getByRole('button', { name: '직접 입력' }))
  expect(within(editor).getByRole('button', { name: '공고 저장' })).toBeDisabled()
  expect(within(editor).getByLabelText('공고명')).toHaveValue('')
  expect(api.getManagementPage.mock.lastCall?.[1].get('keyword')).toBe('행복')
})

it('삭제 확인과 복구 후 목록을 갱신한다', async () => {
  renderPage('/admin/complexes/7')
  fireEvent.click(await screen.findByRole('button', { name: '삭제' }))
  expect(api.requestManagementApi).not.toHaveBeenCalled()
  api.requestManagementApi.mockResolvedValue(null)
  api.getManagementDetail.mockResolvedValue({ ...detail(), summary: { ...summary, deleted: true }, data: { ...detail().data, version: 3 } })
  fireEvent.click(screen.getByRole('button', { name: '휴지통으로 이동' }))
  await screen.findByRole('button', { name: '복구' })
  await waitFor(() => expect(api.getManagementPage).toHaveBeenCalledTimes(2))
  fireEvent.click(screen.getByRole('button', { name: '복구' }))
  api.getManagementDetail.mockResolvedValue({ ...detail(), data: { ...detail().data, version: 4 } })
  fireEvent.click(screen.getByRole('button', { name: '복구 확인' }))
  await screen.findByLabelText('이름')
  expect(api.requestManagementApi).toHaveBeenLastCalledWith('/api/admin/housing-complexes/7/restore?version=3', 'POST')
})

it('기존 returnTo 상세 URL의 검색 조건과 페이지를 화면에서도 복원한다', async () => {
  const returnTo = encodeURIComponent('/admin/complexes?keyword=두꺼비&provider=LH&page=1')
  api.getManagementPage.mockResolvedValue({ items: [summary], page: 1, totalPages: 3, totalElements: 41, hasNext: true })
  renderPage(`/admin/complexes/7?returnTo=${returnTo}`)
  await screen.findByLabelText('이름')
  expect(screen.getByLabelText('단지명·주소')).toHaveValue('두꺼비')
  expect(screen.getByLabelText('기관')).toHaveValue('LH')
  fireEvent.click(screen.getByRole('button', { name: '다음' }))
  await waitFor(() => expect(api.getManagementPage.mock.lastCall?.[1].get('page')).toBe('2'))
  expect(api.getManagementPage.mock.lastCall?.[1].get('keyword')).toBe('두꺼비')
  expect(api.getManagementPage.mock.lastCall?.[1].get('provider')).toBe('LH')
})
