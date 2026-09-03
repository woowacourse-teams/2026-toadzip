import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { useRef, useState } from 'react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { NaverMapProps } from '../maps/naver/NaverMap.tsx'
import {
  PublicHousingHttpError,
  type PublicHousingRepository,
} from './api/publicHousingRepository.ts'
import type { PublicHousingRegionRepository } from './api/publicHousingRegionRepository.ts'
import type {
  AnnouncementDetail,
  AnnouncementListItem,
  AnnouncementPage,
  ComplexDetail,
  ComplexListItem,
  ComplexPage,
  MapBounds,
  MapComplex,
  RawAnnouncementListItem,
  RawAnnouncementDetail,
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

const INITIAL_CENTER = {
  latitude: 37.56,
  longitude: 127,
}

const NEXT_CENTER = {
  latitude: 37.475,
  longitude: 126.9,
}

const PRECISION_CENTER = {
  latitude: 37.5666103,
  longitude: 126.9783882,
}

const SEOUL_CITY_HALL_CENTER = {
  latitude: 37.5666103,
  longitude: 126.9783882,
}

const TEST_REGIONS = [
  {
    regionCode: '11',
    provinceName: '서울특별시',
    districtName: null,
    displayName: '서울특별시 전체',
  },
  {
    regionCode: '11140',
    provinceName: '서울특별시',
    districtName: '중구',
    displayName: '서울특별시 중구',
  },
  {
    regionCode: '41',
    provinceName: '경기도',
    districtName: null,
    displayName: '경기도 전체',
  },
  {
    regionCode: '41130',
    provinceName: '경기도',
    districtName: '성남시',
    displayName: '경기도 성남시',
  },
  {
    regionCode: '41135',
    provinceName: '경기도',
    districtName: '성남시 분당구',
    displayName: '경기도 성남시 분당구',
  },
] as const

afterEach(() => {
  vi.restoreAllMocks()
})

describe('PublicHousingExplorer', () => {
  it('연속 idle은 마지막 유효 영역만 300ms 뒤 요청한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))

    await waitFor(() => {
      expect(repository.findMapComplexes).toHaveBeenCalledOnce()
      expect(repository.findComplexPage).toHaveBeenCalledOnce()
    })
    expect(repository.findMapComplexes).toHaveBeenCalledWith(
      NEXT_BOUNDS,
      expect.any(AbortSignal),
    )
  })

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

  it('단지 필터는 지도 우상단의 토픽별 도구모음으로 공고 탭에서도 유지한다', () => {
    const repository = createRepository()
    renderExplorer(repository)

    const complexFilter = screen.getByRole('toolbar', {
      name: '단지 검색 필터',
    })
    expect(complexFilter.closest('main')).toHaveClass(
      'housing-map-workspace',
    )
    expect(within(complexFilter).getAllByRole('button').map(
      (button) => button.getAttribute('aria-label'),
    )).toEqual([
      '지역 필터 열기',
      '임대유형 필터 열기',
      '모집상태 필터 열기',
      '공급기관 필터 열기',
      '모집유형 필터 열기',
      '가격 필터 열기',
      '전용면적 필터 열기',
      '준공년도 필터 열기',
    ])
    expect(within(screen.getByRole('complementary', {
      name: '공공임대주택 검색 결과',
    })).queryByRole('toolbar', {
      name: '단지 검색 필터',
    })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))

    expect(complexFilter).toBeVisible()
    expect(within(screen.getByRole('tabpanel', {
      name: '공고 목록',
    })).getByRole('region', {
      name: '공고 검색 필터',
    })).toBeVisible()
  })

  it('가격 토픽만 적용해도 기존 지역·임대유형 조건을 보존한다', async () => {
    const repository = createRepository()
    renderExplorer(
      repository,
      '/?complexRegionCode=11&complexRentalTypes=NATIONAL_RENTAL',
    )
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))
    fireEvent.change(screen.getByRole('slider', {
      name: '임대보증금 최솟값',
    }), { target: { value: '100000000' } })
    fireEvent.change(screen.getByRole('slider', {
      name: '임대보증금 최댓값',
    }), { target: { value: '200000000' } })
    fireEvent.click(screen.getByRole('button', { name: '가격 필터 적용' }))

    await waitFor(() => {
      expect(repository.findMapComplexes).toHaveBeenCalledTimes(2)
      expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
    })
    expect(repository.findMapComplexes).toHaveBeenLastCalledWith(
      INITIAL_BOUNDS,
      expect.any(AbortSignal),
      {
        maxDeposit: 200_000_000,
        minDeposit: 100_000_000,
        regionCode: '11',
        rentalTypes: ['NATIONAL_RENTAL'],
      },
    )
    const search = new URLSearchParams(
      screen.getByTestId('location-search').textContent ?? '',
    )
    expect(search.get('complexRegionCode')).toBe('11')
    expect(search.getAll('complexRentalTypes')).toEqual(['NATIONAL_RENTAL'])
    expect(search.get('complexMinDeposit')).toBe('100000000')
    expect(search.get('complexMaxDeposit')).toBe('200000000')
  })

  it('단지 필터는 적용한 조건을 URL에 보존하고 지도와 단지 목록에 함께 전달한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    fireEvent.click(screen.getByRole('button', { name: '지역 필터 열기' }))
    fireEvent.change(screen.getByLabelText('시·도'), {
      target: { value: '11' },
    })
    fireEvent.click(screen.getByRole('button', { name: '지역 필터 적용' }))

    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 열기' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '국민임대' }))
    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 적용' }))

    fireEvent.click(screen.getByRole('button', { name: '모집상태 필터 열기' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '접수중' }))
    fireEvent.click(screen.getByRole('button', { name: '모집상태 필터 적용' }))

    fireEvent.click(screen.getByRole('button', { name: '공급기관 필터 열기' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'LH' }))
    fireEvent.click(screen.getByRole('button', { name: '공급기관 필터 적용' }))

    fireEvent.click(screen.getByRole('button', { name: '모집유형 필터 열기' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '신규 모집' }))
    fireEvent.click(screen.getByRole('button', { name: '모집유형 필터 적용' }))

    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))
    fireEvent.change(screen.getByRole('slider', {
      name: '임대보증금 최솟값',
    }), {
      target: { value: '100000000' },
    })
    fireEvent.change(screen.getByRole('slider', {
      name: '임대보증금 최댓값',
    }), {
      target: { value: '300000000' },
    })
    fireEvent.change(screen.getByRole('slider', {
      name: '월 임대료 최솟값',
    }), {
      target: { value: '100000' },
    })
    fireEvent.change(screen.getByRole('slider', {
      name: '월 임대료 최댓값',
    }), {
      target: { value: '500000' },
    })
    fireEvent.click(screen.getByRole('button', { name: '가격 필터 적용' }))

    fireEvent.click(screen.getByRole('button', { name: '전용면적 필터 열기' }))
    fireEvent.change(screen.getByRole('slider', {
      name: '전용면적 최솟값',
    }), {
      target: { value: '33' },
    })
    fireEvent.change(screen.getByRole('slider', {
      name: '전용면적 최댓값',
    }), {
      target: { value: '66' },
    })
    fireEvent.click(screen.getByRole('button', { name: '전용면적 필터 적용' }))

    await waitFor(() => {
      expect(repository.findMapComplexes).toHaveBeenCalledTimes(8)
      expect(repository.findComplexPage).toHaveBeenCalledTimes(8)
    })
    repository.findComplexPage
      .mockResolvedValueOnce(complexPageWithNext())
      .mockResolvedValueOnce(complexPageFor(18, '서울마루 국민임대'))

    fireEvent.click(screen.getByRole('button', { name: '준공년도 필터 열기' }))
    fireEvent.change(screen.getByLabelText('최소 준공년도'), {
      target: { value: '2015' },
    })
    fireEvent.change(screen.getByLabelText('최대 준공년도'), {
      target: { value: '2026' },
    })
    fireEvent.click(screen.getByRole('button', { name: '준공년도 필터 적용' }))

    const expectedFilters = {
      agencyCodes: ['LH'],
      applicationStatuses: ['APPLYING'],
      builtYearFrom: 2015,
      builtYearTo: 2026,
      maxDeposit: 300_000_000,
      maxExclusiveArea: 66,
      maxMonthlyRent: 500_000,
      minDeposit: 100_000_000,
      minExclusiveArea: 33,
      minMonthlyRent: 100_000,
      recruitmentTypes: ['NEW'],
      regionCode: '11',
      rentalTypes: ['NATIONAL_RENTAL'],
    }
    await waitFor(() => {
      expect(repository.findMapComplexes).toHaveBeenCalledTimes(9)
      expect(repository.findComplexPage).toHaveBeenCalledTimes(9)
    })
    expect(repository.findMapComplexes).toHaveBeenLastCalledWith(
      INITIAL_BOUNDS,
      expect.any(AbortSignal),
      expectedFilters,
    )
    expect(repository.findComplexPage).toHaveBeenLastCalledWith(
      INITIAL_BOUNDS,
      null,
      20,
      expect.any(AbortSignal),
      expectedFilters,
    )
    const search = new URLSearchParams(
      screen.getByTestId('location-search').textContent ?? '',
    )
    expect(search.get('complexRegionCode')).toBe('11')
    expect(search.getAll('complexRentalTypes')).toEqual(['NATIONAL_RENTAL'])
    expect(search.get('complexMinDeposit')).toBe('100000000')
    expect(search.get('complexBuiltYearTo')).toBe('2026')

    fireEvent.click(screen.getByRole('button', { name: '단지 더 보기' }))
    expect(await screen.findByRole('heading', {
      name: '서울마루 국민임대',
    })).toBeVisible()
    expect(repository.findComplexPage).toHaveBeenLastCalledWith(
      INITIAL_BOUNDS,
      'cursor-2',
      20,
      expect.any(AbortSignal),
      expectedFilters,
    )

    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    fireEvent.click(screen.getByRole('tab', { name: '단지 목록' }))
    fireEvent.click(screen.getByRole('button', { name: '지역 필터 열기' }))
    expect(within(screen.getByRole('region', {
      name: '지역 필터',
    })).getByLabelText('시·도')).toHaveValue('11')
    fireEvent.keyDown(document, { key: 'Escape' })

    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 열기' }))
    const rentalFilter = screen.getByRole('region', {
      name: '임대유형 필터',
    })
    expect(within(rentalFilter).getByRole('checkbox', { name: '국민임대' }))
      .toBeChecked()
    fireEvent.click(within(rentalFilter).getByRole('button', {
      name: '임대유형 필터 초기화',
    }))
    await waitFor(() => {
      expect(repository.findMapComplexes).toHaveBeenCalledTimes(10)
      expect(repository.findComplexPage).toHaveBeenCalledTimes(11)
    })
    const resetSearch = new URLSearchParams(
      screen.getByTestId('location-search').textContent ?? '',
    )
    expect(resetSearch.get('complexRegionCode')).toBe('11')
    expect(resetSearch.getAll('complexRentalTypes')).toEqual([])
    expect(repository.findMapComplexes).toHaveBeenLastCalledWith(
      INITIAL_BOUNDS,
      expect.any(AbortSignal),
      {
        agencyCodes: ['LH'],
        applicationStatuses: ['APPLYING'],
        builtYearFrom: 2015,
        builtYearTo: 2026,
        maxDeposit: 300_000_000,
        maxExclusiveArea: 66,
        maxMonthlyRent: 500_000,
        minDeposit: 100_000_000,
        minExclusiveArea: 33,
        minMonthlyRent: 100_000,
        recruitmentTypes: ['NEW'],
        regionCode: '11',
      },
    )
  })

  it('공유 URL의 시군구와 복수 조건을 폼에서 다시 적용해도 보존한다', async () => {
    const repository = createRepository()
    renderExplorer(
      repository,
      '/?complexRegionCode=41135'
        + '&complexRentalTypes=NATIONAL_RENTAL'
        + '&complexRentalTypes=HAPPY_HOUSING'
        + '&complexAgencyCodes=LH'
        + '&complexAgencyCodes=GH',
    )
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    fireEvent.click(screen.getByRole('button', { name: '지역 필터 열기' }))
    const regionFilter = screen.getByRole('region', {
      name: '지역 필터',
    })
    expect(within(regionFilter).getByLabelText('시·도')).toHaveValue('41')
    await waitFor(() => {
      expect(within(regionFilter).getByLabelText('시·군·구'))
        .toHaveValue('41135')
    })
    fireEvent.click(within(regionFilter).getByRole('button', {
      name: '지역 필터 적용',
    }))

    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 열기' }))
    const rentalFilter = screen.getByRole('region', {
      name: '임대유형 필터',
    })
    expect(within(rentalFilter).getByRole('checkbox', {
      name: '국민임대',
    })).toBeChecked()
    expect(within(rentalFilter).getByRole('checkbox', {
      name: '행복주택',
    })).toBeChecked()
    fireEvent.click(within(rentalFilter).getByRole('button', {
      name: '임대유형 필터 적용',
    }))

    fireEvent.click(screen.getByRole('button', { name: '공급기관 필터 열기' }))
    const agencyFilter = screen.getByRole('region', {
      name: '공급기관 필터',
    })
    expect(within(agencyFilter).getByRole('checkbox', { name: 'LH' }))
      .toBeChecked()
    expect(within(agencyFilter).getByRole('checkbox', { name: 'GH' }))
      .toBeChecked()
    fireEvent.click(within(agencyFilter).getByRole('button', {
      name: '공급기관 필터 적용',
    }))

    const search = new URLSearchParams(
      screen.getByTestId('location-search').textContent ?? '',
    )
    expect(search.get('complexRegionCode')).toBe('41135')
    expect(new Set(search.getAll('complexRentalTypes'))).toEqual(new Set([
      'NATIONAL_RENTAL',
      'HAPPY_HOUSING',
    ]))
    expect(search.getAll('complexAgencyCodes')).toEqual(['LH', 'GH'])
  })

  it('지도 idle 대기 중 단지 필터를 바꿔도 이전 조건 요청이 덮어쓰지 않는다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 열기' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '국민임대' }))
    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 적용' }))

    await waitFor(() => {
      expect(repository.findMapComplexes).toHaveBeenCalledTimes(2)
      expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
    })
    await act(async () => new Promise((resolve) => {
      window.setTimeout(resolve, 350)
    }))

    const filters = { rentalTypes: ['NATIONAL_RENTAL'] }
    expect(repository.findMapComplexes).toHaveBeenCalledTimes(2)
    expect(repository.findMapComplexes).toHaveBeenLastCalledWith(
      NEXT_BOUNDS,
      expect.any(AbortSignal),
      filters,
    )
    expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
    expect(repository.findComplexPage).toHaveBeenLastCalledWith(
      NEXT_BOUNDS,
      null,
      20,
      expect.any(AbortSignal),
      filters,
    )
  })

  it('단지 범위 손잡이는 서로 교차하지 않도록 상대 값에 맞춘다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))
    const minimum = screen.getByRole('slider', {
      name: '임대보증금 최솟값',
    })
    const maximum = screen.getByRole('slider', {
      name: '임대보증금 최댓값',
    })
    fireEvent.change(maximum, { target: { value: '100000000' } })
    fireEvent.change(minimum, { target: { value: '300000000' } })

    expect(minimum).toHaveValue('100000000')
    expect(screen.getByRole('status', {
      name: '임대보증금 선택 범위',
    })).toHaveTextContent('1억~1억')

    fireEvent.click(screen.getByRole('button', { name: '가격 필터 적용' }))
    await waitFor(() => {
      expect(repository.findMapComplexes).toHaveBeenCalledTimes(2)
      expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
    })
    expect(repository.findMapComplexes).toHaveBeenLastCalledWith(
      INITIAL_BOUNDS,
      expect.any(AbortSignal),
      { maxDeposit: 100_000_000, minDeposit: 100_000_000 },
    )
    const search = new URLSearchParams(
      screen.getByTestId('location-search').textContent ?? '',
    )
    expect(search.get('complexMinDeposit')).toBe('100000000')
    expect(search.get('complexMaxDeposit')).toBe('100000000')
  })

  it('공고 필터는 단지 필터와 독립적으로 공고 목록에만 적용한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    await screen.findByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
    })
    const complexRequestCount = repository.findComplexPage.mock.calls.length
    const mapRequestCount = repository.findMapComplexes.mock.calls.length

    fireEvent.click(screen.getByRole('button', { name: '공고 필터 열기' }))
    const announcementFilter = screen.getByRole('region', {
      name: '공고 검색 필터',
    })
    fireEvent.change(within(announcementFilter).getByLabelText('시·도'), {
      target: { value: '41' },
    })
    fireEvent.click(screen.getByRole('checkbox', { name: '행복주택' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '접수예정' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'GH' }))
    fireEvent.click(screen.getByRole('checkbox', {
      name: '예비입주자 모집',
    }))
    fireEvent.click(screen.getByRole('button', { name: '공고 필터 적용' }))

    await waitFor(() => {
      expect(repository.findAnnouncementPage).toHaveBeenCalledTimes(2)
    })
    expect(repository.findAnnouncementPage).toHaveBeenLastCalledWith(
      null,
      20,
      expect.any(AbortSignal),
      {
        agencyCodes: ['GH'],
        applicationStatuses: ['BEFORE_APPLICATION'],
        recruitmentTypes: ['WAITLIST'],
        regionCode: '41',
        rentalTypes: ['HAPPY_HOUSING'],
      },
    )
    expect(repository.findComplexPage).toHaveBeenCalledTimes(complexRequestCount)
    expect(repository.findMapComplexes).toHaveBeenCalledTimes(mapRequestCount)
    const search = new URLSearchParams(
      screen.getByTestId('location-search').textContent ?? '',
    )
    expect(search.get('announcementRegionCode')).toBe('41')
    expect(search.get('complexRegionCode')).toBeNull()

    fireEvent.click(screen.getByRole('tab', { name: '단지 목록' }))
    fireEvent.click(screen.getByRole('button', { name: '지역 필터 열기' }))
    expect(within(screen.getByRole('region', {
      name: '지역 필터',
    })).getByLabelText('시·도')).toHaveValue('')
    fireEvent.keyDown(document, { key: 'Escape' })
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    expect(within(announcementFilter).getByLabelText('시·도'))
      .toHaveValue('41')
  })

  it('지도 응답의 임대 조건을 정보형 마커 표시값으로 변환한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    const marker = await screen.findByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })
    expect(marker).toHaveAttribute('data-agency-label', 'LH')
    expect(marker).toHaveAttribute('data-rental-type-label', '행복주택')
    expect(marker).toHaveAttribute('data-area-label', '36.12~44.87㎡')
    expect(marker).toHaveAttribute('data-monthly-rent-label', '20만~30만 원')
  })

  it('이후 지도 이동도 idle 뒤 자동으로 마지막 영역을 요청한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))

    await waitFor(() => {
      expect(repository.findMapComplexes).toHaveBeenCalledTimes(2)
      expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
    })
    expect(repository.findMapComplexes).toHaveBeenLastCalledWith(
      NEXT_BOUNDS,
      expect.any(AbortSignal),
    )
    expect(screen.queryByRole('button', {
      name: '이 지역에서 다시 찾기',
    })).not.toBeInTheDocument()
  })

  it('새 영역 재조회 중 기존 단지 목록 레이아웃을 유지한다', async () => {
    const repository = createRepository()
    const nextMap = createDeferred<readonly MapComplex[]>()
    const nextPage = createDeferred<ComplexPage>()
    repository.findMapComplexes
      .mockResolvedValueOnce([mapComplex()])
      .mockReturnValueOnce(nextMap.promise)
    repository.findComplexPage
      .mockResolvedValueOnce(complexPage())
      .mockReturnValueOnce(nextPage.promise)
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const initialCard = await screen.findByRole('article', {
      name: '서울가람 행복주택',
    })
    const initialList = screen.getByRole('list')
    const scrollContainer = initialList.parentElement
    expect(scrollContainer).not.toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await waitFor(() => {
      expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
    })

    expect(initialCard).toBeVisible()
    expect(screen.getByRole('list')).toBe(initialList)
    expect(scrollContainer).toHaveAttribute('aria-busy', 'true')
    expect(scrollContainer?.children).toHaveLength(1)
    expect(scrollContainer?.firstElementChild).toBe(initialList)
    expect(within(scrollContainer as HTMLElement).queryByText(
      '기존 결과를 유지하면서 새 지역을 확인하고 있습니다.',
    )).not.toBeInTheDocument()
    expect(within(screen.getByRole('complementary', {
      name: '공공임대주택 검색 결과',
    })).getByRole('status')).toHaveTextContent(
      '기존 결과를 유지하면서 새 지역을 확인하고 있습니다.',
    )

    nextMap.resolve([mapComplexFor(18, '서울마루 국민임대')])
    nextPage.resolve(complexPageFor(18, '서울마루 국민임대'))
    expect(await screen.findByRole('heading', {
      name: '서울마루 국민임대',
    })).toBeVisible()
  })

  it('이미 적용한 동일 request key는 다시 요청하지 않는다', async () => {
    const repository = createRepository()
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await act(async () => new Promise((resolve) => {
      window.setTimeout(resolve, 350)
    }))

    expect(repository.findMapComplexes).toHaveBeenCalledOnce()
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
  })

  it('너무 넓은 영역에서는 요청하지 않고 이전 목록과 마커를 숨긴다', async () => {
    const repository = createRepository()
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '넓은 영역 알림' }))

    expect(repository.findMapComplexes).toHaveBeenCalledOnce()
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
    expect(
      screen.getByText('요청 범위가 넓습니다. 지도를 조금 더 확대해 주세요.'),
    ).toBeVisible()
    expect(screen.getByLabelText(
      '단지 조회를 위한 지도 확대 필요',
    )).toHaveTextContent('확대 필요')
    expect(screen.queryByRole('heading', {
      name: '서울가람 행복주택',
    })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })).not.toBeInTheDocument()
  })

  it('지도와 목록의 새 영역 응답을 둘 다 받은 뒤 한 번에 교체한다', async () => {
    const repository = createRepository()
    const nextMap = createDeferred<readonly MapComplex[]>()
    const nextPage = createDeferred<ComplexPage>()
    repository.findMapComplexes
      .mockResolvedValueOnce([mapComplex()])
      .mockReturnValueOnce(nextMap.promise)
    repository.findComplexPage
      .mockResolvedValueOnce(complexPage())
      .mockReturnValueOnce(nextPage.promise)
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await waitFor(() => {
      expect(repository.findMapComplexes).toHaveBeenCalledTimes(2)
      expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
    })

    nextMap.resolve([mapComplexFor(18, '서울마루 국민임대')])
    await act(async () => Promise.resolve())

    expect(screen.getByRole('heading', {
      name: '서울가람 행복주택',
    })).toBeVisible()
    expect(screen.queryByRole('button', {
      name: '서울마루 국민임대 지도 마커 선택',
    })).not.toBeInTheDocument()

    nextPage.resolve(complexPageFor(18, '서울마루 국민임대'))

    expect(await screen.findByRole('heading', {
      name: '서울마루 국민임대',
    })).toBeVisible()
    expect(screen.getByRole('button', {
      name: '서울마루 국민임대 지도 마커 선택',
    })).toBeVisible()
  })

  it('새 영역은 이전 요청을 abort하고 늦은 응답을 폐기한다', async () => {
    const repository = createRepository()
    const previousMap = createDeferred<readonly MapComplex[]>()
    const previousPage = createDeferred<ComplexPage>()
    const nextMap = createDeferred<readonly MapComplex[]>()
    const nextPage = createDeferred<ComplexPage>()
    repository.findMapComplexes
      .mockReturnValueOnce(previousMap.promise)
      .mockReturnValueOnce(nextMap.promise)
    repository.findComplexPage
      .mockReturnValueOnce(previousPage.promise)
      .mockReturnValueOnce(nextPage.promise)
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findComplexPage).toHaveBeenCalledOnce())
    const previousSignal = repository.findComplexPage.mock.calls[0][3]
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await waitFor(() => expect(repository.findComplexPage).toHaveBeenCalledTimes(2))
    expect(previousSignal).toHaveProperty('aborted', true)

    await act(async () => {
      nextMap.resolve([mapComplexFor(18, '서울마루 국민임대')])
      nextPage.resolve(complexPageFor(18, '서울마루 국민임대'))
    })
    expect(await screen.findByRole('heading', {
      name: '서울마루 국민임대',
    })).toBeVisible()

    await act(async () => {
      previousMap.resolve([mapComplex()])
      previousPage.resolve(complexPage())
    })
    expect(screen.queryByRole('heading', {
      name: '서울가람 행복주택',
    })).not.toBeInTheDocument()
  })

  it('새 영역 한쪽이 실패하면 직전 지도와 목록 쌍을 유지하고 재시도한다', async () => {
    const repository = createRepository()
    repository.findMapComplexes
      .mockResolvedValueOnce([mapComplex()])
      .mockRejectedValueOnce(new Error('지도 조회 실패'))
      .mockResolvedValueOnce([mapComplexFor(18, '서울마루 국민임대')])
    repository.findComplexPage
      .mockResolvedValueOnce(complexPage())
      .mockResolvedValueOnce(complexPageFor(18, '서울마루 국민임대'))
      .mockResolvedValueOnce(complexPageFor(18, '서울마루 국민임대'))
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await screen.findByText('지도 조회 실패')

    expect(screen.getAllByRole('alert')).toHaveLength(1)
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    fireEvent.click(screen.getByRole('tab', { name: '단지 목록' }))
    expect(screen.getByRole('heading', {
      name: '서울가람 행복주택',
    })).toBeVisible()
    expect(screen.getByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByRole('heading', {
      name: '서울마루 국민임대',
    })).toBeVisible()
    expect(repository.findComplexPage).toHaveBeenLastCalledWith(
      NEXT_BOUNDS,
      null,
      20,
      expect.any(AbortSignal),
    )
  })

  it('최초 통합 요청 실패 뒤 같은 영역을 다시 시도한다', async () => {
    const repository = createRepository()
    repository.findMapComplexes
      .mockRejectedValueOnce(new Error('최초 조회 실패'))
      .mockResolvedValueOnce([mapComplex()])
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    expect(await screen.findByText(
      '단지 목록을 불러오지 못했습니다.',
    )).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByRole('heading', {
      name: '서울가람 행복주택',
    })).toBeVisible()
    expect(repository.findMapComplexes).toHaveBeenCalledTimes(2)
    expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
  })

  it('더 보기 실패는 첫 페이지 대신 실패한 cursor를 다시 요청한다', async () => {
    const repository = createRepository()
    repository.findComplexPage
      .mockResolvedValueOnce(complexPageWithNext())
      .mockRejectedValueOnce(new Error('다음 페이지 실패'))
      .mockResolvedValueOnce(complexPageFor(18, '서울마루 국민임대'))
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '단지 더 보기' }))
    await screen.findByText('다음 페이지 실패')
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByRole('heading', {
      name: '서울마루 국민임대',
    })).toBeVisible()
    expect(repository.findComplexPage).toHaveBeenNthCalledWith(
      2,
      INITIAL_BOUNDS,
      'cursor-2',
      20,
      expect.any(AbortSignal),
    )
    expect(repository.findComplexPage).toHaveBeenNthCalledWith(
      3,
      INITIAL_BOUNDS,
      'cursor-2',
      20,
      expect.any(AbortSignal),
    )
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

  it('단지 초기 조회와 다음 페이지 존재를 count와 지도 busy에 반영한다', async () => {
    const repository = createRepository()
    const mapDeferred = createDeferred<readonly MapComplex[]>()
    const pageDeferred = createDeferred<ComplexPage>()
    repository.findMapComplexes.mockReturnValueOnce(mapDeferred.promise)
    repository.findComplexPage.mockReturnValueOnce(pageDeferred.promise)
    renderExplorer(repository)

    expect(screen.getByLabelText('단지 목록 불러오는 중')).toHaveTextContent(
      '불러오는 중',
    )
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    await waitFor(() => expect(repository.findMapComplexes).toHaveBeenCalled())
    expect(
      screen.getByRole('region', { name: '공공임대주택 지도' }),
    ).toHaveAttribute('aria-busy', 'true')

    await act(async () => {
      mapDeferred.resolve([mapComplex()])
      pageDeferred.resolve(complexPageWithNext())
    })

    expect(await screen.findByLabelText(
      '현재 불러온 단지 1곳 이상',
    )).toHaveTextContent('1곳 이상')
    fireEvent.click(within(screen.getByRole('toolbar', {
      name: '모바일 단지 검색 필터',
    })).getByRole('button', { name: /^전체 단지 필터 열기/ }))
    expect(screen.getByRole('button', { name: '단지 1곳 보기' }))
      .toBeInTheDocument()
    expect(
      screen.getByRole('region', { name: '공공임대주택 지도' }),
    ).toHaveAttribute('aria-busy', 'false')
    expect(within(screen.getByRole('complementary', {
      name: '공공임대주택 검색 결과',
    })).getAllByRole('status')).toHaveLength(1)
  })

  it('카드와 marker hover 및 focus를 연결하고 marker 선택만 카드를 노출한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    const card = await screen.findByRole('article', {
      name: '서울가람 행복주택',
    })
    const marker = screen.getByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })
    const cardAction = within(card).getByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    let panelHiddenAtScroll: boolean | null = null
    const scrollIntoView = vi.fn()
    scrollIntoView.mockImplementation(() => {
      panelHiddenAtScroll = card.closest('[role="tabpanel"]')
        ?.hasAttribute('hidden') ?? null
    })
    card.scrollIntoView = scrollIntoView

    fireEvent.mouseEnter(card)
    expect(marker).toHaveAttribute('data-highlighted', 'true')
    fireEvent.focus(cardAction)
    fireEvent.mouseLeave(card)
    expect(marker).toHaveAttribute('data-highlighted', 'true')

    fireEvent.mouseEnter(marker)
    expect(card).toHaveAttribute('data-hovered', 'true')
    expect(scrollIntoView).not.toHaveBeenCalled()
    fireEvent.mouseLeave(marker)
    expect(card).toHaveAttribute('data-hovered', 'true')
    expect(marker).toHaveAttribute('data-highlighted', 'true')
    fireEvent.blur(cardAction)
    expect(card).not.toHaveAttribute('data-hovered')
    expect(marker).not.toHaveAttribute('data-highlighted')
    fireEvent.focus(marker)
    expect(card).toHaveAttribute('data-hovered', 'true')
    fireEvent.blur(marker)
    expect(card).not.toHaveAttribute('data-hovered')

    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    expect(screen.getByRole('tab', { name: '공고 목록' }))
      .toHaveAttribute('aria-selected', 'true')
    fireEvent.click(marker)

    await waitFor(() => expect(scrollIntoView).toHaveBeenCalledWith({
      block: 'nearest',
    }))
    expect(panelHiddenAtScroll).toBe(false)
    expect(scrollIntoView.mock.invocationCallOrder[0]).toBeLessThan(
      repository.findComplexDetail.mock.invocationCallOrder[0],
    )
    expect(await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).toBeVisible()
  })

  it('상세가 열린 선택 단지는 영역 밖에 유지하고 닫힌 뒤 새 결과에서 정리한다', async () => {
    const repository = createRepository()
    repository.findMapComplexes
      .mockResolvedValueOnce([mapComplex()])
      .mockResolvedValueOnce([mapComplexFor(18, '서울마루 국민임대')])
      .mockResolvedValueOnce([mapComplex()])
    repository.findComplexPage
      .mockResolvedValueOnce(complexPage())
      .mockResolvedValueOnce(complexPageFor(18, '서울마루 국민임대'))
      .mockResolvedValueOnce(complexPage())
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    const initialMarker = await screen.findByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })
    fireEvent.click(initialMarker)
    await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })

    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))

    await screen.findByRole('heading', { name: '서울마루 국민임대' })
    expect(screen.getByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })).toHaveAttribute('data-selected', 'true')

    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
    await waitFor(() => expect(screen.queryByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).not.toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    const returnedMarker = await screen.findByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })
    expect(returnedMarker).not.toHaveAttribute('data-selected')
  })

  it('로컬 mock도 운영 marker 경로와 boolean badge만 사용한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?complexId=17', true)

    const detailHeading = await screen.findByRole('heading', {
      name: '서울가람 행복주택',
      level: 2,
    })
    await waitFor(() => expect(detailHeading).toHaveFocus())
    expect(screen.getByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })).toBeVisible()
    expect(screen.getByText('로컬 mock')).toBeVisible()
  })

  it('기존 지도 query는 최초 카메라에 한 번 반영하고 URL에서 제거한다', async () => {
    const repository = createRepository()
    renderExplorer(
      repository,
      '/?source=shared&mapLat=37.58123&mapLng=126.99123&mapZoom=15.75',
    )

    await waitFor(() => {
      expect(screen.getByText('카메라 37.58123,126.99123')).toBeVisible()
      expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('15.75')
      expectCurrentSearch({ source: 'shared' })
    })
  })

  it.each([
    '?source=shared&mapLat=37.5#map',
    '?source=shared&mapLat=37.5&mapLat=37.6&mapLng=127&mapZoom=14#map',
    '?source=shared&mapLat=91&mapLng=127&mapZoom=14#map',
  ])(
    '부분, 중복 또는 범위 밖 지도 query %s는 모두 제거하고 기본 카메라로 복구한다',
    async (initialEntry) => {
      const repository = createRepository()
      renderExplorer(repository, initialEntry)

      await waitFor(() => {
        expectCurrentSearch({ source: 'shared' })
      })
      expect(screen.getByTestId('location-hash')).toHaveTextContent('#map')
      expect(screen.getByText(
        `카메라 ${SEOUL_CITY_HALL_CENTER.latitude},${SEOUL_CITY_HALL_CENTER.longitude}`,
      )).toBeVisible()
      expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('14')
    },
  )

  it('지도 idle은 URL을 바꾸지 않고 무관 query, hash, state를 보존한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?source=shared#results')
    fireEvent.click(screen.getByRole('button', { name: '공유 상태 설정' }))
    await waitFor(() => {
      expect(screen.getByTestId('location-state')).toHaveTextContent(
        'shared-state',
      )
    })

    fireEvent.click(screen.getByRole('button', { name: '정밀 영역 알림' }))

    await waitFor(() => {
      expectCurrentSearch({ source: 'shared' })
    })
    expect(screen.getByTestId('location-hash')).toHaveTextContent('#results')
    expect(screen.getByTestId('location-state')).toHaveTextContent(
      'shared-state',
    )

    fireEvent.click(screen.getByRole('button', { name: '브라우저 뒤로' }))
    await act(async () => Promise.resolve())
    expectCurrentSearch({ source: 'shared' })
  })

  it('동일한 camera idle은 URL과 history, 조회 세대를 반복하지 않는다', async () => {
    const repository = createRepository()
    renderExplorer(
      repository,
      '/?mapLat=37.56661&mapLng=126.97839&mapZoom=14.26',
    )
    const locationKey = screen.getByTestId('location-key').textContent

    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    await waitFor(() => {
      expect(repository.findMapComplexes).toHaveBeenCalledOnce()
      expect(repository.findComplexPage).toHaveBeenCalledOnce()
    })
    expect(screen.getByTestId('location-key').textContent).toBe(locationKey)

    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    await act(async () => new Promise((resolve) => {
      window.setTimeout(resolve, 350)
    }))
    expect(repository.findMapComplexes).toHaveBeenCalledOnce()
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
    expectCurrentSearch({})
  })

  it('상세 entry만 URL에 추가하고 닫기와 앞뒤 이동은 로컬 지도 위치를 복원한다', async () => {
    const repository = createRepository()
    renderExplorer(
      repository,
      '/?source=shared&mapLat=37.58123&mapLng=126.99123&mapZoom=15.50#results',
    )
    fireEvent.click(screen.getByRole('button', { name: '공유 상태 설정' }))
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    const openButton = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    const listLocationKey = screen.getByTestId('location-key').textContent

    fireEvent.click(openButton)

    expect(await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).toBeVisible()
    expectCurrentSearch({
      complexId: '17',
      source: 'shared',
    })
    expect(screen.getByTestId('location-key').textContent)
      .not.toBe(listLocationKey)
    expect(screen.getByTestId('location-hash')).toHaveTextContent('#results')
    expect(screen.getByTestId('location-state')).toHaveTextContent(
      'shared-state',
    )

    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    await waitFor(() => {
      expectCurrentSearch({
        complexId: '17',
        source: 'shared',
      })
    })
    expect(repository.findComplexDetail).toHaveBeenCalledOnce()
    const detailLocationKey = screen.getByTestId('location-key').textContent

    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))

    await waitFor(() => {
      expectCurrentSearch({ source: 'shared' })
    })
    expect(screen.getByTestId('location-key').textContent)
      .toBe(listLocationKey)
    expect(screen.getByText('카메라 37.58123,126.99123')).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '브라우저 앞으로' }))

    await waitFor(() => {
      expectCurrentSearch({
        complexId: '17',
        source: 'shared',
      })
    })
    expect(screen.getByTestId('location-key').textContent)
      .toBe(detailLocationKey)
    expect(screen.getByText('카메라 37.5,126.9')).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '브라우저 뒤로' }))
    await waitFor(() => {
      expect(screen.getByText('카메라 37.58123,126.99123')).toBeVisible()
      expectCurrentSearch({ source: 'shared' })
    })
  })

  it('단지 카드에서 상세 A를 열고 닫으면 URL과 focus가 원래 카드로 돌아간다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    expect(screen.getByText('카메라 37.56,127')).toBeVisible()
    expectCurrentSearch({})

    const openButton = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    openButton.focus()
    fireEvent.click(openButton)

    const detailHeading = await screen.findByRole('heading', {
      name: '서울가람 행복주택',
      level: 2,
    })
    await waitFor(() => expect(detailHeading).toHaveFocus())
    expectCurrentSearch({ complexId: '17' })
    expect(screen.getByText('카메라 37.5,126.9')).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))

    await waitFor(() => expect(openButton).toHaveFocus())
    expect(screen.queryByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).not.toBeInTheDocument()
    expectCurrentSearch({})
    expect(screen.getByText('카메라 37.56,127')).toBeVisible()
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

  it('단지 카드의 대표 공고를 닫으면 원래 단지 탭과 action으로 돌아간다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    const openAnnouncement = screen.getByRole('button', {
      name: '대표 공고 상세 보기',
    })
    openAnnouncement.focus()
    fireEvent.click(openAnnouncement)
    expect(await screen.findByRole('complementary', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 정보',
    })).toBeVisible()
    expectCurrentSearch({ announcementId: '117' })

    fireEvent.click(screen.getByRole('button', { name: '공고 상세 닫기' }))

    await waitFor(() => expect(openAnnouncement).toHaveFocus())
    expect(screen.getByRole('tab', { name: '단지 목록' }))
      .toHaveAttribute('aria-selected', 'true')
    expectCurrentSearch({})
    expect(repository.findAnnouncementPage).not.toHaveBeenCalled()
  })

  it('내부에서 연 상세은 뒤로 갔다가 앞으로 온 뒤에도 닫기로 원래 목록에 복귀한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const openComplex = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    openComplex.focus()
    fireEvent.click(openComplex)
    await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })

    fireEvent.click(screen.getByRole('button', { name: '브라우저 뒤로' }))
    await waitFor(() => {
      expectCurrentSearch({})
    })
    fireEvent.click(screen.getByRole('button', { name: '브라우저 앞으로' }))
    await waitFor(() => {
      expectCurrentSearch({ complexId: '17' })
    })

    fireEvent.click(await screen.findByRole('button', {
      name: '단지 상세 닫기',
    }))
    await waitFor(() => {
      expectCurrentSearch({})
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
    expect(screen.getByRole('button', { name: /입주자 모집 공고 상세 보기/ }))
      .toBeVisible()
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

  it('공고 카드에서 상세 B를 열고 닫으면 URL과 focus가 원래 카드로 돌아간다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))

    const openButton = await screen.findByRole('button', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 보기',
    })
    openButton.focus()
    fireEvent.click(openButton)

    const detail = await screen.findByRole('complementary', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 정보',
    })
    expect(within(detail).getByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
      level: 2,
    })).toHaveFocus()
    expect(screen.getByTestId('location-search')).toHaveTextContent(
      '?announcementId=201',
    )

    fireEvent.click(screen.getByRole('button', { name: '공고 상세 닫기' }))

    await waitFor(() => expect(openButton).toHaveFocus())
    expect(screen.getByTestId('location-search')).toBeEmptyDOMElement()
    expect(screen.getByRole('tab', { name: '공고 목록' }))
      .toHaveAttribute('aria-selected', 'true')
  })

  it('공고 상세의 관련 단지를 닫고 공고를 닫으면 최초 공고 카드로 복귀한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    const openButton = await screen.findByRole('button', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 보기',
    })
    openButton.focus()
    fireEvent.click(openButton)

    const openComplex = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    openComplex.focus()
    fireEvent.click(openComplex)
    expect(await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).toBeVisible()
    expect(screen.getByTestId('location-search')).toHaveTextContent(
      '?complexId=17',
    )

    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
    expect(await screen.findByRole('complementary', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 정보',
    })).toBeVisible()
    const restoredOpenComplex = screen.getByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    await waitFor(() => expect(restoredOpenComplex).toHaveFocus())
    expect(screen.getByTestId('location-search')).toHaveTextContent(
      '?announcementId=201',
    )

    fireEvent.click(screen.getByRole('button', { name: '공고 상세 닫기' }))
    await waitFor(() => expect(openButton).toHaveFocus())
    expect(screen.getByTestId('location-search')).toBeEmptyDOMElement()
  })

  it('단지 상세의 현재 공고를 닫으면 단지 상세로 돌아간다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const complexButton = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    complexButton.focus()
    fireEvent.click(complexButton)

    const openAnnouncement = await screen.findByRole('button', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 보기',
    })
    openAnnouncement.focus()
    fireEvent.click(openAnnouncement)
    expect(await screen.findByRole('complementary', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 정보',
    })).toBeVisible()
    expect(screen.getByRole('tab', { name: '공고 목록' }))
      .toHaveAttribute('aria-selected', 'true')

    fireEvent.click(screen.getByRole('button', { name: '공고 상세 닫기' }))
    expect(await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).toBeVisible()
    expect(screen.getByRole('tab', { name: '단지 목록' }))
      .toHaveAttribute('aria-selected', 'true')
    const restoredOpenAnnouncement = screen.getByRole('button', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 보기',
    })
    await waitFor(() => expect(restoredOpenAnnouncement).toHaveFocus())
    expect(repository.findAnnouncementPage).not.toHaveBeenCalled()
  })

  it('교차 상세을 두 번 중첩해도 각 부모 상세의 호출 버튼으로 차례로 돌아간다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    fireEvent.click(await screen.findByRole('button', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 보기',
    }))

    const firstOpenComplex = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    firstOpenComplex.focus()
    fireEvent.click(firstOpenComplex)
    const firstOpenAnnouncement = await screen.findByRole('button', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 보기',
    })
    firstOpenAnnouncement.focus()
    fireEvent.click(firstOpenAnnouncement)

    fireEvent.click(screen.getByRole('button', { name: '공고 상세 닫기' }))
    const restoredOpenAnnouncement = await screen.findByRole('button', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 보기',
    })
    await waitFor(() => expect(restoredOpenAnnouncement).toHaveFocus())

    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
    const restoredOpenComplex = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    await waitFor(() => expect(restoredOpenComplex).toHaveFocus())
    expect(repository.findAnnouncementPage).toHaveBeenCalledOnce()
  })

  it('직접 announcementId URL은 목록 요청 전에 상세를 조회하고 닫을 때 무관 query를 보존한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?source=shared&announcementId=201')

    expect(await screen.findByRole('complementary', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 정보',
    })).toBeVisible()
    expect(repository.findAnnouncementDetail).toHaveBeenCalledWith(
      '201',
      expect.any(AbortSignal),
    )
    expect(repository.findAnnouncementPage).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '공고 상세 닫기' }))
    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toHaveTextContent(
        '?source=shared',
      )
    })
    const complexTab = screen.getByRole('tab', { name: '단지 목록' })
    expect(complexTab).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(complexTab).toHaveFocus())
    expect(repository.findAnnouncementPage).not.toHaveBeenCalled()
  })

  it('두 상세 ID가 함께 있으면 정규화하고 상세 API를 호출하지 않는다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?source=shared&complexId=17&announcementId=201')

    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toHaveTextContent(
        '?source=shared',
      )
    })
    expect(repository.findComplexDetail).not.toHaveBeenCalled()
    expect(repository.findAnnouncementDetail).not.toHaveBeenCalled()
  })

  it('공고 상세 404와 일시 오류를 구분하고 일시 오류만 재시도한다', async () => {
    const repository = createRepository()
    repository.findAnnouncementDetail
      .mockRejectedValueOnce(new PublicHousingHttpError(404, {
        code: 'ANNOUNCEMENT_NOT_FOUND',
        message: '공고를 찾을 수 없습니다.',
        traceId: 'trace-test',
      }))
    const view = renderExplorer(repository, '/?announcementId=999')

    expect(await screen.findByText('공고를 찾을 수 없습니다.')).toBeVisible()
    expect(screen.queryByRole('button', { name: '다시 시도' }))
      .not.toBeInTheDocument()

    view.unmount()
    repository.findAnnouncementDetail
      .mockRejectedValueOnce(new Error('연결이 잠시 끊겼습니다.'))
      .mockResolvedValueOnce(announcementDetail())
    renderExplorer(repository, '/?announcementId=201')
    expect(await screen.findByText('공고 상세를 불러오지 못했습니다.'))
      .toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByRole('complementary', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 정보',
    })).toBeVisible()
  })

  it('닫힌 뒤 늦게 끝난 공고 상세 응답은 화면을 다시 열지 않는다', async () => {
    let resolveDetail: (detail: AnnouncementDetail) => void = () => undefined
    const repository = createRepository()
    repository.findAnnouncementDetail.mockReturnValueOnce(
      new Promise<AnnouncementDetail>((resolve) => {
        resolveDetail = resolve
      }),
    )
    renderExplorer(repository, '/?announcementId=201')

    expect(await screen.findByText('공고 상세를 불러오고 있습니다.'))
      .toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '공고 상세 닫기' }))
    await act(async () => resolveDetail(announcementDetail()))

    expect(screen.queryByRole('complementary', {
      name: /공고 상세 정보/,
    })).not.toBeInTheDocument()
    expect(screen.getByTestId('location-search')).toBeEmptyDOMElement()
  })

  it('교차 상세의 브라우저 뒤로와 앞으로는 공고와 단지를 URL 순서대로 복원한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    fireEvent.click(await screen.findByRole('button', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 보기',
    }))
    const openComplex = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    openComplex.focus()
    fireEvent.click(openComplex)
    await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })

    fireEvent.click(screen.getByRole('button', { name: '브라우저 뒤로' }))
    expect(await screen.findByRole('complementary', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 정보',
    })).toBeVisible()
    const firstRestoredOpenComplex = screen.getByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    await waitFor(() => expect(firstRestoredOpenComplex).toHaveFocus())
    expect(screen.getByTestId('location-search')).toHaveTextContent(
      '?announcementId=201',
    )

    fireEvent.click(screen.getByRole('button', { name: '브라우저 앞으로' }))
    expect(await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).toBeVisible()
    expect(screen.getByTestId('location-search')).toHaveTextContent(
      '?complexId=17',
    )

    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
    expect(await screen.findByRole('complementary', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 정보',
    })).toBeVisible()
    const secondRestoredOpenComplex = screen.getByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    await waitFor(() => expect(secondRestoredOpenComplex).toHaveFocus())
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

  it('공고 탭에서 viewport가 자동 갱신돼도 공고는 유지한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    await screen.findByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
    })

    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))

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
  localMockEnabled = false,
  regionRepository = createRegionRepository(),
) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <PublicHousingExplorer
        localMockEnabled={localMockEnabled}
        regionRepository={regionRepository}
        repository={repository}
      />
      <LocationSearch />
    </MemoryRouter>,
  )
}

function createRegionRepository(): PublicHousingRegionRepository {
  return {
    search: vi.fn().mockImplementation((keyword: string) => Promise.resolve(
      TEST_REGIONS.filter(({ provinceName }) => provinceName === keyword),
    )),
  }
}

function LocationSearch() {
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <div>
      <output data-testid="location-search">{location.search}</output>
      <output data-testid="location-hash">{location.hash}</output>
      <output data-testid="location-key">{location.key}</output>
      <output data-testid="location-state">
        {JSON.stringify(location.state)}
      </output>
      <button type="button" onClick={() => navigate(-1)}>브라우저 뒤로</button>
      <button type="button" onClick={() => navigate(1)}>브라우저 앞으로</button>
      <button
        type="button"
        onClick={() => navigate({
          hash: location.hash,
          pathname: location.pathname,
          search: location.search,
        }, {
          replace: true,
          state: { source: 'shared-state' },
        })}
      >
        공유 상태 설정
      </button>
      <button type="button" onClick={() => navigate('/?complexId=18')}>
        단지 18 직접 열기
      </button>
    </div>
  )
}

function FakeNaverMap({
  cameraTarget,
  dataBusy = false,
  markers = [],
  onMarkerHighlight,
  onMarkerSelect,
  onViewportChange,
}: NaverMapProps) {
  const [, setRevision] = useState(0)
  const currentZoomRef = useRef(14)
  if (cameraTarget?.zoom !== undefined) {
    currentZoomRef.current = cameraTarget.zoom
  }

  return (
    <section aria-label="공공임대주택 지도" aria-busy={dataBusy}>
      {cameraTarget && (
        <output>
          카메라 {cameraTarget.latitude},{cameraTarget.longitude}
        </output>
      )}
      <output data-testid="map-camera-zoom">
        {cameraTarget?.zoom ?? currentZoomRef.current}
      </output>
      <button
        type="button"
        onClick={() =>
          onViewportChange?.({
            bounds: INITIAL_BOUNDS,
            center: INITIAL_CENTER,
            zoom: 14,
          })
        }
      >
        초기 영역 알림
      </button>
      <button
        type="button"
        onClick={() => onViewportChange?.({
          bounds: NEXT_BOUNDS,
          center: NEXT_CENTER,
          zoom: 14,
        })}
      >
        다음 영역 알림
      </button>
      <button
        type="button"
        onClick={() => onViewportChange?.({
          bounds: INITIAL_BOUNDS,
          center: PRECISION_CENTER,
          zoom: 14.256,
        })}
      >
        정밀 영역 알림
      </button>
      <button
        type="button"
        onClick={() => onViewportChange?.({
          bounds: INITIAL_BOUNDS,
          center: cameraTarget
            ? {
                latitude: cameraTarget.latitude,
                longitude: cameraTarget.longitude,
              }
            : INITIAL_CENTER,
          zoom: currentZoomRef.current,
        })}
      >
        현재 카메라 idle
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
            center: { latitude: 37, longitude: 126.5 },
            zoom: 14,
          })
        }
      >
        넓은 영역 알림
      </button>
      {markers.map((marker) => (
        <button
          key={`complex-${marker.id}`}
          className="housing-map-marker"
          type="button"
          data-complex-id={marker.id}
          data-highlighted={marker.highlighted || undefined}
          data-map-complex-marker="true"
          data-selected={marker.selected || undefined}
          data-agency-label={marker.agencyLabel}
          data-rental-type-label={marker.rentalTypeLabel}
          data-area-label={marker.areaLabel}
          data-monthly-rent-label={marker.monthlyRentLabel}
          onMouseEnter={() => onMarkerHighlight?.(marker.id)}
          onMouseLeave={() => onMarkerHighlight?.(null)}
          onFocus={() => onMarkerHighlight?.(marker.id)}
          onBlur={() => onMarkerHighlight?.(null)}
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

function expectCurrentSearch(expected: Record<string, string>) {
  const search = screen.getByTestId('location-search').textContent ?? ''
  expect(Object.fromEntries(new URLSearchParams(search))).toEqual(expected)
}

function createRepository(): PublicHousingRepository & {
  findAnnouncementDetail: ReturnType<typeof vi.fn>
  findAnnouncementPage: ReturnType<typeof vi.fn>
  findComplexDetail: ReturnType<typeof vi.fn>
  findComplexPage: ReturnType<typeof vi.fn>
  findMapComplexes: ReturnType<typeof vi.fn>
} {
  return {
    findAnnouncementDetail: vi.fn().mockResolvedValue(announcementDetail()),
    findAnnouncementPage: vi.fn().mockResolvedValue(announcementPage()),
    findComplexDetail: vi.fn().mockResolvedValue(complexDetail()),
    findComplexPage: vi.fn().mockResolvedValue(complexPage()),
    findMapComplexes: vi.fn().mockResolvedValue([mapComplex()]),
  }
}

function announcementDetail(): AnnouncementDetail {
  return {
    agency: { code: 'LH', name: '한국토지주택공사' },
    announcementId: '201',
    applicationEndAt: '2026-08-30',
    applicationStartAt: '2026-08-28',
    applicationStatus: 'APPLYING',
    attachments: [],
    competition: null,
    correctionOrCancellationReason: null,
    dDay: 2,
    documentLinkUrl: 'https://example.com/announcements/201',
    publicationType: 'ORIGINAL',
    publishedAt: '2026-08-20',
    raw: {} as RawAnnouncementDetail,
    receptionPlaces: [],
    recruitmentType: 'NEW',
    regionNames: ['경기도 성남시'],
    rentalType: 'HAPPY_HOUSING',
    schedules: [],
    supplyComplexCount: 1,
    supplyHouseholdCount: 75,
    supplyRows: [{
      complex: {
        address: '서울특별시 중구 세종대로 110',
        complexId: '17',
        name: '서울가람 행복주택',
        overviewImageUrl: null,
        totalHouseholdCount: 100,
      },
      housingType: {
        exclusiveArea: 36.12,
        floorPlan3dImageUrl: null,
        floorPlanImageUrl: null,
        housingTypeId: '301',
        name: '36A',
        supplyArea: 48.2,
      },
      occupancyExpectedYearMonth: '2026-12',
      sourceComplexName: '서울가람 행복주택',
      sourceHousingTypeName: '36A',
      supplyRowId: '401',
      supplyType: 'NEW',
      targets: [],
      totalSupplyHouseholdCount: 75,
    }],
    targets: ['청년'],
    title: '성남 청년 행복주택 입주자 모집 공고',
    viewCount: 614,
    winnerAnnouncementAt: '2026-09-10',
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
    currentAnnouncements: [{
      actualCompetitionRate: null,
      announcementId: '201',
      applicationEndAt: '2026-08-30',
      applicationStartAt: '2026-08-28',
      applicationStatus: 'APPLYING',
      dDay: 2,
      publicationType: 'ORIGINAL',
      targets: ['청년'],
      title: '성남 청년 행복주택 입주자 모집 공고',
    }],
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

function complexPageWithNext(): ComplexPage {
  const page = complexPage()
  return {
    ...page,
    hasNext: true,
    nextCursor: 'cursor-2',
    raw: {
      ...page.raw,
      hasNext: true,
      nextCursor: 'cursor-2',
    },
  }
}

function complexPageFor(complexId: number, name: string): ComplexPage {
  const rawItem = {
    ...rawComplexListItem(),
    complexId,
    name,
  }
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

function mapComplexFor(complexId: number, name: string): MapComplex {
  const current = mapComplex()
  const raw = { ...current.raw, complexId, name }
  return {
    ...current,
    complexId: String(complexId),
    name,
    raw,
  }
}

function createDeferred<T>() {
  let resolvePromise: (value: T) => void = () => {
    throw new Error('Promise resolve 함수가 준비되지 않았습니다.')
  }
  const promise = new Promise<T>((resolve) => {
    resolvePromise = resolve
  })
  return { promise, resolve: resolvePromise }
}
