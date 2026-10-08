import {
  act,
  fireEvent,
  render,
  screen,
  within,
  waitFor,
} from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { NaverMapProps } from '../maps/naver/NaverMap.tsx'
import { housingMapRepository } from './api/housingMapRepository.ts'
import { defaultPublicHousingRepository } from './api/defaultPublicHousingRepository.ts'
import { MINIMAL_PUBLIC_HOUSING_SNAPSHOT } from './testing/minimalPublicHousingSnapshot.ts'
import {
  DefaultPublicHousingExplorer,
  LocalPublicHousingExplorer,
} from './DefaultPublicHousingExplorer.tsx'

vi.mock('../maps/naver/NaverMap.tsx', () => ({
  default: ({ representation, markers, onViewportChange }: NaverMapProps) => (
    <section aria-label="공공임대주택 지도">
      <output data-testid="map-representation">{representation}</output>
      <output data-testid="map-complex-ids">
        {markers?.map((marker) => marker.id).join(',')}
      </output>
      <button type="button" onClick={() => onViewportChange?.({
        bounds: {
          southWestLat: 37.4,
          southWestLng: 126.8,
          northEastLat: 37.7,
          northEastLng: 127.1,
        },
        center: { latitude: 37.55, longitude: 126.95 },
        zoom: 9,
      })}>
        지도 범위 알림
      </button>
    </section>
  ),
}))

afterEach(() => {
  vi.restoreAllMocks()
})

const SNAPSHOT = MINIMAL_PUBLIC_HOUSING_SNAPSHOT

describe('DefaultPublicHousingExplorer', () => {
  it('일반 실행에서는 서버 지도 repository를 연결한다', async () => {
    const findMap = vi.spyOn(housingMapRepository, 'findMap').mockResolvedValue({
      resolvedStage: 1,
      representation: 'AGGREGATE',
      policyVersion: 'test',
      regionDatasetVersion: 'test',
      nodes: [],
    })
    vi.spyOn(defaultPublicHousingRepository, 'findComplexPage').mockResolvedValue({
      items: [], nextCursor: null, hasNext: false,
      raw: { items: [], nextCursor: null, hasNext: false },
    })
    render(
      <MemoryRouter>
        <DefaultPublicHousingExplorer />
      </MemoryRouter>,
    )

    fireEvent.click(screen.getByRole('button', { name: '지도 범위 알림' }))

    await waitFor(() => expect(findMap).toHaveBeenCalledOnce())
    expect(screen.getByTestId('map-representation')).toHaveTextContent('AGGREGATE')
  })
})

describe('LocalPublicHousingExplorer', () => {
  it('snapshot 검증이 끝나기 전에는 Explorer를 열지 않는다', async () => {
    let resolveSnapshot: (value: unknown) => void = () => undefined
    const loadSnapshot = vi.fn(() => new Promise<unknown>((resolve) => {
      resolveSnapshot = resolve
    }))
    renderLocalExplorer(loadSnapshot)

    expect(screen.getByRole('status')).toHaveTextContent(
      '로컬 mock 데이터를 준비하고 있습니다.',
    )
    expect(screen.queryByRole('complementary', { name: '공공임대주택 검색 결과' }))
      .not.toBeInTheDocument()

    await act(async () => resolveSnapshot(SNAPSHOT))
    expect(await screen.findByRole('complementary', { name: '공공임대주택 검색 결과' }))
      .toBeVisible()
    expect(screen.queryByText('로컬 mock')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '지도 범위 알림' }))
    await waitFor(() => expect(screen.getByTestId('map-complex-ids')).toHaveTextContent('17'))
    expect(screen.getByTestId('map-representation')).toHaveTextContent('INDIVIDUAL')
  })

  it('snapshot의 통합 검색 결과를 선택해 외부 요청 없이 단지 상세를 연다', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    renderLocalExplorer(vi.fn().mockResolvedValue(SNAPSHOT))
    await screen.findByRole('complementary', { name: '공공임대주택 검색 결과' })

    fireEvent.change(screen.getByRole('searchbox', { name: '지역, 지하철역, 단지, 공고 검색' }), {
      target: { value: '서울가람' },
    })
    const result = await screen.findByRole('button', { name: /서울가람 행복주택/ })
    fireEvent.click(result)

    expect(await screen.findByRole('heading', { name: '서울가람 행복주택' })).toBeVisible()
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('파일 오류를 안내하고 사용자가 다시 불러올 수 있다', async () => {
    const loadSnapshot = vi.fn()
      .mockRejectedValueOnce(new Error('broken local file'))
      .mockResolvedValueOnce(SNAPSHOT)
    renderLocalExplorer(loadSnapshot)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '로컬 mock 데이터를 불러오지 못했습니다.',
    )
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByRole('complementary', { name: '공공임대주택 검색 결과' }))
      .toBeVisible()
    expect(loadSnapshot).toHaveBeenCalledTimes(2)
  })

  it('지도 이외의 API DTO 오류도 Explorer를 열기 전에 차단한다', async () => {
    const loadSnapshot = vi.fn().mockResolvedValue({
      ...SNAPSHOT,
      announcementListItems: [{
        ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT.announcementListItems[0],
        announcementId: null,
      }],
    })
    renderLocalExplorer(loadSnapshot)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '로컬 mock 데이터를 불러오지 못했습니다.',
    )
    expect(screen.queryByRole('complementary', { name: '공공임대주택 검색 결과' }))
      .not.toBeInTheDocument()
  })

  it('로컬 snapshot의 지역 정보로 2단계 지역 선택을 제공한다', async () => {
    const loadSnapshot = vi.fn().mockResolvedValue(SNAPSHOT)
    renderLocalExplorer(loadSnapshot)

    await screen.findByRole('complementary', { name: '공공임대주택 검색 결과' })
    fireEvent.click(screen.getByRole('button', { name: '상세 필터 열기' }))
    const detailFilter = screen.getByRole('region', { name: '상세 필터' })
    fireEvent.change(within(detailFilter).getByLabelText('시·도'), {
      target: { value: '11' },
    })

    const districtSelect = await within(detailFilter).findByLabelText('시·군·구')
    expect(await within(districtSelect).findByRole('option', { name: '중구' }))
      .toHaveValue('11140')
    expect(screen.queryByText('세부 지역을 불러오지 못했습니다.'))
      .not.toBeInTheDocument()
  })
})

function renderLocalExplorer(loadSnapshot: () => Promise<unknown>) {
  return render(
    <MemoryRouter>
      <LocalPublicHousingExplorer
        loadSnapshot={loadSnapshot}
      />
    </MemoryRouter>,
  )
}
