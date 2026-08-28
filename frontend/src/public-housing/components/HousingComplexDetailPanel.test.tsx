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

  it('마운트와 단지 ID 교체 때 해당 상세 제목으로 focus를 이동한다', () => {
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
  })

  it('null은 속성별 정보 확인 중 또는 미표시하고 0과 false는 실제 값으로 표시한다', () => {
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

    expect(factValue(facts, '준공일자').getByText('정보 확인 중')).toBeInTheDocument()
    expect(factValue(facts, '엘리베이터').getByText('없음')).toBeInTheDocument()
    expect(factValue(facts, '1년 퇴거 세대수').getByText('0세대')).toBeInTheDocument()
    expect(factValue(facts, '총세대수').getByText('0세대')).toBeInTheDocument()
    expect(factValue(facts, '총주차대수(세대당)').getByText('0대')).toBeInTheDocument()

    const housingType = section(panel, '주택형 정보')
    expect(factValue(housingType, '전용 면적').getByText('정보 확인 중')).toBeInTheDocument()
    expect(factValue(housingType, '공급 면적').getByText('정보 확인 중')).toBeInTheDocument()
    expect(factValue(housingType, '복층여부').getByText('해당 없음')).toBeInTheDocument()
    expect(factValue(housingType, '관리비').getByText('0원')).toBeInTheDocument()
    expect(within(housingType).getAllByText('0원')).toHaveLength(3)
  })

  it('주택형 탭 선택과 좌우 방향키 이동으로 표시할 상세를 바꾼다', () => {
    const { panel } = renderPanel()
    const firstTab = within(panel).getByRole('tab', { name: '36A' })
    const secondTab = within(panel).getByRole('tab', { name: '44B' })

    expect(firstTab).toHaveAttribute('aria-selected', 'true')
    expect(within(panel).getByRole('tabpanel', { name: '36A 주택형 상세' }))
      .toBeInTheDocument()

    firstTab.focus()
    fireEvent.keyDown(firstTab, { key: 'ArrowRight' })
    expect(secondTab).toHaveFocus()
    expect(secondTab).toHaveAttribute('aria-selected', 'true')
    expect(within(panel).getByRole('tabpanel', { name: '44B 주택형 상세' }))
      .toBeInTheDocument()

    fireEvent.keyDown(secondTab, { key: 'ArrowLeft' })
    expect(firstTab).toHaveFocus()
    expect(firstTab).toHaveAttribute('aria-selected', 'true')
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
  })

  it('공고 callback이 없으면 공고 정보는 유지하고 이동 버튼만 숨긴다', () => {
    render(<HousingComplexDetailPanel detail={BASE_DETAIL} onClose={vi.fn()} />)

    expect(screen.getByText('행복주택 입주자 모집 공고')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /공고 상세 보기/ })).not.toBeInTheDocument()
  })

  it('빈 주택형과 현재 공고 배열을 실제 빈 상태로 표시한다', () => {
    const { panel } = renderPanel(
      detailWith({ housingTypes: [], currentAnnouncements: [] }),
    )

    expect(within(panel).queryByRole('heading', { name: '주택형 정보' }))
      .not.toBeInTheDocument()
    expect(within(panel).getByText('현재 연결된 모집 공고가 없습니다.'))
      .toBeInTheDocument()
  })

  it('270px 단일 열과 키보드 focus-visible 규칙을 스타일에 고정한다', () => {
    const css = readFileSync(
      resolve(
        process.cwd(),
        'src/public-housing/components/HousingComplexDetailPanel.module.css',
      ),
      'utf8',
    )
    const narrowRule = css.slice(
      css.indexOf('@container housing-complex-detail (max-width: 270px)'),
    )

    expect(css).toContain('container: housing-complex-detail / inline-size;')
    expect(css).toMatch(/\.closeButton:focus-visible[\s\S]*?outline:/)
    expect(css).toMatch(/\.housingTypeTab:focus-visible[\s\S]*?outline:/)
    expect(css).toMatch(/\.announcementAction:focus-visible[\s\S]*?outline:/)
    expect(narrowRule).toMatch(/\.facts[\s\S]*?grid-template-columns:\s*minmax\(0, 1fr\);/)
    expect(narrowRule).toMatch(/\.announcementCard[\s\S]*?grid-template-columns:\s*minmax\(0, 1fr\);/)
    expect(narrowRule).toMatch(/\.supplyFacts[\s\S]*?grid-template-columns:\s*minmax\(0, 1fr\);/)
  })
})
