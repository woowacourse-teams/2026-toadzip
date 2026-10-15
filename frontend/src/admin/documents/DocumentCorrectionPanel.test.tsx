import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { Link, MemoryRouter } from 'react-router'
import { DocumentCorrectionPanel } from './DocumentCorrectionPanel'
import { ManagementError } from '../management/api'
import type { ManagementDetailData } from '../management/managementContract'
const mocks = vi.hoisted(() => ({ openPdf: vi.fn(), requestManagementApi: vi.fn(), destroy: vi.fn(), extract: vi.fn() }))
vi.mock('./pdfExtraction', async original => ({ ...await original<typeof import('./pdfExtraction')>(), openPdf: mocks.openPdf }))
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(), requestManagementApi: mocks.requestManagementApi }))
const detail: ManagementDetailData = {
  summary: { id: 7, name: '두꺼비', subtitle: '서울', provider: 'LH', rental: 'HAPPY_HOUSING', deleted: false, modified: false, reviewRequired: false, updatedAt: null },
  sourceIdentifier: 'TEST-7', data: { version: 2, name: '두꺼비', totalHouseholdCount: 10, totalParkingCount: 0 },
  housingTypes: [], announcements: [], supplyRows: [], schedules: [], scheduleReviewed: false,
}
const document = { fileName: '공고.pdf', sha256: 'a'.repeat(64), totalPages: 3,
  pages: [{ page: 2, text: '전체 120세대 주차대수 5대', items: [], requiresOcr: false }] }
const proposals = [
  { field: 'totalHouseholdCount', value: 120, page: 2, quote: '전체 120세대', unit: '세대', scope: '단지 전체', reason: '전체 세대수 보완' },
  { field: 'totalParkingCount', value: 5, page: 2, quote: '주차대수 5대', unit: '대', scope: '단지 전체', reason: '주차대수 보완' },
]
const json = JSON.stringify({ schemaVersion: 'document-review/v1', targetKey: 'complexes:7:basic', version: 2, sha256: document.sha256, proposals, unresolved: [] })
beforeEach(() => {
  vi.clearAllMocks()
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: vi.fn(() => 'blob:test') })
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
  mocks.extract.mockResolvedValue(document); mocks.destroy.mockResolvedValue(undefined)
  mocks.openPdf.mockResolvedValue({ fileName: '공고.pdf', sha256: document.sha256, totalPages: 3, extract: mocks.extract, destroy: mocks.destroy })
  mocks.requestManagementApi.mockResolvedValue(detail)
})
afterEach(() => vi.restoreAllMocks())
async function setup(withNavigation = false) {
  const onSaved = vi.fn(); const onReload = vi.fn()
  const view = render(<MemoryRouter><DocumentCorrectionPanel resource="complexes" detail={detail} onSaved={onSaved} onReload={onReload} onBusyChange={vi.fn()} />{withNavigation ? <Link to="/admin/complexes">목록으로 돌아가기</Link> : null}</MemoryRouter>)
  fireEvent.change(screen.getByLabelText('공식 출처 URL'), { target: { value: 'https://example.com/notice.pdf' } })
  fireEvent.change(screen.getByLabelText('공식 PDF 파일'), { target: { files: [new File(['%PDF-'], '공고.pdf')] } })
  fireEvent.click(await screen.findByRole('button', { name: '선택 페이지 추출' }))
  await screen.findByRole('heading', { name: '2. 추출 근거 확인' })
  return { ...view, onSaved, onReload }
}
async function imported() {
  const context = await setup()
  fireEvent.click(screen.getByRole('button', { name: '에이전트 결과 가져오기' }))
  fireEvent.change(screen.getByLabelText('에이전트 결과 JSON'), { target: { value: json } })
  fireEvent.click(screen.getByRole('button', { name: '결과 검증' }))
  return context
}
it('원문 확인과 메모가 있어야 선택한 값만 저장한다', async () => {
  const { onSaved } = await imported()
  expect(screen.getByRole('button', { name: '확인한 0개 항목 반영' })).toBeDisabled()
  fireEvent.click(screen.getByRole('checkbox', { name: '전체 세대수 원문 확인' }))
  expect(screen.getByRole('button', { name: '확인한 1개 항목 반영' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('확인 메모'), { target: { value: '원문과 전체 세대수 확인' } })
  fireEvent.click(screen.getByRole('button', { name: '확인한 1개 항목 반영' }))
  await waitFor(() => expect(onSaved).toHaveBeenCalledOnce())
  expect(mocks.requestManagementApi).toHaveBeenCalledWith('/api/admin/housing-complexes/7', 'PUT', expect.objectContaining({
    version: 2, totalHouseholdCount: 120, totalParkingCount: 0,
    documentEvidence: expect.objectContaining({ citations: [{ field: 'totalHouseholdCount', page: 2, quote: '전체 120세대' }] }),
  }))
})
it('충돌이면 입력을 보존하고 새로 조회하기 전에는 재반영하지 않는다', async () => {
  mocks.requestManagementApi.mockRejectedValue(new ManagementError('다른 작업에서 변경했습니다.', 409))
  const { onReload } = await imported()
  fireEvent.click(screen.getByRole('checkbox', { name: '전체 세대수 원문 확인' }))
  fireEvent.change(screen.getByLabelText('확인 메모'), { target: { value: '원문 확인' } })
  fireEvent.click(screen.getByRole('button', { name: '확인한 1개 항목 반영' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('다른 작업에서 변경했습니다.')
  expect(screen.getByLabelText('에이전트 결과 JSON')).toHaveValue(json)
  expect(screen.getByRole('button', { name: '확인한 1개 항목 반영' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '현재 데이터 다시 조회' }))
  expect(onReload).toHaveBeenCalledOnce()
})
it('수기 입력도 원문 인용을 확인하며 수정안을 만들 수 있다', async () => {
  await setup()
  fireEvent.click(screen.getByText('원문을 확인하며 수기 수정안 추가'))
  fireEvent.change(screen.getByLabelText('수정 항목'), { target: { value: 'totalHouseholdCount' } })
  fireEvent.change(screen.getByLabelText('수정할 값'), { target: { value: '120' } })
  fireEvent.change(screen.getByLabelText('근거 쪽수'), { target: { value: '2' } })
  fireEvent.change(screen.getByLabelText('원문 인용'), { target: { value: '전체 120세대' } })
  fireEvent.change(screen.getByLabelText('변경 이유'), { target: { value: '전체 세대수 확인' } })
  fireEvent.submit(screen.getByRole('button', { name: '수기 수정안 추가' }).closest('form')!)
  expect(screen.getByRole('checkbox', { name: '전체 세대수 원문 확인' })).not.toBeChecked()
  expect(mocks.requestManagementApi).not.toHaveBeenCalled()
})
it('잘못된 근거 결과는 검토 항목을 만들지 않는다', async () => {
  await setup()
  fireEvent.click(screen.getByRole('button', { name: '에이전트 결과 가져오기' }))
  fireEvent.change(screen.getByLabelText('에이전트 결과 JSON'), { target: { value: json.replace('전체 120세대', '없는 문장') } })
  fireEvent.click(screen.getByRole('button', { name: '결과 검증' }))
  expect(screen.getByRole('alert')).toHaveTextContent('원문 인용')
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
})
it('추출 후 직접 입력을 먼저 보여주고 에이전트 결과는 선택해서 가져온다', async () => {
  await setup()
  expect(screen.getByText(/자료 다시 선택/).closest('details')).not.toHaveAttribute('open')
  expect(screen.getByLabelText('수정할 값')).toBeVisible()
  fireEvent.change(screen.getByLabelText('수정할 값'), { target: { value: '120' } })
  expect(screen.queryByLabelText('에이전트 결과 JSON')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '에이전트 결과 가져오기' }))
  expect(screen.getByLabelText('에이전트 결과 JSON')).toBeVisible()
  expect(screen.getByLabelText('수정할 값')).not.toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '직접 입력' }))
  expect(screen.getByLabelText('수정할 값')).toHaveValue('120')
})
it('수기 수정안을 추가하기 전에도 이동을 취소하면 작성한 값을 보존한다', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  await setup(true)
  fireEvent.change(screen.getByLabelText('수정할 값'), { target: { value: '120' } })
  fireEvent.click(screen.getByRole('link', { name: '목록으로 돌아가기' }))
  expect(confirm).toHaveBeenCalledOnce()
  expect(screen.getByLabelText('수정할 값')).toHaveValue('120')
})
it('유형과 설치 여부는 읽기 쉬운 선택지로 입력하고 미설치를 false로 저장한다', async () => {
  mocks.extract.mockResolvedValue({ ...document, pages: [{ page: 2, text: '엘리베이터 미설치', items: [], requiresOcr: false }] })
  const { onSaved } = await setup()
  fireEvent.change(screen.getByLabelText('수정 항목'), { target: { value: 'heatingType' } })
  expect(screen.getByRole('combobox', { name: '수정할 값' })).toBeVisible()
  expect(screen.getByRole('option', { name: '개별난방' })).toHaveValue('INDIVIDUAL')
  fireEvent.change(screen.getByLabelText('수정 항목'), { target: { value: 'hasElevator' } })
  fireEvent.change(screen.getByLabelText('수정할 값'), { target: { value: 'false' } })
  expect(screen.getByRole('option', { name: '미설치' })).toHaveValue('false')
  fireEvent.change(screen.getByLabelText('근거 쪽수'), { target: { value: '2' } })
  fireEvent.change(screen.getByLabelText('원문 인용'), { target: { value: '엘리베이터 미설치' } })
  fireEvent.change(screen.getByLabelText('변경 이유'), { target: { value: '공식 문서로 미설치 확인' } })
  fireEvent.submit(screen.getByRole('button', { name: '수기 수정안 추가' }).closest('form')!)
  fireEvent.click(screen.getByRole('checkbox', { name: '엘리베이터 원문 확인' }))
  fireEvent.change(screen.getByLabelText('확인 메모'), { target: { value: '미설치 원문 확인' } })
  fireEvent.click(screen.getByRole('button', { name: '확인한 1개 항목 반영' }))
  await waitFor(() => expect(onSaved).toHaveBeenCalledOnce())
  expect(mocks.requestManagementApi).toHaveBeenCalledWith('/api/admin/housing-complexes/7', 'PUT', expect.objectContaining({ hasElevator: false }))
})
it('화면을 닫으면 PDF와 미리보기 URL을 해제한다', async () => {
  const { unmount } = await setup(); unmount()
  expect(mocks.destroy).toHaveBeenCalledOnce()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:test')
})
it('임대조건의 조회 당시 값을 보내고 수집 충돌이면 수정안을 유지한다', async () => {
  const current = { target: '청년', supplyRank: null, supplyHouseholdCount: 5, reserveCount: null,
    rentalDeposit: '10000000', monthlyRent: '200000', convertedDeposit: null, applicationCondition: null }
  const targetDetail = { ...detail, supplyTargets: [{ id: 9, supplyRowId: 8, data: current }] }
  mocks.extract.mockResolvedValue({ ...document,
    pages: [{ page: 2, text: '임대보증금 12000000원', items: [], requiresOcr: false }] })
  mocks.requestManagementApi.mockRejectedValue(new ManagementError('임대조건이 변경되었습니다.', 409))
  const onSaved = vi.fn()
  render(<MemoryRouter><DocumentCorrectionPanel resource="announcements" detail={targetDetail}
    onSaved={onSaved} onReload={vi.fn()} onBusyChange={vi.fn()} /></MemoryRouter>)
  fireEvent.change(screen.getByLabelText('보완 대상'), { target: { value: 'target:9' } })
  fireEvent.change(screen.getByLabelText('공식 출처 URL'), { target: { value: 'https://example.com/notice.pdf' } })
  fireEvent.change(screen.getByLabelText('공식 PDF 파일'), { target: { files: [new File(['%PDF-'], '공고.pdf')] } })
  fireEvent.click(await screen.findByRole('button', { name: '선택 페이지 추출' }))
  await screen.findByRole('heading', { name: '2. 추출 근거 확인' })
  const targetJson = JSON.stringify({ schemaVersion: 'document-review/v1', targetKey: 'announcements:7:target:9',
    version: 2, sha256: document.sha256, unresolved: [], proposals: [{ field: 'rentalDeposit', value: '12000000',
      page: 2, quote: '임대보증금 12000000원', unit: '원', scope: '해당 공급대상', reason: '원문 확인' }] })
  fireEvent.click(screen.getByRole('button', { name: '에이전트 결과 가져오기' }))
  fireEvent.change(screen.getByLabelText('에이전트 결과 JSON'), { target: { value: targetJson } })
  fireEvent.click(screen.getByRole('button', { name: '결과 검증' }))
  fireEvent.click(screen.getByRole('checkbox', { name: '임대보증금 원문 확인' }))
  fireEvent.change(screen.getByLabelText('확인 메모'), { target: { value: '보증금 확인' } })
  fireEvent.click(screen.getByRole('button', { name: '확인한 1개 항목 반영' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('임대조건이 변경되었습니다.')
  expect(mocks.requestManagementApi).toHaveBeenCalledWith('/api/admin/announcements/7/supply-targets/9', 'PUT',
    expect.objectContaining({ rentalDeposit: '12000000', monthlyRent: '200000', expectedValues: current }))
  expect(screen.getByLabelText('에이전트 결과 JSON')).toHaveValue(targetJson)
  expect(screen.getByRole('button', { name: '확인한 1개 항목 반영' })).toBeDisabled()
  expect(onSaved).not.toHaveBeenCalled()
})
