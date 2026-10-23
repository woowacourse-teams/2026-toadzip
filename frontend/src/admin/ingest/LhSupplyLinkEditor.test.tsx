import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { LhSupplyLinkEditor } from './LhSupplyLinkEditor'
import { getLhSupplyLinks, releaseLhSupplyLink, saveLhSupplyLinks, type LhSupplyLinkView } from './supplyMatchingApi'

vi.mock('./supplyMatchingApi', () => ({ getLhSupplyLinks: vi.fn(), saveLhSupplyLinks: vi.fn(), releaseLhSupplyLink: vi.fn() }))

const myhomeRows = [
  { rowIdentifier: 'myhome-1', complexName: '평화주공1단지', housingTypeName: '아파트', supplyHouseholdCount: 450, splitCorrected: false },
  { rowIdentifier: 'myhome-4', complexName: '평화주공4단지', housingTypeName: '아파트', supplyHouseholdCount: 300, splitCorrected: false },
  { rowIdentifier: 'myhome-split', complexName: '평화주공5단지', housingTypeName: '아파트', supplyHouseholdCount: 10, splitCorrected: true },
]
const unlinked = { rowIdentifier: 'LH:pan:SUPPLY:0', complexLabel: '전주평화4 영구임대주택', typeName: '26',
  exclusiveArea: '26.37', supplyArea: '38.97', suppliedUnitCount: '12', linkedRowIdentifier: null, savedLink: null }
const automatic = { ...unlinked, rowIdentifier: 'LH:pan:SUPPLY:1', complexLabel: '전주평화주공1단지',
  linkedRowIdentifier: 'myhome-1' }
const saved = { myhomeRowIdentifier: 'myhome-4', sourceUrl: 'https://apply.lh.or.kr/notice', reason: '공고문 3쪽',
  actor: 'operator', updatedAt: '2026-10-24T00:00:00Z', reviewRequired: false }
const view: LhSupplyLinkView = { token: 'token', myhomeRows, lhRows: [unlinked, automatic] }

describe('LH 공급 원본 연결', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(getLhSupplyLinks).mockResolvedValue(view)
  })

  it('연결 상태를 보여주고 복수 공급행 보정 행은 고를 수 없다', async () => {
    render(<LhSupplyLinkEditor identifier="19585" />)

    expect(await screen.findByText('연결된 마이홈 원천행 없음')).toBeVisible()
    expect(screen.getByText(/자동 연결 → 평화주공1단지/)).toBeVisible()
    expect(screen.getByRole('option', { name: /평화주공5단지.*연결할 수 없음/ })).toBeDisabled()
  })

  it('선택한 LH 행과 원천행을 근거와 함께 조회 토큰으로 저장한다', async () => {
    vi.mocked(saveLhSupplyLinks).mockResolvedValue({ ...view,
      lhRows: [{ ...unlinked, linkedRowIdentifier: 'myhome-4', savedLink: saved }, automatic] })
    render(<LhSupplyLinkEditor identifier="19585" />)
    fireEvent.click(await screen.findByRole('checkbox', { name: /전주평화4 영구임대주택/ }))
    fireEvent.change(screen.getByLabelText('마이홈 원천행'), { target: { value: 'myhome-4' } })
    fireEvent.change(screen.getByLabelText('공식 출처 URL'), { target: { value: 'https://apply.lh.or.kr/notice' } })
    fireEvent.change(screen.getByLabelText('확인 근거'), { target: { value: '공고문 3쪽' } })

    fireEvent.click(screen.getByRole('button', { name: '선택한 LH 행 연결 저장' }))

    await waitFor(() => expect(saveLhSupplyLinks).toHaveBeenCalledWith('19585', view, 'myhome-4',
      ['LH:pan:SUPPLY:0'], 'https://apply.lh.or.kr/notice', '공고문 3쪽'))
    expect(await screen.findByText(/저장한 연결 사용 중 → 평화주공4단지/)).toBeVisible()
  })

  it('근거 없이 저장하지 않는다', async () => {
    render(<LhSupplyLinkEditor identifier="19585" />)
    fireEvent.click(await screen.findByRole('checkbox', { name: /전주평화4 영구임대주택/ }))
    fireEvent.change(screen.getByLabelText('마이홈 원천행'), { target: { value: 'myhome-4' } })

    fireEvent.click(screen.getByRole('button', { name: '선택한 LH 행 연결 저장' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('공식 출처 URL과 확인 근거')
    expect(saveLhSupplyLinks).not.toHaveBeenCalled()
  })

  it('재확인이 필요한 연결을 표시하고 이유와 함께 해제한다', async () => {
    const stale = { ...unlinked, savedLink: { ...saved, reviewRequired: true } }
    vi.mocked(getLhSupplyLinks).mockResolvedValue({ ...view, lhRows: [stale] })
    vi.mocked(releaseLhSupplyLink).mockResolvedValue({ ...view, lhRows: [unlinked] })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<LhSupplyLinkEditor identifier="19585" />)
    expect(await screen.findByText(/저장한 연결 재확인 필요/)).toBeVisible()
    fireEvent.change(screen.getByLabelText('확인 근거'), { target: { value: '다른 단지로 잘못 연결함' } })

    fireEvent.click(screen.getByRole('button', { name: '저장한 연결 해제' }))

    await waitFor(() => expect(releaseLhSupplyLink).toHaveBeenCalledWith('19585',
      { ...view, lhRows: [stale] }, 'LH:pan:SUPPLY:0', '다른 단지로 잘못 연결함'))
    expect(await screen.findByText('연결된 마이홈 원천행 없음')).toBeVisible()
  })
})
