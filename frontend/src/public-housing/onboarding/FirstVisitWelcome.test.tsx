import { fireEvent, render, screen, within } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { IntegratedSearchRepository, IntegratedSearchResponse, SearchResultItem } from '../search/integratedSearchRepository.ts'
import { FirstVisitWelcome } from './FirstVisitWelcome.tsx'

const region: SearchResultItem = {
  id: '11140', regionCode: '11140', type: 'REGION', title: '서울특별시 중구',
  subtitle: null, latitude: 37.5636, longitude: 126.9976,
  applicationStatus: null, publishedAt: null,
}
const response: IntegratedSearchResponse = {
  announcements: [], complexes: [], regions: [region], failures: [],
  hasNext: false, page: 0, query: '중구', size: 5, totalCount: 1,
}

beforeEach(() => localStorage.clear())
afterEach(() => { localStorage.clear(); vi.restoreAllMocks() })

describe('첫 방문 안내', () => {
  it('완료 후 7일 직전에는 생략하고 정확히 7일 뒤 다시 표시하며 완료할 때만 갱신한다', () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_800_000_000_000)
    const mount = () => render(<FirstVisitWelcome onPlaceSelect={vi.fn()} onRegionSelect={vi.fn()} />)
    const first = mount()
    fireEvent.click(screen.getByRole('button', { name: '바로 지도 둘러보기' }))
    first.unmount()
    const saved = localStorage.getItem('toadzip:welcome-completed')
    now.mockReturnValue(1_800_000_000_000 + 7 * 24 * 60 * 60 * 1000 - 1)
    const returning = mount()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(localStorage.getItem('toadzip:welcome-completed')).toBe(saved)
    returning.unmount()
    now.mockReturnValue(1_800_000_000_000 + 7 * 24 * 60 * 60 * 1000)
    const expired = mount()
    expect(screen.getByRole('dialog')).toBeVisible()
    expect(localStorage.getItem('toadzip:welcome-completed')).toBe(saved)
    fireEvent.click(screen.getByRole('button', { name: '바로 지도 둘러보기' }))
    expired.unmount()
    now.mockReturnValue(1_800_000_000_000 + 8 * 24 * 60 * 60 * 1000)
    mount()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it.each(['true', 'broken', 'null', '{}', '{"expiresAt":"invalid"}'])('기존 또는 잘못된 기록 %s는 안내를 다시 표시한다', (record) => {
    localStorage.setItem('toadzip:welcome-completed', record)
    render(<FirstVisitWelcome onPlaceSelect={vi.fn()} onRegionSelect={vi.fn()} />)
    expect(screen.getByRole('dialog')).toBeVisible()
  })

  it('모달에서도 입력 중 제안을 Enter로 선택하고 Escape는 검색어만 먼저 지운다', async () => {
    const onRegionSelect = vi.fn()
    const repository = { search: vi.fn().mockResolvedValue(response) }
    render(<FirstVisitWelcome onPlaceSelect={vi.fn()} onRegionSelect={onRegionSelect} repository={repository} />)
    fireEvent.click(screen.getByRole('button', { name: '살고 싶은 지역 검색하기' }))
    const input = screen.getByRole('searchbox')
    fireEvent.change(input, { target: { value: '중구' } })
    await screen.findByRole('button', { name: '서울특별시 중구' })
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(input).toHaveValue('')
    expect(screen.getByRole('dialog')).toBeVisible()
    expect(screen.getByRole('button', { name: /서울 잠실/ })).toBeVisible()
    fireEvent.change(input, { target: { value: '중구' } })
    await screen.findByRole('button', { name: '서울특별시 중구' })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onRegionSelect).toHaveBeenCalledExactlyOnceWith(region)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it.each([
    ['서울 잠실', 37.51322, 127.10014],
    ['서울 강남', 37.49782, 127.02775],
    ['성남 판교', 37.39473, 127.11119],
  ])('검색 전 예시 %s를 선택하면 API 없이 해당 동네에서 시작한다', (name, latitude, longitude) => {
    const onPlaceSelect = vi.fn()
    const repository = { search: vi.fn() }
    render(<FirstVisitWelcome onPlaceSelect={onPlaceSelect} onRegionSelect={vi.fn()} repository={repository} />)
    fireEvent.click(screen.getByRole('button', { name: '살고 싶은 지역 검색하기' }))
    expect(screen.getByRole('searchbox')).toHaveAttribute('placeholder', '예: 잠실, 강남, 판교')
    expect(screen.queryByText('지역 이름을 두 글자 이상 입력해 주세요.')).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '지역' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: new RegExp(name) }))
    expect(onPlaceSelect).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ center: { latitude, longitude }, zoom: 14 }))
    expect(repository.search).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('첫 방문 즉시 현재 로고와 두 시작 동작을 표시하고 X는 표시하지 않는다', () => {
    render(<StrictMode><FirstVisitWelcome onPlaceSelect={vi.fn()} onRegionSelect={vi.fn()} /></StrictMode>)
    const dialog = screen.getByRole('dialog', { name: /살고 싶은 동네의/ })
    expect(within(dialog).getByRole('img', { name: 'BOK 공공주택 복덕방 로고' })).toBeVisible()
    expect(within(dialog).getAllByRole('button').map(button => button.textContent))
      .toEqual(['살고 싶은 지역 검색하기', '바로 지도 둘러보기'])
    fireEvent.click(dialog)
    expect(dialog).toBeVisible()
  })

  it('보기만 하고 나가면 다음 방문에도 표시하고, 지도 탐색을 시작하면 다시 표시하지 않는다', () => {
    const first = render(<FirstVisitWelcome onPlaceSelect={vi.fn()} onRegionSelect={vi.fn()} />)
    first.unmount()
    const second = render(<FirstVisitWelcome onPlaceSelect={vi.fn()} onRegionSelect={vi.fn()} />)
    expect(screen.getByRole('dialog')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '바로 지도 둘러보기' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    second.unmount()
    render(<FirstVisitWelcome onPlaceSelect={vi.fn()} onRegionSelect={vi.fn()} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('지역만 검색하고 선택 즉시 시작하며 다음 방문에는 안내를 생략한다', async () => {
    const repository: IntegratedSearchRepository = { search: vi.fn().mockResolvedValue(response) }
    const onRegionSelect = vi.fn()
    const first = render(<FirstVisitWelcome onPlaceSelect={vi.fn()} onRegionSelect={onRegionSelect} repository={repository} />)
    fireEvent.click(screen.getByRole('button', { name: '살고 싶은 지역 검색하기' }))
    const input = screen.getByRole('searchbox', { name: '살고 싶은 지역' })
    expect(input).toHaveFocus()
    fireEvent.change(input, { target: { value: '중구' } })
    fireEvent.click(await screen.findByRole('button', { name: '서울특별시 중구' }))
    expect(repository.search).toHaveBeenCalledWith('중구', false, 0, expect.any(AbortSignal), 'REGION')
    expect(onRegionSelect).toHaveBeenCalledExactlyOnceWith(region)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    first.unmount()
    render(<FirstVisitWelcome onPlaceSelect={vi.fn()} onRegionSelect={vi.fn()} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('검색 오류에서 재시도할 수 있고 결과가 없을 때도 바로 지도 탐색을 시작할 수 있다', async () => {
    const repository = { search: vi.fn().mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ ...response, regions: [], totalCount: 0 }) }
    render(<FirstVisitWelcome onPlaceSelect={vi.fn()} onRegionSelect={vi.fn()} repository={repository} />)
    fireEvent.click(screen.getByRole('button', { name: '살고 싶은 지역 검색하기' }))
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '중구' } })
    expect(await screen.findByRole('alert')).toHaveTextContent('지역 검색 결과를 불러오지 못했습니다.')
    fireEvent.click(screen.getByRole('button', { name: '지역 다시 시도' }))
    expect(await screen.findByText('지역 검색 결과가 없습니다.')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '바로 지도 둘러보기' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('저장소가 차단되어도 안내를 표시하고 Escape로 지도 탐색을 시작한다', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    render(<FirstVisitWelcome onPlaceSelect={vi.fn()} onRegionSelect={vi.fn()} />)
    fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
