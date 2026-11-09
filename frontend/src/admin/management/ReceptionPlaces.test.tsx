import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { ManagementDetail } from './ManagementDetail'
import { ManagementError } from './api'
import { type ManagementDetailData, type ManagementValues } from './managementContract'

const mocks = vi.hoisted(() => ({ getManagementDetail: vi.fn(), requestManagementApi: vi.fn(), getManagementHistory: vi.fn() }))
vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), ...mocks }))

const online = { name: 'LH 청약플러스', method: 'ONLINE', address: null, contact: null, url: 'https://apply.lh.or.kr/', guidance: null }
const visit = { name: '두꺼비 주거복지지사', method: 'VISIT', address: '서울시 중구 세종대로 1', contact: '1600-1004', url: null, guidance: '인터넷 취약계층만 현장 접수' }
function fixture(receptionPlaces: ManagementValues[] = [online, visit]): ManagementDetailData {
  return { summary: { id: 7, name: '두꺼비 공고', subtitle: 'LH', provider: 'LH', rental: 'HAPPY_HOUSING', deleted: false, modified: false, reviewRequired: false, updatedAt: null },
    sourceIdentifier: 'TEST-7', data: { version: 2, name: '두꺼비 공고', rentalType: 'HAPPY_HOUSING', agencyCode: 'LH', recruitmentType: 'NEW', originalUrl: 'https://example.com/notice',
      postedDate: '2026-08-01', applicationStartDate: '2026-08-10', applicationEndDate: '2026-08-14', winnerAnnouncementDate: '2026-09-01', receptionPlaces },
    housingTypes: [], announcements: [], supplyRows: [], scheduleReviewed: false, schedules: [] }
}
function renderDetail() {
  render(<MemoryRouter initialEntries={['/admin/announcements/7']}><Routes><Route path="/admin/announcements/:id" element={<ManagementDetail resource="announcements" />} /></Routes></MemoryRouter>)
}
function sentPlaces() { return (mocks.requestManagementApi.mock.lastCall?.[2] as ManagementValues | undefined)?.receptionPlaces }
beforeEach(() => { vi.clearAllMocks(); mocks.getManagementDetail.mockResolvedValue(fixture()); mocks.getManagementHistory.mockResolvedValue([]) })

it('공고 상세는 접수처를 공고문 순서대로 접수처마다 방식과 칸을 나눠 보여준다', async () => {
  renderDetail()
  const first = (await screen.findByRole('heading', { name: '접수처 1 · 온라인' })).parentElement!
  expect(within(first).getByText('LH 청약플러스')).toBeVisible()
  expect(within(first).getByRole('link', { name: 'https://apply.lh.or.kr/' })).toHaveAttribute('href', 'https://apply.lh.or.kr/')
  const second = screen.getByRole('heading', { name: '접수처 2 · 방문' }).parentElement!
  expect(within(second).getByText('1600-1004')).toBeVisible()
  expect(within(second).getByText('인터넷 취약계층만 현장 접수')).toBeVisible()
})

it('접수처가 없는 공고는 없음으로 보여준다', async () => {
  mocks.getManagementDetail.mockResolvedValue(fixture([]))
  renderDetail()
  expect(await screen.findByText('등록된 접수처가 없습니다.')).toBeVisible()
  expect(screen.queryByRole('heading', { name: /접수처 1/ })).not.toBeInTheDocument()
})

it('수정에서 접수처를 추가·삭제하면 저장 확인 대상이 되고, 칸이 모두 빈 접수처는 버리고 목록으로 저장한다', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  mocks.requestManagementApi.mockResolvedValue(fixture([visit]))
  renderDetail()
  fireEvent.click(await screen.findByRole('button', { name: '기본정보 수정' }))
  fireEvent.click(screen.getByRole('button', { name: '접수처 1 삭제' }))
  fireEvent.click(screen.getByRole('button', { name: '취소' }))
  expect(confirm).toHaveBeenCalledOnce()

  const kept = screen.getByRole('group', { name: '접수처 1' })
  expect(within(kept).getByLabelText('접수처명')).toHaveValue('두꺼비 주거복지지사')
  fireEvent.change(within(kept).getByLabelText('안내'), { target: { value: '인터넷 취약계층·고령자만 현장 접수' } })
  fireEvent.click(screen.getByRole('button', { name: '접수처 추가' }))
  expect(within(screen.getByRole('group', { name: '접수처 2' })).getByLabelText('접수 방식')).toHaveValue('VISIT')
  fireEvent.submit(screen.getByRole('button', { name: '변경사항 저장' }).closest('form')!)

  await waitFor(() => expect(mocks.requestManagementApi).toHaveBeenCalledWith('/api/admin/announcements/7', 'PUT', expect.objectContaining({ version: 2, name: '두꺼비 공고' })))
  expect(sentPlaces()).toEqual([{ ...visit, guidance: '인터넷 취약계층·고령자만 현장 접수' }])
  expect(mocks.requestManagementApi.mock.lastCall?.[2]).not.toHaveProperty('receptionPlace')
  confirm.mockRestore()
})

it('접수처를 모두 삭제하면 빈 목록으로 저장한다', async () => {
  mocks.requestManagementApi.mockResolvedValue(fixture([]))
  renderDetail()
  fireEvent.click(await screen.findByRole('button', { name: '기본정보 수정' }))
  fireEvent.click(screen.getByRole('button', { name: '접수처 2 삭제' }))
  fireEvent.click(screen.getByRole('button', { name: '접수처 1 삭제' }))
  expect(screen.getByText('등록된 접수처가 없습니다.')).toBeVisible()
  fireEvent.submit(screen.getByRole('button', { name: '변경사항 저장' }).closest('form')!)
  await waitFor(() => expect(mocks.requestManagementApi).toHaveBeenCalled())
  expect(sentPlaces()).toEqual([])
})

it('빈 접수처를 버려 서버 목록 순서가 화면과 달라도 서버 검증 오류는 화면의 그 접수처 칸에 붙는다', async () => {
  mocks.requestManagementApi.mockRejectedValue(new ManagementError('요청값이 올바르지 않습니다.', 400, { 'receptionPlaces[2].name': '필수 값입니다.' }))
  renderDetail()
  fireEvent.click(await screen.findByRole('button', { name: '기본정보 수정' }))
  fireEvent.click(screen.getByRole('button', { name: '접수처 추가' }))
  fireEvent.click(screen.getByRole('button', { name: '접수처 추가' }))
  // 접수처 3은 비워 두어 버리고, 접수처 4는 이름 없이 연락처만 넣어 서버가 목록의 세 번째(인덱스 2)로 거절한다.
  fireEvent.change(within(screen.getByRole('group', { name: '접수처 4' })).getByLabelText('연락처'), { target: { value: '1600-1004' } })
  fireEvent.submit(screen.getByRole('button', { name: '변경사항 저장' }).closest('form')!)

  expect(await screen.findByRole('alert')).toHaveTextContent('요청값이 올바르지 않습니다.')
  expect(sentPlaces()).toEqual([online, visit, { name: null, method: 'VISIT', address: null, contact: '1600-1004', url: null, guidance: null }])
  expect(within(screen.getByRole('group', { name: '접수처 4' })).getByLabelText('접수처명')).toHaveAttribute('aria-invalid', 'true')
  expect(within(screen.getByRole('group', { name: '접수처 3' })).getByLabelText('접수처명')).not.toHaveAttribute('aria-invalid')
})
