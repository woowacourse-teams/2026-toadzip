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
vi.mock('../ingest/supplyMatchingApi', () => ({ getMatchingHousingTypes: async (id: number) => fixtures.hansville.complexes.find(item => item.id === id)?.housingTypes ?? [] }))
vi.mock('../../public-housing/api/announcementAttachments', () => ({ loadAnnouncementAttachment: async () => new Blob(), attachmentErrorMessage: () => 'PDF 열기 실패' }))
vi.mock('../../public-housing/components/PdfDocumentPreview', () => ({ default: ({ focus }: { focus: { page: number; query: string } | null }) => <p>PDF {focus?.page ?? 1}쪽 · 강조 {focus?.query || '없음'}</p> }))
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(),
  getManagementPage: async () => ({ items: [{ id: 7, name: '검색 단지', subtitle: '주소 확인' }] }),
  getManagementDetail: async () => ({ data: { name: '익산한스빌', rentalType: 'NATIONAL_RENTAL', address: { pnu: '1114010100100010000' } } }) }))
const base = scenario('hansville').workspace
const workspace = { ...base, detail: { ...base.detail, data: { ...base.detail.data, name: '익산한스빌 국민임대주택 예비입주자 자격완화 모집 공고(2026.10.08)', postedDate: '2026-10-08' }, attachments: [{ attachmentId: 68, fileName: '공고문.pdf', fileType: 'ANNOUNCEMENT', fileUrl: 'https://example.com/notice.pdf' }] } }
beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('URL', class extends URL { static createObjectURL = () => 'blob:pdf'; static revokeObjectURL = vi.fn() })
  api.loadJsonWorkspace.mockResolvedValue(workspace)
  api.applyJsonUpdate.mockResolvedValue(workspace)
  api.previewJsonUpdate.mockResolvedValue({ confirmationToken: 'confirmed' })
})
afterEach(() => vi.unstubAllGlobals())
function renderPage() {
  render(<MemoryRouter initialEntries={['/admin/announcements/29/pdf-correction?failureReturnTo=%2Fadmin%2Ffailures%3Fdomain%3Dannouncement&section=verification']}><Routes><Route path="/admin/announcements/:id/pdf-correction" element={<PdfCorrectionPage />} /></Routes></MemoryRouter>)
}
async function open(text = JSON.stringify(fixtures.hansville.extraction)) {
  renderPage()
  fireEvent.change(await screen.findByRole('textbox', { name: 'ChatGPT 응답' }), { target: { value: text } })
  await waitFor(() => expect(screen.getByRole('button', { name: '응답 검토하기' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '응답 검토하기' }))
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
it('등록된 PDF를 자동 선택하고 다운로드와 프롬프트를 보여준다', async () => {
  renderPage()
  expect(await screen.findByLabelText('프롬프트 내용')).toHaveTextContent('첨부한 공식 공고문 PDF에서')
  const download = await screen.findByRole('link', { name: 'PDF 다운로드' })
  expect(download).toHaveAttribute('download', '공고문.pdf')
  expect(screen.getByRole('combobox', { name: '공고문 PDF' })).toHaveAccessibleDescription('공고문.pdf')
  expect(document.querySelector('input[type=file]')).toBeNull()
})
it('등록된 PDF가 없으면 준비와 검토를 막고 등록 위치를 알린다', async () => {
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, detail: { ...workspace.detail, attachments: [] } })
  renderPage()
  expect(await screen.findByText('등록된 PDF가 없습니다.')).toBeVisible()
  expect(screen.getByRole('button', { name: 'PDF 받고 프롬프트 복사해 ChatGPT 열기' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '응답 검토하기' })).toBeDisabled()
})
it('깨진 응답은 검토를 시작하지 못하며 내용을 유지한다', async () => {
  renderPage()
  const field = await screen.findByRole('textbox', { name: 'ChatGPT 응답' })
  fireEvent.change(field, { target: { value: '{broken}' } })
  expect(screen.getByRole('alert')).toBeVisible()
  expect(screen.getByRole('button', { name: '응답 검토하기' })).toBeDisabled()
  expect(field).toHaveValue('{broken}')
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

it('붙여넣는 즉시 오류 또는 추출 수를 보여주고 검토 버튼으로만 다음 단계로 간다', async () => {
  renderPage()
  const response = await screen.findByRole('textbox', { name: 'ChatGPT 응답' })
  fireEvent.change(response, { target: { value: '{broken}' } })
  expect(screen.getByRole('alert')).toHaveTextContent('JSON')
  fireEvent.change(response, { target: { value: JSON.stringify(fixtures.hansville.extraction) } })
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.getByText(/공급행 2개 · 임대조건 2개 · 일정 3개 · 미확인 \d+개 · 공고명 일치 · 공고일 일치/)).toBeVisible()
  expect(screen.queryByText(/형식 복구/)).not.toBeInTheDocument()
  await waitFor(() => expect(screen.getByRole('button', { name: '응답 검토하기' })).toBeEnabled())
  expect(screen.queryByRole('heading', { name: '공급정보' })).not.toBeInTheDocument()
})

it('확인 필요 항목을 세고 하단 버튼으로 다음 항목에 이동하며 상태로 거른다', async () => {
  const scroll = vi.fn()
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scroll })
  onTestFinished(() => { delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView })
  await open()
  expect(screen.getByRole('button', { name: '일정 · 확인 1' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '확인 필요 3개 남음 ↓' }))
  expect(screen.getByRole('region', { name: '공급행 1 검토' })).toHaveFocus()
  expect(scroll).toHaveBeenCalledWith({ block: 'center' })
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

it('다른 공고의 응답이면 공고명·공고일이 다르다고 알리고 검토 화면에도 남긴다', async () => {
  renderPage()
  fireEvent.change(await screen.findByRole('textbox', { name: 'ChatGPT 응답' }), { target: { value: JSON.stringify(fixtures.gwangju.extraction) } })
  expect(await screen.findByRole('alert')).toHaveTextContent('현재 공고와 다른 PDF의 응답일 수 있습니다 · 공고명 다름(PDF 광주첨단 H-1,2,3BL 행복주택 예비입주자 모집) · 공고일 다름(PDF 2026-10-15)')
  await waitFor(() => expect(screen.getByRole('button', { name: '응답 검토하기' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '응답 검토하기' }))
  expect(await screen.findByText(/이 공고의 PDF로 받은 응답인지 확인하세요/)).toBeVisible()
})

it('공급정보의 PDF 채우기는 작성 중인 행을 기준으로 대조하고 저장 없이 편집표에 돌려준다', async () => {
  const onFill = vi.fn()
  const draft = structuredClone(workspace.supply.rows)
  draft[0].moveInMonth = '2027-03'
  render(<MemoryRouter><PdfCorrectionWorkspace id="29" supplyDraft={{ rows: draft, onFill, onCancel: vi.fn() }} /></MemoryRouter>)
  fireEvent.change(await screen.findByLabelText('ChatGPT 응답'), { target: { value: JSON.stringify(fixtures.hansville.extraction) } })
  await waitFor(() => expect(screen.getByRole('button', { name: '응답 검토하기' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '응답 검토하기' }))
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
  render(<MemoryRouter><PdfCorrectionWorkspace id="29" supplyDraft={{ rows: [], onFill, onCancel: vi.fn() }} /></MemoryRouter>)
  const extraction = structuredClone(fixtures.hansville.extraction)
  extraction.data.supplyRows = extraction.data.supplyRows.slice(0, 1)
  extraction.sharedCounts = []
  fireEvent.change(await screen.findByLabelText('ChatGPT 응답'), { target: { value: JSON.stringify(extraction) } })
  await waitFor(() => expect(screen.getByRole('button', { name: '응답 검토하기' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '응답 검토하기' }))
  fireEvent.click(screen.getByRole('button', { name: '단지 검색' }))
  fireEvent.click(await screen.findByRole('button', { name: '선택: 검색 단지' }))
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
