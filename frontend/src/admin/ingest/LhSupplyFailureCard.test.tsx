import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { LhSupplyFailureCard } from './LhSupplyFailureCard'
import { getLhSupplyLinks, type LhSupplyLinkView } from './supplyMatchingApi'

vi.mock('./supplyMatchingApi', () => ({ getLhSupplyLinks: vi.fn() }))

const failedRow = { rowIdentifier: 'LH:pan:SUPPLY:12', complexLabel: '대전중촌2 증축 영구임대주택(주거복지동)',
  typeName: '19A(주거약자용)', exclusiveArea: '19.91', supplyArea: '27.84', suppliedUnitCount: '11',
  linkedRowIdentifier: null, savedLink: null }
const candidate = { supplyRowId: 7, myhomeRowIdentifier: 'myhome-14', sourceComplexName: '대전중촌2',
  sourceHousingTypeName: '19A(주거약자용)', complexName: '대전중촌2', housingTypeName: '19A', lhRowIdentifier: null }
const view: LhSupplyLinkView = { token: 'token',
  announcement: { identifier: '21391', name: '대전광역시 영구임대주택 예비입주자 모집(2026.10.06)',
    sourceUrl: 'https://apply.lh.or.kr/notice', panId: 'pan' },
  myhomeRows: [{ rowIdentifier: 'myhome-14', complexName: '대전중촌2', housingTypeName: '아파트',
    supplyHouseholdCount: 91, splitCorrected: false }],
  lhRows: [failedRow],
  failures: [{ lhRowIdentifier: failedRow.rowIdentifier, reason: 'COMPLEX_NOT_FOUND', detail: '단지 없음', candidates: [candidate] }] }

describe('LH 보강 실패 주택형 비교', () => {
  beforeEach(() => vi.resetAllMocks())

  it('어떤 공고의 어떤 주택형이 무엇과 달라 실패했는지 나란히 보여 주고 연결을 시작한다', async () => {
    vi.mocked(getLhSupplyLinks).mockResolvedValue(view)
    const onLink = vi.fn()
    render(<LhSupplyFailureCard identifier="21391" onLink={onLink} onMatch={vi.fn()} />)

    expect(await screen.findByRole('heading', { name: '대전광역시 영구임대주택 예비입주자 모집(2026.10.06)' })).toBeVisible()
    expect(screen.getByRole('link', { name: '공고 원문 보기' })).toHaveAttribute('href', 'https://apply.lh.or.kr/notice')
    expect(screen.getByText('실패한 주택형: 19A(주거약자용) · 전용 19.91㎡ · 모집 11')).toBeVisible()
    const complexRow = within(screen.getByRole('table')).getByRole('row', { name: /단지명/ })
    expect(complexRow).toHaveTextContent('대전중촌2 증축 영구임대주택(주거복지동)')
    expect(complexRow).toHaveTextContent('대전중촌2 (다름)')
    expect(screen.getByText(/단지명이 달라 같은 단지로 판단하지 못했습니다/)).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '포함된다면: 이 LH 행을 대전중촌2에 연결' }))

    expect(onLink).toHaveBeenCalledWith({ lhRowIdentifier: 'LH:pan:SUPPLY:12', myhomeRowIdentifier: 'myhome-14' })
  })

  it('정제 기준으로 이미 이어지는 행이면 연결 대신 재정제를 안내한다', async () => {
    vi.mocked(getLhSupplyLinks).mockResolvedValue({ ...view, lhRows: [{ ...failedRow, linkedRowIdentifier: 'myhome-14' }] })
    render(<LhSupplyFailureCard identifier="21391" onLink={vi.fn()} onMatch={vi.fn()} />)

    expect(await screen.findByText(/정제 기준으로는 이미 대전중촌2에 이어집니다/)).toBeVisible()
    expect(screen.queryByRole('button', { name: /연결/ })).not.toBeInTheDocument()
  })

  it('현재 데이터로 실패가 없으면 이전 정제의 오류라고 알린다', async () => {
    vi.mocked(getLhSupplyLinks).mockResolvedValue({ ...view, failures: [] })
    render(<LhSupplyFailureCard identifier="21391" onLink={vi.fn()} onMatch={vi.fn()} />)

    expect(await screen.findByText(/모두 공고 공급행에 이어집니다/)).toBeVisible()
  })

  // 실제 21391: 중촌2 원천의 단지를 하나로 정하지 못해 정제가 LH 연결을 기록하지 않았고, 재정제로도 풀리지 않았다.
  it('비교한 공급행에 단지가 없으면 재정제나 LH 연결 대신 단지·주택형 매칭부터 안내한다', async () => {
    vi.mocked(getLhSupplyLinks).mockResolvedValue({ ...view,
      lhRows: [{ ...failedRow, linkedRowIdentifier: 'myhome-14' }],
      failures: [{ ...view.failures![0], candidates: [{ ...candidate, complexName: null, housingTypeName: null }] }] })
    const onMatch = vi.fn()
    render(<LhSupplyFailureCard identifier="21391" onLink={vi.fn()} onMatch={onMatch} />)

    expect(await screen.findByText(/단지가 연결되어 있지 않습니다/)).toBeVisible()
    expect(screen.queryByText(/정제 기준으로는 이미/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '단지·주택형 매칭 열기' }))
    expect(onMatch).toHaveBeenCalled()
  })
})
