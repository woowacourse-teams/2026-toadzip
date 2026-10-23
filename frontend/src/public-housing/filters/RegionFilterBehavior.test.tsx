import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { PublicHousingRegionRepository } from '../api/publicHousingRegionRepository.ts'
import type { ComplexSearchFilters } from '../api/publicHousingRepository.ts'
import type { PublicHousingRegion } from '../model/publicHousingRegion.ts'
import { ComplexFilterToolbar } from './ComplexFilterToolbar.tsx'
import { AnnouncementFilterPanel } from './AnnouncementFilterPanel.tsx'

const SEOUL_REGIONS: readonly PublicHousingRegion[] = [{
  regionCode: '11680', provinceName: '서울특별시',
  districtName: '강남구', displayName: '서울특별시 강남구',
}]
const GYEONGGI_REGIONS: readonly PublicHousingRegion[] = [{
  regionCode: '41110', provinceName: '경기도',
  districtName: '수원시', displayName: '경기도 수원시',
}]

describe.each([
  { kind: 'announcement', open: '공고 필터 열기', apply: '공고 필터 적용', close: '공고 필터 닫기' },
  { kind: 'complex', open: '지역 필터 열기', apply: '지역 필터 적용', close: '지역 필터 패널 닫기' },
] as const)('$kind 지역 필터', ({ kind, open, apply, close }) => {
  it.each(['success', 'error'] as const)('지역을 바꾸면 이전 요청을 취소하고 늦은 %s 응답을 무시한다', async (outcome) => {
    const first = deferred<readonly PublicHousingRegion[]>()
    const second = deferred<readonly PublicHousingRegion[]>()
    const search = vi.fn<PublicHousingRegionRepository['search']>()
      .mockResolvedValue([])
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
    const onApply = vi.fn()
    renderPanel({ onApply, regionRepository: { search } })
    fireEvent.click(screen.getByRole('button', { name: open }))
    selectProvince('11')
    expect(screen.getByText(kind === 'announcement'
      ? '시·군·구 목록을 불러오는 중이에요.'
      : '지역을 불러오고 있어요.')).toBeVisible()
    selectProvince('41')
    expect(search.mock.calls[0][1].aborted).toBe(true)
    await act(async () => second.resolve(GYEONGGI_REGIONS))
    await act(async () => {
      if (outcome === 'success') first.resolve(SEOUL_REGIONS)
      else first.reject(new Error('old request failed'))
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText(/불러오.*중|불러오고 있어요/)).not.toBeInTheDocument()
    expect(screen.getByRole(optionRole, { name: '수원시' })).toBeInTheDocument()
    expect(screen.queryByRole(optionRole, { name: '강남구' })).not.toBeInTheDocument()
    selectDistrict('41110', '수원시')
    if (kind === 'announcement') expect(onApply).not.toHaveBeenCalled()
    if (kind === 'announcement') fireEvent.click(screen.getByRole('button', { name: apply }))
    expect(onApply).toHaveBeenLastCalledWith({ regionCode: '41110' })
  })

  it('다른 시도를 고르면 기존 시군구 선택을 지우고 전체 선택은 시군구를 비활성화한다', async () => {
    const search = vi.fn<PublicHousingRegionRepository['search']>()
      .mockImplementation(async (name) => name === '경기도' ? GYEONGGI_REGIONS : SEOUL_REGIONS)
    const onApply = vi.fn()
    renderPanel({ onApply, regionRepository: { search } })
    fireEvent.click(screen.getByRole('button', { name: open }))
    selectProvince('11')
    await screen.findByRole(optionRole, { name: '강남구' })
    selectDistrict('11680', '강남구')
    selectProvince('41')
    await screen.findByRole(optionRole, { name: '수원시' })
    if (kind === 'announcement') expect(screen.getByLabelText('시·군·구')).toHaveValue('')
    else expect(screen.getByRole('radio', { name: '경기 전체' })).toBeChecked()
    expect(screen.queryByRole(optionRole, { name: '강남구' })).not.toBeInTheDocument()
    selectProvince('')
    if (kind === 'announcement') {
      expect(screen.getByLabelText('시·군·구')).toBeDisabled()
      expect(within(screen.getByLabelText('시·군·구')).getAllByRole('option')).toHaveLength(1)
    } else {
      expect(screen.getByRole('button', { name: '시·군·구 선택' })).toBeDisabled()
      expect(screen.getByRole('button', { name: '읍·면·동 선택' })).toBeDisabled()
    }
    if (kind === 'announcement') fireEvent.click(screen.getByRole('button', { name: apply }))
    expect(onApply).toHaveBeenLastCalledWith({})
  })

  it.each([['41135', '성남시 분당구'], ['41102', '지역 선택됨']])('조회 실패에도 공유 URL의 %s 조건은 보존하고 지역 코드 대신 이름이나 선택 상태를 보인다', async (regionCode, label) => {
    const search = vi.fn<PublicHousingRegionRepository['search']>()
      .mockRejectedValue(new Error('offline'))
    const onApply = vi.fn()
    renderPanel({ filters: { regionCode }, onApply, regionRepository: { search } })
    fireEvent.click(screen.getByRole('button', { name: open }))
    const alert = await screen.findByRole('alert')
    if (kind === 'announcement') {
      const district = screen.getByLabelText('시·군·구')
      expect(district).toHaveValue(regionCode)
      expect(district).toHaveAttribute('aria-describedby', alert.id)
    } else {
      fireEvent.click(screen.getByRole('button', { name: '시·군·구 선택' }))
      expect(screen.getByRole('group', { name: '시·군·구 선택' })).toHaveAttribute('aria-describedby', alert.id)
      expect(screen.getByRole('radio', { name: label })).toBeChecked()
    }
    expect(screen.getByRole(optionRole, { name: label })).toBeInTheDocument()
    if (kind === 'announcement') fireEvent.click(screen.getByRole('button', { name: apply }))
    if (kind === 'announcement') expect(onApply).toHaveBeenLastCalledWith({ regionCode })
    else expect(onApply).not.toHaveBeenCalled()
    selectDistrict('', '경기 전체')
    if (kind === 'announcement') fireEvent.click(screen.getByRole('button', { name: apply }))
    expect(onApply).toHaveBeenLastCalledWith({ regionCode: '41' })
    await act(async () => {})
  })

  it('닫으면 요청을 취소하고 다시 열었을 때 이전 결과를 재사용하지 않는다', async () => {
    const pending = deferred<readonly PublicHousingRegion[]>()
    const search = vi.fn<PublicHousingRegionRepository['search']>()
      .mockImplementation(async (name) => name === '경기도' ? GYEONGGI_REGIONS : [])
      .mockReturnValueOnce(pending.promise)
    const onApply = vi.fn()
    renderPanel({ onApply, regionRepository: { search } })
    fireEvent.click(screen.getByRole('button', { name: open }))
    selectProvince('11')
    fireEvent.click(screen.getByRole('button', { name: close }))
    expect(search.mock.calls[0][1].aborted).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: open }))
    selectProvince('41')
    await screen.findByRole(optionRole, { name: '수원시' })
    await act(async () => pending.resolve(SEOUL_REGIONS))
    expect(screen.queryByRole(optionRole, { name: '강남구' })).not.toBeInTheDocument()
    if (kind === 'announcement') expect(screen.getByLabelText('시·도')).toHaveValue('41')
    else expect(screen.getByRole('button', { name: '시·도 선택' })).toHaveTextContent('경기')
    if (kind === 'announcement') expect(onApply).not.toHaveBeenCalled()
    else expect(onApply).toHaveBeenLastCalledWith({ regionCode: '41' })
  })

  const optionRole = kind === 'announcement' ? 'option' : 'radio'

  function selectProvince(code: string) {
    if (kind === 'announcement') {
      fireEvent.change(screen.getByLabelText('시·도'), { target: { value: code } })
      return
    }
    const name = code === '11' ? '서울' : code === '41' ? '경기' : '전체'
    if (!screen.queryByRole('radio', { name })) {
      fireEvent.click(screen.getByRole('button', { name: '시·도 선택' }))
    }
    fireEvent.click(screen.getByRole('radio', { name }))
  }

  function selectDistrict(code: string, name: string) {
    if (kind === 'announcement') fireEvent.change(screen.getByLabelText('시·군·구'), { target: { value: code } })
    else fireEvent.click(screen.getByRole('radio', { name }))
  }

  function renderPanel({ filters = {}, onApply, regionRepository }: {
    readonly filters?: ComplexSearchFilters
    readonly onApply: (filters: ComplexSearchFilters) => void
    readonly regionRepository: PublicHousingRegionRepository
  }) {
    return render(kind === 'announcement'
      ? <AnnouncementFilterPanel filters={filters} onApply={onApply} regionRepository={regionRepository} />
      : <AppliedComplexFilters initialFilters={filters} onApply={onApply} regionRepository={regionRepository} />)
  }
})

it('공고 초기화는 적용된 지역과 선택 조건도 지우고 다시 열어도 빈 상태를 유지한다', async () => {
  const regionRepository = { search: vi.fn().mockResolvedValue(GYEONGGI_REGIONS) }
  function AppliedAnnouncementFilters() {
    const [filters, setFilters] = useState<ComplexSearchFilters>({
      regionCode: '41110', rentalTypes: ['HAPPY_HOUSING'], agencyCodes: ['LH'],
    })
    return <AnnouncementFilterPanel filters={filters} onApply={setFilters} regionRepository={regionRepository} />
  }
  render(<AppliedAnnouncementFilters />)
  fireEvent.click(screen.getByRole('button', { name: '공고 필터 열기' }))
  await screen.findByRole('option', { name: '수원시' })
  fireEvent.click(screen.getByRole('button', { name: '초기화' }))
  expect(screen.getByLabelText('시·도')).toHaveValue('')
  expect(screen.getByLabelText('시·군·구')).toBeDisabled()
  expect(screen.getByRole('checkbox', { name: '행복주택' })).not.toBeChecked()
  expect(screen.getByRole('checkbox', { name: 'LH' })).not.toBeChecked()
  fireEvent.click(screen.getByRole('button', { name: '공고 필터 닫기' }))
  expect(screen.getByRole('button', { name: '공고 필터 열기' })).toHaveAccessibleDescription('조건 선택')
  fireEvent.click(screen.getByRole('button', { name: '공고 필터 열기' }))
  expect(screen.getByLabelText('시·도')).toHaveValue('')
})

it.each(['announcement', 'complex'] as const)('%s 폼은 표시된 조건만 반영하고 다른 조건을 보존한다', async (kind) => {
  const filters: ComplexSearchFilters = {
    regionCode: '41110',
    rentalTypes: ['NATIONAL_RENTAL', 'HAPPY_HOUSING'],
    applicationStatuses: ['CLOSED', 'APPLYING', 'BEFORE_APPLICATION'],
    agencyCodes: ['GH', 'SH', 'LH'],
    recruitmentTypes: ['WAITLIST', 'NEW'],
    maxDeposit: 0,
  }
  const regionRepository = { search: vi.fn().mockResolvedValue(GYEONGGI_REGIONS) }
  const onApply = vi.fn()
  render(kind === 'announcement'
    ? <AnnouncementFilterPanel filters={filters} onApply={onApply} regionRepository={regionRepository} />
    : <AppliedComplexFilters initialFilters={filters} onApply={onApply} regionRepository={regionRepository} />)
  fireEvent.click(screen.getByRole('button', { name: kind === 'announcement'
    ? '공고 필터 열기' : '모바일 지역 필터 열기' }))
  if (kind === 'complex') fireEvent.click(screen.getByRole('button', { name: '시·군·구 선택' }))
  await screen.findByRole(kind === 'announcement' ? 'option' : 'radio', { name: '수원시' })
  expect(onApply).not.toHaveBeenCalled()
  if (kind === 'announcement') fireEvent.click(screen.getByRole('button', { name: '공고 필터 적용' }))
  else fireEvent.click(screen.getByRole('radio', { name: '경기 전체' }))
  expect(onApply).toHaveBeenCalledExactlyOnceWith(kind === 'complex' ? { ...filters, regionCode: '41' } : {
    regionCode: '41110',
    rentalTypes: ['HAPPY_HOUSING', 'NATIONAL_RENTAL'],
    applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'],
    agencyCodes: ['LH', 'SH', 'GH'],
    recruitmentTypes: ['NEW', 'WAITLIST'],
  })
})

function AppliedComplexFilters({ initialFilters, onApply, regionRepository }: {
  initialFilters: ComplexSearchFilters
  onApply: (filters: ComplexSearchFilters) => void
  regionRepository: PublicHousingRegionRepository
}) {
  const [filters, setFilters] = useState(initialFilters)
  return <ComplexFilterToolbar filters={filters} regionRepository={regionRepository}
    onApply={(next) => { setFilters(next); onApply(next) }} />
}

function deferred<Value>() {
  let resolve!: (value: Value) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<Value>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}
