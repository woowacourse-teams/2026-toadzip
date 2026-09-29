import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import HwpDocumentPreview from './HwpDocumentPreview.tsx'
import type { HwpResponse } from './hwpPreview.worker.ts'

class PreviewWorker {
  static instances: PreviewWorker[] = []
  onmessage: ((event: { data: HwpResponse }) => void) | null = null
  onerror: (() => void) | null = null
  postMessage = vi.fn()
  terminate = vi.fn()
  constructor() { PreviewWorker.instances.push(this) }
  page(page = 0) {
    act(() => this.onmessage?.({ data: { type: 'page', page, count: 2, svg: '<svg/>', text: '<script>공고문</script>' } }))
  }
}

beforeEach(() => {
  PreviewWorker.instances = []
  vi.stubGlobal('Worker', PreviewWorker)
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:hwp-page'), revokeObjectURL: vi.fn() }))
})
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

it('페이지 이동과 확대를 제공하고 문서 텍스트를 HTML로 실행하지 않는다', () => {
  const { unmount } = render(<HwpDocumentPreview url="blob:original" name="공고.hwpx" />)
  const worker = PreviewWorker.instances[0]!
  expect(worker.postMessage).toHaveBeenCalledWith({ type: 'open', url: 'blob:original' })
  worker.page()
  expect(screen.getByRole('status')).toHaveTextContent('1 / 2 페이지')
  expect(screen.getByRole('button', { name: '이전 페이지' })).toBeDisabled()
  expect(screen.getByText('<script>공고문</script>')).toBeInTheDocument()
  expect(document.querySelector('script')).toBeNull()
  fireEvent.change(screen.getByRole('combobox', { name: '크기' }), { target: { value: '150' } })
  expect(screen.getByRole('img')).toHaveStyle({ width: '1191px' })
  fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }))
  expect(worker.postMessage).toHaveBeenLastCalledWith({ type: 'page', page: 1 })
  expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled()
  worker.page(1)
  expect(screen.getByRole('status')).toHaveTextContent('2 / 2 페이지')
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:hwp-page')
  unmount()
  expect(worker.terminate).toHaveBeenCalled()
})

it('암호·손상 등 SDK 실패는 다운로드 안내와 새 작업자로 재시도를 제공한다', () => {
  render(<HwpDocumentPreview url="blob:original" name="공고.hwp" />)
  const worker = PreviewWorker.instances[0]!
  act(() => worker.onmessage?.({ data: { type: 'error' } }))
  expect(screen.getByRole('alert')).toHaveTextContent('다운로드해서 확인해 주세요')
  expect(worker.terminate).toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(PreviewWorker.instances).toHaveLength(2)
  PreviewWorker.instances[1]!.page()
  expect(screen.getByRole('img', { name: '공고.hwp 1페이지' })).toBeVisible()
})

it('해석이 멈추면 제한시간 후 작업자를 종료하고 오류를 표시한다', () => {
  vi.useFakeTimers()
  render(<HwpDocumentPreview url="blob:original" name="공고.hwp" />)
  act(() => vi.advanceTimersByTime(60_000))
  expect(PreviewWorker.instances[0]!.terminate).toHaveBeenCalled()
  expect(screen.getByRole('alert')).toBeVisible()
})

it('페이지 이동 중 닫으면 작업자와 타이머와 이미지 URL을 정리한다', () => {
  vi.useFakeTimers()
  const { unmount } = render(<HwpDocumentPreview url="blob:original" name="공고.hwp" />)
  PreviewWorker.instances[0]!.page()
  fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }))
  unmount()
  expect(PreviewWorker.instances[0]!.terminate).toHaveBeenCalled()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:hwp-page')
  expect(vi.getTimerCount()).toBe(0)
})
