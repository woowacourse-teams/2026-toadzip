import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { NaverMapProps } from '../maps/naver/NaverMap.tsx'
import type { PublicHousingRepository } from './api/publicHousingRepository.ts'
import type {
  ComplexListItem,
  ComplexPage,
  MapBounds,
  MapComplex,
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
    render(<PublicHousingExplorer repository={repository} />)

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
    render(<PublicHousingExplorer repository={repository} />)

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
    render(<PublicHousingExplorer repository={repository} />)

    fireEvent.click(screen.getByRole('button', { name: '넓은 영역 알림' }))

    expect(repository.findMapComplexes).not.toHaveBeenCalled()
    expect(repository.findComplexPage).not.toHaveBeenCalled()
    expect(
      screen.getByText('요청 범위가 넓습니다. 지도를 조금 더 확대해 주세요.'),
    ).toBeVisible()
  })

  it('지도 마커 선택과 목록 카드 선택 상태를 같은 ID로 동기화한다', async () => {
    const repository = createRepository()
    render(<PublicHousingExplorer repository={repository} />)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(
      screen.getByRole('button', { name: '서울가람 행복주택 지도 마커 선택' }),
    )

    expect(
      screen.getByRole('article', { name: '서울가람 행복주택' }),
    ).toHaveAttribute('aria-current', 'true')
  })
})

function FakeNaverMap({
  markers = [],
  onMarkerSelect,
  onViewportChange,
}: NaverMapProps) {
  const [, setRevision] = useState(0)

  return (
    <section aria-label="공공임대주택 지도">
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
  findComplexPage: ReturnType<typeof vi.fn>
  findMapComplexes: ReturnType<typeof vi.fn>
} {
  return {
    findComplexPage: vi.fn().mockResolvedValue(complexPage()),
    findMapComplexes: vi.fn().mockResolvedValue([mapComplex()]),
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
