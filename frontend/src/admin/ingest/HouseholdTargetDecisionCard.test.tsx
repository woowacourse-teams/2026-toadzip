import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HouseholdTargetDecisionCard } from './HouseholdTargetDecisionCard'
import { getHouseholdTargetDecision, releaseHouseholdTargetDecision, saveHouseholdTargetDecision,
  type HouseholdTargetDecisionView } from './householdTargetDecisionApi'

vi.mock('./householdTargetDecisionApi', () => ({ getHouseholdTargetDecision: vi.fn(),
  saveHouseholdTargetDecision: vi.fn(), releaseHouseholdTargetDecision: vi.fn() }))

const types = [{ area: 33.61, plain: 116, other: 100 }, { area: 33.72, plain: 90, other: 90 },
  { area: 46.8, plain: 57, other: 57 }, { area: 51.93, plain: 90, other: 106 }]
const view: HouseholdTargetDecisionView = { failureId: 801, token: 'token', identical: false, decidable: true, decision: null,
  complex: { id: 2918, name: '남해평리 휴먼시아', supplyType: 'NATIONAL_RENTAL', totalHouseholdCount: 353,
    roadAddress: '경상남도 남해군 남해읍 평리로 30',
    housingTypes: types.map((type, index) => ({ id: 9954 + index, name: String(Math.floor(type.area)), exclusiveArea: type.area,
      totalHouseholdCount: null })) },
  groups: [
    { sourceKey: 'key-plain', complexName: '남해평리', supplyTypeName: '국민임대', areaName: '경상남도 남해군',
      totalHouseholdCount: 353, failureSource: true,
      housingTypes: types.map(type => ({ exclusiveArea: type.area, householdCount: type.plain })) },
    { sourceKey: 'key-other', complexName: '남해평리(국민)', supplyTypeName: '국민임대', areaName: '경상남도 남해군',
      totalHouseholdCount: 353, failureSource: false,
      housingTypes: types.map(type => ({ exclusiveArea: type.area, householdCount: type.other })) },
  ] }

describe('중복 대상 단지 결정', () => {
  beforeEach(() => vi.resetAllMocks())

  it('묶음별로 고르면 주택형에 들어갈 세대수를 나란히 보여 주고 다른 값을 표시한다', async () => {
    vi.mocked(getHouseholdTargetDecision).mockResolvedValue(view)
    render(<HouseholdTargetDecisionCard failureId={801} />)

    expect(await screen.findByRole('heading', { name: '남해평리 휴먼시아 (#2918)' })).toBeVisible()
    const table = within(screen.getByRole('table'))
    expect(table.getByRole('columnheader', { name: '‘남해평리’ · 이 오류' })).toBeVisible()
    const row = table.getByRole('row', { name: /33 · 전용 33.61㎡/ })
    expect(row).toHaveTextContent('116세대100세대')
    expect(within(row).getAllByRole('cell')[0]).toHaveClass(/differs/)
    expect(within(table.getByRole('row', { name: /전용 46.8㎡/ })).getAllByRole('cell')[0]).not.toHaveClass(/differs/)
  })

  it('고른 묶음과 확인 이유로 결정을 저장하고 반영 시점을 알린다', async () => {
    vi.mocked(getHouseholdTargetDecision).mockResolvedValue(view)
    vi.mocked(saveHouseholdTargetDecision).mockResolvedValue({ ...view, decision: { decision: 'USE_SOURCE',
      chosenSourceKey: 'key-plain', reason: '공고문 대조', actor: 'operator', updatedAt: '2026-10-24T00:00:00Z', reviewRequired: false } })
    render(<HouseholdTargetDecisionCard failureId={801} />)
    fireEvent.click(await screen.findByRole('radio', { name: /‘남해평리’ 세대수 사용/ }))
    expect(screen.getByRole('button', { name: '결정 저장' })).toBeDisabled()

    fireEvent.change(screen.getByLabelText('확인 이유'), { target: { value: '공고문 대조' } })
    fireEvent.click(screen.getByRole('button', { name: '결정 저장' }))

    await waitFor(() => expect(saveHouseholdTargetDecision).toHaveBeenCalledWith(view,
      { decision: 'USE_SOURCE', chosenSourceKey: 'key-plain' }, '공고문 대조'))
    expect(await screen.findByText(/단지 정제를 다시 실행하면 반영되고/)).toBeVisible()
    expect(screen.getByText(/‘남해평리’ 세대수 사용$/)).toBeVisible()
  })

  it('보강하지 않음은 현재 값을 그대로 둔다고 알리고 SKIP으로 저장한다', async () => {
    vi.mocked(getHouseholdTargetDecision).mockResolvedValue(view)
    vi.mocked(saveHouseholdTargetDecision).mockResolvedValue(view)
    render(<HouseholdTargetDecisionCard failureId={801} />)
    fireEvent.click(await screen.findByRole('radio', { name: /세대수 보강을 하지 않음 → 주택형 세대수를 지금 값 그대로 둡니다/ }))
    fireEvent.change(screen.getByLabelText('확인 이유'), { target: { value: '확인 불가' } })

    fireEvent.click(screen.getByRole('button', { name: '결정 저장' }))

    await waitFor(() => expect(saveHouseholdTargetDecision).toHaveBeenCalledWith(view, { decision: 'SKIP' }, '확인 불가'))
  })

  it('세대수가 모두 같으면 결정 없이 다음 정제에서 한 번만 반영된다고 안내한다', async () => {
    vi.mocked(getHouseholdTargetDecision).mockResolvedValue({ ...view, identical: true, decidable: false })
    render(<HouseholdTargetDecisionCard failureId={801} />)

    expect(await screen.findByText(/한 번만 반영됩니다/)).toBeVisible()
    expect(screen.queryByRole('button', { name: '결정 저장' })).not.toBeInTheDocument()
  })

  it('재확인이 필요한 결정은 이유를 따로 받아 해제한다', async () => {
    const stale = { ...view, decision: { decision: 'SKIP' as const, chosenSourceKey: null, reason: '확인 불가', actor: 'operator',
      updatedAt: '2026-10-24T00:00:00Z', reviewRequired: true } }
    vi.mocked(getHouseholdTargetDecision).mockResolvedValue(stale)
    vi.mocked(releaseHouseholdTargetDecision).mockResolvedValue(view)
    render(<HouseholdTargetDecisionCard failureId={801} />)
    expect(await screen.findByText('저장한 결정 재확인 필요')).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '저장한 결정 해제' }))
    fireEvent.change(screen.getByLabelText('해제 이유'), { target: { value: '다시 판단' } })
    fireEvent.click(screen.getByRole('button', { name: '해제' }))

    await waitFor(() => expect(releaseHouseholdTargetDecision).toHaveBeenCalledWith(stale, '다시 판단'))
  })
})
