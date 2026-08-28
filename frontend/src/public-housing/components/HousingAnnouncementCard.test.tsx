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
  recruitmentTypeLabel: '예비입주자',
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
  it('공고명부터 맥락, 접수 일정, 공급 규모, 메타 순으로 표시한다', () => {
    const { card } = renderCard()
    const title = requiredRow(card, 'title')
    const context = requiredRow(card, 'context')
    const schedule = requiredRow(card, 'schedule')
    const supply = requiredRow(card, 'supply')
    const meta = requiredRow(card, 'meta')

    expect(within(title).getByRole('heading', { name: BASE_TITLE }))
      .toBeInTheDocument()
    expect(within(context).getByText('경기도 성남시')).toBeInTheDocument()
    expect(within(context).getByText('LH')).toBeInTheDocument()
    expect(within(context).getByText('행복주택')).toBeInTheDocument()
    expect(within(context).getByText('예비입주자')).toBeInTheDocument()
    expect(schedule).toHaveAccessibleName('접수 일정')
    expect(within(schedule).getByText('접수중')).toBeInTheDocument()
    expect(within(schedule).getByLabelText('접수 마감까지 3일')).toHaveTextContent(
      '접수 마감까지3일',
    )
    expect(within(supply).getByText('공급 세대수')).toBeInTheDocument()
    expect(within(supply).getByText('75세대')).toBeInTheDocument()
    expect(within(supply).queryByText('공급 단지')).not.toBeInTheDocument()
    expect(within(meta).getByText('조회 614')).toBeInTheDocument()
    expect(card).toHaveAccessibleName(
      `${BASE_TITLE}, 접수중, 접수 마감까지 3일`,
    )
    expect(card).not.toHaveAccessibleName(/공고 공고/)

    expect(title.nextElementSibling).toBe(context)
    expect(context.nextElementSibling).toBe(schedule)
    expect(schedule.nextElementSibling).toBe(supply)
    expect(supply.nextElementSibling).toBe(meta)
    expect(within(card).queryByRole('img')).not.toBeInTheDocument()
    expect(within(card).queryByRole('button', { name: /저장|북마크|알림/ }))
      .not.toBeInTheDocument()
    expect(card.textContent).not.toMatch(/검색|필터|예측 경쟁률/)
  })

  it('접수기간 날짜를 부터·까지가 있는 두 행 값으로 표시한다', () => {
    const { card } = renderCard()
    const schedule = requiredRow(card, 'schedule')
    const times = within(schedule).getAllByRole('time')

    expect(times.map((time) => time.textContent)).toEqual(['2026.08.10', '2026.08.11'])
    expect(times.map((time) => time.getAttribute('datetime'))).toEqual([
      '2026-08-10',
      '2026-08-11',
    ])
    expect(within(schedule).getByText('부터')).toBeInTheDocument()
    expect(within(schedule).getByText('까지')).toBeInTheDocument()
  })

  it('모집유형과 무관하게 supplyHouseholdCount만 공급 세대수로 표시한다', () => {
    const { card } = renderCard(
      announcementWith({
        recruitmentTypeLabel: '예비입주자',
        supplyHouseholdCount: 1_234,
      }),
    )
    const supply = requiredRow(card, 'supply')

    expect(within(supply).getByText('공급 세대수')).toBeInTheDocument()
    expect(within(supply).getByText('1,234세대')).toBeInTheDocument()
    expect(within(supply).queryByText(/모집 호수|모집 예비자 수/)).not.toBeInTheDocument()
  })

  it('nullable 핵심 속성은 정보 확인 중으로 표시하고 nullable 메타는 숨긴다', () => {
    const { card } = renderCard(
      announcementWith({
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
      }),
    )

    expect(within(card).getByRole('heading', { name: '공고명 정보 확인 중' }))
      .toBeInTheDocument()
    expect(within(card).getByText('지역 정보 확인 중')).toBeInTheDocument()
    expect(within(card).getByText('공사 정보 확인 중')).toBeInTheDocument()
    expect(within(card).getByText('주택유형 정보 확인 중')).toBeInTheDocument()
    expect(within(card).getByText('모집유형 정보 확인 중')).toBeInTheDocument()
    expect(within(card).getByText('마감일')).toBeInTheDocument()
    expect(within(card).getAllByText('정보 확인 중').length).toBeGreaterThan(2)
    expect(within(card).queryByText(/조회/)).not.toBeInTheDocument()
    expect(requiredRow(card, 'supply')).toHaveTextContent('공급 세대수정보 확인 중')
  })

  it('0을 실제 공급 규모와 조회수로 표시한다', () => {
    const { card } = renderCard(
      announcementWith({
        supplyHouseholdCount: 0,
        viewCount: 0,
      }),
    )

    expect(within(card).getByText('0세대')).toBeInTheDocument()
    expect(within(card).getByText('조회 0')).toBeInTheDocument()
  })

  it.each([
    ['BEFORE_APPLICATION', '접수예정', '접수 마감까지 3일'],
    ['APPLYING', '접수중', '접수 마감까지 3일'],
    ['CLOSED', null, '접수 마감 완료'],
    ['CANCELLED', null, '공고 취소'],
    ['UNEXPECTED', '정보 확인 중', '접수 마감까지 3일'],
  ])('%s 상태를 중복 없이 표현한다', (applicationStatus, statusLabel, deadlineLabel) => {
    const { card } = renderCard(announcementWith({ applicationStatus }))
    const schedule = requiredRow(card, 'schedule')

    if (statusLabel === null) {
      expect(schedule.querySelector('[data-status-kind="application"]')).toBeNull()
    } else {
      expect(within(schedule).getByText(statusLabel)).toBeInTheDocument()
    }
    expect(within(schedule).getByLabelText(deadlineLabel)).toBeInTheDocument()
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
      return
    }
    expect(card).not.toHaveAttribute('data-urgency')
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

  it('상세 callback이 없으면 정보 카드는 유지하고 primary button만 숨긴다', () => {
    render(<HousingAnnouncementCard announcement={BASE_ANNOUNCEMENT} />)
    const card = screen.getByRole('article', {
      name: `${BASE_TITLE}, 접수중, 접수 마감까지 3일`,
    })

    expect(within(card).getByRole('heading', { name: BASE_TITLE }))
      .toBeInTheDocument()
    expect(within(card).queryByRole('button')).not.toBeInTheDocument()
  })

  it('270px 일정 적층, 무이동 hover, focus-visible 규칙을 스타일에 고정한다', () => {
    const css = readFileSync(
      resolve(
        process.cwd(),
        'src/public-housing/components/HousingAnnouncementCard.module.css',
      ),
      'utf8',
    )
    const narrowRule = css.slice(
      css.indexOf('@container housing-announcement-card (max-width: 270px)'),
    )

    expect(css).toContain('container: housing-announcement-card / inline-size;')
    expect(css).toMatch(/\.card:hover[\s\S]*?transform:\s*none;/)
    expect(css).toMatch(/\.primaryAction:focus-visible[\s\S]*?outline:/)
    expect(narrowRule).toMatch(/\.periodRow[\s\S]*?grid-template-columns:\s*minmax\(0, 1fr\);/)
    expect(narrowRule).toMatch(/\.periodValues[\s\S]*?flex-direction:\s*column;/)
  })
})
