import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { PdfCorrectionPage } from './PdfCorrectionPage'
import { scenario } from './fixtures/workspace'
import fixtures from './fixtures/scenarios.json'
import type { JsonUpdateInput } from '../announcement-json/api'
import { ManagementError } from '../management/api'
const api = vi.hoisted(() => ({ loadJsonWorkspace: vi.fn(), previewJsonUpdate: vi.fn(), applyJsonUpdate: vi.fn() }))
vi.mock('../announcement-json/api', () => api)
vi.mock('../ingest/supplyMatchingApi', () => ({ getMatchingHousingTypes: async (id: number) => fixtures.hansville.complexes.find(item => item.id === id)?.housingTypes ?? [] }))
vi.mock('../../public-housing/api/announcementAttachments', () => ({ loadAnnouncementAttachment: async () => new Blob(), attachmentErrorMessage: () => 'PDF 열기 실패' }))
vi.mock('../../public-housing/components/PdfDocumentPreview', () => ({ default: ({ focus }: { focus: { page: number } | null }) => <p>PDF {focus?.page ?? 1}쪽</p> }))
const workspace = scenario('hansville').workspace
beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('URL', class extends URL { static createObjectURL = () => 'blob:pdf'; static revokeObjectURL = vi.fn() })
  api.loadJsonWorkspace.mockResolvedValue(workspace)
  api.applyJsonUpdate.mockResolvedValue(workspace)
  api.previewJsonUpdate.mockImplementation(async (_id: string, input: JsonUpdateInput) => ({ confirmationToken: 'confirmed', fields: [], schedules: [], attachments: [], warnings: [], supply: { before: workspace.supply.rows, after: input.correction.rows, warnings: [], newGroups: input.correction.newGroups } }))
})
afterEach(() => vi.unstubAllGlobals())
async function open(text = JSON.stringify(fixtures.hansville.extraction)) {
  render(<MemoryRouter initialEntries={['/admin/announcements/29/pdf-correction?failureReturnTo=%2Fadmin%2Ffailures%3Fdomain%3Dannouncement&section=verification']}><Routes><Route path="/admin/announcements/:id/pdf-correction" element={<PdfCorrectionPage />} /></Routes></MemoryRouter>)
  fireEvent.change(await screen.findByRole('textbox', { name: '추출 응답' }), { target: { value: text } })
  fireEvent.change(screen.getByLabelText('내 컴퓨터에서 PDF 선택'), { target: { files: [new File(['pdf'], '공고문.pdf', { type: 'application/pdf' })] } })
  await waitFor(() => expect(screen.getByRole('button', { name: '응답 검토하기' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '응답 검토하기' }))
  await screen.findByRole('heading', { name: '공급정보' })
}
function confirmSupply() {
  for (const index of [1, 2]) {
    fireEvent.click(screen.getByRole('checkbox', { name: `공급행 ${index}의 행 대응을 확인함` }))
    fireEvent.click(screen.getByRole('checkbox', { name: `공급행 ${index} 임대조건을 PDF와 대조함` }))
  }
}
function confirmSchedules() {
  fireEvent.click(screen.getByRole('button', { name: '일정' }))
  for (const checkbox of screen.getAllByRole('checkbox', { name: /의 대응과 날짜·시각 근거를 확인함/ })) if (!(checkbox as HTMLInputElement).checked) fireEvent.click(checkbox)
  fireEvent.click(screen.getByRole('button', { name: '공급정보' }))
}
async function preview() {
  await open(); confirmSupply(); confirmSchedules()
  fireEvent.click(screen.getByRole('button', { name: '변경 미리보기' }))
  await screen.findByRole('heading', { name: '적용할 변경' })
}
it('분할 행·공통 모집수를 미리 확인하고 명시적으로 적용한 뒤에만 저장한다', async () => {
  await preview()
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
  expect(api.previewJsonUpdate).toHaveBeenCalledWith('29', expect.objectContaining({ correction: expect.objectContaining({ newGroups: [expect.objectContaining({ sourceRowId: 90, sharedHouseholdCount: 170 })] }) }))
  expect(screen.getByText(/공통 모집수 170세대/)).toBeVisible()
  expect(screen.getByRole('heading', { name: '적용하지 않는 항목' })).toBeVisible()
  expect(screen.getAllByText('보증금 (원)').length).toBeGreaterThan(0)
  fireEvent.click(screen.getByRole('button', { name: '확인한 변경 적용' }))
  await screen.findByRole('heading', { name: '공고 보정을 적용했습니다.' })
  expect(api.applyJsonUpdate).toHaveBeenCalledOnce()
  expect(screen.getByRole('link', { name: '실패 검토로 돌아가기' })).toHaveAttribute('href', '/admin/failures?domain=announcement')
  expect(screen.getByRole('link', { name: '저장된 공급정보 확인' }).getAttribute('href')).toContain('section=relations')
  expect(screen.getByRole('link', { name: '저장된 공급정보 확인' }).getAttribute('href')).not.toContain('section=verification')
})
it('행 대응과 PDF 블록 확인 전에는 미리보기 요청을 보내지 않는다', async () => {
  await open()
  fireEvent.click(screen.getByRole('button', { name: '변경 미리보기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('행 대응과 임대조건 블록을 확인')
  expect(api.previewJsonUpdate).not.toHaveBeenCalled()
})
it('형식 오류는 바로 표시하고 셀 수정·PDF 근거 이동·조건 제외를 제공한다', async () => {
  await open()
  const row = screen.getByRole('region', { name: '공급행 1 검토' })
  fireEvent.click(within(row).getByRole('button', { name: '조건 1 대조·수정' }))
  const input = screen.getByRole('textbox', { name: '공급행 1 조건 1 보증금 (원) 수정' })
  fireEvent.change(input, { target: { value: '4,076,000' } })
  expect(input).toHaveAttribute('aria-invalid', 'true')
  expect(screen.getByRole('checkbox', { name: '공급행 1 조건 1 보증금 (원) 적용' })).toBeDisabled()
  fireEvent.change(input, { target: { value: '4076000' } })
  const details = screen.getByRole('region', { name: '공급행 1 조건 1 수정' })
  fireEvent.click(within(details).getAllByRole('button', { name: '5쪽 근거' })[0])
  expect(await screen.findByText('PDF 5쪽')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '검토표 보기' }))
  fireEvent.change(screen.getByRole('combobox', { name: '공급행 1 조건 1 적용 대상' }), { target: { value: 'exclude' } })
  expect(screen.getByText('이 조건은 적용하지 않습니다. 저장된 기존 조건은 유지합니다.')).toBeVisible()
})
it('409 뒤 선택과 수동 입력을 보존하고 행 대응을 다시 확인한다', async () => {
  api.previewJsonUpdate.mockRejectedValue(new ManagementError('현재값이 변경되었습니다.', 409))
  await open(); confirmSupply(); confirmSchedules()
  fireEvent.change(screen.getByRole('textbox', { name: '공급행 1 공고 주택형명 수정' }), { target: { value: '26A 수동 보정' } })
  fireEvent.click(screen.getByRole('checkbox', { name: '공급행 1 임대조건을 PDF와 대조함' }))
  fireEvent.click(screen.getByRole('button', { name: '변경 미리보기' }))
  const reload = await screen.findByRole('button', { name: '입력을 유지하고 최신값 비교' })
  expect(screen.getByRole('button', { name: '변경 미리보기' })).toBeDisabled()
  fireEvent.click(reload)
  await screen.findByRole('status')
  expect(screen.getByRole('textbox', { name: '공급행 1 공고 주택형명 수정' })).toHaveValue('26A 수동 보정')
  expect(screen.getByRole('checkbox', { name: '공급행 1의 행 대응을 확인함' })).not.toBeChecked()
})
it('저장 응답을 잃으면 자동 재적용하지 않고 최신값 확인을 요구한다', async () => {
  api.applyJsonUpdate.mockRejectedValue(new TypeError('network'))
  await preview()
  fireEvent.click(screen.getByRole('button', { name: '확인한 변경 적용' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('저장 응답을 받지 못했습니다.')
  expect(screen.getByRole('button', { name: '변경 미리보기' })).toBeDisabled()
  expect(api.applyJsonUpdate).toHaveBeenCalledOnce()
  expect(screen.getByRole('button', { name: '입력을 유지하고 최신값 비교' })).toBeEnabled()
})
it('깨진 응답은 검토를 시작하지 못하며 내용을 유지한다', async () => {
  render(<MemoryRouter initialEntries={['/admin/announcements/29/pdf-correction']}><Routes><Route path="/admin/announcements/:id/pdf-correction" element={<PdfCorrectionPage />} /></Routes></MemoryRouter>)
  const field = await screen.findByRole('textbox', { name: '추출 응답' })
  fireEvent.change(field, { target: { value: '{broken}' } })
  expect(screen.getByRole('alert')).toBeVisible()
  expect(screen.getByRole('button', { name: '응답 검토하기' })).toBeDisabled()
  expect(field).toHaveValue('{broken}')
})

it('PDF 보기 중 미리보기를 요청하면 변경 목록을 표시하고 제목에 포커스를 둔다', async () => {
  await open(); confirmSupply(); confirmSchedules()
  fireEvent.click(screen.getByRole('button', { name: 'PDF 보기' }))
  fireEvent.click(screen.getByRole('button', { name: '변경 미리보기' }))
  const heading = await screen.findByRole('heading', { name: '적용할 변경' })
  expect(heading).toBeVisible()
  await waitFor(() => expect(heading).toHaveFocus())
})
it('원문 URL이 없는 공고는 필요한 근거 입력을 펼치고 진행 불가 이유를 알려준다', async () => {
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, detail: { ...workspace.detail, data: { ...workspace.detail.data, originalUrl: null } } })
  await open()
  expect(screen.getByRole('status')).toHaveTextContent('공식 근거 URL에 올바른 http 또는 https 주소를 입력하세요.')
  const input = screen.getByRole('textbox', { name: '공식 근거 URL' })
  expect(input).toBeVisible()
  expect(screen.getByRole('button', { name: '변경 미리보기' })).toBeDisabled()
  fireEvent.change(input, { target: { value: 'https://example.com/official-notice' } })
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '변경 미리보기' })).toBeEnabled()
})
