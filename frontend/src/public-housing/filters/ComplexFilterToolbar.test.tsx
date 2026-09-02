import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { ComponentProps } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { ComplexSearchFilters } from '../api/publicHousingRepository.ts'
import { ComplexFilterToolbar } from './ComplexFilterToolbar.tsx'

const GYEONGGI_REGIONS = [
  {
    regionCode: '41',
    provinceName: '경기도',
    districtName: null,
    displayName: '경기도 전체',
  },
  {
    regionCode: '41110',
    provinceName: '경기도',
    districtName: '수원시',
    displayName: '경기도 수원시',
  },
  {
    regionCode: '41130',
    provinceName: '경기도',
    districtName: '성남시',
    displayName: '경기도 성남시',
  },
] as const

const BASE_FILTERS: ComplexSearchFilters = {
  regionCode: '11',
  rentalTypes: ['NATIONAL_RENTAL'],
  applicationStatuses: ['APPLYING'],
  agencyCodes: ['LH'],
  recruitmentTypes: ['NEW'],
  minDeposit: 100_000_000,
  maxDeposit: 200_000_000,
  minMonthlyRent: 200_000,
  maxMonthlyRent: 300_000,
  minExclusiveArea: 33,
  maxExclusiveArea: 62.7,
  builtYearFrom: 2019,
  builtYearTo: 2024,
}

const TOOLBAR_STYLES = readFileSync(
  resolve(
    process.cwd(),
    'src/public-housing/filters/ComplexFilterToolbar.module.css',
  ),
  'utf8',
)

describe('ComplexFilterToolbar', () => {
  it('데스크톱 트리거와 선택값을 36px 높이의 둥근 사각형으로 통일한다', () => {
    const triggerRule = TOOLBAR_STYLES.match(/\.trigger\s*\{([^}]*)\}/)?.[1]
    const choiceRule = TOOLBAR_STYLES.match(
      /\.choice\s*>\s*span\s*\{([^}]*)\}/,
    )?.[1]

    expect(triggerRule).toMatch(/height:\s*36px;/)
    expect(triggerRule).toMatch(/border-radius:\s*9px;/)
    expect(choiceRule).toMatch(/min-height:\s*36px;/)
    expect(choiceRule).toMatch(/border-radius:\s*9px;/)
  })

  it('8개 토픽을 독립 버튼으로 보이고 한 번에 하나의 팝오버만 연다', () => {
    renderToolbar()

    expect(screen.getByRole('toolbar', { name: '단지 검색 필터' }))
      .toBeInTheDocument()
    const topics = [
      '지역',
      '임대유형',
      '모집상태',
      '공급기관',
      '모집유형',
      '가격',
      '전용면적',
      '준공년도',
    ]
    topics.forEach((topic) => {
      expect(screen.getByRole('button', { name: `${topic} 필터 열기` }))
        .toHaveAttribute('aria-expanded', 'false')
    })

    fireEvent.click(screen.getByRole('button', { name: '지역 필터 열기' }))
    expect(screen.getByRole('region', { name: '지역 필터' }))
      .toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {
      name: '임대유형 필터 열기',
    }))
    expect(screen.queryByRole('region', { name: '지역 필터' }))
      .not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: '임대유형 필터' }))
      .toBeInTheDocument()
    expect(screen.getByRole('button', { name: '지역 필터 열기' }))
      .toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByRole('button', { name: '임대유형 필터 닫기' }))
      .toHaveAttribute('aria-expanded', 'true')
  })

  it('팝오버를 누른 필터 칩의 가로 중심에 연결한다', () => {
    renderToolbar()
    const toolbar = screen.getByRole('toolbar', { name: '단지 검색 필터' })
    const root = toolbar.closest('section')
    const price = screen.getByRole('button', { name: '가격 필터 열기' })
    if (root === null) throw new Error('필터 root를 찾을 수 없습니다.')
    vi.spyOn(root, 'getBoundingClientRect').mockReturnValue(
      domRect({ left: 100, top: 40, width: 800 }),
    )
    vi.spyOn(price, 'getBoundingClientRect').mockReturnValue(
      domRect({ height: 36, left: 300, top: 90, width: 100 }),
    )

    fireEvent.click(price)

    const popover = screen.getByRole('region', { name: '가격 필터' })
    expect(popover).toHaveAttribute('data-topic', 'price')
    expect(popover.style.getPropertyValue('--popover-anchor-x')).toBe('250px')
    expect(popover.style.getPropertyValue('--popover-width')).toBe('420px')
    expect(popover.style.getPropertyValue('--popover-left')).toBe('200px')
    expect(popover.style.getPropertyValue('--popover-top')).toBe('94px')
  })

  it('필터 행을 가로 스크롤하면 열린 팝오버의 연결 위치를 갱신한다', () => {
    renderToolbar()
    const toolbar = screen.getByRole('toolbar', { name: '단지 검색 필터' })
    const root = toolbar.closest('section')
    const scroller = toolbar.parentElement
    const region = screen.getByRole('button', { name: '지역 필터 열기' })
    if (root === null || scroller === null) {
      throw new Error('필터 배치 요소를 찾을 수 없습니다.')
    }
    vi.spyOn(root, 'getBoundingClientRect').mockReturnValue(
      domRect({ left: 100, width: 800 }),
    )
    let triggerLeft = 180
    vi.spyOn(region, 'getBoundingClientRect').mockImplementation(() =>
      domRect({ left: triggerLeft, width: 80 }),
    )

    fireEvent.click(region)
    const popover = screen.getByRole('region', { name: '지역 필터' })
    expect(popover.style.getPropertyValue('--popover-anchor-x')).toBe('120px')
    expect(popover.style.getPropertyValue('--popover-width')).toBe('320px')
    expect(popover.style.getPropertyValue('--popover-left')).toBe('80px')

    triggerLeft = 260
    fireEvent.scroll(scroller)

    expect(popover.style.getPropertyValue('--popover-anchor-x')).toBe('200px')
    expect(popover.style.getPropertyValue('--popover-left')).toBe('160px')
  })

  it('좁은 화면에서도 지역 팝오버를 전체 폭으로 늘리지 않고 경계 안에 둔다', () => {
    renderToolbar()
    const toolbar = screen.getByRole('toolbar', { name: '단지 검색 필터' })
    const root = toolbar.closest('section')
    const region = screen.getByRole('button', { name: '지역 필터 열기' })
    if (root === null) throw new Error('필터 root를 찾을 수 없습니다.')
    vi.spyOn(root, 'getBoundingClientRect').mockReturnValue(
      domRect({ left: 100, width: 360 }),
    )
    vi.spyOn(region, 'getBoundingClientRect').mockReturnValue(
      domRect({ left: 370, width: 60 }),
    )

    fireEvent.click(region)

    const popover = screen.getByRole('region', { name: '지역 필터' })
    expect(popover.style.getPropertyValue('--popover-anchor-x')).toBe('300px')
    expect(popover.style.getPropertyValue('--popover-width')).toBe('320px')
    expect(popover.style.getPropertyValue('--popover-left')).toBe('40px')
  })

  it.each([
    {
      topic: '임대유형',
      option: '행복주택',
      expected: {
        ...BASE_FILTERS,
        rentalTypes: ['HAPPY_HOUSING', 'NATIONAL_RENTAL'],
      },
    },
    {
      topic: '모집상태',
      option: '접수예정',
      expected: {
        ...BASE_FILTERS,
        applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'],
      },
    },
    {
      topic: '공급기관',
      option: 'SH',
      expected: {
        ...BASE_FILTERS,
        agencyCodes: ['LH', 'SH'],
      },
    },
    {
      topic: '모집유형',
      option: '예비입주자 모집',
      expected: {
        ...BASE_FILTERS,
        recruitmentTypes: ['NEW', 'WAITLIST'],
      },
    },
  ])('$topic 적용은 해당 키만 바꾸고 다른 단지 필터를 보존한다', ({
    expected,
    option,
    topic,
  }) => {
    const onApply = vi.fn()
    renderToolbar({ filters: BASE_FILTERS, onApply })

    fireEvent.click(screen.getByRole('button', {
      name: `${topic} 필터 열기`,
    }))
    fireEvent.click(screen.getByRole('checkbox', { name: option }))
    fireEvent.click(screen.getByRole('button', {
      name: `${topic} 필터 적용`,
    }))

    expect(onApply).toHaveBeenCalledOnce()
    expect(onApply).toHaveBeenCalledWith(expected)
    expect(screen.queryByRole('region', { name: `${topic} 필터` }))
      .not.toBeInTheDocument()
  })

  it('초기화는 해당 토픽의 키만 제거하고 즉시 적용한다', () => {
    const onApply = vi.fn()
    renderToolbar({ filters: BASE_FILTERS, onApply })

    fireEvent.click(screen.getByRole('button', {
      name: '가격 필터 열기',
    }))
    fireEvent.click(screen.getByRole('button', { name: '가격 필터 초기화' }))

    expect(onApply).toHaveBeenCalledWith({
      regionCode: '11',
      rentalTypes: ['NATIONAL_RENTAL'],
      applicationStatuses: ['APPLYING'],
      agencyCodes: ['LH'],
      recruitmentTypes: ['NEW'],
      minExclusiveArea: 33,
      maxExclusiveArea: 62.7,
      builtYearFrom: 2019,
      builtYearTo: 2024,
    })
    expect(screen.queryByRole('region', { name: '가격 필터' }))
      .not.toBeInTheDocument()
  })

  it('Escape와 바깥 클릭은 draft를 적용하지 않고 닫는다', () => {
    const onApply = vi.fn()
    renderToolbar({ filters: BASE_FILTERS, onApply })

    fireEvent.click(screen.getByRole('button', {
      name: '공급기관 필터 열기',
    }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'SH' }))
    fireEvent.keyDown(document, { key: 'Escape' })

    expect(onApply).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '공급기관 필터 열기' }))
      .toHaveFocus()
    fireEvent.click(screen.getByRole('button', {
      name: '공급기관 필터 열기',
    }))
    expect(screen.getByRole('checkbox', { name: 'SH' })).not.toBeChecked()

    fireEvent.click(screen.getByRole('checkbox', { name: 'SH' }))
    fireEvent.pointerDown(document.body)

    expect(onApply).not.toHaveBeenCalled()
    expect(screen.queryByRole('region', { name: '공급기관 필터' }))
      .not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {
      name: '공급기관 필터 열기',
    }))
    expect(screen.getByRole('checkbox', { name: 'SH' })).not.toBeChecked()
  })

  it('가격 팝오버에서 보증금과 월세 범위를 함께 적용한다', () => {
    const onApply = vi.fn()
    renderToolbar({ filters: BASE_FILTERS, onApply })

    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))
    const popover = screen.getByRole('region', { name: '가격 필터' })
    fireEvent.click(within(within(popover).getByRole('group', {
      name: '임대보증금 빠른 선택',
    })).getByRole('button', { name: '2~3억' }))
    fireEvent.click(within(within(popover).getByRole('group', {
      name: '월 임대료 빠른 선택',
    })).getByRole('button', { name: '40~60만' }))
    fireEvent.click(within(popover).getByRole('button', {
      name: '가격 필터 적용',
    }))

    expect(onApply).toHaveBeenCalledWith({
      ...BASE_FILTERS,
      minDeposit: 200_000_000,
      maxDeposit: 300_000_000,
      minMonthlyRent: 400_000,
      maxMonthlyRent: 590_000,
    })
  })

  it('가격 빠른 선택은 전체 중복 없이 범위별 4~5개만 한 번에 제공한다', () => {
    renderToolbar()

    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))
    const popover = screen.getByRole('region', { name: '가격 필터' })
    const depositPresets = within(popover).getByRole('group', {
      name: '임대보증금 빠른 선택',
    })
    const rentPresets = within(popover).getByRole('group', {
      name: '월 임대료 빠른 선택',
    })

    expect(within(depositPresets).getAllByRole('button').map(
      (button) => button.textContent,
    )).toEqual(['1억 이하', '1~2억', '2~3억', '3~5억', '5억 이상'])
    expect(within(rentPresets).getAllByRole('button').map(
      (button) => button.textContent,
    )).toEqual(['10만 이하', '10~20만', '20~30만', '30~40만', '40~60만'])
    expect(within(popover).getAllByRole('status', {
      name: /선택 범위/,
    }).map((output) => output.textContent)).toEqual(['전체', '전체'])
  })

  it('URL의 endpoint·domain 초과·step 비정렬 범위를 무변경 적용하면 그대로 보존한다', () => {
    const onApply = vi.fn()
    const filters: ComplexSearchFilters = {
      minDeposit: 0,
      maxDeposit: 500_000_000,
      minMonthlyRent: 610_000,
      maxMonthlyRent: 700_000,
      minExclusiveArea: 50,
      maxExclusiveArea: 150,
    }
    renderToolbar({ filters, onApply })

    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))
    fireEvent.click(screen.getByRole('button', { name: '가격 필터 적용' }))
    fireEvent.click(screen.getByRole('button', {
      name: '전용면적 필터 열기',
    }))
    fireEvent.click(screen.getByRole('button', {
      name: '전용면적 필터 적용',
    }))

    expect(onApply).toHaveBeenNthCalledWith(1, filters)
    expect(onApply).toHaveBeenNthCalledWith(2, filters)
  })

  it('가격 slider를 조작하면 해당 범위만 정규화하고 다른 범위 원값은 보존한다', () => {
    const onApply = vi.fn()
    const filters: ComplexSearchFilters = {
      minDeposit: 140_000_000,
      maxDeposit: 155_000_000,
      minMonthlyRent: 610_000,
      maxMonthlyRent: 700_000,
    }
    renderToolbar({ filters, onApply })

    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))
    fireEvent.change(screen.getByRole('slider', {
      name: '임대보증금 최솟값',
    }), { target: { value: '160000000' } })
    fireEvent.click(screen.getByRole('button', { name: '가격 필터 적용' }))

    expect(onApply).toHaveBeenCalledWith({
      minDeposit: 160_000_000,
      maxDeposit: 160_000_000,
      minMonthlyRent: 610_000,
      maxMonthlyRent: 700_000,
    })
  })

  it('전용면적은 가격과 분리된 범위 팝오버에서 적용한다', () => {
    const onApply = vi.fn()
    renderToolbar({ filters: BASE_FILTERS, onApply })

    fireEvent.click(screen.getByRole('button', {
      name: '전용면적 필터 열기',
    }))
    const popover = screen.getByRole('region', { name: '전용면적 필터' })
    fireEvent.click(within(within(popover).getByRole('group', {
      name: '전용면적 빠른 선택',
    })).getByRole('button', { name: '30평 이상' }))
    fireEvent.click(within(popover).getByRole('button', {
      name: '전용면적 필터 적용',
    }))

    expect(onApply).toHaveBeenCalledWith({
      regionCode: '11',
      rentalTypes: ['NATIONAL_RENTAL'],
      applicationStatuses: ['APPLYING'],
      agencyCodes: ['LH'],
      recruitmentTypes: ['NEW'],
      minDeposit: 100_000_000,
      maxDeposit: 200_000_000,
      minMonthlyRent: 200_000,
      maxMonthlyRent: 300_000,
      minExclusiveArea: 99,
      builtYearFrom: 2019,
      builtYearTo: 2024,
    })
  })

  it('준공년도 역전 범위는 적용하지 않고 팝오버에 오류를 보인다', () => {
    const onApply = vi.fn()
    renderToolbar({ filters: BASE_FILTERS, onApply })

    fireEvent.click(screen.getByRole('button', {
      name: '준공년도 필터 열기',
    }))
    fireEvent.change(screen.getByRole('combobox', {
      name: '최소 준공년도',
    }), { target: { value: '2025' } })
    fireEvent.change(screen.getByRole('combobox', {
      name: '최대 준공년도',
    }), { target: { value: '2020' } })
    fireEvent.click(screen.getByRole('button', {
      name: '준공년도 필터 적용',
    }))

    expect(onApply).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent(
      '최소 준공년도는 최대 준공년도보다 클 수 없습니다.',
    )
    expect(screen.getByRole('region', { name: '준공년도 필터' }))
      .toBeInTheDocument()
  })

  it('준공년도 드롭다운에서 최소·최대 연도를 선택해 적용한다', () => {
    const onApply = vi.fn()
    renderToolbar({ onApply })

    fireEvent.click(screen.getByRole('button', {
      name: '준공년도 필터 열기',
    }))
    const minimum = screen.getByRole('combobox', {
      name: '최소 준공년도',
    })
    const maximum = screen.getByRole('combobox', {
      name: '최대 준공년도',
    })
    const latestYear = String(new Date().getFullYear() + 5)

    expect(within(minimum).getByRole('option', { name: '제한 없음' }))
      .toHaveValue('')
    expect(within(minimum).getAllByRole<HTMLOptionElement>('option')
      .map((option) => option.value))
      .toEqual([
        '',
        ...Array.from(
          { length: Number(latestYear) - 1980 + 1 },
          (_, index) => String(Number(latestYear) - index),
        ),
      ])

    fireEvent.change(minimum, { target: { value: '1998' } })
    fireEvent.change(maximum, { target: { value: '2021' } })
    fireEvent.click(screen.getByRole('button', {
      name: '준공년도 필터 적용',
    }))

    expect(onApply).toHaveBeenCalledWith({
      builtYearFrom: 1998,
      builtYearTo: 2021,
    })
  })

  it('선택 범위 밖의 기존 준공년도도 드롭다운에서 보존해 적용한다', () => {
    const onApply = vi.fn()
    const filters: ComplexSearchFilters = {
      builtYearFrom: 1979,
      builtYearTo: 9999,
    }
    renderToolbar({ filters, onApply })

    fireEvent.click(screen.getByRole('button', {
      name: '준공년도 필터 열기',
    }))
    const minimum = screen.getByRole('combobox', {
      name: '최소 준공년도',
    })
    const maximum = screen.getByRole('combobox', {
      name: '최대 준공년도',
    })

    expect(minimum).toHaveValue('1979')
    expect(maximum).toHaveValue('9999')
    expect(within(minimum).getByRole('option', { name: '1979년' }))
      .toBeInTheDocument()
    expect(within(maximum).getByRole('option', { name: '9999년' }))
      .toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {
      name: '준공년도 필터 적용',
    }))

    expect(onApply).toHaveBeenCalledWith(filters)
  })

  it('지역 repository로 시군구를 불러와 지역 키만 교체한다', async () => {
    const onApply = vi.fn()
    const regionRepository = {
      search: vi.fn().mockResolvedValue(GYEONGGI_REGIONS),
    }
    renderToolbar({ filters: BASE_FILTERS, onApply, regionRepository })

    fireEvent.click(screen.getByRole('button', { name: '지역 필터 열기' }))
    fireEvent.change(screen.getByLabelText('시·도'), {
      target: { value: '41' },
    })

    const districtSelect = await screen.findByLabelText('시·군·구')
    await waitFor(() => {
      expect(regionRepository.search).toHaveBeenLastCalledWith(
        '경기도',
        expect.any(AbortSignal),
      )
      expect(within(districtSelect).getByRole('option', { name: '수원시' }))
        .toBeInTheDocument()
    })
    fireEvent.change(districtSelect, { target: { value: '41110' } })
    fireEvent.click(screen.getByRole('button', { name: '지역 필터 적용' }))

    expect(onApply).toHaveBeenCalledWith({
      ...BASE_FILTERS,
      regionCode: '41110',
    })
  })

  it('적용된 토픽 버튼에 요약과 active 상태를 보인다', () => {
    renderToolbar({ filters: BASE_FILTERS })

    const expectedSummaries = [
      ['지역', '서울'],
      ['임대유형', '국민임대'],
      ['모집상태', '접수중'],
      ['공급기관', 'LH'],
      ['모집유형', '신규 모집'],
      ['가격', '1억~2억 · 월 20만~30만'],
      ['전용면적', '10~19평'],
      ['준공년도', '2019~2024년'],
    ] as const

    expectedSummaries.forEach(([topic, summary]) => {
      const button = screen.getByRole('button', {
        name: `${topic} 필터 열기`,
      })
      expect(button).toHaveAttribute('data-active', 'true')
      expect(button.textContent).toBe(summary)
      expect(button).toHaveAccessibleDescription(`적용됨: ${summary}`)
    })
  })

  it('적용값이 없는 토픽은 분류명을 그대로 보인다', () => {
    renderToolbar()

    expect(screen.getByRole('button', { name: '지역 필터 열기' }).textContent)
      .toBe('지역')
    expect(screen.getByRole('button', { name: '가격 필터 열기' }).textContent)
      .toBe('가격')
  })

  it('다섯 자리 지역은 실제 시군구 이름을 active 요약으로 보인다', async () => {
    const regionRepository = {
      search: vi.fn().mockResolvedValue(GYEONGGI_REGIONS),
    }
    renderToolbar({ filters: { regionCode: '41110' }, regionRepository })

    const region = screen.getByRole('button', { name: '지역 필터 열기' })
    await waitFor(() => {
      expect(region).toHaveTextContent('경기 수원시')
      expect(region).toHaveAccessibleDescription('적용됨: 경기 수원시')
    })
  })

  it('모바일 핵심 조건은 적용값을 읽어 주고 하나의 전체 필터 시트에서 초기화한다', async () => {
    const onApply = vi.fn()
    renderToolbar({
      filters: {
        regionCode: '11',
        rentalTypes: ['NATIONAL_RENTAL'],
        minDeposit: 100_000_000,
        maxDeposit: 200_000_000,
      },
      onApply,
      resultCountLabel: '12곳',
    })
    const mobileToolbar = screen.getByRole('toolbar', {
      name: '모바일 단지 검색 필터',
    })

    expect(within(mobileToolbar).getAllByRole('button').map(
      (button) => button.textContent,
    )).toEqual(['서울', '국민임대', '1억~2억', '필터 3'])
    expect(within(mobileToolbar).getByRole('button', {
      name: '가격 1억~2억, 전체 단지 필터 열기',
    })).toBeInTheDocument()

    fireEvent.click(within(mobileToolbar).getByRole('button', {
      name: '가격 1억~2억, 전체 단지 필터 열기',
    }))

    expect(screen.queryByRole('region', { name: '가격 필터' }))
      .not.toBeInTheDocument()
    const sheet = screen.getByRole('dialog', { name: '단지 필터' })
    expect(within(sheet).getByRole('button', {
      name: '단지 필터 닫기',
    })).toHaveFocus()
    expect(within(sheet).getByRole('button', {
      name: '단지 12곳 보기',
    })).toBeInTheDocument()
    expect(within(sheet).getByRole('slider', {
      name: '임대보증금 최솟값',
    })).toBeInTheDocument()
    expect(within(sheet).getByRole('slider', {
      name: '전용면적 최댓값',
    })).toBeInTheDocument()

    fireEvent.click(within(sheet).getByRole('button', {
      name: '전체 필터 초기화',
    }))
    expect(onApply).not.toHaveBeenCalled()
    await waitFor(() => {
      expect(within(sheet).getByRole('button', {
        name: '전체 필터 초기화',
      })).toHaveFocus()
    })

    fireEvent.click(within(sheet).getByRole('button', {
      name: '단지 보기',
    }))
    expect(onApply).toHaveBeenCalledWith({})
    expect(screen.queryByRole('dialog', { name: '단지 필터' }))
      .not.toBeInTheDocument()
  })

  it('모바일 시트가 열린 동안 외부 필터가 바뀌면 stale draft를 닫는다', () => {
    const onApply = vi.fn()
    const regionRepository = { search: vi.fn().mockResolvedValue([]) }
    const { rerender } = render(
      <ComplexFilterToolbar
        filters={{ regionCode: '11' }}
        onApply={onApply}
        regionRepository={regionRepository}
      />,
    )

    fireEvent.click(screen.getByRole('button', {
      name: '전체 단지 필터 열기, 1개 적용',
    }))
    expect(screen.getByRole('dialog', { name: '단지 필터' }))
      .toBeInTheDocument()

    rerender(
      <ComplexFilterToolbar
        filters={{ regionCode: '41' }}
        onApply={onApply}
        regionRepository={regionRepository}
      />,
    )

    expect(screen.queryByRole('dialog', { name: '단지 필터' }))
      .not.toBeInTheDocument()
    expect(onApply).not.toHaveBeenCalled()
  })

  it('모바일 시트는 배경 스크롤을 잠그고 내부 토픽만 직접 이동한다', () => {
    const scrollIntoView = vi.fn()
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: scrollIntoView,
    })
    renderToolbar({ filters: { minDeposit: 100_000_000 } })

    fireEvent.click(screen.getByRole('button', {
      name: '가격 1억 이상, 전체 단지 필터 열기',
    }))

    expect(scrollIntoView).not.toHaveBeenCalled()
    expect(document.documentElement.style.overflow).toBe('hidden')
    expect(document.body.style.overflow).toBe('hidden')

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(document.documentElement.style.overflow).toBe('')
    expect(document.body.style.overflow).toBe('')
    Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
  })

  it('모바일 시트가 열린 채 데스크톱 너비가 되면 숨은 dialog를 종료한다', () => {
    const innerWidth = vi.spyOn(window, 'innerWidth', 'get')
      .mockReturnValue(390)
    renderToolbar()
    fireEvent.click(screen.getByRole('button', {
      name: '전체 단지 필터 열기',
    }))
    expect(screen.getByRole('dialog', { name: '단지 필터' }))
      .toBeInTheDocument()

    innerWidth.mockReturnValue(768)
    fireEvent(window, new Event('resize'))

    expect(screen.queryByRole('dialog', { name: '단지 필터' }))
      .not.toBeInTheDocument()
    innerWidth.mockRestore()
  })

  it('시군구 로딩 실패를 즉시 알리고 관련 select와 연결한다', async () => {
    const regionRepository = {
      search: vi.fn().mockRejectedValue(new Error('network error')),
    }
    renderToolbar({ filters: { regionCode: '11' }, regionRepository })

    fireEvent.click(screen.getByRole('button', { name: '지역 필터 열기' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(
      '시·군·구를 불러오지 못했습니다.',
    )
    expect(screen.getByLabelText('시·군·구'))
      .toHaveAttribute('aria-describedby', alert.id)
  })

  it('toolbar 키보드 키로 인접 필터와 양 끝으로 포커스를 옮긴다', () => {
    renderToolbar()
    const region = screen.getByRole('button', { name: '지역 필터 열기' })
    const rentalType = screen.getByRole('button', {
      name: '임대유형 필터 열기',
    })
    const builtYear = screen.getByRole('button', {
      name: '준공년도 필터 열기',
    })

    region.focus()
    fireEvent.keyDown(region, { key: 'ArrowRight' })
    expect(rentalType).toHaveFocus()
    fireEvent.keyDown(rentalType, { key: 'End' })
    expect(builtYear).toHaveFocus()
    fireEvent.keyDown(builtYear, { key: 'ArrowRight' })
    expect(region).toHaveFocus()
    fireEvent.keyDown(region, { key: 'ArrowLeft' })
    expect(builtYear).toHaveFocus()
    fireEvent.keyDown(builtYear, { key: 'Home' })
    expect(region).toHaveFocus()
  })
})

function renderToolbar({
  filters = {},
  onApply = vi.fn(),
  regionRepository = { search: vi.fn().mockResolvedValue([]) },
  resultCountLabel,
}: {
  readonly filters?: ComplexSearchFilters
  readonly onApply?: ComponentProps<typeof ComplexFilterToolbar>['onApply']
  readonly regionRepository?: ComponentProps<
    typeof ComplexFilterToolbar
  >['regionRepository']
  readonly resultCountLabel?: string
} = {}) {
  return render(
    <ComplexFilterToolbar
      filters={filters}
      onApply={onApply}
      regionRepository={regionRepository}
      resultCountLabel={resultCountLabel}
    />,
  )
}

function domRect({
  height = 0,
  left,
  top = 0,
  width,
}: {
  readonly height?: number
  readonly left: number
  readonly top?: number
  readonly width: number
}): DOMRect {
  return {
    bottom: top + height,
    height,
    left,
    right: left + width,
    top,
    width,
    x: left,
    y: top,
    toJSON: () => ({}),
  }
}
