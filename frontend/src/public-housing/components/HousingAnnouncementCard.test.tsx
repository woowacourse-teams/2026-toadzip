/// <reference types="node" />

import { fireEvent, render, screen, within } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import {
  HousingAnnouncementCard,
  type HousingAnnouncementCardData,
} from './HousingAnnouncementCard'

const BASE_TITLE = '성남 청년 행복주택 예비입주자 모집 공고'

const BASE_ANNOUNCEMENT: HousingAnnouncementCardData = {
  announcementId: '201',
  title: BASE_TITLE,
  regionNames: ['경기도 성남시'],
  agencyLabel: 'LH',
  rentalTypeLabel: '행복주택',
  recruitmentTypeLabel: '예비',
  applicationStatus: 'APPLYING',
  applicationStartAt: '2026-08-10',
  applicationEndAt: '2026-08-11',
  dDay: 3,
  viewCount: 614,
  supplyHouseholdCount: 75,
}

function renderCard(
  announcement: HousingAnnouncementCardData = BASE_ANNOUNCEMENT,
  onSelect: ((announcementId: string) => void) | undefined = vi.fn(),
) {
  const result = render(
    <HousingAnnouncementCard
      announcement={announcement}
      onSelect={onSelect}
    />,
  )
  const card = screen.getByRole('article')

  return { ...result, card, onSelect }
}

function announcementWith(
  changes: Partial<HousingAnnouncementCardData>,
): HousingAnnouncementCardData {
  return { ...BASE_ANNOUNCEMENT, ...changes }
}

function requiredRow(card: HTMLElement, row: string) {
  const element = card.querySelector<HTMLElement>(`[data-summary-row="${row}"]`)
  if (element === null) {
    throw new Error(`${row} row not found`)
  }
  return element
}

describe('HousingAnnouncementCard', () => {
  it('상태와 기관부터 제목, 지역·유형, 접수기간, 공급·조회 순으로 표시한다', () => {
    const { card } = renderCard()
    const status = requiredRow(card, 'status')
    const title = requiredRow(card, 'title')
    const context = requiredRow(card, 'context')
    const schedule = requiredRow(card, 'schedule')
    const footer = requiredRow(card, 'footer')

    expect(within(status).getByText('접수중')).toBeInTheDocument()
    expect(within(status).getByLabelText('접수 마감까지 3일')).toHaveTextContent('D-3')
    expect(within(status).getByText('LH')).toBeInTheDocument()
    expect(within(title).getByRole('heading', { name: BASE_TITLE }))
      .toBeInTheDocument()
    expect(context).toHaveTextContent('경기도 성남시 · 행복주택')
    expect(schedule).toHaveAccessibleName('접수기간')
    expect(footer).toHaveTextContent('예비 · 공급 75세대')
    expect(footer).toHaveTextContent('조회 614')
    expect(card).toHaveAccessibleName(
      `${BASE_TITLE}, 접수중, 접수 마감까지 3일`,
    )

    expect(status.nextElementSibling).toBe(title)
    expect(title.nextElementSibling).toBe(context)
    expect(context.nextElementSibling).toBe(schedule)
    expect(schedule.nextElementSibling).toBe(footer)
    expect(within(card).queryByRole('img')).not.toBeInTheDocument()
    expect(within(card).queryByRole('button', { name: /저장|북마크|알림/ }))
      .not.toBeInTheDocument()
  })

  it('같은 연도의 종료일만 연도를 생략한다', () => {
    const { card } = renderCard(announcementWith({
      applicationStartAt: '2026-09-18',
      applicationEndAt: '2026-09-21',
    }))
    const times = within(requiredRow(card, 'schedule')).getAllByRole('time')

    expect(times.map((time) => time.textContent)).toEqual(['2026.09.18', '09.21'])
    expect(times.map((time) => time.getAttribute('datetime'))).toEqual([
      '2026-09-18',
      '2026-09-21',
    ])
  })

  it('연도를 넘기는 접수기간은 두 날짜의 연도를 모두 표시한다', () => {
    const { card } = renderCard(announcementWith({
      applicationStartAt: '2026-12-28',
      applicationEndAt: '2027-01-05',
    }))
    const times = within(requiredRow(card, 'schedule')).getAllByRole('time')

    expect(times.map((time) => time.textContent)).toEqual([
      '2026.12.28',
      '2027.01.05',
    ])
  })

  it('접수 날짜가 모두 누락되거나 유효하지 않으면 공고문 확인을 한 번만 표시한다', () => {
    const { card } = renderCard(announcementWith({
      applicationStartAt: null,
      applicationEndAt: '2026-02-30',
    }))
    const schedule = requiredRow(card, 'schedule')

    expect(within(schedule).getAllByText('공고문 확인')).toHaveLength(1)
    expect(within(schedule).queryByRole('time')).not.toBeInTheDocument()
    expect(schedule).not.toHaveTextContent('~')
  })

  it.each([
    [null, '2026-09-21', '공고문 확인 ~ 2026.09.21'],
    ['2026-09-18', null, '2026.09.18 ~ 공고문 확인'],
  ])('접수기간 한쪽만 누락되면 확인된 날짜 %s → %s를 유지한다', (start, end, expected) => {
    const { card } = renderCard(announcementWith({
      applicationStartAt: start,
      applicationEndAt: end,
    }))
    const schedule = requiredRow(card, 'schedule')

    expect(within(schedule).getAllByText('공고문 확인')).toHaveLength(1)
    expect(within(schedule).getAllByRole('time')).toHaveLength(1)
    expect(schedule).toHaveTextContent(expected)
  })

  it('첫 지역과 나머지 지역 개수만 요약한다', () => {
    const { card } = renderCard(announcementWith({
      regionNames: ['서울특별시', '경기도', '인천광역시'],
    }))

    expect(requiredRow(card, 'context')).toHaveTextContent(
      '서울특별시 외 2개 · 행복주택',
    )
    expect(card).not.toHaveTextContent('경기도')
    expect(card).not.toHaveTextContent('인천광역시')
  })

  it('모집유형과 무관하게 supplyHouseholdCount만 공급 세대수로 표시한다', () => {
    const { card } = renderCard(announcementWith({
      recruitmentTypeLabel: '예비',
      supplyHouseholdCount: 1_234,
    }))
    const footer = requiredRow(card, 'footer')

    expect(footer).toHaveTextContent('예비 · 공급 1,234세대')
    expect(footer).not.toHaveTextContent(/모집 호수|모집 예비자 수/)
  })

  it('nullable 핵심 속성은 공고문 확인으로 표시하고 nullable 조회수는 숨긴다', () => {
    const { card } = renderCard(announcementWith({
      title: null,
      regionNames: [],
      agencyLabel: null,
      rentalTypeLabel: null,
      recruitmentTypeLabel: null,
      applicationStatus: null,
      applicationStartAt: null,
      applicationEndAt: null,
      dDay: null,
      viewCount: null,
      supplyHouseholdCount: null,
    }))

    expect(within(card).getByRole('heading', { name: '공고문 확인' }))
      .toBeInTheDocument()
    const context = requiredRow(card, 'context')
    expect(within(context).getAllByText('공고문 확인')).toHaveLength(1)
    expect(context).toHaveAttribute('aria-label', '지역 및 주택유형 공고문 확인')
    expect(within(requiredRow(card, 'status')).getAllByText('공고문 확인')).toHaveLength(2)
    const footer = requiredRow(card, 'footer')
    expect(within(footer).getAllByText('공고문 확인')).toHaveLength(1)
    expect(within(footer).getByLabelText('모집유형 및 공급 세대수 공고문 확인'))
      .toBeInTheDocument()
    expect(card).not.toHaveTextContent('공고문 확인 · 공고문 확인')
    expect(card).toHaveAccessibleName('공고문 확인, 공고 상태 공고문 확인')
    expect(within(card).queryByText(/조회/)).not.toBeInTheDocument()
  })

  it('0을 실제 공급 규모와 조회수로 표시한다', () => {
    const { card } = renderCard(announcementWith({
      supplyHouseholdCount: 0,
      viewCount: 0,
    }))

    expect(requiredRow(card, 'footer')).toHaveTextContent('예비 · 공급 0세대')
    expect(requiredRow(card, 'footer')).toHaveTextContent('조회 0')
  })

  it.each([
    ['BEFORE_APPLICATION', 3, '공고중', '마감 D-3', '접수 마감까지 3일'],
    ['APPLYING', 3, '접수중', 'D-3', '접수 마감까지 3일'],
    ['APPLYING', 0, '접수중', 'D-Day', '접수 마감일 당일'],
    ['CLOSED', 1, '접수마감', null, '접수 마감 완료'],
    ['CANCELLED', 1, '공고취소', null, '공고 취소'],
    ['UNEXPECTED', 3, '공고문 확인', null, '공고 상태 공고문 확인'],
  ])(
    '%s 상태는 마감 기준 정보를 표현한다',
    (applicationStatus, dDay, statusLabel, countdown, accessibleDeadline) => {
      const { card } = renderCard(announcementWith({ applicationStatus, dDay }))
      const status = requiredRow(card, 'status')

      expect(within(status).getByText(statusLabel)).toBeInTheDocument()
      if (countdown === null) {
        expect(status.querySelector('[data-status-kind="countdown"]')).toBeNull()
      } else {
        expect(within(status).getByLabelText(accessibleDeadline))
          .toHaveTextContent(countdown)
      }
      const expectedName = applicationStatus === 'UNEXPECTED'
        ? `${BASE_TITLE}, ${accessibleDeadline}`
        : `${BASE_TITLE}, ${statusLabel}, ${accessibleDeadline}`
      expect(card).toHaveAccessibleName(expectedName)
    },
  )

  it('예정·접수중 상태라도 D-day가 유효하지 않으면 마감일 정보를 추정하지 않는다', () => {
    const { card } = renderCard(announcementWith({
      applicationStatus: 'BEFORE_APPLICATION',
      dDay: -1,
    }))
    const status = requiredRow(card, 'status')

    expect(within(status).getByText('공고중')).toBeInTheDocument()
    expect(within(status).getByLabelText('접수 마감일 공고문 확인'))
      .toHaveTextContent('공고문 확인')
  })

  it.each([
    ['APPLYING', 0, true],
    ['APPLYING', 3, true],
    ['APPLYING', 4, false],
    ['BEFORE_APPLICATION', 2, false],
    ['CLOSED', 1, false],
    ['CANCELLED', 1, false],
  ])('%s D-%s는 접수중 0~3일만 긴급 표시한다', (status, dDay, urgent) => {
    const { card } = renderCard(
      announcementWith({ applicationStatus: status, dDay }),
    )

    if (urgent) {
      expect(card).toHaveAttribute('data-urgency', 'urgent')
    } else {
      expect(card).not.toHaveAttribute('data-urgency')
    }
  })

  it('긴 제목을 DOM에 온전히 유지하고 상세 버튼의 전체 accessible name으로 사용한다', () => {
    const longTitle = `${BASE_TITLE} 2026년 2차 추가 공급 및 입주 자격 안내`
    const { card } = renderCard(announcementWith({ title: longTitle }))

    expect(within(card).getByRole('heading', { name: longTitle }))
      .toHaveTextContent(longTitle)
    expect(within(card).getByRole('button', { name: `${longTitle} 상세 보기` }))
      .toBeInTheDocument()
  })

  it('상세 callback이 있으면 focus 가능한 실제 버튼으로 ID를 전달한다', () => {
    const onSelect = vi.fn()
    const { card } = renderCard(BASE_ANNOUNCEMENT, onSelect)
    const button = within(card).getByRole('button', {
      name: `${BASE_ANNOUNCEMENT.title} 상세 보기`,
    })

    button.focus()
    expect(button).toHaveFocus()
    expect(button).toHaveAttribute(
      'data-announcement-detail-trigger',
      BASE_ANNOUNCEMENT.announcementId,
    )
    expect(button).not.toHaveAttribute('aria-haspopup')
    fireEvent.click(button)
    expect(onSelect).toHaveBeenCalledWith('201')
  })

  it('상세 callback이 없으면 정보 카드는 유지하고 상세 버튼만 숨긴다', () => {
    render(<HousingAnnouncementCard announcement={BASE_ANNOUNCEMENT} />)
    const card = screen.getByRole('article', {
      name: `${BASE_TITLE}, 접수중, 접수 마감까지 3일`,
    })

    expect(within(card).getByRole('heading', { name: BASE_TITLE }))
      .toBeInTheDocument()
    expect(within(card).queryByRole('button')).not.toBeInTheDocument()
  })

  it('연속 목록의 여백·말줄임·무이동 hover·focus-visible 규칙을 고정한다', () => {
    const css = readFileSync(
      resolve(
        process.cwd(),
        'src/public-housing/components/HousingAnnouncementCard.module.css',
      ),
      'utf8',
    )
    const badgeCss = readFileSync(
      resolve(
        process.cwd(),
        'src/public-housing/components/AnnouncementStatusBadge.module.css',
      ),
      'utf8',
    )

    expect(css).toContain('padding-inline: var(--list-inset, 28px);')
    expect(css).toMatch(/\.titleRow h3[\s\S]*?text-overflow:\s*ellipsis;/)
    expect(css).toMatch(/\.titleRow h3[\s\S]*?white-space:\s*nowrap;/)
    expect(css).toMatch(/\.card:hover[\s\S]*?transform:\s*none;/)
    expect(css).toMatch(/\.primaryAction:focus-visible[\s\S]*?outline:/)
    const tokensCss = readFileSync(
      resolve(process.cwd(), 'src/public-housing/styles/tokens.css'),
      'utf8',
    )
    expect(badgeCss).toMatch(/\.badge\s*\{[^}]*background:\s*var\(--ds-color-status-applying-surface\);/)
    expect(tokensCss).toMatch(/--ds-color-status-applying-surface:\s*#edf3ff;/)
    expect(css).toMatch(/\.supplySummary span:last-child\s*\{[^}]*color:\s*var\(--list-text/)
    expect(css).toMatch(/\.footer\s*\{[^}]*flex-wrap:\s*wrap;/)
    expect(css).not.toMatch(/\.card\s*\{[\s\S]*?border:\s*1px/)
    expect(css).not.toMatch(/\.card\s*\{[\s\S]*?box-shadow:/)
  })
})
