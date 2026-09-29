import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { NotificationInterestButton, NotificationInterestProvider } from './NotificationInterest'
import type { NotificationInterestRepository } from './notificationInterestRepository'

beforeEach(() => {
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

  it('신청 후 다시 누르면 취소되고 새로고침 후 폼 없이 재신청한다', async () => {
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
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 취소' })).toHaveAttribute('aria-pressed', 'true'))
    fireEvent.click(screen.getByRole('button', { name: '서울특별시 알림 받기' }))
    await waitFor(() => expect(record).toHaveBeenCalledTimes(5))
    expect(record.mock.calls.map(([event]) => event.eventType)).toEqual(['CLICKED', 'CONFIRMED', 'CANCELLED', 'CLICKED', 'CLICKED'])
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(loadUser).toHaveBeenCalledTimes(1)
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
    expect(trigger).toHaveFocus()
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
