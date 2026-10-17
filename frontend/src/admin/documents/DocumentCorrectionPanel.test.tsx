import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { Link, MemoryRouter } from 'react-router'
import { DocumentCorrectionPanel } from './DocumentCorrectionPanel'
import { ManagementError } from '../management/api'
import type { ManagementDetailData } from '../management/managementContract'
const mocks = vi.hoisted(() => ({ openPdf: vi.fn(), requestManagementApi: vi.fn(), destroy: vi.fn(), extract: vi.fn() }))
const work = vi.hoisted(() => ({ readReviewDraft: vi.fn(), saveReviewDraft: vi.fn(), removeReviewDraft: vi.fn(), ocr: vi.fn() }))
const exchange = vi.hoisted(() => ({ result: '' }))
vi.mock('./FolderExchangePanel', () => ({ FolderExchangePanel: ({ onResult }: { onResult: (text: string) => void }) => <button onClick={() => onResult(exchange.result)}>자동 결과 가져오기</button> }))
vi.mock('./reviewDraftStore', () => work)
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
  mocks.openPdf.mockResolvedValue({ fileName: '공고.pdf', sha256: document.sha256, totalPages: 3, extract: mocks.extract, destroy: mocks.destroy, ocr: work.ocr })
  work.saveReviewDraft.mockResolvedValue(undefined)
  mocks.requestManagementApi.mockResolvedValue(detail)
})
afterEach(() => vi.restoreAllMocks())

it('폴더 대기 종료 뒤 받은 교환 결과 파일도 수동으로 검증해 가져올 수 있다', async () => {
  await setup()
  fireEvent.click(screen.getByRole('button', { name: '에이전트 결과 가져오기' }))
  const file = Object.assign(new File(['result'], 'review.result.json', { type: 'application/json' }), {
    text: async () => JSON.stringify({ schemaVersion: 'document-exchange/v1', requestId: 'late', result: JSON.parse(json) as unknown }),
  })
  fireEvent.change(screen.getByLabelText('에이전트 결과 JSON 파일'), { target: { files: [file] } })
  expect(await screen.findByRole('heading', { name: '원문과 대조 후 선택 반영' })).toBeVisible()
  expect(screen.getByRole('checkbox', { name: '전체 세대수 원문 확인' })).not.toBeChecked()
  expect(mocks.requestManagementApi).not.toHaveBeenCalled()
})

it('기존 수정안과 새 결과를 비교해 선택한 항목만 교체하고 이전 입력을 복원한다', async () => {
  mocks.extract.mockResolvedValue({ ...document, pages: [{ ...document.pages[0], text: document.pages[0].text + ' 정정 전체 130세대' }] })
  await imported()
  fireEvent.change(screen.getByLabelText('확인 메모'), { target: { value: '기존 검토 메모' } })
  fireEvent.click(screen.getByRole('checkbox', { name: '전체 세대수 원문 확인' }))
  fireEvent.click(screen.getByRole('button', { name: '수정안 계속 작성' }))
  exchange.result = JSON.stringify({ ...JSON.parse(json) as object, proposals: [{ ...proposals[0], value: 130, quote: '전체 130세대' }] })
  fireEvent.click(screen.getByRole('button', { name: '자동 결과 가져오기' }))
  expect(screen.getByRole('region', { name: '가져온 수정안 비교' })).toBeVisible()
  expect(screen.getByRole('region', { name: '가져온 수정안 비교' })).toHaveFocus()
  expect(screen.getByRole('button', { name: '선택한 0개 수정안 추가·교체' })).toBeDisabled()
  expect(mocks.requestManagementApi).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('checkbox', { name: '전체 세대수 가져오기' }))
  fireEvent.click(screen.getByRole('button', { name: '선택한 1개 수정안 추가·교체' }))
  expect(screen.getByRole('button', { name: /3\s*확인·저장/ })).toHaveFocus()
  expect(screen.getByRole('checkbox', { name: '전체 세대수 원문 확인' })).not.toBeChecked()
  expect(screen.getByRole('checkbox', { name: '주차대수 원문 확인' })).not.toBeChecked()
  expect(JSON.parse((screen.getByLabelText('에이전트 결과 JSON') as HTMLTextAreaElement).value).proposals).toEqual(expect.arrayContaining([
    expect.objectContaining({ field: 'totalHouseholdCount', value: 130 }), expect.objectContaining({ field: 'totalParkingCount', value: 5 }),
  ]))
  fireEvent.click(screen.getByRole('button', { name: '가져오기 전 입력 복원' }))
  expect(screen.getByLabelText('에이전트 결과 JSON')).toHaveValue(json)
  expect(screen.getByLabelText('확인 메모')).toHaveValue('기존 검토 메모')
  expect(screen.getByRole('checkbox', { name: '전체 세대수 원문 확인' })).not.toBeChecked()
  expect(mocks.requestManagementApi).not.toHaveBeenCalled()
})

it('보류 결과의 전체 교체도 명시적으로 선택하고 취소하면 기존 수정안을 유지한다', async () => {
  await imported()
  fireEvent.click(screen.getByRole('button', { name: '수정안 계속 작성' }))
  exchange.result = JSON.stringify({ ...JSON.parse(json) as object, proposals: [], unresolved: [{ field: 'totalHouseholdCount', reason: '적용 범위 불확실' }] })
  fireEvent.click(screen.getByRole('button', { name: '자동 결과 가져오기' }))
  fireEvent.click(screen.getByRole('button', { name: '이번 결과 가져오기 취소' }))
  expect(screen.getByLabelText('에이전트 결과 JSON')).toHaveValue(json)
  fireEvent.click(screen.getByRole('button', { name: '자동 결과 가져오기' }))
  fireEvent.click(screen.getByRole('button', { name: '가져온 결과로 전체 교체' }))
  expect(screen.getByText('반영할 확정 수정안이 없습니다.')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '가져오기 전 입력 복원' }))
  expect(screen.getByRole('checkbox', { name: '전체 세대수 원문 확인' })).not.toBeChecked()
  expect(mocks.requestManagementApi).not.toHaveBeenCalled()
})

it('잘못된 결과 파일은 기존 수정안·메모를 지우지 않고 올바른 파일만 비교한다', async () => {
  await imported()
  fireEvent.change(screen.getByLabelText('확인 메모'), { target: { value: '보존할 메모' } })
  fireEvent.click(screen.getByRole('button', { name: '수정안 계속 작성' }))
  fireEvent.change(screen.getByLabelText('에이전트 결과 JSON 파일'), { target: { files: [Object.assign(new File(['bad'], 'bad.json'), { text: async () => 'bad' })] } })
  expect(await screen.findByRole('alert')).toHaveTextContent('결과 파일을 읽지 못했습니다')
  expect(screen.getByLabelText('에이전트 결과 JSON')).toHaveValue(json)
  const result = JSON.stringify({ ...JSON.parse(json) as object, proposals: [proposals[1]] })
  fireEvent.change(screen.getByLabelText('에이전트 결과 JSON 파일'), { target: { files: [Object.assign(new File([result], 'result.json'), { text: async () => result })] } })
  expect(await screen.findByRole('region', { name: '가져온 수정안 비교' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '가져온 결과로 전체 교체' }))
  fireEvent.click(screen.getByRole('button', { name: '가져오기 전 입력 복원' }))
  expect(screen.getByLabelText('확인 메모')).toHaveValue('보존할 메모')
  expect(screen.getByLabelText('에이전트 결과 JSON')).toHaveValue(json)
})

it('JSON 붙여넣기도 기존 수정안과 비교하고 취소하면 원래 입력을 유지한다', async () => {
  await imported()
  fireEvent.change(screen.getByLabelText('확인 메모'), { target: { value: '원래 메모' } })
  fireEvent.click(screen.getByRole('button', { name: '수정안 계속 작성' }))
  fireEvent.change(screen.getByLabelText('에이전트 결과 JSON'), { target: { value: JSON.stringify({ ...JSON.parse(json) as object, proposals: [proposals[1]] }) } })
  fireEvent.click(screen.getByRole('button', { name: '결과 검증' }))
  expect(screen.getByRole('region', { name: '가져온 수정안 비교' })).toHaveFocus()
  fireEvent.click(screen.getByRole('button', { name: '이번 결과 가져오기 취소' }))
  expect(screen.getByLabelText('에이전트 결과 JSON')).toHaveValue(json)
  expect(screen.getByLabelText('확인 메모')).toHaveValue('원래 메모')
  expect(mocks.requestManagementApi).not.toHaveBeenCalled()
})

it('명시적으로 보관할 때만 원본과 작업을 보관하고 DB 수정 API는 호출하지 않는다', async () => {
  await setup()
  expect(work.saveReviewDraft).not.toHaveBeenCalled()
  fireEvent.click(screen.getByText('원본·작업 보관 및 이어서 하기'))
  fireEvent.click(screen.getByRole('button', { name: '원본과 작업 보관' }))
  await waitFor(() => expect(work.saveReviewDraft).toHaveBeenCalledWith(expect.objectContaining({
    key: 'complexes:7:basic', version: 2, file: expect.any(File), sha256: document.sha256,
    sourceUrl: 'https://example.com/notice.pdf',
  })))
  expect(mocks.requestManagementApi).not.toHaveBeenCalled()
})

it('보관 작업을 복원하면 PDF를 다시 추출하고 예전 선택 확인은 복원하지 않는다', async () => {
  work.readReviewDraft.mockResolvedValue({ key: 'complexes:7:basic', resource: 'complexes', id: 7, selection: 'basic', version: 1,
    file: new Blob(['%PDF-']), fileName: '공고.pdf', sha256: document.sha256, pages: '2',
    sourceUrl: 'https://example.com/notice.pdf', json, note: '보관 메모' })
  await setup()
  fireEvent.click(screen.getByText('원본·작업 보관 및 이어서 하기'))
  fireEvent.click(screen.getByRole('button', { name: '보관 작업 이어서 하기' }))
  expect(await screen.findByText(/작업 이후 DB 버전이 바뀌었습니다/)).toBeVisible()
  expect(mocks.extract).toHaveBeenLastCalledWith('2', expect.any(AbortSignal))
  expect(screen.queryByRole('heading', { name: '원문과 대조 후 선택 반영' })).not.toBeInTheDocument()
  expect(mocks.requestManagementApi).not.toHaveBeenCalled()
})

it('텍스트 없는 페이지를 OCR 처리하고 인식 점수와 원문 대조 필요를 안내한다', async () => {
  mocks.extract.mockResolvedValue({ ...document, pages: [{ page: 2, text: '', items: [], requiresOcr: true }] })
  work.ocr.mockResolvedValue({ ...document, pages: [{ ...document.pages[0], method: 'ocr', confidence: 72 }] })
  await setup()
  fireEvent.click(screen.getByRole('button', { name: '텍스트 없는 선택 페이지 OCR · 최대 5쪽' }))
  expect(await screen.findByText(/OCR 추정 텍스트 · 인식 점수 72/)).toBeVisible()
  expect(work.ocr).toHaveBeenCalledWith('1', expect.any(AbortSignal), expect.any(Function))
  expect(mocks.requestManagementApi).not.toHaveBeenCalled()
})

it('보관 PDF 추출이 실패하면 복원 중인 문서만 해제하고 기존 문서를 유지한다', async () => {
  await setup()
  const release = vi.fn().mockResolvedValue(undefined)
  work.readReviewDraft.mockResolvedValue({ key: 'complexes:7:basic', resource: 'complexes', id: 7, selection: 'basic',
    version: 2, file: new Blob(['%PDF-']), fileName: '보관.pdf', sha256: document.sha256, pages: '2' })
  mocks.openPdf.mockResolvedValueOnce({ sha256: document.sha256, destroy: release,
    extract: vi.fn().mockRejectedValue(new Error('보관 문서를 읽지 못했습니다.')) })
  fireEvent.click(screen.getByText('원본·작업 보관 및 이어서 하기'))
  fireEvent.click(screen.getByRole('button', { name: '보관 작업 이어서 하기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('보관 문서를 읽지 못했습니다.')
  await waitFor(() => expect(release).toHaveBeenCalledOnce())
  expect(mocks.destroy).not.toHaveBeenCalled()
  expect(screen.getByRole('heading', { name: '추출한 근거' })).toBeVisible()
})

async function setup(withNavigation = false) {
  const onSaved = vi.fn(); const onReload = vi.fn()
  const view = render(<MemoryRouter><DocumentCorrectionPanel resource="complexes" detail={detail} onSaved={onSaved} onReload={onReload} onBusyChange={vi.fn()} />{withNavigation ? <Link to="/admin/complexes">목록으로 돌아가기</Link> : null}</MemoryRouter>)
  fireEvent.change(screen.getByLabelText('공식 출처 URL'), { target: { value: 'https://example.com/notice.pdf' } })
  fireEvent.change(screen.getByLabelText('공식 PDF 파일'), { target: { files: [new File(['%PDF-'], '공고.pdf')] } })
  fireEvent.click(await screen.findByRole('button', { name: '선택 페이지 추출' }))
  await screen.findByRole('heading', { name: '추출한 근거' })
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
  expect(screen.getByLabelText('공식 PDF 파일')).not.toBeVisible()
  expect(screen.getByRole('button', { name: /근거·수정안/ })).toHaveAttribute('aria-current', 'step')
  expect(screen.getByLabelText('수정할 값')).toBeVisible()
  fireEvent.change(screen.getByLabelText('수정할 값'), { target: { value: '120' } })
  expect(screen.queryByLabelText('에이전트 결과 JSON')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '에이전트 결과 가져오기' }))
  expect(screen.getByLabelText('에이전트 결과 JSON')).toBeVisible()
  expect(screen.getByLabelText('수정할 값')).not.toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '직접 입력' }))
  expect(screen.getByLabelText('수정할 값')).toHaveValue('120')
})

it('단계를 오가도 수정안 입력은 보존하고 최종 확인 전에는 저장을 노출하지 않는다', async () => {
  await setup()
  fireEvent.change(screen.getByLabelText('수정할 값'), { target: { value: '120' } })
  fireEvent.click(screen.getByRole('button', { name: '자료 다시 선택' }))
  expect(screen.getByLabelText('공식 PDF 파일')).toBeVisible()
  expect(screen.getByLabelText('수정할 값')).not.toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: /근거·수정안/ }))
  expect(screen.getByLabelText('수정할 값')).toHaveValue('120')
  expect(screen.queryByRole('button', { name: /항목 반영/ })).not.toBeInTheDocument()
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
  await screen.findByRole('heading', { name: '추출한 근거' })
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
