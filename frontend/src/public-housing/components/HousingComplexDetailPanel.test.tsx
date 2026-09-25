/// <reference types="node" />

import { fireEvent, render, screen, within } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import {
  HousingComplexDetailPanel,
  type HousingComplexDetailData,
} from './HousingComplexDetailPanel'

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
  ])('단지 요약에 %s 약어를 표시하고 기본 정보에는 기관명을 유지한다', (agencyCode, agencyName) => {
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

  it('대표 사진 뒤에 단지 식별 정보·모집 요약·기본 정보를 차례로 표시한다', () => {
    const { panel } = renderPanel()
    const content = within(panel).getByRole('region', {
      name: `${BASE_DETAIL.name} 단지 상세 내용`,
    })
    const contentSections = Array.from(content.children)

    expect(contentSections[0]).toHaveAccessibleName('단지 사진')
    expect(contentSections[1]).toHaveAccessibleName(BASE_DETAIL.name)
    expect(within(contentSections[2] as HTMLElement).getByRole('heading', { name: '현재 모집 공고' }))
      .toBeInTheDocument()
    expect(within(contentSections[3] as HTMLElement).getByRole('heading', { name: '단지 기본 정보' }))
      .toBeInTheDocument()
    expect(within(content).getAllByText(BASE_DETAIL.name)).toHaveLength(1)
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

  it('null은 공고문 확인 또는 미표시하고 0과 false는 실제 값으로 표시한다', () => {
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

    expect(factValue(facts, '준공일자').getByText('공고문 확인')).toBeInTheDocument()
    expect(factValue(facts, '엘리베이터').getByText('없음')).toBeInTheDocument()
    expect(factValue(facts, '1년 퇴거 세대수').getByText('0세대')).toBeInTheDocument()
    expect(factValue(facts, '총세대수').getByText('0세대')).toBeInTheDocument()
    expect(factValue(facts, '총주차대수(세대당)').getByText('0대')).toBeInTheDocument()

    const housingType = section(panel, '주택형 정보')
    expect(factValue(housingType, '전용 면적').getByText('공고문 확인')).toBeInTheDocument()
    expect(factValue(housingType, '공급 면적').getByText('공고문 확인')).toBeInTheDocument()
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
    expect(within(table).getByRole('row', { name: '전환 가능 보증금 공고문 확인' }))
      .toBeInTheDocument()
    expect(within(table).getByRole('cell', { name: '5,000만원' }))
      .toHaveAttribute('data-emphasis', 'true')
    expect(within(table).getByRole('cell', { name: '공고문 확인' }))
      .not.toHaveAttribute('data-emphasis')
  })

  it('원 단위 금액을 공통 만원·억 표기로 표시하고 소수 금액을 보존한다', () => {
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

    expect(within(table).getByRole('cell', { name: '1.8억' })).toBeInTheDocument()
    expect(within(table).getByRole('cell', { name: '20.55만원' })).toBeInTheDocument()
    expect(within(table).getByRole('cell', { name: '1,800만원' })).toBeInTheDocument()
    expect(factValue(panel, '관리비').getByText('12.3456만원')).toBeInTheDocument()
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
    expect(within(secondPanel).getByText('공고문 확인')).toBeInTheDocument()
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

  it('화면 밖 탭에 키보드로 이동하면 탭 띠만 가로로 이동하고 상세 스크롤은 유지한다', () => {
    const { panel } = renderPanel(detailWith({
      housingTypes: [...BASE_DETAIL.housingTypes, { ...BASE_DETAIL.housingTypes[1], housingTypeId: '103', name: '59C' }],
    }))
    const content = within(panel).getByRole('region', { name: `${BASE_DETAIL.name} 단지 상세 내용` })
    const tabs = within(panel).getByRole('tablist', { name: '주택형 선택' })
    const firstTab = within(tabs).getByRole('tab', { name: '36A' })
    const lastTab = within(tabs).getByRole('tab', { name: '59C' })
    content.scrollTop = 320
    vi.spyOn(tabs, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 100, 160, 44))
    vi.spyOn(firstTab, 'getBoundingClientRect').mockImplementation(() => new DOMRect(100 - tabs.scrollLeft, 100, 80, 44))
    vi.spyOn(lastTab, 'getBoundingClientRect').mockImplementation(() => new DOMRect(
      260 - tabs.scrollLeft, 100, lastTab.getAttribute('aria-selected') === 'true' ? 90 : 80, 44,
    ))
    const focus = vi.spyOn(lastTab, 'focus')

    firstTab.focus()
    fireEvent.keyDown(firstTab, { key: 'End' })
    expect(lastTab).toHaveFocus()
    expect(focus).toHaveBeenCalledWith({ preventScroll: true })
    expect(tabs.scrollLeft).toBe(90)
    expect(content.scrollTop).toBe(320)

    fireEvent.keyDown(lastTab, { key: 'Home' })
    expect(firstTab).toHaveFocus()
    expect(tabs.scrollLeft).toBe(0)
    expect(content.scrollTop).toBe(320)
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
    expect(within(missingRow).getAllByText('공고문 확인')).toHaveLength(3)
    fireEvent.click(within(panel).getByRole('tab', { name: '44B' }))
    expect(within(panel).getByRole('tabpanel', { name: '44B' })).toBeInTheDocument()
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
    expect(factValue(panel, '접수 종료').getByText('2026.08.27')).toBeInTheDocument()
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
    '누락되거나 유효하지 않은 마감일 %s는 상단의 공고문 확인으로 표시한다',
    (dDay) => {
      const { panel } = renderPanel(detailWith({
        currentAnnouncements: [{ ...BASE_DETAIL.currentAnnouncements[0], dDay }],
      }))
      const card = within(section(panel, '현재 모집 공고')).getByRole('article')
      const title = within(card).getByRole('heading', { name: '행복주택 입주자 모집 공고' })
      const deadline = within(card).getByText('공고문 확인')

      expect(title.closest('header')?.firstElementChild).toContainElement(deadline)
      expect(deadline.closest('p')).toHaveTextContent('마감까지 공고문 확인')
      expect(deadline.closest('p')).toHaveAttribute('data-tone', 'neutral')
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
    expect(within(panel).getByText('현재 연결된 모집 공고가 없습니다.'))
      .toBeInTheDocument()
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
