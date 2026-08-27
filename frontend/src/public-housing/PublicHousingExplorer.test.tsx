import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { useState } from 'react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { NaverMapProps } from '../maps/naver/NaverMap.tsx'
import {
  PublicHousingHttpError,
  type PublicHousingRepository,
} from './api/publicHousingRepository.ts'
import type {
  AnnouncementListItem,
  AnnouncementPage,
  ComplexDetail,
  ComplexListItem,
  ComplexPage,
  MapBounds,
  MapComplex,
  RawAnnouncementListItem,
  RawAnnouncementPage,
  RawComplexDetail,
  RawComplexListItem,
  RawComplexPage,
  RawMapComplex,
} from './model/publicHousing.ts'
import { PublicHousingExplorer } from './PublicHousingExplorer.tsx'

vi.mock('../maps/naver/NaverMap.tsx', () => ({
  default: FakeNaverMap,
}))

const INITIAL_BOUNDS: MapBounds = {
  southWestLat: 37.5,
  southWestLng: 126.9,
  northEastLat: 37.62,
  northEastLng: 127.1,
}

const NEXT_BOUNDS: MapBounds = {
  southWestLat: 37.4,
  southWestLng: 126.8,
  northEastLat: 37.55,
  northEastLng: 127,
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('PublicHousingExplorer', () => {
  it('처음 준비된 유효 영역은 지도와 목록에 같은 bounds로 한 번 적용한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    await waitFor(() => {
      expect(repository.findMapComplexes).toHaveBeenCalledOnce()
      expect(repository.findComplexPage).toHaveBeenCalledOnce()
    })
    expect(repository.findMapComplexes).toHaveBeenCalledWith(
      INITIAL_BOUNDS,
      expect.any(AbortSignal),
    )
    expect(repository.findComplexPage).toHaveBeenCalledWith(
      INITIAL_BOUNDS,
      null,
      20,
      expect.any(AbortSignal),
    )
    expect(
      await screen.findByRole('heading', { name: '서울가람 행복주택' }),
    ).toBeVisible()
    expect(screen.getByText('1곳')).toBeVisible()
  })

  it('이후 지도 이동은 명시적으로 다시 찾을 때만 요청한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))

    expect(repository.findMapComplexes).toHaveBeenCalledOnce()
    expect(repository.findComplexPage).toHaveBeenCalledOnce()

    fireEvent.click(
      screen.getByRole('button', { name: '이 지역에서 다시 찾기' }),
    )

    await waitFor(() => {
      expect(repository.findMapComplexes).toHaveBeenCalledTimes(2)
      expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
    })
    expect(repository.findMapComplexes).toHaveBeenLastCalledWith(
      NEXT_BOUNDS,
      expect.any(AbortSignal),
    )
  })

  it('너무 넓은 영역에서는 요청하지 않고 확대 안내를 표시한다', () => {
    const repository = createRepository()
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '넓은 영역 알림' }))

    expect(repository.findMapComplexes).not.toHaveBeenCalled()
    expect(repository.findComplexPage).not.toHaveBeenCalled()
    expect(
      screen.getByText('요청 범위가 넓습니다. 지도를 조금 더 확대해 주세요.'),
    ).toBeVisible()
  })

  it('지도 마커 선택과 목록 카드 선택 상태를 같은 ID로 동기화한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(
      screen.getByRole('button', { name: '서울가람 행복주택 지도 마커 선택' }),
    )

    expect(
      screen.getByRole('article', { name: '서울가람 행복주택' }),
    ).toHaveAttribute('aria-current', 'true')
    expect(repository.findComplexDetail).toHaveBeenCalledWith(
      '17',
      expect.any(AbortSignal),
    )
  })

  it('단지 카드에서 상세 A를 열고 닫으면 URL과 focus가 원래 카드로 돌아간다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    const openButton = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    openButton.focus()
    fireEvent.click(openButton)

    const detailHeading = await screen.findByRole('heading', {
      name: '서울가람 행복주택',
      level: 2,
    })
    expect(detailHeading).toHaveFocus()
    expect(screen.getByTestId('location-search')).toHaveTextContent(
      '?complexId=17',
    )
    expect(screen.getByText('카메라 37.5,126.9')).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))

    await waitFor(() => expect(openButton).toHaveFocus())
    expect(screen.queryByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).not.toBeInTheDocument()
    expect(screen.getByTestId('location-search')).toBeEmptyDOMElement()
  })

  it('단지 상세를 연 카드가 숨겨지면 닫을 때 현재 결과 탭으로 focus가 돌아간다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    const openButton = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    openButton.focus()
    fireEvent.click(openButton)
    await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })

    const announcementTab = screen.getByRole('tab', { name: '공고 목록' })
    fireEvent.click(announcementTab)
    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))

    await waitFor(() => expect(announcementTab).toHaveFocus())
    expect(announcementTab).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).not.toBeInTheDocument()
  })

  it('내부에서 연 상세은 뒤로 갔다가 앞으로 온 뒤에도 닫기로 원래 목록에 복귀한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    }))
    await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })

    fireEvent.click(screen.getByRole('button', { name: '브라우저 뒤로' }))
    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toBeEmptyDOMElement()
    })
    fireEvent.click(screen.getByRole('button', { name: '브라우저 앞으로' }))
    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toHaveTextContent(
        '?complexId=17',
      )
    })

    fireEvent.click(await screen.findByRole('button', {
      name: '단지 상세 닫기',
    }))
    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toBeEmptyDOMElement()
    })
  })

  it('직접 URL 상세는 목록 요청 전에도 조회하고 임시 마커를 추가한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?source=shared&complexId=17')

    expect(await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).toBeVisible()
    expect(screen.getByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })).toBeVisible()
    expect(repository.findComplexPage).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))

    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toHaveTextContent(
        '?source=shared',
      )
    })
  })

  it('없는 단지와 일시 오류를 구분하고 일시 오류만 다시 시도한다', async () => {
    const repository = createRepository()
    repository.findComplexDetail.mockRejectedValueOnce(
      new PublicHousingHttpError(404, {
        code: 'COMPLEX_NOT_FOUND',
        message: '단지를 찾을 수 없습니다.',
        traceId: 'trace-test',
      }),
    )
    renderExplorer(repository, '/?complexId=999')

    expect(await screen.findByText('단지를 찾을 수 없습니다.')).toBeVisible()
    const detailState = screen.getByRole('complementary', {
      name: '단지 상세 정보',
    })
    expect(detailState).toHaveFocus()
    expect(screen.queryByRole('button', { name: '다시 시도' }))
      .not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: '공공임대주택 지도' })).toBeVisible()

    fireEvent.keyDown(detailState, { key: 'Escape' })
    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toBeEmptyDOMElement()
    })
  })

  it('일시 오류는 현재 URL의 같은 단지 상세를 다시 요청한다', async () => {
    const repository = createRepository()
    repository.findComplexDetail
      .mockRejectedValueOnce(new Error('연결이 잠시 끊겼습니다.'))
      .mockResolvedValueOnce(complexDetail())
    renderExplorer(repository, '/?complexId=17')

    expect(await screen.findByText('단지 상세를 불러오지 못했습니다.'))
      .toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).toBeVisible()
    expect(repository.findComplexDetail).toHaveBeenCalledTimes(2)
  })

  it('닫힌 뒤 늦게 끝난 상세 응답은 화면을 다시 열지 않는다', async () => {
    let resolveDetail: (detail: ComplexDetail) => void = () => undefined
    const repository = createRepository()
    repository.findComplexDetail.mockReturnValueOnce(
      new Promise<ComplexDetail>((resolve) => {
        resolveDetail = resolve
      }),
    )
    renderExplorer(repository, '/?complexId=17')

    expect(await screen.findByText('단지 상세를 불러오고 있습니다.'))
      .toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
    await act(async () => resolveDetail(complexDetail()))

    expect(screen.queryByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).not.toBeInTheDocument()
    expect(screen.getByTestId('location-search')).toBeEmptyDOMElement()
  })

  it('잘못된 complexId는 API로 보내지 않고 unrelated query만 남겨 정규화한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?source=shared&complexId=017&complexId=18')

    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toHaveTextContent(
        '?source=shared',
      )
    })
    expect(repository.findComplexDetail).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('단지 상세 정보')).not.toBeInTheDocument()
  })

  it('공고 탭을 처음 열 때 지도와 분리된 공고 cursor 목록을 불러와 유지한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    const announcementTab = screen.getByRole('tab', { name: '공고 목록' })
    fireEvent.click(announcementTab)

    const announcementHeading = await screen.findByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
    })
    expect(announcementHeading).toBeVisible()
    expect(announcementTab).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('1건')).toBeVisible()
    expect(screen.getByText('공급 세대수')).toBeVisible()
    expect(screen.getByText('75세대')).toBeVisible()
    expect(screen.queryByText('공급 단지')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /입주자 모집 공고 상세 보기/ }))
      .not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: '공공임대주택 지도' })).toBeVisible()
    expect(repository.findAnnouncementPage).toHaveBeenCalledWith(
      null,
      20,
      expect.any(AbortSignal),
    )

    fireEvent.click(screen.getByRole('tab', { name: '단지 목록' }))
    fireEvent.click(announcementTab)

    expect(repository.findAnnouncementPage).toHaveBeenCalledOnce()
    expect(screen.getByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
    })).toBeVisible()
  })

  it('공고 첫 로딩은 완료된 0건으로 알리지 않는다', () => {
    const repository = createRepository()
    repository.findAnnouncementPage.mockReturnValueOnce(
      new Promise<AnnouncementPage>(() => undefined),
    )
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))

    const count = screen.getByLabelText('공고 목록 불러오는 중')
    expect(count).toHaveTextContent('불러오는 중')
    expect(count).not.toHaveTextContent('0건')
  })

  it('결과 탭은 좌우 방향키로 전환하고 활성 탭만 tab stop으로 둔다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    const complexTab = screen.getByRole('tab', { name: '단지 목록' })
    const announcementTab = screen.getByRole('tab', { name: '공고 목록' })

    complexTab.focus()
    fireEvent.keyDown(complexTab, { key: 'ArrowRight' })

    expect(announcementTab).toHaveFocus()
    expect(announcementTab).toHaveAttribute('aria-selected', 'true')
    expect(announcementTab).toHaveAttribute('tabindex', '0')
    expect(complexTab).toHaveAttribute('tabindex', '-1')
    await screen.findByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
    })

    fireEvent.keyDown(announcementTab, { key: 'ArrowLeft' })
    expect(complexTab).toHaveFocus()
    expect(complexTab).toHaveAttribute('aria-selected', 'true')
  })

  it('공고 탭에서 viewport를 적용해도 지도와 단지만 갱신하고 공고는 유지한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    await screen.findByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
    })

    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    expect(screen.getByText(
      '공고는 전국 최신순이며 지도·단지만 이 영역으로 갱신합니다.',
    )).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '지도·단지 다시 찾기' }))

    await waitFor(() => {
      expect(repository.findMapComplexes).toHaveBeenCalledTimes(2)
      expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
    })
    expect(repository.findAnnouncementPage).toHaveBeenCalledOnce()
    expect(screen.getByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
    })).toBeVisible()
  })

  it('공고 탭의 스크롤을 보존하고 지도 마커는 단지 탭 상세로 연다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    const announcementPanel = await screen.findByRole('tabpanel', {
      name: '공고 목록',
    })
    const scroll = announcementPanel.querySelector<HTMLElement>(
      '.housing-results__scroll',
    )
    if (scroll === null) {
      throw new Error('공고 목록 scroll container를 찾을 수 없습니다.')
    }
    scroll.scrollTop = 120
    fireEvent.click(screen.getByRole('tab', { name: '단지 목록' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    expect(scroll.scrollTop).toBe(120)

    fireEvent.click(within(
      screen.getByRole('region', { name: '공공임대주택 지도' }),
    ).getByRole('button', { name: '서울가람 행복주택 지도 마커 선택' }))

    expect(screen.getByRole('tab', { name: '단지 목록' }))
      .toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).toBeVisible()
  })
})

function renderExplorer(
  repository: PublicHousingRepository,
  initialEntry = '/',
) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <PublicHousingExplorer repository={repository} />
      <LocationSearch />
    </MemoryRouter>,
  )
}

function LocationSearch() {
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <div>
      <output data-testid="location-search">{location.search}</output>
      <button type="button" onClick={() => navigate(-1)}>브라우저 뒤로</button>
      <button type="button" onClick={() => navigate(1)}>브라우저 앞으로</button>
    </div>
  )
}

function FakeNaverMap({
  cameraTarget,
  markers = [],
  onMarkerSelect,
  onViewportChange,
}: NaverMapProps) {
  const [, setRevision] = useState(0)

  return (
    <section aria-label="공공임대주택 지도">
      {cameraTarget && (
        <output>
          카메라 {cameraTarget.latitude},{cameraTarget.longitude}
        </output>
      )}
      <button
        type="button"
        onClick={() =>
          onViewportChange?.({ bounds: INITIAL_BOUNDS, zoom: 14 })
        }
      >
        초기 영역 알림
      </button>
      <button
        type="button"
        onClick={() => onViewportChange?.({ bounds: NEXT_BOUNDS, zoom: 14 })}
      >
        다음 영역 알림
      </button>
      <button
        type="button"
        onClick={() =>
          onViewportChange?.({
            bounds: {
              southWestLat: 36,
              southWestLng: 125,
              northEastLat: 38,
              northEastLng: 128,
            },
            zoom: 14,
          })
        }
      >
        넓은 영역 알림
      </button>
      {markers.map((marker) => (
        <button
          key={marker.id}
          type="button"
          onClick={() => {
            onMarkerSelect?.(marker.id)
            setRevision((current) => current + 1)
          }}
        >
          {marker.name} 지도 마커 선택
        </button>
      ))}
    </section>
  )
}

function createRepository(): PublicHousingRepository & {
  findAnnouncementPage: ReturnType<typeof vi.fn>
  findComplexDetail: ReturnType<typeof vi.fn>
  findComplexPage: ReturnType<typeof vi.fn>
  findMapComplexes: ReturnType<typeof vi.fn>
} {
  return {
    findAnnouncementPage: vi.fn().mockResolvedValue(announcementPage()),
    findComplexDetail: vi.fn().mockResolvedValue(complexDetail()),
    findComplexPage: vi.fn().mockResolvedValue(complexPage()),
    findMapComplexes: vi.fn().mockResolvedValue([mapComplex()]),
  }
}

function announcementPage(): AnnouncementPage {
  const item = announcementListItem()
  const raw: RawAnnouncementPage = {
    hasNext: false,
    items: [item.raw],
    nextCursor: null,
  }
  return { hasNext: false, items: [item], nextCursor: null, raw }
}

function announcementListItem(): AnnouncementListItem {
  const raw: RawAnnouncementListItem = {
    actualCompetitionRate: null,
    agency: { code: 'LH', name: '한국토지주택공사' },
    announcementId: 201,
    applicationEndAt: '2026-08-30',
    applicationStartAt: '2026-08-28',
    applicationStatus: 'APPLYING',
    dDay: 2,
    predictedCompetitionRate: null,
    publicationType: 'ORIGINAL',
    publishedAt: '2026-08-20',
    recruitmentType: 'NEW',
    regionNames: ['경기도 성남시'],
    rentalType: 'HAPPY_HOUSING',
    supplyComplexCount: 2,
    supplyHouseholdCount: 75,
    thumbnailImageUrl: null,
    title: '성남 청년 행복주택 입주자 모집 공고',
    viewCount: 614,
  }
  return { ...raw, announcementId: '201', raw }
}

function complexDetail(): ComplexDetail {
  const raw = {} as RawComplexDetail
  return {
    address: {
      latitude: 37.5,
      longitude: 126.9,
      regionName: '서울특별시 중구',
      roadAddress: '서울특별시 중구 세종대로 110',
    },
    agency: { code: 'LH', name: '한국토지주택공사' },
    buildingType: 'APARTMENT',
    completionDate: '2020-01-01',
    complexId: '17',
    corridorType: 'STAIR',
    currentAnnouncements: [],
    hasElevator: true,
    heatingType: 'INDIVIDUAL',
    housingTypes: [],
    images: [],
    moveOutCountLastYear: 7,
    name: '서울가람 행복주택',
    overviewImageUrl: null,
    raw,
    rentalType: 'HAPPY_HOUSING',
    totalHouseholdCount: 100,
    totalParkingCount: 80,
  }
}

function complexPage(): ComplexPage {
  const rawItem = rawComplexListItem()
  const raw: RawComplexPage = {
    hasNext: false,
    items: [rawItem],
    nextCursor: null,
  }
  return {
    hasNext: false,
    items: [complexListItem(rawItem)],
    nextCursor: null,
    raw,
  }
}

function complexListItem(raw: RawComplexListItem): ComplexListItem {
  return {
    agency: raw.agency,
    complexId: String(raw.complexId),
    depositMax: raw.depositMax,
    depositMin: raw.depositMin,
    exclusiveAreaMax: raw.exclusiveAreaMax,
    exclusiveAreaMin: raw.exclusiveAreaMin,
    monthlyRentMax: raw.monthlyRentMax,
    monthlyRentMin: raw.monthlyRentMin,
    name: raw.name,
    raw,
    regionName: raw.regionName,
    rentalType: raw.rentalType,
    representativeAnnouncement: {
      announcementId: '117',
      applicationEndAt: '2026-08-30',
      applicationStatus: 'APPLYING',
      dDay: 2,
      publicationType: 'ORIGINAL',
    },
    thumbnailImageUrl: null,
  }
}

function rawComplexListItem(): RawComplexListItem {
  return {
    agency: { code: 'LH', name: '한국토지주택공사' },
    complexId: 17,
    depositMax: 70_000_000,
    depositMin: 50_000_000,
    exclusiveAreaMax: 44.87,
    exclusiveAreaMin: 36.12,
    monthlyRentMax: 300_000,
    monthlyRentMin: 200_000,
    name: '서울가람 행복주택',
    regionName: '서울특별시 중구',
    rentalType: 'HAPPY_HOUSING',
    representativeAnnouncement: {
      announcementId: 117,
      applicationEndAt: '2026-08-30',
      applicationStatus: 'APPLYING',
      dDay: 2,
      publicationType: 'ORIGINAL',
    },
    thumbnailImageUrl: null,
  }
}

function mapComplex(): MapComplex {
  const raw: RawMapComplex = {
    agency: { code: 'LH', name: '한국토지주택공사' },
    complexId: 17,
    depositMax: 70_000_000,
    depositMin: 50_000_000,
    exclusiveAreaMax: 44.87,
    exclusiveAreaMin: 36.12,
    latitude: 37.56,
    longitude: 126.98,
    monthlyRentMax: 300_000,
    monthlyRentMin: 200_000,
    name: '서울가람 행복주택',
    rentalType: 'HAPPY_HOUSING',
  }
  return {
    ...raw,
    complexId: '17',
    raw,
  }
}
