import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { IntegratedSearch } from './IntegratedSearch.tsx'
import type {
  IntegratedSearchRepository,
  IntegratedSearchResponse,
  SearchResultItem,
} from './integratedSearchRepository.ts'

vi.mock('../regions/regionBoundaryCatalog.ts', () => ({
  findRegionBoundaryMetadata: (code: string) => code === '41111' ? { regionCode: code } : null,
}))

describe('IntegratedSearch', () => {
  it('검색창만 표시하다 공백을 제외한 두 글자부터 검색하고 지우면 목록을 닫는다', async () => {
    vi.useFakeTimers()
    try {
      const repository = repositoryWith(response([], [], []))
      render(<IntegratedSearch repository={repository} onSelect={vi.fn()} />)
      const input = screen.getByRole('searchbox', { name: '지역, 단지, 공고 검색' })
      const searchRegion = screen.getByRole('region', { name: '통합 검색' })

      expect(input).toHaveAttribute('placeholder', '지역, 단지, 공고 검색')
      expect(searchRegion.querySelector('.integrated-search__body')).toBeNull()
      expect(screen.queryByText('두 글자 이상 입력해 주세요.')).not.toBeInTheDocument()
      fireEvent.change(input, { target: { value: ' 서  ' } })
      await act(async () => vi.advanceTimersByTime(200))
      expect(repository.search).not.toHaveBeenCalled()
      expect(screen.queryByRole('heading', { name: '검색결과' })).not.toBeInTheDocument()

      fireEvent.change(input, { target: { value: ' 서 울 ' } })
      await act(async () => vi.advanceTimersByTime(199))
      expect(repository.search).not.toHaveBeenCalled()
      await act(async () => vi.advanceTimersByTime(1))
      expect(repository.search).toHaveBeenCalledTimes(3)
      expect(repository.search).toHaveBeenCalledWith('서 울', false, 0, expect.any(AbortSignal), 'REGION')
      expect(screen.getByRole('heading', { name: '검색결과' })).toBeVisible()

      fireEvent.change(input, { target: { value: '' } })
      expect(searchRegion.querySelector('.integrated-search__body')).toBeNull()
      expect(screen.queryByRole('heading', { name: '검색결과' })).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('지역 조작부는 입력 다음과 검색결과 앞에 남고 결과 스크롤과 검색 닫기에 영향받지 않는다', async () => {
    const regions = Array.from({ length: 20 }, (_, index) => item('REGION', String(index), `서울 지역 ${index}`))
    render(<IntegratedSearch
      repository={repositoryWith(response([], [], regions))}
      onSelect={vi.fn()}
      selectionControl={<section aria-label="검색 지역 표시"><button type="button">전체 보기</button></section>}
    />)
    const control = screen.getByRole('region', { name: '검색 지역 표시' })
    const input = screen.getByRole('searchbox')
    expect(input.compareDocumentPosition(control) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    fireEvent.change(input, { target: { value: '서울' } })
    await screen.findByRole('button', { name: /서울 지역 19/ })
    const heading = screen.getByRole('heading', { name: '검색결과' })
    expect(control.compareDocumentPosition(heading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    const body = screen.getByRole('region', { name: '통합 검색' }).querySelector('.integrated-search__body')
    if (!(body instanceof HTMLElement)) throw new Error('검색 결과 스크롤 영역 없음')
    expect(body).not.toContainElement(control)
    body.scrollTop = 800
    fireEvent.scroll(body)
    expect(screen.getByRole('region', { name: '검색 지역 표시' })).toBe(control)
    fireEvent.click(screen.getByRole('button', { name: '검색결과 닫기' }))
    expect(screen.getByRole('region', { name: '검색 지역 표시' })).toBe(control)
    expect(screen.getByRole('button', { name: '전체 보기' })).toBeVisible()
  })

  it('로딩과 오류, 빈 결과 사이에 본문 컨테이너를 유지하고 유형별로 다시 시도한다', async () => {
    let regionAttempts = 0
    const search = vi.fn<IntegratedSearchRepository['search']>().mockImplementation(
      async (_query, _preview, _page, _signal, type) => {
        if (type === 'REGION' && regionAttempts++ === 0) {
          throw new Error('검색 실패')
        }
        return response([], [], [])
      },
    )
    render(<IntegratedSearch repository={{ search }} onSelect={vi.fn()} />)
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })
    const body = screen.getByRole('region', { name: '통합 검색' })
      .querySelector('.integrated-search__body')
    expect(body).not.toBeNull()

    expect(body).toContainElement(screen.getByText('지역 검색 중입니다.'))
    expect(body).toContainElement(await screen.findByRole('alert'))
    fireEvent.click(screen.getByRole('button', { name: '지역 다시 시도' }))
    expect(body).toContainElement(await screen.findByText('지역 검색 결과가 없습니다.'))
    expect(search).toHaveBeenCalledTimes(4)
  })

  it('검색결과 제목과 지역 공고 단지 순서를 표시하고 유형별 첫 페이지를 요청한다', async () => {
    const announcement = item('ANNOUNCEMENT', '1', '서울 행복주택 공고')
    const complex = item('COMPLEX', '2', '서울 행복주택 단지')
    const region = item('REGION', '11', '서울특별시 전체')
    const repository = repositoryWith(response([announcement], [complex], [region]))
    const onSelect = vi.fn()
    render(<IntegratedSearch repository={repository} onSelect={onSelect} />)

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '  서울  ' } })
    await screen.findByText('서울 행복주택 공고')

    expect(screen.getAllByRole('heading').map((heading) => heading.textContent)).toEqual([
      '검색결과', '지역', '공고', '단지',
    ])
    expect(screen.getByRole('button', { name: '검색결과 닫기' })).toBeVisible()
    for (const type of ['REGION', 'ANNOUNCEMENT', 'COMPLEX']) {
      expect(repository.search).toHaveBeenCalledWith('서울', false, 0, expect.any(AbortSignal), type)
    }
    expect(screen.queryByRole('button', { name: '전체 결과 보기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: '검색 결과 페이지' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /서울 행복주택 공고/ }))
    expect(onSelect).toHaveBeenCalledWith(announcement)
  })

  it('검색결과 닫기는 검색어와 결과만 지우고 선택 콜백을 호출하지 않는다', async () => {
    const onSelect = vi.fn()
    const onActiveChange = vi.fn()
    render(<IntegratedSearch
      repository={repositoryWith(response([], [item('COMPLEX', '1', '서울 단지')], []))}
      onSelect={onSelect}
      onActiveChange={onActiveChange}
    />)
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })
    await screen.findByText('서울 단지')

    fireEvent.click(screen.getByRole('button', { name: '검색결과 닫기' }))

    expect(screen.getByRole('searchbox')).toHaveValue('')
    expect(screen.getByRole('searchbox')).toHaveFocus()
    expect(screen.queryByText('서울 단지')).not.toBeInTheDocument()
    expect(onActiveChange).toHaveBeenLastCalledWith(false)
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('지역을 선택해도 검색어와 검색결과를 유지하고 좌표 없는 지역은 이동시키지 않는다', async () => {
    const region = item('REGION', '41110', '수원시')
    const unavailable = { ...item('REGION', '99999', '미지원 지역'), latitude: null }
    const onSelect = vi.fn()
    render(<IntegratedSearch
      repository={repositoryWith(response([], [], [region, unavailable]))}
      onSelect={onSelect}
    />)
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '수원' } })
    fireEvent.click(await screen.findByRole('button', { name: /^수원시\s*서울/ }))

    expect(onSelect).toHaveBeenCalledExactlyOnceWith(region)
    expect(screen.getByRole('searchbox')).toHaveValue('수원')
    const unavailableButton = screen.getByRole('button', { name: /미지원 지역/ })
    expect(unavailableButton).toBeDisabled()
    expect(within(unavailableButton).getByText('위치 정보 준비 중')).toBeVisible()
    fireEvent.click(unavailableButton)
    expect(onSelect).toHaveBeenCalledOnce()
  })

  it('경계가 있는 지역은 대표 좌표 없이 선택할 수 있다', async () => {
    const region = { ...item('REGION', '41111', '수원시 장안구'), regionCode: '41111', latitude: null, longitude: null }
    const onSelect = vi.fn()
    render(<IntegratedSearch repository={repositoryWith(response([], [], [region]))} onSelect={onSelect} />)
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '장안' } })
    const button = await screen.findByRole('button', { name: /수원시 장안구/ })
    expect(button).toBeEnabled()
    fireEvent.click(button)
    expect(onSelect).toHaveBeenCalledWith(region)
  })

  it('더보기는 현재 수와 유형별 전체 수를 갱신하고 마지막 페이지에서 사라진다', async () => {
    const next = deferred<IntegratedSearchResponse>()
    const firstPage = Array.from({ length: 5 }, (_, index) => (
      item('COMPLEX', String(index), `서울 단지 ${index + 1}`)
    ))
    const secondPage = Array.from({ length: 5 }, (_, index) => (
      item('COMPLEX', String(index + 5), `서울 단지 ${index + 6}`)
    ))
    const initial = response([], firstPage, [item('REGION', '11', '서울특별시')])
    const search = vi.fn<IntegratedSearchRepository['search']>().mockImplementation(
      async (_query, _preview, page, _signal, type) => {
        if (page === 1) {
          return next.promise
        }
        if (page === 2) {
          return { ...response([], [item('COMPLEX', '10', '서울 단지 11')], []), totalCount: 11 }
        }
        return { ...initial, hasNext: type === 'COMPLEX', totalCount: type === 'COMPLEX' ? 11 : 1 }
      },
    )
    render(<IntegratedSearch repository={{ search }} onSelect={vi.fn()} />)
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })
    const more = await screen.findByRole('button', { name: /^단지 더보기/ })
    expect(more).toHaveTextContent(/더보기\s*\(5 \| 11\)/)
    fireEvent.click(more)
    const complexGroup = within(screen.getByRole('region', { name: '단지' }))

    await waitFor(() => expect(search).toHaveBeenCalledTimes(4))
    expect(search).toHaveBeenLastCalledWith('서울', false, 1, expect.any(AbortSignal), 'COMPLEX')
    expect(complexGroup.getAllByRole('listitem')).toHaveLength(5)
    expect(screen.getByText('서울특별시')).toBeVisible()
    expect(screen.getByRole('button', { name: /^단지 더보기/ })).toBeDisabled()
    next.resolve({ ...response([], secondPage, []), hasNext: true, totalCount: 11 })

    expect(await screen.findByText('서울 단지 10')).toBeVisible()
    expect(screen.getByText('서울 단지 1')).toBeVisible()
    expect(complexGroup.getAllByRole('listitem')).toHaveLength(10)
    expect(screen.getByRole('button', { name: /^단지 더보기/ })).toHaveTextContent(/더보기\s*\(10 \| 11\)/)
    fireEvent.click(screen.getByRole('button', { name: /^단지 더보기/ }))
    expect(await screen.findByText('서울 단지 11')).toBeVisible()
    expect(complexGroup.getAllByRole('listitem')).toHaveLength(11)
    expect(screen.queryByRole('button', { name: /^단지 더보기/ })).not.toBeInTheDocument()
  })

  it('전체 건수가 없는 구버전 응답도 더보기를 유지하고 알 수 없는 수는 대시로 표시한다', async () => {
    const repository = repositoryWith({
      ...response([], [item('COMPLEX', '1', '서울 단지')], []), hasNext: true,
    })
    render(<IntegratedSearch repository={repository} onSelect={vi.fn()} />)
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })

    const more = await screen.findByRole('button', { name: '단지 더보기, 현재 1개, 전체 확인 중' })
    expect(more).toHaveTextContent('(1 | —)')
    expect(more).toBeEnabled()
  })

  it('추가 조회가 실패하면 기존 결과를 유지하며 실패한 페이지를 재시도한다', async () => {
    let nextAttempts = 0
    const search = vi.fn<IntegratedSearchRepository['search']>().mockImplementation(
      async (_query, _preview, page, _signal, type) => {
        if (page === 1 && nextAttempts++ === 0) {
          throw new Error('일시적 오류')
        }
        return {
          ...response([], [item('COMPLEX', String(page), `서울 단지 ${page}`)], []),
          hasNext: type === 'COMPLEX' && page === 0,
        }
      },
    )
    render(<IntegratedSearch repository={{ search }} onSelect={vi.fn()} />)
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })
    fireEvent.click(await screen.findByRole('button', { name: /^단지 더보기/ }))
    fireEvent.click(await screen.findByRole('button', { name: '단지 다시 시도' }))

    expect(screen.getByText('서울 단지 0')).toBeVisible()
    expect(await screen.findByText('서울 단지 1')).toBeVisible()
    expect(search).toHaveBeenLastCalledWith('서울', false, 1, expect.any(AbortSignal), 'COMPLEX')
    expect(search).toHaveBeenCalledTimes(5)
  })

  it('늦게 끝난 이전 검색과 추가 조회를 취소하고 최신 검색 첫 페이지를 유지한다', async () => {
    const previousPage = deferred<IntegratedSearchResponse>()
    const signals: AbortSignal[] = []
    const search = vi.fn<IntegratedSearchRepository['search']>().mockImplementation(
      async (query, _preview, page, signal, type) => {
        if (query === '서울') {
          signals.push(signal)
        }
        if (page === 1) {
          return previousPage.promise
        }
        return {
          ...response([], [item('COMPLEX', query, `${query} 단지`)], []),
          hasNext: type === 'COMPLEX',
        }
      },
    )
    render(<IntegratedSearch repository={{ search }} onSelect={vi.fn()} />)
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })
    fireEvent.click(await screen.findByRole('button', { name: /^단지 더보기/ }))
    await waitFor(() => expect(search).toHaveBeenCalledTimes(4))
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '부산' } })
    expect(await screen.findByText('부산 단지')).toBeVisible()

    await act(async () => previousPage.resolve(response([], [item('COMPLEX', 'old', '서울 추가 단지')], [])))

    expect(signals.every((signal) => signal.aborted)).toBe(true)
    expect(screen.queryByText('서울 단지')).not.toBeInTheDocument()
    expect(screen.queryByText('서울 추가 단지')).not.toBeInTheDocument()
    expect(screen.getByText('부산 단지')).toBeVisible()
    expect(search).toHaveBeenCalledWith('부산', false, 0, expect.any(AbortSignal), 'COMPLEX')
  })

  it('검색결과를 닫은 뒤 늦게 끝난 요청 결과를 표시하지 않는다', async () => {
    const pending = deferred<IntegratedSearchResponse>()
    const search = vi.fn().mockReturnValue(pending.promise)
    render(<IntegratedSearch repository={{ search }} onSelect={vi.fn()} />)

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })
    await waitFor(() => expect(search).toHaveBeenCalledTimes(3))
    fireEvent.click(screen.getByRole('button', { name: '검색결과 닫기' }))
    await act(async () => pending.resolve(response([], [item('COMPLEX', '1', '서울 단지')], [])))

    expect(screen.getByRole('searchbox')).toHaveValue('')
    expect(screen.queryByRole('heading', { name: '검색결과' })).not.toBeInTheDocument()
    expect(screen.queryByText('서울 단지')).not.toBeInTheDocument()
  })

  it('일부 유형 실패 시 성공 결과와 해당 유형 재시도를 함께 표시한다', async () => {
    const successful = response([], [item('COMPLEX', '1', '서울 단지')], [], [{
      message: '지역 검색에 실패했습니다. 다시 시도해 주세요.',
      type: 'REGION',
    }])
    const repository = repositoryWith(successful)
    render(<IntegratedSearch repository={repository} onSelect={vi.fn()} />)

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })

    expect(await screen.findByText('서울 단지')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '지역 다시 시도' }))
    await waitFor(() => expect(repository.search).toHaveBeenCalledTimes(4))
    expect(repository.search).toHaveBeenLastCalledWith('서울', false, 0, expect.any(AbortSignal), 'REGION')
    expect(screen.getByText('서울 단지')).toBeVisible()
  })
})

function repositoryWith(result: IntegratedSearchResponse): IntegratedSearchRepository {
  return { search: vi.fn().mockResolvedValue(result) }
}

function response(
  announcements: readonly SearchResultItem[],
  complexes: readonly SearchResultItem[],
  regions: readonly SearchResultItem[],
  failures: IntegratedSearchResponse['failures'] = [],
): IntegratedSearchResponse {
  return { announcements, complexes, failures, hasNext: false, page: 0, query: '서울', regions, size: 5, totalCount: null }
}

function item(
  type: SearchResultItem['type'],
  id: string,
  title: string,
): SearchResultItem {
  return {
    applicationStatus: type === 'ANNOUNCEMENT' ? 'APPLYING' : null,
    id,
    latitude: type === 'ANNOUNCEMENT' ? null : 37.5,
    longitude: type === 'ANNOUNCEMENT' ? null : 127,
    publishedAt: type === 'ANNOUNCEMENT' ? '2026-09-01' : null,
    regionCode: type === 'REGION' ? id : null,
    subtitle: '서울특별시 중구',
    title,
    type,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}
