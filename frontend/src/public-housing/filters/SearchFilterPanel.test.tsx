import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { type ComponentProps, createElement } from 'react'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import searchFilterPanelClasses from './SearchFilterPanel.module.css'
import { SearchFilterPanel } from './SearchFilterPanel.tsx'

const styleElement = document.createElement('style')
const searchFilterPanelStylesheet = scopeCssModule(
  readFileSync(
    resolve(
      process.cwd(),
      'src/public-housing/filters/SearchFilterPanel.module.css',
    ),
    'utf8',
  ),
  {
    choiceGroup: searchFilterPanelClasses.choiceGroup,
    choiceOptions: searchFilterPanelClasses.choiceOptions,
  },
)

beforeAll(() => {
  styleElement.textContent = searchFilterPanelStylesheet
  document.head.append(styleElement)
})

afterAll(() => styleElement.remove())

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
    regionCode: '41111',
    provinceName: '경기도',
    districtName: '수원시 장안구',
    displayName: '경기도 수원시 장안구',
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

describe('SearchFilterPanel', () => {
  it('공고 필터 항목명과 선택값을 구분선과 간격으로 분리한다', () => {
    renderFilter({ kind: 'announcement' })

    fireEvent.click(screen.getByRole('button', { name: '공고 필터 열기' }))

    const rentalTypeGroup = screen.getByRole('group', { name: '임대유형' })
    const firstChoice = within(rentalTypeGroup).getByRole('checkbox', {
      name: '행복주택',
    })
    const valueArea = firstChoice.closest('div')
    const legend = within(rentalTypeGroup).getByText('임대유형')

    if (!(valueArea instanceof HTMLElement)) {
      throw new Error('임대유형 선택값 영역을 찾을 수 없습니다.')
    }
    expect(getComputedStyle(valueArea).borderTopStyle)
      .toBe('solid')
    expect(getComputedStyle(valueArea).borderTopWidth)
      .toBe('1px')
    expect(getComputedStyle(valueArea).paddingTop).toBe('8px')
    expect(getComputedStyle(legend).fontWeight).toBe('800')
  })

  it('시도를 선택하면 직속 시군구를 불러오고 시도만 또는 시군구까지 적용한다', async () => {
    const onApply = vi.fn()
    const regionRepository = {
      search: vi.fn().mockResolvedValue(GYEONGGI_REGIONS),
    }
    renderFilter({ onApply, regionRepository })

    fireEvent.click(screen.getByRole('button', { name: '단지 필터 열기' }))
    fireEvent.change(screen.getByLabelText('시·도'), {
      target: { value: '41' },
    })

    const districtSelect = await screen.findByLabelText('시·군·구')
    expect(regionRepository.search).toHaveBeenCalledWith(
      '경기도',
      expect.any(AbortSignal),
    )
    expect(within(districtSelect).getByRole('option', { name: '수원시' }))
      .toBeInTheDocument()
    expect(within(districtSelect).getByRole('option', { name: '성남시' }))
      .toBeInTheDocument()
    expect(within(districtSelect).queryByRole('option', {
      name: '수원시 장안구',
    })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '단지 필터 적용' }))
    expect(onApply).toHaveBeenLastCalledWith({ regionCode: '41' })

    fireEvent.change(districtSelect, { target: { value: '41110' } })
    fireEvent.click(screen.getByRole('button', { name: '단지 필터 적용' }))
    expect(onApply).toHaveBeenLastCalledWith({ regionCode: '41110' })
  })

  it('공유 URL의 세부 지역은 일반 후보에서 숨겨져도 선택값을 복원한다', async () => {
    const regionRepository = {
      search: vi.fn().mockResolvedValue(GYEONGGI_REGIONS),
    }
    renderFilter({
      filters: { regionCode: '41135' },
      regionRepository,
    })

    fireEvent.click(screen.getByRole('button', { name: '단지 필터 열기' }))

    expect(screen.getByLabelText('시·도')).toHaveValue('41')
    await waitFor(() => {
      expect(screen.getByLabelText('시·군·구')).toHaveValue('41135')
    })
    expect(screen.getByRole('option', {
      name: '성남시 분당구',
    })).toBeInTheDocument()
  })

  it('시군구 로딩 실패를 즉시 알리고 관련 select와 연결한다', async () => {
    const regionRepository = {
      search: vi.fn().mockRejectedValue(new Error('network error')),
    }
    renderFilter({ filters: { regionCode: '41' }, regionRepository })

    fireEvent.click(screen.getByRole('button', { name: '단지 필터 열기' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('시·군·구를 불러오지 못했습니다.')
    expect(screen.getByLabelText('시·군·구'))
      .toHaveAttribute('aria-describedby', alert.id)
  })

  it('보증금·월세·면적을 승인된 간격의 슬라이더와 빠른 선택으로 적용한다', () => {
    const onApply = vi.fn()
    renderFilter({ onApply })

    fireEvent.click(screen.getByRole('button', { name: '단지 필터 열기' }))
    const depositMinimum = screen.getByRole('slider', {
      name: '임대보증금 최솟값',
    })
    const depositMaximum = screen.getByRole('slider', {
      name: '임대보증금 최댓값',
    })
    const monthlyMinimum = screen.getByRole('slider', {
      name: '월 임대료 최솟값',
    })
    const areaMinimum = screen.getByRole('slider', {
      name: '전용면적 최솟값',
    })

    expect(depositMinimum).toHaveAttribute('step', '10000000')
    expect(depositMaximum).toHaveAttribute('max', '500000000')
    expect(monthlyMinimum).toHaveAttribute('step', '10000')
    expect(monthlyMinimum).toHaveAttribute('max', '600000')
    expect(areaMinimum).toHaveAttribute('step', '3.3')
    expect(areaMinimum).toHaveAttribute('max', '132')

    fireEvent.change(depositMinimum, { target: { value: '100000000' } })
    fireEvent.change(depositMaximum, { target: { value: '300000000' } })
    expect(screen.getByRole('status', {
      name: '임대보증금 선택 범위',
    })).toHaveTextContent('1억 ~ 3억')

    fireEvent.click(screen.getByRole('button', { name: '10평대' }))
    fireEvent.click(screen.getByRole('button', { name: '단지 필터 적용' }))

    expect(onApply).toHaveBeenLastCalledWith({
      maxDeposit: 300_000_000,
      maxExclusiveArea: 62.7,
      minDeposit: 100_000_000,
      minExclusiveArea: 33,
    })
  })
})

function renderFilter({
  filters = {},
  kind = 'complex',
  onApply = vi.fn(),
  regionRepository = { search: vi.fn().mockResolvedValue([]) },
}: {
  readonly filters?: ComponentProps<typeof SearchFilterPanel>['filters']
  readonly kind?: ComponentProps<typeof SearchFilterPanel>['kind']
  readonly onApply?: ComponentProps<typeof SearchFilterPanel>['onApply']
  readonly regionRepository?: {
    readonly search: (
      keyword: string,
      signal: AbortSignal,
    ) => Promise<readonly unknown[]>
  }
} = {}) {
  const props = {
    filters,
    kind,
    onApply,
    regionRepository,
  } as ComponentProps<typeof SearchFilterPanel>
  return render(createElement(SearchFilterPanel, props))
}

function scopeCssModule(
  stylesheet: string,
  classNames: Readonly<Record<string, string>>,
) {
  return Object.entries(classNames).reduce(
    (scopedStylesheet, [className, scopedClassName]) => scopedStylesheet.replace(
      new RegExp(`\\.${className}(?![\\w-])`, 'g'),
      `.${scopedClassName}`,
    ),
    stylesheet,
  )
}
