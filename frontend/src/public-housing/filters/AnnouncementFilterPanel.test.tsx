import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { type ComponentProps, useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { AnnouncementFilterPanel } from './AnnouncementFilterPanel.tsx'

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

describe('AnnouncementFilterPanel', () => {
  it('적용과 초기화로 입력 폼이 교체되어도 패널 안에 포커스를 유지하고 Escape로 되돌린다', () => {
    function AppliedFilters() {
      const [filters, setFilters] = useState<ComponentProps<typeof AnnouncementFilterPanel>['filters']>({})
      return <AnnouncementFilterPanel filters={filters} onApply={setFilters} />
    }
    render(<AppliedFilters />)
    fireEvent.click(screen.getByRole('button', { name: '공고 필터 열기' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'LH' }))
    const apply = screen.getByRole('button', { name: '공고 필터 적용' })
    apply.focus()
    fireEvent.click(apply)
    expect(screen.getByRole('button', { name: '공고 필터 닫기' })).toHaveFocus()
    const reset = screen.getByRole('button', { name: '초기화' })
    reset.focus()
    fireEvent.click(reset)
    const close = screen.getByRole('button', { name: '공고 필터 닫기' })
    expect(close).toHaveFocus()
    fireEvent.keyDown(close, { key: 'Escape' })
    expect(screen.getByRole('button', { name: '공고 필터 열기' })).toHaveFocus()
  })

  it('적용된 조건을 다시 열어 수정해도 적용 버튼을 누르기 전에는 조회하지 않는다', () => {
    const onApply = vi.fn()
    renderFilter({
      filters: { rentalTypes: ['NATIONAL_RENTAL'], agencyCodes: ['SH'], recruitmentTypes: ['WAITLIST'] },
      onApply,
    })
    expect(screen.getByRole('button', { name: '공고 필터 열기' }))
      .toHaveAccessibleDescription('3개 적용')
    fireEvent.click(screen.getByRole('button', { name: '공고 필터 열기' }))
    expect(screen.getByRole('checkbox', { name: '국민임대' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'SH' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '예비입주자 모집' })).toBeChecked()
    fireEvent.click(screen.getByRole('checkbox', { name: '국민임대' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'LH' }))
    expect(onApply).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '공고 필터 적용' }))
    expect(onApply).toHaveBeenLastCalledWith({
      agencyCodes: ['LH', 'SH'], recruitmentTypes: ['WAITLIST'],
    })
  })

  it('외부 탐색에서 적용 조건이 바뀌면 열려 있는 이전 입력을 새 조건으로 복원한다', () => {
    const onApply = vi.fn()
    const regionRepository = { search: vi.fn().mockResolvedValue([]) }
    const { rerender } = render(<AnnouncementFilterPanel
      filters={{ rentalTypes: ['HAPPY_HOUSING'] }}
      onApply={onApply}
      regionRepository={regionRepository}
    />)
    fireEvent.click(screen.getByRole('button', { name: '공고 필터 열기' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'LH' }))
    rerender(<AnnouncementFilterPanel
      filters={{ applicationStatuses: ['BEFORE_APPLICATION'], agencyCodes: ['GH'] }}
      onApply={onApply}
      regionRepository={regionRepository}
    />)
    expect(screen.getByRole('checkbox', { name: '행복주택' })).not.toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'LH' })).not.toBeChecked()
    expect(screen.getByRole('checkbox', { name: '공고중' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'GH' })).toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: '공고 필터 적용' }))
    expect(onApply).toHaveBeenLastCalledWith({
      applicationStatuses: ['BEFORE_APPLICATION'], agencyCodes: ['GH'],
    })
  })

  it('공고 모집상태는 공고중과 접수중만 선택해 적용할 수 있다', () => {
    const onApply = vi.fn()
    renderFilter({ onApply })

    fireEvent.click(screen.getByRole('button', { name: '공고 필터 열기' }))
    const statusGroup = within(screen.getByRole('group', { name: '모집상태' }))
    expect(statusGroup.queryByRole('checkbox', { name: '접수마감' }))
      .not.toBeInTheDocument()
    fireEvent.click(statusGroup.getByRole('checkbox', { name: '공고중' }))
    fireEvent.click(statusGroup.getByRole('checkbox', { name: '접수중' }))
    fireEvent.click(screen.getByRole('button', { name: '공고 필터 적용' }))

    expect(onApply).toHaveBeenLastCalledWith({
      applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'],
    })
  })

  it('공고 조건을 함께 선택해 적용하고 초기화한다', () => {
    const onApply = vi.fn()
    renderFilter({ onApply })

    fireEvent.click(screen.getByRole('button', { name: '공고 필터 열기' }))
    fireEvent.click(within(screen.getByRole('group', { name: '임대유형' }))
      .getByRole('checkbox', { name: '행복주택' }))
    fireEvent.click(within(screen.getByRole('group', { name: '모집상태' }))
      .getByRole('checkbox', { name: '접수중' }))
    fireEvent.click(screen.getByRole('button', { name: '공고 필터 적용' }))

    expect(onApply).toHaveBeenLastCalledWith({
      rentalTypes: ['HAPPY_HOUSING'],
      applicationStatuses: ['APPLYING'],
    })
    fireEvent.click(screen.getByRole('button', { name: '초기화' }))
    expect(onApply).toHaveBeenLastCalledWith({})
    expect(screen.getByRole('checkbox', { name: '행복주택' })).not.toBeChecked()
    expect(screen.getByRole('checkbox', { name: '접수중' })).not.toBeChecked()
  })

  it('헤더에서 닫거나 Escape를 누르면 적용 없이 닫고 열기 버튼으로 포커스를 돌린다', () => {
    const onApply = vi.fn()
    renderFilter({ onApply })

    fireEvent.click(screen.getByRole('button', { name: '공고 필터 열기' }))
    expect(screen.getByRole('heading', { name: '공고 필터' })).toBeVisible()
    expect(screen.getByRole('button', { name: '공고 필터 닫기' })).toHaveFocus()
    fireEvent.click(screen.getByRole('checkbox', { name: '행복주택' }))
    fireEvent.keyDown(screen.getByRole('checkbox', { name: '행복주택' }), {
      key: 'Escape',
    })

    expect(screen.queryByRole('checkbox', { name: '행복주택' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '공고 필터 열기' })).toHaveFocus()
    expect(onApply).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '공고 필터 열기' }))
    expect(screen.getByRole('checkbox', { name: '행복주택' })).not.toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: '공고 필터 닫기' }))
    expect(screen.getByRole('button', { name: '공고 필터 열기' })).toHaveFocus()
  })

  it('시도를 선택하면 직속 시군구를 불러오고 시도만 또는 시군구까지 적용한다', async () => {
    const onApply = vi.fn()
    const regionRepository = {
      search: vi.fn().mockResolvedValue(GYEONGGI_REGIONS),
    }
    renderFilter({ onApply, regionRepository })

    fireEvent.click(screen.getByRole('button', { name: '공고 필터 열기' }))
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

    fireEvent.click(screen.getByRole('button', { name: '공고 필터 적용' }))
    expect(onApply).toHaveBeenLastCalledWith({ regionCode: '41' })

    fireEvent.change(districtSelect, { target: { value: '41110' } })
    fireEvent.click(screen.getByRole('button', { name: '공고 필터 적용' }))
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

    fireEvent.click(screen.getByRole('button', { name: '공고 필터 열기' }))

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

    fireEvent.click(screen.getByRole('button', { name: '공고 필터 열기' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('시·군·구를 불러오지 못했습니다.')
    expect(screen.getByLabelText('시·군·구'))
      .toHaveAttribute('aria-describedby', alert.id)
  })


})

function renderFilter({
  filters = {},
  onApply = vi.fn(),
  regionRepository = { search: vi.fn().mockResolvedValue([]) },
}: {
  readonly filters?: ComponentProps<typeof AnnouncementFilterPanel>['filters']
  readonly onApply?: ComponentProps<typeof AnnouncementFilterPanel>['onApply']
  readonly regionRepository?: ComponentProps<typeof AnnouncementFilterPanel>['regionRepository']
} = {}) {
  return render(<AnnouncementFilterPanel
    filters={filters}
    onApply={onApply}
    regionRepository={regionRepository}
  />)
}
