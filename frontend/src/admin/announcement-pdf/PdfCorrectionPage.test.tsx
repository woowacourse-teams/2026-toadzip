import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, expect, it, onTestFinished, vi } from 'vitest'
import { PdfCorrectionPage, PdfCorrectionWorkspace } from './PdfCorrectionPage'
import { scenario } from './fixtures/workspace'
import fixtures from './fixtures/scenarios.json'
import type { JsonUpdateInput } from '../announcement-json/api'
import { ManagementError } from '../management/api'
const api = vi.hoisted(() => ({ loadJsonWorkspace: vi.fn(), previewJsonUpdate: vi.fn(), applyJsonUpdate: vi.fn() }))
vi.mock('../announcement-json/api', () => api)
const registration = vi.hoisted(() => ({ createHousingComplex: vi.fn() }))
vi.mock('../registration/api', async original => ({ ...await original<typeof import('../registration/api')>(), ...registration }))
const lookup = vi.hoisted(() => ({ lookupAddresses: vi.fn(async () => []) }))
vi.mock('./addressLookupApi', () => lookup)
const pdfTypes = vi.hoisted(() => ({ createPdfHousingTypes: vi.fn(async () => {}) }))
vi.mock('./pdfHousingTypes', () => pdfTypes)
const extractor = vi.hoisted(() => ({ extractAnnouncementPdf: vi.fn() }))
vi.mock('./extractionApi', async original => ({ ...await original<typeof import('./extractionApi')>(), ...extractor }))
const matchingTypes = vi.hoisted(() => ({ getMatchingHousingTypes: vi.fn() }))
vi.mock('../ingest/supplyMatchingApi', () => matchingTypes)
vi.mock('../../public-housing/api/announcementAttachments', () => ({ loadAnnouncementAttachment: async () => new Blob(), attachmentErrorMessage: () => 'PDF 열기 실패' }))
vi.mock('../../public-housing/components/PdfDocumentPreview', () => ({ default: ({ focus }: { focus: { page: number; query: string } | null }) => <p>PDF {focus?.page ?? 1}쪽 · 강조 {focus?.query || '없음'}</p> }))
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(),
  getManagementPage: async () => ({ items: [{ id: 7, name: '검색 단지', subtitle: '주소 확인' }] }),
  getManagementDetail: async () => ({ data: { name: '익산한스빌', rentalType: 'NATIONAL_RENTAL', address: { pnu: '1114010100100010000' } } }) }))
const base = scenario('hansville').workspace
const workspace = { ...base, detail: { ...base.detail, data: { ...base.detail.data, name: '익산한스빌 국민임대주택 예비입주자 자격완화 모집 공고(2026.10.08)', postedDate: '2026-10-08' }, attachments: [{ attachmentId: 68, fileName: '공고문.pdf', fileType: 'ANNOUNCEMENT', fileUrl: 'https://example.com/notice.pdf' }] } }
beforeEach(() => {
  vi.clearAllMocks()
  matchingTypes.getMatchingHousingTypes.mockImplementation(async (id: number) => fixtures.hansville.complexes.find(item => item.id === id)?.housingTypes ?? [])
  vi.stubGlobal('URL', class extends URL { static createObjectURL = () => 'blob:pdf'; static revokeObjectURL = vi.fn() })
  api.loadJsonWorkspace.mockResolvedValue(workspace)
  api.applyJsonUpdate.mockResolvedValue(workspace)
  api.previewJsonUpdate.mockResolvedValue({ confirmationToken: 'confirmed' })
  extracts(JSON.stringify(fixtures.hansville.extraction))
})
/** 서버 추출 API가 돌려줄 JSON을 정한다. 실제 모델은 부르지 않는다. */
function extracts(json: string) {
  extractor.extractAnnouncementPdf.mockResolvedValue({ json, pages: [1, 2], totalPages: 3, warnings: [], usage: { promptTokens: 100, outputTokens: 50, thinkingTokens: null } })
}
afterEach(() => vi.unstubAllGlobals())
function renderPage() {
  render(<MemoryRouter initialEntries={['/admin/announcements/29/pdf-correction?failureReturnTo=%2Fadmin%2Ffailures%3Fdomain%3Dannouncement&section=verification']}><Routes><Route path="/admin/announcements/:id/pdf-correction" element={<PdfCorrectionPage />} /></Routes></MemoryRouter>)
}
/** 테스트 화면은 좁은 화면처럼 한 패널만 보이므로 PDF 영역을 연다. */
function showPdf() {
  fireEvent.click(screen.getByRole('button', { name: 'PDF 보기' }))
}
async function startExtraction() {
  fireEvent.click(await screen.findByRole('button', { name: 'PDF에서 값 추출' }))
}
async function review(start = true) {
  if (start) await startExtraction()
  await waitFor(() => expect(screen.getByRole('button', { name: '추출 결과 검토하기' })).toBeEnabled(), { timeout: 3000 })
  fireEvent.click(screen.getByRole('button', { name: '추출 결과 검토하기' }))
}
async function open(text = JSON.stringify(fixtures.hansville.extraction)) {
  extracts(text)
  renderPage()
  await review()
  await screen.findByRole('heading', { name: '공급정보' })
}
function manual(scope: string, label: string) {
  fireEvent.click(within(screen.getByRole('group', { name: `${scope} ${label} 반영 방법` })).getByRole('button', { name: '직접 수정' }))
}
it('추가 확인 체크나 미리보기 화면 없이 서버 검증을 마친 같은 보정안만 한 번 저장한다', async () => {
  let finish!: (value: { confirmationToken: string }) => void
  api.previewJsonUpdate.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  await open()
  expect(screen.queryByRole('checkbox', { name: /확인함|대조함/ })).not.toBeInTheDocument()
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  expect(screen.getByRole('button', { name: '적용 중…' })).toBeDisabled()
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
  const input = api.previewJsonUpdate.mock.calls[0][1] as JsonUpdateInput
  expect(input.correction.newGroups).toEqual([expect.objectContaining({ sourceRowId: 90, sharedHouseholdCount: 170 })])
  await act(async () => finish({ confirmationToken: 'confirmed' }))
  await screen.findByRole('heading', { name: '공고 보정을 적용했습니다.' })
  expect(api.applyJsonUpdate).toHaveBeenCalledExactlyOnceWith('29', input, 'confirmed')
  expect(screen.queryByRole('heading', { name: '적용할 변경' })).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '실패 검토로 돌아가기' })).toHaveAttribute('href', '/admin/failures?domain=announcement')
  expect(screen.getByRole('link', { name: '저장된 공급정보 확인' }).getAttribute('href')).toContain('section=relations')
  expect(screen.getByRole('link', { name: '출처·수정 이력 보기' }).getAttribute('href')).toContain('section=history')
  expect(within(screen.getByRole('region', { name: '단지 주택형 이름 후보' })).getAllByRole('listitem').map(item => item.textContent)).toEqual(['#10353 「26」 → 공고 표기 「26A」', '#10352 「26」 → 공고 표기 「26B」'])
})
it('저장할 위치가 없는 공급정보는 오류를 알리고 서버에 보내지 않는다', async () => {
  await open()
  fireEvent.change(screen.getByRole('combobox', { name: '공급행 1 저장할 위치' }), { target: { value: '' } })
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('기존 공급정보를 선택')
  expect(api.previewJsonUpdate).not.toHaveBeenCalled()
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
})
it('형식 오류와 셀 수정·조건 제외를 제공하고 PDF 쪽 이동은 강조 없이 한다', async () => {
  await open()
  const row = screen.getByRole('region', { name: '공급행 1 검토' })
  fireEvent.click(within(row).getByRole('button', { name: '조건 1 대조·수정' }))
  manual('공급행 1 조건 1', '보증금 (원)')
  const input = screen.getByRole('textbox', { name: '공급행 1 조건 1 보증금 (원) 수정' })
  fireEvent.change(input, { target: { value: '4,076,000' } })
  expect(input).toHaveAttribute('aria-invalid', 'true')
  expect(within(screen.getByRole('group', { name: '공급행 1 조건 1 보증금 (원) 반영 방법' })).getByRole('button', { name: '직접 수정' })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.change(input, { target: { value: '4076000' } })
  const details = screen.getByRole('region', { name: '공급행 1 조건 1 수정' })
  fireEvent.click(within(details).getAllByRole('button', { name: 'PDF 5쪽 보기' })[0])
  expect(await screen.findByText('PDF 5쪽 · 강조 없음')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '검토표 보기' }))
  fireEvent.change(screen.getByRole('combobox', { name: '공급행 1 조건 1 저장 방법' }), { target: { value: 'exclude' } })
  expect(screen.getByText('이 조건은 적용하지 않습니다. 저장된 기존 조건은 유지합니다.')).toBeVisible()
})
it.each(['preview', 'apply'])('%s의 409는 저장을 반복하지 않고 최신값을 조회해 수동 입력을 보존한다', async stage => {
  const failed = stage === 'preview' ? api.previewJsonUpdate : api.applyJsonUpdate
  failed.mockRejectedValueOnce(new ManagementError('현재값이 변경되었습니다.', 409))
  await open()
  manual('공급행 1', '이 공고의 주택형 표기')
  fireEvent.change(screen.getByRole('textbox', { name: '공급행 1 이 공고의 주택형 표기 수정' }), { target: { value: '26A 수동 보정' } })
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  const reload = await screen.findByRole('button', { name: '입력을 유지하고 최신값 비교' })
  expect(screen.getByRole('button', { name: '보정 내용 적용' })).toBeDisabled()
  if (stage === 'preview') expect(api.applyJsonUpdate).not.toHaveBeenCalled()
  fireEvent.click(reload)
  await screen.findByRole('status')
  expect(screen.getByRole('textbox', { name: '공급행 1 이 공고의 주택형 표기 수정' })).toHaveValue('26A 수동 보정')
  expect(screen.getByRole('button', { name: '보정 내용 적용' })).toBeEnabled()
})
it('저장 응답을 잃으면 자동 재적용하지 않고 최신값 확인을 요구한다', async () => {
  api.applyJsonUpdate.mockRejectedValue(new TypeError('network'))
  await open()
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('저장 응답을 받지 못했습니다.')
  expect(screen.getByRole('button', { name: '보정 내용 적용' })).toBeDisabled()
  expect(api.applyJsonUpdate).toHaveBeenCalledOnce()
  expect(screen.getByRole('button', { name: '입력을 유지하고 최신값 비교' })).toBeEnabled()
})
it('저장 전 검증 요청 실패는 저장 응답 유실과 구분하고 재시도할 수 있다', async () => {
  api.previewJsonUpdate.mockRejectedValue(new TypeError('연결 실패'))
  await open()
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('연결 실패')
  expect(screen.getByRole('button', { name: '보정 내용 적용' })).toBeEnabled()
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
})
it('등록된 PDF를 자동으로 고르고 추출 버튼을 누를 때만 서버에서 추출한다', async () => {
  renderPage()
  await screen.findByRole('button', { name: 'PDF에서 값 추출' })
  expect(extractor.extractAnnouncementPdf).not.toHaveBeenCalled()
  await startExtraction()
  await waitFor(() => expect(extractor.extractAnnouncementPdf).toHaveBeenCalledOnce())
  expect(extractor.extractAnnouncementPdf).toHaveBeenCalledWith('29', expect.any(Blob), '공고문.pdf', expect.any(AbortSignal))
  showPdf()
  expect(screen.getByRole('combobox', { name: '공고문 PDF' })).toHaveAccessibleDescription('공고문.pdf')
  expect(screen.getByLabelText('내 PC에서 PDF 고르기')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '추출 보기' }))
  expect(await screen.findByText(/공급행 2개 · 임대조건 2개 · 일정 3개/)).toBeVisible()
  expect(screen.queryByRole('heading', { name: '공급정보' })).not.toBeInTheDocument()
})
it('추출 뒤 PDF를 바꾸면 이전 결과를 버리고 다시 추출하게 한다', async () => {
  renderPage()
  await startExtraction()
  expect(await screen.findByText(/공급행 2개 · 임대조건 2개 · 일정 3개/)).toBeVisible()
  fireEvent.change(screen.getByLabelText('내 PC에서 PDF 고르기'), { target: { files: [new File(['%PDF'], '정정 공고문.pdf', { type: 'application/pdf' })] } })
  expect(await screen.findByRole('button', { name: 'PDF에서 값 추출' })).toBeVisible()
  expect(screen.queryByText(/공급행 2개/)).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '추출 결과 검토하기' })).toBeDisabled()
  expect(extractor.extractAnnouncementPdf).toHaveBeenCalledOnce()
})
it('첨부가 여러 개면 종류가 공고문인 PDF를 먼저 골라 추출한다', async () => {
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, detail: { ...workspace.detail, attachments: [
    { attachmentId: 70, fileName: '신청서 양식.pdf', fileType: 'REFERENCE', fileUrl: 'https://example.com/form.pdf' },
    { attachmentId: 71, fileName: '모집 안내.pdf', fileType: 'ANNOUNCEMENT', fileUrl: 'https://example.com/notice.pdf' }] } })
  renderPage()
  await startExtraction()
  await waitFor(() => expect(extractor.extractAnnouncementPdf).toHaveBeenCalledWith('29', expect.any(Blob), '모집 안내.pdf', expect.any(AbortSignal)))
  showPdf()
  expect(screen.getByRole('combobox', { name: '공고문 PDF' })).toHaveAccessibleDescription('모집 안내.pdf')
})
it('준비 단계의 PDF 영역에 PDF를 끌어다 놓으면 그 PDF로 바꾼다', async () => {
  renderPage()
  await screen.findByRole('button', { name: 'PDF에서 값 추출' })
  showPdf()
  const pane = screen.getByRole('region', { name: 'PDF 근거' })
  const file = new File(['%PDF'], '끌어온 공고문.pdf', { type: 'application/pdf' })
  fireEvent.dragOver(pane, { dataTransfer: { files: [file] } })
  expect(pane).toHaveAttribute('data-dragging', 'true')
  fireEvent.drop(pane, { dataTransfer: { files: [file] } })
  expect(pane).toHaveAttribute('data-dragging', 'false')
  expect(screen.getByText('내 PC · 끌어온 공고문.pdf')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '추출 보기' }))
  await startExtraction()
  await waitFor(() => expect(extractor.extractAnnouncementPdf).toHaveBeenCalledWith('29', file, '끌어온 공고문.pdf', expect.any(AbortSignal)))
})
it('추출에 실패하면 이유를 알리고 다시 추출하면 검토를 이어 간다', async () => {
  extractor.extractAnnouncementPdf.mockRejectedValueOnce(new ManagementError('AI 모델 호출에 실패했습니다.', 502))
  renderPage()
  await startExtraction()
  expect(await screen.findByRole('alert')).toHaveTextContent('AI 모델 호출에 실패했습니다.')
  expect(screen.getByRole('button', { name: '추출 결과 검토하기' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '다시 추출' }))
  await review(false)
  await screen.findByRole('heading', { name: '공급정보' })
  expect(extractor.extractAnnouncementPdf).toHaveBeenCalledTimes(2)
})
it('연결된 PDF가 없으면 내 PC의 PDF를 골라야 추출과 검토를 진행한다', async () => {
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, detail: { ...workspace.detail, attachments: [] } })
  renderPage()
  fireEvent.click(await screen.findByRole('button', { name: 'PDF 보기' }))
  expect(await screen.findByText('연결된 PDF가 없습니다')).toBeVisible()
  expect(screen.getByRole('button', { name: '추출 결과 검토하기' })).toBeDisabled()
  expect(extractor.extractAnnouncementPdf).not.toHaveBeenCalled()
  const file = new File(['%PDF'], '금호빛여울채 공고문.pdf', { type: 'application/pdf' })
  fireEvent.change(screen.getByLabelText('내 PC에서 PDF 고르기'), { target: { files: [file] } })
  expect(screen.getByText('내 PC · 금호빛여울채 공고문.pdf')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '추출 보기' }))
  await startExtraction()
  await waitFor(() => expect(extractor.extractAnnouncementPdf).toHaveBeenCalledWith('29', file, '금호빛여울채 공고문.pdf', expect.any(AbortSignal)))
  await review(false)
  await screen.findByRole('heading', { name: '공급정보' })
  expect(document.querySelector('[data-pane=pdf]')).toHaveTextContent('금호빛여울채 공고문.pdf')
})
it('추출 결과를 읽지 못하면 검토를 시작하지 못하고 다시 추출하게 한다', async () => {
  extracts('{broken}')
  renderPage()
  await startExtraction()
  expect(await screen.findByRole('alert')).toHaveTextContent('JSON')
  expect(screen.getByRole('button', { name: '추출 결과 검토하기' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '다시 추출' })).toBeEnabled()
})
it('원문 URL이 없으면 근거 입력을 펼쳐 안내한다', async () => {
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, detail: { ...workspace.detail, data: { ...workspace.detail.data, originalUrl: null } } })
  await open()
  expect(screen.getByRole('status')).toHaveTextContent('공식 근거 URL에 올바른 http 또는 https 주소를 입력하세요.')
  expect(screen.getByRole('textbox', { name: '공식 근거 URL' })).toBeVisible()
  expect(screen.getByRole('button', { name: '보정 내용 적용' })).toBeDisabled()
  fireEvent.change(screen.getByRole('textbox', { name: '공식 근거 URL' }), { target: { value: 'https://example.com/official-notice' } })
  expect(screen.getByRole('button', { name: '보정 내용 적용' })).toBeEnabled()
})

it('확인 필요 항목을 세고 하단 버튼으로 다음 항목에 이동하며 상태로 거른다', async () => {
  const scroll = vi.fn()
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scroll })
  onTestFinished(() => { delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView })
  await open()
  expect(screen.getByRole('button', { name: '일정 · 확인 1' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '확인 필요 3개 남음 ↓' }))
  expect(screen.getByRole('region', { name: '공급행 1 검토' })).toHaveFocus()
  expect(scroll).toHaveBeenCalledWith({ block: 'start' })
  fireEvent.click(screen.getByRole('button', { name: '공급행 1 대응 확인' }))
  fireEvent.click(within(screen.getByRole('group', { name: '검토 항목 필터' })).getByRole('button', { name: '확인 필요 2' }))
  expect(screen.queryByRole('region', { name: '공급행 1 검토' })).not.toBeInTheDocument()
  expect(screen.getByRole('region', { name: '공급행 2 검토' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '공급행 2 대응 확인' }))
  expect(screen.getByText('이 영역에는 ‘확인 필요’ 항목이 없습니다.')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '확인 필요 1개 남음 ↓' }))
  expect(screen.getByRole('heading', { name: '일정' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '일정 2 적용 안 함' }))
  expect(screen.getByText('확인 필요 없음')).toBeVisible()
  fireEvent.click(within(screen.getByRole('group', { name: '검토 항목 필터' })).getByRole('button', { name: '전체' }))
  expect(screen.getByRole('checkbox', { name: '일정 2 적용' })).not.toBeChecked()
})

it('기준 단지는 원천 공급행이 아니라 단지명·임대유형으로 한 번씩 보여준다', async () => {
  const [first] = workspace.supply.rows
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, supply: { ...workspace.supply, rows: [first, { ...first, id: 91, key: 'row:91', typeName: '42.1900', displayOrder: 1 }] } })
  await open()
  fireEvent.change(screen.getByRole('combobox', { name: '공급행 1 저장할 위치' }), { target: { value: 'new' } })
  const anchor = screen.getByRole('combobox', { name: '공급행 1 기준 단지' })
  expect(within(anchor).getAllByRole('option').map(option => option.textContent)).toEqual(['다른 단지 찾기', '익산한스빌 · 국민임대'])
  expect(anchor).toHaveValue(first.key)
})

it('다른 공고의 PDF면 공고명·공고일이 다르다고 알리고 검토 화면에도 남긴다', async () => {
  extracts(JSON.stringify(fixtures.gwangju.extraction))
  renderPage()
  await startExtraction()
  expect(await screen.findByRole('alert')).toHaveTextContent('현재 공고와 다른 PDF일 수 있습니다 · 공고명 다름(PDF 광주첨단 H-1,2,3BL 행복주택 예비입주자 모집) · 공고일 다름(PDF 2026-10-15)')
  await review(false)
  expect(await screen.findByText(/이 공고의 PDF로 받은 응답인지 확인하세요/)).toBeVisible()
})

it('공급정보의 PDF 채우기는 작성 중인 행을 기준으로 대조하고 저장 없이 편집표에 돌려준다', async () => {
  const onFill = vi.fn()
  const draft = structuredClone(workspace.supply.rows)
  draft[0].moveInMonth = '2027-03'
  render(<MemoryRouter><PdfCorrectionWorkspace id="29" supplyDraft={{ rows: draft, onFill, onCancel: vi.fn() }} /></MemoryRouter>)
  await review()
  expect(screen.queryByRole('navigation', { name: 'PDF 보정 영역' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '공급정보 편집표에 채우기' }))
  await waitFor(() => expect(onFill).toHaveBeenCalledOnce())
  expect(onFill.mock.calls[0][0].rows[0]).toMatchObject({ id: draft[0].id, moveInMonth: '2027-03' })
  expect(onFill.mock.calls[0][0].newGroups).toHaveLength(1)
  expect(api.previewJsonUpdate).not.toHaveBeenCalled()
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
})

it('빈 공고의 PDF 추출값은 단지를 검색해 선택한 뒤 첫 공급행으로 채운다', async () => {
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, supply: { ...workspace.supply, rows: [] } })
  const onFill = vi.fn()
  const extraction = structuredClone(fixtures.hansville.extraction)
  extraction.data.supplyRows = extraction.data.supplyRows.slice(0, 1)
  extraction.sharedCounts = []
  extracts(JSON.stringify(extraction))
  render(<MemoryRouter><PdfCorrectionWorkspace id="29" supplyDraft={{ rows: [], onFill, onCancel: vi.fn() }} /></MemoryRouter>)
  await review()
  // PDF 단지명으로 미리 검색한 후보에서 고른다.
  fireEvent.click(await within(screen.getByRole('region', { name: 'PDF 단지 정하기' })).findByRole('button', { name: '선택: 검색 단지' }))
  expect(await screen.findByLabelText('공급행 1 소재지 PNU')).toHaveValue('1114010100100010000')
  fireEvent.change(screen.getByLabelText('공급행 1 공급 구분'), { target: { value: 'NEW_SUPPLY' } })
  fireEvent.click(screen.getByRole('button', { name: '공급정보 편집표에 채우기' }))
  await waitFor(() => expect(onFill).toHaveBeenCalledOnce())
  expect(onFill.mock.calls[0][0].rows).toEqual([expect.objectContaining({ id: null, housingComplexId: 7, category: 'NEW_SUPPLY', typeName: '26A' })])
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
})

it('실패 검토에서 열면 고칠 공급행과 오류 사유를 먼저 보여 준다', async () => {
  const [first] = workspace.supply.rows
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, supply: { ...workspace.supply, rowSources: [{ rowKey: first.key, category: 'MYHOME_ANNOUNCEMENT', sourceKey: '5:213951:1' }] } })
  render(<MemoryRouter initialEntries={['/admin/announcements/29/pdf-correction?failureCount=2&failureRow=AMBIGUOUS_HOUSING_TYPE%7C5%3A213951%3A1&failureRow=HOUSING_TYPE_NOT_FOUND%7C9%3A9%3A9&failureReturnTo=%2Fadmin%2Ffailures']}><Routes><Route path="/admin/announcements/:id/pdf-correction" element={<PdfCorrectionPage />} /></Routes></MemoryRouter>)
  const note = await screen.findByRole('note', { name: '고칠 오류' })
  expect(note).toHaveTextContent(`(#${String(first.id)}): 같은 면적의 주택형이 여러 개라 하나로 정하지 못했습니다`)
  expect(note).toHaveTextContent('원천 공급행 9:9:9: 맞는 주택형을 찾지 못했습니다')
  expect(screen.getByRole('link', { name: '공고 상세로 돌아가기' }).getAttribute('href')).not.toContain('failureRow')
})

it('응답 준비로 돌아갔다 와도 같은 PDF·응답이면 검토하던 입력을 이어 간다', async () => {
  await open()
  fireEvent.change(screen.getByLabelText('보정 이유'), { target: { value: '직접 고친 이유' } })
  fireEvent.click(screen.getByRole('button', { name: '응답 준비로 돌아가기' }))
  // 같은 PDF로 돌아오면 다시 추출하지 않는다.
  fireEvent.click(await screen.findByRole('button', { name: '추출 결과 검토하기' }))
  expect(await screen.findByLabelText('보정 이유')).toHaveValue('직접 고친 이유')
  expect(extractor.extractAnnouncementPdf).toHaveBeenCalledOnce()
  fireEvent.click(screen.getByRole('button', { name: '응답 준비로 돌아가기' }))
  extracts(JSON.stringify({ ...fixtures.hansville.extraction }, null, 1))
  fireEvent.click(await screen.findByRole('button', { name: '다시 추출' }))
  await review(false)
  expect(await screen.findByLabelText('보정 이유')).toHaveValue('공고문.pdf · AI 자동 추출 후 PDF와 대조')
})

it('단지에 맞는 주택형이 없으면 따로 알리고 PDF 형명·면적으로 새 주택형을 추가해 저장한다', async () => {
  const extraction = structuredClone(fixtures.hansville.extraction)
  extraction.data.supplyRows[0].exclusiveArea = 99.1234
  // 공통 모집 묶음에는 기존 주택형만 넣을 수 있으므로 묶음이 없는 공고로 확인한다.
  extraction.sharedCounts = []
  await open(JSON.stringify(extraction))
  const block = screen.getByRole('region', { name: '공급행 1 검토' })
  expect(within(block).getByText('맞는 주택형 없음')).toBeVisible()
  expect(within(block).getByText(/PDF 전용 99.1234㎡ 주택형이 없습니다/)).toBeVisible()
  fireEvent.click(within(block).getByRole('button', { name: 'PDF 형명·면적으로 새 주택형 추가' }))
  expect(within(block).getByText(/'26A · 전용 99.1234㎡' 주택형을 이 단지에 추가합니다/)).toBeVisible()
  expect(within(block).getByRole('combobox', { name: '공급행 1 연결 주택형 수정' })).toHaveValue('new')
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  await waitFor(() => expect(api.previewJsonUpdate).toHaveBeenCalled())
  const input = api.previewJsonUpdate.mock.calls[0][1] as JsonUpdateInput
  expect(input.correction.rows.find(row => row.typeName === '26A')).toMatchObject({ housingTypeId: null, newHousingType: { name: '26A', exclusiveArea: 99.1234 } })
})

it('적용할 수 없는 값이 있으면 오류를 알리고 그 항목으로 이동한다', async () => {
  const scroll = vi.fn()
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scroll })
  onTestFinished(() => { delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView })
  await open()
  manual('공급행 1', '이 공고의 주택형 표기')
  fireEvent.change(screen.getByLabelText('공급행 1 이 공고의 주택형 표기 수정'), { target: { value: '' } })
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  expect(await screen.findByText('이 공고의 주택형 표기의 직접 입력값을 확인하거나 기존 값 사용을 선택하세요.')).toBeVisible()
  await waitFor(() => expect(document.activeElement?.getAttribute('data-review-id')).toBe('cell:supplyRows[0].sourceHousingTypeName'))
  expect(scroll).toHaveBeenCalled()
  expect(api.previewJsonUpdate).not.toHaveBeenCalled()
})

it('대상들이 함께 쓰는 모집수는 안내만 하고 대상별 모집수는 비워 저장한다', async () => {
  const extraction = structuredClone(fixtures.hansville.extraction) as unknown as { data: { supplyRows: { targets: Record<string, unknown>[] }[] }; sharedCounts: unknown[] }
  extraction.data.supplyRows[0].targets.push({ ...extraction.data.supplyRows[0].targets[0], target: '청년' })
  extraction.sharedCounts = [{ paths: ['supplyRows[0].targets[0].supplyHouseholdCount', 'supplyRows[0].targets[1].supplyHouseholdCount'], count: 39, page: 3, quote: '대학생 청년 39' }]
  await open(JSON.stringify(extraction))
  expect(screen.getByText('26A에서 일반 · 청년 대상이 함께 쓰는 모집수입니다. 대상별 모집수는 비워 둡니다.')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  await waitFor(() => expect(api.previewJsonUpdate).toHaveBeenCalled())
  const input = api.previewJsonUpdate.mock.calls[0][1] as JsonUpdateInput
  expect(input.correction.newGroups ?? []).toEqual([])
  expect(input.correction.rows.find(row => row.typeName === '26A')?.targets.map(target => target.householdCount)).toEqual([null, null])
})

it('공고에는 있지만 단지 DB에 없는 단지는 공고문 단지 개요로 한 번 등록해 같은 단지 공급행 전체에 연결한다', async () => {
  registration.createHousingComplex.mockResolvedValue({ housingComplexId: 7, name: '익산한스빌', roadAddress: '전북특별자치도 익산시 목천동 1' })
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, supply: { ...workspace.supply, rows: [] } })
  const extraction = structuredClone(fixtures.hansville.extraction) as typeof fixtures.hansville.extraction & { data: { complexes?: unknown[] } }
  extraction.data.supplyRows = extraction.data.supplyRows.slice(0, 2); extraction.sharedCounts = []
  const name = extraction.data.supplyRows[0].complexReference.sourceComplexName
  extraction.data.supplyRows[1].complexReference.sourceComplexName = name
  extraction.data.complexes = [{ sourceComplexName: name, address: '전북특별자치도 익산시 목천동 1', totalHouseholdCount: 350, parkingCount: 200 }]
  await open(JSON.stringify(extraction))
  const decision = screen.getByRole('group', { name: `PDF 단지 ${name}` })
  expect(decision).toHaveTextContent('공급행 2개')
  fireEvent.click(within(decision).getByRole('button', { name: '찾는 단지가 없으면 새 단지로 등록' }))
  const form = within(decision).getByRole('group', { name: '공급행 1 새 단지 등록' })
  expect(within(form).getByLabelText('단지명')).toHaveValue(name)
  expect(within(form).getByLabelText('주소(도로명 또는 지번)')).toHaveValue('전북특별자치도 익산시 목천동 1')
  expect(within(form).getByLabelText('전체 세대수')).toHaveValue(350)
  expect(within(form).getByLabelText('주차대수')).toHaveValue(200)
  await waitFor(() => expect(lookup.lookupAddresses).toHaveBeenCalledWith('전북특별자치도 익산시 목천동 1', expect.any(AbortSignal)))
  expect(within(form).getByRole('button', { name: '단지 등록하고 연결' })).toBeDisabled()
  for (const [label, value] of [['PNU', '4514011100100010000'], ['위도', '35.95'], ['경도', '126.95']]) {
    fireEvent.change(within(form).getByLabelText(label), { target: { value } })
  }
  fireEvent.click(within(form).getByRole('button', { name: '단지 등록하고 연결' }))
  await waitFor(() => expect(registration.createHousingComplex).toHaveBeenCalledWith(expect.objectContaining({
    totalHouseholdCount: 350, totalParkingCount: 200, completionDate: null, heatingType: null,
    address: expect.objectContaining({ pnu: '4514011100100010000', legalDongCode: '4514011100', provinceCode: '45', cityCountyDistrictCode: '45140', latitude: 35.95 }) })))
  expect(await within(decision).findByText('연결 단지: 익산한스빌 · 같은 단지명의 공급행 2개에 연결했습니다.')).toBeVisible()
  // 새로 등록한 단지에는 공고문 주택형을 함께 만든다.
  expect(pdfTypes.createPdfHousingTypes).toHaveBeenCalledWith(7, [expect.objectContaining({ typeName: '26A' }), expect.objectContaining({ typeName: '26B' })], [], expect.any(AbortSignal))
  for (const index of [1, 2]) {
    const block = within(screen.getByRole('region', { name: `공급행 ${index} 검토` }))
    expect(block.getByText('선택 단지: 익산한스빌')).toBeVisible()
    // 새 단지에는 주택형이 없으므로 PDF 형명·면적으로 새 주택형을 추가하도록 미리 고른다.
    expect(block.getByText(/주택형을 이 단지에 추가합니다/)).toBeVisible()
  }
  expect(screen.getByLabelText('공급행 1 공급 구분')).toHaveValue('NEW_SUPPLY')
})

it('단지가 연결되지 않은 LH 원천행은 단지를 고르면 공고문 주택형에 맞춰 나누고 LH 공급 수를 공통 모집수로 저장한다', async () => {
  const [first] = workspace.supply.rows
  const source = { ...first, id: 11, key: 'row:11', housingComplexId: null, housingComplexName: null, housingTypeId: null, groupId: null, sharedHouseholdCount: null,
    typeName: '26A,B(고령자)', householdCount: 4, category: 'NEW_SUPPLY' as const, targets: [] }
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, supply: { ...workspace.supply, rows: [source] } })
  matchingTypes.getMatchingHousingTypes.mockResolvedValue([{ id: 21, name: '26A', exclusiveArea: 26.5195, supplyArea: 40.6486, householdCount: null }, { id: 22, name: '26B', exclusiveArea: 26.4673, supplyArea: 40.5686, householdCount: null }])
  const extraction = structuredClone(fixtures.hansville.extraction)
  extraction.sharedCounts = []
  await open(JSON.stringify(extraction))
  expect(screen.getByText('단지에 연결되지 않은 LH 원천행 1개가 있습니다. 단지를 정하면 공고문 주택형에 맞춰 연결하거나 나눕니다.')).toBeVisible()
  fireEvent.click(await within(screen.getByRole('region', { name: 'PDF 단지 정하기' })).findByRole('button', { name: '선택: 검색 단지' }))
  const block = screen.getByRole('region', { name: '공급행 1 검토' })
  expect((await within(block).findAllByText(/LH 원천 '26A,B\(고령자\)'을\(를\) 공고문 26A·26B로 나눕니다\. 이번 공급 4세대를 공통 모집수로 둡니다\./))[0]).toBeVisible()
  expect(within(block).getByLabelText('공급행 1 저장할 위치')).toHaveValue('row:11')
  for (const index of [1, 2]) fireEvent.click(within(screen.getByRole('region', { name: `공급행 ${index} 검토` })).getByRole('button', { name: `공급행 ${index} 대응 확인` }))
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  await waitFor(() => expect(api.previewJsonUpdate).toHaveBeenCalled())
  const correction = (api.previewJsonUpdate.mock.calls[0][1] as JsonUpdateInput).correction
  const added = correction.rows.filter(row => row.id === null)
  expect(correction.rows.find(row => row.key === 'row:11')).toEqual(expect.objectContaining({ housingComplexId: 7, housingTypeId: 21, typeName: '26A', excluded: false }))
  expect(added).toEqual([expect.objectContaining({ housingComplexId: 7, housingTypeId: 22, typeName: '26B', category: 'NEW_SUPPLY' })])
  expect(correction.newGroups).toEqual([{ sourceRowId: 11, rowKeys: ['row:11', added[0].key], sharedHouseholdCount: 4 }])
})

it('검산에서 맞지 않은 합계를 공급정보 위에 보여 준다', async () => {
  const extraction = structuredClone(fixtures.hansville.extraction) as unknown as { data: { supplyRows: Record<string, unknown>[] } }
  Object.assign(extraction.data.supplyRows[0], { exclusiveArea: 26.37, commonArea: 15.1, supplyArea: 42 })
  await open(JSON.stringify(extraction))
  expect(screen.getByRole('note', { name: '검산 결과' })).toHaveTextContent('공급면적 42㎡가 전용 + 공용 41.47㎡와 달라 계산값을 씁니다.')
})
