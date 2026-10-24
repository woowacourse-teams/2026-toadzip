import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HouseholdComplexLinkCard } from './HouseholdComplexLinkCard'
import { getHouseholdComplexLink, releaseHouseholdComplexLink, saveHouseholdComplexLink,
  type HouseholdComplexLinkView } from './householdComplexLinkApi'

vi.mock('./householdComplexLinkApi', () => ({ getHouseholdComplexLink: vi.fn(), saveHouseholdComplexLink: vi.fn(),
  releaseHouseholdComplexLink: vi.fn() }))

// 실제 로컬 데이터의 대전관저5 영구임대: 유형·세대수·지역은 맞고 단지명만 다르다.
const view: HouseholdComplexLinkView = { failureId: 397, token: 'token', linkable: true, savedLink: null,
  source: { areaName: '대전광역시 서구', supplyTypeName: '영구임대', complexName: '대전관저5 천년나무 1단지',
    totalHouseholdCount: 240, housingTypes: [{ exclusiveArea: 21.97, householdCount: 60 }, { exclusiveArea: 26.9, householdCount: 180 }],
    invalidReason: null },
  candidates: [{ id: 742, name: '대전관저5(05,국민,GB) 1단지', supplyType: 'PERMANENT_RENTAL', totalHouseholdCount: 240,
    roadAddress: '대전광역시 서구 구봉산북로 212', exclusiveAreas: [21.97, 26.9] }] }
const saved = { complexId: 742, complexName: '대전관저5(05,국민,GB) 1단지', sourceUrl: null, reason: '주소·세대수 대조',
  actor: 'operator', updatedAt: '2026-10-24T00:00:00Z', reviewRequired: false }

describe('세대수 보강 단지 비교', () => {
  beforeEach(() => vi.resetAllMocks())

  it('공급유형·세대수·지역이 같은 유일 후보와 LH 원천을 나란히 보여 준다', async () => {
    vi.mocked(getHouseholdComplexLink).mockResolvedValue(view)
    render(<HouseholdComplexLinkCard failureId={397} />)

    expect(await screen.findByRole('heading', { name: '대전관저5 천년나무 1단지 · 영구임대' })).toBeVisible()
    const table = within(screen.getByRole('table'))
    expect(table.getByRole('row', { name: /공급유형/ })).toHaveTextContent('영구임대영구임대')
    expect(table.getByRole('row', { name: /전체 세대수/ })).toHaveTextContent('240세대240세대')
    expect(table.getByRole('row', { name: /단지명/ })).toHaveTextContent('대전관저5(05,국민,GB) 1단지 (다름)')
    expect(screen.getByText(/단지명이 달라 자동으로 연결하지 않았습니다/)).toBeVisible()
  })

  it('확인 이유를 적어야 유일 후보로 연결을 저장하고 결과를 보여 준다', async () => {
    vi.mocked(getHouseholdComplexLink).mockResolvedValue(view)
    vi.mocked(saveHouseholdComplexLink).mockResolvedValue({ ...view, savedLink: saved })
    render(<HouseholdComplexLinkCard failureId={397} />)
    const button = await screen.findByRole('button', { name: '대전관저5(05,국민,GB) 1단지(으)로 연결 저장' })
    expect(button).toBeDisabled()

    fireEvent.change(screen.getByLabelText('확인 이유'), { target: { value: '주소·세대수 대조' } })
    fireEvent.click(button)

    await waitFor(() => expect(saveHouseholdComplexLink).toHaveBeenCalledWith(view, 742, '주소·세대수 대조', ''))
    expect(await screen.findByText(/단지 정제를 다시 실행하면 세대수 보강에 반영됩니다/)).toBeVisible()
    expect(screen.getByText('저장한 연결 사용 중')).toBeVisible()
  })

  it('후보가 여러 개면 연결 저장을 제공하지 않는다', async () => {
    vi.mocked(getHouseholdComplexLink).mockResolvedValue({ ...view, linkable: false,
      candidates: [view.candidates[0], { ...view.candidates[0], id: 999, name: '다른 단지' }] })
    render(<HouseholdComplexLinkCard failureId={397} />)

    expect(await screen.findByText(/같은 단지가 2개라 하나로 정하지 못했습니다/)).toBeVisible()
    expect(screen.queryByRole('button', { name: /연결 저장/ })).not.toBeInTheDocument()
  })

  it('주택형 면적이 맞지 않으면 보강되지 않을 면적을 표시한다', async () => {
    vi.mocked(getHouseholdComplexLink).mockResolvedValue({ ...view,
      candidates: [{ ...view.candidates[0], exclusiveAreas: [26.9] }] })
    render(<HouseholdComplexLinkCard failureId={397} />)

    expect(await screen.findByText('26.9㎡ (LH 21.97㎡ 없음)')).toBeVisible()
  })

  // 실제 #742는 26·26D가 모두 전용 26.9㎡라, 연결해도 이 면적의 세대수는 채워지지 않는다.
  it('같은 면적의 주택형이 여러 개면 세대수를 채우지 않는다고 표시한다', async () => {
    vi.mocked(getHouseholdComplexLink).mockResolvedValue({ ...view,
      candidates: [{ ...view.candidates[0], exclusiveAreas: [21.97, 26.9, 26.9] }] })
    render(<HouseholdComplexLinkCard failureId={397} />)

    expect(await screen.findByText('21.97㎡, 26.9㎡, 26.9㎡ (26.9㎡ 주택형이 여러 개라 세대수를 채우지 않음)')).toBeVisible()
  })

  it('저장한 연결은 해제 이유를 따로 받아 해제한다', async () => {
    vi.mocked(getHouseholdComplexLink).mockResolvedValue({ ...view, savedLink: { ...saved, reviewRequired: true } })
    vi.mocked(releaseHouseholdComplexLink).mockResolvedValue(view)
    render(<HouseholdComplexLinkCard failureId={397} />)
    expect(await screen.findByText('저장한 연결 재확인 필요')).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '저장한 연결 해제' }))
    fireEvent.change(screen.getByLabelText('해제 이유'), { target: { value: '다른 단지로 잘못 연결함' } })
    fireEvent.click(screen.getByRole('button', { name: '해제' }))

    await waitFor(() => expect(releaseHouseholdComplexLink).toHaveBeenCalledWith(
      { ...view, savedLink: { ...saved, reviewRequired: true } }, '다른 단지로 잘못 연결함'))
  })
})
