import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { InitialRegistrationProgress as Progress } from './InitialRegistrationProgress'
import type { ComponentProps } from 'react'
import { getInitialRegistration, getRegistrationFailures, getRegistrationInformation, refreshInitialRegistration, registerReadyComplexes, type InitialRegistrationResponse } from './api'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(),
  getInitialRegistration: vi.fn(), refreshInitialRegistration: vi.fn(), registerReadyComplexes: vi.fn(),
  getRegistrationFailures: vi.fn(), getRegistrationInformation: vi.fn(),
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
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open'); this.dispatchEvent(new Event('close')) } })
  localStorage.removeItem('toadzip:admin:initial-registration:page-size')
  vi.mocked(getInitialRegistration).mockResolvedValue(response)
  vi.mocked(refreshInitialRegistration).mockResolvedValue()
})

it('실패한 한 곳을 선택해 재처리하고 완료 대상은 선택할 수 없다', async () => {
  const onRetry = vi.fn()
  vi.mocked(getInitialRegistration).mockResolvedValue({ ...response, targets: [
    { ...response.targets[0], status: 'FAILED', detail: '저장 실패' },
    { ...response.targets[0], sourceComplexIdentifier: '456:HAPPY_HOUSING', name: '완료 단지', status: 'COMPLETED', housingComplexId: 7 },
  ] })
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} onRetry={onRetry} />)
  const checkbox = await screen.findByRole('checkbox', { name: '남은 단지 재처리 선택' })
  expect(checkbox).toBeVisible()
  fireEvent.click(checkbox)
  expect(screen.getByLabelText('남은 단지 상세 정보').closest('details')).not.toHaveAttribute('open')
  fireEvent.click(screen.getByRole('button', { name: '선택한 1곳 다시 처리' }))
  expect(onRetry).toHaveBeenCalledExactlyOnceWith(['123:HAPPY_HOUSING'])
  expect(screen.queryByRole('checkbox', { name: '완료 단지 재처리 선택', hidden: true })).not.toBeInTheDocument()
})

it('현재 페이지 전체 선택과 해제 및 Shift 연속 선택을 지원한다', async () => {
  vi.mocked(getInitialRegistration).mockResolvedValue({ ...response, targets: [
    ...[1, 2, 3, 4].map(n => ({ ...response.targets[0], sourceComplexIdentifier: `${n}:HAPPY_HOUSING`, name: `단지 ${n}` })),
    { ...response.targets[0], sourceComplexIdentifier: '5:HAPPY_HOUSING', name: '완료 단지', status: 'COMPLETED', housingComplexId: 7 },
  ] })
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} onRetry={vi.fn()} />)
  const first = await screen.findByRole('checkbox', { name: '단지 1 재처리 선택' })
  const third = screen.getByRole('checkbox', { name: '단지 3 재처리 선택' })
  fireEvent.click(first)
  fireEvent.click(third, { shiftKey: true })
  expect(screen.getByRole('checkbox', { name: '단지 2 재처리 선택' })).toBeChecked()
  expect(screen.getByRole('button', { name: '선택한 3곳 다시 처리' })).toBeEnabled()
  fireEvent.click(third, { shiftKey: true })
  expect(first).not.toBeChecked()
  expect(screen.getByRole('checkbox', { name: '단지 2 재처리 선택' })).not.toBeChecked()
  expect(third).not.toBeChecked()
  fireEvent.click(screen.getByRole('button', { name: '이 페이지 전체 선택' }))
  expect(screen.getByRole('button', { name: '선택한 4곳 다시 처리' })).toBeEnabled()
  expect(screen.queryByRole('checkbox', { name: '완료 단지 재처리 선택' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '이 페이지 전체 선택 해제' }))
  expect(first).not.toBeChecked()
  expect(third).not.toBeChecked()
})

it('여러 페이지의 대상을 전체 선택하고 완료 대상을 제외한 채 페이지 이동과 재처리를 지원한다', async () => {
  const onRetry = vi.fn()
  const targets = Array.from({ length: 201 }, (_, index) => ({ ...response.targets[0],
    sourceComplexIdentifier: `${index + 1}:HAPPY_HOUSING`, name: `단지 ${index + 1}` }))
  const all = [...targets, { ...response.targets[0], sourceComplexIdentifier: '999:HAPPY_HOUSING',
    name: '완료 단지', status: 'COMPLETED' as const, housingComplexId: 7 }]
  vi.mocked(getInitialRegistration).mockImplementation(async (_status, page, size) => ({ ...response,
    targets: all.slice(page * size, (page + 1) * size), totalElements: all.length, page,
    totalPages: Math.ceil(all.length / size) }))
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} onRetry={onRetry} />)
  await screen.findByText('단지 1')
  expect(screen.queryByText(/Shift 클릭으로 현재 페이지/)).not.toBeInTheDocument()
  fireEvent.focus(screen.getByRole('button', { name: '대상 선택 안내' }))
  expect(screen.getByRole('tooltip')).toHaveTextContent('Shift 클릭')
  fireEvent.click(screen.getByRole('button', { name: '등록 대상 전체 선택' }))
  await screen.findByRole('button', { name: '선택한 201곳 다시 처리' })
  expect(screen.getByRole('checkbox', { name: '단지 1 재처리 선택' })).toBeChecked()
  fireEvent.click(screen.getByRole('button', { name: '다음 대상' }))
  expect(await screen.findByRole('checkbox', { name: '단지 21 재처리 선택' })).toBeChecked()
  fireEvent.click(screen.getByRole('button', { name: '선택한 201곳 다시 처리' }))
  expect(onRetry).toHaveBeenCalledExactlyOnceWith(targets.map(target => target.sourceComplexIdentifier))
})

it('전체 선택 중 페이지 조회가 실패하면 일부만 선택했다고 표시하지 않는다', async () => {
  vi.mocked(getInitialRegistration).mockImplementation(async (_status, page, size) => {
    if (size === 200 && page === 1) throw new Error('조회 실패')
    return { ...response, totalElements: 201, totalPages: 2 }
  })
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} onRetry={vi.fn()} />)
  await screen.findByText('남은 단지')
  fireEvent.click(screen.getByRole('button', { name: '등록 대상 전체 선택' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('조회 실패')
  expect(screen.queryByText(/곳을 선택했습니다/)).not.toBeInTheDocument()
})

it('대상 상태 확인 중에는 버튼에서 진행 상태를 보여준다', async () => {
  let finish: () => void = () => {}
  vi.mocked(refreshInitialRegistration).mockReturnValue(new Promise<void>(resolve => { finish = resolve }))
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} />)
  await screen.findByText('남은 단지')
  fireEvent.click(screen.getByRole('button', { name: '대상 상태 확인' }))
  expect(screen.getByRole('button', { name: '대상 상태 확인 중…' })).toBeDisabled()
  await act(async () => finish())
  expect(screen.getByRole('button', { name: '대상 상태 확인' })).toBeEnabled()
})

it('재처리 후 보완이 남으면 등록 성공이 아니라 확인이 필요하다고 안내한다', async () => {
  const onRetry = vi.fn()
  const props = { disabled: false, revision: '', onBusyChange: vi.fn(), onRetry }
  const view = render(<InitialRegistrationProgress {...props} />)
  fireEvent.click(await screen.findByLabelText('남은 단지 상세 정보'))
  fireEvent.click(screen.getByRole('button', { name: '이 단지 다시 처리' }))
  expect(screen.getByText(/재처리를 요청했습니다/)).toBeVisible()
  view.rerender(<InitialRegistrationProgress {...props} registration={{ execution: { ...idle, executionId: 'retry-1',
    status: 'COMPLETED_WARNINGS' }, requestError: null, errorResponse: null }} />)
  const dialog = await screen.findByRole('dialog', { name: '재처리 결과' })
  expect(dialog).toHaveTextContent('1곳의 재처리가 끝났지만 확인이 필요한 대상이 있습니다. 확인 사항과 이력을 확인하세요.')
  expect(screen.queryByText('처리가 끝났지만 확인이 필요한 대상이 있습니다.')).not.toBeInTheDocument()
  vi.spyOn(dialog, 'getBoundingClientRect').mockReturnValue({ left: 100, top: 100, right: 400, bottom: 300 } as DOMRect)
  fireEvent(dialog, new MouseEvent('pointerdown', { bubbles: true, clientX: 150, clientY: 150 }))
  fireEvent.click(dialog, { clientX: 150, clientY: 150 })
  expect(dialog).toBeVisible()
  fireEvent(dialog, new MouseEvent('pointerdown', { bubbles: true, clientX: 10, clientY: 10 }))
  fireEvent.click(dialog, { clientX: 10, clientY: 10 })
  expect(screen.queryByRole('dialog', { name: '재처리 결과' })).not.toBeInTheDocument()
})

it('보류 이력과 정보 보완은 구분해 하나씩 연다', async () => {
  vi.mocked(getRegistrationFailures).mockResolvedValue({ failures: [], totalElements: 0, totalPages: 0 })
  vi.mocked(getRegistrationInformation).mockResolvedValue({ fingerprint: 'a'.repeat(64), complexName: null,
    housingTypes: [], supplement: null })
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} />)
  fireEvent.click(await screen.findByLabelText('남은 단지 상세 정보'))
  fireEvent.click(screen.getByRole('button', { name: '보류·실패 이력' }))
  expect(await screen.findByText(/기록된 보류·실패 이력이 없습니다/)).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '등록 정보 보완' }))
  expect(await screen.findByLabelText('보완 근거')).toBeVisible()
  expect(screen.queryByRole('region', { name: '보류·실패 이력' })).not.toBeInTheDocument()
})

it('현황 숫자를 누르면 해당 상태의 첫 페이지로 이동한다', async () => {
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} />)
  await screen.findByText('남은 단지')
  fireEvent.click(screen.getByRole('button', { name: '좌표 준비 필요 10곳 보기' }))
  await waitFor(() => expect(getInitialRegistration).toHaveBeenLastCalledWith('LOCATION_REQUIRED', 0, 20))
  expect(screen.getByLabelText('진행 상태')).toHaveValue('LOCATION_REQUIRED')
  expect(screen.getByRole('button', { name: '좌표 준비 필요 10곳 보기' })).toHaveAttribute('aria-pressed', 'true')
})

it('보류 사유를 바로 보여주고 원천과 확인 시각은 단지 상세를 펼쳤을 때 보여준다', async () => {
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} />)
  expect(await screen.findByText('주소·좌표 확인 필요')).toBeVisible()
  expect(screen.getByText('123:HAPPY_HOUSING')).not.toBeVisible()
  fireEvent.click(screen.getByLabelText('남은 단지 상세 정보'))
  expect(screen.getByText('123:HAPPY_HOUSING')).toBeVisible()
})

it('등록 가능한 신규 단지가 있을 때 등록 실행을 제공한다', async () => {
  vi.mocked(getInitialRegistration).mockResolvedValue({ ...response, counts: { ...response.counts, READY: 2 } })
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} />)
  await screen.findByText('남은 단지')
  expect(screen.getByRole('button', { name: '준비된 신규 단지 등록' })).toBeEnabled()
})

it('새로고침으로 실행 중인 등록을 복원하고 자동으로 완료 수를 갱신한다', async () => {
  const running = { ...idle, executionId: 'run-1', status: 'RUNNING' as const }
  const view = render(<InitialRegistrationProgress disabled={true} revision="" onBusyChange={vi.fn()}
    registration={{ execution: running, requestError: null, errorResponse: null }} />)
  await screen.findByText('남은 단지')
  expect(screen.getByRole('button', { name: '준비된 신규 단지 등록' })).toBeDisabled()
  expect(screen.getByText('등록 준비 중…')).toBeVisible()
  vi.mocked(getInitialRegistration).mockResolvedValue({ ...response, counts: { ...response.counts, COMPLETED: 95 } })
  await waitFor(() => expect(screen.getByLabelText('등록 현황')).toHaveTextContent('95곳'), { timeout: 3500 })
  view.rerender(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()}
    registration={{ execution: { ...running, status: 'COMPLETED', finishedAt: '2026-11-09T01:00:00Z' }, requestError: null, errorResponse: null }} />)
  expect(screen.queryByLabelText('등록 실행 상태')).not.toBeInTheDocument()
})

it('실행 상태 조회에 실패해도 진행 중 상태를 유지하고 등록을 다시 보내지 않는다', async () => {
  render(<InitialRegistrationProgress disabled={true} revision="" onBusyChange={vi.fn()}
    registration={{ execution: { ...idle, status: 'RUNNING' }, requestError: '조회 실패', errorResponse: null }} />)
  expect(await screen.findByRole('alert')).toHaveTextContent('마지막 실행 상태를 유지')
  expect(screen.getByRole('button', { name: '준비된 신규 단지 등록' })).toBeDisabled()
  expect(registerReadyComplexes).not.toHaveBeenCalled()
})

it('실행 중에는 처리 건수와 중지 버튼을 함께 표시한다', async () => {
  const onStop = vi.fn()
  render(<InitialRegistrationProgress disabled={true} revision="" onBusyChange={vi.fn()}
    stopControl={<button onClick={onStop}>등록 중지</button>}
    registration={{ execution: { ...idle, status: 'RUNNING', workProgress: {
      label: '등록', unit: '곳', completedCount: 106, totalCount: 2239,
      startedAt: '2026-11-09T00:00:00Z', updatedAt: '2026-11-09T00:01:00Z',
    } }, requestError: null, errorResponse: null }} />)
  expect(await screen.findByText('등록 중 106 / 2,239곳')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '등록 중지' }))
  expect(onStop).toHaveBeenCalledOnce()
  expect(screen.queryByRole('button', { name: '실행 이력 보기' })).not.toBeInTheDocument()
})

it('완료 후 중복 실행 안내를 표시하지 않고 전체 현황을 유지한다', async () => {
  const onShowHistory = vi.fn()
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()}
    onShowHistory={onShowHistory}
    registration={{ execution: { ...idle, status: 'COMPLETED', completedStepResults: [{ step: 'REGISTER_MYHOME_COMPLEXES',
      stepName: '준비된 신규 단지 등록', report: { targetCount: 2, uniqueAddressCount: 1,
        counts: { ...response.counts, COMPLETED: 2 } } }] }, requestError: null, errorResponse: null }} />)
  await screen.findByLabelText('등록 현황')
  expect(screen.queryByRole('status', { name: '등록 실행 상태' })).not.toBeInTheDocument()
  expect(screen.queryByText(/고유 원천 주소/)).not.toBeInTheDocument()
  expect(screen.queryByRole('region', { name: '최근 등록 실행' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '실행 이력 보기' })).not.toBeInTheDocument()
  expect(onShowHistory).not.toHaveBeenCalled()
  expect(within(screen.getByLabelText('등록 현황')).getByText('90')).toBeVisible()
})

it('기존 단지와 완료 대상은 원천 비교 및 변경 이력 화면으로 연결한다', async () => {
  vi.mocked(getInitialRegistration).mockResolvedValue({ ...response,
    targets: [{ ...response.targets[0], status: 'COMPLETED', housingComplexId: 7 }] })
  render(<InitialRegistrationProgress disabled={false} revision="" onBusyChange={vi.fn()} />)
  fireEvent.click(await screen.findByLabelText('남은 단지 상세 정보'))
  expect(screen.getByRole('link', { name: '원천·적용값 비교' })).toHaveAttribute('href', '/admin/complexes/7?section=verification')
  expect(screen.getByRole('link', { name: '출처·변경 이력' })).toHaveAttribute('href', '/admin/complexes/7?section=history')
  expect(screen.getByRole('button', { name: '준비된 신규 단지 등록' })).toBeDisabled()
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

const idle = { executionId: null, type: 'COMPLEX_REGISTRATION' as const, status: 'IDLE' as const,
  currentStepName: null, currentStepIndex: 0, totalStepCount: 1, completedSteps: [], skippedSteps: [], partiallyFailedSteps: [], failure: null }
function InitialRegistrationProgress(props: Partial<ComponentProps<typeof Progress>> & Pick<ComponentProps<typeof Progress>, 'disabled' | 'revision' | 'onBusyChange'>) {
  return <Progress registration={{ execution: idle, requestError: null, errorResponse: null }} starting={false} onRegister={vi.fn()} onRefreshRegistration={vi.fn()} {...props} />
}
