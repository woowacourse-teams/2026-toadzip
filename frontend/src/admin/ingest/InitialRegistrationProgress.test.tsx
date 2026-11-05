import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { InitialRegistrationProgress } from './InitialRegistrationProgress'
import { getInitialRegistration, refreshInitialRegistration, type InitialRegistrationResponse } from './api'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(),
  getInitialRegistration: vi.fn(), refreshInitialRegistration: vi.fn(),
}))
const response: InitialRegistrationResponse = {
  counts: { EXISTING: 0, LOCATION_REQUIRED: 10, INFORMATION_REQUIRED: 0, READY: 0, FAILED: 0, COMPLETED: 90 },
  targets: [{ sourceComplexIdentifier: '123:HAPPY_HOUSING', name: '남은 단지', roadAddress: null,
    status: 'LOCATION_REQUIRED', detail: '주소·좌표 확인 필요', housingComplexId: null, checkedAt: '2026-11-04T00:00:00Z' }],
  totalElements: 100, page: 0, totalPages: 2,
}
beforeEach(() => {
  vi.restoreAllMocks()
  vi.resetAllMocks()
  localStorage.removeItem('toadzip:admin:initial-registration:page-size')
  vi.mocked(getInitialRegistration).mockResolvedValue(response)
  vi.mocked(refreshInitialRegistration).mockResolvedValue()
})

it('90곳 완료와 남은 10곳의 필요한 작업을 구분해 표시한다', async () => {
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} />)
  await screen.findByText('남은 단지')
  const summary = screen.getByLabelText('등록 현황')
  expect(within(summary).getByText('90')).toHaveTextContent('90곳')
  expect(within(summary).getByText('10')).toHaveTextContent('10곳')
  expect(screen.getByText('주소·좌표 확인 필요')).toBeVisible()
  fireEvent.change(screen.getByLabelText('진행 상태'), { target: { value: 'LOCATION_REQUIRED' } })
  await waitFor(() => expect(getInitialRegistration).toHaveBeenLastCalledWith('LOCATION_REQUIRED', 0, 20))
})

it('다음 페이지를 조회하고 화면을 다시 열면 서버에 저장된 상태를 읽는다', async () => {
  const view = render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} />)
  await screen.findByText('남은 단지')
  fireEvent.click(screen.getByRole('button', { name: '다음 대상' }))
  await waitFor(() => expect(getInitialRegistration).toHaveBeenLastCalledWith('', 1, 20))
  view.unmount()
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} />)
  await screen.findByText('남은 단지')
  expect(getInitialRegistration).toHaveBeenLastCalledWith('', 0, 20)
  expect(refreshInitialRegistration).not.toHaveBeenCalled()
})

it('상태 확인 중 중복 실행을 막고 완료 후 목록을 다시 조회한다', async () => {
  let finish: () => void = () => {}
  vi.mocked(refreshInitialRegistration).mockReturnValue(new Promise(resolve => { finish = resolve }))
  const onBusy = vi.fn()
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={onBusy} />)
  await screen.findByText('남은 단지')
  const button = screen.getByRole('button', { name: '대상 상태 확인' })
  fireEvent.click(button); fireEvent.click(button)
  expect(button).toBeDisabled()
  expect(refreshInitialRegistration).toHaveBeenCalledTimes(1)
  expect(onBusy).toHaveBeenCalledWith(true)
  await act(async () => finish())
  await waitFor(() => expect(getInitialRegistration).toHaveBeenCalledTimes(2))
  expect(onBusy).toHaveBeenLastCalledWith(false)
})

it('조회 실패를 빈 결과로 표시하지 않고 다시 조회할 수 있다', async () => {
  vi.mocked(getInitialRegistration).mockRejectedValueOnce(new Error('조회 실패'))
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} />)
  expect(await screen.findByRole('alert')).toHaveTextContent('조회 실패')
  expect(screen.queryByText('등록 대상이 없습니다.')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '상태 다시 조회' }))
  await screen.findByText('남은 단지')
})

it('첫 방문은 20건으로 조회하고 10·20·50·100건을 선택할 수 있다', async () => {
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} />)
  await screen.findByText('남은 단지')
  const size = screen.getByRole('combobox', { name: '표시 건수' })
  expect(size).toHaveValue('20')
  expect(within(size).getAllByRole('option').map(option => option.getAttribute('value')))
    .toEqual(['10', '20', '50', '100'])
  expect(getInitialRegistration).toHaveBeenLastCalledWith('', 0, 20)
})

it('100건 선택을 다시 방문해도 기억하고 상태 필터를 바꿔도 유지한다', async () => {
  const view = render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} />)
  await screen.findByText('남은 단지')
  fireEvent.change(screen.getByRole('combobox', { name: '표시 건수' }), { target: { value: '100' } })
  await waitFor(() => expect(getInitialRegistration).toHaveBeenLastCalledWith('', 0, 100))
  view.unmount()
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} />)
  await screen.findByText('남은 단지')
  expect(screen.getByRole('combobox', { name: '표시 건수' })).toHaveValue('100')
  expect(getInitialRegistration).toHaveBeenLastCalledWith('', 0, 100)
  fireEvent.change(screen.getByLabelText('진행 상태'), { target: { value: 'LOCATION_REQUIRED' } })
  await waitFor(() => expect(getInitialRegistration).toHaveBeenLastCalledWith('LOCATION_REQUIRED', 0, 100))
})

it('표시 건수를 바꾸면 상태 필터를 유지하고 첫 페이지부터 조회한다', async () => {
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} />)
  await screen.findByText('남은 단지')
  fireEvent.change(screen.getByLabelText('진행 상태'), { target: { value: 'FAILED' } })
  await waitFor(() => expect(getInitialRegistration).toHaveBeenLastCalledWith('FAILED', 0, 20))
  await screen.findByText('남은 단지')
  fireEvent.click(screen.getByRole('button', { name: '다음 대상' }))
  await waitFor(() => expect(getInitialRegistration).toHaveBeenLastCalledWith('FAILED', 1, 20))
  fireEvent.change(screen.getByRole('combobox', { name: '표시 건수' }), { target: { value: '50' } })
  await waitFor(() => expect(getInitialRegistration).toHaveBeenLastCalledWith('FAILED', 0, 50))
})

it.each(['30', '100.0', '잘못된 값'])('저장된 표시 건수 %s는 기본 20건으로 복구한다', async stored => {
  localStorage.setItem('toadzip:admin:initial-registration:page-size', stored)
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} />)
  await screen.findByText('남은 단지')
  expect(screen.getByRole('combobox', { name: '표시 건수' })).toHaveValue('20')
  expect(getInitialRegistration).toHaveBeenLastCalledWith('', 0, 20)
})

it('브라우저 저장소가 차단되어도 기본 조회와 건수 변경을 사용할 수 있다', async () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('저장소 차단') })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('저장소 차단') })
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} />)
  await screen.findByText('남은 단지')
  expect(getInitialRegistration).toHaveBeenLastCalledWith('', 0, 20)
  fireEvent.change(screen.getByRole('combobox', { name: '표시 건수' }), { target: { value: '100' } })
  await waitFor(() => expect(getInitialRegistration).toHaveBeenLastCalledWith('', 0, 100))
  expect(screen.getByRole('combobox', { name: '표시 건수' })).toHaveValue('100')
})
