import { afterEach, beforeAll, beforeEach, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ request: vi.fn() }))
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(), requestManagementApi: mocks.request }))

import { ManagementError } from '../management/api'
import { JOB_POLL_MS, JOB_WAIT_LIMIT_MS, NOT_STARTED_NOTICE_MS, extractAnnouncementPdf, pdfSha256 } from './extractionApi'

const PATH = '/api/admin/announcements/29/pdf-extractions'
const extraction = { json: '{"data":{}}', pages: [1, 3], totalPages: 9, warnings: [], usage: { promptTokens: 10, outputTokens: 5, thinkingTokens: null } }
const pdf = new Blob(['%PDF'])
let sha = ''
const job = (status: string, more: Record<string, unknown> = {}) => ({ jobId: 31, status, fileName: '공고문.pdf', pdfSha256: sha, failure: null, extraction: null, ...more })
const inProgress = () => new ManagementError('이미 진행 중인 PDF 추출 작업이 있습니다.', 409, {}, 'ANNOUNCEMENT_PDF_EXTRACTION_IN_PROGRESS')

beforeAll(async () => { sha = await pdfSha256(pdf) })
beforeEach(() => { vi.useFakeTimers(); mocks.request.mockReset() })
afterEach(() => { vi.useRealTimers() })

test('작업을 넣고 성공할 때까지 작업 상태를 다시 물어 결과를 돌려준다', async () => {
  mocks.request.mockResolvedValueOnce(job('PENDING')).mockResolvedValueOnce(job('RUNNING'))
    .mockResolvedValueOnce(job('SUCCEEDED', { extraction }))

  const result = extractAnnouncementPdf('29', new Blob(['%PDF']), '공고문.pdf')
  await vi.advanceTimersByTimeAsync(JOB_POLL_MS * 2)

  await expect(result).resolves.toEqual(extraction)
  expect(mocks.request).toHaveBeenNthCalledWith(1, PATH, 'POST', expect.any(FormData), undefined)
  expect(mocks.request).toHaveBeenNthCalledWith(3, `${PATH}/jobs/31`, 'GET', undefined, undefined)
})

test('공고 첨부면 파일을 올리지 않고 서버가 그 첨부를 받게 한다', async () => {
  mocks.request.mockResolvedValueOnce(job('SUCCEEDED', { extraction }))

  await expect(extractAnnouncementPdf('29', pdf, '공고문.pdf', undefined, { attachmentId: '68' })).resolves.toEqual(extraction)
  expect(mocks.request).toHaveBeenCalledWith(`${PATH}/attachments/68`, 'POST', undefined, undefined)
})

test('재시도를 기다리는 동안 사유가 바뀔 때만 알리고, 다시 처리하면 사유를 지운다', async () => {
  const reason = { code: 'UPSTREAM_TIMEOUT', message: '추출 서비스 응답이 늦습니다.' }
  mocks.request.mockResolvedValueOnce(job('RETRY', { retryReason: reason })).mockResolvedValueOnce(job('RETRY', { retryReason: reason }))
    .mockResolvedValueOnce(job('RUNNING', { retryReason: reason })).mockResolvedValueOnce(job('SUCCEEDED', { extraction }))
  const onNotice = vi.fn()

  const result = extractAnnouncementPdf('29', pdf, '공고문.pdf', undefined, { onNotice })
  await vi.advanceTimersByTimeAsync(JOB_POLL_MS * 3)

  await expect(result).resolves.toEqual(extraction)
  expect(onNotice.mock.calls).toEqual([['잠시 뒤 다시 시도합니다 · 추출 서비스 응답이 늦습니다.'], [null]])
})

test('처리가 시작되지 않은 채 오래 기다리면 알리고, 워커가 꺼내면 안내를 지운다', async () => {
  const pendingPolls = NOT_STARTED_NOTICE_MS / JOB_POLL_MS
  for (let poll = 0; poll <= pendingPolls; poll++) mocks.request.mockResolvedValueOnce(job('PENDING'))
  mocks.request.mockResolvedValueOnce(job('RUNNING')).mockResolvedValueOnce(job('SUCCEEDED', { extraction }))
  const onNotice = vi.fn()

  const result = extractAnnouncementPdf('29', pdf, '공고문.pdf', undefined, { onNotice })
  await vi.advanceTimersByTimeAsync(NOT_STARTED_NOTICE_MS - JOB_POLL_MS)
  expect(onNotice).not.toHaveBeenCalled()
  await vi.advanceTimersByTimeAsync(JOB_POLL_MS * 3)

  await expect(result).resolves.toEqual(extraction)
  expect(onNotice.mock.calls).toEqual([['아직 처리가 시작되지 않았습니다. 앞선 추출을 처리 중일 수 있으니, 계속 그대로면 AI 워커가 실행 중인지 확인해 주세요.'], [null]])
})

test('작업이 실패하면 워커가 남긴 사유로 실패한다', async () => {
  mocks.request.mockResolvedValueOnce(job('FAILED', { failure: { code: 'PDF_INVALID', message: 'PDF를 읽지 못했습니다.' } }))

  await expect(extractAnnouncementPdf('29', new Blob(['%PDF']), '공고문.pdf')).rejects.toThrow('PDF를 읽지 못했습니다.')
})

test('같은 PDF의 진행 중 작업이 있으면 새로 넣지 않고 그 작업을 기다린다', async () => {
  mocks.request.mockRejectedValueOnce(inProgress())
    .mockResolvedValueOnce(job('RETRY', { pdfSha256: sha })).mockResolvedValueOnce(job('SUCCEEDED', { pdfSha256: sha, extraction }))

  const result = extractAnnouncementPdf('29', pdf, '공고문.pdf')
  // PDF 해시는 가짜 타이머 밖에서 계산되므로 다음 조회 예약을 기다린 뒤 시간을 넘긴다.
  await vi.waitFor(() => expect(vi.getTimerCount()).toBe(1))
  await vi.advanceTimersByTimeAsync(JOB_POLL_MS)

  await expect(result).resolves.toEqual(extraction)
  expect(mocks.request).toHaveBeenNthCalledWith(2, `${PATH}/jobs/active`, 'GET', undefined, undefined)
})

test('다른 PDF의 작업이 진행 중이면 그 결과를 기다리지 않는다', async () => {
  mocks.request.mockRejectedValueOnce(inProgress()).mockResolvedValueOnce(job('SUCCEEDED', { fileName: '다른 공고문.pdf', pdfSha256: 'other', extraction }))

  await expect(extractAnnouncementPdf('29', pdf, '공고문.pdf')).rejects.toThrow('다른 PDF(다른 공고문.pdf)의 추출이 진행 중입니다.')
  expect(mocks.request).toHaveBeenCalledTimes(2)
})

test('진행 중 작업이 그사이 끝났으면 같은 PDF의 최근 결과를 쓴다', async () => {
  mocks.request.mockRejectedValueOnce(inProgress()).mockResolvedValueOnce(null)
    .mockResolvedValueOnce({ fileName: '공고문.pdf', pdfSha256: sha, createdAt: '2026-11-01T01:00:00Z', extraction })

  await expect(extractAnnouncementPdf('29', pdf, '공고문.pdf')).resolves.toEqual(extraction)
  expect(mocks.request).toHaveBeenNthCalledWith(3, `${PATH}/latest`, 'GET', undefined, undefined)
})

test('진행 중 작업이 그사이 끝났어도 다른 PDF의 결과면 쓰지 않는다', async () => {
  mocks.request.mockRejectedValueOnce(inProgress()).mockResolvedValueOnce(null)
    .mockResolvedValueOnce({ fileName: '다른 공고문.pdf', pdfSha256: 'other', createdAt: '2026-11-01T01:00:00Z', extraction })

  await expect(extractAnnouncementPdf('29', pdf, '공고문.pdf')).rejects.toThrow('진행 중이던 추출이 방금 끝났습니다.')
})

test('기다리는 시간이 상한을 넘으면 더 묻지 않고 이어서 기다리는 방법을 알린다', async () => {
  mocks.request.mockResolvedValue(job('PENDING'))

  const result = extractAnnouncementPdf('29', pdf, '공고문.pdf')
  const settled = expect(result).rejects.toThrow('추출이 아직 끝나지 않았습니다.')
  await vi.advanceTimersByTimeAsync(JOB_WAIT_LIMIT_MS + JOB_POLL_MS)
  await settled
  const calls = mocks.request.mock.calls.length
  await vi.advanceTimersByTimeAsync(JOB_POLL_MS * 3)

  expect(mocks.request).toHaveBeenCalledTimes(calls)
})

test('공고 첨부가 미리 본 파일과 다른 PDF로 추출되면 결과를 쓰지 않고 다시 추출하게 한다', async () => {
  mocks.request.mockResolvedValueOnce(job('SUCCEEDED', { pdfSha256: 'changed', extraction }))

  await expect(extractAnnouncementPdf('29', pdf, '공고문.pdf', undefined, { attachmentId: '68' }))
    .rejects.toThrow('추출한 첨부 PDF가 화면에서 미리 본 파일과 다릅니다.')
})

test('알 수 없는 작업 상태면 실패로 끝낸다', async () => {
  mocks.request.mockResolvedValueOnce(job('CANCELLED'))

  await expect(extractAnnouncementPdf('29', pdf, '공고문.pdf')).rejects.toThrow('추출 작업 상태를 알 수 없습니다.')
})

test('기다리는 중에 취소하면 더 묻지 않는다', async () => {
  mocks.request.mockResolvedValue(job('PENDING'))
  const controller = new AbortController()

  const result = extractAnnouncementPdf('29', new Blob(['%PDF']), '공고문.pdf', controller.signal)
  const settled = expect(result).rejects.toBeDefined()
  await vi.advanceTimersByTimeAsync(0)
  controller.abort()
  await settled
  await vi.advanceTimersByTimeAsync(JOB_POLL_MS * 3)

  expect(mocks.request).toHaveBeenCalledOnce()
})
