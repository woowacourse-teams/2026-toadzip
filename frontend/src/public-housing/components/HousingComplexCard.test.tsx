import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  HousingComplexCard,
  type HousingComplexCardAnnouncement,
  type HousingComplexCardData,
} from './HousingComplexCard'

const BASE_ANNOUNCEMENT: HousingComplexCardAnnouncement = {
  announcementId: '117',
  applicationStatus: 'APPLYING',
  applicationEndAt: '2026-08-30',
  dDay: 2,
}

const BASE_COMPLEX: HousingComplexCardData = {
  agencyCode: 'LH',
  agencyName: '한국토지주택공사',
  complexId: '17',
  depositMax: 70_000_000,
  depositMin: 50_000_000,
  exclusiveAreaMax: 44.87,
  exclusiveAreaMin: 36.12,
  monthlyRentMax: 300_000,
  monthlyRentMin: 200_000,
  name: '서울가람 행복주택',
  regionName: '서울특별시 중구',
  rentalTypeLabel: '행복주택',
  representativeAnnouncement: BASE_ANNOUNCEMENT,
  thumbnailImageUrl: 'https://example.com/complex.jpg',
}

function renderCard(complex: HousingComplexCardData = BASE_COMPLEX) {
  const onSelect = vi.fn()
  const onOpenAnnouncement = vi.fn()
  const result = render(
    <HousingComplexCard
      complex={complex}
      onSelect={onSelect}
      onOpenAnnouncement={onOpenAnnouncement}
    />,
  )

  return { ...result, onOpenAnnouncement, onSelect }
}

function complexWith(
  changes: Partial<HousingComplexCardData>,
): HousingComplexCardData {
  return { ...BASE_COMPLEX, ...changes }
}

describe('HousingComplexCard', () => {
  it('상태와 기관부터 제목, 지역, 금액, 면적 순서로 표시한다', () => {
    renderCard()

    const card = screen.getByRole('article')
    const announcement = within(card).getByRole('group', { name: '대표 공고' })
    const conditions = within(card).getByRole('group', { name: '주요 임대 조건' })
    const image = within(card).getByRole('img', {
      name: `${BASE_COMPLEX.name} 단지 대표 이미지`,
    })
    const announcementAction = within(announcement).getByRole('button', {
      name: '대표 공고 상세 보기',
    })

    expect(within(announcement).getByText('접수중')).toBeInTheDocument()
    expect(within(announcement).getByLabelText('접수 마감까지 2일'))
      .toHaveTextContent('D-2')
    expect(announcementAction).toHaveAccessibleDescription(
      '대표 공고 상태 접수중, 접수 마감까지 2일',
    )
    expect(within(card).getByText('LH')).toBeInTheDocument()
    expect(within(card).getByText(BASE_COMPLEX.rentalTypeLabel)).toBeInTheDocument()
    expect(within(card).getByRole('heading', { name: BASE_COMPLEX.name }))
      .toBeInTheDocument()
    expect(within(card).getByText(BASE_COMPLEX.regionName)).toBeInTheDocument()
    expect(within(conditions).getByText('임대보증금')).toBeInTheDocument()
    expect(within(conditions).getByText('5,000만원 ~')).toBeInTheDocument()
    expect(within(conditions).getByLabelText('임대보증금 5,000만원부터 7,000만원까지'))
      .toBeInTheDocument()
    expect(within(conditions).getByText('월 임대료')).toBeInTheDocument()
    expect(within(conditions).getByText('20만원 ~')).toBeInTheDocument()
    expect(within(conditions).getByText('전용')).toBeInTheDocument()
    expect(within(conditions).getByText('36.12m² ~')).toBeInTheDocument()
    expect(within(conditions).getByLabelText('전용 36.12m²부터 44.87m²까지'))
      .toBeInTheDocument()
    expect(image).toHaveAttribute('src', 'https://example.com/complex.jpg')
    expect(image).toHaveAttribute('loading', 'lazy')
    expect(image).toHaveAttribute('decoding', 'async')
    expect(within(card).queryByText(/접수 마감일|준공/)).not.toBeInTheDocument()
  })

  it.each([
    [true, true],
    [true, false],
    [false, true],
    [false, false],
  ])('대표 공고 %s / 이미지 %s 조합', (hasAnnouncement, hasImage) => {
    renderCard(complexWith({
      representativeAnnouncement: hasAnnouncement ? BASE_ANNOUNCEMENT : null,
      thumbnailImageUrl: hasImage ? 'https://example.com/complex.jpg' : null,
    }))

    expect(screen.queryByRole('img') !== null).toBe(hasImage)
    expect(screen.queryByRole('button', { name: '대표 공고 상세 보기' }) !== null)
      .toBe(hasAnnouncement)
    expect(screen.getByRole('heading', { name: BASE_COMPLEX.name }))
      .toBeInTheDocument()
  })

  it('이미지 로드 실패 후 다른 URL이 성공하면 이전 URL도 다시 시도한다', () => {
    const { onOpenAnnouncement, onSelect, rerender } = renderCard()
    const failedImage = screen.getByRole('img')

    fireEvent.error(failedImage)
    expect(screen.queryByRole('img')).not.toBeInTheDocument()

    rerender(
      <HousingComplexCard
        complex={complexWith({
          thumbnailImageUrl: 'https://example.com/replacement.jpg',
        })}
        onSelect={onSelect}
        onOpenAnnouncement={onOpenAnnouncement}
      />,
    )

    const replacementImage = screen.getByRole('img')
    expect(replacementImage).toHaveAttribute(
      'src',
      'https://example.com/replacement.jpg',
    )
    fireEvent.load(replacementImage)

    rerender(
      <HousingComplexCard
        complex={BASE_COMPLEX}
        onSelect={onSelect}
        onOpenAnnouncement={onOpenAnnouncement}
      />,
    )

    expect(screen.getByRole('img')).toHaveAttribute(
      'src',
      'https://example.com/complex.jpg',
    )
  })

  it('이미지 로드 실패 후 URL이 null을 거치면 같은 URL도 다시 시도한다', () => {
    const { onOpenAnnouncement, onSelect, rerender } = renderCard()

    fireEvent.error(screen.getByRole('img'))
    expect(screen.queryByRole('img')).not.toBeInTheDocument()

    rerender(
      <HousingComplexCard
        complex={complexWith({ thumbnailImageUrl: null })}
        onSelect={onSelect}
        onOpenAnnouncement={onOpenAnnouncement}
      />,
    )
    expect(screen.queryByRole('img')).not.toBeInTheDocument()

    rerender(
      <HousingComplexCard
        complex={BASE_COMPLEX}
        onSelect={onSelect}
        onOpenAnnouncement={onOpenAnnouncement}
      />,
    )
    expect(screen.getByRole('img')).toHaveAttribute(
      'src',
      'https://example.com/complex.jpg',
    )
  })

  it('이미지 로드 실패 후 다른 URL이 로딩 중이어도 이전 URL을 다시 시도한다', () => {
    const { onOpenAnnouncement, onSelect, rerender } = renderCard()

    fireEvent.error(screen.getByRole('img'))

    rerender(
      <HousingComplexCard
        complex={complexWith({
          thumbnailImageUrl: 'https://example.com/pending.jpg',
        })}
        onSelect={onSelect}
        onOpenAnnouncement={onOpenAnnouncement}
      />,
    )
    expect(screen.getByRole('img')).toHaveAttribute(
      'src',
      'https://example.com/pending.jpg',
    )

    rerender(
      <HousingComplexCard
        complex={BASE_COMPLEX}
        onSelect={onSelect}
        onOpenAnnouncement={onOpenAnnouncement}
      />,
    )
    expect(screen.getByRole('img')).toHaveAttribute(
      'src',
      'https://example.com/complex.jpg',
    )
  })

  it.each([
    null,
    '',
    '   ',
    'javascript:alert(1)',
    'data:image/png;base64,AAAA',
    '/relative-image.jpg',
  ])('허용되지 않는 이미지 URL %j은 이미지 자리를 만들지 않는다', (thumbnailImageUrl) => {
    renderCard(complexWith({ thumbnailImageUrl }))

    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('기관 코드를 우선 표시하고 코드가 없으면 정식 기관명을 표시한다', () => {
    const { rerender } = renderCard()

    expect(screen.getByText('LH')).toBeInTheDocument()
    expect(screen.queryByText(BASE_COMPLEX.agencyName)).not.toBeInTheDocument()

    rerender(
      <HousingComplexCard
        complex={complexWith({ agencyCode: null })}
        onSelect={vi.fn()}
      />,
    )

    expect(screen.getByText(BASE_COMPLEX.agencyName)).toBeInTheDocument()
    expect(screen.queryByText('LH')).not.toBeInTheDocument()
  })

  it.each([
    {
      label: '0을 실제 금액으로 표시한다',
      changes: {
        depositMin: 0,
        depositMax: 0,
        monthlyRentMin: 0,
        monthlyRentMax: null,
      },
      expected: ['0원'],
    },
    {
      label: '한쪽 값만 있으면 범위를 추측하지 않고 단일 값으로 표시한다',
      changes: {
        exclusiveAreaMin: null,
        exclusiveAreaMax: 44.87,
        depositMin: 50_000_000,
        depositMax: null,
      },
      expected: ['44.87m²', '5,000만원'],
    },
    {
      label: '두 값이 모두 없으면 공고문 확인으로 표시한다',
      changes: {
        exclusiveAreaMin: null,
        exclusiveAreaMax: null,
        depositMin: null,
        depositMax: null,
        monthlyRentMin: null,
        monthlyRentMax: null,
      },
      expected: ['공고문 확인'],
    },
    {
      label: '만원 미만 단위와 큰 금액 및 면적을 손실 없이 표시한다',
      changes: {
        exclusiveAreaMin: 1_234.56,
        exclusiveAreaMax: 1_234.56,
        depositMin: 123_456_789,
        depositMax: 123_456_789,
        monthlyRentMin: 85_000,
        monthlyRentMax: 85_000,
      },
      expected: ['1,234.56m²', '1.23456789억', '8.5만원'],
    },
    {
      label: '유효하지 않은 수는 값으로 표시하지 않는다',
      changes: {
        exclusiveAreaMin: Number.NaN,
        exclusiveAreaMax: Number.POSITIVE_INFINITY,
        depositMin: Number.NaN,
        depositMax: null,
        monthlyRentMin: -1,
        monthlyRentMax: -1,
      },
      expected: ['공고문 확인'],
    },
    {
      label: '음수 금액은 범위에서 제외하고 확인된 0원은 유지한다',
      changes: {
        depositMin: -1,
        depositMax: 0,
      },
      expected: ['0원'],
    },
  ])('$label', ({ changes, expected }) => {
    renderCard(complexWith(changes))

    const conditions = screen.getByRole('group', { name: '주요 임대 조건' })
    for (const value of expected) {
      expect(within(conditions).getAllByText(value).length).toBeGreaterThan(0)
    }
  })

  it('대표 공고가 없으면 상태 영역과 이동 액션을 모두 숨긴다', () => {
    renderCard(complexWith({ representativeAnnouncement: null }))

    expect(screen.queryByRole('group', { name: '대표 공고' }))
      .not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '대표 공고 상세 보기' }))
      .not.toBeInTheDocument()
  })

  it.each([true, false])('마감된 대표 공고는 이미지 %s에서도 공고 없는 배치로 표시한다', (hasImage) => {
    const { onOpenAnnouncement, onSelect } = renderCard(complexWith({
      representativeAnnouncement: {
        ...BASE_ANNOUNCEMENT,
        applicationStatus: 'CLOSED',
        applicationEndAt: '2099-08-30',
      },
      thumbnailImageUrl: hasImage ? BASE_COMPLEX.thumbnailImageUrl : null,
    }))

    const card = screen.getByRole('article', { name: BASE_COMPLEX.name })
    const headings = within(card).getAllByRole('heading', {
      name: BASE_COMPLEX.name,
    })
    const titleRow = headings[0].closest('header')
    const complexAction = within(card).getByRole('button', {
      name: `${BASE_COMPLEX.name} 단지 상세 보기`,
    })

    expect(within(card).queryByRole('group', { name: '대표 공고' }))
      .not.toBeInTheDocument()
    expect(within(card).queryByRole('button', { name: '대표 공고 상세 보기' }))
      .not.toBeInTheDocument()
    expect(within(card).queryByText('접수마감')).not.toBeInTheDocument()
    expect(card).toHaveAttribute('data-has-announcement', 'false')
    expect(headings).toHaveLength(1)
    expect(titleRow).toContainElement(within(card).getByText('LH'))
    expect(titleRow?.parentElement?.firstElementChild).toBe(titleRow)
    expect(within(card).queryByRole('img') !== null).toBe(hasImage)
    expect(within(card).getByRole('group', { name: '주요 임대 조건' }))
      .toBeInTheDocument()

    complexAction.focus()
    expect(complexAction).toHaveFocus()
    expect(complexAction).toHaveAttribute(
      'data-complex-detail-trigger',
      BASE_COMPLEX.complexId,
    )
    fireEvent.click(complexAction)
    expect(onSelect).toHaveBeenCalledExactlyOnceWith(BASE_COMPLEX.complexId)
    expect(onOpenAnnouncement).not.toHaveBeenCalled()
  })

  it('마감일이 지났더라도 서버 상태가 접수중이면 대표 공고를 유지한다', () => {
    renderCard(complexWith({
      representativeAnnouncement: {
        ...BASE_ANNOUNCEMENT,
        applicationEndAt: '2000-01-01',
        dDay: -1,
      },
    }))

    expect(screen.getByRole('group', { name: '대표 공고' }))
      .toHaveTextContent('접수중')
    expect(screen.getByRole('button', { name: '대표 공고 상세 보기' }))
      .toBeInTheDocument()
  })

  it('공고 상세 handler가 없으면 같은 상태를 정적 정보로 표시한다', () => {
    render(
      <HousingComplexCard
        complex={BASE_COMPLEX}
        onSelect={vi.fn()}
      />,
    )

    const announcement = screen.getByRole('group', { name: '대표 공고' })
    expect(within(announcement).getByText('접수중')).toBeInTheDocument()
    expect(within(announcement).queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '대표 공고 상세 보기' }))
      .not.toBeInTheDocument()
  })

  it('단지 선택과 대표 공고 이동을 서로 다른 버튼 동작으로 제공한다', () => {
    const { onOpenAnnouncement, onSelect } = renderCard()
    const complexAction = screen.getByRole('button', {
      name: `${BASE_COMPLEX.name} 단지 상세 보기`,
    })
    const announcementAction = screen.getByRole('button', {
      name: '대표 공고 상세 보기',
    })

    expect(complexAction).toHaveAttribute(
      'data-complex-detail-trigger',
      BASE_COMPLEX.complexId,
    )
    expect(announcementAction).toHaveAttribute(
      'data-representative-announcement-detail-trigger',
      BASE_ANNOUNCEMENT.announcementId,
    )
    expect(within(announcementAction).queryByRole('button')).not.toBeInTheDocument()

    fireEvent.click(complexAction)
    expect(onSelect).toHaveBeenCalledWith(BASE_COMPLEX.complexId)

    fireEvent.click(announcementAction)
    expect(onOpenAnnouncement).toHaveBeenCalledWith('117')
    expect(onSelect).toHaveBeenCalledTimes(1)
  })

  it('mouse와 keyboard focus로 같은 hover ID를 전달하고 내부 focus 이동을 유지한다', () => {
    const onHover = vi.fn()
    render(
      <HousingComplexCard
        complex={BASE_COMPLEX}
        onHover={onHover}
        onSelect={vi.fn()}
        onOpenAnnouncement={vi.fn()}
      />,
    )
    const card = screen.getByRole('article')
    const complexAction = screen.getByRole('button', {
      name: `${BASE_COMPLEX.name} 단지 상세 보기`,
    })
    const announcementAction = screen.getByRole('button', {
      name: '대표 공고 상세 보기',
    })

    fireEvent.mouseEnter(card)
    fireEvent.mouseLeave(card)
    expect(onHover.mock.calls).toEqual([
      [BASE_COMPLEX.complexId],
      [null],
    ])

    onHover.mockClear()
    complexAction.focus()
    expect(onHover).toHaveBeenLastCalledWith(BASE_COMPLEX.complexId)

    onHover.mockClear()
    fireEvent.mouseEnter(card)
    fireEvent.mouseLeave(card)
    expect(onHover).toHaveBeenLastCalledWith(BASE_COMPLEX.complexId)

    onHover.mockClear()
    announcementAction.focus()
    expect(onHover.mock.calls).toEqual([[BASE_COMPLEX.complexId]])

    onHover.mockClear()
    announcementAction.blur()
    expect(onHover.mock.calls).toEqual([[null]])
  })

  it.each([
    ['BEFORE_APPLICATION', '공고중', '마감 D-2'],
    ['APPLYING', '접수중', 'D-2'],
    ['CANCELLED', '공고취소', null],
    ['UNEXPECTED', '공고문 확인', 'D-2'],
  ])('대표 공고 상태 %s를 중복 없이 표시한다', (applicationStatus, label, countdown) => {
    renderCard(complexWith({
      representativeAnnouncement: {
        ...BASE_ANNOUNCEMENT,
        applicationStatus,
      },
    }))

    const announcement = screen.getByRole('group', { name: '대표 공고' })
    expect(within(announcement).getByText(label)).toBeInTheDocument()
    if (countdown === null) {
      expect(within(announcement).queryByText(/^D-/)).not.toBeInTheDocument()
    } else {
      expect(within(announcement).getByText(countdown)).toBeInTheDocument()
    }
  })

  it('D-day 0은 D-Day로 표시하고 마감 설명을 보존한다', () => {
    renderCard(complexWith({
      representativeAnnouncement: {
        ...BASE_ANNOUNCEMENT,
        dDay: 0,
      },
    }))

    expect(screen.getByLabelText('접수 마감까지 0일')).toHaveTextContent('D-Day')
    expect(screen.getByRole('button', { name: '대표 공고 상세 보기' }))
      .toHaveAccessibleDescription('대표 공고 상태 접수중, 접수 마감까지 0일')
  })

  it.each([null, -1, 1.5])('유효하지 않은 D-day %s는 공고문 확인으로 표시한다', (dDay) => {
    renderCard(complexWith({
      representativeAnnouncement: {
        ...BASE_ANNOUNCEMENT,
        dDay,
      },
    }))

    expect(screen.getByText('공고문 확인')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '대표 공고 상세 보기' }))
      .toHaveAccessibleDescription('대표 공고 상태 접수중, 접수 마감일 공고문 확인')
  })

  it('대표 공고 상태와 마감일이 모두 없으면 누락 배지를 한 번만 표시한다', () => {
    renderCard(complexWith({
      representativeAnnouncement: {
        ...BASE_ANNOUNCEMENT,
        applicationStatus: 'UNKNOWN',
        dDay: null,
      },
    }))

    const announcement = screen.getByRole('group', { name: '대표 공고' })
    expect(within(announcement).getAllByText('공고문 확인')).toHaveLength(1)
    expect(announcement.querySelector('[data-status-kind="countdown"]')).toBeNull()
    expect(within(announcement).getByRole('button', { name: '대표 공고 상세 보기' }))
      .toHaveAccessibleDescription('대표 공고 상태 공고문 확인')
  })

  it('기관과 임대유형이 모두 없으면 한 문구로 표시하고 접근성 설명에 두 항목을 남긴다', () => {
    renderCard(complexWith({
      agencyCode: null,
      agencyName: '공고문 확인',
      rentalTypeLabel: '공고문 확인',
    }))

    const metadata = screen.getByLabelText('공급기관 공고문 확인, 임대유형 공고문 확인')
    expect(within(metadata).getAllByText('공고문 확인')).toHaveLength(1)
    expect(metadata).not.toHaveTextContent('·')
  })

  it('여러 단지가 같은 대표 공고를 공유해도 각 버튼의 마감 설명을 연결한다', () => {
    render(
      <>
        <HousingComplexCard complex={BASE_COMPLEX} onSelect={vi.fn()} onOpenAnnouncement={vi.fn()} />
        <HousingComplexCard
          complex={complexWith({
            complexId: '18',
            name: '서울나루 행복주택',
            representativeAnnouncement: {
              ...BASE_ANNOUNCEMENT,
              dDay: 5,
            },
          })}
          onSelect={vi.fn()}
          onOpenAnnouncement={vi.fn()}
        />
      </>,
    )

    const actions = screen.getAllByRole('button', { name: '대표 공고 상세 보기' })
    expect(actions[0]).toHaveAccessibleDescription(
      '대표 공고 상태 접수중, 접수 마감까지 2일',
    )
    expect(actions[1]).toHaveAccessibleDescription(
      '대표 공고 상태 접수중, 접수 마감까지 5일',
    )
  })
})
