/// <reference types="node" />

import { fireEvent, render, screen, within } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
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
  complexId: '17',
  name: '서울가람 행복주택',
  regionName: '서울특별시 중구',
  agencyName: '한국토지주택공사',
  rentalTypeLabel: '행복주택',
  exclusiveAreaMin: 36.12,
  exclusiveAreaMax: 44.87,
  depositMin: 50_000_000,
  depositMax: 70_000_000,
  monthlyRentMin: 200_000,
  monthlyRentMax: 300_000,
  representativeAnnouncement: BASE_ANNOUNCEMENT,
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
  it('이미지 없이 단지 맥락과 임대 조건, 대표 공고를 정해진 계층으로 표시한다', () => {
    renderCard()

    expect(
      screen.getByRole('heading', { name: BASE_COMPLEX.name }),
    ).toBeInTheDocument()
    expect(screen.getByText(BASE_COMPLEX.regionName)).toBeInTheDocument()
    expect(screen.getByText(BASE_COMPLEX.agencyName)).toBeInTheDocument()
    expect(screen.getByText(BASE_COMPLEX.rentalTypeLabel)).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.queryByText(/접수 시작일|준공/)).not.toBeInTheDocument()

    const conditions = screen.getByRole('group', { name: '주요 임대 조건' })
    expect(within(conditions).getByText('전용면적')).toBeInTheDocument()
    expect(within(conditions).getByText('36.12㎡ ~ 44.87㎡')).toBeInTheDocument()
    expect(within(conditions).getByText('5,000만 원 ~ 7,000만 원')).toBeInTheDocument()
    expect(within(conditions).getByText('20만 원 ~ 30만 원')).toBeInTheDocument()

    const announcement = screen.getByRole('group', { name: '대표 공고' })
    expect(within(announcement).getByText('접수중')).toBeInTheDocument()
    expect(within(announcement).getByText('2026.08.30')).toHaveAttribute(
      'datetime',
      '2026-08-30',
    )
    expect(within(announcement).getByText('D-2')).toBeInTheDocument()
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
      expected: ['44.87㎡', '5,000만 원'],
    },
    {
      label: '두 값이 모두 없으면 정보 확인 중으로 표시한다',
      changes: {
        exclusiveAreaMin: null,
        exclusiveAreaMax: null,
        depositMin: null,
        depositMax: null,
        monthlyRentMin: null,
        monthlyRentMax: null,
      },
      expected: ['정보 확인 중'],
    },
  ])('$label', ({ changes, expected }) => {
    renderCard(complexWith(changes))

    const conditions = screen.getByRole('group', { name: '주요 임대 조건' })
    for (const value of expected) {
      expect(within(conditions).getAllByText(value).length).toBeGreaterThan(0)
    }
  })

  it('대표 공고가 없으면 공고 블록과 이동 액션을 모두 숨긴다', () => {
    renderCard(complexWith({ representativeAnnouncement: null }))

    expect(screen.queryByRole('group', { name: '대표 공고' })).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '대표 공고 상세 보기' }),
    ).not.toBeInTheDocument()
  })

  it('공고 상세 handler가 없으면 대표 공고 정보만 표시한다', () => {
    render(
      <HousingComplexCard
        complex={BASE_COMPLEX}
        onSelect={vi.fn()}
      />,
    )

    expect(screen.getByRole('group', { name: '대표 공고' })).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '대표 공고 상세 보기' }),
    ).not.toBeInTheDocument()
  })

  it('단지 선택과 대표 공고 이동을 서로 다른 버튼 동작으로 제공한다', () => {
    const { onOpenAnnouncement, onSelect } = renderCard()

    fireEvent.click(
      screen.getByRole('button', { name: `${BASE_COMPLEX.name} 단지 상세 보기` }),
    )
    expect(onSelect).toHaveBeenCalledWith(BASE_COMPLEX.complexId)

    fireEvent.click(screen.getByRole('button', { name: '대표 공고 상세 보기' }))
    expect(onOpenAnnouncement).toHaveBeenCalledWith('117')
    expect(onSelect).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['BEFORE_APPLICATION', '모집예정'],
    ['APPLYING', '접수중'],
    ['CLOSED', '접수마감'],
    ['UNEXPECTED', '정보 확인 중'],
  ])('대표 공고 상태 %s를 %s로 표시한다', (applicationStatus, label) => {
    renderCard(
      complexWith({
        representativeAnnouncement: {
          ...BASE_ANNOUNCEMENT,
          applicationStatus,
        },
      }),
    )

    expect(screen.getByText(label)).toBeInTheDocument()
  })

  it.each([
    [0, true],
    [3, true],
    [4, false],
  ])('접수중 D-%s는 0일부터 3일까지만 긴급하게 표시한다', (dDay, urgent) => {
    renderCard(
      complexWith({
        representativeAnnouncement: {
          ...BASE_ANNOUNCEMENT,
          applicationStatus: 'APPLYING',
          dDay,
        },
      }),
    )

    const announcement = screen.getByRole('group', { name: '대표 공고' })
    if (urgent) {
      expect(announcement).toHaveAttribute('data-urgency', 'urgent')
      return
    }
    expect(announcement).not.toHaveAttribute('data-urgency')
  })

  it('마감된 공고의 null D-day는 결측값 대신 종료 의미로 표시한다', () => {
    renderCard(
      complexWith({
        representativeAnnouncement: {
          ...BASE_ANNOUNCEMENT,
          applicationStatus: 'CLOSED',
          dDay: null,
        },
      }),
    )

    expect(screen.getByText('종료')).toBeInTheDocument()
    expect(screen.queryByText('D-null')).not.toBeInTheDocument()
  })

  it('270px 재배치, 무이동 hover, focus-visible 규칙을 스타일에 고정한다', () => {
    const cardStyles = readFileSync(
      resolve(
        process.cwd(),
        'src/public-housing/components/HousingComplexCard.module.css',
      ),
      'utf8',
    )
    const narrowRule = cardStyles.slice(
      cardStyles.indexOf('@container housing-complex-card (max-width: 270px)'),
    )

    expect(cardStyles).toContain('container: housing-complex-card / inline-size;')
    expect(cardStyles).toMatch(/\.card:hover[\s\S]*?transform:\s*none;/)
    expect(cardStyles).toMatch(/\.primaryAction:focus-visible[\s\S]*?outline:/)
    expect(cardStyles).toMatch(/\.announcementAction:focus-visible[\s\S]*?outline:/)
    expect(narrowRule).toMatch(/\.conditions[\s\S]*?repeat\(2, minmax\(0, 1fr\)\)/)
    expect(narrowRule).toMatch(/\.areaMetric[\s\S]*?grid-column:\s*1 \/ -1;/)
    expect(narrowRule).toMatch(/\.announcement[\s\S]*?grid-template-columns:\s*minmax\(0, 1fr\);/)
    expect(narrowRule).toMatch(/\.announcementAction[\s\S]*?width:\s*100%;/)
  })
})
