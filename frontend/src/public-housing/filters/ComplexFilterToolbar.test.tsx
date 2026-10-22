import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { type ComponentProps, useState } from 'react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
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
  applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'],
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

afterEach(() => vi.unstubAllGlobals())

describe('ComplexFilterToolbar', () => {

  it.each([['desktop', '지역'], ['mobile', '지역'], ['desktop', '전체'], ['mobile', '전체']])('%s %s 지역 선택에서 이전 단계는 하위 조건만 해제하고 다시 진입해도 복원하지 않는다', async (mode, panel) => {
    const regionRepository = { search: vi.fn().mockResolvedValue([
      { regionCode: '11110', provinceName: '서울특별시', districtName: '종로구', displayName: '서울특별시 종로구' },
      { regionCode: '1111010100', provinceName: '서울특별시', districtName: '종로구', displayName: '서울특별시 종로구 청운동' },
    ]) }
    render(<StatefulToolbar initialFilters={{ minDeposit: 100_000_000, rentalTypes: ['HAPPY_HOUSING'] }} regionRepository={regionRepository} />)
    const open = screen.getByRole('button', { name: `${mode === 'mobile' ? '모바일 ' : ''}${panel} 필터 열기` })
    fireEvent.click(open)
    fireEvent.click(screen.getByRole('radio', { name: '서울' }))
    fireEvent.click(await screen.findByRole('radio', { name: '종로구' }))
    fireEvent.click(screen.getByRole('radio', { name: '청운동' }))
    expect(appliedFilters()).toEqual({ regionCode: '1111010100', minDeposit: 100_000_000, rentalTypes: ['HAPPY_HOUSING'] })
    expect(screen.getByRole('status', { name: '현재 지역' })).toHaveTextContent('서울 종로구 청운동')
    fireEvent.click(screen.getByRole('button', { name: '시·군·구 선택으로 돌아가기' }))
    expect(appliedFilters().regionCode).toBe('11110')
    await waitFor(() => expect(screen.getByRole('button', { name: '시·군·구 선택' })).toHaveFocus())
    fireEvent.click(screen.getByRole('radio', { name: '종로구' }))
    expect(screen.getByRole('radio', { name: '청운동' })).not.toBeChecked()
    expect(screen.getByRole('radio', { name: '전체' })).toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: '시·도 선택' }))
    expect(appliedFilters().regionCode).toBe('11')
    expect(screen.getByRole('button', { name: '읍·면·동 선택' })).toBeDisabled()
    fireEvent.click(screen.getByRole('radio', { name: '전체' }))
    expect(appliedFilters()).toEqual({ minDeposit: 100_000_000, rentalTypes: ['HAPPY_HOUSING'] })
    expect(screen.getByRole('status', { name: '현재 지역' })).toHaveTextContent('전국')
    fireEvent.keyDown(document, { key: 'Escape' })
    fireEvent.click(open)
    expect(screen.getByRole('radio', { name: '전체' })).toBeChecked()
  })

  it('하위 지역이 없으면 안내하고 이전 단계에서 다른 시도를 고를 수 있다', async () => {
    render(<StatefulToolbar initialFilters={{}} regionRepository={{ search: vi.fn().mockResolvedValue([]) }} />)
    fireEvent.click(screen.getByRole('button', { name: '지역 필터 열기' }))
    fireEvent.click(screen.getByRole('radio', { name: '세종' }))
    expect(await screen.findByText('선택할 하위 지역이 없어요. 상위 지역 전체로 검색할 수 있어요.')).toBeVisible()
    expect(screen.getByRole('radio', { name: '세종 전체' })).toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: '시·도 선택으로 돌아가기' }))
    fireEvent.click(screen.getByRole('radio', { name: '서울' }))
    expect(appliedFilters()).toEqual({ regionCode: '11' })
  })

  it('모바일 임대유형은 연속 선택 즉시 적용하고 닫아도 선택과 다른 조건을 보존한다', () => {
    render(<StatefulToolbar initialFilters={{ agencyCodes: ['LH'] }} />)
    const trigger = screen.getByRole('button', { name: '모바일 임대유형 필터 열기' })
    fireEvent.click(trigger)
    const happy = screen.getByRole('checkbox', { name: '행복주택' })
    expect(happy.closest('[data-appearance]')).toHaveAttribute('data-appearance', 'grid')
    expect(happy.closest('label')).not.toHaveTextContent('✓')
    happy.focus()
    fireEvent.click(happy)
    expect(appliedFilters()).toEqual({ agencyCodes: ['LH'], rentalTypes: ['HAPPY_HOUSING'] })
    expect(happy).toHaveFocus()
    fireEvent.click(screen.getByRole('checkbox', { name: '국민임대' }))
    expect(appliedFilters().rentalTypes).toEqual(expect.arrayContaining(['HAPPY_HOUSING', 'NATIONAL_RENTAL']))
    expect(screen.queryByRole('button', { name: /적용$/ })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '단지 필터 닫기' }))
    fireEvent.click(trigger)
    expect(screen.getByRole('checkbox', { name: '행복주택' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '국민임대' })).toBeChecked()
  })

  it.each([
    ['가격', '임대보증금 최댓값', '100000000', { maxDeposit: 100_000_000 }],
    ['전용면적', '전용면적 최댓값', '66', { maxExclusiveArea: 66 }],
  ] as const)('모바일 %s 슬라이더는 손을 놓을 때 적용하고 패널을 유지한다', (topic, label, value, expected) => {
    const onApply = vi.fn()
    render(<StatefulToolbar initialFilters={{ agencyCodes: ['LH'] }} onApply={onApply} />)
    fireEvent.click(screen.getByRole('button', { name: `모바일 ${topic} 필터 열기` }))
    const slider = screen.getByRole('slider', { name: label })
    fireEvent.pointerDown(slider)
    fireEvent.change(slider, { target: { value } })
    expect(onApply).not.toHaveBeenCalled()
    fireEvent.pointerUp(slider)
    expect(appliedFilters()).toEqual({ agencyCodes: ['LH'], ...expected })
    expect(screen.getByRole('dialog', { name: `${topic} 필터` })).toBeVisible()
    expect(screen.queryByRole('button', { name: /적용$/ })).not.toBeInTheDocument()
  })

  it('모바일 준공년도는 유효한 선택만 즉시 적용하고 역전 범위는 수정할 때까지 유지한다', () => {
    render(<StatefulToolbar initialFilters={{ builtYearTo: 2020, agencyCodes: ['LH'] }} />)
    fireEvent.click(screen.getByRole('button', { name: '모바일 전체 필터 열기' }))
    fireEvent.change(screen.getByLabelText('최소 준공년도'), { target: { value: '2025' } })
    expect(screen.getByRole('alert')).toBeVisible()
    expect(appliedFilters()).toEqual({ builtYearTo: 2020, agencyCodes: ['LH'] })
    fireEvent.change(screen.getByLabelText('최대 준공년도'), { target: { value: '2026' } })
    expect(appliedFilters()).toEqual({ builtYearFrom: 2025, builtYearTo: 2026, agencyCodes: ['LH'] })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /적용$/ })).not.toBeInTheDocument()
  })

  it.each(['desktop', 'mobile'])('%s 모집중은 기본 해제이며 두 모집 상태를 함께 켜고 다른 조건을 유지하며 해제한다', (mode) => {
    render(<StatefulToolbar initialFilters={{ agencyCodes: ['LH'], maxDeposit: 0 }} />)
    const toolbar = screen.getByRole('toolbar', { name: mode === 'desktop' ? '단지 검색 필터' : '모바일 단지 검색 필터' })
    const toggle = within(toolbar).getByRole('switch', { name: '모집중', checked: false })
    expect(toggle).not.toHaveAttribute('aria-expanded')
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-checked', 'true')
    expect(appliedFilters()).toEqual({ agencyCodes: ['LH'], maxDeposit: 0, applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'] })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-checked', 'false')
    expect(appliedFilters()).toEqual({ agencyCodes: ['LH'], maxDeposit: 0 })
  })

  it.each(['desktop', 'mobile'])('%s 모집중은 이름만 표시하고 켜짐 상태를 전환한다', (mode) => {
    render(<StatefulToolbar initialFilters={{}} />)
    const toolbar = screen.getByRole('toolbar', { name: mode === 'desktop' ? '단지 검색 필터' : '모바일 단지 검색 필터' })
    const toggle = within(toolbar).getByRole('switch', { name: '모집중', checked: false })

    expect(toolbar.lastElementChild).toContainElement(toggle)
    expect(toggle).toHaveTextContent(/^모집중$/)
    fireEvent.click(toggle)
    expect(toggle).toHaveTextContent(/^모집중$/)
    expect(toggle).toHaveAttribute('aria-checked', 'true')

    const css = readFileSync(resolve(process.cwd(), 'src/public-housing/filters/ComplexFilterToolbar.module.css'), 'utf8')
    expect(css).toMatch(/\.toolbar \{[^}]*flex-direction: column;/)
    expect(css).toMatch(/\.mobileToolbar \{[^}]*flex-direction: row;/)
    expect(css).toMatch(/\.recruitingRow \{[^}]*flex: none;/)
  })

  it.each(['desktop', 'mobile'])('%s 전체 패널은 모집상태 선택 없이 다른 조건을 변경해도 모집중을 유지하고 전체 초기화로 해제한다', (mode) => {
    render(<StatefulToolbar initialFilters={{ applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'] }} />)
    const toolbar = screen.getByRole('toolbar', { name: mode === 'desktop' ? '단지 검색 필터' : '모바일 단지 검색 필터' })
    const toggle = within(toolbar).getByRole('switch', { name: '모집중', checked: true })
    expect(within(toolbar).queryByRole('button', { name: /모집상태/ })).not.toBeInTheDocument()
    fireEvent.click(within(toolbar).getByRole('button', { name: mode === 'desktop' ? '전체 필터 열기' : '모바일 전체 필터 열기' }))
    expect(screen.queryByRole('group', { name: '모집상태' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: '행복주택' }))
    expect(appliedFilters()).toEqual({ applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'], rentalTypes: ['HAPPY_HOUSING'] })
    expect(toggle).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(screen.getByRole('button', { name: '전체 필터 초기화' }))
    expect(toggle).toHaveAttribute('aria-checked', 'false')
    expect(appliedFilters()).toEqual({})
  })

  it.each(['desktop', 'mobile'])('%s 전체 배지는 선택 항목 수가 아닌 적용 중인 필터 종류 수를 표시한다', (mode) => {
    const { rerender } = renderToolbar({ filters: BASE_FILTERS })
    const name = mode === 'desktop' ? '전체 필터 열기' : '모바일 전체 필터 열기'
    const trigger = screen.getByRole('button', { name })
    expect(within(trigger).getByText('8')).toBeVisible()
    expect(trigger).toHaveAccessibleDescription('적용 중인 필터 8개')
    rerender(<ComplexFilterToolbar filters={{
      rentalTypes: ['HAPPY_HOUSING', 'NATIONAL_RENTAL'],
      minDeposit: 100_000_000, maxDeposit: 200_000_000, minMonthlyRent: 0,
    }} onApply={vi.fn()} />)
    expect(within(trigger).getByText('2')).toBeVisible()
    expect(trigger).toHaveAccessibleDescription('적용 중인 필터 2개')
    rerender(<ComplexFilterToolbar filters={{ rentalTypes: [], regionCode: '' }} onApply={vi.fn()} />)
    expect(trigger).toHaveTextContent(/^전체$/)
    expect(trigger).not.toHaveAttribute('aria-describedby')
    expect(trigger).toHaveAttribute('data-active', 'false')
  })

  it.each(['desktop', 'mobile'])('%s 전체 선택과 초기화를 즉시 배지에 반영하고 패널을 유지한다', (mode) => {
    render(<StatefulToolbar initialFilters={{ rentalTypes: ['NATIONAL_RENTAL'] }} />)
    const toolbar = screen.getByRole('toolbar', { name: mode === 'desktop' ? '단지 검색 필터' : '모바일 단지 검색 필터' })
    const trigger = within(toolbar).getByRole('button', { name: mode === 'desktop' ? '전체 필터 열기' : '모바일 전체 필터 열기' })
    fireEvent.click(trigger)
    const sh = screen.getByRole('checkbox', { name: 'SH' })
    sh.focus()
    fireEvent.click(sh)
    expect(within(trigger).getByText('2')).toBeVisible()
    expect(appliedFilters()).toEqual({ rentalTypes: ['NATIONAL_RENTAL'], agencyCodes: ['SH'] })
    expect(screen.getByRole('checkbox', { name: 'SH' })).toBe(sh)
    expect(sh).toHaveFocus()
    expect(screen.queryByRole('button', { name: mode === 'desktop' ? '전체 필터 적용' : '전체 적용' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '전체 필터 초기화' }))
    expect(trigger).toHaveTextContent(/^전체$/)
    expect(appliedFilters()).toEqual({})
    expect(screen.getByRole(mode === 'desktop' ? 'region' : 'dialog', { name: '전체 필터' })).toBeVisible()
  })

  it('개별 필터를 바로 적용하거나 해제하면 전체 배지에도 반영한다', () => {
    render(<StatefulToolbar initialFilters={{ maxDeposit: 0 }} />)
    const trigger = screen.getByRole('button', { name: '전체 필터 열기' })
    expect(within(trigger).getByText('1')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 열기' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '행복주택' }))
    expect(within(trigger).getByText('2')).toBeVisible()
    fireEvent.click(screen.getByRole('checkbox', { name: '행복주택' }))
    expect(within(trigger).getByText('1')).toBeVisible()
  })

  it.each(['desktop', 'mobile'])('%s 전체 패널에서 모든 조건을 즉시 초기화한다', (mode) => {
    render(<StatefulToolbar initialFilters={BASE_FILTERS} />)
    fireEvent.click(screen.getByRole('button', { name: mode === 'desktop' ? '전체 필터 열기' : '모바일 전체 필터 열기' }))
    expect(screen.getByRole('radio', { name: '서울' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '국민임대' })).toBeChecked()
    expect(screen.getByRole('slider', { name: '임대보증금 최솟값' })).toHaveValue('100000000')
    expect(screen.getByLabelText('최소 준공년도')).toHaveValue('2019')
    fireEvent.click(screen.getByRole('button', { name: '전체 필터 초기화' }))
    expect(screen.getByRole('button', { name: '전체 필터 초기화' })).toHaveFocus()
    expect(appliedFilters()).toEqual({})
    expect(screen.getByRole('radio', { name: '전체' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '국민임대' })).not.toBeChecked()
    expect(screen.getByRole('slider', { name: '임대보증금 최솟값' })).toHaveValue('0')
    expect(screen.getByLabelText('최소 준공년도')).toHaveValue('')
    expect(appliedFilters()).toEqual({})
  })

  it.each(['desktop', 'mobile'])('%s 전체 패널은 조건별로 즉시 적용하고 잘못된 연도는 반영하지 않는다', (mode) => {
    render(<StatefulToolbar initialFilters={{ builtYearFrom: 2019, builtYearTo: 2024, recruitmentTypes: ['NEW'] }} />)
    fireEvent.click(screen.getByRole('button', { name: mode === 'desktop' ? '전체 필터 열기' : '모바일 전체 필터 열기' }))
    fireEvent.click(screen.getByRole('radio', { name: '경기' }))
    expect(appliedFilters().regionCode).toBe('41')
    fireEvent.click(screen.getByRole('checkbox', { name: '행복주택' }))
    expect(appliedFilters().rentalTypes).toEqual(['HAPPY_HOUSING'])
    fireEvent.click(screen.getByRole('checkbox', { name: 'SH' }))
    expect(appliedFilters().agencyCodes).toEqual(['SH'])
    fireEvent.click(screen.getByRole('button', { name: '1억 이하' }))
    expect(appliedFilters().maxDeposit).toBe(100_000_000)
    fireEvent.change(screen.getByLabelText('최소 준공년도'), { target: { value: '2025' } })
    expect(screen.getByRole('alert')).toHaveTextContent('최소 준공년도')
    expect(appliedFilters()).toMatchObject({ builtYearFrom: 2019, builtYearTo: 2024 })
    fireEvent.click(screen.getByRole('checkbox', { name: 'LH' }))
    expect(appliedFilters().recruitmentTypes).toEqual(['NEW'])
    expect(screen.getByRole('alert')).toHaveTextContent('최소 준공년도')
    fireEvent.change(screen.getByLabelText('최대 준공년도'), { target: { value: '2026' } })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(appliedFilters()).toEqual({ regionCode: '41', rentalTypes: ['HAPPY_HOUSING'], agencyCodes: ['LH', 'SH'], recruitmentTypes: ['NEW'], maxDeposit: 100_000_000, builtYearFrom: 2025, builtYearTo: 2026 })
    fireEvent.keyDown(document, { key: 'Escape' })
    fireEvent.click(screen.getByRole('button', { name: mode === 'desktop' ? '전체 필터 열기' : '모바일 전체 필터 열기' }))
    expect(screen.getByRole('checkbox', { name: '행복주택' })).toBeChecked()
    expect(screen.getByLabelText('최소 준공년도')).toHaveValue('2025')
  })

  it.each(['desktop', 'mobile'])('%s 전체 패널에서 초기화 후 닫고 다시 열어도 조건은 비어 있다', (mode) => {
    render(<StatefulToolbar initialFilters={BASE_FILTERS} />)
    const opener = mode === 'desktop' ? '전체 필터 열기' : '모바일 전체 필터 열기'
    fireEvent.click(screen.getByRole('button', { name: opener }))
    fireEvent.click(screen.getByRole('button', { name: '전체 필터 초기화' }))
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(appliedFilters()).toEqual({})
    fireEvent.click(screen.getByRole('button', { name: opener }))
    expect(screen.getByRole('checkbox', { name: '국민임대' })).not.toBeChecked()
    expect(screen.getByLabelText('최소 준공년도')).toHaveValue('')
  })

  it.each([['desktop', '전체'], ['mobile', '전체'], ['desktop', '지역'], ['mobile', '지역']])('%s %s 지역 변경은 이전 하위 지역을 제거한 값으로 즉시 적용한다', async (mode, panel) => {
    const regionRepository = { search: vi.fn().mockResolvedValue([
      { regionCode: '11680', provinceName: '서울특별시', districtName: '강남구', displayName: '서울특별시 강남구' },
      { regionCode: '11650', provinceName: '서울특별시', districtName: '서초구', displayName: '서울특별시 서초구' },
      { regionCode: '1168010100', provinceName: '서울특별시', districtName: '강남구', displayName: '서울특별시 강남구 역삼동' },
      ...GYEONGGI_REGIONS,
    ]) }
    render(<StatefulToolbar initialFilters={{ regionCode: '1168010100' }} regionRepository={regionRepository} />)
    fireEvent.click(screen.getByRole('button', { name: `${mode === 'mobile' ? '모바일 ' : ''}${panel} 필터 열기` }))
    fireEvent.click(screen.getByRole('button', { name: '시·군·구 선택' }))
    fireEvent.click(await screen.findByRole('radio', { name: '서초구' }))
    expect(appliedFilters()).toEqual({ regionCode: '11650' })
    expect(screen.getByRole('radio', { name: '전체' })).toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: '시·도 선택' }))
    fireEvent.click(screen.getByRole('radio', { name: '경기' }))
    expect(appliedFilters()).toEqual({ regionCode: '41' })
    fireEvent.click(await screen.findByRole('radio', { name: '수원시' }))
    expect(appliedFilters()).toEqual({ regionCode: '41110' })
    fireEvent.click(screen.getByRole('button', { name: '시·도 선택' }))
    fireEvent.click(screen.getByRole('radio', { name: '전체' }))
    expect(appliedFilters()).toEqual({})
  })

  it.each([
    ['desktop', 'pointer'], ['desktop', 'keyboard'], ['mobile', 'pointer'], ['mobile', 'keyboard'],
  ])('%s 전체 슬라이더는 %s 조작이 끝날 때 한 번 적용하고 손잡이를 유지한다', (mode, interaction) => {
    const onApply = vi.fn()
    render(<StatefulToolbar initialFilters={{ agencyCodes: ['LH'], maxMonthlyRent: 700_000 }} onApply={onApply} />)
    fireEvent.click(screen.getByRole('button', { name: mode === 'desktop' ? '전체 필터 열기' : '모바일 전체 필터 열기' }))
    const slider = screen.getByRole('slider', { name: '임대보증금 최솟값' })
    slider.focus()
    if (interaction === 'pointer') fireEvent.pointerDown(slider)
    else fireEvent.keyDown(slider, { key: 'ArrowRight' })
    fireEvent.change(slider, { target: { value: '100000000' } })
    fireEvent.change(slider, { target: { value: '200000000' } })
    expect(onApply).not.toHaveBeenCalled()
    if (interaction === 'pointer') fireEvent.pointerUp(slider)
    else fireEvent.keyUp(slider, { key: 'ArrowRight' })
    expect(onApply).toHaveBeenCalledExactlyOnceWith({ agencyCodes: ['LH'], maxMonthlyRent: 700_000, minDeposit: 200_000_000 })
    expect(screen.getByRole('slider', { name: '임대보증금 최솟값' })).toBe(slider)
    expect(slider).toHaveFocus()
  })

  it.each(['desktop', 'mobile'])('%s 전체 편집 중 외부 조건 변경은 패널을 닫고 최신 값을 다시 연다', (mode) => {
    const onApply = vi.fn()
    const { rerender } = renderToolbar({ filters: { rentalTypes: ['HAPPY_HOUSING'] }, onApply })
    const opener = mode === 'desktop' ? '전체 필터 열기' : '모바일 전체 필터 열기'
    fireEvent.click(screen.getByRole('button', { name: opener }))
    rerender(<ComplexFilterToolbar filters={{ agencyCodes: ['SH'] }} onApply={onApply} />)
    expect(screen.queryByRole(mode === 'desktop' ? 'region' : 'dialog', { name: '전체 필터' })).not.toBeInTheDocument()
    expect(onApply).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: opener }))
    expect(screen.getByRole('checkbox', { name: '행복주택' })).not.toBeChecked()
    expect(screen.getByRole('checkbox', { name: '국민임대' })).not.toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'SH' })).toBeChecked()
  })

  it.each(['desktop', 'mobile'])('%s 전체 패널에서 수정하지 않은 범위 원값을 유지한다', (mode) => {
    const filters = { maxDeposit: 0, minMonthlyRent: 610_000, maxMonthlyRent: 700_000, maxExclusiveArea: 0, builtYearTo: 1970 }
    const onApply = vi.fn()
    renderToolbar({ filters, onApply })
    fireEvent.click(screen.getByRole('button', { name: mode === 'desktop' ? '전체 필터 열기' : '모바일 전체 필터 열기' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '행복주택' }))
    expect(onApply).toHaveBeenCalledExactlyOnceWith({ ...filters, rentalTypes: ['HAPPY_HOUSING'] })
  })

  it('다른 조건을 선택해도 0과 한쪽만 있는 금액·면적 조건을 보존한다', () => {
    const initialFilters = {
      maxDeposit: 0,
      minMonthlyRent: 0,
      maxExclusiveArea: 0,
      builtYearFrom: 1970,
    }
    render(<StatefulToolbar initialFilters={initialFilters} />)
    expect(screen.getByRole('button', { name: '가격 필터 열기' }))
      .toHaveAttribute('data-active', 'true')
    expect(screen.getByRole('button', { name: '전용면적 필터 열기' }))
      .toHaveAttribute('data-active', 'true')
    fireEvent.click(within(screen.getByRole('toolbar', { name: '단지 검색 필터' })).getByRole('switch', { name: '모집중' }))
    expect(appliedFilters()).toEqual({ ...initialFilters, applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'] })
    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))
    fireEvent.click(screen.getByRole('button', { name: '가격 필터 패널 닫기' }))
    expect(appliedFilters()).toEqual({ ...initialFilters, applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'] })
  })

  it('모바일에서 수정 없이 닫아도 0과 제한 없는 범위의 다른 끝점을 그대로 유지한다', () => {
    const filters = { maxDeposit: 0, minMonthlyRent: 0, maxExclusiveArea: 0, builtYearTo: 1970 }
    const onApply = vi.fn()
    render(<StatefulToolbar initialFilters={filters} onApply={onApply} />)
    fireEvent.click(screen.getByRole('button', { name: '모바일 가격 필터 열기' }))
    fireEvent.click(screen.getByRole('button', { name: '단지 필터 닫기' }))
    expect(onApply).not.toHaveBeenCalled()
    expect(appliedFilters()).toEqual(filters)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('주요 필터만 노출하고 나머지 조건은 전체에서 즉시 적용한다', () => {
    render(<StatefulToolbar initialFilters={{ recruitmentTypes: ['NEW'] }} />)
    const toolbar = screen.getByRole('toolbar', { name: '단지 검색 필터' })
    expect(within(toolbar).getAllByRole('button').map(button => button.getAttribute('aria-label'))).toEqual([
      '지역 필터 열기', '임대유형 필터 열기', '가격 필터 열기', '전용면적 필터 열기', '전체 필터 열기',
    ])
    expect(within(toolbar).getByRole('switch', { name: '모집중' })).toBeVisible()
    fireEvent.click(within(toolbar).getByRole('button', { name: '전체 필터 열기' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'LH' }))
    expect(screen.queryByRole('group', { name: '모집유형' })).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('최소 준공년도'), { target: { value: '2020' } })
    expect(appliedFilters()).toMatchObject({ agencyCodes: ['LH'], recruitmentTypes: ['NEW'], builtYearFrom: 2020 })
    expect(screen.getByRole('button', { name: '전체 필터 닫기' })).toHaveAccessibleDescription('적용 중인 필터 3개')
  })

  it('기본 필터 선택과 해제를 즉시 반영하고 팝오버와 포커스를 유지한다', () => {
    render(<StatefulToolbar initialFilters={{ agencyCodes: ['SH'] }} />)
    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 열기' }))
    const happy = screen.getByRole('checkbox', { name: '행복주택' })
    happy.focus()
    fireEvent.click(happy)

    expect(appliedFilters()).toEqual({ agencyCodes: ['SH'], rentalTypes: ['HAPPY_HOUSING'] })
    expect(screen.getByRole('region', { name: '임대유형 필터' })).toBeVisible()
    expect(happy).toHaveFocus()
    expect(screen.queryByRole('button', { name: '임대유형 필터 적용' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: '국민임대' }))
    expect(appliedFilters()).toEqual({
      agencyCodes: ['SH'], rentalTypes: ['HAPPY_HOUSING', 'NATIONAL_RENTAL'],
    })
    fireEvent.click(happy)
    fireEvent.click(screen.getByRole('checkbox', { name: '국민임대' }))
    expect(appliedFilters()).toEqual({ agencyCodes: ['SH'] })
    expect(screen.getByRole('button', { name: '임대유형 필터 닫기' }))
      .toHaveTextContent(/^임대유형$/)
    expect(screen.getByRole('button', { name: '임대유형 필터 닫기' }))
      .toHaveAttribute('data-active', 'false')
    expect(screen.getByRole('button', { name: '전체 필터 열기' }))
      .toHaveAccessibleDescription('적용 중인 필터 1개')
  })

  it('슬라이더를 연속 조작해도 손잡이를 유지하고 변경하지 않은 원값을 보존한다', () => {
    render(<StatefulToolbar initialFilters={{
      minDeposit: 140_000_000, maxDeposit: 155_000_000,
      minMonthlyRent: 610_000, maxMonthlyRent: 700_000,
    }} />)
    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))
    const minimum = screen.getByRole('slider', { name: '임대보증금 최솟값' })
    minimum.focus()
    fireEvent.change(minimum, { target: { value: '160000000' } })
    expect(appliedFilters()).toEqual({
      minDeposit: 160_000_000, maxDeposit: 160_000_000,
      minMonthlyRent: 610_000, maxMonthlyRent: 700_000,
    })
    expect(minimum).toHaveFocus()
    expect(screen.getByRole('slider', { name: '임대보증금 최솟값' })).toBe(minimum)
    fireEvent.change(minimum, { target: { value: '120000000' } })
    expect(appliedFilters()).toEqual({
      minDeposit: 120_000_000, maxDeposit: 160_000_000,
      minMonthlyRent: 610_000, maxMonthlyRent: 700_000,
    })
  })

  it('준공년도 역전은 조회하지 않고 범위를 바로잡으면 즉시 반영한다', () => {
    render(<StatefulToolbar initialFilters={{ builtYearFrom: 2019, builtYearTo: 2024 }} />)
    fireEvent.click(screen.getByRole('button', { name: '전체 필터 열기' }))
    fireEvent.change(screen.getByLabelText('최소 준공년도'), { target: { value: '2025' } })
    expect(appliedFilters()).toEqual({ builtYearFrom: 2019, builtYearTo: 2024 })
    expect(screen.getByRole('alert')).toHaveTextContent('최소 준공년도')
    fireEvent.change(screen.getByLabelText('최대 준공년도'), { target: { value: '2026' } })
    expect(appliedFilters()).toEqual({ builtYearFrom: 2025, builtYearTo: 2026 })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it.each(['pointer', 'keyboard'])('%s 슬라이더 연속 조작은 완료한 범위만 한 번 적용한다', (interaction) => {
    const onApply = vi.fn()
    renderToolbar({ onApply })
    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))
    const minimum = screen.getByRole('slider', { name: '임대보증금 최솟값' })
    if (interaction === 'pointer') fireEvent.pointerDown(minimum)
    else fireEvent.keyDown(minimum, { key: 'ArrowRight' })
    fireEvent.change(minimum, { target: { value: '100000000' } })
    fireEvent.change(minimum, { target: { value: '200000000' } })
    expect(onApply).not.toHaveBeenCalled()
    expect(screen.getByRole('status', { name: '임대보증금 선택 범위' })).toHaveTextContent('2억 이상')

    if (interaction === 'pointer') fireEvent.pointerUp(minimum)
    else fireEvent.keyUp(minimum, { key: 'ArrowRight' })
    expect(onApply).toHaveBeenCalledExactlyOnceWith({ minDeposit: 200_000_000 })
    fireEvent.blur(minimum)
    expect(onApply).toHaveBeenCalledOnce()
  })

  it.each([
    ['desktop', '가격', '임대보증금 최솟값', '10000000', '20000000'],
    ['mobile', '가격', '임대보증금 최솟값', '10000000', '20000000'],
    ['desktop', '전용면적', '전용면적 최솟값', '33', '66'],
    ['mobile', '전용면적', '전용면적 최솟값', '33', '66'],
  ])('%s %s 연속 변경의 이전 적용이 늦게 도착해도 필터를 닫지 않는다', (mode, topic, label, first, second) => {
    const onApply = vi.fn()
    const { rerender } = renderToolbar({ onApply })
    fireEvent.click(screen.getByRole('button', { name: `${mode === 'mobile' ? '모바일 ' : ''}${topic} 필터 열기` }))
    const slider = screen.getByRole('slider', { name: label })
    fireEvent.change(slider, { target: { value: first } })
    fireEvent.change(slider, { target: { value: second } })

    rerender(<ComplexFilterToolbar filters={onApply.mock.calls[0][0]} onApply={onApply} />)
    expect(screen.getByRole('slider', { name: label })).toBe(slider)
    expect(slider).toHaveValue(second)
    rerender(<ComplexFilterToolbar filters={onApply.mock.calls[1][0]} onApply={onApply} />)
    expect(screen.getByRole('slider', { name: label })).toBe(slider)
    // Once acknowledged, a history visit to an earlier value is external again.
    rerender(<ComplexFilterToolbar filters={onApply.mock.calls[0][0]} onApply={onApply} />)
    expect(screen.queryByRole('slider', { name: label })).not.toBeInTheDocument()
  })

  it.each(['desktop', 'mobile'])('%s 전체 필터의 적용 대기 중 다른 조건을 바꿔도 앞선 선택을 보존한다', (mode) => {
    const onApply = vi.fn()
    const { rerender } = renderToolbar({ onApply })
    fireEvent.click(screen.getByRole('button', { name: `${mode === 'mobile' ? '모바일 ' : ''}전체 필터 열기` }))
    fireEvent.change(screen.getByRole('slider', { name: '임대보증금 최솟값' }), { target: { value: '10000000' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'LH' }))
    expect(onApply).toHaveBeenLastCalledWith({ minDeposit: 10000000, agencyCodes: ['LH'] })
    // A transition may skip the intermediate commit altogether.
    rerender(<ComplexFilterToolbar filters={onApply.mock.calls[1][0]} onApply={onApply} />)
    expect(screen.getByRole('checkbox', { name: 'LH' })).toBeChecked()
    expect(screen.getByRole('slider', { name: '임대보증금 최솟값' })).toHaveValue('10000000')
  })

  it('외부 탐색으로 조건이 바뀌면 이전 기본 필터 입력을 닫고 새 조건을 표시한다', () => {
    const onApply = vi.fn()
    const { rerender } = renderToolbar({ filters: { minDeposit: 100_000_000 }, onApply })
    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))

    rerender(<ComplexFilterToolbar filters={{ maxDeposit: 200_000_000 }} onApply={onApply} />)

    expect(screen.queryByRole('region', { name: '가격 필터' })).not.toBeInTheDocument()
    expect(onApply).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))
    expect(screen.getByRole('slider', { name: '임대보증금 최솟값' })).toHaveValue('0')
    expect(screen.getByRole('slider', { name: '임대보증금 최댓값' })).toHaveValue('200000000')
  })

  it('한 번에 하나의 개별 팝오버만 연다', () => {
    renderToolbar()
    fireEvent.click(screen.getByRole('button', { name: '전체 필터 열기' }))
    expect(screen.getByRole('region', { name: '전체 필터' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 열기' }))
    expect(screen.queryByRole('region', { name: '전체 필터' })).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: '임대유형 필터' })).toBeVisible()
    expect(screen.getByRole('button', { name: '전체 필터 열기' })).toHaveAttribute('aria-expanded', 'false')
  })

  it('전체 필터는 데스크톱에서는 마지막, 모바일에서는 맨 왼쪽에 표시한다', () => {
    renderToolbar()
    const desktop = screen.getByRole('toolbar', { name: '단지 검색 필터' })
    expect(within(desktop).getAllByRole('button').at(-1)).toHaveAccessibleName('전체 필터 열기')
    const mobile = screen.getByRole('toolbar', { name: '모바일 단지 검색 필터' })
    expect(within(mobile).getAllByRole('button').map(button => button.getAttribute('aria-label'))).toEqual([
      '모바일 전체 필터 열기', '모바일 지역 필터 열기', '모바일 임대유형 필터 열기', '모바일 가격 필터 열기', '모바일 전용면적 필터 열기',
    ])
    for (const toolbar of [desktop, mobile]) {
      expect(within(toolbar).queryByRole('button', { name: /초기화|전체 해제/ })).not.toBeInTheDocument()
    }
  })

  it('기본 필터를 닫고 다시 열어도 즉시 적용된 선택을 유지한다', () => {
    render(<StatefulToolbar initialFilters={{}} />)
    const trigger = screen.getByRole('button', { name: '임대유형 필터 열기' })
    fireEvent.click(trigger)
    fireEvent.click(screen.getByRole('checkbox', { name: '행복주택' }))
    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 패널 닫기' }))

    expect(screen.queryByRole('region', { name: '임대유형 필터' })).not.toBeInTheDocument()
    expect(appliedFilters()).toEqual({ rentalTypes: ['HAPPY_HOUSING'] })
    expect(trigger).toHaveFocus()
    fireEvent.click(trigger)
    expect(screen.getByRole('checkbox', { name: '행복주택' })).toBeChecked()
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
    expect(popover.style.getPropertyValue('--popover-width')).toBe('320px')
    expect(popover.style.getPropertyValue('--popover-left')).toBe('200px')
    expect(popover.style.getPropertyValue('--popover-top')).toBe('94px')
  })

  it('좌우 넘기기 버튼 없이 전체에서 모든 조건에 접근한다', () => {
    renderToolbar()
    expect(screen.queryByRole('button', { name: '뒤쪽 필터 더 보기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '앞쪽 필터 보기' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '전체 필터 열기' }))
    expect(screen.getByLabelText('최소 준공년도')).toBeVisible()
    expect(screen.getByRole('group', { name: '공급기관' })).toBeVisible()
    expect(screen.queryByRole('group', { name: '모집유형' })).not.toBeInTheDocument()
  })

  it('화면 크기가 달라지면 열린 팝오버의 연결 위치를 갱신한다', () => {
    renderToolbar()
    const toolbar = screen.getByRole('toolbar', { name: '단지 검색 필터' })
    const root = toolbar.closest('section')
    const price = screen.getByRole('button', { name: '가격 필터 열기' })
    const scroller = price.parentElement?.parentElement
    if (root === null || scroller === null || scroller === undefined) {
      throw new Error('필터 배치 요소를 찾을 수 없습니다.')
    }
    vi.spyOn(root, 'getBoundingClientRect').mockReturnValue(
      domRect({ left: 100, width: 800 }),
    )
    let triggerLeft = 180
    vi.spyOn(price, 'getBoundingClientRect').mockImplementation(() =>
      domRect({ left: triggerLeft, width: 80 }),
    )

    fireEvent.click(price)
    const popover = screen.getByRole('region', { name: '가격 필터' })
    expect(popover.style.getPropertyValue('--popover-anchor-x')).toBe('120px')
    expect(popover.style.getPropertyValue('--popover-width')).toBe('320px')
    expect(popover.style.getPropertyValue('--popover-left')).toBe('80px')

    triggerLeft = 260
    fireEvent(window, new Event('resize'))

    expect(popover.style.getPropertyValue('--popover-anchor-x')).toBe('200px')
    expect(popover.style.getPropertyValue('--popover-left')).toBe('160px')
  })

  it('선택 중 필터창은 버튼이 이동해도 제자리에 두고 다시 열 때 맞춘다', () => {
    render(<StatefulToolbar initialFilters={{}} />)
    const trigger = screen.getByRole('button', { name: '임대유형 필터 열기' })
    const root = trigger.closest('section')
    if (root === null) throw new Error('필터 root를 찾을 수 없습니다.')
    vi.spyOn(root, 'getBoundingClientRect').mockReturnValue(domRect({ left: 100, width: 800 }))
    let triggerLeft = 300
    vi.spyOn(trigger, 'getBoundingClientRect').mockImplementation(() => domRect({ left: triggerLeft, width: 120 }))

    fireEvent.click(trigger)
    const popover = screen.getByRole('region', { name: '임대유형 필터' })
    triggerLeft = 240
    fireEvent.click(screen.getByRole('checkbox', { name: '50년 공공임대' }))
    expect(popover.style.getPropertyValue('--popover-left')).toBe('200px')
    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 패널 닫기' }))
    fireEvent.click(trigger)
    const reopened = screen.getByRole('region', { name: '임대유형 필터' })
    expect(reopened.style.getPropertyValue('--popover-left')).toBe('140px')
    triggerLeft = 300
    fireEvent.click(screen.getByRole('checkbox', { name: '50년 공공임대' }))
    expect(reopened.style.getPropertyValue('--popover-left')).toBe('140px')
    expect(appliedFilters()).toEqual({})
  })

  it('선택 중 필터창은 지역 이름 조회가 완료되어도 위치를 유지한다', async () => {
    const response = Promise.resolve(GYEONGGI_REGIONS)
    renderToolbar({ filters: { regionCode: '41110' }, regionRepository: { search: vi.fn().mockReturnValue(response) } })
    const trigger = screen.getByRole('button', { name: '지역 필터 열기' })
    const root = trigger.closest('section')
    if (root === null) throw new Error('필터 root를 찾을 수 없습니다.')
    vi.spyOn(root, 'getBoundingClientRect').mockReturnValue(domRect({ left: 100, width: 800 }))
    let triggerLeft = 300
    vi.spyOn(trigger, 'getBoundingClientRect').mockImplementation(() => domRect({ left: triggerLeft, width: 118 }))

    fireEvent.click(trigger)
    const popover = screen.getByRole('region', { name: '지역 필터' })
    triggerLeft = 240
    await act(async () => { await response })
    expect(trigger).toHaveTextContent('경기 수원시')
    expect(popover.style.getPropertyValue('--popover-left')).toBe('200px')
  })

  it('좁은 화면에서도 지역 팝오버를 필터 영역 경계 안에 둔다', () => {
    renderToolbar()
    const toolbar = screen.getByRole('toolbar', { name: '단지 검색 필터' })
    const root = toolbar.closest('section')
    const detail = screen.getByRole('button', { name: '지역 필터 열기' })
    if (root === null) throw new Error('필터 root를 찾을 수 없습니다.')
    vi.spyOn(root, 'getBoundingClientRect').mockReturnValue(
      domRect({ left: 100, width: 360 }),
    )
    vi.spyOn(detail, 'getBoundingClientRect').mockReturnValue(
      domRect({ left: 370, width: 60 }),
    )

    fireEvent.click(detail)

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

    expect(onApply).toHaveBeenCalledOnce()
    expect(onApply).toHaveBeenCalledWith(expected)
    expect(screen.getByRole('region', { name: `${topic} 필터` })).toBeVisible()
    expect(screen.queryByRole('button', { name: `${topic} 필터 적용` })).not.toBeInTheDocument()
  })

  it.each(['desktop', 'mobile'].flatMap((mode) => [
    { mode, topic: '지역', key: 'regionCode', value: '41', option: null },
    { mode, topic: '공급기관', key: 'agencyCodes', value: ['LH', 'SH'], option: 'SH' },
  ]))('$mode $topic 선택은 즉시 적용하고 다른 조건과 패널을 유지한다', async ({ mode, topic, key, value, option }) => {
    const onApply = vi.fn()
    render(<StatefulToolbar initialFilters={BASE_FILTERS} onApply={onApply} />)
    fireEvent.click(screen.getByRole('button', { name: `${mode === 'mobile' ? '모바일 ' : ''}${topic === '지역' ? topic : '전체'} 필터 열기` }))
    const field = option ? screen.getByRole('checkbox', { name: option }) : screen.getByRole('radio', { name: '경기' })
    field.focus()
    fireEvent.click(field)
    expect(onApply).toHaveBeenCalledExactlyOnceWith({ ...BASE_FILTERS, [key]: value })
    if (option) expect(field).toHaveFocus()
    else await waitFor(() => expect(screen.getByRole('button', { name: '시·군·구 선택' })).toHaveFocus())
    expect(screen.getByRole(mode === 'desktop' ? 'region' : 'dialog', { name: `${topic === '지역' ? topic : '전체'} 필터` })).toBeVisible()
    expect(screen.queryByRole('button', { name: mode === 'desktop' ? `${topic === '지역' ? topic : '전체'} 필터 적용` : `${topic} 적용` })).not.toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'Escape' })
    fireEvent.click(screen.getByRole('button', { name: mode === 'desktop' ? '전체 필터 열기' : '모바일 전체 필터 열기' }))
    if (option) expect(screen.getByRole('checkbox', { name: option })).toBeChecked()
    else expect(screen.getByRole('radio', { name: '경기' })).toBeChecked()
  })

  it.each(['desktop', 'mobile'])('%s 전체 필터는 모집유형을 숨기고 다른 조건을 바꿀 때 기존 모집유형을 보존한다', (mode) => {
    render(<StatefulToolbar initialFilters={{ recruitmentTypes: ['NEW'] }} />)
    fireEvent.click(screen.getByRole('button', { name: `${mode === 'mobile' ? '모바일 ' : ''}전체 필터 열기` }))
    expect(screen.queryByRole('group', { name: '모집유형' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: 'LH' }))
    expect(appliedFilters()).toEqual({ recruitmentTypes: ['NEW'], agencyCodes: ['LH'] })
    const reset = screen.getByRole('button', { name: '전체 필터 초기화' })
    expect(reset).toHaveTextContent(/^$/)
    fireEvent.click(reset)
    expect(appliedFilters()).toEqual({})
    expect(screen.getByRole('button', { name: '전체 필터 초기화' })).toHaveFocus()
  })

  const resetCases = [
    { topic: '지역', keys: ['regionCode'], control: '현재 지역', empty: '전국' },
    { topic: '임대유형', keys: ['rentalTypes'], control: '국민임대', empty: false },
    { topic: '가격', keys: ['minDeposit', 'maxDeposit', 'minMonthlyRent', 'maxMonthlyRent'], control: '임대보증금 최솟값', empty: '0' },
    { topic: '전용면적', keys: ['minExclusiveArea', 'maxExclusiveArea'], control: '전용면적 최솟값', empty: '0' },
  ] as const

  it.each(['desktop', 'mobile'].flatMap(mode => resetCases.map(testCase => ({ mode, ...testCase }))))(
    '$mode $topic 초기화는 해당 조건만 즉시 비우고 포커스와 열린 패널을 유지한다',
    ({ mode, topic, keys, control, empty }) => {
      render(<StatefulToolbar initialFilters={BASE_FILTERS} />)
      fireEvent.click(screen.getByRole('button', { name: `${mode === 'mobile' ? '모바일 ' : ''}${topic} 필터 열기` }))
      const reset = screen.getByRole('button', { name: `${topic} 필터 초기화` })
      expect(reset).toHaveTextContent(/^$/)
      fireEvent.click(reset)
      const expected = { ...BASE_FILTERS }
      keys.forEach(key => delete expected[key])
      expect(appliedFilters()).toEqual(expected)
      expect(screen.getByRole('button', { name: `${topic} 필터 초기화` })).toHaveFocus()
      expect(screen.getByRole(mode === 'mobile' ? 'dialog' : 'region', { name: `${topic} 필터` })).toBeVisible()
      if (empty === false) expect(screen.getByRole('checkbox', { name: control })).not.toBeChecked()
      else if (topic === '지역') expect(screen.getByRole('status', { name: control })).toHaveTextContent(empty)
      else expect(screen.getByRole('slider', { name: control })).toHaveValue(empty)
      fireEvent.keyDown(document, { key: 'Escape' })
      expect(appliedFilters()).toEqual(expected)
    },
  )

  it.each(['desktop', 'mobile'])('%s 개별 초기화는 상위 상태가 늦게 갱신되어도 입력을 비우고 직전의 다른 조건을 보존한다', (mode) => {
    const onApply = vi.fn()
    renderToolbar({ filters: BASE_FILTERS, onApply })
    const prefix = mode === 'mobile' ? '모바일 ' : ''
    fireEvent.click(screen.getByRole('button', { name: `${prefix}임대유형 필터 열기` }))
    fireEvent.click(screen.getByRole('checkbox', { name: '행복주택' }))
    fireEvent.keyDown(document, { key: 'Escape' })
    fireEvent.click(screen.getByRole('button', { name: `${prefix}가격 필터 열기` }))
    fireEvent.click(screen.getByRole('button', { name: '가격 필터 초기화' }))
    expect(screen.getByRole('slider', { name: '임대보증금 최솟값' })).toHaveValue('0')
    const expected = { ...BASE_FILTERS, rentalTypes: ['HAPPY_HOUSING', 'NATIONAL_RENTAL'] }
    delete expected.minDeposit
    delete expected.maxDeposit
    delete expected.minMonthlyRent
    delete expected.maxMonthlyRent
    expect(onApply).toHaveBeenLastCalledWith(expected)
  })

  it('Escape와 바깥 클릭으로 닫아도 즉시 적용한 공급기관 선택은 유지한다', () => {
    render(<StatefulToolbar initialFilters={BASE_FILTERS} />)
    fireEvent.click(screen.getByRole('button', { name: '전체 필터 열기' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'SH' }))
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(appliedFilters().agencyCodes).toEqual(['LH', 'SH'])
    expect(screen.getByRole('button', { name: '전체 필터 열기' })).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: '전체 필터 열기' }))
    expect(screen.getByRole('checkbox', { name: 'SH' })).toBeChecked()
    fireEvent.click(screen.getByRole('checkbox', { name: 'SH' }))
    fireEvent.pointerDown(document.body)
    expect(appliedFilters().agencyCodes).toEqual(['LH'])
    expect(screen.queryByRole('region', { name: '전체 필터' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '전체 필터 열기' }))
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
    })).getByRole('button', { name: '40~60만원' }))

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
    )).toEqual(['10만원 이하', '10~20만원', '20~30만원', '30~40만원', '40~60만원'])
    expect(within(popover).getAllByRole('status', {
      name: /선택 범위/,
    }).map((output) => output.textContent)).toEqual(['전체', '전체'])
  })

  it('가격·면적을 열기만 하면 URL 원값을 바꾸지 않고 다른 필터 선택에도 보존한다', () => {
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
    fireEvent.click(screen.getByRole('button', {
      name: '전용면적 필터 열기',
    }))

    expect(onApply).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 열기' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '행복주택' }))
    expect(onApply).toHaveBeenCalledWith({ ...filters, rentalTypes: ['HAPPY_HOUSING'] })
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
    })).getByRole('button', { name: '60평 이상' }))

    expect(onApply).toHaveBeenCalledWith({
      regionCode: '11',
      rentalTypes: ['NATIONAL_RENTAL'],
      applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'],
      agencyCodes: ['LH'],
      recruitmentTypes: ['NEW'],
      minDeposit: 100_000_000,
      maxDeposit: 200_000_000,
      minMonthlyRent: 200_000,
      maxMonthlyRent: 300_000,
      minExclusiveArea: 198,
      builtYearFrom: 2019,
      builtYearTo: 2024,
    })
  })

  it.each([
    ['30평대', 99, 128.7],
    ['40평대', 132, 161.7],
    ['50평대', 165, 194.7],
  ] as const)('%s 빠른 선택은 해당 구간을 적용하고 다른 필터는 보존한다', (label, minimum, maximum) => {
    render(<StatefulToolbar initialFilters={{ agencyCodes: ['SH'] }} />)
    fireEvent.click(screen.getByRole('button', { name: '전용면적 필터 열기' }))
    fireEvent.click(screen.getByRole('button', { name: label }))
    expect(appliedFilters()).toEqual({ agencyCodes: ['SH'], minExclusiveArea: minimum, maxExclusiveArea: maximum })
  })

  it.each(['전용면적', '전체', '모바일 전용면적'])('%s 필터의 60평 이상 버튼과 마지막 눈금은 같은 범위를 적용한다', (topic) => {
    render(<StatefulToolbar initialFilters={{}} />)
    fireEvent.click(screen.getByRole('button', { name: `${topic} 필터 열기` }))
    const ticks = screen.getByRole('list', { name: '전용면적 주요 눈금' })
    expect(within(ticks).getAllByRole('listitem').map(item => item.textContent))
      .toEqual(['0', '20평', '40평', '60평 이상'])
    const minimum = screen.getByRole('slider', { name: '전용면적 최솟값' })
    const maximum = screen.getByRole('slider', { name: '전용면적 최댓값' })
    expect(maximum).toHaveAttribute('max', '198')

    fireEvent.click(screen.getByRole('button', { name: '60평 이상' }))
    expect(appliedFilters()).toEqual({ minExclusiveArea: 198 })
    expect(screen.getByRole('status', { name: '전용면적 선택 범위' })).toHaveTextContent('60평 이상')
    expect(minimum).toHaveValue('198')
    expect(maximum).toHaveValue('198')

    fireEvent.change(minimum, { target: { value: '66' } })
    expect(appliedFilters()).toEqual({ minExclusiveArea: 66 })
    fireEvent.change(minimum, { target: { value: '198' } })
    expect(appliedFilters()).toEqual({ minExclusiveArea: 198 })
    expect(screen.getByRole('button', { name: '60평 이상' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('준공년도 역전 범위는 적용하지 않고 팝오버에 오류를 보인다', () => {
    const onApply = vi.fn()
    renderToolbar({ filters: BASE_FILTERS, onApply })

    fireEvent.click(screen.getByRole('button', {
      name: '전체 필터 열기',
    }))
    fireEvent.change(screen.getByRole('combobox', {
      name: '최소 준공년도',
    }), { target: { value: '2025' } })
    fireEvent.change(screen.getByRole('combobox', {
      name: '최대 준공년도',
    }), { target: { value: '2020' } })

    expect(onApply).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent(
      '최소 준공년도는 최대 준공년도보다 클 수 없어요.',
    )
    expect(screen.getByRole('region', { name: '전체 필터' }))
      .toBeInTheDocument()
  })

  it('준공년도 드롭다운에서 최소·최대 연도를 선택해 적용한다', () => {
    const onApply = vi.fn()
    renderToolbar({ onApply })

    fireEvent.click(screen.getByRole('button', {
      name: '전체 필터 열기',
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

    expect(onApply).toHaveBeenCalledWith({
      builtYearFrom: 1998,
      builtYearTo: 2021,
    })
  })

  it('선택 범위 밖의 기존 준공년도도 드롭다운에서 보존한다', () => {
    const onApply = vi.fn()
    const filters: ComplexSearchFilters = {
      builtYearFrom: 1979,
      builtYearTo: 9999,
    }
    renderToolbar({ filters, onApply })

    fireEvent.click(screen.getByRole('button', {
      name: '전체 필터 열기',
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


    expect(onApply).not.toHaveBeenCalled()
  })

  it('지역 repository로 시군구를 불러와 지역 키만 교체한다', async () => {
    const onApply = vi.fn()
    const regionRepository = {
      search: vi.fn().mockResolvedValue(GYEONGGI_REGIONS),
    }
    renderToolbar({ filters: BASE_FILTERS, onApply, regionRepository })

    fireEvent.click(screen.getByRole('button', { name: '지역 필터 열기' }))
    fireEvent.click(screen.getByRole('radio', { name: '경기' }))

    const district = screen.getByRole('group', { name: '시·군·구 선택' })
    await waitFor(() => {
      expect(regionRepository.search).toHaveBeenLastCalledWith(
        '경기도',
        expect.any(AbortSignal),
      )
      expect(within(district).getByRole('radio', { name: '수원시' }))
        .toBeInTheDocument()
    })
    fireEvent.click(within(district).getByRole('radio', { name: '수원시' }))

    expect(onApply).toHaveBeenCalledWith({
      ...BASE_FILTERS,
      regionCode: '41110',
    })
  })

  it('적용된 토픽 버튼에 요약과 active 상태를 보인다', () => {
    renderToolbar({ filters: BASE_FILTERS })

    const expectedSummaries = [
      ['임대유형', '국민임대'],
      ['가격', '1억~2억 · 월 20만원~30만원'],
      ['전용면적', '10~19평'],
    ] as const

    expectedSummaries.forEach(([topic, summary]) => {
      const button = screen.getByRole('button', {
        name: `${topic} 필터 열기`,
      })
      expect(button).toHaveAttribute('data-active', 'true')
      expect(button.textContent).toBe(summary)
      expect(button).toHaveAccessibleDescription(`적용됨: ${summary}`)
    })

    for (const topic of ['지역']) {
      const button = screen.getByRole('button', { name: `${topic} 필터 열기` })
      expect(button).toHaveAttribute('data-active', 'true')
      expect(button).toHaveAccessibleDescription(`적용됨: ${button.textContent}`)
    }
  })

  it('적용값이 없는 토픽은 분류명을 그대로 보인다', () => {
    renderToolbar()

    expect(screen.getByRole('button', { name: '가격 필터 열기' }).textContent)
      .toBe('가격')
    expect(screen.getByRole('button', { name: '지역 필터 열기' }).textContent)
      .toBe('지역')
  })

  it.each(['desktop', 'mobile'])('%s 복수 임대유형 요약은 첫 유형과 외 N을 함께 표시한다', (mode) => {
    renderToolbar({ filters: { rentalTypes: ['PUBLIC_RENTAL_50Y', 'NATIONAL_RENTAL', 'HAPPY_HOUSING'] } })
    const toolbar = screen.getByRole('toolbar', { name: mode === 'desktop' ? '단지 검색 필터' : '모바일 단지 검색 필터' })
    const trigger = within(toolbar).getByRole('button', { name: mode === 'desktop' ? '임대유형 필터 열기' : '모바일 임대유형 필터 열기' })

    expect(trigger).toHaveTextContent(/^50년 공공임대 외 2$/)
    expect(trigger).toHaveAttribute('title', '50년 공공임대 외 2')
  })

  it('다섯 자리 지역은 실제 시군구 이름을 active 요약으로 보인다', async () => {
    const regionRepository = {
      search: vi.fn().mockResolvedValue(GYEONGGI_REGIONS),
    }
    renderToolbar({ filters: { regionCode: '41110' }, regionRepository })

    expect(screen.getByRole('button', { name: '지역 필터 열기' })).toHaveTextContent('경기 수원시')

    const mobileToolbar = screen.getByRole('toolbar', {
      name: '모바일 단지 검색 필터',
    })
    await waitFor(() => {
      expect(within(mobileToolbar).getByRole('button', {
        name: '모바일 지역 필터 열기',
      })).toHaveTextContent('경기 수원시')
    })
  })

  it('지역명 조회가 실패해도 저장된 시군구 이름을 유지한다', async () => {
    const regionRepository = { search: vi.fn().mockRejectedValue(new Error('지역 조회 실패')) }
    renderToolbar({ filters: { regionCode: '41110' }, regionRepository })
    await act(async () => {})
    expect(screen.getByRole('button', { name: '지역 필터 열기' })).toHaveTextContent('경기 수원시')
    expect(screen.getByRole('button', { name: '모바일 지역 필터 열기' })).toHaveTextContent('경기 수원시')
  })

  it('알 수 없는 지역의 조회가 실패해도 코드 대신 선택 상태를 보인다', async () => {
    const regionRepository = { search: vi.fn().mockRejectedValue(new Error('지역 조회 실패')) }
    renderToolbar({ filters: { regionCode: '41102' }, regionRepository })
    const trigger = screen.getByRole('button', { name: '지역 필터 열기' })
    expect(trigger).toHaveTextContent('지역 선택됨')
    await act(async () => {})
    expect(trigger).toHaveTextContent('지역 선택됨')
    expect(trigger).toHaveAccessibleDescription('적용됨: 지역 선택됨')
    expect(trigger).not.toHaveTextContent('41102')
  })

  it('읍면동도 조회한 전체 지역명을 사용한다', async () => {
    const regionRepository = { search: vi.fn().mockResolvedValue([{
      regionCode: '1111010100', provinceName: '서울특별시', districtName: '종로구',
      displayName: '서울특별시 종로구 청운동',
    }]) }
    renderToolbar({ filters: { regionCode: '1111010100' }, regionRepository })
    expect(screen.getByRole('button', { name: '지역 필터 열기' })).toHaveTextContent('서울 종로구 청운동')
    await waitFor(() => expect(regionRepository.search).toHaveBeenCalledWith('서울특별시', expect.any(AbortSignal)))
    expect(screen.getByRole('button', { name: '모바일 지역 필터 열기' })).toHaveTextContent('서울 종로구 청운동')
  })

  it('모바일에서도 선택한 개별 조건만 열고 수정한다', () => {
    const onApply = vi.fn()
    renderToolbar({ filters: BASE_FILTERS, onApply })
    const toolbar = screen.getByRole('toolbar', { name: '모바일 단지 검색 필터' })
    expect(within(toolbar).getAllByRole('button')).toHaveLength(5)
    fireEvent.click(within(toolbar).getByRole('button', { name: '모바일 가격 필터 열기' }))
    const sheet = screen.getByRole('dialog', { name: '가격 필터' })
    expect(within(sheet).getByRole('button', { name: '단지 필터 닫기' })).toHaveFocus()
    expect(within(sheet).getByRole('slider', { name: '임대보증금 최솟값' })).toBeVisible()
    expect(within(sheet).queryByRole('slider', { name: '전용면적 최댓값' })).not.toBeInTheDocument()
    fireEvent.click(within(sheet).getByRole('button', { name: '1억 이하' }))
    expect(within(sheet).queryByRole('button', { name: '가격 적용' })).not.toBeInTheDocument()
    const { minDeposit, ...expected } = BASE_FILTERS
    void minDeposit
    expect(onApply).toHaveBeenCalledExactlyOnceWith({ ...expected, maxDeposit: 100_000_000 })
    expect(sheet).toBeVisible()
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
      name: '모바일 지역 필터 열기',
    }))
    expect(screen.getByRole('dialog', { name: '지역 필터' }))
      .toBeInTheDocument()

    rerender(
      <ComplexFilterToolbar
        filters={{ regionCode: '41' }}
        onApply={onApply}
        regionRepository={regionRepository}
      />,
    )

    expect(screen.queryByRole('dialog', { name: '지역 필터' }))
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
      name: '모바일 가격 필터 열기',
    }))

    expect(scrollIntoView).not.toHaveBeenCalled()
    expect(document.documentElement.style.overflow).toBe('hidden')
    expect(document.body.style.overflow).toBe('hidden')

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(document.documentElement.style.overflow).toBe('')
    expect(document.body.style.overflow).toBe('')
    Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
  })

  it('터치 화면에서 회전하거나 키보드로 높이가 바뀌어도 열린 필터와 포커스를 유지한다', () => {
    const innerWidth = vi.spyOn(window, 'innerWidth', 'get').mockReturnValue(390)
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query === '(hover: none) and (pointer: coarse)' || window.innerWidth <= 767,
    } as MediaQueryList))
    renderToolbar()
    fireEvent.click(screen.getByRole('button', { name: '모바일 전체 필터 열기' }))
    const sheet = screen.getByRole('dialog', { name: '전체 필터' })
    const close = screen.getByRole('button', { name: '단지 필터 닫기' })
    expect(close).toHaveFocus()
    innerWidth.mockReturnValue(844)
    fireEvent(window, new Event('resize'))
    fireEvent(window, new Event('resize'))
    expect(screen.getByRole('dialog', { name: '전체 필터' })).toBe(sheet)
    expect(close).toHaveFocus()
    expect(document.body.style.overflow).toBe('hidden')
    innerWidth.mockRestore()
  })

  it('화면 크기가 같아도 터치에서 마우스 정책으로 바뀌면 숨겨진 필터와 스크롤 잠금을 정리한다', async () => {
    let touch = true
    let inputChanged: (() => void) | undefined
    vi.stubGlobal('innerWidth', 1024)
    vi.stubGlobal('matchMedia', (query: string) => ({
      get matches() { return query === '(hover: none) and (pointer: coarse)' && touch },
      addEventListener: (_: string, callback: () => void) => { inputChanged = callback },
      removeEventListener: () => { inputChanged = undefined },
    }))
    renderToolbar()
    fireEvent.click(screen.getByRole('button', { name: '모바일 전체 필터 열기' }))
    expect(screen.getByRole('dialog', { name: '전체 필터' })).toBeInTheDocument()
    act(() => { touch = false; inputChanged?.() })
    expect(screen.queryByRole('dialog', { name: '전체 필터' })).not.toBeInTheDocument()
    expect(document.body.style.overflow).toBe('')
    await waitFor(() => expect(screen.getByRole('button', { name: '전체 필터 열기' })).toHaveFocus())
    expect(inputChanged).toBeUndefined()
  })

  it('모바일 시트가 열린 채 데스크톱 너비가 되면 숨은 dialog를 종료한다', () => {
    const innerWidth = vi.spyOn(window, 'innerWidth', 'get')
      .mockReturnValue(390)
    renderToolbar()
    fireEvent.click(screen.getByRole('button', {
      name: '모바일 지역 필터 열기',
    }))
    expect(screen.getByRole('dialog', { name: '지역 필터' }))
      .toBeInTheDocument()

    innerWidth.mockReturnValue(768)
    fireEvent(window, new Event('resize'))

    expect(screen.queryByRole('dialog', { name: '지역 필터' }))
      .not.toBeInTheDocument()
    innerWidth.mockRestore()
  })

  it('시군구 로딩 실패를 즉시 알리고 관련 선택 목록과 연결한다', async () => {
    const regionRepository = {
      search: vi.fn().mockRejectedValue(new Error('network error')),
    }
    renderToolbar({ filters: { regionCode: '11' }, regionRepository })

    fireEvent.click(screen.getByRole('button', { name: '지역 필터 열기' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(
      '지역을 불러오지 못했어요.',
    )
    fireEvent.click(screen.getByRole('button', { name: '시·군·구 선택' }))
    expect(screen.getByRole('group', { name: '시·군·구 선택' }))
      .toHaveAttribute('aria-describedby', alert.id)
  })

  it('toolbar 키보드 키로 인접 필터와 양 끝으로 포커스를 옮긴다', () => {
    renderToolbar()
    const rentalType = screen.getByRole('button', {
      name: '임대유형 필터 열기',
    })
    const applicationStatus = within(screen.getByRole('toolbar', { name: '단지 검색 필터' })).getByRole('switch', { name: '모집중' })
    const detail = screen.getByRole('button', { name: '전체 필터 열기' })
    const region = screen.getByRole('button', { name: '지역 필터 열기' })

    rentalType.focus()
    fireEvent.keyDown(rentalType, { key: 'ArrowRight' })
    expect(screen.getByRole('button', { name: '가격 필터 열기' })).toHaveFocus()
    fireEvent.keyDown(rentalType, { key: 'End' })
    expect(applicationStatus).toHaveFocus()
    fireEvent.keyDown(applicationStatus, { key: 'ArrowRight' })
    expect(region).toHaveFocus()
    fireEvent.keyDown(region, { key: 'ArrowLeft' })
    expect(applicationStatus).toHaveFocus()
    fireEvent.keyDown(detail, { key: 'ArrowLeft' })
    expect(screen.getByRole('button', { name: '전용면적 필터 열기' })).toHaveFocus()
    fireEvent.keyDown(detail, { key: 'Home' })
    expect(region).toHaveFocus()
  })
})

function StatefulToolbar({ initialFilters, onApply, regionRepository }: {
  initialFilters: ComplexSearchFilters
  onApply?: (filters: ComplexSearchFilters) => void
  regionRepository?: ComponentProps<typeof ComplexFilterToolbar>['regionRepository']
}) {
  const [filters, setFilters] = useState(initialFilters)
  return <>
    <ComplexFilterToolbar filters={filters} regionRepository={regionRepository} onApply={(next) => { setFilters(next); onApply?.(next) }} />
    <output data-testid="applied-filters">{JSON.stringify(filters)}</output>
  </>
}

function appliedFilters() {
  return JSON.parse(screen.getByTestId('applied-filters').textContent ?? '{}') as ComplexSearchFilters
}

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
