/// <reference types="node" />

import '@testing-library/jest-dom/vitest'
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import {
  HousingAnnouncementDetailPanel,
  type HousingAnnouncementDetailData,
} from './HousingAnnouncementDetailPanel.tsx'

describe('HousingAnnouncementDetailPanel', () => {
  it('중복된 후속 일정은 한 번만 표시하고 서로 다른 접수일은 날짜순으로 분리한다', () => {
    const schedules = Object.freeze(['2026-09-30', '2026-09-28', '2026-10-01'].flatMap((date, index) => [
      Object.freeze(legacySchedule({ scheduleId: `application-${index}`,
        startAt: `${date}T10:00:00`, endAt: `${date}T16:10:00` })),
      Object.freeze(legacySchedule({ scheduleId: `winner-${index}`, type: 'WINNER_ANNOUNCEMENT',
        typeLabel: '당첨자 발표', startAt: '2026-10-22', endAt: null })),
      Object.freeze(legacySchedule({ scheduleId: `document-${index}`, type: 'DOCUMENT_SUBMISSION',
        typeLabel: '서류제출', startAt: '2026-10-22', endAt: '2026-10-29' })),
    ]))
    const original = JSON.stringify(schedules)
    render(<HousingAnnouncementDetailPanel detail={detail({ schedules,
      winnerAnnouncementAt: '2026-10-22' })} onClose={vi.fn()} />)

    const applications = screen.getByRole('table', { name: '접수 일정' })
    expect(within(applications).getAllByRole('rowheader', { name: '접수' })).toHaveLength(3)
    expect([...applications.querySelectorAll('time')].map((time) => time.dateTime)).toEqual([
      '2026-09-28T10:00:00', '2026-09-28T16:10:00',
      '2026-09-30T10:00:00', '2026-09-30T16:10:00',
      '2026-10-01T10:00:00', '2026-10-01T16:10:00',
    ])
    expect(within(applications).queryByText('당첨자 발표')).not.toBeInTheDocument()
    expect(within(applications).queryByText('서류제출')).not.toBeInTheDocument()
    const followUps = screen.getByRole('table', { name: '후속 일정' })
    expect(within(followUps).getAllByRole('rowheader', { name: '당첨자 발표' })).toHaveLength(1)
    expect(within(followUps).getAllByRole('rowheader', { name: '서류제출' })).toHaveLength(1)
    expect(JSON.stringify(schedules)).toBe(original)
  })

  it('종류·이름·시작·종료가 모두 같은 일정만 묶고 날짜가 없는 접수는 뒤에 둔다', () => {
    render(<HousingAnnouncementDetailPanel detail={detail({
      winnerAnnouncementAt: null,
      schedules: [
        legacySchedule({ scheduleId: 'unknown', startAt: null, endAt: null }),
        legacySchedule(),
        legacySchedule({ scheduleId: 'duplicate' }),
        legacySchedule({ scheduleId: 'name', name: '현장 접수' }),
        legacySchedule({ scheduleId: 'start', startAt: '2026-09-28T11:00:00' }),
        legacySchedule({ scheduleId: 'end', endAt: '2026-09-28T17:00:00' }),
        legacySchedule({ scheduleId: 'type', type: 'DOCUMENT_SUBMISSION', typeLabel: '서류제출' }),
      ],
    })} onClose={vi.fn()} />)
    const applications = screen.getByRole('table', { name: '접수 일정' })
    expect(applications.querySelectorAll('tbody')).toHaveLength(5)
    expect(within(applications).getByRole('rowheader', { name: '현장 접수' })).toBeVisible()
    expect(within(applications).getByText('2026.09.28 11:00')).toBeVisible()
    expect(within(applications).getByText('2026.09.28 17:00')).toBeVisible()
    expect(applications.querySelector('tbody:last-child')).toHaveTextContent('공고문 확인')
    expect(within(screen.getByRole('table', { name: '후속 일정' }))
      .getByRole('rowheader', { name: '서류제출' })).toBeVisible()
  })

  it('단지별 접수도 날짜순으로 표시하고 다른 단지·순위·조건은 유지한다', () => {
    const schedules = Object.freeze([
      applicationSchedule({ scheduleId: 'later', supplyRank: '2순위', startDate: '2026-09-02', endDate: '2026-09-02' }),
      applicationSchedule(),
      applicationSchedule({ scheduleId: 'duplicate' }),
      applicationSchedule({ scheduleId: 'rank', supplyRank: '우선공급' }),
      applicationSchedule({ scheduleId: 'conditional', state: 'CONDITIONAL', condition: '미달 시 진행' }),
      applicationSchedule({ scheduleId: 'other', housingComplexId: '102', complexName: '봇들마을' }),
    ])
    const original = JSON.stringify(schedules)
    render(<HousingAnnouncementDetailPanel detail={detail({ applicationSchedules: schedules })} onClose={vi.fn()} />)
    const firstComplex = screen.getByRole('region', { name: '새솔마을 접수 일정' })
    const cards = within(firstComplex).getAllByRole('listitem')
    expect(cards).toHaveLength(4)
    expect(within(cards[0]!).getByRole('heading', { name: '1순위' })).toBeVisible()
    expect(within(cards[3]!).getByRole('heading', { name: '2순위' })).toBeVisible()
    expect(within(firstComplex).getByRole('heading', { name: '우선공급' })).toBeVisible()
    expect(within(firstComplex).getByText('미달 시 진행')).toBeVisible()
    expect(within(screen.getByRole('region', { name: '봇들마을 접수 일정' })).getAllByRole('listitem')).toHaveLength(1)
    expect(JSON.stringify(schedules)).toBe(original)
  })

  it('접수일 없이 발표일만 있으면 후속 일정에 표시한다', () => {
    render(<HousingAnnouncementDetailPanel detail={detail({ schedules: [],
      applicationStartAt: null, applicationEndAt: null })} onClose={vi.fn()} />)
    const reception = screen.getByRole('heading', { name: '접수 일정' }).closest('section')!
    expect(within(reception).getByText('공고문 확인')).toBeVisible()
    expect(within(reception).queryByRole('table')).not.toBeInTheDocument()
    expect(within(screen.getByRole('table', { name: '후속 일정' }))
      .getByRole('rowheader', { name: '당첨자 발표' })).toBeVisible()
  })

  it('단지 연결 ID가 없어도 제공된 단지명을 보존하고 전체 공통 일정과 구분한다', () => {
    render(<HousingAnnouncementDetailPanel detail={detail({
      supplyRows: [supplyRow({ complex: null, sourceComplexName: '미연결 단지' })],
      applicationSchedules: [
        applicationSchedule({ scheduleId: 'named', housingComplexId: null, complexName: '이름만 제공된 단지' }),
        applicationSchedule({ scheduleId: 'common', housingComplexId: null, complexName: null }),
      ],
    })} onClose={vi.fn()} />)
    expect(screen.getByRole('region', { name: '이름만 제공된 단지 접수 일정' })).toBeVisible()
    expect(screen.getByRole('region', { name: '전체 단지 공통 접수 일정' })).toBeVisible()
  })

  it('접수 외의 일정 종류와 시작일 없는 일정도 후속 일정에서 누락하지 않는다', () => {
    render(<HousingAnnouncementDetailPanel detail={detail({
      winnerAnnouncementAt: null,
      schedules: [
        legacySchedule({ scheduleId: 'unknown', type: null, typeLabel: '기타', startAt: null, endAt: null }),
        legacySchedule({ scheduleId: 'contract', type: 'CONTRACT', typeLabel: '계약',
          startAt: '2026-11-10', endAt: '2026-11-12' }),
        legacySchedule({ scheduleId: 'etc', type: 'ETC', typeLabel: '추가 안내',
          startAt: null, endAt: '2026-11-01' }),
      ],
    })} onClose={vi.fn()} />)
    const followUps = screen.getByRole('table', { name: '후속 일정' })
    expect(within(followUps).getAllByRole('rowheader')
      .map((header) => header.textContent).filter((text) => !['시작', '종료'].includes(text!)))
      .toEqual(['추가 안내', '계약', '기타'])
    expect(within(followUps).getByText('2026.11.01')).toBeVisible()
  })

  it('단지와 공급 순위별 확정·조건부 접수 일정을 구분하고 공통 일정을 보존한다', () => {
    render(<HousingAnnouncementDetailPanel detail={detail({
      applicationSchedules: [
        applicationSchedule({ scheduleId: '3', housingComplexId: null, complexName: null, supplyRank: null }),
        applicationSchedule({ scheduleId: '1' }),
        applicationSchedule({ scheduleId: '2', supplyRank: '2순위', state: 'CONDITIONAL',
          condition: '1순위 미달 시 진행', startDate: '2026-09-02', endDate: '2026-09-02',
          startTime: null, endTime: null }),
        applicationSchedule({ scheduleId: '4', housingComplexId: '102', complexName: '봇들마을' }),
      ],
      schedules: [{ scheduleId: 'old', type: 'APPLICATION', typeLabel: '접수 기간',
        name: '기존 접수', startAt: '2026-08-28T09:00:00', endAt: '2026-08-30T18:00:00' }],
    })} onClose={vi.fn()} />)
    const firstComplex = screen.getByRole('region', { name: '새솔마을 접수 일정' })
    expect(within(firstComplex).getByRole('heading', { name: '1순위' })).toBeVisible()
    expect(within(firstComplex).getByRole('heading', { name: '2순위' })).toBeVisible()
    expect(within(firstComplex).getByText('확정')).toBeVisible()
    expect(within(firstComplex).getByText('조건부')).toBeVisible()
    expect(within(firstComplex).getByText('1순위 미달 시 진행')).toBeVisible()
    expect(within(firstComplex).getByText('2026.09.01 09:00')).toBeVisible()
    expect(within(firstComplex).getByText('2026.09.01 18:00')).toBeVisible()
    expect(within(firstComplex).getByText('2026.09.02')).toBeVisible()
    expect(within(firstComplex).queryByText(/00:00/)).not.toBeInTheDocument()
    expect(within(firstComplex).getAllByRole('link', { name: '공고문 3쪽' })[0])
      .toHaveAttribute('href', 'https://example.com/schedule.pdf#page=3')
    expect(screen.getByRole('region', { name: '전체 단지 공통 접수 일정' })).toBeVisible()
    expect(screen.getByRole('region', { name: '봇들마을 접수 일정' })).toBeVisible()
    expect(screen.queryByText('기존 접수')).not.toBeInTheDocument()
    expect(screen.queryByText('현재 단계')).not.toBeInTheDocument()
  })

  it('일정의 단지명이 없으면 공급 단지명으로 식별하고 안전하지 않은 출처는 링크로 만들지 않는다', () => {
    render(<HousingAnnouncementDetailPanel detail={detail({
      applicationSchedules: [applicationSchedule({ complexName: null, sourceUrl: 'javascript:alert(1)' })],
    })} onClose={vi.fn()} />)
    const group = screen.getByRole('region', { name: '새솔마을 접수 일정' })
    expect(within(group).getByRole('heading', { name: '1순위' })).toBeVisible()
    expect(within(group).queryByRole('link')).not.toBeInTheDocument()
  })

  it('공고중에는 접수 시작까지 남은 일수를 표시한다', () => {
    render(<HousingAnnouncementDetailPanel detail={detail({
      applicationStatus: 'BEFORE_APPLICATION', applicationStatusLabel: '공고중', dDay: 3,
    })} onClose={vi.fn()} />)
    expect(screen.getByLabelText('접수 시작까지 3일')).toHaveTextContent('접수 시작 D-3')
  })

  it('조건부 접수의 상단에 확정 디데이를 표시하지 않는다', () => {
    render(<HousingAnnouncementDetailPanel detail={detail({
      applicationStatus: 'CONDITIONAL', applicationStatusLabel: '조건부 접수', dDay: 3,
    })} onClose={vi.fn()} />)
    expect(screen.getByText('조건부 접수')).toBeVisible()
    expect(screen.queryByText('D-3')).not.toBeInTheDocument()
  })

  it('단지가 하나여도 단지명 선택 탭과 주택형 패널을 연결한다', () => {
    render(<HousingAnnouncementDetailPanel detail={detail({
      supplyRows: [supplyRow()], supplyComplexCount: 1,
    })} onClose={vi.fn()} />)
    const tab = screen.getByRole('tab', { name: /새솔마을/ })
    expect(tab).toHaveAttribute('aria-selected', 'true')
    const panel = screen.getByRole('tabpanel', { name: /새솔마을/ })
    expect(tab).toHaveAttribute('aria-controls', panel.id)
    expect(within(panel).getByRole('article', { name: '새솔마을 36A 주택형' })).toBeVisible()
    fireEvent.keyDown(tab, { key: 'ArrowRight' })
    expect(tab).toHaveFocus()
  })

  it.each([
    ['16700003', '1670-0003'],
    ['021234567', '02-123-4567'],
    ['0212345678', '02-1234-5678'],
    ['0311234567', '031-123-4567'],
    ['01012345678', '010-1234-5678'],
    ['1670-0003', '1670-0003'],
    ['대표 16700003 / 0311234567 (내선 123)', '대표 1670-0003 / 031-123-4567 (내선 123)'],
    ['공고문 참조', '공고문 참조'],
  ])('문의 전화번호 %s의 구분 기호와 안내를 보존한다', (phoneNumber, expected) => {
    render(<HousingAnnouncementDetailPanel detail={detail({ receptionPlaces: [{
      name: '상담센터', methodLabel: '전화', address: null, url: null, phoneNumber,
    }] })} onClose={vi.fn()} />)
    expect(screen.getByText(expected)).toBeVisible()
  })

  it('상세 제목에 focus를 옮길 때 목록과 페이지를 스크롤하지 않는다', () => {
    const focus = vi.spyOn(HTMLHeadingElement.prototype, 'focus')
    renderPanel()
    expect(screen.getByRole('heading', {
      name: '성남 행복주택 예비입주자 모집',
    })).toHaveFocus()
    expect(focus).toHaveBeenCalledWith({ preventScroll: true })
    focus.mockRestore()
  })


  it('확정 시안 B의 판단 정보와 단지·주택형 공급 단위를 구분해 표시한다', async () => {
    renderPanel()

    const panel = screen.getByRole('complementary', {
      name: '성남 행복주택 예비입주자 모집 상세 정보',
    })
    expect(within(panel).getByRole('heading', {
      name: '성남 행복주택 예비입주자 모집',
    })).toHaveFocus()
    expect(within(panel).getByRole('heading', { name: '공고 핵심 정보' })).toBeVisible()
    expect(within(panel).getByText('정정공고 안내')).toBeVisible()
    expect(within(panel).getByText('접수 일정 정정')).toBeVisible()
    expect(within(panel).getAllByText('청년').length).toBeGreaterThan(0)
    expect(within(panel).getByText('현재 단계')).toBeVisible()
    expect(within(panel).getByText('조회 0')).toBeVisible()
    expect(within(panel).getAllByText('공급 세대수').length).toBeGreaterThan(0)
    expect(within(panel).getByText('모집 예비자 수')).toBeVisible()
    expect(within(panel).getByText('30명')).toBeVisible()
    expect(within(panel).queryByText(/내 정보 기준/)).not.toBeInTheDocument()
    expect(within(panel).queryByText(/경쟁률 예측/)).not.toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: /공고 저장/ })).not.toBeInTheDocument()

    const firstComplex = within(panel).getByRole('article', {
      name: '새솔마을 단지 비교',
    })
    expect(within(firstComplex).getByText('1,046세대')).toBeVisible()
    expect(within(firstComplex).getByText('12세대')).toBeVisible()
    expect(within(firstComplex).getByText('36.2㎡')).toBeVisible()
    expect(within(firstComplex).getByText('3,200만원')).toBeVisible()
    expect(within(firstComplex).getByText('12.8만원')).toBeVisible()

    const firstHousingType = within(panel).getByRole('article', { name: '새솔마을 36A 주택형' })
    expect(within(firstHousingType).getByText('평면도: 공고문 확인')).toBeVisible()
    expect(within(firstHousingType).queryByText('대상별 공급 조건: 공고문 확인')).not.toBeInTheDocument()

    await waitFor(() => {
      expect(within(panel).getByRole('button', { name: '공고문 보기' })).toBeEnabled()
    })
    expect(within(panel).getByRole('link', { name: '공고 원문' }))
      .toHaveAttribute('href', 'https://example.com/notice')
  })

  it('단지 상세 콜백과 주택형 단지 탭의 키보드 이동을 연결한다', () => {
    const onOpenComplex = vi.fn()
    renderPanel({ onOpenComplex })
    const panel = screen.getByRole('complementary')

    fireEvent.click(within(panel).getByRole('button', {
      name: '새솔마을 단지 상세 보기',
    }))
    expect(onOpenComplex).toHaveBeenCalledWith('101')

    const tabs = within(panel).getByRole('tablist', {
      name: '주택형을 볼 단지 선택',
    })
    const firstTab = within(tabs).getByRole('tab', { name: /새솔마을/ })
    const secondTab = within(tabs).getByRole('tab', { name: /봇들마을/ })
    fireEvent.keyDown(firstTab, { key: 'ArrowRight' })

    expect(secondTab).toHaveAttribute('aria-selected', 'true')
    expect(secondTab).toHaveFocus()
    expect(within(panel).getByRole('article', {
      name: '봇들마을 44B 주택형',
    })).toBeVisible()
    const secondHousingType = within(panel).getByRole('article', { name: '봇들마을 44B 주택형' })
    expect(within(secondHousingType).getByText('대상별 공급 조건: 공고문 확인')).toBeVisible()
    expect(within(secondHousingType).queryByText('평면도: 공고문 확인')).not.toBeInTheDocument()

    fireEvent.keyDown(secondTab, { key: 'Home' })
    expect(firstTab).toHaveAttribute('aria-selected', 'true')
    expect(firstTab).toHaveFocus()
  })

  it('API 접수 일정의 시각을 보존하고 상단 날짜 fallback을 중복하지 않는다', () => {
    render(
      <HousingAnnouncementDetailPanel
        detail={detail({
          schedules: [{
            endAt: '2026-08-28T18:00:00',
            name: '인터넷 접수',
            scheduleId: '500',
            startAt: '2026-08-28T09:00:00',
            type: 'APPLICATION',
            typeLabel: '접수',
          }, {
            endAt: '2026-08-29T18:00:00',
            name: '현장 접수',
            scheduleId: '501',
            startAt: '2026-08-29T09:00:00',
            type: 'APPLICATION',
            typeLabel: '접수',
          }],
        })}
        onClose={vi.fn()}
      />,
    )

    const schedule = screen.getByRole('heading', { name: '접수 일정' })
      .closest('section')
    expect(schedule).not.toBeNull()
    expect(within(schedule!).getByText('인터넷 접수')).toBeVisible()
    const scheduleTable = within(schedule!).getByRole('table', { name: '접수 일정' })
    expect(within(scheduleTable).getByText('2026.08.28 09:00')).toBeVisible()
    expect(within(scheduleTable).getByText('2026.08.28 18:00')).toBeVisible()
    expect(within(scheduleTable).getByText('2026.08.28 09:00'))
      .toHaveAttribute('datetime', '2026-08-28T09:00:00')
    const firstTimes = within(scheduleTable).getByText('2026.08.28 09:00').closest('tr')
    expect(within(firstTimes!).getAllByRole('rowheader')).toHaveLength(2)
    expect(within(firstTimes!).getAllByRole('cell')).toHaveLength(2)
    expect(within(scheduleTable).getByText('2026.08.28 09:00').closest('td'))
      .toHaveAttribute('headers', [
        within(scheduleTable).getByRole('rowheader', { name: '인터넷 접수' }).id,
        within(firstTimes!).getByRole('rowheader', { name: '시작' }).id,
      ].join(' '))
    expect(within(scheduleTable).queryByRole('columnheader', { name: '상태' }))
      .not.toBeInTheDocument()
    expect(within(schedule!).queryByText('접수 기간')).not.toBeInTheDocument()
    expect(within(schedule!).queryByText('현재 단계')).not.toBeInTheDocument()
  })

  it('API 접수 일정이 없을 때만 상단 접수기간을 현재 단계 fallback으로 표시한다', () => {
    render(
      <HousingAnnouncementDetailPanel
        detail={detail({ schedules: [] })}
        onClose={vi.fn()}
      />,
    )

    const schedule = screen.getByRole('heading', { name: '접수 일정' })
      .closest('section')
    expect(schedule).not.toBeNull()
    expect(within(schedule!).getByText('접수 기간')).toBeVisible()
    const scheduleTable = within(schedule!).getByRole('table', { name: '접수 일정' })
    expect(within(scheduleTable).getByText('2026.08.28')).toBeVisible()
    expect(within(scheduleTable).getByText('2026.08.30')).toBeVisible()
    expect(within(scheduleTable).getByRole('rowheader', { name: '접수 기간 현재 단계' })).toBeVisible()
    expect(within(schedule!).getByText('현재 단계')).toBeVisible()
  })

  it('연결되지 않은 한글 단지 그룹도 고유한 탭 관계를 만든다', () => {
    render(
      <HousingAnnouncementDetailPanel
        detail={detail({
          supplyRows: [
            supplyRow({
              complex: null,
              sourceComplexName: '가나다',
              supplyRowId: '401',
              totalSupplyHouseholdCount: null,
            }),
            supplyRow({
              complex: null,
              sourceComplexName: '라마바',
              supplyRowId: '402',
            }),
          ],
        })}
        onClose={vi.fn()}
      />,
    )

    const tablist = screen.getByRole('tablist', {
      name: '주택형을 볼 단지 선택',
    })
    const tabs = within(tablist).getAllByRole('tab')
    expect(within(tabs[0]!).getByText('공급 세대수: 공고문 확인')).toBeVisible()
    expect(within(screen.getByRole('article', { name: '가나다 단지 비교' })).getByText('주소: 공고문 확인')).toBeVisible()
    const panel = screen.getByRole('tabpanel')
    expect(tabs[0]).not.toHaveAttribute('id', tabs[1]?.id)
    expect(tabs[0]).toHaveAttribute('aria-controls', panel.id)
    expect(panel).toHaveAttribute('aria-labelledby', tabs[0]?.id)

    fireEvent.click(tabs[1]!)
    expect(panel).toHaveAttribute('aria-labelledby', tabs[1]?.id)
  })

  it('실제 평면도 링크가 있을 때만 모달을 열고 Escape 후 실행 버튼으로 포커스를 돌린다', async () => {
    renderPanel()
    const panel = screen.getByRole('complementary')
    const secondTab = within(panel).getByRole('tab', { name: /봇들마을/ })
    fireEvent.click(secondTab)
    const openFloorPlan = within(panel).getByRole('button', {
      name: '봇들마을 44B 평면도 보기',
    })

    openFloorPlan.focus()
    fireEvent.click(openFloorPlan)
    const dialog = screen.getByRole('dialog', { name: '44B 평면도' })
    expect(within(dialog).getByRole('img', { name: '44B 2D 평면도' }))
      .toHaveAttribute('src', 'https://example.com/44b.png')
    expect(within(dialog).getByText('3D 평면도: 공고문 확인')).toBeVisible()
    expect(within(dialog).getByRole('button', { name: '평면도 닫기' })).toHaveFocus()

    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(screen.queryByRole('dialog', { name: '44B 평면도' })).not.toBeInTheDocument()
    await waitFor(() => expect(openFloorPlan).toHaveFocus())
  })

  it('닫기 버튼과 패널 Escape가 각각 닫기 콜백을 한 번 호출한다', () => {
    const onClose = vi.fn()
    renderPanel({ onClose })
    const panel = screen.getByRole('complementary')

    fireEvent.click(within(panel).getByRole('button', { name: '공고 상세 닫기' }))
    expect(onClose).toHaveBeenCalledTimes(1)

    fireEvent.keyDown(panel, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('공고로 끝나는 제목의 접근성 이름과 변경 공고 배지를 중복하지 않는다', () => {
    const { container } = render(
      <HousingAnnouncementDetailPanel
        detail={detail({ title: '성남 행복주택 모집 공고' })}
        onClose={vi.fn()}
      />,
    )

    expect(screen.getByRole('complementary', {
      name: '성남 행복주택 모집 공고 상세 정보',
    })).toBeVisible()
    expect(screen.queryByRole('complementary', {
      name: /공고 공고 상세 정보/,
    })).not.toBeInTheDocument()
    expect(container.querySelectorAll('[data-publication="changed"]')).toHaveLength(1)
  })

  it('null과 안전하지 않은 URL은 공고문 확인과 비활성 링크로 표시한다', () => {
    const unsafe = detail({
      agencyCode: null,
      agencyName: null,
      applicationEndAt: null,
      applicationStartAt: null,
      applicationStatus: null,
      applicationStatusLabel: '공고문 확인',
      attachments: [{
        attachmentId: '601',
        fileName: null,
        fileTypeLabel: '공고문',
        fileUrl: 'javascript:alert(1)',
      }],
      correctionOrCancellationReason: null,
      dDay: null,
      documentLinkUrl: 'data:text/html,unsafe',
      publicationTypeLabel: '원공고',
      receptionPlaces: [],
      regionNames: [],
      schedules: [],
      supplyComplexCount: 0,
      supplyHouseholdCount: null,
      supplyRows: [],
      targets: [],
      title: null,
      winnerAnnouncementAt: null,
    })
    render(<HousingAnnouncementDetailPanel detail={unsafe} onClose={vi.fn()} />)
    const panel = screen.getByRole('complementary', {
      name: '공고문 확인 상세 정보',
    })

    expect(within(panel).getByText('공사').parentElement).toHaveTextContent('공고문 확인')
    expect(within(panel).getByText('지역').parentElement).toHaveTextContent('공고문 확인')
    for (const title of ['신청 대상', '접수 일정']) {
      const section = within(panel).getByRole('heading', { name: title }).closest('section')
      expect(within(section!).getByText('공고문 확인')).toBeVisible()
    }
    expect(within(panel).getByText('0개 단지')).toBeVisible()
    const complexSection = within(panel).getByRole('heading', { name: '단지 비교' }).closest('section')
    expect(within(complexSection!).getByText('0개 단지 · 공급 세대수: 공고문 확인')).toBeVisible()
    expect(within(complexSection!).getByText('단지 정보: 공고문 확인')).toBeVisible()
    expect(within(complexSection!).queryByText('공고문 확인')).not.toBeInTheDocument()
    const footer = within(panel).getByRole('navigation', { name: '공고문 바로가기' }).parentElement
    expect(within(footer!).queryByText('첨부파일 정보 없음')).not.toBeInTheDocument()
    expect(within(footer!).queryByText(/연결됨|링크 없음/)).not.toBeInTheDocument()
    expect(within(panel).queryByText('공고문 확인 · 공고문 확인')).not.toBeInTheDocument()
    expect(within(panel).queryByRole('link', { name: '첨부파일' })).not.toBeInTheDocument()
    expect(within(panel).queryByRole('link', { name: '공고 원문' })).not.toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: '공고문 보기' })).toBeDisabled()
    expect(within(panel).getByText('공고 원문', { selector: '[aria-disabled="true"]' }))
      .toBeVisible()
  })

  it('여러 첨부는 공고문 보기 버튼으로 제공하고 하단 설명을 중복하지 않는다', () => {
    render(
      <HousingAnnouncementDetailPanel
        detail={detail({
          attachments: [{
            attachmentId: '602',
            fileName: '참고자료.pdf',
            fileTypeLabel: '참고자료',
            fileUrl: 'https://example.com/reference.pdf',
          }, {
            attachmentId: '601',
            fileName: '정정공고문.pdf',
            fileTypeLabel: '정정공고문',
            fileUrl: 'https://example.com/correction.pdf',
          }],
        })}
        onClose={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: '공고문 보기' })).toBeEnabled()
    const navigation = screen.getByRole('navigation', { name: '공고문 바로가기' })
    expect(navigation.parentElement).toHaveTextContent(/^공고문 보기공고 원문$/)
    expect(screen.queryByText(/연결됨/)).not.toBeInTheDocument()
  })

  it('주택형과 대상별 조건은 항목·값 두 쌍의 4열 표로 표시한다', () => {
    renderPanel()

    const housingType = screen.getByRole('table', { name: '새솔마을 36A 공급 정보' })
    const housingRows = within(housingType).getAllByRole('row')
    expect(housingRows).toHaveLength(2)
    expect(within(housingRows[0]!).getAllByRole('rowheader')).toHaveLength(2)
    expect(within(housingRows[0]!).getAllByRole('cell')).toHaveLength(2)
    expect(within(housingType).getByRole('rowheader', { name: '공급 구분' }))
      .toHaveAttribute('scope', 'row')
    expect(within(housingType).getByRole('rowheader', { name: '입주 예정' })).toBeVisible()
    expect(within(housingType).getByRole('cell', { name: '신규공급' }))
      .toHaveAttribute('headers', within(housingType).getByRole('rowheader', { name: '공급 구분' }).id)

    const conditions = screen.getByRole('table', { name: '청년 공급 조건' })
    const conditionRows = within(conditions).getAllByRole('row')
    expect(within(conditionRows[0]!).getAllByRole('rowheader')).toHaveLength(2)
    expect(within(conditionRows[0]!).getAllByRole('cell')).toHaveLength(2)
    expect(within(conditions).getByRole('cell', { name: '무주택 세대구성원' }))
      .toHaveAttribute('colspan', '3')
    expect(within(conditions).getByRole('rowheader', { name: '모집 예비자 수' })).toBeVisible()
    expect(within(conditions).getByRole('cell', { name: '3,200만원' }))
      .toHaveAttribute('data-emphasis', 'true')
    expect(within(conditions).getByRole('cell', { name: '3,200만원' }))
      .toHaveAttribute('headers', within(conditions).getByRole('rowheader', { name: '보증금' }).id)
    expect(within(conditions).getByRole('cell', { name: '12.8만원' }))
      .toHaveAttribute('data-numeric', 'true')
  })

  it('만원·억 금액과 긴 범위를 표시하고 월 임대료의 월 접두사를 반복하지 않는다', () => {
    const baseRow = supplyRow()
    const baseTarget = baseRow.targets[0]!
    render(
      <HousingAnnouncementDetailPanel
        detail={detail({ supplyRows: [supplyRow({
          targets: [
            { ...baseTarget, deposit: 18_000_000, monthlyRent: 180_000 },
            { ...baseTarget, supplyTargetId: '702', target: '신혼부부', deposit: 180_000_000, monthlyRent: 0 },
          ],
        })] })}
        onClose={vi.fn()}
      />,
    )

    const complex = screen.getByRole('article', { name: '새솔마을 단지 비교' })
    const range = within(complex).getByText('1,800만원 – 1.8억')
    expect(range.closest('[data-wide]')).toHaveAttribute('data-wide', 'true')
    const young = screen.getByRole('table', { name: '청년 공급 조건' })
    expect(within(young).getByRole('cell', { name: '1,800만원' })).toBeVisible()
    expect(within(young).getByRole('cell', { name: '18만원' })).toBeVisible()
    const newlywed = screen.getByRole('table', { name: '신혼부부 공급 조건' })
    expect(within(newlywed).getByRole('cell', { name: '1.8억' })).toBeVisible()
    expect(within(newlywed).getByRole('cell', { name: '0원' })).toBeVisible()
    expect(screen.queryByText('월 18만원')).not.toBeInTheDocument()
  })

  it('상단 공고 정보는 항목명 접두어 없이 기존 값과 접근성 설명을 유지한다', () => {
    render(
      <HousingAnnouncementDetailPanel
        detail={detail({
          applicationStatus: null,
          applicationStatusLabel: '공고문 확인',
          dDay: null,
          rentalTypeLabel: '공고문 확인',
          publicationTypeLabel: '공고문 확인',
          recruitmentTypeLabel: '공고문 확인',
          agencyCode: null,
          agencyName: null,
          regionNames: [],
        })}
        onClose={vi.fn()}
      />,
    )
    const context = screen.getByRole('group', { name: '임대유형 · 접수상태 · 공고구분 공고문 확인' })
    expect(within(context).getAllByText('공고문 확인')).toHaveLength(1)
    expect(context).not.toHaveTextContent(':')
    const intro = screen.getByRole('region', { name: '공고 요약' })
    expect(intro).not.toHaveTextContent(':')
    expect(within(intro).getByText('공사·지역')).toBeInTheDocument()
    expect(within(intro).getAllByText('공고문 확인')).toHaveLength(2)
  })

  it('좁은 패널의 표 스크롤과 문서 1열 규칙을 스타일에 고정한다', () => {
    const css = readFileSync(
      resolve(
        process.cwd(),
        'src/public-housing/components/HousingAnnouncementDetailPanel.module.css',
      ),
      'utf8',
    )
    const compactRule = css.slice(
      css.indexOf('@container housing-announcement-detail (max-width: 420px)'),
      css.indexOf('@container housing-announcement-detail (max-width: 330px)'),
    )

    expect(css).toContain('container: housing-announcement-detail / inline-size;')
    const primitivesCss = readFileSync(
      resolve(process.cwd(), 'src/public-housing/components/DetailPrimitives.module.css'),
      'utf8',
    )
    const compactHeadingRule = primitivesCss.slice(
      primitivesCss.indexOf('@container (max-width: 420px)'),
    )
    expect(compactHeadingRule).toMatch(
      /\.sectionHeading[\s\S]*?display:\s*block;/,
    )
    expect(primitivesCss).toMatch(/\.tableViewport[\s\S]*?overflow-x:\s*auto;/)
    expect(primitivesCss).toMatch(/\.table\s*\{[\s\S]*?width:\s*100%;/)
    expect(compactRule).toMatch(
      /\.documents[\s\S]*?grid-template-columns:\s*minmax\(0, 1fr\);/,
    )
  })
})

function renderPanel({
  onClose = vi.fn(),
  onOpenComplex = vi.fn(),
}: {
  onClose?: () => void
  onOpenComplex?: (complexId: string) => void
} = {}) {
  return render(
    <HousingAnnouncementDetailPanel
      detail={detail()}
      onClose={onClose}
      onOpenComplex={onOpenComplex}
    />,
  )
}

function detail(
  changes: Partial<HousingAnnouncementDetailData> = {},
): HousingAnnouncementDetailData {
  return {
    agencyCode: 'LH',
    agencyName: '한국토지주택공사',
    announcementId: '201',
    applicationEndAt: '2026-08-30',
    applicationStartAt: '2026-08-28',
    applicationStatus: 'APPLYING',
    applicationStatusLabel: '접수중',
    attachments: [
      {
        attachmentId: '601',
        fileName: '공고문.pdf',
        fileTypeLabel: '공고문',
        fileUrl: 'https://example.com/notice.pdf',
      },
      {
        attachmentId: '602',
        fileName: '참고자료.pdf',
        fileTypeLabel: '참고자료',
        fileUrl: null,
      },
    ],
    correctionOrCancellationReason: '접수 일정 정정',
    dDay: 2,
    documentLinkUrl: 'https://example.com/notice',
    publicationTypeLabel: '정정공고',
    publishedAt: '2026-08-20',
    receptionPlaces: [{
      address: null,
      methodLabel: '온라인',
      name: 'LH청약플러스',
      phoneNumber: null,
      url: 'https://example.com/apply',
    }],
    recruitmentTypeLabel: '예비입주자 모집',
    regionNames: ['경기 성남시'],
    rentalTypeLabel: '행복주택',
    schedules: [{
      endAt: '2026-09-02T18:00:00',
      name: '서류 제출',
      scheduleId: '501',
      startAt: '2026-09-01T09:00:00',
      type: 'DOCUMENT_SUBMISSION',
      typeLabel: '서류 제출',
    }],
    supplyComplexCount: 2,
    supplyHouseholdCount: 20,
    supplyRows: [
      supplyRow(),
      supplyRow({
        complex: {
          address: '경기 성남시 분당구',
          complexId: '102',
          name: '봇들마을',
          overviewImageUrl: 'https://example.com/botdeul.jpg',
          totalHouseholdCount: 794,
        },
        housingType: {
          exclusiveArea: 44.1,
          floorPlan3dImageUrl: null,
          floorPlanImageUrl: 'https://example.com/44b.png',
          housingTypeId: '302',
          name: '44B',
          supplyArea: 60.2,
        },
        sourceComplexName: '봇들마을',
        sourceHousingTypeName: '44B',
        supplyRowId: '402',
        targets: [],
        totalSupplyHouseholdCount: 8,
      }),
    ],
    targets: ['청년'],
    title: '성남 행복주택 예비입주자 모집',
    viewCount: 0,
    winnerAnnouncementAt: '2026-09-10',
    ...changes,
  }
}

function supplyRow(
  changes: Partial<HousingAnnouncementDetailData['supplyRows'][number]> = {},
): HousingAnnouncementDetailData['supplyRows'][number] {
  return {
    complex: {
      address: '경기 성남시 수정구',
      complexId: '101',
      name: '새솔마을',
      overviewImageUrl: null,
      totalHouseholdCount: 1046,
    },
    housingType: {
      exclusiveArea: 36.2,
      floorPlan3dImageUrl: null,
      floorPlanImageUrl: null,
      housingTypeId: '301',
      name: '36A',
      supplyArea: 50.1,
    },
    occupancyExpectedYearMonth: '202611',
    sourceComplexName: '새솔마을',
    sourceHousingTypeName: '36A',
    supplyRowId: '401',
    supplyTypeLabel: '신규공급',
    targets: [{
      applicationCondition: '무주택 세대구성원',
      convertibleDeposit: null,
      deposit: 32000000,
      monthlyRent: 128000,
      priority: '1순위',
      supplyHouseholdCount: 12,
      supplyTargetId: '701',
      target: '청년',
      waitlistCount: 30,
    }],
    totalSupplyHouseholdCount: 12,
    ...changes,
  }
}

function applicationSchedule(overrides = {}) {
  return {
    scheduleId: '1', housingComplexId: '101', complexName: '새솔마을', supplyRank: '1순위',
    state: 'CONFIRMED', condition: null, startDate: '2026-09-01', endDate: '2026-09-01',
    startTime: '09:00:00', endTime: '18:00:00', sourceUrl: 'https://example.com/schedule.pdf',
    sourcePage: 3, ...overrides,
  }
}

function legacySchedule(
  overrides: Partial<HousingAnnouncementDetailData['schedules'][number]> = {},
): HousingAnnouncementDetailData['schedules'][number] {
  return { scheduleId: 'application', type: 'APPLICATION', typeLabel: '접수', name: null,
    startAt: '2026-09-28T10:00:00', endAt: '2026-09-28T16:10:00', ...overrides }
}
