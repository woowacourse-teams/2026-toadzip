import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { ScheduleEditor } from './ScheduleEditor'
import { getManagementDetail, requestManagementApi } from './api'
import type { ManagementDetailData } from './managementContract'

const json = vi.hoisted(() => ({ loadJsonWorkspace: vi.fn() }))
vi.mock('../announcement-json/api', () => json)
vi.mock('./api', async original => ({
  ...await original<typeof import('./api')>(), getManagementDetail: vi.fn(), requestManagementApi: vi.fn(),
}))

function detail(): ManagementDetailData {
  return {
    summary: { id: 7, name: '공고', subtitle: '', provider: 'LH', rental: 'HAPPY_HOUSING', deleted: false, modified: false, reviewRequired: false, updatedAt: null },
    sourceIdentifier: 'LH-7', data: { version: 0, applicationStartDate: '2026-10-01', applicationEndDate: '2026-10-02', originalUrl: 'https://example.com/notice' },
    housingTypes: [], announcements: [], supplyRows: [], scheduleReviewed: false,
    schedules: [{ housingComplexId: null, supplyRank: '1순위', state: 'CONFIRMED', condition: null, startDate: '2026-10-01', endDate: '2026-10-02', startTime: null, endTime: null, sourceUrl: 'https://example.com/notice', sourcePage: 3, preserved: { count: 0, verified: false } }],
  }
}

beforeEach(() => {
  vi.mocked(getManagementDetail).mockReset().mockResolvedValue(detail())
  vi.mocked(requestManagementApi).mockReset().mockResolvedValue(null)
  json.loadJsonWorkspace.mockReset().mockResolvedValue({ events: [] })
})

it('일정 저장은 버전과 숨은 필드를 보존하고 입력 공백·빈값·숫자를 정규화한다', async () => {
  const onSaved = vi.fn()
  render(<ScheduleEditor value={detail()} id="7" onSaved={onSaved} />)
  fireEvent.click(screen.getByRole('button', { name: '직접 입력' }))
  fireEvent.change(screen.getByLabelText('공급 순위'), { target: { value: '  2순위  ' } })
  fireEvent.change(screen.getByLabelText('접수 조건'), { target: { value: '   ' } })
  fireEvent.change(screen.getByLabelText('공고문 페이지 (선택)'), { target: { value: '12' } })
  fireEvent.click(screen.getByRole('button', { name: '접수 일정 저장' }))
  await waitFor(() => expect(onSaved).toHaveBeenCalledWith(detail()))
  expect(requestManagementApi).toHaveBeenCalledWith('/api/admin/announcements/7/application-schedules?version=0', 'PUT', {
    schedules: [{ ...detail().schedules[0], supplyRank: '2순위', condition: null, sourcePage: 12 }],
  })
})

it('일정이 없으면 공고 날짜·원문 URL로 시작하고 행 추가·제거와 저장 실패 후 입력을 보존한다', async () => {
  vi.mocked(requestManagementApi).mockRejectedValue(new Error('수정 충돌'))
  render(<ScheduleEditor value={{ ...detail(), schedules: [] }} id="7" onSaved={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: '직접 입력' }))
  expect(screen.getByLabelText('시작일')).toHaveValue('2026-10-01')
  expect(screen.getByLabelText('근거 URL')).toHaveValue('https://example.com/notice')
  expect(screen.getByRole('button', { name: '일정 제거' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('공고문 페이지 (선택)'), { target: { value: '1' } })
  fireEvent.click(screen.getByRole('button', { name: '일정 추가' }))
  fireEvent.change(within(screen.getByRole('group', { name: '접수 일정 2' })).getByLabelText('공급 순위'), { target: { value: '2순위' } })
  fireEvent.click(within(screen.getByRole('group', { name: '접수 일정 1' })).getByRole('button', { name: '일정 제거' }))
  expect(screen.getByLabelText('공급 순위')).toHaveValue('2순위')
  fireEvent.click(screen.getByRole('button', { name: '접수 일정 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('수정 충돌')
  expect(screen.getByLabelText('공급 순위')).toHaveValue('2순위')
})

it('취소 후 다시 열면 수정·추가한 초안과 오류를 버리고 현재 저장된 일정을 편집한다', async () => {
  vi.mocked(requestManagementApi).mockRejectedValue(new Error('수정 충돌'))
  const { rerender } = render(<ScheduleEditor value={detail()} id="7" onSaved={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: '직접 입력' }))
  fireEvent.change(screen.getByLabelText('공급 순위'), { target: { value: '버릴 초안' } })
  fireEvent.click(screen.getByRole('button', { name: '일정 추가' }))
  fireEvent.click(screen.getByRole('button', { name: '접수 일정 저장' }))
  await screen.findByRole('alert')
  fireEvent.click(screen.getByRole('button', { name: '취소' }))
  const latest = { ...detail(), schedules: [{ ...detail().schedules[0], supplyRank: '최신 순위' }] }
  rerender(<ScheduleEditor value={latest} id="7" onSaved={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: '직접 입력' }))
  expect(screen.getAllByLabelText('공급 순위')).toHaveLength(1)
  expect(screen.getByLabelText('공급 순위')).toHaveValue('최신 순위')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(requestManagementApi).toHaveBeenCalledTimes(1)
})

it('외부 일정의 표시할 수 없는 필드는 실제 입력창과 동일하게 빈 값으로 저장한다', async () => {
  const schedule = {
    ...detail().schedules[0], condition: { unexpected: true }, supplyRank: ['잘못된 값'],
    startDate: 'invalid-date', startTime: 'invalid-time', sourcePage: 'invalid-number',
    housingComplexId: 999,
  }
  const onSaved = vi.fn()
  render(<ScheduleEditor value={{ ...detail(), schedules: [schedule] }} id="7" onSaved={onSaved} />)
  fireEvent.click(screen.getByRole('button', { name: '직접 입력' }))
  expect(screen.getByLabelText('접수 조건')).toHaveValue('')
  expect(screen.getByLabelText('공급 순위')).toHaveValue('')
  expect(screen.getByLabelText('시작일')).toHaveValue('')
  expect(screen.getByLabelText('시작 시각')).toHaveValue('')
  expect(screen.getByLabelText('공고문 페이지 (선택)')).toHaveValue(null)
  expect(screen.getByLabelText('적용 단지')).toHaveValue('')
  const form = screen.getByRole('button', { name: '접수 일정 저장' }).closest('form')
  if (!form) throw new Error('일정 폼이 없습니다.')
  fireEvent.submit(form)
  await waitFor(() => expect(onSaved).toHaveBeenCalled())
  expect(requestManagementApi).toHaveBeenCalledWith('/api/admin/announcements/7/application-schedules?version=0', 'PUT', {
    schedules: [{ ...schedule, condition: null, supplyRank: null, startDate: null, startTime: null, sourcePage: null, housingComplexId: null }],
  })
})

it('공고문 일정 문구를 줄마다 일정으로 채우고 근거 URL·페이지는 모든 일정에 함께 저장한다', async () => {
  const onSaved = vi.fn()
  render(<ScheduleEditor value={detail()} id="7" onSaved={onSaved} />)
  fireEvent.click(screen.getByRole('button', { name: '직접 입력' }))
  fireEvent.change(screen.getByLabelText('공고문 일정 붙여넣기'), { target: { value: '2026.10.27(화) 10:00 ~ 10.30(금) 17:00\n서류 제출 11.3 ~ 5일' } })
  fireEvent.click(screen.getByRole('button', { name: '날짜 채우기' }))
  expect(screen.getByRole('status')).toHaveTextContent('일정 2개의 날짜를 채웠습니다.')
  const second = within(screen.getByRole('group', { name: '접수 일정 2' }))
  expect(second.getByLabelText('시작일')).toHaveValue('2026-11-03')
  expect(second.getByLabelText('종료일')).toHaveValue('2026-11-05')
  fireEvent.change(screen.getByLabelText('공고문 페이지 (선택)'), { target: { value: '4' } })
  fireEvent.click(screen.getByRole('button', { name: '접수 일정 저장' }))
  await waitFor(() => expect(onSaved).toHaveBeenCalled())
  const saved = vi.mocked(requestManagementApi).mock.calls[0][2] as { schedules: Record<string, unknown>[] }
  expect(saved.schedules).toHaveLength(2)
  expect(saved.schedules[0]).toMatchObject({ startDate: '2026-10-27', startTime: '10:00', endDate: '2026-10-30', endTime: '17:00', sourcePage: 4, supplyRank: '1순위' })
  expect(saved.schedules[1]).toMatchObject({ startDate: '2026-11-03', endDate: '2026-11-05', sourcePage: 4, sourceUrl: 'https://example.com/notice', supplyRank: null })
})

it('수집·PDF 접수 일정을 공고문 URL을 근거로 한 번에 확정하고, 확정 뒤에는 초안으로 다시 채운다', async () => {
  const value = { ...detail(), schedules: [], supplyRows: [
    { id: 1, housingComplexId: 11, housingComplexName: '판암4단지', housingTypeId: null, modified: false, sourceComplexName: '대전판암4', sourceHousingTypeName: '26', data: {} },
    { id: 2, housingComplexId: 12, housingComplexName: '둔산1단지', housingTypeId: null, modified: false, sourceComplexName: '대전둔산1', sourceHousingTypeName: '26', data: {} },
  ] } as unknown as ManagementDetailData
  const event = (id: number, complexName: string, scheduleType: string, startAt: string, endAt: string) => ({ id, complexName, data: { scheduleType, name: '현장 접수', startAt, endAt } })
  json.loadJsonWorkspace.mockResolvedValue({ events: [
    event(1, '대전판암4 영구임대', 'APPLICATION', '2026-10-20T10:00:00', '2026-10-22T17:00:00'),
    event(2, '대전둔산1 영구임대', 'APPLICATION', '2026-10-21T00:00:00', '2026-10-23T00:00:00'),
    event(3, '대전판암4 영구임대', 'WINNER_ANNOUNCEMENT', '2027-02-26T14:00:00', '2027-02-26T14:00:00'),
  ] })
  const onSaved = vi.fn()
  vi.mocked(getManagementDetail).mockResolvedValue({ ...value, schedules: [detail().schedules[0]] })
  const { rerender } = render(<ScheduleEditor value={value} id="7" onSaved={onSaved} />)
  expect(await screen.findByRole('heading', { name: '수집·PDF 일정 3개' })).toBeVisible()
  expect(screen.getByText('아직 확정한 접수 일정이 없습니다.')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '접수 일정 2개 확정' }))
  await waitFor(() => expect(onSaved).toHaveBeenCalled())
  expect(requestManagementApi).toHaveBeenCalledWith('/api/admin/announcements/7/application-schedules?version=0', 'PUT', { schedules: [
    expect.objectContaining({ housingComplexId: 11, startDate: '2026-10-20', startTime: '10:00', endDate: '2026-10-22', endTime: '17:00', state: 'CONFIRMED', sourceUrl: 'https://example.com/notice', sourcePage: null }),
    expect.objectContaining({ housingComplexId: 12, startDate: '2026-10-21', startTime: null, endTime: null, sourcePage: null }),
  ] })
  rerender(<ScheduleEditor value={{ ...value, schedules: [detail().schedules[0]] }} id="7" onSaved={onSaved} />)
  fireEvent.click(await screen.findByRole('button', { name: '수집 일정으로 다시 채우기' }))
  expect(screen.getByRole('status')).toHaveTextContent('접수 일정 2개를 채웠습니다')
  expect(screen.getByLabelText('공고문 페이지 (선택)')).not.toBeRequired()
})
