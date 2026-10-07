import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AnnouncementRegistrationPage } from './AnnouncementRegistrationPage'
import { HousingComplexRegistrationForm } from './HousingComplexRegistrationForm'
import type { HousingComplexCreateResponse } from './api'

const api = vi.hoisted(() => ({ createHousingComplex: vi.fn(), createAnnouncement: vi.fn(), getManagementPage: vi.fn(), getManagementDetail: vi.fn() }))
vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), createHousingComplex: api.createHousingComplex, createAnnouncement: api.createAnnouncement }))
vi.mock('../management/api', async original => ({ ...(await original<typeof import('../management/api')>()), getManagementPage: api.getManagementPage, getManagementDetail: api.getManagementDetail }))
const summary = { id: 42, name: '두꺼비 국민임대', subtitle: '서울 중구 세종대로 1', provider: 'SH', rental: 'NATIONAL_RENTAL', deleted: false, modified: false, reviewRequired: false, updatedAt: null }
beforeEach(() => {
  vi.clearAllMocks()
  api.getManagementPage.mockResolvedValue({ items: [summary] })
  api.getManagementDetail.mockResolvedValue({ summary, data: { address: { pnu: '1114010100100010000' } } })
})
afterEach(() => vi.restoreAllMocks())

it('19자리 PNU로 행정구역 코드를 채우며 불완전한 입력은 기존 값을 바꾸지 않는다', () => {
  render(<HousingComplexRegistrationForm onCreated={vi.fn()} />, { wrapper: MemoryRouter })
  fireEvent.change(screen.getByLabelText('PNU'), { target: { value: '1114010100100010000' } })
  expect(screen.getByLabelText('법정동 코드')).toHaveValue('1114010100')
  expect(screen.getByLabelText('시·도 코드')).toHaveValue('11')
  expect(screen.getByLabelText('시·군·구 코드')).toHaveValue('11140')
  fireEvent.change(screen.getByLabelText('PNU'), { target: { value: '11' } })
  expect(screen.getByLabelText('법정동 코드')).toHaveValue('1114010100')
})

it('단지 선택은 기관·유형·원문 단지명·PNU를 채우고 작성 중인 공고명을 유지한다', async () => {
  render(<MemoryRouter initialEntries={['/admin/announcements/new?mode=direct']}><AnnouncementRegistrationPage /></MemoryRouter>)
  fireEvent.change(screen.getByLabelText('공고명'), { target: { value: '작성 중인 모집공고' } })
  fireEvent.click(screen.getByRole('button', { name: '단지 검색' }))
  fireEvent.click(await screen.findByRole('button', { name: `선택: ${summary.name}` }))
  await screen.findByText(/선택 단지:/)
  expect(screen.getByLabelText('공급 기관')).toHaveValue('SH')
  expect(screen.getByLabelText('공급 유형')).toHaveValue('NATIONAL_RENTAL')
  expect(screen.getByLabelText('원문 단지명')).toHaveValue(summary.name)
  expect(screen.getByLabelText('공급 PNU')).toHaveValue('1114010100100010000')
  expect(screen.getByLabelText('공고명')).toHaveValue('작성 중인 모집공고')
})

it('새 단지를 같은 화면에서 저장해 연결하며 저장 중 방식·선택·공고 입력을 잠근다', async () => {
  let finish: (item: HousingComplexCreateResponse) => void = () => { throw new Error('not initialized') }
  api.createHousingComplex.mockReturnValue(new Promise<HousingComplexCreateResponse>(resolve => { finish = resolve }))
  render(<MemoryRouter initialEntries={['/admin/announcements/new?mode=direct']}><AnnouncementRegistrationPage /></MemoryRouter>)
  fireEvent.click(screen.getByRole('button', { name: '새 단지 등록' }))
  const housing = screen.getByRole('region', { name: '단지 등록' })
  fireEvent.change(within(housing).getByLabelText('단지명'), { target: { value: summary.name } })
  fireEvent.submit(within(housing).getByRole('button', { name: '단지 저장' }).closest('form')!)
  expect(screen.getByRole('button', { name: 'JSON 가져오기' })).toBeDisabled()
  expect(within(screen.getByRole('region', { name: '공고를 연결할 단지' })).getByRole('button', { name: '단지 검색' })).toBeDisabled()
  expect(screen.getByLabelText('공고명')).toBeDisabled()
  await act(() => finish({ housingComplexId: 42, name: summary.name, roadAddress: summary.subtitle }))
  await screen.findByText(/선택 단지:/)
  expect(screen.queryByRole('region', { name: '단지 등록' })).not.toBeInTheDocument()
  expect(screen.getByLabelText('공급 PNU')).toHaveValue('1114010100100010000')
  expect(screen.getByRole('button', { name: '공고 저장' })).toBeEnabled()
})

it('작성 중 입력 방식 전환을 취소하면 공고 내용을 유지한다', () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  render(<MemoryRouter initialEntries={['/admin/announcements/new?mode=direct']}><AnnouncementRegistrationPage /></MemoryRouter>)
  fireEvent.change(screen.getByLabelText('공고명'), { target: { value: '저장 전 공고' } })
  fireEvent.click(screen.getByRole('button', { name: 'JSON 가져오기' }))
  expect(confirm).toHaveBeenCalledOnce()
  expect(screen.getByLabelText('공고명')).toHaveValue('저장 전 공고')
  expect(screen.queryByLabelText('공고 JSON')).not.toBeInTheDocument()
})

it('기본 등록 옵션 밖의 선택 단지 유형도 자동 입력으로 보존한다', async () => {
  api.getManagementDetail.mockResolvedValue({ summary: { ...summary, rental: 'PUBLIC_RENTAL_5Y' }, data: { address: { pnu: '1114010100100010000' } } })
  render(<MemoryRouter initialEntries={['/admin/announcements/new?mode=direct&complexId=42']}><AnnouncementRegistrationPage /></MemoryRouter>)
  await screen.findByText(/선택 단지:/)
  expect(screen.getByLabelText('공급 유형')).toHaveValue('PUBLIC_RENTAL_5Y')
  expect(screen.getByRole('option', { name: '5년 공공임대' })).toBeInTheDocument()
})

it('단지와 공고에 모두 미저장 입력이 있어도 이동 확인은 한 번만 표시한다', () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
  render(<MemoryRouter initialEntries={['/admin/announcements/new?mode=direct']}><AnnouncementRegistrationPage /></MemoryRouter>)
  fireEvent.click(screen.getByRole('button', { name: '새 단지 등록' }))
  fireEvent.change(screen.getByLabelText('단지명'), { target: { value: '미저장 단지' } })
  fireEvent.change(screen.getByLabelText('공고명'), { target: { value: '미저장 공고' } })
  fireEvent.click(screen.getByRole('button', { name: 'JSON 가져오기' }))
  expect(confirm).toHaveBeenCalledOnce()
  expect(screen.getByLabelText('공고 JSON')).toBeVisible()
})
