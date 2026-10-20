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
  fireEvent.change(screen.getByLabelText('공고의 주택형명'), { target: { value: '작성한 26B' } })
  fireEvent.click(screen.getByRole('button', { name: '단지 검색' }))
  fireEvent.click(await screen.findByRole('button', { name: '선택: 새 단지' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('단지 조회 실패')
  await waitFor(() => expect(screen.getByLabelText('공고의 주택형명')).toBeEnabled())
  expect(screen.getByLabelText('공고의 주택형명')).toHaveValue('작성한 26B')
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
