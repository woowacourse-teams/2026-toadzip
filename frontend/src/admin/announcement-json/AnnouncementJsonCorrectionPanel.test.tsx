import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { AnnouncementJsonCorrectionPanel } from './AnnouncementJsonCorrectionPanel'
import { ManagementError } from '../management/api'
import example from '../registration/announcementImportExample.json'
import type { JsonWorkspace } from './api'
import { blankRow } from '../supply/supplyCorrectionApi'
const api = vi.hoisted(() => ({ loadJsonWorkspace: vi.fn(), previewJsonUpdate: vi.fn(), applyJsonUpdate: vi.fn() }))
vi.mock('./api', () => api)
const workspace: JsonWorkspace = { snapshotToken: 'all-snapshot', detail: {
  summary: { id: 7, name: '기존 공고', subtitle: '', provider: 'LH', rental: 'HAPPY_HOUSING', modified: false, deleted: false, reviewRequired: false, updatedAt: null },
  sourceIdentifier: 'stable-source', data: { version: 1, ...example.announcement, name: '기존 공고', originalUrl: example.source.originalUrl, receptionPlace: example.receptionPlace },
  housingTypes: [], supplyRows: [], announcements: [], schedules: [], scheduleReviewed: false, attachments: [],
}, supply: { snapshotToken: 'supply-snapshot', version: 1, rows: [], sources: [] }, events: [] }
const input = JSON.stringify({ ...example, announcement: { ...example.announcement, name: 'JSON으로 갱신한 공고' }, supplyRows: [], schedules: [], attachments: [] })
beforeEach(() => {
  vi.clearAllMocks()
  api.loadJsonWorkspace.mockResolvedValue(workspace)
  api.previewJsonUpdate.mockResolvedValue({ confirmationToken: 'confirm', fields: [{ field: 'name', before: '기존 공고', after: 'JSON으로 갱신한 공고' }], supply: null, schedules: [], attachments: [], warnings: ['기존 항목 유지'] })
  api.applyJsonUpdate.mockResolvedValue(workspace)
})
async function start() {
  const onChanged = vi.fn()
  render(<MemoryRouter><AnnouncementJsonCorrectionPanel id="7" onChanged={onChanged} onBusyChange={vi.fn()} onDirtyChange={vi.fn()} /></MemoryRouter>)
  fireEvent.change(await screen.findByRole('textbox', { name: '공고 갱신 JSON' }), { target: { value: input } })
  fireEvent.click(screen.getByRole('button', { name: '현재 공고와 JSON 비교' }))
  fireEvent.change(screen.getByRole('textbox', { name: '갱신 이유' }), { target: { value: '공고문 확인' } })
  fireEvent.click(screen.getByRole('button', { name: '전체 변경 미리보기' }))
  await screen.findByRole('button', { name: '확인한 변경으로 현재 공고 갱신' })
  return onChanged
}
it('신규 등록 JSON으로 현재 공고를 비교하고 최종 확인한 뒤에만 저장한다', async () => {
  const changed = await start()
  expect(api.applyJsonUpdate).not.toHaveBeenCalled()
  expect(api.previewJsonUpdate).toHaveBeenCalledWith('7', expect.objectContaining({ fields: ['name'], document: expect.objectContaining({ schemaVersion: 'admin-announcement-import/v1' }), snapshotToken: 'all-snapshot' }))
  fireEvent.click(screen.getByRole('button', { name: '확인한 변경으로 현재 공고 갱신' }))
  expect(await screen.findByRole('status')).toHaveTextContent('현재 공고를 갱신했습니다.')
  expect(api.applyJsonUpdate).toHaveBeenCalledWith('7', expect.objectContaining({ fields: ['name'] }), 'confirm')
  expect(changed).toHaveBeenCalledOnce()
})
it('동시 변경을 거절하면 JSON을 유지하고 최신 저장값부터 다시 비교한다', async () => {
  api.applyJsonUpdate.mockRejectedValue(new ManagementError('현재값 변경', 409))
  await start()
  fireEvent.click(screen.getByRole('button', { name: '확인한 변경으로 현재 공고 갱신' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('현재값 변경')
  expect(screen.getByRole('textbox', { name: '공고 갱신 JSON' })).toHaveValue(input)
  fireEvent.click(screen.getByRole('button', { name: '입력을 유지하고 최신 저장값 확인' }))
  expect(await screen.findByRole('status')).toHaveTextContent('JSON 입력은 유지했습니다.')
  expect(api.applyJsonUpdate).toHaveBeenCalledOnce()
})
it('공급 변경안을 만든 뒤 공급정보 유지를 선택하면 이전 변경안을 보내지 않는다', async () => {
  const row = { ...blankRow(0), id: 9, key: 'row:9', complexName: '두꺼비 행복주택', typeName: '36A', pnu: '1114010100100010000', householdCount: 10 }
  api.loadJsonWorkspace.mockResolvedValue({ ...workspace, supply: { ...workspace.supply, rows: [row] } })
  render(<MemoryRouter><AnnouncementJsonCorrectionPanel id="7" onChanged={vi.fn()} onBusyChange={vi.fn()} onDirtyChange={vi.fn()} /></MemoryRouter>)
  fireEvent.change(await screen.findByRole('textbox', { name: '공고 갱신 JSON' }), { target: { value: JSON.stringify({ ...example, schedules: [], attachments: [], supplyRows: [{ ...example.supplyRows[0], targets: [] }] }) } })
  fireEvent.click(screen.getByRole('button', { name: '현재 공고와 JSON 비교' }))
  fireEvent.click(screen.getByRole('button', { name: '선택한 공급정보를 변경안으로 가져오기' }))
  expect(screen.getByText('공급정보 변경안·단지 연결')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '이번에는 공급정보 유지' }))
  fireEvent.change(screen.getByRole('textbox', { name: '갱신 이유' }), { target: { value: '기본정보만 확인' } })
  fireEvent.click(screen.getByRole('button', { name: '전체 변경 미리보기' }))
  await screen.findByRole('button', { name: '확인한 변경으로 현재 공고 갱신' })
  expect(api.previewJsonUpdate).toHaveBeenCalledWith('7', expect.objectContaining({ correction: expect.objectContaining({ rows: [row] }) }))
})
