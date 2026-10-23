import type { NotificationInterestRepository } from '../interest/notificationInterestRepository'
import { MemoryRouter } from 'react-router'
/// <reference types="node" />

import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  HousingComplexDetailPanel,
  type HousingComplexDetailData,
} from './HousingComplexDetailPanel'
import { NotificationInterestProvider } from '../interest/NotificationInterest'

const BASE_DETAIL: HousingComplexDetailData = {
  complexId: '17',
  name: '서울가람 행복주택',
  rentalTypeLabel: '행복주택',
  agencyName: '한국토지주택공사',
  regionName: '서울특별시 중구',
  roadAddress: '서울특별시 중구 세종대로 110',
  completionDate: '2020-01-01',
  buildingTypeLabel: '아파트',
  hasElevator: true,
  heatingTypeLabel: '개별난방',
  corridorTypeLabel: '계단식',
  moveOutCountLastYear: 7,
  totalHouseholdCount: 100,
  totalParkingCount: 80,
  images: [
    'https://example.com/complex.png',
    'javascript:alert(1)',
  ],
  overviewImageUrl: 'https://example.com/overview.png',
  housingTypes: [
    {
      housingTypeId: '101',
      name: '36A',
      exclusiveArea: 36.12,
      supplyArea: null,
      floorPlanImageUrl: 'https://example.com/floor.png',
      floorPlan3dImageUrl: null,
      isDuplex: false,
      maintenanceFee: null,
      basicRentalCondition: null,
      currentSupplyConditions: [
        {
          target: '청년',
          deposit: 50_000_000,
          monthlyRent: 200_000,
          convertibleDeposit: null,
        },
      ],
    },
    {
      housingTypeId: '102',
      name: '44B',
      exclusiveArea: 44.87,
      supplyArea: 60.12,
      floorPlanImageUrl: null,
      floorPlan3dImageUrl: null,
      isDuplex: true,
      maintenanceFee: 123_456,
      basicRentalCondition: null,
      currentSupplyConditions: [],
    },
  ],
  currentAnnouncements: [
    {
      announcementId: '201',
      title: '행복주택 입주자 모집 공고',
      publicationTypeLabel: '원공고',
      applicationStatus: 'APPLYING',
      targets: ['청년'],
      applicationStartAt: '2026-08-20',
      applicationEndAt: '2026-08-27',
      dDay: 0,
      actualCompetitionRate: 0,
    },
  ],
}

function renderPanel(detail: HousingComplexDetailData = BASE_DETAIL) {
  const onClose = vi.fn()
  const onOpenAnnouncement = vi.fn()
  const result = render(
    <HousingComplexDetailPanel
      detail={detail}
      onClose={onClose}
      onOpenAnnouncement={onOpenAnnouncement}
    />,
  )
  const panel = screen.getByRole('complementary', {
    name: `${detail.name} 단지 상세 정보`,
  })

  return { ...result, onClose, onOpenAnnouncement, panel }
}

it('모바일은 단지명만 고정 헤더에 두고 공급기관과 주소를 스크롤 본문 앞에 표시한다', () => {
  render(<HousingComplexDetailPanel detail={{ ...BASE_DETAIL, agencyCode: 'LH' }} mobile onClose={() => {}} />)
  const title = screen.getByRole('heading', { name: BASE_DETAIL.name })
  const header = title.closest('header')!
  const content = screen.getByRole('region', { name: `${BASE_DETAIL.name} 단지 상세 내용` })
  expect(header).toHaveTextContent(BASE_DETAIL.name)
  expect(within(header).queryByText('LH')).not.toBeInTheDocument()
  expect(within(header).queryByText('행복주택')).not.toBeInTheDocument()
  expect(content).not.toContainElement(title)
  const agency = within(content).getByText('LH')
  const address = within(content).getByText(BASE_DETAIL.roadAddress!)
  expect(title.compareDocumentPosition(agency) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(agency.compareDocumentPosition(address) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(address.compareDocumentPosition(within(content).getAllByRole('img')[0]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
})

function detailWith(
  changes: Partial<HousingComplexDetailData>,
): HousingComplexDetailData {
  return { ...BASE_DETAIL, ...changes }
}

function section(panel: HTMLElement, name: string) {
  const heading = within(panel).getByRole('heading', { name })
  const result = heading.closest('section')
  if (result === null) {
    throw new Error(`${name} section not found`)
  }
  return result
}

function factValue(container: HTMLElement, term: string) {
  const element = within(container).getByText(term).parentElement
  if (element === null) {
    throw new Error(`${term} fact not found`)
  }
  return within(element)
}

describe('HousingComplexDetailPanel', () => {
  beforeEach(() => localStorage.removeItem('toadzip.area-unit'))

  it('데스크톱도 단지명만 헤더에 두고 공급기관·주택 유형은 주소와 함께 본문에 표시한다', () => {
    const { panel } = renderPanel()
    const header = panel.querySelector('header')
    if (!header) throw new Error('상세 헤더 없음')
    const heading = within(header).getByRole('heading', { level: 2, name: BASE_DETAIL.name })
    const content = within(panel).getByRole('region', { name: `${BASE_DETAIL.name} 단지 상세 내용` })

    expect(heading).toHaveFocus()
    expect(within(header).queryByText(BASE_DETAIL.agencyName)).not.toBeInTheDocument()
    expect(within(header).queryByText(BASE_DETAIL.rentalTypeLabel)).not.toBeInTheDocument()
    expect(within(header).getByRole('button', { name: '단지 상세 닫기' })).toBeVisible()
    expect(within(content).queryByText(BASE_DETAIL.name)).not.toBeInTheDocument()
    const identity = within(content).getByRole('region', { name: BASE_DETAIL.name })
    expect(within(identity).getByText(BASE_DETAIL.agencyName)).toBeVisible()
    expect(within(identity).getByText(BASE_DETAIL.rentalTypeLabel)).toBeVisible()
    const address = within(identity).getByText('서울특별시 중구 세종대로 110')
    expect(within(identity).getByText(BASE_DETAIL.rentalTypeLabel).compareDocumentPosition(address)
      & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('공고가 없어도 주택형 기본 보증금과 월 임대료 및 출처를 표시하고 탭 전환에 맞춰 바꾼다', () => {
    const { panel } = renderPanel(detailWith({
      currentAnnouncements: [],
      housingTypes: BASE_DETAIL.housingTypes.map((housingType, index) => ({
        ...housingType,
        currentSupplyConditions: [],
        basicRentalCondition: {
          deposit: index === 0 ? 42_000_000 : null,
          monthlyRent: index === 0 ? 185_500 : 0,
          source: 'MYHOME',
          sourceUrl: `https://www.myhome.go.kr/rental/${housingType.housingTypeId}`,
          collectedAt: '2026-10-09T18:30:00Z',
        },
      })),
    }))
    const comparison = within(panel).getByRole('table', { name: '주택형별 임대조건 비교' })
    const firstRow = within(comparison).getByRole('rowheader', { name: '36A' }).closest('tr')!
    expect(within(firstRow).getByText('기본 임대조건')).toBeInTheDocument()
    expect(within(firstRow).getByText('4,200만원')).toBeInTheDocument()
    expect(within(firstRow).getByText('18만 5,500원')).toBeInTheDocument()
    const firstPanel = within(panel).getByRole('tabpanel', { name: '36A' })
    expect(factValue(firstPanel, '기본 보증금').getByText('4,200만원')).toBeInTheDocument()
    expect(factValue(firstPanel, '기본 월 임대료').getByText('18만 5,500원')).toBeInTheDocument()
    expect(within(firstPanel).getByRole('link', { name: '마이홈' })).toHaveAttribute('href', 'https://www.myhome.go.kr/rental/101')
    expect(within(firstPanel).getByText('2026.10.10')).toHaveAttribute('datetime', '2026-10-09T18:30:00Z')

    fireEvent.click(within(panel).getByRole('tab', { name: '44B' }))
    const secondPanel = within(panel).getByRole('tabpanel', { name: '44B' })
    expect(factValue(secondPanel, '기본 보증금').getByText('', { selector: 'dd' })).toBeEmptyDOMElement()
    expect(factValue(secondPanel, '기본 월 임대료').getByText('0원')).toBeInTheDocument()
    expect(within(secondPanel).getByRole('link', { name: '마이홈' })).toHaveAttribute('href', 'https://www.myhome.go.kr/rental/102')
  })

  it('기본 조건과 공고 공급 조건의 금액을 별도 행으로 표시하고 누락 금액을 섞어 채우지 않는다', () => {
    const { panel } = renderPanel(detailWith({ housingTypes: [{
      ...BASE_DETAIL.housingTypes[0],
      basicRentalCondition: { deposit: 42_000_000, monthlyRent: 185_500, source: 'MYHOME', sourceUrl: null, collectedAt: null },
      currentSupplyConditions: [{ target: '청년', deposit: null, monthlyRent: 200_000, convertibleDeposit: null }],
    }] }))
    const table = within(panel).getByRole('table', { name: '주택형별 임대조건 비교' })
    const basicRow = within(table).getByText('기본 임대조건').closest('tr')!
    expect(within(basicRow).getByText('4,200만원')).toBeInTheDocument()
    expect(within(basicRow).getByText('18만 5,500원')).toBeInTheDocument()
    const currentRow = within(table).getByText('청년').closest('tr')!
    expect(within(currentRow).getByText('현재 공급')).toBeInTheDocument()
    expect(within(currentRow).getByRole('cell', { name: '' })).toBeInTheDocument()
    expect(within(currentRow).getByText('20만원')).toBeInTheDocument()
    expect(within(table).getByRole('rowheader', { name: '36A' })).toHaveAttribute('rowspan', '2')
    expect(within(panel).getByRole('table', { name: '36A 청년 현재 공급 조건' })).not.toHaveTextContent('4,200만원')
    expect(within(within(panel).getByRole('table', { name: '36A 청년 현재 공급 조건' }))
      .getAllByRole('cell', { name: '' })).toHaveLength(2)
  })

  it.each([null, 'javascript:alert(1)'])('출처 주소 %s는 링크로 만들지 않고 잘못된 수집일은 표시하지 않는다', (sourceUrl) => {
    const { panel } = renderPanel(detailWith({ housingTypes: [{
      ...BASE_DETAIL.housingTypes[0],
      basicRentalCondition: { deposit: null, monthlyRent: 0, source: 'MYHOME', sourceUrl, collectedAt: 'invalid' },
    }] }))
    const typePanel = within(panel).getByRole('tabpanel', { name: '36A' })
    expect(within(typePanel).getByText('마이홈')).toBeInTheDocument()
    expect(within(typePanel).queryByRole('link', { name: '마이홈' })).not.toBeInTheDocument()
    expect(within(typePanel).queryByText(/invalid|수집일/)).not.toBeInTheDocument()
    expect(factValue(typePanel, '기본 월 임대료').getByText('0원')).toBeInTheDocument()
  })

  it('두 면적 전환 버튼으로 모달 전체를 양방향 전환하고 주택형 변경에도 단위를 유지한다', () => {
    const { panel } = renderPanel()
    expect(within(panel).getAllByRole('button', { name: '평 전환' })).toHaveLength(2)
    fireEvent.click(within(section(panel, '주택형별 임대조건')).getByRole('button', { name: '평 전환' }))
    expect(within(panel).getAllByRole('button', { name: '㎡ 전환' })).toHaveLength(2)
    expect(within(panel).getAllByText('10.93평')).toHaveLength(2)
    expect(factValue(section(panel, '주택형 정보'), '공급 면적').getByText('', { selector: 'dd' })).toBeEmptyDOMElement()
    fireEvent.click(within(panel).getByRole('tab', { name: '44B' }))
    expect(within(panel).getAllByText('13.57평')).toHaveLength(2)
    expect(within(panel).getByText('18.19평')).toBeVisible()
    fireEvent.click(within(section(panel, '주택형 정보')).getByRole('button', { name: '㎡ 전환' }))
    expect(within(panel).getAllByRole('button', { name: '평 전환' })).toHaveLength(2)
    expect(within(panel).getAllByText('44.87㎡')).toHaveLength(2)
    expect(within(panel).getByText('60.12㎡')).toBeVisible()
    expect(within(panel).getByText('5,000만원')).toBeVisible()
  })

  it('회원 단지 알림 클릭은 단지 ID를 저장하고 신청 완료를 안내한다', async () => {
    localStorage.clear()
    const record = vi.fn<NotificationInterestRepository['record']>().mockImplementation(async event => ({ ...event, outcome: 'ACTIVATED', occurredAt: '2026-10-09T00:00:00Z', settingsRevision: 1, currentTarget: { active: true, expiresAt: '2027-04-07T00:00:00Z', noticeVersion: 'notification-2026-10-09-v1', requestedAt: '2026-10-09T00:00:00Z' } }))
    render(
      <MemoryRouter><NotificationInterestProvider repository={{ record, loadStatus: async () => ({ userId: '1', settingsRevision: 0, targets: [] }) }}>
        <HousingComplexDetailPanel detail={BASE_DETAIL} onClose={vi.fn()} />
      </NotificationInterestProvider></MemoryRouter>,
    )
    const identity = screen.getByRole('region', { name: BASE_DETAIL.name })
    await waitFor(() => expect(within(identity).getByRole('button', { name: '서울가람 행복주택 알림 받기' })).toBeEnabled())
    expect(screen.queryByText('단지 알림')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '서울가람 행복주택 알림 받기' }))
    expect(record).not.toHaveBeenCalled()
    fireEvent.click(await screen.findByRole('button', { name: '신청하기' }))
    expect(await screen.findByRole('dialog', { name: '알림 신청을 완료했어요' })).toBeVisible()
    expect(record).toHaveBeenCalledWith(expect.objectContaining({
      eventType: 'CONFIRMED', source: 'COMPLEX_DETAIL', targetType: 'COMPLEX', targetId: '17',
    }))
  })

  it('상단에 선택한 단지명과 아이콘 닫기를 표시한다', () => {
    const { panel } = renderPanel()
    const header = panel.querySelector('header')
    if (!header) throw new Error('상세 헤더 없음')
    expect(within(header).getByText(BASE_DETAIL.name)).toBeVisible()
    expect(within(header).queryByText('단지 상세')).not.toBeInTheDocument()
    const close = within(header).getByRole('button', { name: '단지 상세 닫기' })
    expect(close).not.toHaveTextContent('닫기')
    expect(close.querySelector('svg')).toBeInTheDocument()
  })

  it.each([
    ['LH', '한국토지주택공사'],
    ['SH', '서울주택도시공사'],
    ['GH', '경기주택도시공사'],
  ])('주소 섹션에 %s 약어를 표시하고 기본 정보에는 기관명을 유지한다', (agencyCode, agencyName) => {
    render(<HousingComplexDetailPanel detail={{ ...BASE_DETAIL, agencyCode, agencyName }} onClose={vi.fn()} />)
    const summary = screen.getByRole('region', { name: BASE_DETAIL.name })
    expect(within(summary).getByText(agencyCode)).toBeVisible()
    expect(within(summary).queryByText(agencyName)).not.toBeInTheDocument()
    expect(screen.getByText(agencyName)).toBeVisible()
  })

  it('단지 상세 A의 계층에 API가 제공하는 정보만 표시한다', () => {
    const { panel } = renderPanel()

    expect(within(panel).getByRole('heading', { name: BASE_DETAIL.name })).toBeInTheDocument()
    expect(within(panel).getAllByText(BASE_DETAIL.agencyName).length).toBeGreaterThan(0)
    expect(within(panel).getAllByText(BASE_DETAIL.rentalTypeLabel).length).toBeGreaterThan(0)
    expect(within(panel).getAllByText('서울특별시 중구 세종대로 110').length)
      .toBeGreaterThan(0)

    expect(within(panel).getByRole('heading', { name: '현재 모집 공고' })).toBeInTheDocument()
    expect(within(panel).getByRole('heading', { name: '단지 기본 정보' })).toBeInTheDocument()
    expect(within(panel).getByRole('heading', { name: '주택형 정보' })).toBeInTheDocument()
    expect(within(panel).getByRole('heading', { name: '단지 조감도' })).toBeInTheDocument()

    expect(within(panel).queryByText('주변 생활 시설')).not.toBeInTheDocument()
    expect(within(panel).queryByText('교통 정보')).not.toBeInTheDocument()
    expect(within(panel).queryByText('배정 학교 정보')).not.toBeInTheDocument()
    expect(within(panel).queryByText('과거 모집 공고')).not.toBeInTheDocument()
    expect(within(panel).queryByText(/예측|자격/)).not.toBeInTheDocument()
  })

  it('유효한 http 이미지 URL만 사진과 조감도로 표시한다', () => {
    const { panel } = renderPanel()

    expect(within(panel).getByRole('img', { name: `${BASE_DETAIL.name} 단지 사진` }))
      .toHaveAttribute('src', 'https://example.com/complex.png')
    expect(within(panel).getByRole('img', { name: `${BASE_DETAIL.name} 단지 조감도` }))
      .toHaveAttribute('src', 'https://example.com/overview.png')
    expect(within(panel).getAllByRole('img')).toHaveLength(3)
    expect(panel.innerHTML).not.toContain('javascript:')
  })

  it('식별 블록 맨 위에 대표 사진을 두고 모집 요약·기본 정보 블록을 차례로 표시한다', () => {
    const { panel } = renderPanel()
    const content = within(panel).getByRole('region', {
      name: `${BASE_DETAIL.name} 단지 상세 내용`,
    })
    const contentSections = Array.from(content.children)

    expect(contentSections[0]).toHaveAccessibleName(BASE_DETAIL.name)
    expect(contentSections[0]?.firstElementChild).toHaveAccessibleName('단지 사진')
    expect(within(contentSections[1] as HTMLElement).getByRole('heading', { name: '현재 모집 공고' }))
      .toBeInTheDocument()
    expect(within(contentSections[2] as HTMLElement).getByRole('heading', { name: '단지 기본 정보' }))
      .toBeInTheDocument()
    for (const section of contentSections.slice(1)) {
      expect(section).toHaveAttribute('data-appearance', 'block')
    }
    expect(within(content).queryByText(BASE_DETAIL.name)).not.toBeInTheDocument()
    expect(within(panel).queryByText('단지 상세')).not.toBeInTheDocument()
    expect(within(panel).getAllByText('서울특별시 중구 세종대로 110')).toHaveLength(1)
  })

  it('사진 없이 조감도만 있으면 상단 대표 이미지로 한 번 표시한다', () => {
    const { panel } = renderPanel(detailWith({ images: ['javascript:alert(1)'] }))
    const overview = within(panel).getByRole('img', { name: `${BASE_DETAIL.name} 단지 조감도` })
    const content = within(panel).getByRole('region', {
      name: `${BASE_DETAIL.name} 단지 상세 내용`,
    })

    expect(content.firstElementChild).toContainElement(overview)
    expect(overview).toHaveAttribute('src', 'https://example.com/overview.png')
    expect(overview).toHaveAttribute('loading', 'eager')
    expect(within(panel).queryByRole('heading', { name: '단지 조감도' })).not.toBeInTheDocument()
    expect(within(panel).getAllByRole('img', { name: `${BASE_DETAIL.name} 단지 조감도` }))
      .toHaveLength(1)
  })

  it('사진 URL이 없거나 안전하지 않으면 이미지 영역을 만들지 않는다', () => {
    const { panel } = renderPanel(
      detailWith({
        images: ['data:image/png;base64,unsafe'],
        overviewImageUrl: null,
        housingTypes: BASE_DETAIL.housingTypes.map((housingType) => ({
          ...housingType,
          floorPlanImageUrl: null,
          floorPlan3dImageUrl: null,
        })),
      }),
    )

    expect(within(panel).queryByRole('img')).not.toBeInTheDocument()
    expect(within(panel).queryByRole('heading', { name: '단지 조감도' }))
      .not.toBeInTheDocument()
    expect(within(panel).queryByText('평면도 준비 중')).not.toBeInTheDocument()
    expect(within(panel).getByRole('region', {
      name: `${BASE_DETAIL.name} 단지 상세 내용`,
    }).firstElementChild).toHaveAccessibleName(BASE_DETAIL.name)
  })

  it('닫기 버튼을 실제 버튼으로 제공한다', () => {
    const { onClose, panel } = renderPanel()
    const closeButton = within(panel).getByRole('button', { name: '단지 상세 닫기' })

    closeButton.focus()
    expect(closeButton).toHaveFocus()
    fireEvent.click(closeButton)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('상세 내부에서 Escape를 누르면 닫는다', () => {
    const { onClose, panel } = renderPanel()

    fireEvent.keyDown(panel, { key: 'Escape' })

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('마운트와 단지 ID 교체 때 스크롤 없이 상세 제목으로 focus를 이동한다', () => {
    const focus = vi.spyOn(HTMLHeadingElement.prototype, 'focus')
    const { rerender } = renderPanel()
    const firstHeading = screen.getByRole('heading', { name: BASE_DETAIL.name })

    expect(firstHeading).toHaveFocus()

    const nextDetail = detailWith({
      complexId: '18',
      name: '새로 선택한 국민임대',
    })
    rerender(
      <HousingComplexDetailPanel
        detail={nextDetail}
        onClose={vi.fn()}
      />,
    )

    expect(screen.getByRole('heading', { name: nextDetail.name })).toHaveFocus()
    expect(focus).toHaveBeenCalledTimes(2)
    expect(focus.mock.calls).toEqual([[{ preventScroll: true }], [{ preventScroll: true }]])
    focus.mockRestore()
  })

  it('값이 없으면 빈칸으로 표시하고 0과 false는 실제 값으로 표시한다', () => {
    const { panel } = renderPanel(
      detailWith({
        completionDate: null,
        hasElevator: false,
        moveOutCountLastYear: 0,
        totalHouseholdCount: 0,
        totalParkingCount: 0,
        housingTypes: [
          {
            ...BASE_DETAIL.housingTypes[0],
            exclusiveArea: null,
            supplyArea: null,
            isDuplex: false,
            maintenanceFee: 0,
            currentSupplyConditions: [
              {
                target: null,
                deposit: 0,
                monthlyRent: 0,
                convertibleDeposit: null,
              },
            ],
          },
        ],
      }),
    )
    const facts = section(panel, '단지 기본 정보')

    expect(factValue(facts, '준공일자').getByText('', { selector: 'dd' })).toBeEmptyDOMElement()
    expect(factValue(facts, '엘리베이터').getByText('없음')).toBeInTheDocument()
    expect(factValue(facts, '1년 퇴거 세대수').getByText('0세대')).toBeInTheDocument()
    expect(factValue(facts, '총세대수').getByText('0세대')).toBeInTheDocument()
    expect(factValue(facts, '총주차대수(세대당)').getByText('0대')).toBeInTheDocument()

    const housingType = section(panel, '주택형 정보')
    expect(factValue(housingType, '전용 면적').getByText('', { selector: 'dd' })).toBeEmptyDOMElement()
    expect(factValue(housingType, '공급 면적').getByText('', { selector: 'dd' })).toBeEmptyDOMElement()
    expect(factValue(housingType, '복층여부').getByText('해당 없음')).toBeInTheDocument()
    expect(factValue(housingType, '관리비').getByText('0원')).toBeInTheDocument()
    expect(within(housingType).getAllByText('0원')).toHaveLength(3)
  })

  it('대상별 공급 조건을 항목·금액 두 쌍의 4열 표로 표시한다', () => {
    const { panel } = renderPanel()
    const table = within(panel).getByRole('table', { name: '36A 청년 현재 공급 조건' })

    expect(within(table).queryByRole('columnheader')).not.toBeInTheDocument()
    expect(within(table).getAllByRole('rowheader')).toHaveLength(3)
    expect(within(table).getByRole('rowheader', { name: '임대보증금' }))
      .toHaveAttribute('scope', 'row')
    expect(within(table).getByRole('row', { name: '임대보증금 5,000만원 월 임대료 20만원' }))
      .toBeInTheDocument()
    expect(within(table).getByRole('cell', { name: '5,000만원' }))
      .toHaveAttribute('headers', within(table).getByRole('rowheader', { name: '임대보증금' }).id)
    expect(within(table).getByRole('cell', { name: '20만원' }))
      .toHaveAttribute('headers', within(table).getByRole('rowheader', { name: '월 임대료' }).id)
    expect(within(table).getByRole('row', { name: '전환 가능 보증금' }))
      .toBeInTheDocument()
    expect(within(table).getByRole('cell', { name: '5,000만원' }))
      .toHaveAttribute('data-emphasis', 'true')
    expect(within(table).getByRole('cell', { name: '' }))
      .not.toHaveAttribute('data-emphasis')
  })

  it('금액을 원 단위 전체 금액으로 표시한다', () => {
    const { panel } = renderPanel(detailWith({
      housingTypes: [{
        ...BASE_DETAIL.housingTypes[0],
        maintenanceFee: 123_456,
        currentSupplyConditions: [{
          target: '청년',
          deposit: 180_000_000,
          monthlyRent: 205_500,
          convertibleDeposit: 18_000_000,
        }],
      }],
    }))
    const table = within(panel).getByRole('table', { name: '36A 청년 현재 공급 조건' })

    expect(within(table).getByRole('cell', { name: '1억 8,000만원' })).toBeInTheDocument()
    expect(within(table).getByRole('cell', { name: '20만 5,500원' })).toBeInTheDocument()
    expect(within(table).getByRole('cell', { name: '1,800만원' })).toBeInTheDocument()
    expect(factValue(panel, '관리비').getByText('12만 3,456원')).toBeInTheDocument()
  })

  it('비교표는 읽기 전용으로 두고 주택형 정보의 독립 탭으로 상세를 바꾼다', () => {
    const { panel } = renderPanel()
    const table = within(panel).getByRole('table', { name: '주택형별 임대조건 비교' })
    const information = section(panel, '주택형 정보')
    const tabs = within(information).getByRole('tablist', { name: '주택형 선택' })
    const firstTab = within(tabs).getByRole('tab', { name: '36A' })
    const secondTab = within(tabs).getByRole('tab', { name: '44B' })
    const firstPanel = within(information).getByRole('tabpanel', { name: '36A' })

    expect(within(table).queryByRole('button')).not.toBeInTheDocument()
    expect(within(table).getByRole('rowheader', { name: '36A' })).toBeInTheDocument()
    expect(firstTab).toHaveAttribute('aria-selected', 'true')
    expect(firstTab).toHaveAttribute('tabindex', '0')
    expect(secondTab).toHaveAttribute('aria-selected', 'false')
    expect(secondTab).toHaveAttribute('tabindex', '-1')
    expect(firstTab).toHaveAttribute('aria-controls', firstPanel.id)
    expect(firstPanel).toHaveAttribute('aria-labelledby', firstTab.id)
    expect(firstPanel).toHaveAttribute('tabindex', '0')
    expect(document.getElementById(secondTab.getAttribute('aria-controls')!)).not.toBeVisible()

    fireEvent.click(secondTab)
    const secondPanel = within(information).getByRole('tabpanel', { name: '44B' })
    expect(secondTab).toHaveAttribute('aria-selected', 'true')
    expect(secondTab).toHaveAttribute('tabindex', '0')
    expect(firstTab).toHaveAttribute('aria-selected', 'false')
    expect(firstTab).toHaveAttribute('tabindex', '-1')
    expect(secondTab).toHaveAttribute('aria-controls', secondPanel.id)
    expect(secondPanel).toHaveAttribute('aria-labelledby', secondTab.id)
    expect(factValue(secondPanel, '기본 보증금').getByText('', { selector: 'dd' })).toBeEmptyDOMElement()
    expect(factValue(secondPanel, '기본 월 임대료').getByText('', { selector: 'dd' })).toBeEmptyDOMElement()
    expect(within(information).queryByRole('tabpanel', { name: '36A' })).not.toBeInTheDocument()
    expect(within(table).queryByRole('button')).not.toBeInTheDocument()
    expect(table.querySelector('[data-selected]')).toBeNull()
    expect(within(panel).queryByText('선택 주택형')).not.toBeInTheDocument()
  })

  it('주택형 탭에서 방향키를 순환하고 Home·End로 처음과 끝을 선택한다', () => {
    const { panel } = renderPanel()
    const tabs = within(panel).getByRole('tablist', { name: '주택형 선택' })
    const firstTab = within(tabs).getByRole('tab', { name: '36A' })
    const secondTab = within(tabs).getByRole('tab', { name: '44B' })
    firstTab.focus()

    for (const [key, source, target] of [
      ['ArrowLeft', firstTab, secondTab],
      ['ArrowRight', secondTab, firstTab],
      ['End', firstTab, secondTab],
      ['Home', secondTab, firstTab],
      ['ArrowDown', firstTab, secondTab],
      ['ArrowUp', secondTab, firstTab],
    ] as const) {
      fireEvent.keyDown(source, { key })
      expect(target).toHaveFocus()
      expect(target).toHaveAttribute('aria-selected', 'true')
      expect(source).toHaveAttribute('aria-selected', 'false')
      expect(within(panel).getByRole('tabpanel', { name: target.textContent! })).toBeVisible()
    }
  })

  it('화면 밖의 주택형 탭으로 Home·End 이동하면 탭 줄과 상세 스크롤 안에 드러낸다', () => {
    const { panel } = renderPanel(detailWith({
      housingTypes: [...BASE_DETAIL.housingTypes, { ...BASE_DETAIL.housingTypes[1], housingTypeId: '103', name: '59C' }],
    }))
    const content = within(panel).getByRole('region', { name: `${BASE_DETAIL.name} 단지 상세 내용` })
    const tabs = within(panel).getByRole('tablist', { name: '주택형 선택' })
    const firstTab = within(tabs).getByRole('tab', { name: '36A' })
    const lastTab = within(tabs).getByRole('tab', { name: '59C' })
    content.scrollTop = 320
    vi.spyOn(content, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 100, 300, 400))
    vi.spyOn(tabs, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 100, 300, 44))
    vi.spyOn(firstTab, 'getBoundingClientRect').mockImplementation(() => new DOMRect(100 - tabs.scrollLeft, 420 - content.scrollTop, 80, 44))
    vi.spyOn(lastTab, 'getBoundingClientRect').mockImplementation(() => new DOMRect(
      500 - tabs.scrollLeft, 900 - content.scrollTop, 80, 44,
    ))
    const focus = vi.spyOn(lastTab, 'focus')

    firstTab.focus()
    fireEvent.keyDown(firstTab, { key: 'End' })
    expect(lastTab).toHaveFocus()
    expect(focus).toHaveBeenCalledWith({ preventScroll: true })
    expect(lastTab.getBoundingClientRect().bottom).toBe(content.getBoundingClientRect().bottom)
    expect(content.scrollTop).toBe(444)
    expect(tabs.scrollLeft).toBe(180)

    fireEvent.keyDown(lastTab, { key: 'Home' })
    expect(firstTab).toHaveFocus()
    expect(firstTab.getBoundingClientRect().top).toBe(content.getBoundingClientRect().top)
    expect(content.scrollTop).toBe(320)
    expect(tabs.scrollLeft).toBe(0)
  })

  it('상세를 처음 열면 화면 아래의 선택 주택형으로 자동 스크롤하지 않는다', () => {
    const geometry = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      return this.getAttribute('role') === 'tab'
        ? new DOMRect(100, 900, 80, 44)
        : new DOMRect(100, 100, 300, 400)
    })
    try {
      const { panel } = renderPanel()
      const content = within(panel).getByRole('region', { name: `${BASE_DETAIL.name} 단지 상세 내용` })
      expect(within(panel).getByRole('heading', { level: 2, name: BASE_DETAIL.name })).toHaveFocus()
      expect(content.scrollTop).toBe(0)
    } finally {
      geometry.mockRestore()
    }
  })

  it('선택한 주택형이 사라지거나 단지가 바뀌면 첫 탭과 상세를 함께 선택한다', () => {
    const { rerender } = renderPanel()
    fireEvent.click(screen.getByRole('tab', { name: '44B' }))
    rerender(<HousingComplexDetailPanel detail={detailWith({ housingTypes: [BASE_DETAIL.housingTypes[0]] })} onClose={vi.fn()} />)
    expect(screen.getByRole('tab', { name: '36A' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tabpanel', { name: '36A' })).toBeVisible()

    rerender(<HousingComplexDetailPanel detail={BASE_DETAIL} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('tab', { name: '44B' }))
    rerender(<HousingComplexDetailPanel detail={detailWith({ complexId: '18' })} onClose={vi.fn()} />)
    expect(screen.getByRole('tab', { name: '36A' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tabpanel', { name: '36A' })).toBeVisible()
  })

  it('4열 비교표에 대상별 보증금·월세 쌍을 섞지 않고 표시하며 조건 없는 주택형도 선택할 수 있다', () => {
    const { panel } = renderPanel(detailWith({
      housingTypes: [{
        ...BASE_DETAIL.housingTypes[0],
        currentSupplyConditions: [
          { target: '청년', deposit: 50_000_000, monthlyRent: 200_000, convertibleDeposit: null },
          { target: '대학생', deposit: 40_000_000, monthlyRent: 220_000, convertibleDeposit: null },
        ],
      }, BASE_DETAIL.housingTypes[1]],
    }))
    const table = within(panel).getByRole('table', { name: '주택형별 임대조건 비교' })
    expect(within(table).getAllByRole('columnheader')).toHaveLength(4)
    const youngRow = within(table).getByText('청년').closest('tr')!
    expect(within(youngRow).getByText('5,000만원')).toBeInTheDocument()
    expect(within(youngRow).getByText('20만원')).toBeInTheDocument()
    const studentRow = within(table).getByText('대학생').closest('tr')!
    expect(within(studentRow).getByText('4,000만원')).toBeInTheDocument()
    expect(within(studentRow).getByText('22만원')).toBeInTheDocument()
    expect(within(table).getByRole('rowheader', { name: '36A' })).toHaveAttribute('rowspan', '2')
    expect(within(table).queryByRole('button')).not.toBeInTheDocument()
    const missingRow = within(table).getByRole('rowheader', { name: '44B' }).closest('tr')!
    expect(within(missingRow).getAllByRole('cell', { name: '' })).toHaveLength(2)
    fireEvent.click(within(panel).getByRole('tab', { name: '44B' }))
    expect(within(panel).getByRole('tabpanel', { name: '44B' })).toBeInTheDocument()
  })

  it('원공고 구분은 숨기고 정정공고에만 정정 딱지를 단다', () => {
    const { panel, unmount } = renderPanel()
    expect(within(panel).queryByText('원공고')).not.toBeInTheDocument()
    expect(within(panel).queryByText('정정')).not.toBeInTheDocument()
    unmount()

    const corrected = renderPanel(detailWith({
      currentAnnouncements: [{ ...BASE_DETAIL.currentAnnouncements[0], publicationTypeLabel: '정정공고' }],
    }))
    expect(within(corrected.panel).getByText('정정')).toBeVisible()
    expect(within(corrected.panel).queryByText('정정공고')).not.toBeInTheDocument()
  })

  it('평면도 URL이 있는 주택형만 실제 이미지를 표시한다', () => {
    const { panel } = renderPanel()

    expect(within(panel).getByRole('img', { name: '36A 평면도' }))
      .toHaveAttribute('src', 'https://example.com/floor.png')

    fireEvent.click(within(panel).getByRole('tab', { name: '44B' }))
    expect(within(panel).queryByRole('img', { name: '44B 평면도' }))
      .not.toBeInTheDocument()
    expect(within(panel).queryByText(/침실|거실|주방|욕실/)).not.toBeInTheDocument()
  })

  it('현재 공고 이동을 독립적인 버튼 callback으로 제공한다', () => {
    const { onOpenAnnouncement, panel } = renderPanel()
    const button = within(panel).getByRole('button', {
      name: `${BASE_DETAIL.currentAnnouncements[0].title} 상세 보기`,
    })

    fireEvent.click(button)
    expect(onOpenAnnouncement).toHaveBeenCalledWith('201')
    expect(within(panel).getByText('0 : 1')).toBeInTheDocument()
    expect(within(panel).getByText('D-0')).toBeInTheDocument()
    expect(factValue(panel, '접수 시작').getByText('2026.08.20')).toBeInTheDocument()
    expect(factValue(panel, '접수 마감').getByText('2026.08.27')).toBeInTheDocument()
  })

  it.each([
    { dDay: 0, label: 'D-0', tone: 'urgent' },
    { dDay: 14, label: 'D-14', tone: 'countdown' },
  ])('마감 $label을 공고 제목 위 상태 행에 한 번 표시하고 설명을 유지한다', ({ dDay, label, tone }) => {
    const { panel, onOpenAnnouncement } = renderPanel(detailWith({
      currentAnnouncements: [{ ...BASE_DETAIL.currentAnnouncements[0], dDay }],
    }))
    const card = within(section(panel, '현재 모집 공고')).getByRole('article')
    const title = within(card).getByRole('heading', { name: '행복주택 입주자 모집 공고' })
    const header = title.closest('header')
    const deadline = within(card).getByText(label)

    expect(header?.firstElementChild).toContainElement(deadline)
    expect(header?.firstElementChild).toContainElement(within(card).getByText('접수중'))
    expect(deadline.closest('p')).toHaveTextContent(`마감까지 ${label}`)
    expect(deadline.closest('p')).toHaveAttribute('data-tone', tone)
    expect(deadline.closest('dl')).toBeNull()
    expect(within(card).getAllByText('마감까지')).toHaveLength(1)
    fireEvent.click(within(card).getByRole('button', { name: '행복주택 입주자 모집 공고 상세 보기' }))
    expect(onOpenAnnouncement).toHaveBeenCalledWith('201')
  })

  it.each([null, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY])(
    '누락되거나 유효하지 않은 마감일 %s는 마감 안내를 표시하지 않는다',
    (dDay) => {
      const { panel } = renderPanel(detailWith({
        currentAnnouncements: [{ ...BASE_DETAIL.currentAnnouncements[0], dDay }],
      }))
      const card = within(section(panel, '현재 모집 공고')).getByRole('article')
      expect(within(card).queryByText('공고문 확인')).not.toBeInTheDocument()
      expect(within(card).queryByText('마감까지')).not.toBeInTheDocument()
      expect(within(card).queryByText(/^D-/)).not.toBeInTheDocument()
    },
  )

  it('접수마감 공고는 남은 숫자와 무관하게 종료를 중립적으로 표시한다', () => {
    const { panel } = renderPanel(detailWith({
      currentAnnouncements: [{
        ...BASE_DETAIL.currentAnnouncements[0],
        applicationStatus: 'CLOSED',
        dDay: 2,
      }],
    }))
    const card = within(section(panel, '현재 모집 공고')).getByRole('article')
    const deadline = within(card).getByText('종료')

    expect(deadline.closest('p')).toHaveTextContent('마감까지 종료')
    expect(deadline.closest('p')).toHaveAttribute('data-tone', 'neutral')
    expect(within(card).getByText('접수마감')).toBeInTheDocument()
    expect(within(card).queryByText('D-2')).not.toBeInTheDocument()
  })

  it('공고 callback이 없으면 공고 정보는 유지하고 이동 버튼만 숨긴다', () => {
    render(<HousingComplexDetailPanel detail={BASE_DETAIL} onClose={vi.fn()} />)

    expect(screen.getByText('행복주택 입주자 모집 공고')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /공고 상세 보기/ })).not.toBeInTheDocument()
  })

  it('여러 모집 공고의 요약과 각 공고 상세 이동을 모두 유지한다', () => {
    const { panel, onOpenAnnouncement } = renderPanel(detailWith({
      currentAnnouncements: [
        BASE_DETAIL.currentAnnouncements[0],
        {
          ...BASE_DETAIL.currentAnnouncements[0],
          announcementId: '202',
          title: '신혼부부 예비입주자 모집 공고',
          applicationStatus: 'BEFORE_APPLICATION',
          targets: ['신혼부부'],
          applicationStartAt: '2026-09-01',
          applicationEndAt: '2026-09-15',
          actualCompetitionRate: null,
          dDay: null,
        },
      ],
    }))
    const announcements = section(panel, '현재 모집 공고')
    const cards = within(announcements).getAllByRole('article')

    expect(cards).toHaveLength(2)
    expect(within(cards[0]).getByText('2026.08.20')).toBeInTheDocument()
    expect(within(cards[1]).getByText('2026.09.01')).toBeInTheDocument()
    expect(within(cards[1]).getByText('신혼부부')).toBeInTheDocument()
    fireEvent.click(within(cards[0]).getByRole('button', { name: '행복주택 입주자 모집 공고 상세 보기' }))
    fireEvent.click(within(cards[1]).getByRole('button', { name: '신혼부부 예비입주자 모집 공고 상세 보기' }))
    expect(onOpenAnnouncement.mock.calls).toEqual([['201'], ['202']])
  })

  it('빈 주택형과 현재 공고 배열을 실제 빈 상태로 표시한다', () => {
    const { panel } = renderPanel(
      detailWith({ housingTypes: [], currentAnnouncements: [] }),
    )

    expect(within(panel).queryByRole('heading', { name: '주택형 정보' }))
      .not.toBeInTheDocument()
    expect(within(panel).queryByRole('tablist')).not.toBeInTheDocument()
    expect(within(panel).queryByRole('tabpanel')).not.toBeInTheDocument()
    expect(within(panel).getByText('지금 모집 중인 공고가 없어요.'))
      .toBeInTheDocument()
    expect(within(panel).getByText('알림을 켜 두면 새 공고가 올라올 때 알려드려요.'))
      .toBeInTheDocument()
  })

  it('단지 기본 정보와 현재 공고의 접수 기간은 표가 아닌 이름/값 목록으로 표시한다', () => {
    const { panel } = renderPanel()
    const basic = within(panel).getByRole('heading', { name: '단지 기본 정보' }).closest('section')
    const basicFacts = basic?.querySelector('dl')

    expect(basicFacts).toHaveAttribute('data-appearance', 'list')
    expect(within(basicFacts as HTMLElement).getByText('공급기관').tagName).toBe('DT')
    const announcementFacts = within(panel).getByText('접수 시작').closest('dl')
    expect(announcementFacts).toHaveAttribute('data-appearance', 'list')
    expect(within(panel).getByRole('table', { name: '주택형별 임대조건 비교' })).toBeInTheDocument()
  })

  it('좁은 화면의 항목표·공급표와 키보드 focus-visible 규칙을 스타일에 고정한다', () => {
    const css = readFileSync(
      resolve(
        process.cwd(),
        'src/public-housing/components/HousingComplexDetailPanel.module.css',
      ),
      'utf8',
    )
    const sharedCss = readFileSync(
      resolve(process.cwd(), 'src/public-housing/components/DetailPrimitives.module.css'),
      'utf8',
    )
    const narrowRule = css.slice(
      css.indexOf('@container housing-complex-detail (max-width: 270px)'),
    )

    expect(css).toContain('container: housing-complex-detail / inline-size;')
    expect(sharedCss).toMatch(/\.closeButton:focus-visible[\s\S]*?outline:/)
    expect(css).toMatch(/\.housingTypeTab:focus-visible[\s\S]*?outline:/)
    expect(css).toMatch(/\.announcementAction:focus-visible[\s\S]*?outline:/)
    expect(sharedCss).toMatch(/\.facts\[data-columns='2'\][\s\S]*?grid-template-columns:\s*repeat\(2, minmax\(0, 1fr\)\);/)
    expect(sharedCss).not.toContain('@container (max-width: 480px)')
    expect(narrowRule).toMatch(/\.announcementCard[\s\S]*?grid-template-columns:\s*minmax\(0, 1fr\);/)
    expect(sharedCss).toMatch(/\.tableViewport[\s\S]*?overflow-x:\s*auto;/)
    expect(sharedCss).toMatch(/\.table \{[\s\S]*?width:\s*100%;[\s\S]*?table-layout:\s*fixed;/)
  })
})

vi.mock('../../privacy/usePrivacy', () => {
  const notices = [{ key: 'NOTIFICATION_NOTICE', version: 'notification-2026-10-09-v1' }]
  return { usePrivacyNotices: () => ({ notices, error: false, retry: vi.fn() }) }
})
