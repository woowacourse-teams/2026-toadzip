import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { ScheduleEditor } from './ScheduleEditor'
import { getManagementDetail, requestManagementApi } from './api'
import type { ManagementDetailData } from './managementContract'

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
})

it('일정 저장은 버전과 숨은 필드를 보존하고 입력 공백·빈값·숫자를 정규화한다', async () => {
  const onSaved = vi.fn()
  render(<ScheduleEditor value={detail()} id="7" onSaved={onSaved} />)
  fireEvent.click(screen.getByRole('button', { name: '접수 일정 관리' }))
  fireEvent.change(screen.getByLabelText('공급 순위'), { target: { value: '  2순위  ' } })
  fireEvent.change(screen.getByLabelText('접수 조건'), { target: { value: '   ' } })
  fireEvent.change(screen.getByLabelText('공고문 페이지'), { target: { value: '12' } })
  fireEvent.click(screen.getByRole('button', { name: '접수 일정 저장' }))
  await waitFor(() => expect(onSaved).toHaveBeenCalledWith(detail()))
  expect(requestManagementApi).toHaveBeenCalledWith('/api/admin/announcements/7/application-schedules?version=0', 'PUT', {
    schedules: [{ ...detail().schedules[0], supplyRank: '2순위', condition: null, sourcePage: 12 }],
  })
})

it('일정이 없으면 공고 날짜·원문 URL로 시작하고 행 추가·제거와 저장 실패 후 입력을 보존한다', async () => {
  vi.mocked(requestManagementApi).mockRejectedValue(new Error('수정 충돌'))
  render(<ScheduleEditor value={{ ...detail(), schedules: [] }} id="7" onSaved={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: '접수 일정 관리' }))
  expect(screen.getByLabelText('시작일')).toHaveValue('2026-10-01')
  expect(screen.getByLabelText('근거 URL')).toHaveValue('https://example.com/notice')
  expect(screen.getByRole('button', { name: '일정 제거' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('공고문 페이지'), { target: { value: '1' } })
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
  fireEvent.click(screen.getByRole('button', { name: '접수 일정 관리' }))
  fireEvent.change(screen.getByLabelText('공급 순위'), { target: { value: '버릴 초안' } })
  fireEvent.click(screen.getByRole('button', { name: '일정 추가' }))
  fireEvent.click(screen.getByRole('button', { name: '접수 일정 저장' }))
  await screen.findByRole('alert')
  fireEvent.click(screen.getByRole('button', { name: '취소' }))
  const latest = { ...detail(), schedules: [{ ...detail().schedules[0], supplyRank: '최신 순위' }] }
  rerender(<ScheduleEditor value={latest} id="7" onSaved={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: '접수 일정 관리' }))
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
  fireEvent.click(screen.getByRole('button', { name: '접수 일정 관리' }))
  expect(screen.getByLabelText('접수 조건')).toHaveValue('')
  expect(screen.getByLabelText('공급 순위')).toHaveValue('')
  expect(screen.getByLabelText('시작일')).toHaveValue('')
  expect(screen.getByLabelText('시작 시각')).toHaveValue('')
  expect(screen.getByLabelText('공고문 페이지')).toHaveValue(null)
  expect(screen.getByLabelText('적용 단지')).toHaveValue('')
  const form = screen.getByRole('button', { name: '접수 일정 저장' }).closest('form')
  if (!form) throw new Error('일정 폼이 없습니다.')
  fireEvent.submit(form)
  await waitFor(() => expect(onSaved).toHaveBeenCalled())
  expect(requestManagementApi).toHaveBeenCalledWith('/api/admin/announcements/7/application-schedules?version=0', 'PUT', {
    schedules: [{ ...schedule, condition: null, supplyRank: null, startDate: null, startTime: null, sourcePage: null, housingComplexId: null }],
  })
})
