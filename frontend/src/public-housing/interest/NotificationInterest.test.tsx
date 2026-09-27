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

function example(repository: NotificationInterestRepository) {
  return (
    <NotificationInterestProvider repository={repository}>
      <NotificationInterestButton target={{ type: 'COMPLEX', id: '1', name: '서울 단지' }} source="COMPLEX_DETAIL" />
      <NotificationInterestButton target={{ type: 'REGION', id: '11', name: '서울특별시' }} source="REGION_SEARCH" />
    </NotificationInterestProvider>
  )
}

describe('알림 수요', () => {
  it('브라우저 저장소를 사용할 수 없어도 현재 화면에서는 한 번만 묻는다', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('저장소 차단') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('저장소 차단') })
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    render(example({ record }))
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: '아니요' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '서울특별시 알림 받기' }))
    await waitFor(() => expect(record).toHaveBeenCalledTimes(3))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('실제 화면 노출에만 기록하고 같은 세션의 반복 노출은 중복하지 않는다', async () => {
    const observers: Array<(entries: Array<{ isIntersecting: boolean }>) => void> = []
    vi.stubGlobal('IntersectionObserver', class {
      constructor(callback: (entries: Array<{ isIntersecting: boolean }>) => void) { observers.push(callback) }
      observe() {}
      disconnect() {}
    })
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    const view = render(example({ record }))
    expect(record).not.toHaveBeenCalled()
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

  it('최초 확인 후 다른 대상과 새로고침에서는 클릭만 기록한다', async () => {
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    const view = render(example({ record }))
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    expect(await screen.findByRole('dialog', { name: '알림 신청 의사 확인' })).toBeVisible()
    expect(screen.getByText('이 단지에 대한 알림을 받으시겠습니까?')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '네, 받고 싶어요' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '서울특별시 알림 받기' }))
    await screen.findByText(/관심이 기록되었습니다/)
    view.unmount()
    render(example({ record }))
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    await waitFor(() => expect(record).toHaveBeenCalledTimes(4))
    expect(record.mock.calls.map(([event]) => event.eventType))
      .toEqual(['CLICKED', 'CONFIRMED', 'CLICKED', 'CLICKED'])
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toHaveFocus())
  })

  it('최초 취소도 질문을 마치며 신청으로 기록하지 않는다', async () => {
    const record = vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined)
    render(example({ record }))
    const trigger = screen.getByRole('button', { name: '서울특별시 알림 받기' })
    trigger.focus()
    fireEvent.click(trigger)
    const dialog = await screen.findByRole('dialog')
    expect(screen.getByText('이 지역에 대한 알림을 받으시겠습니까?')).toBeVisible()
    fireEvent.keyDown(dialog, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(trigger).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    await waitFor(() => expect(record).toHaveBeenCalledTimes(3))
    expect(record.mock.calls.map(([event]) => event.eventType))
      .toEqual(['CLICKED', 'DECLINED', 'CLICKED'])
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('저장 실패를 완료로 표시하지 않고 같은 이벤트 ID로 재시도한다', async () => {
    const record = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('실패'))
      .mockResolvedValue(undefined)
    render(example({ record }))
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: '네, 받고 싶어요' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('관심을 기록하지 못했습니다.')
    expect(screen.queryByText(/관심이 기록되었습니다/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(record.mock.calls[1]?.[0]).toEqual(record.mock.calls[2]?.[0])
  })
})
