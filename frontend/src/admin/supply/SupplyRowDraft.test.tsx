import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, expect, it, vi } from 'vitest'
import { SupplyRowDraft } from './SupplyRowDraft'
import { blankRow, blankTarget, type CorrectionRow } from './supplyCorrectionApi'
import { getManagementDetail, getManagementPage } from '../management/api'
import type { ManagementDetailData } from '../management/managementContract'
import { getMatchingHousingTypes } from '../ingest/supplyMatchingApi'
vi.mock('../ingest/supplyMatchingApi', () => ({ getMatchingHousingTypes: vi.fn() }))
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(), getManagementDetail: vi.fn(), getManagementPage: vi.fn() }))
const summary = { id: 7, name: '새 단지', subtitle: '서울', provider: 'LH', rental: 'HAPPY_HOUSING', deleted: false, modified: false, reviewRequired: false, updatedAt: null }
const detail: ManagementDetailData = { summary, sourceIdentifier: 'source', data: { address: { pnu: '1114010100100010000' } }, housingTypes: [{ id: 9, name: '26', exclusiveArea: 26.5195, householdCount: null }], announcements: [], supplyRows: [], schedules: [], scheduleReviewed: false }
function Editor() {
  const [row, setRow] = useState<CorrectionRow>({ ...blankRow(0), housingComplexId: 1, housingComplexName: '기존 단지', housingTypeId: 2, typeName: '26A' })
  return <SupplyRowDraft value={row} onChange={setRow} onRemove={vi.fn()} onMove={vi.fn()} first last />
}
beforeEach(() => {
  vi.resetAllMocks(); vi.mocked(getManagementDetail).mockResolvedValue(detail)
  vi.mocked(getMatchingHousingTypes).mockResolvedValue([{ id: 9, name: '26', exclusiveArea: 26.5195, supplyArea: 40, householdCount: 200 }])
  vi.mocked(getManagementPage).mockResolvedValue({ page: 0, hasNext: false, totalElements: 1, totalPages: 1, items: [summary] })
})
it('다른 단지를 선택하면 이전 주택형을 해제하고 확인된 단지 지번을 사용한다', async () => {
  render(<Editor />)
  fireEvent.click(screen.getByRole('button', { name: '단지 검색' }))
  fireEvent.click(await screen.findByRole('button', { name: '선택: 새 단지' }))
  await screen.findByRole('option', { name: '26 · 전용 26.5195㎡ · #9' })
  expect(screen.getByLabelText('공통 주택형')).toHaveValue('')
  expect(screen.getByLabelText('공급 PNU')).toHaveValue('1114010100100010000')
  fireEvent.change(screen.getByLabelText('공통 주택형'), { target: { value: '9' } })
  expect(screen.getByRole('region', { name: '연결 후보 비교' })).toHaveTextContent('26.5195')
  expect(screen.getByRole('table', { name: '공고 입력값과 선택한 공통 주택형' })).toHaveTextContent('26A')
  fireEvent.change(screen.getByLabelText('공통 주택형'), { target: { value: 'new' } })
  expect(screen.getByLabelText('새 공통 주택형 이름')).toHaveValue('26A')
})
it('새 단지 조회가 실패하면 기존 연결과 작성한 값을 유지한다', async () => {
  vi.mocked(getManagementDetail).mockRejectedValue(new Error('단지 조회 실패'))
  render(<Editor />)
  fireEvent.change(screen.getByLabelText('이 공고의 주택형 표기'), { target: { value: '작성한 26B' } })
  fireEvent.click(screen.getByRole('button', { name: '단지 검색' }))
  fireEvent.click(await screen.findByRole('button', { name: '선택: 새 단지' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('단지 조회 실패')
  await waitFor(() => expect(screen.getByLabelText('이 공고의 주택형 표기')).toBeEnabled())
  expect(screen.getByLabelText('이 공고의 주택형 표기')).toHaveValue('작성한 26B')
  expect(screen.getByText(/현재 선택: 기존 단지/)).toBeVisible()
})
it('연결이 빠진 행은 접힌 요약에 문제를 표시하고 처음부터 펼친다', () => {
  const unlinked: CorrectionRow = { ...blankRow(0), id: 3, housingComplexId: 1, housingComplexName: '한스빌', housingTypeId: null, complexName: '익산한스빌', typeName: '아파트' }
  const { container, rerender } = render(<SupplyRowDraft value={unlinked} onChange={vi.fn()} onRemove={vi.fn()} onMove={vi.fn()} first last />)
  expect(container.querySelector('summary')).toHaveTextContent('주택형 미연결')
  expect(container.querySelector('details')).toHaveAttribute('open')
  rerender(<SupplyRowDraft key="linked" value={{ ...unlinked, housingTypeId: 9 }} onChange={vi.fn()} onRemove={vi.fn()} onMove={vi.fn()} first last />)
  expect(container.querySelector('summary')).not.toHaveTextContent('미연결')
  expect(container.querySelector('details')).not.toHaveAttribute('open')
})
it('공개할 임대조건이 없는 행은 요약에 표시하되 연결 문제처럼 펼치지 않는다', () => {
  const linked: CorrectionRow = { ...blankRow(0), id: 3, housingComplexId: 1, housingComplexName: '한스빌', housingTypeId: 9, complexName: '익산한스빌', typeName: '아파트' }
  const { container, rerender } = render(<SupplyRowDraft value={linked} onChange={vi.fn()} onRemove={vi.fn()} onMove={vi.fn()} first last />)
  expect(container.querySelector('summary')).toHaveTextContent('임대조건 없음')
  expect(container.querySelector('details')).not.toHaveAttribute('open')
  const excludedOnly = { ...linked, targets: [{ ...blankTarget(0), id: 4, key: 'target:4', target: '일반', excluded: true }] }
  rerender(<SupplyRowDraft key="excluded-target" value={excludedOnly} onChange={vi.fn()} onRemove={vi.fn()} onMove={vi.fn()} first last />)
  expect(container.querySelector('summary')).toHaveTextContent('임대조건 없음')
  rerender(<SupplyRowDraft key="with-target" value={{ ...excludedOnly, targets: [{ ...excludedOnly.targets[0], excluded: false }] }} onChange={vi.fn()} onRemove={vi.fn()} onMove={vi.fn()} first last />)
  expect(container.querySelector('summary')).not.toHaveTextContent('임대조건 없음')
})
it('펼친 행에 대응하는 원천값을 보여 주고, 원천이 없거나 사라진 경우를 구분한다', () => {
  const stored: CorrectionRow = { ...blankRow(0), id: 3, key: 'row:3', housingComplexId: 1, housingComplexName: '한스빌', housingTypeId: 9, complexName: '익산한스빌', typeName: '아파트' }
  const props = { onChange: vi.fn(), onRemove: vi.fn(), onMove: vi.fn(), first: true, last: true }
  const { rerender } = render(<SupplyRowDraft key="found" value={stored} {...props}
    sources={[{ category: 'MYHOME_ANNOUNCEMENT', sourceKey: '12:manual-21395:1', found: { category: 'MYHOME_ANNOUNCEMENT', sourceKey: '12:manual-21395:1', rows: [{ collectedAt: null, raw: { hshld_co: '170' } }] } },
      { category: 'LH_ANNOUNCEMENT_SUPPLY', sourceKey: 'PAN-77:hash-a:1', found: { category: 'LH_ANNOUNCEMENT_SUPPLY', sourceKey: 'PAN-77:hash-a:1', rows: [{ collectedAt: null, raw: { supplied_unit_count: '10' } }] } }]} />)
  fireEvent.click(screen.getByText(/익산한스빌 · 아파트/))
  const region = screen.getByRole('region', { name: '대응 원천' })
  expect(region).toHaveTextContent('12:manual-21395:1')
  expect(region).toHaveTextContent('170')
  expect(region).toHaveTextContent('PAN-77:hash-a:1')
  expect(region).toHaveTextContent('10')
  rerender(<SupplyRowDraft key="lost" value={stored} {...props} sources={[{ category: 'LH_ANNOUNCEMENT_SUPPLY', sourceKey: 'LH:PAN-77:SUPPLY:9', found: null }]} />)
  fireEvent.click(screen.getByText(/익산한스빌 · 아파트/))
  expect(screen.getByRole('region', { name: '대응 원천' })).toHaveTextContent('지금 원천 목록에서 찾을 수 없습니다')
  rerender(<SupplyRowDraft key="none" value={stored} {...props} />)
  fireEvent.click(screen.getByText(/익산한스빌 · 아파트/))
  expect(screen.getByRole('region', { name: '대응 원천' })).toHaveTextContent('대응하는 원천을 정할 수 없습니다')
})
it('기존 공급행의 원천 단지명은 수정 칸 없이 보여 주고 단지 주택형 수정은 단지 상세로 안내한다', () => {
  const row: CorrectionRow = { ...blankRow(0), id: 5, key: 'row:5', complexName: '평화주공1단지', housingComplexId: 7, housingComplexName: '평화주공1단지', typeName: '26' }
  render(<SupplyRowDraft value={row} onChange={vi.fn()} onRemove={vi.fn()} onMove={vi.fn()} first last inline />)
  expect(screen.queryByLabelText('공고의 단지명')).not.toBeInTheDocument()
  expect(screen.getByText('수집 원문 단지명: 평화주공1단지')).toBeVisible()
  expect(screen.getByLabelText('이 공고의 주택형 표기')).toHaveValue('26')
  expect(screen.getByRole('link', { name: '단지 주택형 이름 고치기 →' })).toHaveAttribute('href', '/admin/complexes/7?section=relations')
})
it('공통 모집 묶음 행은 지원 종료한 원천별 공급행 보정 대신 여기서 바꿀 수 없다고만 알린다', () => {
  const row: CorrectionRow = { ...blankRow(0), id: 5, key: 'row:5', housingComplexId: 7, housingComplexName: '한스빌', housingTypeId: 9, typeName: '26', groupId: 3, sharedHouseholdCount: 170 }
  render(<SupplyRowDraft value={row} onChange={vi.fn()} onRemove={vi.fn()} onMove={vi.fn()} first last inline />)
  const notice = screen.getByText(/공통 모집수 170세대는 묶음에 한 번만 보관합니다/)
  expect(notice).toHaveTextContent('단지·공통 모집수는 여기서 바꿀 수 없습니다.')
  expect(notice).not.toHaveTextContent('원천별 공급행 보정')
})
it('조회한 함께 모집하는 주택형은 손대지 않으면 그대로 두고, 대표 주택형·단지를 바꾸면 빈 목록으로 보내 되돌려도 이전 연결이 살아나지 않는다', async () => {
  const onChange = vi.fn<(value: CorrectionRow) => void>()
  const row: CorrectionRow = { ...blankRow(0), id: 3, housingComplexId: 1, housingComplexName: '한스빌', housingTypeId: 9, complexName: '익산한스빌', typeName: '26A/B', linkedHousingTypes: [{ housingTypeId: 10 }] }
  render(<SupplyRowDraft value={row} onChange={onChange} onRemove={vi.fn()} onMove={vi.fn()} first last />)
  fireEvent.change(screen.getByLabelText('입주 예정 연월'), { target: { value: '2027-03' } })
  expect(onChange.mock.lastCall![0].linkedHousingTypes).toEqual([{ housingTypeId: 10 }])
  fireEvent.click(screen.getByRole('button', { name: '이 단지의 주택형 확인' }))
  await screen.findByRole('option', { name: '26 · 전용 26.5195㎡ · #9' })
  fireEvent.change(screen.getByLabelText('공통 주택형'), { target: { value: 'new' } })
  expect(onChange.mock.lastCall![0].linkedHousingTypes).toEqual([])
  fireEvent.change(screen.getByLabelText('공통 주택형'), { target: { value: '9' } })
  expect(onChange.mock.lastCall![0].linkedHousingTypes).toEqual([])
  fireEvent.click(screen.getByRole('button', { name: '단지·주택형 연결 해제' }))
  expect(onChange.mock.lastCall![0].linkedHousingTypes).toEqual([])
  fireEvent.click(screen.getByRole('button', { name: '단지 검색' }))
  fireEvent.click(await screen.findByRole('button', { name: '선택: 새 단지' }))
  await waitFor(() => expect(onChange.mock.lastCall![0].housingComplexId).toBe(7))
  expect(onChange.mock.lastCall![0].linkedHousingTypes).toEqual([])
})
