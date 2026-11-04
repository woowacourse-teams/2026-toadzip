import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, expect, it, onTestFinished, vi } from 'vitest'
import { PdfCorrectionPage, PdfCorrectionWorkspace } from './PdfCorrectionPage'
import { scenario, separateHansville } from './fixtures/workspace'
import fixtures from './fixtures/scenarios.json'
import type { JsonPreview, JsonUpdateInput } from '../announcement-json/api'
import { ManagementError } from '../management/api'
const api = vi.hoisted(() => ({ loadJsonWorkspace: vi.fn(), previewJsonUpdate: vi.fn(), applyJsonUpdate: vi.fn() }))
vi.mock('../announcement-json/api', () => api)
const registration = vi.hoisted(() => ({ createHousingComplex: vi.fn() }))
vi.mock('../registration/api', async original => ({ ...await original<typeof import('../registration/api')>(), ...registration }))
const lookup = vi.hoisted(() => ({ lookupAddresses: vi.fn(async () => []) }))
vi.mock('./addressLookupApi', () => lookup)
const pdfTypes = vi.hoisted(() => ({ createPdfHousingTypes: vi.fn(async () => {}) }))
vi.mock('./pdfHousingTypes', () => pdfTypes)
const extractor = vi.hoisted(() => ({ extractAnnouncementPdf: vi.fn(), loadLatestExtraction: vi.fn(), cancelExtraction: vi.fn() }))
vi.mock('./extractionApi', async original => ({ ...await original<typeof import('./extractionApi')>(), ...extractor }))
const draftApi = vi.hoisted(() => ({ loadCorrectionDraft: vi.fn(), saveCorrectionDraft: vi.fn(), deleteCorrectionDraft: vi.fn() }))
vi.mock('./correctionDraftApi', async original => ({ ...await original<typeof import('./correctionDraftApi')>(), ...draftApi }))
const matchingTypes = vi.hoisted(() => ({ getMatchingHousingTypes: vi.fn() }))
vi.mock('../ingest/supplyMatchingApi', () => matchingTypes)
vi.mock('../../public-housing/api/announcementAttachments', () => ({ loadAnnouncementAttachment: async () => new Blob(), attachmentErrorMessage: () => 'PDF 열기 실패' }))
vi.mock('../../public-housing/components/PdfDocumentPreview', () => ({ default: ({ focus }: { focus: { page: number; query: string | string[] } | null }) => <p>PDF {focus?.page ?? 1}쪽 · 강조 {(Array.isArray(focus?.query) ? focus.query.join(' | ') : focus?.query) || '없음'}</p> }))
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
  api.previewJsonUpdate.mockResolvedValue(previewResult())
  extracts(JSON.stringify(fixtures.hansville.extraction))
  extractor.loadLatestExtraction.mockResolvedValue(null)
  draftApi.loadCorrectionDraft.mockResolvedValue(null)
  draftApi.saveCorrectionDraft.mockImplementation(async () => ({ revision: draftApi.saveCorrectionDraft.mock.calls.length, updatedAt: '2026-11-03T03:00:00Z' }))
  draftApi.deleteCorrectionDraft.mockResolvedValue(undefined)
})
/** 서버 미리보기 응답. 적용 전 확인에 쓴다. */
function previewResult(more: Partial<JsonPreview> = {}): JsonPreview {
  return { confirmationToken: 'confirmed', fields: [], supply: null, schedules: [], attachments: [], housingTypes: [], housingTypeWarnings: [], warnings: [], ...more }
}
/** 적용 전 확인을 열고 이대로 적용한다. */
async function applyCorrection() {
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  fireEvent.click(await within(await screen.findByRole('region', { name: '적용 전 확인' })).findByRole('button', { name: '이대로 적용' }))
}
/** 빈 Blob(첨부 PDF 목)의 SHA-256 */
const EMPTY_PDF_SHA256 = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
function savedResult(pdfSha256: string) {
  return { fileName: '지난 공고문.pdf', pdfSha256, createdAt: '2026-10-25T03:00:00Z',
    extraction: { json: JSON.stringify(fixtures.hansville.extraction), pages: [1, 2], totalPages: 3, warnings: [], usage: { promptTokens: 100, outputTokens: 50, thinkingTokens: null } } }
}
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
/** 한스빌 26A·26B를 공고문이 "26A/B" 한 행(세부 타입 묶음, 모집수 170)으로 적은 응답 */
function subtypeExtraction() {
  const extraction = structuredClone(fixtures.hansville.extraction) as unknown as { data: { supplyRows: Record<string, unknown>[] }; sharedCounts: unknown[] }
  extraction.data.supplyRows = [{ ...extraction.data.supplyRows[0], sourceHousingTypeName: '26A/B', totalSupplyHouseholdCount: 170, exclusiveArea: null, supplyArea: null, subtypes: [
    { name: '26A', exclusiveArea: 26.5195, commonArea: null, supplyArea: 52.5, constructedHouseholdCount: 120 },
    { name: '26B', exclusiveArea: 26.4673, commonArea: null, supplyArea: 52.4, constructedHouseholdCount: 50 }] }]
  extraction.sharedCounts = []
  return extraction
}
/** 공급행 목록에서 그 행을 골라 편집 영역에 연다. */
function showRow(row: number) {
  fireEvent.click(within(screen.getByRole('region', { name: '공급행 목록' })).getByRole('button', { name: `공급행 ${row} 편집` }))
}
/** 그 행이 저장될 곳을 적은 한 문장 */
function placeOf(row: number) {
  showRow(row)
  return screen.getByLabelText(`공급행 ${row} 저장할 곳`).textContent
}
/** 바꾸기를 열어 저장할 곳 고르기 영역을 돌려준다. */
function openPicker(row: number) {
  showRow(row)
  fireEvent.click(screen.getByRole('button', { name: `공급행 ${row} 저장할 곳 바꾸기` }))
  return screen.getByRole('group', { name: `공급행 ${row} 저장할 곳 고르기` })
}
function chooseExisting(row: number, id: number) {
  fireEvent.click(within(openPicker(row)).getByRole('button', { name: new RegExp(`#${id} · `) }))
}
function chooseNew(row: number) {
  fireEvent.click(within(openPicker(row)).getByRole('button', { name: '새 공급정보로 추가' }))
}
function manual(scope: string, label: string) {
  fireEvent.click(within(screen.getByRole('group', { name: `${scope} ${label} 반영 방법` })).getByRole('button', { name: '직접 입력' }))
}
it('서버 검증을 마친 보정안을 적용 전 확인으로 보여 주고, 확인한 같은 보정안만 한 번 저장한다', async () => {
  let finish!: (value: JsonPreview) => void
  api.previewJsonUpdate.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  await open()
  expect(screen.queryByRole('checkbox', { name: /확인함|대조함/ })).not.toBeInTheDocument()
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
  fireEvent.click(within(screen.getByRole('group', { name: '공급행 1 이 공고의 주택형 표기 반영 방법' })).getByRole('button', { name: 'PDF 추출값 사용' }))
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  expect(screen.getByRole('button', { name: '적용 중…' })).toBeDisabled()
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
  const input = api.previewJsonUpdate.mock.calls[0][1] as JsonUpdateInput
  // 한스빌 26A·26B는 기존 행 하나와 공통 모집수 170을 함께 적은 LH 한 행이라 공급행 하나로 접어 저장한다.
  expect(input.correction.newGroups).toEqual([])
  expect(input.correction.rows).toEqual([expect.objectContaining({ id: 90, householdCount: 170, housingTypeId: 10353, linkedHousingTypes: [{ housingTypeId: 10352 }] })])
  expect(input.housingTypeValues?.map(value => [value.name, value.linkedHousingTypeId])).toEqual([['26A', undefined], ['26B', 10352]])
  await act(async () => finish(previewResult({ housingTypeWarnings: ['26: 관리자가 고친 세대수를 유지합니다.'],
    housingTypes: [{ rowKey: 'row:1', housingTypeId: 10353, typeName: '26', before: { name: '26', supplyArea: null, householdCount: null }, after: { name: '26A', supplyArea: 52.5, householdCount: 120 } }] })))
  const summary = await screen.findByRole('region', { name: '적용 전 확인' })
  expect(within(summary).getByRole('note', { name: '적용 경고' })).toHaveTextContent('26: 관리자가 고친 세대수를 유지합니다.')
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
  fireEvent.click(within(summary).getByRole('button', { name: '이대로 적용' }))
  await screen.findByRole('heading', { name: '공고 보정을 적용했습니다.' })
  expect(api.applyJsonUpdate).toHaveBeenCalledExactlyOnceWith('29', input, 'confirmed')
  expect(screen.queryByRole('heading', { name: '적용할 변경' })).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '실패 검토로 돌아가기' })).toHaveAttribute('href', '/admin/failures?domain=announcement')
  const result = screen.getByRole('heading', { name: '공고 보정을 적용했습니다.' }).closest('section')!
  expect(result).toHaveTextContent('이 공고의 미해결 오류를 해결로 바꿨습니다.')
  expect(screen.getByRole('link', { name: '저장된 공급정보 확인' }).getAttribute('href')).toContain('section=relations')
  expect(screen.getByRole('link', { name: '출처·수정 이력 보기' }).getAttribute('href')).toContain('section=history')
  expect(within(screen.getByRole('region', { name: '공고로 갱신한 주택형' })).getAllByRole('listitem').map(item => item.textContent)).toEqual(['#10353 「26」 → 이름 26A · 공급면적 52.5㎡ · 세대수 120', '26: 관리자가 고친 세대수를 유지합니다.'])
})
/** GH·SH 원천으로 만든 미확정 공고. 임대·모집 유형이 비어 있고 사용자에게 보이지 않는다. */
function unconfirmedWorkspace() {
  return { ...workspace, detail: { ...workspace.detail,
    summary: { ...workspace.detail.summary, announcement: { ...(workspace.detail.summary as { announcement?: object }).announcement, releaseStatus: 'UNCONFIRMED' } },
    data: { ...workspace.detail.data, rentalType: null, recruitmentType: null } } }
}
it('미확정 공고는 임대·모집 유형을 고르고 단지를 확인했다고 공개를 요청해야 공개 요청을 보내고 결과에 공개를 알린다', async () => {
  api.loadJsonWorkspace.mockResolvedValue(unconfirmedWorkspace())
  api.previewJsonUpdate.mockResolvedValue(previewResult({ publishes: true, warnings: ['미확정 공고를 공개합니다. 적용하면 사용자에게 보이고 신규 공고 알림이 나갑니다.'] }))
  await open()
  const release = screen.getByRole('region', { name: '공개 전 확인' })
  fireEvent.change(within(release).getByLabelText('공급 유형'), { target: { value: 'NATIONAL_RENTAL' } })
  fireEvent.change(within(release).getByLabelText('모집 유형'), { target: { value: 'WAITLIST' } })
  fireEvent.click(within(release).getByRole('checkbox', { name: /단지를 확인했고 이 보정과 함께 공개합니다/ }))

  await applyCorrection()

  const input = api.previewJsonUpdate.mock.calls[0][1] as JsonUpdateInput
  expect(input.publish).toBe(true)
  expect(input.fields).toEqual(expect.arrayContaining(['rentalType', 'recruitmentType']))
  expect(input.document.announcement).toEqual(expect.objectContaining({ rentalType: 'NATIONAL_RENTAL', recruitmentType: 'WAITLIST' }))
  expect(await screen.findByRole('heading', { name: '공고를 공개했습니다.' })).toBeInTheDocument()
})
it('미확정 공고라도 공개를 요청하지 않으면 공개 요청을 보내지 않고 아직 보이지 않는다고 알린다', async () => {
  api.loadJsonWorkspace.mockResolvedValue(unconfirmedWorkspace())
  api.applyJsonUpdate.mockResolvedValue(unconfirmedWorkspace())
  await open()
  expect(within(screen.getByRole('region', { name: '공개 전 확인' })).getByRole('checkbox')).not.toBeChecked()

  await applyCorrection()

  const input = api.previewJsonUpdate.mock.calls[0][1] as JsonUpdateInput
  expect(input.publish).toBeFalsy()
  expect(input.fields).not.toContain('rentalType')
  const result = (await screen.findByRole('heading', { name: '공고 보정을 적용했습니다.' })).closest('section')!
  expect(result).toHaveTextContent('아직 미확정이라 사용자에게 보이지 않습니다')
})
it('공개된 공고에는 공개 전 확인을 보이지 않는다', async () => {
  await open()
  expect(screen.queryByRole('region', { name: '공개 전 확인' })).not.toBeInTheDocument()
})
it('저장할 곳을 정하지 않은 공급행은 까닭을 쉬운 말로 알리고 서버에 보내지 않는다', async () => {
  const extraction = structuredClone(fixtures.hansville.extraction)
  extraction.sharedCounts = []
  await open(JSON.stringify(extraction))
  // 표기·면적·모집수 근거 없이 남은 기존 행 하나는 자동으로 고르지 않고 모호로 알린다.
  expect(placeOf(1)).toBe('저장할 곳을 아직 정하지 않았습니다.')
  expect(screen.getByText(/^이 대응이 맞는지 확인하세요\. 표기·전용면적이 맞는 기존 공급정보가 없어 자동으로 고르지 않았습니다/)).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('공급행 1(26A)을 저장할 곳을 고르세요. 고칠 기존 공급정보를 고르거나 새 공급정보로 추가하세요.')
  expect(api.previewJsonUpdate).not.toHaveBeenCalled()
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
})
it('AI 워커가 원문과 대조한 경고를 검토 화면의 검산 확인에도 보여 준다', async () => {
  extractor.extractAnnouncementPdf.mockResolvedValue({ json: JSON.stringify(fixtures.hansville.extraction), pages: [1, 2], totalPages: 3, warnings: ['supplyRows[0].sourceHousingTypeName: 근거 인용에 없는 LH 주택형 이름(24A)'], usage: { promptTokens: 100, outputTokens: 50, thinkingTokens: null } })
  renderPage()
  await review()
  fireEvent.click(await screen.findByRole('button', { name: '안내 펼치기' }))
  const checks = await screen.findByRole('note', { name: '검산 결과' })
  expect(checks).toHaveTextContent('근거 인용에 없는 LH 주택형 이름(24A)')
})

it('공급행을 PDF 단지 이름별로 묶어 한 단지의 주택형을 한곳에 보여 주고, 쓰지 않는 변경 목록은 두지 않는다', async () => {
  await open(JSON.stringify(separateHansville()))
  // 목록은 단지별로 모든 공급행을 한 줄씩 요약하고, 편집 영역은 고른 행 하나만 보인다.
  const list = screen.getByRole('region', { name: '공급행 목록' })
  expect(within(list).getByRole('rowheader', { name: /^익산한스빌/ })).toHaveTextContent('익산한스빌 · 주택형 2개 · 모집 합')
  expect(within(list).getAllByRole('button', { name: /^공급행 \d 편집$/ }).map(button => button.textContent)).toEqual(['1. 26A', '2. 26B'])
  const group = screen.getByRole('region', { name: '익산한스빌 주택형' })
  expect(within(group).getByRole('heading', { name: '익산한스빌주택형 2개 중 공급행 1' })).toBeVisible()
  expect(within(group).getByRole('region', { name: '공급행 1 검토' })).toBeVisible()
  expect(screen.queryByRole('region', { name: '공급행 2 검토' })).not.toBeInTheDocument()
  showRow(2)
  expect(screen.getByRole('region', { name: '공급행 2 검토' })).toBeVisible()
  expect(screen.queryByText(/반영할 변경 \d+개 보기/)).not.toBeInTheDocument()
})
it('형식 오류와 셀 수정·조건 제외를 제공하고 PDF 근거 쪽으로 이동해 그 값을 강조한다', async () => {
  await open()
  const row = screen.getByRole('region', { name: '공급행 1 검토' })
  fireEvent.click(within(row).getByRole('button', { name: '조건 1 대조·수정' }))
  manual('공급행 1 조건 1', '보증금 (원)')
  const input = screen.getByRole('textbox', { name: '공급행 1 조건 1 보증금 (원) 수정' })
  fireEvent.change(input, { target: { value: '4,076,000' } })
  expect(input).toHaveAttribute('aria-invalid', 'true')
  expect(within(screen.getByRole('group', { name: '공급행 1 조건 1 보증금 (원) 반영 방법' })).getByRole('button', { name: '직접 입력' })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.change(input, { target: { value: '4076000' } })
  const details = screen.getByRole('region', { name: '공급행 1 조건 1 수정' })
  fireEvent.click(within(details).getAllByRole('button', { name: 'PDF 5쪽 보기' })[0])
  // 금액은 PDF 표기(천 단위 쉼표)로 그 값만, 값이 인용문에 없으면 인용문에서 고른 숫자를 강조한다.
  expect(await screen.findByText('PDF 5쪽 · 강조 4,076,000')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '검토표 보기' }))
  fireEvent.click(within(row).getByRole('button', { name: 'PDF 5쪽에서 조건 1 월 임대료 (원) 보기' }))
  expect(await screen.findByText('PDF 5쪽 · 강조 72,820')).toBeVisible()
  // 셀의 p.N도 그 셀의 값을 강조한다. 다른 값을 먼저 강조해 두고 바뀌는지 본다.
  fireEvent.click(screen.getByRole('button', { name: '검토표 보기' }))
  fireEvent.click(within(row).getByRole('button', { name: 'PDF 5쪽에서 조건 1 보증금 (원) 보기' }))
  expect(await screen.findByText('PDF 5쪽 · 강조 4,076,000')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '검토표 보기' }))
  const rent = within(screen.getByRole('region', { name: '공급행 1 조건 1 수정' })).getAllByRole('button', { name: 'PDF 5쪽 보기' }).find(button => button.closest('[data-review-id]')?.getAttribute('data-review-id')?.endsWith('.monthlyRent'))!
  fireEvent.click(rent)
  expect(await screen.findByText('PDF 5쪽 · 강조 72,820')).toBeVisible()
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
  if (stage === 'apply') await applyCorrection(); else fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
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
  await applyCorrection()
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
it('기다리는 동안 추출 취소를 누르면 서버에서 작업을 끝내고 기다림을 멈추며, 그사이 끝난 작업이면 계속 기다린다', async () => {
  let signal: AbortSignal | undefined
  extractor.extractAnnouncementPdf.mockImplementation((_id: string, _file: Blob, _name: string, given: AbortSignal, options: { onJob?: (jobId: number) => void }) => {
    signal = given; options.onJob?.(31)
    return new Promise(() => {})
  })
  extractor.cancelExtraction.mockResolvedValueOnce(null).mockResolvedValueOnce('관리자가 추출을 취소했습니다. 다시 요청할 수 있습니다.')
  renderPage()
  await startExtraction()
  fireEvent.click(await screen.findByRole('button', { name: '추출 취소' }))
  await waitFor(() => expect(extractor.cancelExtraction).toHaveBeenCalledWith('29', 31))
  expect(screen.getByRole('button', { name: '추출 취소' })).toBeVisible()
  expect(signal?.aborted).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: '추출 취소' }))
  expect(await screen.findByText('관리자가 추출을 취소했습니다. 다시 요청할 수 있습니다.')).toBeVisible()
  expect(signal?.aborted).toBe(true)
  expect(screen.getByRole('button', { name: '다시 추출' })).toBeVisible()
})
it('서버가 칸 경로로 거절하면 화면 칸 이름으로 알리고 그 칸이 있는 접수처·기본정보로 옮긴다', async () => {
  api.previewJsonUpdate.mockRejectedValue(new ManagementError('receptionPlace.name: 필수 값입니다.', 400, {}, 'INVALID_REQUEST'))
  await open()
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('접수처명: 필수 값입니다.')
  expect(screen.getByRole('heading', { name: '접수처·기본정보' })).toBeVisible()
})
it('등록된 PDF를 자동으로 고르고 추출 버튼을 누를 때만 서버에서 추출한다', async () => {
  renderPage()
  await screen.findByRole('button', { name: 'PDF에서 값 추출' })
  expect(extractor.extractAnnouncementPdf).not.toHaveBeenCalled()
  await startExtraction()
  await waitFor(() => expect(extractor.extractAnnouncementPdf).toHaveBeenCalledOnce())
  expect(extractor.extractAnnouncementPdf).toHaveBeenCalledWith('29', expect.any(Blob), '공고문.pdf', expect.any(AbortSignal), expect.objectContaining({ attachmentId: '68' }))
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
  await waitFor(() => expect(extractor.extractAnnouncementPdf).toHaveBeenCalledWith('29', expect.any(Blob), '모집 안내.pdf', expect.any(AbortSignal), expect.objectContaining({ attachmentId: '71' })))
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
  await waitFor(() => expect(extractor.extractAnnouncementPdf).toHaveBeenCalledWith('29', file, '끌어온 공고문.pdf', expect.any(AbortSignal), expect.objectContaining({ attachmentId: undefined })))
})
it('재시도를 기다리는 동안 워커가 남긴 사유를 보여 준다', async () => {
  extractor.extractAnnouncementPdf.mockImplementation((_id, _file, _name, _signal, options) => {
    options.onNotice('잠시 뒤 다시 시도합니다 · 추출 서비스 응답이 늦습니다.')
    return new Promise(() => {})
  })
  renderPage()
  await startExtraction()
  expect(await screen.findByText('잠시 뒤 다시 시도합니다 · 추출 서비스 응답이 늦습니다.')).toBeVisible()
})
it('같은 PDF로 저장한 이전 결과가 있으면 모델을 부르지 않고 불러와 검토한다', async () => {
  extractor.loadLatestExtraction.mockResolvedValue(savedResult(EMPTY_PDF_SHA256))
  renderPage()
  expect(await screen.findByText(/지금 PDF와 같은 파일입니다/)).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '이전 결과 불러오기' }))
  expect(screen.getByText(/지난 공고문\.pdf에서 추출해 저장한 결과를 불러왔습니다/)).toBeVisible()
  await review(false)
  await screen.findByRole('heading', { name: '공급정보' })
  expect(extractor.extractAnnouncementPdf).not.toHaveBeenCalled()
})
it('이전 결과가 다른 PDF에서 나왔으면 그렇다고 알린다', async () => {
  extractor.loadLatestExtraction.mockResolvedValue(savedResult('0'.repeat(64)))
  renderPage()
  expect(await screen.findByText(/지금 고른 PDF와 다른 파일에서 추출했습니다/)).toBeVisible()
  expect(screen.getByRole('button', { name: '이전 결과 불러오기' })).toBeEnabled()
})
it('이전 결과를 불러오지 못해도 추출은 할 수 있다', async () => {
  extractor.loadLatestExtraction.mockRejectedValue(new ManagementError('요청을 처리하지 못했습니다.', 500))
  renderPage()
  await startExtraction()
  expect(await screen.findByText(/공급행 2개 · 임대조건 2개 · 일정 3개/)).toBeVisible()
  expect(screen.queryByRole('button', { name: '이전 결과 불러오기' })).not.toBeInTheDocument()
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
  await waitFor(() => expect(extractor.extractAnnouncementPdf).toHaveBeenCalledWith('29', file, '금호빛여울채 공고문.pdf', expect.any(AbortSignal), expect.objectContaining({ attachmentId: undefined })))
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
  // 한 글자씩 입력하는 도중 값이 유효해져도 입력란이 사라지지 않아야 끝까지 칠 수 있다.
  const url = 'https://example.com/official-notice'
  for (let end = 1; end <= url.length; end++) fireEvent.change(screen.getByRole('textbox', { name: '공식 근거 URL' }), { target: { value: url.slice(0, end) } })
  expect(screen.getByRole('textbox', { name: '공식 근거 URL' })).toHaveValue(url)
  expect(screen.getByRole('button', { name: '보정 내용 적용' })).toBeEnabled()
})

it('확인 필요 항목을 세고 하단 버튼으로 다음 항목에 이동하며 상태로 거른다', async () => {
  const scroll = vi.fn()
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scroll })
  onTestFinished(() => { delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView })
  await open(JSON.stringify(separateHansville()))
  expect(screen.getByRole('tab', { name: '일정 · 확인 1' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '미해결 3개 ↓' }))
  expect(screen.getByRole('region', { name: '공급행 1 검토' })).toHaveFocus()
  expect(scroll).toHaveBeenCalledWith({ block: 'start' })
  fireEvent.click(screen.getByRole('button', { name: '공급행 1 대응 확인' }))
  fireEvent.click(within(screen.getByRole('group', { name: '검토 항목 필터' })).getByRole('button', { name: '확인 필요 2' }))
  expect(screen.queryByRole('region', { name: '공급행 1 검토' })).not.toBeInTheDocument()
  expect(screen.getByRole('region', { name: '공급행 2 검토' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '공급행 2 대응 확인' }))
  expect(screen.getByText('이 영역에는 ‘확인 필요’ 항목이 없습니다.')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '미해결 1개 ↓' }))
  expect(screen.getByRole('heading', { name: '일정' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '일정 2 적용 안 함' }))
  expect(screen.getByText('확인 필요 없음')).toBeVisible()
  fireEvent.click(within(screen.getByRole('group', { name: '검토 항목 필터' })).getByRole('button', { name: '전체' }))
  expect(screen.getByRole('button', { name: '일정 2 적용 안 함' })).toHaveAttribute('aria-pressed', 'true')
  expect(within(screen.getByRole('list', { name: '일정 대조표' })).getByText('적용 안 함', { selector: 'span' })).toBeVisible()
})

it('기준 단지는 원천 공급행이 아니라 단지명·임대유형으로 한 번씩 보여준다', async () => {
  const [first] = workspace.supply.rows
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, supply: { ...workspace.supply, rows: [first, { ...first, id: 91, key: 'row:91', typeName: '42.1900', displayOrder: 1 }] } })
  await open()
  chooseNew(1)
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
  extracts(JSON.stringify(separateHansville()))
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

it('저장할 곳은 한 문장으로 보이고, 바꾸기는 추천 행을 먼저 두고 이 공고의 다른 공급행도 찾아 고치거나 새 공급정보로 추가하게 한다', async () => {
  const [first] = workspace.supply.rows
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, supply: { ...workspace.supply, rows: [first, { ...first, id: 92, key: 'row:92', complexName: '다른 단지', housingComplexId: 999, housingComplexName: '다른 단지', typeName: '59B', displayOrder: 1 },
    { ...first, id: 95, key: 'row:95', excluded: true, displayOrder: 2 }] } })
  await open()
  expect(placeOf(1)).toBe(`기존 공급정보 #90(${first.typeName})을 고칩니다.`)
  expect(screen.queryByRole('radio')).not.toBeInTheDocument()
  const picker = openPicker(1)
  const options = () => within(picker).queryAllByRole('button').map(option => option.textContent)
  expect(options()[0]).toMatch(/^추천 · #90 · /)
  expect(options().some(text => text?.includes('#92 · '))).toBe(true)
  expect(options().some(text => text?.includes('#95 · '))).toBe(false)
  fireEvent.change(within(picker).getByRole('searchbox', { name: '공급행 1 기존 공급정보 찾기' }), { target: { value: '없는 이름' } })
  expect(options().some(text => text?.includes('#92 · '))).toBe(false)
  fireEvent.change(within(picker).getByRole('searchbox', { name: '공급행 1 기존 공급정보 찾기' }), { target: { value: '59b' } })
  fireEvent.click(within(picker).getByRole('button', { name: /#92 · / }))
  expect(screen.queryByRole('group', { name: '공급행 1 저장할 곳 고르기' })).not.toBeInTheDocument()
  expect(placeOf(1)).toBe('기존 공급정보 #92(59B)을 고칩니다.')
  expect(within(screen.getByRole('region', { name: '공급행 목록' })).getAllByRole('row')[2]).toHaveTextContent('#92 고침')
  chooseNew(1)
  expect(placeOf(1)).toBe('새 공급정보로 추가합니다.')
})

it('단지 정하기에서 이미 공급정보가 연결된 단지를 고르면 새 행 대신 그 공급정보를 추천한다', async () => {
  const [first] = workspace.supply.rows
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, supply: { ...workspace.supply, rows: [{ ...first, complexName: '표기가 전혀 다른 원천 단지', housingComplexName: '등록 단지', housingComplexId: 7, householdCount: null }] } })
  await open()
  expect(placeOf(1)).toBe('새 공급정보로 추가합니다.')
  fireEvent.click(await within(screen.getByRole('region', { name: 'PDF 단지 정하기' })).findByRole('button', { name: '선택: 검색 단지' }))
  // 표기·면적이 맞지 않는 행이라 고르지는 않고 추천으로만 보여 준다. 운영자가 직접 고른다.
  await waitFor(() => expect(placeOf(1)).toBe('저장할 곳을 아직 정하지 않았습니다.'))
  expect(screen.getByRole('region', { name: '공급행 1 검토' })).toHaveTextContent(`추천: #90(${first.typeName})`)
  expect(screen.queryByRole('region', { name: 'PDF 단지 정하기' })).not.toBeInTheDocument()
  chooseExisting(1, 90)
  expect(placeOf(1)).toBe(`기존 공급정보 #90(${first.typeName})을 고칩니다.`)
})

it('단지 정하기에서 전용면적으로 고른 공급정보라도 표기가 다르면 대응을 확인하게 한다', async () => {
  const [first] = workspace.supply.rows
  const area = fixtures.hansville.extraction.data.supplyRows[0].exclusiveArea
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, supply: { ...workspace.supply, rows: [{ ...first, complexName: '표기가 전혀 다른 원천 단지', housingComplexName: '등록 단지', housingComplexId: 7, householdCount: null, typeName: '다른 표기', exclusiveArea: area }] } })
  await open()
  fireEvent.click(await within(screen.getByRole('region', { name: 'PDF 단지 정하기' })).findByRole('button', { name: '선택: 검색 단지' }))
  await waitFor(() => expect(placeOf(1)).toBe('기존 공급정보 #90(다른 표기)을 고칩니다.'))
  expect(screen.getByRole('button', { name: '공급행 1 대응 확인' })).toBeInTheDocument()
})

it('공급행이 없는 LH 행은 단지 대응에서 맞는 PDF 행을 묻고, 고른 PDF 행을 그 LH 행에 붙여 LH 행 식별자를 보낸다', async () => {
  const lh = { rowIdentifier: 'LH:P:SUPPLY:9', complexLabel: '한스빌', typeName: '59A', exclusiveArea: null, householdCount: 3, attachedRowKey: null, splitRowKeys: [] }
  const extraction = separateHansville()
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, supply: { ...workspace.supply, lhSupplyRows: [lh], unattachedLhSupplyRows: [lh] } })
  await open(JSON.stringify(extraction))
  expect(placeOf(2)).toBe('새 공급정보로 추가합니다.')
  const match = screen.getByRole('region', { name: 'LH·PDF 대응' })
  expect(within(match).getByRole('list', { name: 'LH가 준 행' })).toHaveTextContent('59A · 면적 없음 · 3세대')
  const question = within(match).getByRole('group', { name: "LH 행 '59A 3세대' 대응" })
  expect(question).toHaveTextContent("LH 행 '59A 3세대'에 맞는 PDF 행이 없습니다.")
  fireEvent.click(within(question).getByRole('button', { name: 'PDF 행 고르기' }))
  fireEvent.click(within(within(question).getByRole('list', { name: "LH 행 '59A 3세대'에 붙일 PDF 행" })).getByRole('button', { name: /^공급행 2\(26B\)/ }))
  expect(question).toHaveTextContent("LH 행 '59A 3세대'에 공급행 2(26B)을 붙여 새 공급정보로 저장합니다.")
  expect(placeOf(2)).toBe("LH 행 '59A 3세대'에 붙는 새 공급정보입니다.")
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  await waitFor(() => expect(api.previewJsonUpdate).toHaveBeenCalled())
  const input = api.previewJsonUpdate.mock.calls[0][1] as JsonUpdateInput
  expect(input.correction.rows.find(row => row.id === null)).toMatchObject({ saveMode: 'ATTACH', lhSourceRowIdentifier: 'LH:P:SUPPLY:9' })
})

it('단지 정하기는 다른 단지명의 PDF 행이 이미 저장할 위치로 고른 공급정보를 다시 추천하지 않는다', async () => {
  const [first] = workspace.supply.rows
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, supply: { ...workspace.supply, rows: [{ ...first, housingComplexId: 7 }] } })
  const extraction = structuredClone(fixtures.hansville.extraction)
  extraction.data.supplyRows[1].complexReference.sourceComplexName = '표기가 다른 PDF 단지'
  await open(JSON.stringify(extraction))
  expect(placeOf(1)).toBe(`기존 공급정보 #90(${first.typeName})을 고칩니다.`)
  fireEvent.click(screen.getByRole('tab', { name: /^표기가 다른 PDF 단지/ }))
  fireEvent.click(await within(screen.getByRole('group', { name: 'PDF 단지 표기가 다른 PDF 단지' })).findByRole('button', { name: '선택: 검색 단지' }))
  await waitFor(() => expect(screen.queryByRole('group', { name: 'PDF 단지 표기가 다른 PDF 단지' })).not.toBeInTheDocument())
  expect(placeOf(2)).toBe('새 공급정보로 추가합니다.')
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

it('마이홈 원천 공고도 보정 결과에서 다시 정제를 실행하지 않고 해결한 오류만 알린다', async () => {
  const [first] = workspace.supply.rows
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, supply: { ...workspace.supply, rowSources: [{ rowKey: first.key, category: 'MYHOME_ANNOUNCEMENT', sourceKey: '5:213951:1' }] } })
  render(<MemoryRouter initialEntries={['/admin/announcements/29/pdf-correction?failureCount=2&failureReturnTo=%2Fadmin%2Ffailures']}><Routes><Route path="/admin/announcements/:id/pdf-correction" element={<PdfCorrectionPage />} /></Routes></MemoryRouter>)
  await review()
  await screen.findByRole('button', { name: '보정 내용 적용' })
  await applyCorrection()
  const result = (await screen.findByRole('heading', { name: '공고 보정을 적용했습니다.' })).closest('section')!
  expect(result).toHaveTextContent('이 공고의 오류 2건을 포함해 이 공고의 미해결 오류를 해결로 바꿨습니다.')
  expect(result).not.toHaveTextContent('정제')
  expect(within(result).queryByRole('button')).not.toBeInTheDocument()
  expect(within(result).queryByRole('link', { name: /LH 행 연결/ })).not.toBeInTheDocument()
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
  fireEvent.click(screen.getByRole('button', { name: '응답 준비로 돌아가기' }))
  // 같은 PDF로 돌아오면 다시 추출하지 않는다.
  fireEvent.click(await screen.findByRole('button', { name: '추출 결과 검토하기' }))
  await screen.findByRole('heading', { name: '공급정보' })
  expect(extractor.extractAnnouncementPdf).toHaveBeenCalledOnce()
  fireEvent.click(screen.getByRole('button', { name: '응답 준비로 돌아가기' }))
  extracts(JSON.stringify({ ...fixtures.hansville.extraction }, null, 1))
  fireEvent.click(await screen.findByRole('button', { name: '다시 추출' }))
  await review(false)
  // 근거 입력은 자동으로 채우므로 값이 정상이면 보이지 않는다.
  expect(screen.queryByRole('region', { name: '적용 근거 입력' })).not.toBeInTheDocument()
})

it('단지에 맞는 주택형이 없으면 따로 알리고 PDF 형명·면적으로 새 주택형을 추가해 저장한다', async () => {
  const extraction = structuredClone(fixtures.hansville.extraction)
  extraction.data.supplyRows[0].exclusiveArea = 99.1234
  // 공통 모집 묶음에는 기존 주택형만 넣을 수 있으므로 묶음이 없는 공고로 확인한다.
  extraction.sharedCounts = []
  await open(JSON.stringify(extraction))
  // 모집수·표기 근거가 없어 모호로 남은 저장 방식을 운영자가 정한다.
  chooseExisting(1, 90); chooseNew(2); showRow(1)
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
  fireEvent.click(screen.getByRole('button', { name: '안내 펼치기' }))
  expect(screen.getByText('26A에서 일반 · 청년 대상이 함께 쓰는 모집수입니다. 대상별 모집수는 비워 둡니다.')).toBeVisible()
  chooseExisting(1, 90); chooseNew(2)
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
    showRow(index)
    const block = within(screen.getByRole('region', { name: `공급행 ${index} 검토` }))
    expect(block.getByText('선택 단지: 익산한스빌')).toBeVisible()
    // 새 단지에는 주택형이 없으므로 PDF 형명·면적으로 새 주택형을 추가하도록 미리 고른다.
    expect(block.getByText(/주택형을 이 단지에 추가합니다/)).toBeVisible()
  }
  showRow(1)
  expect(screen.getByLabelText('공급행 1 공급 구분')).toHaveValue('NEW_SUPPLY')
})

it('단지가 연결되지 않은 LH 원천행은 단지를 고르면 공고문 세부 타입 행들을 그 LH 행 하나로 접어 LH 공급 수와 세부 타입 주택형을 모두 그 행에 저장한다', async () => {
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
  // 접은 근거는 자동 대응 근거에 접어 두고, 열면 보인다.
  fireEvent.click(await within(block).findByText('자동 대응 근거'))
  expect(within(block).getByText(/LH 원천 '26A,B\(고령자\)' 한 행에 공고문 26A·26B를 세부 타입으로 함께 연결합니다\. 이번 공급 4세대는 이 공급행 하나의 모집수입니다\./)).toBeVisible()
  expect(screen.queryByRole('region', { name: '공급행 2 검토' })).not.toBeInTheDocument()
  expect(placeOf(1)).toBe('기존 공급정보 #11(26A,B(고령자))을 고칩니다.')
  expect(within(within(block).getByRole('region', { name: '공급행 1 세부 타입 연결' })).getAllByRole('listitem').map(item => item.textContent)).toEqual([
    '26A · 전용 26.5195㎡ → 26A · 전용 26.5195㎡ · 공급 40.6486㎡ 대표 주택형', '26B · 전용 26.4673㎡ → 26B · 전용 26.4673㎡ · 공급 40.5686㎡'])
  fireEvent.click(within(block).getByRole('button', { name: '공급행 1 대응 확인' }))
  // 공고 표기는 LH 표기가 아니라 접은 공고문 행의 형명(26A/26B)이다. 근거 인용이 행마다 따로라 받아들여야 쓴다.
  fireEvent.click(within(screen.getByRole('group', { name: '공급행 1 이 공고의 주택형 표기 반영 방법' })).getByRole('button', { name: 'PDF 추출값 사용' }))
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  await waitFor(() => expect(api.previewJsonUpdate).toHaveBeenCalled())
  const input = api.previewJsonUpdate.mock.calls[0][1] as JsonUpdateInput
  expect(input.correction.rows).toEqual([expect.objectContaining({ key: 'row:11', housingComplexId: 7, housingTypeId: 21, typeName: '26A/26B', householdCount: 4, excluded: false, linkedHousingTypes: [{ housingTypeId: 22 }] })])
  expect(input.correction.newGroups).toEqual([])
  expect(input.housingTypeValues?.map(value => [value.rowKey, value.name, value.linkedHousingTypeId])).toEqual([['row:11', '26A', undefined], ['row:11', '26B', 22]])
})

it('세부 타입 묶음 행은 공급행 하나로 저장하고 행 모집수를 지키며 세부 타입 주택형을 모두 그 행에 연결한다', async () => {
  await open(JSON.stringify(subtypeExtraction()))
  const links = screen.getByRole('region', { name: '공급행 1 세부 타입 연결' })
  expect(links).toHaveTextContent('모집수 170세대는 이 공급행 하나에 두고')
  expect(screen.queryByText(/공통 모집 \d+세대/)).not.toBeInTheDocument()
  // 세부 타입 주택형 이름은 관리자가 공급행의 주택형 표기를 받아들였을 때만 보낸다.
  fireEvent.click(within(screen.getByRole('group', { name: '공급행 1 이 공고의 주택형 표기 반영 방법' })).getByRole('button', { name: 'PDF 추출값 사용' }))
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  await waitFor(() => expect(api.previewJsonUpdate).toHaveBeenCalled())
  const input = api.previewJsonUpdate.mock.calls[0][1] as JsonUpdateInput
  expect(input.correction.newGroups ?? []).toEqual([])
  expect(input.correction.rows).toEqual([expect.objectContaining({ id: 90, householdCount: 170, housingTypeId: 10353, newHousingType: null, linkedHousingTypes: [{ housingTypeId: 10352 }] })])
  expect(input.housingTypeValues).toEqual([
    expect.objectContaining({ rowKey: 'row:90', name: '26A', supplyArea: 52.5, householdCount: 120 }),
    expect.objectContaining({ rowKey: 'row:90', name: '26B', supplyArea: 52.4, householdCount: 50, linkedHousingTypeId: 10352 })])
  expect(input.housingTypeValues?.[0]).not.toHaveProperty('linkedHousingTypeId')
})

it('같은 면적 주택형이 여럿인 세부 타입은 고를 때까지 확인 필요로 남기고 저장하지 않는다', async () => {
  matchingTypes.getMatchingHousingTypes.mockResolvedValue([...fixtures.hansville.complexes[0].housingTypes, { id: 10354, name: '26 복제', exclusiveArea: 26.5195, supplyArea: null, householdCount: null }])
  await open(JSON.stringify(subtypeExtraction()))
  const choice = screen.getByRole('combobox', { name: '공급행 1 세부 타입 26A 연결 주택형' })
  expect(choice).toHaveValue('')
  expect(screen.getByRole('button', { name: /미해결 \d+개/ })).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('공급행 1: 세부 타입 26A의 연결 주택형을 고르세요.')
  expect(api.previewJsonUpdate).not.toHaveBeenCalled()
  fireEvent.change(choice, { target: { value: '10354' } })
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  await waitFor(() => expect(api.previewJsonUpdate).toHaveBeenCalled())
  expect((api.previewJsonUpdate.mock.calls[0][1] as JsonUpdateInput).correction.rows[0]).toMatchObject({ housingTypeId: 10354, linkedHousingTypes: [{ housingTypeId: 10352 }] })
})

it('PDF로 공급정보 편집표를 채울 때도 세부 타입 묶음 행의 함께 모집하는 주택형을 넘긴다', async () => {
  const onFill = vi.fn()
  extracts(JSON.stringify(subtypeExtraction()))
  render(<MemoryRouter><PdfCorrectionWorkspace id="29" supplyDraft={{ rows: structuredClone(workspace.supply.rows), onFill, onCancel: vi.fn() }} /></MemoryRouter>)
  await review()
  fireEvent.click(screen.getByRole('button', { name: '공급정보 편집표에 채우기' }))
  await waitFor(() => expect(onFill).toHaveBeenCalledOnce())
  expect(onFill.mock.calls[0][0].rows).toEqual([expect.objectContaining({ id: 90, housingTypeId: 10353, householdCount: 170, linkedHousingTypes: [{ housingTypeId: 10352 }] })])
  expect(onFill.mock.calls[0][0].newGroups).toEqual([])
})

it('검산에서 맞지 않은 합계를 공급정보 위에 보여 준다', async () => {
  const extraction = structuredClone(fixtures.hansville.extraction) as unknown as { data: { supplyRows: Record<string, unknown>[] } }
  Object.assign(extraction.data.supplyRows[0], { exclusiveArea: 26.37, commonArea: 15.1, supplyArea: 42 })
  await open(JSON.stringify(extraction))
  fireEvent.click(screen.getByRole('button', { name: '안내 펼치기' }))
  expect(screen.getByRole('note', { name: '검산 결과' })).toHaveTextContent('공급면적 42㎡가 전용 + 공용 41.47㎡와 달라 계산값을 씁니다.')
})

it('LH 공급 원본 행이 공급행에 없어도 별도 추가를 막지 않고, 이번 보정에서 그대로 두기를 고르면 임시저장본에도 남긴다', async () => {
  const lh = [{ rowIdentifier: 'LH:P:SUPPLY:1', complexLabel: '익산한스빌', typeName: '26B', exclusiveArea: '26.41', householdCount: null, attachedRowKey: null, splitRowKeys: [] }]
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, supply: { ...workspace.supply, lhSupplyRows: lh, unattachedLhSupplyRows: lh } })
  await open()
  expect(screen.queryByText('LH 행 연결 필요')).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: 'LH 행 연결로 이동' })).not.toBeInTheDocument()
  expect(within(openPicker(1)).getByRole('button', { name: '새 공급정보로 추가' })).toBeEnabled()
  const question = within(screen.getByRole('region', { name: 'LH·PDF 대응' })).getByRole('group', { name: "LH 행 '26B 모집수 미확인' 대응" })
  fireEvent.click(within(question).getByRole('button', { name: '이번 보정에서 그대로 두기' }))
  expect(question).toHaveTextContent("LH 행 '26B 모집수 미확인'에는 이번 보정에서 공급정보를 붙이지 않고 그대로 둡니다.")
  fireEvent.click(screen.getByRole('button', { name: '임시저장' }))
  await waitFor(() => expect(draftApi.saveCorrectionDraft.mock.lastCall?.[1].payload.review.keptLhRows).toEqual(['LH:P:SUPPLY:1']))
})

/** 저장해 둔 초안. 검토 화면에서 공급행 1의 주택형 표기를 직접 입력한 상태다. */
function storedDraft(overrides: Record<string, unknown> = {}) {
  const { review } = scenario('hansville')
  review.rows[0].cells = review.rows[0].cells.map(cell => cell.field === 'sourceHousingTypeName' ? { ...cell, mode: 'manual', input: '26A 초안', manualInput: '26A 초안', value: '26A 초안', state: 'changed', selected: true, edited: true, reviewed: true } : cell)
  return { revision: 4, baseSnapshotToken: 'current', pdfSha256: EMPTY_PDF_SHA256, extractionId: 31, updatedAt: '2026-11-03T03:00:00Z', stale: false,
    payload: { version: 1, text: JSON.stringify(fixtures.hansville.extraction), review, reason: '초안 이유', sourceUrl: 'https://example.com/notice', sourcePage: '4', section: 'supply', pdfName: '공고문.pdf',
      baseRows: [{ key: 'row:90', id: 90, label: '익산한스빌 · 아파트', householdCount: 160, exclusiveArea: null, excluded: false }] }, ...overrides }
}

it('편집을 멈추면 잠시 뒤 작업 상태 전체를 임시저장하고, 실패하면 편집을 유지한 채 다시 시도할 수 있다', async () => {
  draftApi.saveCorrectionDraft.mockRejectedValueOnce(new Error('네트워크 오류'))
  await open()
  manual('공급행 1', '이 공고의 주택형 표기')
  fireEvent.change(screen.getByLabelText('공급행 1 이 공고의 주택형 표기 수정'), { target: { value: '26A 관리자' } })
  await waitFor(() => expect(screen.getByText('임시저장 실패')).toBeVisible(), { timeout: 3000 })
  expect(screen.getByLabelText('공급행 1 이 공고의 주택형 표기 수정')).toHaveValue('26A 관리자')
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  await waitFor(() => expect(screen.getByLabelText(/^임시저장 상태: 저장됨/)).toBeInTheDocument())
  const saved = draftApi.saveCorrectionDraft.mock.lastCall!
  expect(saved[0]).toBe('29')
  expect(saved[1]).toMatchObject({ expectedRevision: null, baseSnapshotToken: 'current', pdfSha256: EMPTY_PDF_SHA256, payload: { version: 1, reason: expect.any(String), section: 'supply' } })
  const cell = saved[1].payload.review.rows[0].cells.find((item: { field: string }) => item.field === 'sourceHousingTypeName')
  expect(cell).toMatchObject({ mode: 'manual', manualInput: '26A 관리자' })
  expect(saved[1].payload.review.rows[0].proposal.destination).toBe('row:90')
})

it('다른 탭이 더 최신 초안을 저장했으면 덮어쓰지 않고 알린 뒤 최신 초안을 불러올지 고르게 한다', async () => {
  draftApi.saveCorrectionDraft.mockRejectedValue(new ManagementError('충돌', 409, {}, 'ANNOUNCEMENT_CORRECTION_DRAFT_CONFLICT'))
  await open()
  fireEvent.click(screen.getByRole('button', { name: '임시저장' }))
  expect(await within(screen.getByLabelText('검토 진행')).findByRole('alert')).toHaveTextContent('다른 탭/세션에서 더 최신 초안이 저장됐습니다')
  const calls = draftApi.saveCorrectionDraft.mock.calls.length
  manual('공급행 1', '이 공고의 주택형 표기')
  await new Promise(resolve => setTimeout(resolve, 1700))
  expect(draftApi.saveCorrectionDraft).toHaveBeenCalledTimes(calls)
  draftApi.loadCorrectionDraft.mockResolvedValue(storedDraft({ revision: 9 }))
  fireEvent.click(screen.getByRole('button', { name: '최신 초안 불러오기' }))
  expect(await screen.findByRole('region', { name: '임시저장한 작업' })).toBeVisible()
})

it('저장된 초안이 있으면 바로 적용하지 않고, 공고가 바뀐 내용을 보여 준 뒤 복원하면 선택을 이어 가고 적용 성공 뒤에만 초안을 지운다', async () => {
  const stored = storedDraft({ stale: true })
  stored.payload = { ...stored.payload, warnings: ['LH 공급 1행(익산한스빌, 모집 170)을 PDF 주택형별 2행으로 나눴습니다.'] } as typeof stored.payload
  draftApi.loadCorrectionDraft.mockResolvedValue(stored)
  renderPage()
  const offer = await screen.findByRole('region', { name: '임시저장한 작업' })
  expect(within(offer).getByRole('list', { name: '초안 뒤 바뀐 공급행' })).toHaveTextContent('익산한스빌 · 아파트 (#90): 모집수 160 → 170')
  expect(screen.queryByRole('heading', { name: '공급정보' })).not.toBeInTheDocument()
  expect(draftApi.saveCorrectionDraft).not.toHaveBeenCalled()
  fireEvent.click(within(offer).getByRole('button', { name: '임시저장본 복원' }))
  expect(await screen.findByRole('heading', { name: '공급정보' })).toBeVisible()
  expect(screen.getByLabelText('공급행 1 이 공고의 주택형 표기 수정')).toHaveValue('26A 초안')
  // 복원 안내는 배너로 쌓이지 않고 상단 임시저장 상태 옆에 짧게 남는다.
  expect(within(screen.getByLabelText('검토 진행')).getByRole('status')).toHaveTextContent('최신 공고 값에 맞춰 복원함')
  fireEvent.click(screen.getByRole('button', { name: '안내 펼치기' }))
  expect(screen.getByRole('note', { name: '검산 결과' })).toHaveTextContent('LH 공급 1행(익산한스빌, 모집 170)을 PDF 주택형별 2행으로 나눴습니다.')
  api.applyJsonUpdate.mockRejectedValueOnce(new ManagementError('잘못된 요청', 400))
  await applyCorrection()
  await screen.findByText('잘못된 요청')
  expect(draftApi.deleteCorrectionDraft).not.toHaveBeenCalled()
  await applyCorrection()
  await screen.findByRole('heading', { name: '공고 보정을 적용했습니다.' })
  expect(screen.queryByText(/복원/)).not.toBeInTheDocument()
  await waitFor(() => expect(draftApi.deleteCorrectionDraft).toHaveBeenCalledWith('29', expect.any(Number)))
})

it('복원한 초안으로 검토하다 응답 준비로 돌아가도 다시 추출하지 않고 복원한 입력으로 돌아온다', async () => {
  draftApi.loadCorrectionDraft.mockResolvedValue(storedDraft())
  renderPage()
  fireEvent.click(within(await screen.findByRole('region', { name: '임시저장한 작업' })).getByRole('button', { name: '임시저장본 복원' }))
  await screen.findByRole('heading', { name: '공급정보' })
  fireEvent.click(screen.getByRole('button', { name: '응답 준비로 돌아가기' }))
  fireEvent.click(await screen.findByRole('button', { name: '추출 결과 검토하기' }))
  await screen.findByRole('heading', { name: '공급정보' })
  expect(screen.getByLabelText('공급행 1 이 공고의 주택형 표기 수정')).toHaveValue('26A 초안')
  expect(extractor.extractAnnouncementPdf).not.toHaveBeenCalled()
})

it('저장된 초안을 폐기하면 그 revision으로 지우고 새 작업을 첫 저장으로 시작한다', async () => {
  draftApi.loadCorrectionDraft.mockResolvedValue(storedDraft())
  extracts(JSON.stringify(fixtures.hansville.extraction))
  renderPage()
  fireEvent.click(within(await screen.findByRole('region', { name: '임시저장한 작업' })).getByRole('button', { name: '임시저장본 폐기' }))
  await waitFor(() => expect(draftApi.deleteCorrectionDraft).toHaveBeenCalledWith('29', 4))
  await review()
  await screen.findByRole('heading', { name: '공급정보' })
  fireEvent.click(screen.getByRole('button', { name: '임시저장' }))
  await waitFor(() => expect(draftApi.saveCorrectionDraft).toHaveBeenCalled())
  expect(draftApi.saveCorrectionDraft.mock.calls[0][1].expectedRevision).toBeNull()
})

it('같은 보정 요청을 다시 보내 409(ADMIN_DATA_CONFLICT)를 받으면 최신값을 조회해 이미 반영됐는지 알린다', async () => {
  await open()
  let sent: JsonUpdateInput | undefined
  api.applyJsonUpdate.mockImplementationOnce(async (_id: string, input: JsonUpdateInput) => { sent = input; throw new ManagementError('이미 처리한 요청입니다.', 409, {}, 'ADMIN_DATA_CONFLICT') })
  // 최신값에 공급행·기본정보·일정이 모두 이 요청대로 들어 있다.
  api.loadJsonWorkspace.mockImplementation(async () => {
    const data = structuredClone(workspace.detail.data) as Record<string, unknown>
    for (const field of sent!.fields) {
      const [head, tail] = field.split('.')
      if (tail) data[head] = { ...(data[head] as object | null), [tail]: (sent!.document.receptionPlace as Record<string, unknown>)[tail] }
      else data[field] = sent!.document.announcement[field]
    }
    const events = sent!.schedules.map((selection, at) => ({ id: selection.existingId ?? 9000 + at, data: sent!.document.schedules[selection.inputIndex], complexName: null }))
    return { ...workspace, detail: { ...workspace.detail, data }, events, supply: { ...workspace.supply, rows: sent!.correction.rows } }
  })
  await applyCorrection()
  expect(await screen.findByRole('heading', { name: '공고 보정을 적용했습니다.' })).toBeVisible()
  expect(screen.getByText('이 보정은 이미 반영돼 있습니다. 같은 요청을 다시 저장하지 않았습니다.')).toBeVisible()
  expect(api.applyJsonUpdate).toHaveBeenCalledOnce()
})

it('409(ADMIN_DATA_CONFLICT) 뒤 공급행은 같아도 고른 기본정보·일정이 최신값에 없으면 반영됐다고 하지 않는다', async () => {
  await open()
  let sent: JsonUpdateInput | undefined
  api.applyJsonUpdate.mockImplementationOnce(async (_id: string, input: JsonUpdateInput) => { sent = input; throw new ManagementError('이미 처리한 요청입니다.', 409, {}, 'ADMIN_DATA_CONFLICT') })
  api.loadJsonWorkspace.mockImplementation(async () => ({ ...workspace, supply: { ...workspace.supply, rows: sent!.correction.rows } }))
  await applyCorrection()
  expect(sent!.fields.length + sent!.schedules.length).toBeGreaterThan(0)
  expect(await screen.findByRole('alert')).toHaveTextContent('이 변경은 반영되지 않았습니다')
})

it('409(ADMIN_DATA_CONFLICT) 뒤 최신값에 이 변경이 없으면 반영되지 않았다고 알리고 다시 비교하게 한다', async () => {
  await open()
  manual('공급행 1', '이 공고의 주택형 표기')
  fireEvent.change(screen.getByRole('textbox', { name: '공급행 1 이 공고의 주택형 표기 수정' }), { target: { value: '26A 새 표기' } })
  api.applyJsonUpdate.mockRejectedValueOnce(new ManagementError('이미 처리한 요청입니다.', 409, {}, 'ADMIN_DATA_CONFLICT'))
  await applyCorrection()
  expect(await screen.findByRole('alert')).toHaveTextContent('이 변경은 반영되지 않았습니다')
  expect(screen.getByRole('button', { name: '입력을 유지하고 최신값 비교' })).toBeEnabled()
})

it('적용 전 확인은 통합행 분리 결과와 단지별 변경 전후 합계, 남는 LH 행을 보여 준다', async () => {
  const [first] = workspace.supply.rows
  const lh = { rowIdentifier: 'LH:P:SUPPLY:1', complexLabel: '익산한스빌', typeName: '아파트', exclusiveArea: null, householdCount: 170, attachedRowKey: first.key, splitRowKeys: [] }
  const after = [{ ...first, typeName: '26A', householdCount: 120, housingTypeId: 10353 }, { ...first, id: null, key: 'new-26b', typeName: '26B', householdCount: 50, housingTypeId: 10352 }]
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, supply: { ...workspace.supply, lhSupplyRows: [lh] } })
  api.previewJsonUpdate.mockResolvedValue(previewResult({ supply: { confirmationToken: 's', before: [first], after, warnings: ['남은 LH 행이 있습니다.'],
    splits: [{ lhSourceRowIdentifier: 'LH:P:SUPPLY:1', rowKeys: [first.key, 'new-26b'], householdCount: 170, lhHouseholdCount: 170 }],
    unattachedLhSupplyRows: [{ rowIdentifier: 'LH:P:SUPPLY:2', complexLabel: '익산한스빌', typeName: '36', exclusiveArea: null, householdCount: 4 }] } }))
  await open()
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  const summary = await screen.findByRole('region', { name: '적용 전 확인' })
  expect(within(summary).getByRole('group', { name: /^통합행 분리/ })).toHaveTextContent('통합행 #90 아파트 · 모집 170 → 첫 분리행 26A 120세대(ID 유지) · 새 분리행 26B 50세대분리 합계 170세대 / LH 170세대 · 일치')
  expect(within(summary).getByRole('region', { name: '익산한스빌 적용 전후' })).toHaveTextContent('모집수 합계 170세대 → 170세대')
  expect(within(summary).getByRole('list', { name: '익산한스빌 변경 후' })).toHaveTextContent('26B50세대')
  expect(within(summary).getByRole('note', { name: '남는 LH 행' })).toHaveTextContent('LH 행 3 · 익산한스빌 · 36')
  fireEvent.click(within(summary).getByRole('button', { name: '돌아가서 수정' }))
  expect(screen.queryByRole('region', { name: '적용 전 확인' })).not.toBeInTheDocument()
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
})

it('공급행 목록은 행마다 저장할 곳과 상태를 요약하고, 단지 주택형 패널에서 기존 주택형을 고른 행에 연결해도 패널을 닫았다 열면 그대로다', async () => {
  await open(JSON.stringify(separateHansville()))
  const list = screen.getByRole('region', { name: '공급행 목록' })
  expect(within(list).getAllByRole('row').slice(2).map(row => row.textContent)).toEqual(['1. 26A26.5195㎡170#90 고침확인 필요', '2. 26B26.4673㎡미확인새로 추가확인 필요'])
  fireEvent.click(screen.getByRole('button', { name: '단지 주택형 보기' }))
  const panel = screen.getByRole('complementary', { name: '익산한스빌 · 국민임대 주택형' })
  const types = within(panel).getAllByRole('listitem')
  expect(types[1]).toHaveTextContent('단지 정보 · 건설호수486세대')
  expect(types[1]).toHaveTextContent('이번 공고 · 모집수없음')
  expect(within(panel).getByRole('heading', { name: '새 주택형 생성' })).toBeVisible()
  fireEvent.click(within(panel).getByRole('button', { name: '26 · 전용 26.4673㎡를 공급행 1에 연결' }))
  expect(screen.getByRole('combobox', { name: '공급행 1 연결 주택형 수정' })).toHaveValue('10352')
  fireEvent.click(within(panel).getByRole('button', { name: '주택형 패널 닫기' }))
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '단지 주택형 보기' }))
  expect(within(screen.getByRole('complementary')).getByText('공급행 1에 연결함')).toBeVisible()
  expect(screen.getByRole('combobox', { name: '공급행 1 연결 주택형 수정' })).toHaveValue('10352')
})

it('공급정보는 PDF 단지마다 탭으로 나누고, 탭 이름에 단지 이름과 그 단지의 확인 수를 보이며 화살표로 옮기고 미해결 이동은 그 행의 단지 탭을 연다', async () => {
  await open(JSON.stringify(fixtures.gwangju.extraction))
  const tabs = within(screen.getByRole('tablist', { name: 'PDF 보정 영역' })).getAllByRole('tab')
  expect(tabs.map(tab => tab.textContent?.split(' · ')[0])).toEqual(['첨단H-1BL', '첨단H-2BL', '첨단H-3BL', '일정', '접수처·기본정보'])
  expect(tabs[0]).toHaveAttribute('aria-selected', 'true')
  expect(tabs[2].textContent).toMatch(/^첨단H-3BL · 확인 \d+$/)
  expect(screen.getByRole('tabpanel')).toHaveAccessibleName(tabs[0].textContent!)
  fireEvent.keyDown(tabs[0], { key: 'ArrowRight' })
  expect(screen.getByRole('tab', { name: /^첨단H-2BL/ })).toHaveAttribute('aria-selected', 'true')
  expect(screen.getByRole('region', { name: '공급행 2 검토' })).toBeVisible()
  fireEvent.click(screen.getByRole('tab', { name: /^첨단H-3BL/ }))
  expect(screen.getByRole('region', { name: '공급행 4 검토' })).toBeVisible()
  expect(screen.queryByRole('button', { name: '공급행 1 편집' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /^미해결 \d+개/ }))
  expect(screen.getByRole('tab', { name: /^첨단H-1BL/ })).toHaveAttribute('aria-selected', 'true')
  expect(screen.getByRole('region', { name: '공급행 1 검토' })).toHaveFocus()
})

it('공급행 목록에서 행을 고르면 PDF를 그 행의 주택형 표기 근거 쪽으로 옮겨 강조한다', async () => {
  await open(JSON.stringify(fixtures.gwangju.extraction))
  fireEvent.click(screen.getByRole('tab', { name: /^첨단H-3BL/ }))
  const list = screen.getByRole('region', { name: '공급행 목록' })
  const button = within(list).getAllByRole('button', { name: /^공급행 \d+ 편집$/ }).at(-1)!
  fireEvent.click(button)
  // 좁은 화면에서는 편집 영역을 그대로 두므로 PDF 영역을 열어 확인한다.
  expect(screen.getByRole('region', { name: /^공급행 \d+ 검토$/ })).toBeVisible()
  showPdf()
  expect(await screen.findByText(/^PDF \d+쪽 · 강조 (?!없음)/)).toBeVisible()
})

it('공급정보 편집표의 PDF 채우기도 단지마다 탭으로 나눠 다른 단지 행을 고를 수 있다', async () => {
  extracts(JSON.stringify(fixtures.gwangju.extraction))
  render(<MemoryRouter><PdfCorrectionWorkspace id="29" supplyDraft={{ rows: structuredClone(workspace.supply.rows), onFill: vi.fn(), onCancel: vi.fn() }} /></MemoryRouter>)
  await review()
  const tabs = within(screen.getByRole('tablist', { name: 'PDF 보정 영역' })).getAllByRole('tab')
  expect(tabs.map(tab => tab.textContent?.split(' · ')[0])).toEqual(['첨단H-1BL', '첨단H-2BL', '첨단H-3BL'])
  fireEvent.click(tabs[2])
  expect(screen.getByRole('region', { name: '공급행 4 검토' })).toBeVisible()
})

it('임시저장본은 열어 둔 단지 탭과 고른 공급행을 함께 저장하고 복원한다', async () => {
  await open(JSON.stringify(fixtures.gwangju.extraction))
  fireEvent.click(screen.getByRole('tab', { name: /^첨단H-3BL/ }))
  fireEvent.click(screen.getByRole('button', { name: '공급행 5 편집' }))
  fireEvent.click(screen.getByRole('button', { name: '임시저장' }))
  await waitFor(() => expect(draftApi.saveCorrectionDraft.mock.lastCall?.[1].payload).toMatchObject({ section: 'supply', selectedRow: 4 }))
  cleanup()
  const saved = draftApi.saveCorrectionDraft.mock.lastCall![1]
  draftApi.loadCorrectionDraft.mockResolvedValue({ revision: 2, baseSnapshotToken: 'current', pdfSha256: null, extractionId: null, updatedAt: '2026-11-03T03:00:00Z', stale: false, payload: saved.payload })
  renderPage()
  fireEvent.click(within(await screen.findByRole('region', { name: '임시저장한 작업' })).getByRole('button', { name: '임시저장본 복원' }))
  expect(await screen.findByRole('tab', { name: /^첨단H-3BL/ })).toHaveAttribute('aria-selected', 'true')
  expect(screen.getByRole('region', { name: '공급행 5 검토' })).toBeVisible()
})
