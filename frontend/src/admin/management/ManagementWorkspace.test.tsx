import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ManagementWorkspace } from './ManagementWorkspace'
import { ManagementError } from './api'
import type { ManagementDetailData } from './managementContract'
import type { HousingComplexCreateResponse } from '../registration/api'

const api = vi.hoisted(() => ({ getManagementPage: vi.fn(), getManagementDetail: vi.fn(), requestManagementApi: vi.fn() }))
vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), ...api }))
const registrationApi = vi.hoisted(() => ({ createHousingComplex: vi.fn() }))
vi.mock('../registration/api', async original => ({ ...(await original<typeof import('../registration/api')>()), ...registrationApi }))
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
afterEach(() => vi.restoreAllMocks())

function startInlineComplexRegistration() {
  let finish!: (value: HousingComplexCreateResponse) => void
  registrationApi.createHousingComplex.mockReturnValue(new Promise<HousingComplexCreateResponse>(resolve => { finish = resolve }))
  renderPage('/admin/announcements/new?mode=direct')
  fireEvent.click(screen.getByRole('button', { name: '새 단지 등록' }))
  const form = screen.getByRole('region', { name: '단지 등록' })
  fireEvent.change(within(form).getByLabelText('단지명'), { target: { value: '새 단지' } })
  fireEvent.submit(within(form).getByRole('button', { name: '단지 저장' }).closest('form')!)
  return () => act(async () => { finish({ housingComplexId: 8, name: '새 단지', roadAddress: summary.subtitle }) })
}

it('공고 편집을 닫은 뒤 도착한 단지 등록 응답은 편집창을 다시 열지 않는다', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  const finish = startInlineComplexRegistration()
  fireEvent.click(screen.getByRole('link', { name: '편집 닫기' }))
  expect(screen.queryByRole('region', { name: '공고 추가' })).not.toBeInTheDocument()
  await finish()
  expect(screen.queryByRole('region', { name: '공고 추가' })).not.toBeInTheDocument()
  expect(api.getManagementDetail).not.toHaveBeenCalled()
})

it('새 JSON 작업으로 이동하면 입력 방식 잠금을 풀고 늦은 단지 등록 응답에도 입력을 유지한다', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  const finish = startInlineComplexRegistration()
  fireEvent.click(screen.getByRole('link', { name: '공고 추가' }))
  fireEvent.change(screen.getByLabelText('공고 JSON'), { target: { value: '{"name":"새 작업"}' } })
  expect(screen.getByRole('button', { name: '직접 입력' })).toBeEnabled()
  await finish()
  expect(screen.queryByLabelText('공고 JSON')).toHaveValue('{"name":"새 작업"}')
  expect(api.getManagementDetail).not.toHaveBeenCalled()
})

it('이전 단지 등록의 완료는 다시 연 편집창에서 진행 중인 새 등록을 풀지 않는다', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  const finishPrevious = startInlineComplexRegistration()
  fireEvent.click(screen.getByRole('link', { name: '편집 닫기' }))
  fireEvent.click(screen.getByRole('link', { name: '공고 추가' }))
  fireEvent.click(screen.getByRole('button', { name: '직접 입력' }))
  let finishCurrent!: (value: HousingComplexCreateResponse) => void
  registrationApi.createHousingComplex.mockReturnValue(new Promise<HousingComplexCreateResponse>(resolve => { finishCurrent = resolve }))
  fireEvent.click(screen.getByRole('button', { name: '새 단지 등록' }))
  const form = screen.getByRole('region', { name: '단지 등록' })
  fireEvent.change(within(form).getByLabelText('단지명'), { target: { value: '현재 작업 단지' } })
  fireEvent.submit(within(form).getByRole('button', { name: '단지 저장' }).closest('form')!)
  await finishPrevious()
  expect(screen.getByRole('region', { name: '단지 등록' })).toBeVisible()
  expect(screen.getByRole('button', { name: 'JSON 가져오기' })).toBeDisabled()
  expect(within(screen.getByRole('region', { name: '공고 추가' })).getByLabelText('공고명')).toBeDisabled()
  await act(async () => { finishCurrent({ housingComplexId: 9, name: '현재 작업 단지', roadAddress: summary.subtitle }) })
  expect(await screen.findByText(/선택 단지:/)).toBeVisible()
  expect(api.getManagementDetail).toHaveBeenCalledExactlyOnceWith('complexes', '9', expect.any(AbortSignal))
  expect(screen.getByRole('button', { name: 'JSON 가져오기' })).toBeEnabled()
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
  expect(screen.getByRole('link', { name: summary.name })).toHaveFocus()
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

it('현재 기본정보 탭을 다시 눌러도 미저장 입력 보호가 유지된다', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
  renderPage('/admin/complexes/7')
  fireEvent.change(await screen.findByLabelText('이름'), { target: { value: '저장 전' } })
  fireEvent.click(screen.getByRole('button', { name: '기본정보' }))
  expect(confirm).not.toHaveBeenCalled()
  confirm.mockReturnValue(false)
  fireEvent.click(screen.getByRole('link', { name: '편집 닫기' }))
  expect(confirm).toHaveBeenCalledOnce()
  expect(screen.getByLabelText('이름')).toHaveValue('저장 전')
  confirm.mockRestore()
})

it('저장 중 편집을 닫은 뒤 도착한 등록 응답은 편집 화면을 다시 열지 않는다', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
  let finish!: (value: { housingComplexId: number; name: string; roadAddress: string }) => void
  registrationApi.createHousingComplex.mockReturnValue(new Promise(resolve => { finish = resolve }))
  renderPage('/admin/complexes/new')
  await screen.findByRole('table')
  fireEvent.change(screen.getByLabelText('단지명'), { target: { value: '새 단지' } })
  fireEvent.submit(screen.getByRole('button', { name: '단지 저장' }).closest('form')!)
  fireEvent.click(screen.getByRole('link', { name: '편집 닫기' }))
  expect(screen.queryByRole('region', { name: '단지 추가' })).not.toBeInTheDocument()
  await act(() => finish({ housingComplexId: 8, name: '새 단지', roadAddress: summary.subtitle }))
  expect(screen.queryByRole('region', { name: '단지 상세·수정' })).not.toBeInTheDocument()
  expect(api.getManagementDetail).not.toHaveBeenCalled()
  await waitFor(() => expect(api.getManagementPage).toHaveBeenCalledTimes(2))
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
