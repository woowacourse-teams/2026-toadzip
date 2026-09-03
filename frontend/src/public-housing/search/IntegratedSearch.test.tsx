import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { IntegratedSearch } from './IntegratedSearch.tsx'
import type {
  IntegratedSearchRepository,
  IntegratedSearchResponse,
  SearchResultItem,
} from './integratedSearchRepository.ts'

describe('IntegratedSearch', () => {
  it('두 글자를 입력하면 공고 단지 지역으로 구분하고 선택한 공고를 전달한다', async () => {
    const announcement = item('ANNOUNCEMENT', '1', '서울 행복주택 공고')
    const complex = item('COMPLEX', '2', '서울 행복주택 단지')
    const region = item('REGION', '11', '서울특별시 전체')
    const repository = repositoryWith(response([announcement], [complex], [region]))
    const onSelect = vi.fn()
    render(<IntegratedSearch repository={repository} onSelect={onSelect} />)

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '  서울  ' } })

    expect(await screen.findByRole('heading', { name: '공고' })).toBeVisible()
    expect(screen.getByRole('heading', { name: '단지' })).toBeVisible()
    expect(screen.getByRole('heading', { name: '지역' })).toBeVisible()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '주택 정보' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '위치' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /서울 행복주택 공고/ }))
    expect(onSelect).toHaveBeenCalledWith(announcement)
  })

  it('늦게 끝난 이전 요청이 최신 검색 결과를 덮어쓰지 않는다', async () => {
    const first = deferred<IntegratedSearchResponse>()
    const second = deferred<IntegratedSearchResponse>()
    const search = vi.fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
    render(<IntegratedSearch repository={{ search }} onSelect={vi.fn()} />)

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })
    await waitFor(() => expect(search).toHaveBeenCalledTimes(1))
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '부산' } })
    await waitFor(() => expect(search).toHaveBeenCalledTimes(2))
    second.resolve(response([], [item('COMPLEX', '2', '부산 단지')], []))
    expect(await screen.findByText('부산 단지')).toBeVisible()
    first.resolve(response([], [item('COMPLEX', '1', '서울 단지')], []))

    await waitFor(() => expect(screen.queryByText('서울 단지')).not.toBeInTheDocument())
    expect(screen.getByText('부산 단지')).toBeVisible()
  })

  it('검색어를 지운 뒤 늦게 끝난 요청 결과를 표시하지 않는다', async () => {
    const pending = deferred<IntegratedSearchResponse>()
    const search = vi.fn().mockReturnValue(pending.promise)
    render(<IntegratedSearch repository={{ search }} onSelect={vi.fn()} />)

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })
    await waitFor(() => expect(search).toHaveBeenCalledOnce())
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '' } })
    pending.resolve(response([], [item('COMPLEX', '1', '서울 단지')], []))

    expect(await screen.findByText('두 글자 이상 입력해 주세요.')).toBeVisible()
    await waitFor(() => expect(screen.queryByText('서울 단지')).not.toBeInTheDocument())
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
    await waitFor(() => expect(repository.search).toHaveBeenCalledTimes(2))
  })

  it('검색어가 바뀌면 입력 중 결과 첫 페이지로 돌아간다', async () => {
    const result = { ...response([], [item('COMPLEX', '1', '서울 단지')], []), hasNext: true }
    const repository = repositoryWith(result)
    render(<IntegratedSearch repository={repository} onSelect={vi.fn()} />)
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })
    fireEvent.click(await screen.findByRole('button', { name: '전체 결과 보기' }))
    await waitFor(() => expect(repository.search).toHaveBeenLastCalledWith(
      '서울', false, 0, expect.any(AbortSignal),
    ))
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    await waitFor(() => expect(repository.search).toHaveBeenLastCalledWith(
      '서울', false, 1, expect.any(AbortSignal),
    ))

    fireEvent.change(screen.getByRole('searchbox'), {
      target: { value: '부산' },
    })

    await waitFor(() => expect(repository.search).toHaveBeenLastCalledWith(
      '부산', true, 0, expect.any(AbortSignal),
    ))
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
  return {
    announcements,
    complexes,
    failures,
    hasNext: false,
    page: 0,
    query: '서울',
    regions,
    size: 8,
  }
}

function item(
  type: SearchResultItem['type'],
  id: string,
  title: string,
): SearchResultItem {
  return {
    applicationStatus: type === 'ANNOUNCEMENT' ? 'APPLYING' : null,
    id,
    latitude: type === 'COMPLEX' ? 37.5 : null,
    longitude: type === 'COMPLEX' ? 127 : null,
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
