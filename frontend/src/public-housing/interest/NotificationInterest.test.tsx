import { captureProductEvent, setProductAuthState, setReplaySensitive } from '../../analytics/productAnalytics'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { NotificationInterestButton, NotificationInterestProvider } from './NotificationInterest'
import type { NotificationInterestEvent, NotificationInterestRepository, NotificationInterestResult, NotificationOutcome } from './notificationInterestRepository'

vi.mock('../../analytics/productAnalytics', () => ({ captureProductEvent: vi.fn(), setProductAuthState: vi.fn(), setReplaySensitive: vi.fn() }))

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  sessionStorage.clear()
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function example(repository: NotificationInterestRepository, loadUser = vi.fn().mockResolvedValue(null)) {
  return (
    <NotificationInterestProvider repository={repository} loadUser={loadUser}>
      <NotificationInterestButton target={{ type: 'COMPLEX', id: '1', name: '서울 단지' }} source="COMPLEX_DETAIL" />
      <NotificationInterestButton target={{ type: 'REGION', id: '11', name: '서울특별시' }} source="REGION_SEARCH" />
    </NotificationInterestProvider>
  )
}

describe('이메일 알림 신청', () => {
  it.each(['ACTIVATED', 'ALREADY_ACTIVE', undefined] as const)(
    '비즈니스 결과 %s를 기준으로만 사전신청 완료를 수집한다', async (outcome) => {
      const record = vi.fn<NotificationInterestRepository['record']>().mockImplementation(async (event) => outcome
        ? { eventId: event.eventId, targetType: event.targetType, targetId: event.targetId,
          outcome, occurredAt: '2026-10-08T00:00:00Z' } : undefined)
      render(example({ record, loadStatus: vi.fn().mockResolvedValue({ guest: true, emailConfirmed: true, targets: [] }) }))
      await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled())
      fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
      await screen.findByText('서울 단지 알림 신청을 받았어요.')
      const completions = vi.mocked(captureProductEvent).mock.calls.filter(([name]) => name === 'notification_preregistration_completed')
      expect(completions).toHaveLength(outcome === 'ACTIVATED' ? 1 : 0)
      if (outcome === 'ACTIVATED') {
        expect(completions[0]?.[1]).toMatchObject({ target_type: 'COMPLEX', target_id: '1', completion_source: 'CLICKED' })
        expect(completions[0]?.[2]?.dedupeKey).toBe(`notification-completed:${record.mock.calls[0]?.[0].eventId}`)
      }
    },
  )

  it('다른 탭에서 이메일이 삭제된 NOT_ACTIVATED 응답은 성공 대신 이메일 폼으로 돌아간다', async () => {
    const record = vi.fn<NotificationInterestRepository['record']>().mockImplementation(async (event) =>
      resultFor(event, event.eventType === 'CONFIRMED' ? 'ACTIVATED' : 'NOT_ACTIVATED'))
    render(example({ record, loadStatus: vi.fn().mockResolvedValue({ guest: true, emailConfirmed: true, targets: [] }) }))
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    const email = await screen.findByRole('textbox', { name: '알림 받을 이메일' })
    expect(screen.queryByText('서울 단지 알림 신청을 받았어요.')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toHaveAttribute('aria-pressed', 'false')
    expect(localStorage.getItem('toadzip.notification-interest.email-confirmed')).toBe('0')
    expect(completions()).toHaveLength(0)
    fireEvent.change(email, { target: { value: 'guest@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: '알림 신청' }))
    await screen.findByText('서울 단지 알림 신청을 받았어요.')
    expect(completions()).toHaveLength(1)
    expect(record.mock.calls[0]?.[0].eventId).not.toBe(record.mock.calls[1]?.[0].eventId)
  })

  it('UNKNOWN은 신청 성공을 만들지 않고 서버 상태를 다시 확인한다', async () => {
    const record = vi.fn<NotificationInterestRepository['record']>().mockImplementation(async (event) => resultFor(event, 'UNKNOWN'))
    const loadStatus = vi.fn().mockResolvedValue({ guest: true, emailConfirmed: true, targets: [] })
    render(example({ record, loadStatus }))
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    await screen.findByText('처리 결과를 확인하지 못했어요. 현재 신청 상태를 다시 확인해 주세요.')
    await waitFor(() => expect(loadStatus).toHaveBeenCalledTimes(2))
    expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.queryByText('서울 단지 알림 신청을 받았어요.')).not.toBeInTheDocument()
    expect(completions()).toHaveLength(0)
  })

  it.each(['ACTIVATED', 'CANCELLED'] as const)('UI가 해제되어도 서버의 %s 결과는 원래 대상과 회원 상태로 수집한다', async (outcome) => {
    const response = deferred<NotificationInterestResult>()
    const record = vi.fn<NotificationInterestRepository['record']>().mockReturnValue(response.promise)
    const repository = { record, loadStatus: vi.fn().mockResolvedValue({ emailConfirmed: true,
      targets: outcome === 'CANCELLED' ? [{ targetType: 'COMPLEX', targetId: '1' }] : [] }) }
    const view = render(example(repository))
    const name = outcome === 'CANCELLED' ? '서울 단지 알림 취소' : '서울 단지 알림 받기'
    await waitFor(() => expect(screen.getByRole('button', { name })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name }))
    const event = record.mock.calls[0]?.[0]
    if (!event) throw new Error('The request should have started')
    view.unmount()
    await act(async () => response.resolve(resultFor(event, outcome)))
    const nameToCapture = outcome === 'CANCELLED' ? 'notification_cancel_completed' : 'notification_preregistration_completed'
    expect(captureProductEvent).toHaveBeenCalledWith(nameToCapture,
      expect.objectContaining({ target_type: 'COMPLEX', target_id: '1', server_event_id: event.eventId }),
      expect.objectContaining({ authState: 'member', pageName: 'explorer' }))
    expect(screen.queryByText('서울 단지 알림 신청을 받았어요.')).not.toBeInTheDocument()
  })

  it('회원 요청 뒤 세션이 비회원으로 초기화되어도 늦은 성공은 회원 요청으로 남기고 새 UI를 덮지 않는다', async () => {
    const response = deferred<NotificationInterestResult>()
    const record = vi.fn<NotificationInterestRepository['record']>().mockReturnValue(response.promise)
    const view = render(example({ record, loadStatus: vi.fn().mockResolvedValue({ emailConfirmed: true, targets: [] }) }))
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    const event = record.mock.calls[0]?.[0]
    if (!event) throw new Error('The request should have started')
    const guestRepository = { record: vi.fn().mockResolvedValue(undefined),
      loadStatus: vi.fn().mockResolvedValue({ guest: true, emailConfirmed: false, targets: [] }) }
    view.rerender(example(guestRepository))
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled())
    expect(setProductAuthState).toHaveBeenLastCalledWith('guest')
    await act(async () => response.resolve(resultFor(event, 'ACTIVATED')))
    expect(completions()).toHaveLength(1)
    expect(completions()[0]?.[2]).toMatchObject({ authState: 'member', pageName: 'explorer' })
    expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.queryByText('서울 단지 알림 신청을 받았어요.')).not.toBeInTheDocument()
  })

  it('응답 유실 재시도는 같은 명령 ID를 유지하며 취소 후 새 신청은 새로운 ID를 사용한다', async () => {
    let first = true
    const record = vi.fn<NotificationInterestRepository['record']>().mockImplementation(async (event) => {
      if (first) { first = false; throw new Error('response lost') }
      return resultFor(event, event.eventType === 'CANCELLED' ? 'CANCELLED' : 'ACTIVATED')
    })
    render(example({ record, loadStatus: vi.fn().mockResolvedValue({ emailConfirmed: true,
      targets: [{ targetType: 'REGION', targetId: '11' }] }) }))
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }))
    await screen.findByText('서울 단지 알림 신청을 받았어요.')
    expect(record.mock.calls[0]?.[0]).toEqual(record.mock.calls[1]?.[0])
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 취소' }))
    await screen.findByText('서울 단지 알림 신청을 취소했어요.')
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    await screen.findByText('서울 단지 알림 신청을 받았어요.')
    expect(record.mock.calls[1]?.[0].eventId).not.toBe(record.mock.calls[2]?.[0].eventId)
    expect(record.mock.calls[2]?.[0].eventId).not.toBe(record.mock.calls[3]?.[0].eventId)
    expect(completions()).toHaveLength(2)
  })

  it('창으로 돌아온 뒤 확인된 알림 상태로 분석의 회원 상태도 갱신한다', async () => {
    const loadStatus = vi.fn().mockResolvedValueOnce({ emailConfirmed: true, targets: [] })
      .mockResolvedValueOnce({ guest: true, emailConfirmed: false, targets: [] })
    render(example({ record: vi.fn().mockResolvedValue(undefined), loadStatus }))
    await waitFor(() => expect(setProductAuthState).toHaveBeenLastCalledWith('member'))
    fireEvent.focus(window)
    await waitFor(() => expect(setProductAuthState).toHaveBeenLastCalledWith('guest'))
  })

  it('이메일 폼을 열기 전에 리플레이를 중단하고 분석 속성에 이메일을 넣지 않는다', async () => {
    const record = vi.fn<NotificationInterestRepository['record']>().mockImplementation(async (event) => ({
      eventId: event.eventId, targetType: event.targetType, targetId: event.targetId,
      outcome: event.eventType === 'CONFIRMED' ? 'ACTIVATED' : 'NOT_ACTIVATED', occurredAt: '2026-10-08T00:00:00Z',
    }))
    render(example({ record }))
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    const email = await screen.findByRole('textbox', { name: '알림 받을 이메일' })
    expect(setReplaySensitive).toHaveBeenCalledWith('notification_form', true)
    fireEvent.change(email, { target: { value: 'analytics-secret@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: '알림 신청' }))
    await screen.findByText('서울 단지 알림 신청을 받았어요.')
    expect(JSON.stringify(vi.mocked(captureProductEvent).mock.calls)).not.toContain('analytics-secret')
    expect(setReplaySensitive).toHaveBeenLastCalledWith('notification_form', false)
  })

  it('상세 내부에서 실패한 알림 요청도 native modal로 재시도와 닫기를 제공한다', async () => {
    const record = vi.fn<NotificationInterestRepository['record']>().mockRejectedValue(new Error('network'))
    render(<NotificationInterestProvider repository={{ record }}>
      <dialog open aria-label="단지 상세">
        <NotificationInterestButton target={{ type: 'COMPLEX', id: '1', name: '서울 단지' }} source="COMPLEX_DETAIL" />
      </dialog>
    </NotificationInterestProvider>)
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    const errorDialog = await screen.findByRole('dialog', { name: '알림 요청 오류' })
    expect(errorDialog).toBeInstanceOf(HTMLDialogElement)
    expect(errorDialog).toHaveAttribute('open')
    expect(within(errorDialog).getByRole('alert')).toHaveTextContent('알림 신청을 완료하지 못했어요.')
    fireEvent.click(within(errorDialog).getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toHaveFocus()
  })

  it('상세 모달 위에서도 조작할 수 있도록 native modal로 열고 취소한다', async () => {
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    render(example({ record }))
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toBeInstanceOf(HTMLDialogElement)
    expect(dialog).toHaveAttribute('open')
    fireEvent(dialog, new Event('cancel', { cancelable: true }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toHaveFocus()
  })

  it.each(['resolve', 'reject'] as const)('저장소 교체 전 요청이 %s되어도 새 신청 화면을 막거나 덮지 않는다', async (settlement) => {
    const previousWrite = deferred<void>()
    const previousRepository = {
      record: vi.fn().mockReturnValue(previousWrite.promise),
      loadStatus: vi.fn().mockResolvedValue({ emailConfirmed: true, targets: [] }),
    }
    const nextRepository = {
      record: vi.fn().mockResolvedValue(undefined),
      loadStatus: vi.fn().mockResolvedValue({ guest: true, emailConfirmed: false, targets: [] }),
    }
    const view = render(example(previousRepository))
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeDisabled()

    view.rerender(example(nextRepository))
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled())
    await act(async () => {
      if (settlement === 'resolve') previousWrite.resolve()
      else previousWrite.reject(new Error('이전 저장소 실패'))
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText('서울 단지 알림 신청을 받았어요.')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    expect(await screen.findByRole('dialog')).toBeVisible()
    expect(nextRepository.record).toHaveBeenCalledOnce()
  })

  it.each([false, true])('늦은 focus 상태조회가 신청 여부 %s의 변경 성공을 되돌리지 않는다', async (initiallyRequested) => {
    const snapshot = {
      emailConfirmed: true,
      targets: initiallyRequested ? [{ targetType: 'COMPLEX' as const, targetId: '1' }] : [],
    }
    const pending = deferred<typeof snapshot>()
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    const loadStatus = vi.fn().mockResolvedValueOnce(snapshot).mockReturnValueOnce(pending.promise)
    render(example({ record, loadStatus }))
    const beforeName = initiallyRequested ? '서울 단지 알림 취소' : '서울 단지 알림 받기'
    await waitFor(() => expect(screen.getByRole('button', { name: beforeName })).toBeEnabled())
    fireEvent.focus(window)
    fireEvent.click(screen.getByRole('button', { name: beforeName }))
    const afterName = initiallyRequested ? '서울 단지 알림 받기' : '서울 단지 알림 취소'
    await screen.findByRole('button', { name: afterName })

    await act(async () => pending.resolve(snapshot))

    expect(screen.getByRole('button', { name: afterName })).toHaveAttribute('aria-pressed', String(!initiallyRequested))
    expect(record).toHaveBeenCalledOnce()
  })

  it('늦은 조회가 완료되어도 실패한 취소를 같은 이벤트로 재시도한다', async () => {
    const snapshot = { emailConfirmed: true, targets: [{ targetType: 'COMPLEX' as const, targetId: '1' }] }
    const pending = deferred<typeof snapshot>()
    const record = vi.fn<NotificationInterestRepository['record']>()
      .mockRejectedValueOnce(new Error('취소 실패')).mockResolvedValue(undefined)
    const loadStatus = vi.fn().mockResolvedValueOnce(snapshot).mockReturnValueOnce(pending.promise)
    render(example({ record, loadStatus }))
    fireEvent.click(await screen.findByRole('button', { name: '서울 단지 알림 취소' }))
    await screen.findByRole('alert')
    fireEvent.focus(window)
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    await screen.findByRole('button', { name: '서울 단지 알림 받기' })

    await act(async () => pending.resolve(snapshot))

    expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toHaveAttribute('aria-pressed', 'false')
    expect(record.mock.calls[0]?.[0]).toEqual(record.mock.calls[1]?.[0])
  })

  it('모달의 도움 링크를 포함해 Tab과 Shift+Tab으로 순환하고 닫으면 시작 버튼으로 돌아온다', async () => {
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    render(example({ record }))
    const trigger = screen.getByRole('button', { name: '서울 단지 알림 받기' })
    fireEvent.click(trigger)
    const dialog = await screen.findByRole('dialog')
    const email = screen.getByRole('textbox', { name: '알림 받을 이메일' })
    const help = screen.getByRole('link', { name: '브라우저 데이터를 지워 신청을 취소할 수 없나요?' })
    expect(email).toHaveFocus()
    fireEvent.keyDown(email, { key: 'Tab', shiftKey: true })
    expect(help).toHaveFocus()
    fireEvent.keyDown(help, { key: 'Tab' })
    expect(email).toHaveFocus()
    const cancel = screen.getByRole('button', { name: '취소' })
    cancel.focus()
    expect(fireEvent.keyDown(cancel, { key: 'Tab' })).toBe(true)
    fireEvent.keyDown(dialog, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(trigger).toHaveFocus()
  })

  it('처리 중인 모달에서는 활성 도움 링크 안에 포커스를 유지한다', async () => {
    const pending = deferred<void>()
    const record = vi.fn<NotificationInterestRepository['record']>()
      .mockResolvedValueOnce(undefined).mockReturnValueOnce(pending.promise)
    render(example({ record }))
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    const email = await screen.findByRole('textbox', { name: '알림 받을 이메일' })
    fireEvent.change(email, { target: { value: 'guest@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: '알림 신청' }))
    const help = screen.getByRole('link', { name: '브라우저 데이터를 지워 신청을 취소할 수 없나요?' })
    help.focus()
    fireEvent.keyDown(help, { key: 'Tab' })
    expect(help).toHaveFocus()
    fireEvent.keyDown(help, { key: 'Tab', shiftKey: true })
    expect(help).toHaveFocus()
    fireEvent.keyDown(help, { key: 'Escape' })
    expect(screen.getByRole('dialog')).toBeVisible()
    await act(async () => pending.resolve())
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('로그인 사용자는 서버의 신청 상태를 우선 표시하고 새 창에서 다시 읽는다', async () => {
    localStorage.setItem('toadzip.notification-interest.requested:REGION:11', '1')
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    const loadStatus = vi.fn().mockResolvedValueOnce({ emailConfirmed: true, targets: [
      { targetType: 'COMPLEX', targetId: '1' },
    ] }).mockResolvedValue({ emailConfirmed: true, targets: [] })
    const first = render(example({ record, loadStatus }))
    expect(await screen.findByRole('button', { name: '서울 단지 알림 취소' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: '서울특별시 알림 받기' })).toHaveAttribute('aria-pressed', 'false')
    first.unmount()

    render(example({ record, loadStatus }))
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '서울특별시 알림 받기' }))
    await waitFor(() => expect(record).toHaveBeenCalledWith(expect.objectContaining({
      eventType: 'CLICKED', targetType: 'REGION', targetId: '11',
    })))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('다른 기기에서 바뀐 신청 상태를 창으로 돌아올 때 반영한다', async () => {
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    const loadStatus = vi.fn().mockResolvedValueOnce({ emailConfirmed: true, targets: [] })
      .mockResolvedValueOnce({ emailConfirmed: true, targets: [{ targetType: 'COMPLEX', targetId: '1' }] })
    render(example({ record, loadStatus }))
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled())
    fireEvent.focus(window)
    expect(await screen.findByRole('button', { name: '서울 단지 알림 취소' })).toHaveAttribute('aria-pressed', 'true')
    expect(record).not.toHaveBeenCalled()
  })

  it('비로그인 상태도 서버의 신청 목록으로 복원한다', async () => {
    localStorage.setItem('toadzip.notification-interest.requested:REGION:11', '1')
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    const loadStatus = vi.fn().mockResolvedValue({ guest: true, emailConfirmed: true,
      targets: [{ targetType: 'COMPLEX', targetId: '1' }] })
    render(example({ record, loadStatus }))
    expect(await screen.findByRole('button', { name: '서울 단지 알림 취소' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: '서울특별시 알림 받기' })).toHaveAttribute('aria-pressed', 'false')
    expect(loadStatus).toHaveBeenCalledWith(expect.stringMatching(/^[0-9a-f-]{36}$/))
    fireEvent.click(screen.getByRole('button', { name: '서울특별시 알림 받기' }))
    await waitFor(() => expect(record).toHaveBeenCalledWith(expect.objectContaining({
      eventType: 'CLICKED', clientId: expect.stringMatching(/^[0-9a-f-]{36}$/),
    })))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('비로그인 사용자는 첫 클릭에서 이메일을 입력하고 이후 다른 대상은 클릭만 기록한다', async () => {
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    render(example({ record }))
    const complexButton = screen.getByRole('button', { name: '서울 단지 알림 받기' })
    expect(complexButton.querySelector('svg')).toHaveAttribute('data-state', 'idle')
    fireEvent.click(complexButton)
    const dialog = await screen.findByRole('dialog', { name: '이메일 알림 신청' })
    const email = screen.getByRole('textbox', { name: '알림 받을 이메일' })
    await waitFor(() => expect(email).toHaveFocus())
    expect(email).toHaveValue('')
    expect(dialog).not.toHaveTextContent('준비 중')
    fireEvent.click(screen.getByRole('button', { name: '알림 신청' }))
    expect(record.mock.calls.map(([event]) => event.eventType)).toEqual(['CLICKED'])
    fireEvent.change(email, { target: { value: 'invalid' } })
    fireEvent.click(screen.getByRole('button', { name: '알림 신청' }))
    expect(record.mock.calls.map(([event]) => event.eventType)).toEqual(['CLICKED'])
    fireEvent.change(email, { target: { value: 'guest@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: '알림 신청' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(record.mock.calls[1]?.[0]).toEqual(expect.objectContaining({ eventType: 'CONFIRMED', email: 'guest@example.com' }))
    expect(screen.getByRole('status')).toHaveTextContent('서울 단지 알림 신청을 받았어요.')
    expect(complexButton).toHaveAccessibleName('서울 단지 알림 취소')
    expect(complexButton.querySelector('svg')).toHaveAttribute('data-state', 'requested')
    fireEvent.click(screen.getByRole('button', { name: '서울특별시 알림 받기' }))
    await waitFor(() => expect(record).toHaveBeenCalledTimes(3))
    expect(record.mock.calls[2]?.[0]).toEqual(expect.objectContaining({ eventType: 'CLICKED', targetType: 'REGION', targetId: '11' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('서울특별시 알림 신청을 받았어요.')
    expect(screen.getByRole('button', { name: '서울특별시 알림 취소' }).querySelector('svg'))
      .toHaveAttribute('data-state', 'requested')
    expect(localStorage.getItem('toadzip.notification-interest.email-confirmed')).toBe('1')
  })

  it('로그인 사용자 이메일을 채우고 수정한 주소로 신청한다', async () => {
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    const loadUser = vi.fn().mockResolvedValue({ id: 7, email: 'member@example.com' })
    render(example({ record }, loadUser))
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    const email = await screen.findByRole('textbox', { name: '알림 받을 이메일' })
    await waitFor(() => expect(email).toHaveValue('member@example.com'))
    fireEvent.change(email, { target: { value: 'other@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: '알림 신청' }))
    await waitFor(() => expect(record).toHaveBeenCalledWith(expect.objectContaining({
      eventType: 'CONFIRMED', email: 'other@example.com',
    })))
  })

  it('마지막 신청을 취소하면 새로고침 후 이메일을 다시 입력한다', async () => {
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    const loadUser = vi.fn().mockResolvedValue({ id: 7, email: 'member@example.com' })
    const first = render(example({ record }, loadUser))
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    const email = await screen.findByRole('textbox', { name: '알림 받을 이메일' })
    await waitFor(() => expect(email).toHaveValue('member@example.com'))
    fireEvent.click(screen.getByRole('button', { name: '알림 신청' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    first.unmount()

    const second = render(example({ record }, loadUser))
    expect(screen.getByRole('button', { name: '서울 단지 알림 취소' }).querySelector('svg'))
      .toHaveAttribute('data-state', 'requested')
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 취소' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toHaveAttribute('aria-pressed', 'false'))
    expect(record.mock.calls[2]?.[0]).toEqual(expect.objectContaining({ eventType: 'CANCELLED', targetType: 'COMPLEX', targetId: '1' }))
    expect(localStorage.getItem('toadzip.notification-interest.requested:COMPLEX:1')).toBe('0')
    expect(screen.getByRole('status')).toHaveTextContent('서울 단지 알림 신청을 취소했어요.')

    second.unmount()
    render(example({ record }, loadUser))
    expect(screen.getByRole('button', { name: '서울 단지 알림 받기' }).querySelector('svg'))
      .toHaveAttribute('data-state', 'idle')
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    expect(await screen.findByRole('dialog', { name: '이메일 알림 신청' })).toBeVisible()
    await waitFor(() => expect(screen.getByRole('textbox', { name: '알림 받을 이메일' })).toHaveValue('member@example.com'))
    fireEvent.click(screen.getByRole('button', { name: '알림 신청' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 취소' })).toHaveAttribute('aria-pressed', 'true'))
    fireEvent.click(screen.getByRole('button', { name: '서울특별시 알림 받기' }))
    await waitFor(() => expect(record).toHaveBeenCalledTimes(6))
    expect(record.mock.calls.map(([event]) => event.eventType)).toEqual(['CLICKED', 'CONFIRMED', 'CANCELLED', 'CLICKED', 'CONFIRMED', 'CLICKED'])
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(loadUser).toHaveBeenCalledTimes(2)
  })

  it('취소 저장이 실패하면 신청 상태를 유지하고 같은 취소 이벤트를 재시도한다', async () => {
    const record = vi.fn().mockResolvedValueOnce(undefined).mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error('실패')).mockResolvedValue(undefined)
    render(example({ record }))
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    await screen.findByRole('dialog')
    fireEvent.change(screen.getByRole('textbox', { name: '알림 받을 이메일' }), { target: { value: 'guest@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: '알림 신청' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    const cancelButton = screen.getByRole('button', { name: '서울 단지 알림 취소' })
    fireEvent.click(cancelButton)
    expect(await screen.findByRole('alert')).toHaveTextContent('알림 취소를 완료하지 못했어요.')
    expect(cancelButton).toHaveAttribute('aria-pressed', 'true')
    expect(localStorage.getItem('toadzip.notification-interest.requested:COMPLEX:1')).toBe('1')
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toHaveAttribute('aria-pressed', 'false'))
    expect(record.mock.calls[2]?.[0]).toEqual(record.mock.calls[3]?.[0])
  })

  it('이전 버전의 완료 기록이 있어도 이메일 신청 전에는 폼을 연다', async () => {
    localStorage.setItem('toadzip.notification-interest.prompt-completed', '1')
    localStorage.setItem('toadzip.notification-interest.email-prompt-completed', '1')
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    render(example({ record }))
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    expect(await screen.findByRole('dialog', { name: '이메일 알림 신청' })).toBeVisible()
  })

  it('이전 버전의 신청 표시만 남아 있으면 다시 신청해야 함을 안내한다', async () => {
    localStorage.setItem('toadzip.notification-interest.requested:COMPLEX:1', '1')
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    const loadStatus = vi.fn().mockResolvedValue({ guest: true, emailConfirmed: false, targets: [] })
    render(example({ record, loadStatus }))
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled())

    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    expect(await screen.findByRole('dialog')).toHaveTextContent('이전에 누른 알림은 현재 신청 목록에 포함되지 않았어요. 이메일을 입력해 다시 신청해 주세요.')
  })

  it('늦은 로그인 응답은 사용자가 입력한 주소를 덮지 않는다', async () => {
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    let resolveUser: (value: { id: number; email: string }) => void = () => {}
    const loadUser = vi.fn().mockImplementation(() => new Promise((resolve) => { resolveUser = resolve }))
    render(example({ record }, loadUser))
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    const email = await screen.findByRole('textbox', { name: '알림 받을 이메일' })
    fireEvent.change(email, { target: { value: 'typed@example.com' } })
    await act(async () => resolveUser({ id: 7, email: 'member@example.com' }))
    expect(email).toHaveValue('typed@example.com')
  })

  it('저장소를 사용할 수 없어도 취소 후 다음 클릭에서 다시 묻는다', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('저장소 차단') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('저장소 차단') })
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    render(example({ record }))
    const trigger = screen.getByRole('button', { name: '서울 단지 알림 받기' })
    fireEvent.click(trigger)
    const dialog = await screen.findByRole('dialog')
    fireEvent.keyDown(dialog, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(trigger).toHaveFocus())
    expect(record.mock.calls[1]?.[0]).toEqual(expect.objectContaining({ eventType: 'DECLINED' }))
    fireEvent.click(screen.getByRole('button', { name: '서울특별시 알림 받기' }))
    await waitFor(() => expect(record).toHaveBeenCalledTimes(3))
    expect(record.mock.calls[2]?.[0]).toEqual(expect.objectContaining({ eventType: 'CLICKED' }))
    expect(await screen.findByRole('dialog', { name: '이메일 알림 신청' })).toBeVisible()
    expect(screen.getByRole('textbox', { name: '알림 받을 이메일' })).toHaveValue('')
  })

  it('취소 후 같은 대상이나 새로고침에서 다시 이메일 폼을 연다', async () => {
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    const first = render(example({ record }))
    const trigger = screen.getByRole('button', { name: '서울 단지 알림 받기' })
    fireEvent.click(trigger)
    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(localStorage.getItem('toadzip.notification-interest.email-confirmed')).toBeNull()
    expect(trigger.querySelector('svg')).toHaveAttribute('data-state', 'idle')

    fireEvent.click(trigger)
    expect(await screen.findByRole('dialog', { name: '이메일 알림 신청' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    first.unmount()

    render(example({ record }))
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    expect(await screen.findByRole('dialog', { name: '이메일 알림 신청' })).toBeVisible()
    expect(record.mock.calls.map(([event]) => event.eventType)).toEqual([
      'CLICKED', 'DECLINED', 'CLICKED', 'DECLINED', 'CLICKED',
    ])
  })

  it('실제 화면 노출만 기록하고 같은 세션의 반복 노출은 중복하지 않는다', async () => {
    const observers: Array<(entries: Array<{ isIntersecting: boolean }>) => void> = []
    vi.stubGlobal('IntersectionObserver', class {
      constructor(callback: (entries: Array<{ isIntersecting: boolean }>) => void) { observers.push(callback) }
      observe() {}
      disconnect() {}
    })
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    const view = render(example({ record }))
    await act(async () => { observers[0]?.([{ isIntersecting: false }]) })
    expect(record).not.toHaveBeenCalled()
    await act(async () => { observers[0]?.([{ isIntersecting: true }]) })
    expect(record).toHaveBeenCalledOnce()
    expect(record.mock.calls[0]?.[0].eventType).toBe('EXPOSED')
    view.unmount()
    render(example({ record }))
    await act(async () => { observers[2]?.([{ isIntersecting: true }]) })
    expect(record).toHaveBeenCalledOnce()
  })

  it('저장 실패를 완료로 표시하지 않고 같은 이벤트 ID로 재시도한다', async () => {
    const record = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('실패'))
      .mockResolvedValue(undefined)
    render(example({ record }))
    const trigger = screen.getByRole('button', { name: '서울 단지 알림 받기' })
    fireEvent.click(trigger)
    await screen.findByRole('dialog')
    fireEvent.change(screen.getByRole('textbox', { name: '알림 받을 이메일' }), { target: { value: 'retry@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: '알림 신청' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('알림 신청을 완료하지 못했어요.')
    expect(trigger.querySelector('svg')).toHaveAttribute('data-state', 'idle')
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(record.mock.calls[1]?.[0]).toEqual(record.mock.calls[2]?.[0])
    expect(trigger.querySelector('svg')).toHaveAttribute('data-state', 'requested')
  })

  it('저장 실패 뒤 창을 닫아도 다시 알림 신청을 시작할 수 있다', async () => {
    const record = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('실패'))
      .mockResolvedValue(undefined)
    render(example({ record }))
    const trigger = screen.getByRole('button', { name: '서울 단지 알림 받기' })
    fireEvent.click(trigger)
    await screen.findByRole('dialog')
    fireEvent.change(screen.getByRole('textbox', { name: '알림 받을 이메일' }), { target: { value: 'guest@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: '알림 신청' }))
    await screen.findByRole('alert')
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(trigger).toHaveFocus()
    fireEvent.click(trigger)
    expect(await screen.findByRole('dialog')).toBeVisible()
  })
})

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((complete, fail) => { resolve = complete; reject = fail })
  return { promise, resolve, reject }
}

function resultFor(event: NotificationInterestEvent, outcome: NotificationOutcome): NotificationInterestResult {
  return { eventId: event.eventId, targetType: event.targetType, targetId: event.targetId, outcome, occurredAt: '2026-10-08T00:00:00Z' }
}
function completions() {
  return vi.mocked(captureProductEvent).mock.calls.filter(([name]) => name === 'notification_preregistration_completed')
}
