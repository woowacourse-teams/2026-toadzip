import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, expect, it, onTestFinished, vi } from 'vitest'
import { PdfCorrectionPage, PdfCorrectionWorkspace } from './PdfCorrectionPage'
import { savedSheet, scenario } from './fixtures/workspace'
import { draftBaseSheet } from './correctionDraftApi'
import fixtures from './fixtures/scenarios.json'
import type { JsonPreview, JsonUpdateInput } from '../announcement-json/api'
import { ManagementError } from '../management/api'
import type { SupplySheet } from '../supply/supplySheet'
const api = vi.hoisted(() => ({ loadJsonWorkspace: vi.fn(), previewJsonUpdate: vi.fn(), applyJsonUpdate: vi.fn() }))
vi.mock('../announcement-json/api', () => api)
const registration = vi.hoisted(() => ({ createHousingComplex: vi.fn() }))
vi.mock('../registration/api', async original => ({ ...await original<typeof import('../registration/api')>(), ...registration }))
const lookup = vi.hoisted(() => ({ lookupAddresses: vi.fn(async () => []) }))
vi.mock('./addressLookupApi', () => lookup)
const extractor = vi.hoisted(() => ({ extractAnnouncementPdf: vi.fn(), resumeExtraction: vi.fn(), loadLatestExtraction: vi.fn(), cancelExtraction: vi.fn() }))
vi.mock('./extractionApi', async original => ({ ...await original<typeof import('./extractionApi')>(), ...extractor }))
const draftApi = vi.hoisted(() => ({ loadCorrectionDraft: vi.fn(), saveCorrectionDraft: vi.fn(), deleteCorrectionDraft: vi.fn() }))
vi.mock('./correctionDraftApi', async original => ({ ...await original<typeof import('./correctionDraftApi')>(), ...draftApi }))
const eligibility = vi.hoisted(() => ({ loadEligibility: vi.fn(async () => null), createEligibilityDraft: vi.fn(() => new Promise(() => {})) }))
vi.mock('./eligibilityDraftApi', async original => ({ ...await original<typeof import('./eligibilityDraftApi')>(), ...eligibility }))
const matchingTypes = vi.hoisted(() => ({ getMatchingHousingTypes: vi.fn() }))
vi.mock('../ingest/supplyMatchingApi', () => matchingTypes)
vi.mock('../../public-housing/api/announcementAttachments', () => ({ loadAnnouncementAttachment: async () => new Blob(), attachmentErrorMessage: () => 'PDF 열기 실패' }))
vi.mock('../../public-housing/components/PdfDocumentPreview', () => ({ default: ({ focus }: { focus: { page: number; query: string | string[] } | null }) => <p>PDF {focus?.page ?? 1}쪽 · 강조 {(Array.isArray(focus?.query) ? focus.query.join(' | ') : focus?.query) || '없음'}</p> }))
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(),
  getManagementPage: async () => ({ items: [{ id: 7, name: '검색 단지', subtitle: '주소 확인' }] }),
  getManagementDetail: async () => ({ data: { name: '익산한스빌', rentalType: 'NATIONAL_RENTAL', address: { pnu: '1114010100100010000' } } }) }))
// 이 공고문을 이미 반영해 단지 3055에 연결된 공급표다. PDF 주택형 26A·26B가 지금 주택형과 짝지어진다.
const base = scenario('hansville', undefined, extracted => savedSheet(extracted, { id: 3055, name: '익산한스빌' })).workspace
const workspace = { ...base, detail: { ...base.detail, data: { ...base.detail.data, name: '익산한스빌 국민임대주택 예비입주자 자격완화 모집 공고(2026.10.08)', postedDate: '2026-10-08' }, attachments: [{ attachmentId: 68, fileName: '공고문.pdf', fileType: 'ANNOUNCEMENT', fileUrl: 'https://example.com/notice.pdf' }] } }
beforeEach(() => {
  vi.clearAllMocks()
  window.sessionStorage.clear()
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
/** 서버 미리보기 응답. 적용할 때 검증과 확인 토큰에 쓴다. */
function previewResult(more: Partial<JsonPreview> = {}): JsonPreview {
  return { confirmationToken: 'confirmed', fields: [], supply: null, schedules: [], attachments: [], housingTypes: [], housingTypeWarnings: [], warnings: [], ...more }
}
/** 보정 내용 적용 한 번으로 서버 검증과 저장을 한다. */
async function applyCorrection() {
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
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
/** 고정 주택형 막대에서 PDF 단지를 고른다. */
function chooseComplex(name: string) {
  const select = screen.getByRole('combobox', { name: '단지' }) as HTMLSelectElement
  fireEvent.change(select, { target: { value: [...select.options].find(option => option.textContent?.startsWith(`${name} · `))!.value } })
}
/** 주택형 목록에서 그 주택형을 골라 편집 영역에 연다. */
function showRow(row: number) {
  fireEvent.click(within(screen.getByRole('region', { name: '주택형 목록' })).getByRole('button', { name: new RegExp(`^${row}\\. `) }))
}
/** 그 칸을 직접 입력으로 바꾼다. 지금 값과 같아 접혀 있으면 같은 값 칸을 펼친다. */
function manual(scope: string, label: string) {
  const name = `${scope} ${label} 반영 방법`
  if (!screen.queryByRole('group', { name })) for (const button of screen.getAllByRole('button', { name: /^같음·해당 없음 \d+개 보기$/ })) fireEvent.click(button)
  fireEvent.click(within(screen.getByRole('group', { name: `${scope} ${label} 반영 방법` })).getByRole('button', { name: '직접 입력' }))
}
it('보정 내용 적용 한 번으로 서버 검증을 거쳐 같은 보정안을 한 번 저장한다', async () => {
  let finish!: (value: JsonPreview) => void
  api.previewJsonUpdate.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  await open()
  expect(screen.queryByRole('checkbox', { name: /확인함|대조함/ })).not.toBeInTheDocument()
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  expect(screen.getByRole('button', { name: '적용 중…' })).toBeDisabled()
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
  const input = api.previewJsonUpdate.mock.calls[0][1] as JsonUpdateInput
  // 26A·26B는 지금 공급표의 두 주택형을 고치고, 두 주택형이 함께 쓰는 모집 170은 묶음 하나로 보낸다. 옛 공급행 필드는 보내지 않는다.
  expect(Object.keys(input.correction).sort()).toEqual(['operationId', 'reason', 'sheet', 'snapshotToken', 'sourcePage', 'sourceUrl'])
  expect(input.correction.sheet.housingTypes.map(type => [type.key, type.id, type.housingComplexId, type.name])).toEqual([['row:1', 1, 3055, '26A'], ['row:2', 2, 3055, '26B']])
  expect(input.correction.sheet.quotas).toEqual([{ kind: 'WINNER', count: 170, members: ['cell:1', 'cell:2'] }])
  for (const field of ['rows', 'supplyRows', 'saveMode', 'lhSourceRowIdentifier', 'newGroups']) expect(JSON.stringify(input)).not.toContain(`"${field}"`)
  await act(async () => finish(previewResult({ housingTypeWarnings: ['26: 관리자가 고친 세대수를 유지합니다.'], fields: [{ field: 'applicationStartDate', before: '2026-10-20', after: '2026-10-27' }],
    housingTypes: [{ rowKey: 'row:1', housingTypeId: 10353, typeName: '26', before: { name: '26', supplyArea: null, householdCount: null }, after: { name: '26A', supplyArea: 52.5, householdCount: 120 } }] })))
  await screen.findByRole('heading', { name: '공고 보정을 적용했습니다.' })
  expect(screen.queryByRole('region', { name: '적용 전 확인' })).not.toBeInTheDocument()
  // 적용이 끝나면 누르지 않아도 열린 PDF의 추출로 신청자격을 만들어 반영한다.
  await waitFor(() => expect(eligibility.createEligibilityDraft).toHaveBeenCalledExactlyOnceWith('29', expect.stringMatching(/^[0-9a-f]{64}$/), null))
  expect(api.applyJsonUpdate).toHaveBeenCalledExactlyOnceWith('29', input, 'confirmed')
  expect(screen.queryByRole('heading', { name: '적용할 변경' })).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '실패 검토로 돌아가기' })).toHaveAttribute('href', '/admin/failures?domain=announcement')
  const result = screen.getByRole('heading', { name: '공고 보정을 적용했습니다.' }).closest('section')!
  expect(result).toHaveTextContent('이 공고의 미해결 오류를 해결로 바꿨습니다.')
  expect(screen.getByRole('link', { name: '저장된 공급정보 확인' }).getAttribute('href')).toContain('section=relations')
  expect(screen.getByRole('link', { name: '출처·수정 이력 보기' }).getAttribute('href')).toContain('section=history')
  expect(within(screen.getByRole('region', { name: '공고로 갱신한 주택타입' })).getAllByRole('listitem').map(item => item.textContent)).toEqual(['#10353 「26」 → 이름 26A · 공급면적 52.5㎡ · 세대수 120', '26: 관리자가 고친 세대수를 유지합니다.'])
})
it('서버가 저장할 변경이 없다고 해도 오류 없이 적용해 공고문 확인을 남기고 변경 없음 결과를 보인다', async () => {
  api.previewJsonUpdate.mockResolvedValue(previewResult({ unchanged: true }))
  await open()
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  await screen.findByRole('heading', { name: '저장할 변경이 없습니다.' })
  const result = screen.getByRole('heading', { name: '저장할 변경이 없습니다.' }).closest('section')!
  expect(result).toHaveTextContent('공고문으로 확인한 것으로 남겼습니다.')
  expect(api.applyJsonUpdate).toHaveBeenCalledExactlyOnceWith('29', api.previewJsonUpdate.mock.calls[0][1], 'confirmed')
  expect(within(result).getByRole('link', { name: '실패 검토로 돌아가기' })).toHaveAttribute('href', '/admin/failures?domain=announcement')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})
/** GH·SH 원천으로 만든 미확정 공고. 임대·모집 유형이 비어 있고 사용자에게 보이지 않는다. */
function unconfirmedWorkspace() {
  return { ...workspace, detail: { ...workspace.detail,
    summary: { ...workspace.detail.summary, announcement: { ...(workspace.detail.summary as { announcement?: object }).announcement, releaseStatus: 'UNCONFIRMED' } },
    data: { ...workspace.detail.data, rentalType: null, recruitmentType: null } } }
}
it('미확정 공고는 임대·모집 유형을 고르고 보정을 적용하면 따로 공개를 요청하지 않아도 공개 요청을 보내고 결과에 공개를 알린다', async () => {
  api.loadJsonWorkspace.mockResolvedValue(unconfirmedWorkspace())
  api.previewJsonUpdate.mockResolvedValue(previewResult({ publishes: true, warnings: ['미확정 공고를 공개합니다. 적용하면 사용자에게 보이고 신규 공고 알림이 나갑니다.'] }))
  await open()
  const release = screen.getByRole('region', { name: '공개 전 확인' })
  fireEvent.change(within(release).getByLabelText('공급 유형'), { target: { value: 'NATIONAL_RENTAL' } })
  fireEvent.change(within(release).getByLabelText('모집 유형'), { target: { value: 'WAITLIST' } })
  expect(within(release).queryByRole('checkbox')).not.toBeInTheDocument()

  await applyCorrection()

  const input = api.previewJsonUpdate.mock.calls[0][1] as JsonUpdateInput
  expect(input.publish).toBe(true)
  expect(input.fields).toEqual(expect.arrayContaining(['rentalType', 'recruitmentType']))
  expect(input.document.announcement).toEqual(expect.objectContaining({ rentalType: 'NATIONAL_RENTAL', recruitmentType: 'WAITLIST' }))
  expect(await screen.findByRole('heading', { name: '공고를 공개했습니다.' })).toBeInTheDocument()
})
it('미확정 공고의 주택형이 단지에 연결되지 않아 공개가 거절되면 저장된 것으로 보지 않고 이유를 알린다', async () => {
  api.loadJsonWorkspace.mockResolvedValue(unconfirmedWorkspace())
  api.previewJsonUpdate.mockRejectedValue(new ManagementError('공개하려면 모든 주택형을 삭제되지 않은 단지에 연결해야 합니다.', 400, {}, 'INVALID_REQUEST'))
  await open()
  const release = screen.getByRole('region', { name: '공개 전 확인' })
  fireEvent.change(within(release).getByLabelText('공급 유형'), { target: { value: 'NATIONAL_RENTAL' } })
  fireEvent.change(within(release).getByLabelText('모집 유형'), { target: { value: 'WAITLIST' } })

  await applyCorrection()

  expect(await screen.findByRole('alert')).toHaveTextContent('공개하려면 모든 주택형을 삭제되지 않은 단지에 연결해야 합니다.')
  expect((api.previewJsonUpdate.mock.calls[0][1] as JsonUpdateInput).publish).toBe(true)
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
  expect(eligibility.createEligibilityDraft).not.toHaveBeenCalled()
  expect(screen.queryByRole('heading', { name: '공고를 공개했습니다.' })).not.toBeInTheDocument()
  expect(within(screen.getByRole('region', { name: '공개 전 확인' })).getByLabelText('공급 유형')).toHaveValue('NATIONAL_RENTAL')
})
it('공개된 공고에는 공개 전 확인을 보이지 않는다', async () => {
  await open()
  expect(screen.queryByRole('region', { name: '공개 전 확인' })).not.toBeInTheDocument()
})
it('AI 워커가 원문과 대조한 경고를 검토 화면의 검산 확인에도 보여 준다', async () => {
  extractor.extractAnnouncementPdf.mockResolvedValue({ json: JSON.stringify(fixtures.hansville.extraction), pages: [1, 2], totalPages: 3, warnings: ['housingTypes[0].name: 근거 인용에 없는 주택형 이름(24A)'], usage: { promptTokens: 100, outputTokens: 50, thinkingTokens: null } })
  renderPage()
  await review()
  fireEvent.click(await screen.findByRole('button', { name: '안내 펼치기' }))
  const checks = await screen.findByRole('note', { name: '검산 결과' })
  expect(checks).toHaveTextContent('근거 인용에 없는 주택형 이름(24A)')
})

it('검산 경고와 저장하면 지울 주택형은 확인 필요가 없어도 하단 요약에 따로 알리고, 검산 경고를 누르면 다른 탭에서도 공급정보 안내로 옮긴다', async () => {
  // 지금 공급표에만 있는 59Z는 이 PDF 단지에 있지만 PDF 공급표에 없어 저장하면 지워진다.
  const [first] = workspace.supply.sheet.housingTypes
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, supply: { ...workspace.supply, sheet: { ...workspace.supply.sheet, housingTypes: [...workspace.supply.sheet.housingTypes, { ...first, key: 'extra', name: '59Z', displayOrder: 99 }] } } })
  extractor.extractAnnouncementPdf.mockResolvedValue({ json: JSON.stringify(fixtures.hansville.extraction), pages: [1, 2], totalPages: 3, warnings: ['59Z(가)가 추출 결과에 없음'], usage: { promptTokens: 100, outputTokens: 50, thinkingTokens: null } })
  renderPage()
  await review()
  const footer = screen.getByRole('button', { name: '보정 내용 적용' }).closest('footer')!
  expect(footer).toHaveTextContent('저장하면 주택형 1개를 지웁니다.')
  fireEvent.click(screen.getByRole('tab', { name: /^일정/ }))
  fireEvent.click(within(footer).getByRole('button', { name: /^검산 경고 \d+건$/ }))
  expect(screen.getByRole('tab', { name: /^공급정보/ })).toHaveAttribute('aria-selected', 'true')
  expect(document.activeElement).toHaveAttribute('data-review-id', 'notices')
  expect(within(screen.getByRole('list', { name: '안내 요약' })).getByText('PDF에 없는 주택형 1개 삭제')).toBeVisible()
})

it('주택형을 PDF 단지 이름별로 묶어 한 단지의 주택형을 한곳에 보여 주고, 쓰지 않는 변경 목록은 두지 않는다', async () => {
  await open()
  // 목록은 단지별로 모든 주택형을 한 줄씩 요약하고, 편집 영역은 고른 행 하나만 보인다.
  const list = screen.getByRole('region', { name: '주택형 목록' })
  expect(within(list).getByRole('rowheader', { name: /^익산한스빌/ })).toHaveTextContent('익산한스빌 · 주택형 2개 · 모집 합')
  expect(within(list).getAllByRole('button', { name: /^\d+\. / }).map(button => button.textContent)).toEqual(['1. 26A', '2. 26B'])
  // 편집 영역 머리는 목록과 같은 '번호. 형명'이고, 고정 주택형 막대도 같은 행을 보인다.
  const block = screen.getByRole('region', { name: '주택형 1 검토' })
  expect(within(block).getByRole('heading', { name: '1. 26A' })).toBeVisible()
  expect(within(screen.getByRole('group', { name: '주택형 이동' })).getByText('1. 26A')).toBeVisible()
  expect(screen.queryByRole('region', { name: '주택형 2 검토' })).not.toBeInTheDocument()
  showRow(2)
  expect(screen.getByRole('region', { name: '주택형 2 검토' })).toBeVisible()
  expect(screen.queryByText(/반영할 변경 \d+개 보기/)).not.toBeInTheDocument()
})
it('형식 오류와 칸 수정을 제공하고 PDF 근거 쪽으로 이동해 그 값을 강조한다', async () => {
  await open()
  const row = screen.getByRole('region', { name: '주택형 1 검토' })
  fireEvent.click(within(row).getByRole('button', { name: '대상 1 대조·수정' }))
  manual('주택형 1 대상 1', '보증금 (원)')
  const input = screen.getByRole('textbox', { name: '주택형 1 대상 1 보증금 (원) 수정' })
  fireEvent.change(input, { target: { value: '4,076,000' } })
  expect(input).toHaveAttribute('aria-invalid', 'true')
  expect(within(screen.getByRole('group', { name: '주택형 1 대상 1 보증금 (원) 반영 방법' })).getByRole('button', { name: '직접 입력' })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.change(input, { target: { value: '4076000' } })
  const details = screen.getByRole('region', { name: '주택형 1 대상 1 수정' })
  fireEvent.click(within(details).getAllByRole('button', { name: 'PDF 5쪽 보기' })[0])
  // 금액은 PDF 표기(천 단위 쉼표)로 그 값만, 값이 인용문에 없으면 인용문에서 고른 숫자를 강조한다.
  expect(await screen.findByText('PDF 5쪽 · 강조 4,076,000')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '검토표 보기' }))
  fireEvent.click(within(row).getByRole('button', { name: 'PDF 5쪽에서 대상 1 월 임대료 (원) 보기' }))
  expect(await screen.findByText('PDF 5쪽 · 강조 72,820')).toBeVisible()
  // 셀의 p.N도 그 셀의 값을 강조한다. 다른 값을 먼저 강조해 두고 바뀌는지 본다.
  fireEvent.click(screen.getByRole('button', { name: '검토표 보기' }))
  fireEvent.click(within(row).getByRole('button', { name: 'PDF 5쪽에서 대상 1 보증금 (원) 보기' }))
  expect(await screen.findByText('PDF 5쪽 · 강조 4,076,000')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '검토표 보기' }))
  const rent = within(screen.getByRole('region', { name: '주택형 1 대상 1 수정' })).getAllByRole('button', { name: 'PDF 5쪽 보기' }).find(button => button.closest('[data-review-id]')?.getAttribute('data-review-id')?.endsWith('.monthlyRent'))!
  fireEvent.click(rent)
  expect(await screen.findByText('PDF 5쪽 · 강조 72,820')).toBeVisible()
})
it.each(['preview', 'apply'])('%s의 409는 저장을 반복하지 않고 최신값을 조회해 수동 입력을 보존한다', async stage => {
  const failed = stage === 'preview' ? api.previewJsonUpdate : api.applyJsonUpdate
  failed.mockRejectedValueOnce(new ManagementError('현재값이 변경되었습니다.', 409))
  await open()
  manual('주택형 1', '주택형')
  fireEvent.change(screen.getByRole('textbox', { name: '주택형 1 주택형 수정' }), { target: { value: '26A 수동 보정' } })
  if (stage === 'apply') await applyCorrection(); else fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  const reload = await screen.findByRole('button', { name: '입력을 유지하고 최신값 비교' })
  expect(screen.getByRole('button', { name: '보정 내용 적용' })).toBeDisabled()
  if (stage === 'preview') expect(api.applyJsonUpdate).not.toHaveBeenCalled()
  fireEvent.click(reload)
  await screen.findByRole('status')
  expect(screen.getByRole('textbox', { name: '주택형 1 주택형 수정' })).toHaveValue('26A 수동 보정')
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
  // 하단 상태는 오른쪽 추출 상태와 같은 기준으로 추출 중·실패를 알린다.
  expect(await screen.findByText('추출 중입니다')).toBeVisible()
  fireEvent.click(await screen.findByRole('button', { name: '추출 취소' }))
  await waitFor(() => expect(extractor.cancelExtraction).toHaveBeenCalledWith('29', 31))
  expect(screen.getByRole('button', { name: '추출 취소' })).toBeVisible()
  expect(signal?.aborted).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: '추출 취소' }))
  expect(await screen.findByText('관리자가 추출을 취소했습니다. 다시 요청할 수 있습니다.')).toBeVisible()
  expect(signal?.aborted).toBe(true)
  expect(screen.getByRole('button', { name: '다시 추출' })).toBeVisible()
  expect(screen.getByText('추출하지 못했습니다')).toBeVisible()
  expect(window.sessionStorage.getItem('pdf-extraction-job:29')).toBeNull()
})
it('돌아왔을 때 남긴 작업이 지금 PDF와 다른 파일이면 실패로 알리지 않고 추출 전으로 둔다', async () => {
  window.sessionStorage.setItem('pdf-extraction-job:29', '31')
  const { OtherPdfJobError } = await vi.importActual<typeof import('./extractionApi')>('./extractionApi')
  extractor.resumeExtraction.mockRejectedValue(new OtherPdfJobError())
  renderPage()
  await waitFor(() => expect(extractor.resumeExtraction).toHaveBeenCalledOnce())
  expect(await screen.findByRole('button', { name: 'PDF에서 값 추출' })).toBeVisible()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(window.sessionStorage.getItem('pdf-extraction-job:29')).toBeNull()
})
it('추출을 기다리다 화면을 떠났다 돌아오면 그 작업에 이어 붙어 끝난 결과를 넣고, 끝난 뒤에는 다시 이어 붙지 않는다', async () => {
  let signal: AbortSignal | undefined
  extractor.extractAnnouncementPdf.mockImplementation((_id: string, _file: Blob, _name: string, given: AbortSignal, options: { onJob?: (jobId: number) => void }) => {
    signal = given; options.onJob?.(31)
    return new Promise(() => {})
  })
  extractor.resumeExtraction.mockResolvedValue({ json: JSON.stringify(fixtures.hansville.extraction), pages: [1, 2], totalPages: 3, warnings: [], usage: { promptTokens: 100, outputTokens: 50, thinkingTokens: null } })
  renderPage()
  await startExtraction()
  expect(await screen.findByText('추출 중입니다')).toBeVisible()
  cleanup()
  expect(signal?.aborted).toBe(true)

  renderPage()
  expect(await screen.findByText('추출을 마쳤습니다')).toBeVisible()
  expect(extractor.resumeExtraction).toHaveBeenCalledWith('29', 31, expect.any(Blob), expect.any(AbortSignal), expect.objectContaining({ attachmentId: '68' }))
  expect(extractor.extractAnnouncementPdf).toHaveBeenCalledOnce()
  cleanup()

  renderPage()
  expect(await screen.findByRole('button', { name: 'PDF에서 값 추출' })).toBeVisible()
  expect(extractor.resumeExtraction).toHaveBeenCalledOnce()
})
it('떠난 사이 작업이 실패했으면 돌아왔을 때 그 사유를 알리고 다시 추출하게 한다', async () => {
  window.sessionStorage.setItem('pdf-extraction-job:29', '31')
  extractor.resumeExtraction.mockRejectedValue(new Error('추출 서비스 응답이 늦습니다.'))
  renderPage()
  expect(await screen.findByText('추출 서비스 응답이 늦습니다.')).toBeVisible()
  expect(screen.getByRole('button', { name: '다시 추출' })).toBeVisible()
  expect(window.sessionStorage.getItem('pdf-extraction-job:29')).toBeNull()
})
it('서버가 칸 경로로 거절하면 화면 칸 이름으로 알리고 그 칸이 있는 접수처·기본정보로 옮긴다', async () => {
  api.previewJsonUpdate.mockRejectedValue(new ManagementError('receptionPlaces[0].name: 필수 값입니다.', 400, {}, 'INVALID_REQUEST'))
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
  expect(await screen.findByText(/주택형 2개 · 대상별 칸 2개 · 일정 3개/)).toBeVisible()
  expect(screen.queryByRole('heading', { name: '공급정보' })).not.toBeInTheDocument()
})
it('추출 뒤 PDF를 바꾸면 이전 결과를 버리고 다시 추출하게 한다', async () => {
  renderPage()
  await startExtraction()
  expect(await screen.findByText(/주택형 2개 · 대상별 칸 2개 · 일정 3개/)).toBeVisible()
  fireEvent.change(screen.getByLabelText('내 PC에서 PDF 고르기'), { target: { files: [new File(['%PDF'], '정정 공고문.pdf', { type: 'application/pdf' })] } })
  expect(await screen.findByRole('button', { name: 'PDF에서 값 추출' })).toBeVisible()
  expect(screen.queryByText(/주택형 2개/)).not.toBeInTheDocument()
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
  expect(await screen.findByText(/주택형 2개 · 대상별 칸 2개 · 일정 3개/)).toBeVisible()
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
  // 근거 입력은 화면에서 하단 버튼 위에 있으므로 Tab 순서도 버튼보다 앞이어야 한다.
  expect(screen.getByRole('textbox', { name: '공식 근거 URL' }).compareDocumentPosition(screen.getByRole('button', { name: '보정 내용 적용' })) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
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
  // 지금 공급표와 같은 26A·26B에는 확인 필요가 없다. 남은 확인 필요는 일정 하나다.
  await open()
  expect(screen.getByRole('tab', { name: '일정 · 확인 1' })).toBeVisible()
  fireEvent.click(within(screen.getByRole('group', { name: '검토 항목 필터' })).getByRole('button', { name: '확인 필요 1' }))
  fireEvent.click(screen.getByRole('button', { name: '미해결 1개 ↓' }))
  expect(screen.getByRole('heading', { name: '일정' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '일정 2 적용 안 함' }))
  expect(screen.getByText('확인 필요 없음')).toBeVisible()
  fireEvent.click(within(screen.getByRole('group', { name: '검토 항목 필터' })).getByRole('button', { name: '전체' }))
  expect(screen.getByRole('button', { name: '일정 2 적용 안 함' })).toHaveAttribute('aria-pressed', 'true')
  expect(within(screen.getByRole('list', { name: '일정 대조표' })).getByText('적용 안 함', { selector: 'span' })).toBeVisible()
})
it('다른 공고의 PDF면 공고명·공고일이 다르다고 알리고 검토 화면에도 남긴다', async () => {
  extracts(JSON.stringify(fixtures.gwangju.extraction))
  renderPage()
  await startExtraction()
  expect(await screen.findByRole('alert')).toHaveTextContent('현재 공고와 다른 PDF일 수 있습니다 · 공고명 다름(PDF 광주첨단 H-1,2,3BL 행복주택 예비입주자 모집) · 공고일 다름(PDF 2026-10-15)')
  await review(false)
  expect(await screen.findByText(/이 공고의 PDF로 받은 응답인지 확인하세요/)).toBeVisible()
})

it('공급정보의 PDF 채우기는 검토한 공급표를 저장 없이 편집표에 돌려준다', async () => {
  const onFill = vi.fn<(sheet: SupplySheet) => void>()
  render(<MemoryRouter><PdfCorrectionWorkspace id="29" supplyDraft={onFill} /></MemoryRouter>)
  await review()
  expect(screen.queryByRole('navigation', { name: 'PDF 보정 영역' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '공급정보 편집표에 채우기' }))
  await waitFor(() => expect(onFill).toHaveBeenCalledOnce())
  const sheet = onFill.mock.calls[0][0]
  expect(sheet.housingTypes.map(type => [type.key, type.name, type.housingComplexId])).toEqual([['row:1', '26A', 3055], ['row:2', '26B', 3055]])
  expect(sheet.quotas.map(quota => [quota.kind, quota.count, quota.members])).toEqual([['WINNER', 170, ['cell:1', 'cell:2']]])
  expect(api.previewJsonUpdate).not.toHaveBeenCalled()
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
})

it('실패 검토에서 연 보정도 결과에서 다시 정제를 실행하지 않고 해결한 오류만 알린다', async () => {
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

it('실패 검토에서 열면 고칠 오류 사유를 먼저 보여 준다', async () => {
  render(<MemoryRouter initialEntries={['/admin/announcements/29/pdf-correction?failureCount=2&failureRow=AMBIGUOUS_HOUSING_TYPE%7C5%3A213951%3A1&failureRow=HOUSING_TYPE_NOT_FOUND%7C9%3A9%3A9&failureReturnTo=%2Fadmin%2Ffailures']}><Routes><Route path="/admin/announcements/:id/pdf-correction" element={<PdfCorrectionPage />} /></Routes></MemoryRouter>)
  const note = await screen.findByRole('note', { name: '고칠 오류' })
  expect(note).toHaveTextContent('같은 면적의 주택타입이 여러 개라 하나로 정하지 못했습니다')
  expect(note).toHaveTextContent('맞는 주택타입을 찾지 못했습니다')
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

it('적용할 수 없는 값이 있으면 오류를 알리고 그 항목으로 이동한다', async () => {
  const scroll = vi.fn()
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scroll })
  onTestFinished(() => { delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView })
  await open()
  manual('주택형 1', '주택형')
  fireEvent.change(screen.getByLabelText('주택형 1 주택형 수정'), { target: { value: '' } })
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  expect(await screen.findByText('주택형의 직접 입력값을 확인하거나 기존 값 사용을 선택하세요.')).toBeVisible()
  await waitFor(() => expect(document.activeElement?.getAttribute('data-review-id')).toBe('cell:housingTypes[0].name'))
  expect(scroll).toHaveBeenCalled()
  expect(api.previewJsonUpdate).not.toHaveBeenCalled()
})

it('검산에서 맞지 않은 합계를 공급정보 위에 보여 준다', async () => {
  const extraction = structuredClone(fixtures.hansville.extraction)
  Object.assign(extraction.data.supplySheet.housingTypes[0], { exclusiveArea: 26.37, commonArea: 15.1, supplyArea: 42 })
  await open(JSON.stringify(extraction))
  fireEvent.click(screen.getByRole('button', { name: '안내 펼치기' }))
  expect(screen.getByRole('note', { name: '검산 결과' })).toHaveTextContent('26A: 공급면적 42㎡가 전용 + 공용 41.47㎡와 다릅니다.')
})

/** 저장해 둔 초안. 검토 화면에서 주택형 1의 형명을 직접 입력한 상태다. 초안을 만들 때 26A의 전용면적은 26㎡였다. */
function storedDraft(overrides: Record<string, unknown> = {}) {
  const { review } = scenario('hansville', undefined, extracted => savedSheet(extracted, { id: 3055, name: '익산한스빌' }))
  review.rows[0].cells = review.rows[0].cells.map(cell => cell.field === 'name' ? { ...cell, mode: 'manual', input: '26A 초안', manualInput: '26A 초안', value: '26A 초안', state: 'changed', selected: true, edited: true, reviewed: true } : cell)
  const baseSheet = draftBaseSheet(workspace.supply.sheet).map(type => type.key === 'row:1' ? { ...type, exclusiveArea: 26 } : type)
  return { revision: 4, baseSnapshotToken: 'current', pdfSha256: EMPTY_PDF_SHA256, extractionId: 31, updatedAt: '2026-11-03T03:00:00Z', stale: false,
    payload: { version: 3, text: JSON.stringify(fixtures.hansville.extraction), review, reason: '초안 이유', sourceUrl: 'https://example.com/notice', sourcePage: '4', section: 'supply', pdfName: '공고문.pdf', baseSheet }, ...overrides }
}

it('편집을 멈추면 잠시 뒤 작업 상태 전체를 임시저장하고, 실패하면 편집을 유지한 채 다시 시도할 수 있다', async () => {
  draftApi.saveCorrectionDraft.mockRejectedValueOnce(new Error('네트워크 오류'))
  await open()
  manual('주택형 1', '주택형')
  fireEvent.change(screen.getByLabelText('주택형 1 주택형 수정'), { target: { value: '26A 관리자' } })
  await waitFor(() => expect(screen.getByText('임시저장 실패')).toBeVisible(), { timeout: 3000 })
  expect(screen.getByLabelText('주택형 1 주택형 수정')).toHaveValue('26A 관리자')
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  await waitFor(() => expect(screen.getByLabelText(/^임시저장 상태: 저장됨/)).toBeInTheDocument())
  const saved = draftApi.saveCorrectionDraft.mock.lastCall!
  expect(saved[0]).toBe('29')
  expect(saved[1]).toMatchObject({ expectedRevision: null, baseSnapshotToken: 'current', pdfSha256: EMPTY_PDF_SHA256, payload: { version: 3, reason: expect.any(String), section: 'supply', baseSheet: draftBaseSheet(workspace.supply.sheet) } })
  const cell = saved[1].payload.review.rows[0].cells.find((item: { field: string }) => item.field === 'name')
  expect(cell).toMatchObject({ mode: 'manual', manualInput: '26A 관리자' })
})

it('다른 탭이 더 최신 초안을 저장했으면 덮어쓰지 않고 알린 뒤 최신 초안을 불러올지 고르게 한다', async () => {
  draftApi.saveCorrectionDraft.mockRejectedValue(new ManagementError('충돌', 409, {}, 'ANNOUNCEMENT_CORRECTION_DRAFT_CONFLICT'))
  await open()
  fireEvent.click(screen.getByRole('button', { name: '임시저장' }))
  expect(await within(screen.getByLabelText('검토 진행')).findByRole('alert')).toHaveTextContent('다른 탭/세션에서 더 최신 초안이 저장됐습니다')
  const calls = draftApi.saveCorrectionDraft.mock.calls.length
  manual('주택형 1', '주택형')
  await new Promise(resolve => setTimeout(resolve, 1700))
  expect(draftApi.saveCorrectionDraft).toHaveBeenCalledTimes(calls)
  draftApi.loadCorrectionDraft.mockResolvedValue(storedDraft({ revision: 9 }))
  fireEvent.click(screen.getByRole('button', { name: '최신 초안 불러오기' }))
  expect(await screen.findByRole('region', { name: '임시저장한 작업' })).toBeVisible()
})

it('저장된 초안이 있으면 바로 적용하지 않고, 공고가 바뀐 내용을 보여 준 뒤 복원하면 선택을 이어 가고 적용 성공 뒤에만 초안을 지운다', async () => {
  const stored = storedDraft({ stale: true })
  stored.payload = { ...stored.payload, warnings: ['26B 임대조건을 원문과 대조하세요.'] } as typeof stored.payload
  draftApi.loadCorrectionDraft.mockResolvedValue(stored)
  renderPage()
  const offer = await screen.findByRole('region', { name: '임시저장한 작업' })
  expect(within(offer).getByRole('list', { name: '초안 뒤 바뀐 주택형' })).toHaveTextContent('익산한스빌 · 26A: 전용 26 → 26.5195㎡')
  expect(screen.queryByRole('heading', { name: '공급정보' })).not.toBeInTheDocument()
  expect(draftApi.saveCorrectionDraft).not.toHaveBeenCalled()
  fireEvent.click(within(offer).getByRole('button', { name: '임시저장본 복원' }))
  expect(await screen.findByRole('heading', { name: '공급정보' })).toBeVisible()
  expect(screen.getByLabelText('주택형 1 주택형 수정')).toHaveValue('26A 초안')
  // 복원 안내는 배너로 쌓이지 않고 상단 임시저장 상태 옆에 짧게 남는다.
  expect(within(screen.getByLabelText('검토 진행')).getByRole('status')).toHaveTextContent('최신 공고 값에 맞춰 복원함')
  fireEvent.click(screen.getByRole('button', { name: '안내 펼치기' }))
  expect(screen.getByRole('note', { name: '검산 결과' })).toHaveTextContent('26B 임대조건을 원문과 대조하세요.')
  api.applyJsonUpdate.mockRejectedValueOnce(new ManagementError('잘못된 요청', 400))
  await applyCorrection()
  await screen.findByText('잘못된 요청')
  expect(draftApi.deleteCorrectionDraft).not.toHaveBeenCalled()
  await applyCorrection()
  await screen.findByRole('heading', { name: '공고 보정을 적용했습니다.' })
  expect(screen.queryByText(/복원/)).not.toBeInTheDocument()
  await waitFor(() => expect(draftApi.deleteCorrectionDraft).toHaveBeenCalledWith('29', expect.any(Number)))
})

it('임시저장본을 복원하지 않고 새로 검토해 적용하면 적용 결과에는 그 임시저장본 복원 안내를 남기지 않고, 고르지 않은 임시저장본은 지우지 않는다', async () => {
  draftApi.loadCorrectionDraft.mockResolvedValue(storedDraft({ revision: 7 }))
  renderPage()
  expect(await screen.findByRole('region', { name: '임시저장한 작업' })).toBeVisible()
  await review()
  await screen.findByRole('heading', { name: '공급정보' })
  await applyCorrection()
  await screen.findByRole('heading', { name: '공고 보정을 적용했습니다.' })
  expect(screen.queryByRole('region', { name: '임시저장한 작업' })).not.toBeInTheDocument()
  expect(draftApi.deleteCorrectionDraft).not.toHaveBeenCalledWith('29', 7)
})

it('돌아와 이어 기다리는 중에 임시저장본을 복원하면 기다리기를 멈춰 늦게 온 추출 결과가 복원한 초안을 덮지 않는다', async () => {
  window.sessionStorage.setItem('pdf-extraction-job:29', '31')
  let signal: AbortSignal | undefined
  extractor.resumeExtraction.mockImplementation((_id: string, _jobId: number, _file: Blob, given: AbortSignal) => { signal = given; return new Promise(() => {}) })
  draftApi.loadCorrectionDraft.mockResolvedValue(storedDraft())
  renderPage()
  await waitFor(() => expect(extractor.resumeExtraction).toHaveBeenCalledOnce())
  fireEvent.click(within(await screen.findByRole('region', { name: '임시저장한 작업' })).getByRole('button', { name: '임시저장본 복원' }))
  expect(await screen.findByRole('heading', { name: '공급정보' })).toBeVisible()
  expect(signal?.aborted).toBe(true)
  expect(window.sessionStorage.getItem('pdf-extraction-job:29')).toBeNull()
})

it('공급행으로 검토하던 이전 방식의 임시저장은 복원하지 않고 다시 시작한다고 알리며, 새 검토의 첫 임시저장이 그 초안을 덮어쓴다', async () => {
  draftApi.loadCorrectionDraft.mockResolvedValue({ outdated: true, revision: 4 })
  renderPage()
  expect(await screen.findByText('이전 방식의 임시저장이라 복원하지 않고 다시 시작합니다. 응답을 다시 검토하면 새로 임시저장합니다.')).toBeVisible()
  expect(screen.queryByRole('region', { name: '임시저장한 작업' })).not.toBeInTheDocument()
  await review()
  await screen.findByRole('heading', { name: '공급정보' })
  fireEvent.click(screen.getByRole('button', { name: '임시저장' }))
  await waitFor(() => expect(draftApi.saveCorrectionDraft).toHaveBeenCalled())
  expect(draftApi.saveCorrectionDraft.mock.calls[0][1]).toMatchObject({ expectedRevision: 4, payload: { version: 3 } })
})

it('복원한 초안으로 검토하다 응답 준비로 돌아가도 다시 추출하지 않고 복원한 입력으로 돌아온다', async () => {
  draftApi.loadCorrectionDraft.mockResolvedValue(storedDraft())
  renderPage()
  fireEvent.click(within(await screen.findByRole('region', { name: '임시저장한 작업' })).getByRole('button', { name: '임시저장본 복원' }))
  await screen.findByRole('heading', { name: '공급정보' })
  fireEvent.click(screen.getByRole('button', { name: '응답 준비로 돌아가기' }))
  fireEvent.click(await screen.findByRole('button', { name: '추출 결과 검토하기' }))
  await screen.findByRole('heading', { name: '공급정보' })
  expect(screen.getByLabelText('주택형 1 주택형 수정')).toHaveValue('26A 초안')
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
  // 최신값에 공급표·기본정보·일정이 모두 이 요청대로 들어 있다.
  api.loadJsonWorkspace.mockImplementation(async () => {
    const data = structuredClone(workspace.detail.data) as Record<string, unknown>
    for (const field of sent!.fields) {
      if (field === 'receptionPlaces') data[field] = sent!.document.receptionPlaces
      else data[field] = sent!.document.announcement[field]
    }
    const events = sent!.schedules.map((selection, at) => ({ id: selection.existingId ?? 9000 + at, data: sent!.document.schedules[selection.inputIndex], complexName: null }))
    return { ...workspace, detail: { ...workspace.detail, data }, events, supply: { ...workspace.supply, sheet: sent!.correction.sheet as unknown as SupplySheet } }
  })
  await applyCorrection()
  expect(await screen.findByRole('heading', { name: '공고 보정을 적용했습니다.' })).toBeVisible()
  expect(screen.getByText('이 보정은 이미 반영돼 있습니다. 같은 요청을 다시 저장하지 않았습니다.')).toBeVisible()
  expect(api.applyJsonUpdate).toHaveBeenCalledOnce()
})

it('409(ADMIN_DATA_CONFLICT) 뒤 공급표는 같아도 고른 기본정보·일정이 최신값에 없으면 반영됐다고 하지 않는다', async () => {
  await open()
  let sent: JsonUpdateInput | undefined
  api.applyJsonUpdate.mockImplementationOnce(async (_id: string, input: JsonUpdateInput) => { sent = input; throw new ManagementError('이미 처리한 요청입니다.', 409, {}, 'ADMIN_DATA_CONFLICT') })
  api.loadJsonWorkspace.mockImplementation(async () => ({ ...workspace, supply: { ...workspace.supply, sheet: sent!.correction.sheet as unknown as SupplySheet } }))
  await applyCorrection()
  expect(sent!.fields.length + sent!.schedules.length).toBeGreaterThan(0)
  expect(await screen.findByRole('alert')).toHaveTextContent('이 변경은 반영되지 않았습니다')
})

it('409(ADMIN_DATA_CONFLICT) 뒤 최신값에 이 변경이 없으면 반영되지 않았다고 알리고 다시 비교하게 한다', async () => {
  await open()
  manual('주택형 1', '주택형')
  fireEvent.change(screen.getByRole('textbox', { name: '주택형 1 주택형 수정' }), { target: { value: '26A 새 표기' } })
  api.applyJsonUpdate.mockRejectedValueOnce(new ManagementError('이미 처리한 요청입니다.', 409, {}, 'ADMIN_DATA_CONFLICT'))
  await applyCorrection()
  expect(await screen.findByRole('alert')).toHaveTextContent('이 변경은 반영되지 않았습니다')
  expect(screen.getByRole('button', { name: '입력을 유지하고 최신값 비교' })).toBeEnabled()
})

it('미확정 공고 저장(공개)이 충돌하고 공급표만 같으면 완료 처리하거나 초안을 지우지 않는다', async () => {
  api.loadJsonWorkspace.mockResolvedValue(unconfirmedWorkspace())
  api.previewJsonUpdate.mockResolvedValue(previewResult({ publishes: true }))
  await open()
  const release = screen.getByRole('region', { name: '공개 전 확인' })
  fireEvent.change(within(release).getByLabelText('공급 유형'), { target: { value: 'NATIONAL_RENTAL' } })
  fireEvent.change(within(release).getByLabelText('모집 유형'), { target: { value: 'WAITLIST' } })
  let sent: JsonUpdateInput | undefined
  api.applyJsonUpdate.mockImplementationOnce(async (_id: string, input: JsonUpdateInput) => {
    sent = input
    throw new ManagementError('현재값이 변경되었습니다.', 409, {}, 'ADMIN_DATA_CONFLICT')
  })
  api.loadJsonWorkspace.mockImplementation(async () => ({ ...unconfirmedWorkspace(),
    supply: { ...workspace.supply, sheet: sent!.correction.sheet as unknown as SupplySheet } }))

  await applyCorrection()

  await screen.findByRole('button', { name: '입력을 유지하고 최신값 비교' })
  expect(screen.queryByRole('heading', { name: '공고를 공개했습니다.' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '입력을 유지하고 최신값 비교' })).toBeEnabled()
  expect(draftApi.deleteCorrectionDraft).not.toHaveBeenCalled()
})

it('미확정 공고 저장(공개) 응답을 잃고 공급표만 같으면 재조회 뒤에도 성공을 추정하지 않는다', async () => {
  api.loadJsonWorkspace.mockResolvedValue(unconfirmedWorkspace())
  api.previewJsonUpdate.mockResolvedValue(previewResult({ publishes: true }))
  await open()
  const release = screen.getByRole('region', { name: '공개 전 확인' })
  fireEvent.change(within(release).getByLabelText('공급 유형'), { target: { value: 'NATIONAL_RENTAL' } })
  fireEvent.change(within(release).getByLabelText('모집 유형'), { target: { value: 'WAITLIST' } })
  let sent: JsonUpdateInput | undefined
  api.applyJsonUpdate.mockImplementationOnce(async (_id: string, input: JsonUpdateInput) => {
    sent = input
    throw new Error('응답 유실')
  })
  api.loadJsonWorkspace.mockImplementation(async () => ({ ...unconfirmedWorkspace(),
    supply: { ...workspace.supply, sheet: sent!.correction.sheet as unknown as SupplySheet } }))

  await applyCorrection()
  fireEvent.click(await screen.findByRole('button', { name: '입력을 유지하고 최신값 비교' }))

  expect(await screen.findByText(/최신 저장값을 불러왔습니다. 수동 입력은 유지했습니다/)).toBeVisible()
  expect(screen.queryByText(/최신 저장값에 이 보정이 이미 반영/)).not.toBeInTheDocument()
  expect(draftApi.deleteCorrectionDraft).not.toHaveBeenCalled()
})

it('주택형 목록은 주택형마다 저장할 곳과 상태를 요약하고, 단지 주택형 패널에서 기존 주택형을 고른 행에 연결해도 패널을 닫았다 열면 그대로다', async () => {
  await open()
  const list = screen.getByRole('region', { name: '주택형 목록' })
  expect(within(list).getAllByRole('row').slice(2).map(row => row.textContent)).toEqual(['1. 26A26.5195㎡미확인기존 주택형준비됨', '2. 26B26.4673㎡미확인기존 주택형준비됨'])
  fireEvent.click(screen.getByRole('button', { name: '단지 주택타입 보기' }))
  const panel = screen.getByRole('complementary', { name: '익산한스빌 주택타입' })
  const types = within(panel).getAllByRole('listitem')
  expect(types[1]).toHaveTextContent('단지 정보 · 건설호수486세대')
  expect(types[1]).toHaveTextContent('이번 공고 · 모집수없음')
  expect(within(panel).getByRole('heading', { name: '새 주택타입 생성' })).toBeVisible()
  fireEvent.click(within(panel).getByRole('button', { name: '26 · 전용 26.4673㎡를 주택형 1에 연결' }))
  expect(screen.getByRole('combobox', { name: '주택형 1 연결 주택타입 수정' })).toHaveValue('10352')
  fireEvent.click(within(panel).getByRole('button', { name: '주택타입 패널 닫기' }))
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '단지 주택타입 보기' }))
  expect(within(screen.getByRole('complementary')).getByText('주택형 1에 연결함')).toBeVisible()
  expect(screen.getByRole('combobox', { name: '주택형 1 연결 주택타입 수정' })).toHaveValue('10352')
})

it('검토 탭은 공급정보·일정·접수처와 신청자격 초안만 두고, 공급정보의 단지는 고정 막대에서 확인 수와 함께 고르며 이전·다음과 미해결 이동이 그 행의 단지를 연다', async () => {
  await open(JSON.stringify(fixtures.gwangju.extraction))
  const tabs = within(screen.getByRole('tablist', { name: 'PDF 보정 영역' })).getAllByRole('tab')
  expect(tabs.map(tab => tab.textContent?.split(' · ')[0])).toEqual(['공급정보', '일정', '접수처·기본정보', '신청자격'])
  expect(tabs[0]).toHaveAttribute('aria-selected', 'true')
  expect(screen.getByRole('tabpanel')).toHaveAccessibleName(tabs[0].textContent!)
  const select = screen.getByRole('combobox', { name: '단지' }) as HTMLSelectElement
  expect([...select.options].map(option => option.textContent?.split(' · ').slice(0, 2).join(' · '))).toEqual(['첨단H-1BL · 주택형 1개', '첨단H-2BL · 주택형 2개', '첨단H-3BL · 주택형 4개'])
  expect(select.options[2].textContent).toMatch(/^첨단H-3BL · 주택형 4개 · 확인 \d+$/)
  // 고정 막대는 지금 편집 중인 행의 번호·형명을 보이고, 다음은 단지를 넘어 다음 단지의 첫 행으로 간다.
  const bar = screen.getByRole('group', { name: '주택형 이동' })
  expect(within(bar).getByText('1. 39(창업인)')).toBeVisible()
  expect(within(bar).getByRole('button', { name: '이전 주택형' })).toBeDisabled()
  fireEvent.click(within(bar).getByRole('button', { name: '다음 주택형' }))
  expect(select).toHaveDisplayValue(/^첨단H-2BL/)
  expect(screen.getByRole('region', { name: '주택형 2 검토' })).toBeVisible()
  expect(within(bar).getByText('2. 16(대학생,청년)')).toBeVisible()
  chooseComplex('첨단H-3BL')
  expect(screen.getByRole('region', { name: '주택형 4 검토' })).toBeVisible()
  expect(screen.queryByRole('button', { name: /^1\. / })).not.toBeInTheDocument()
  // 미해결 이동은 첫 확인 행의 단지를 열고, 그 행은 단지를 아직 정하지 않았으므로 단지 정하기로 간다(상태 배지와 같은 규칙).
  fireEvent.click(screen.getByRole('button', { name: /^미해결 \d+개/ }))
  expect(select).toHaveDisplayValue(/^첨단H-1BL/)
  expect(document.activeElement).toHaveAttribute('data-review-id', 'complex')
  fireEvent.keyDown(screen.getByRole('tab', { name: /^공급정보/ }), { key: 'ArrowRight' })
  expect(screen.getByRole('tab', { name: /^일정/ })).toHaveAttribute('aria-selected', 'true')
  expect(screen.queryByRole('group', { name: '주택형 이동' })).not.toBeInTheDocument()
})

it('주택형 상태의 확인 필요는 남은 종류를 알리고 누르면 그 행의 첫 확인 항목으로 옮기며, 고정 막대는 고른 행의 형명·전용·모집수·저장할 곳·위치를 보인다', async () => {
  await open(JSON.stringify(fixtures.gwangju.extraction))
  const bar = screen.getByRole('group', { name: '주택형 이동' })
  expect(within(bar).getByText('1. 39(창업인)').nextElementSibling).toHaveTextContent(/^전용 39\.78㎡ · 모집 25세대 · 새 주택형$/)
  expect(within(bar).getByLabelText('이 단지 주택형 1개 중 1번째')).toHaveTextContent('1/1')
  // 이 행은 새로 추가할 행이라 단지를 아직 정하지 않았다. 배지 이름은 실제로 옮겨 갈 곳(단지 정하기)을 말한다.
  const badge = within(screen.getByRole('region', { name: '주택형 목록' })).getByRole('button', { name: /^주택형 1 확인 필요: 단지 정하기( · .*)? 확인하러 가기$/ })
  expect(within(bar).getByRole('button', { name: /^주택형 1 확인 필요: 단지 정하기( · .*)? 확인하러 가기$/ })).toHaveTextContent(/^확인 필요 · 단지 정하기/)
  fireEvent.click(within(screen.getByRole('group', { name: '검토 항목 필터' })).getByRole('button', { name: /^확인 필요/ }))
  fireEvent.click(badge)
  // 단지 정하기는 확인 필요 필터에서도 보이므로 필터를 끄지 않는다.
  expect(within(screen.getByRole('group', { name: '검토 항목 필터' })).getByRole('button', { name: /^확인 필요/ })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(within(screen.getByRole('group', { name: '검토 항목 필터' })).getByRole('button', { name: '전체' }))
  expect(document.activeElement).toHaveAttribute('data-review-id', 'complex')
  // 목록에서 다른 단지 행을 고르면 막대도 그 행으로 바뀐다.
  chooseComplex('첨단H-3BL')
  showRow(5)
  expect(within(bar).getByText(/^5\. /)).toBeVisible()
  expect(within(bar).getByLabelText('이 단지 주택형 4개 중 2번째')).toHaveTextContent('2/4')
})

it('단지를 바꿀 때 그 단지에 단지 정하기가 없으면 맨 위가 아니라 그 단지 첫 주택형의 편집 영역으로 옮긴다', async () => {
  const scroll = vi.fn()
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scroll })
  onTestFinished(() => { delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView })
  // 이 공고문을 이미 반영해 모든 주택형이 단지에 연결된 공고라 어느 단지에도 단지 정하기가 없다.
  const linked = scenario('gwangju', undefined, extracted => savedSheet(extracted, { id: 3055, name: '첨단' })).workspace
  api.loadJsonWorkspace.mockResolvedValue({ ...linked, detail: { ...linked.detail, attachments: workspace.detail.attachments } })
  await open(JSON.stringify(fixtures.gwangju.extraction))
  expect(screen.queryByRole('group', { name: /단지 정하기/ })).not.toBeInTheDocument()
  scroll.mockClear()
  chooseComplex('첨단H-3BL')
  expect(scroll).toHaveBeenCalled()
  expect(scroll.mock.contexts.at(-1)).toBe(screen.getByRole('region', { name: '주택형 4 검토' }))
})

it('확인 필요 필터에서 이전·다음은 단지 순서대로 확인 행만 빠짐없이 지나고 마지막 행에서는 다음이 꺼진다', async () => {
  await open(JSON.stringify(fixtures.gwangju.extraction))
  fireEvent.click(within(screen.getByRole('group', { name: '검토 항목 필터' })).getByRole('button', { name: /^확인 필요/ }))
  // 기대 순서: 단지마다 확인 필요 필터의 목록에 남는 행 번호를 단지 순서대로 이은 것
  const select = screen.getByRole('combobox', { name: '단지' }) as HTMLSelectElement
  const expected = [...select.options].flatMap(option => {
    chooseComplex(option.textContent!.split(' · ')[0])
    return within(screen.queryByRole('region', { name: '주택형 목록' }) ?? document.createElement('div')).queryAllByRole('button', { name: /^\d+\. / }).map(button => Number(button.textContent!.split('.')[0]))
  })
  expect(expected.length).toBeGreaterThan(1)
  chooseComplex(select.options[0].textContent!.split(' · ')[0])
  const bar = screen.getByRole('group', { name: '주택형 이동' })
  const visited = [Number(within(bar).getByText(/^\d+\. /).textContent!.split('.')[0])]
  while (!within(bar).getByRole('button', { name: '다음 주택형' }).hasAttribute('disabled') && visited.length < 20) {
    fireEvent.click(within(bar).getByRole('button', { name: '다음 주택형' }))
    visited.push(Number(within(bar).getByText(/^\d+\. /).textContent!.split('.')[0]))
  }
  expect(visited).toEqual(expected)
})

it('주택형 목록에서 주택형을 고르면 PDF를 그 행의 주택형 표기 근거 쪽으로 옮겨 강조한다', async () => {
  await open(JSON.stringify(fixtures.gwangju.extraction))
  chooseComplex('첨단H-3BL')
  const list = screen.getByRole('region', { name: '주택형 목록' })
  const button = within(list).getAllByRole('button', { name: /^\d+\. / }).at(-1)!
  fireEvent.click(button)
  // 좁은 화면에서는 편집 영역을 그대로 두므로 PDF 영역을 열어 확인한다.
  expect(screen.getByRole('region', { name: /^주택형 \d+ 검토$/ })).toBeVisible()
  showPdf()
  expect(await screen.findByText(/^PDF \d+쪽 · 강조 (?!없음)/)).toBeVisible()
})

it('공급정보 편집표의 PDF 채우기는 공급정보 탭 하나만 두고 단지 선택으로 다른 단지 행을 고를 수 있다', async () => {
  extracts(JSON.stringify(fixtures.gwangju.extraction))
  render(<MemoryRouter><PdfCorrectionWorkspace id="29" supplyDraft={vi.fn()} /></MemoryRouter>)
  await review()
  const tabs = within(screen.getByRole('tablist', { name: 'PDF 보정 영역' })).getAllByRole('tab')
  expect(tabs.map(tab => tab.textContent?.split(' · ')[0])).toEqual(['공급정보'])
  chooseComplex('첨단H-3BL')
  expect(screen.getByRole('region', { name: '주택형 4 검토' })).toBeVisible()
})

it('임시저장본은 열어 둔 단지와 고른 주택형을 함께 저장하고 복원한다', async () => {
  await open(JSON.stringify(fixtures.gwangju.extraction))
  chooseComplex('첨단H-3BL')
  showRow(5)
  fireEvent.click(screen.getByRole('button', { name: '임시저장' }))
  await waitFor(() => expect(draftApi.saveCorrectionDraft.mock.lastCall?.[1].payload).toMatchObject({ section: 'supply', selectedRow: 4 }))
  cleanup()
  const saved = draftApi.saveCorrectionDraft.mock.lastCall![1]
  draftApi.loadCorrectionDraft.mockResolvedValue({ revision: 2, baseSnapshotToken: 'current', pdfSha256: null, extractionId: null, updatedAt: '2026-11-03T03:00:00Z', stale: false, payload: saved.payload })
  renderPage()
  fireEvent.click(within(await screen.findByRole('region', { name: '임시저장한 작업' })).getByRole('button', { name: '임시저장본 복원' }))
  expect(await screen.findByRole('combobox', { name: '단지' })).toHaveDisplayValue(/^첨단H-3BL/)
  expect(screen.getByRole('region', { name: '주택형 5 검토' })).toBeVisible()
})


it('미확정 공고의 필수값 누락은 미해결로 표시하고 해당 칸으로 안내해 서버에 보내지 않는다', async () => {
  const initial = unconfirmedWorkspace()
  const empty = { ...initial, detail: { ...initial.detail, data: { ...initial.detail.data, applicationStartDate: null, applicationEndDate: null, winnerAnnouncementDate: null } } }
  api.loadJsonWorkspace.mockResolvedValue(empty)
  const sample = fixtures.hansville.extraction
  const missing = { ...sample, data: { ...sample.data, announcement: { ...sample.data.announcement, applicationStartDate: null, applicationEndDate: null, winnerAnnouncementDate: null } } }
  await open(JSON.stringify(missing))
  expect(screen.getByLabelText('공급 유형')).toHaveAttribute('aria-invalid', 'true')
  expect(screen.getByLabelText('모집 유형')).toHaveAttribute('aria-invalid', 'true')
  expect(screen.queryByText('확인 필요 없음')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '보정 내용 적용' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('접수 시작일')
  expect(screen.getByRole('tab', { name: /접수처·기본정보/ })).toHaveAttribute('aria-selected', 'true')
  expect(api.previewJsonUpdate).not.toHaveBeenCalled()
})

