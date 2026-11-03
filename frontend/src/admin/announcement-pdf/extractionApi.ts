import type { JsonWorkspace } from '../announcement-json/api'
import { isRecord } from '../management/managementContract'
import { ManagementError, requestManagementApi } from '../management/api'

type Attachment = NonNullable<JsonWorkspace['detail']['attachments']>[number]

/** 서버가 공고문 PDF에서 뽑은 보정용 JSON. 검토 화면의 입력으로 쓰고, 서버는 다시 불러올 수 있게 남겨 둔다. */
export type PdfExtraction = {
  json: string
  /** 모델에 보낸 원본 쪽 번호(1부터) */
  pages: number[]
  totalPages: number
  warnings: string[]
  usage: { promptTokens: number | null; outputTokens: number | null; thinkingTokens: number | null }
}

/** 서버에 남겨 둔 이전 추출 결과. pdfSha256으로 지금 고른 PDF와 같은 파일인지 비교한다. */
export type SavedExtraction = { fileName: string; pdfSha256: string; createdAt: string; extraction: PdfExtraction }

export type ExtractionState = { status: 'idle' } | { status: 'running'; startedAt: number; notice?: string }
  | { status: 'done'; result: PdfExtraction; saved?: SavedExtraction } | { status: 'failed'; message: string }

const count = (value: unknown) => typeof value === 'number' && Number.isInteger(value) && value >= 0 ? value : null

function parsePdfExtraction(value: unknown): PdfExtraction {
  if (!isRecord(value) || typeof value.json !== 'string' || !Array.isArray(value.pages) || !value.pages.every(page => count(page) !== null)
    || count(value.totalPages) === null || !Array.isArray(value.warnings) || !value.warnings.every(item => typeof item === 'string') || !isRecord(value.usage)) {
    throw new Error('추출 응답 형식이 올바르지 않습니다.')
  }
  return { json: value.json, pages: value.pages as number[], totalPages: value.totalPages as number, warnings: value.warnings as string[],
    usage: { promptTokens: count(value.usage.promptTokens), outputTokens: count(value.usage.outputTokens), thinkingTokens: count(value.usage.thinkingTokens) } }
}

/** 추출 작업 상태를 다시 묻는 간격. 추출은 보통 1~3분 걸린다. */
export const JOB_POLL_MS = 3000
/**
 * 이보다 오래 끝나지 않으면 기다리기를 멈춘다. 워커는 넣은 지 40분이 지난 작업을 실패로 끝내므로(ai-worker DEADLINE)
 * 보통은 이 상한 전에 성공이나 실패를 받는다. 상한은 워커가 멈춘 경우의 안전장치다.
 */
export const JOB_WAIT_LIMIT_MS = 45 * 60 * 1000
/** 워커는 한 번에 작업 하나만 처리해, 앞선 작업이 길면 정상이어도 이만큼 PENDING일 수 있다. 그래서 멈췄다고 단정하지 않고 확인을 권한다. */
export const NOT_STARTED_NOTICE_MS = 3 * 60 * 1000
const NOT_STARTED_NOTICE = '아직 처리가 시작되지 않았습니다. 앞선 추출을 처리 중일 수 있으니, 계속 그대로면 AI 워커가 실행 중인지 확인해 주세요.'

type Reason = { code: string; message: string }
type ExtractionJob = { jobId: number; status: string; fileName: string; pdfSha256: string; failure: Reason | null; retryReason: Reason | null; extraction: unknown }

/** attachmentId가 있으면 서버가 그 공고 첨부를 받아 작업을 넣는다. onNotice는 기다리는 동안 보일 안내(재시도 사유, 처리 미시작)가 바뀔 때마다 받는다. */
export type ExtractionOptions = { attachmentId?: string; onNotice?: (message: string | null) => void }

const pendingStatuses = new Set(['PENDING', 'RUNNING', 'RETRY'])

function parseJob(value: unknown): ExtractionJob {
  if (!isRecord(value) || count(value.jobId) === null || typeof value.status !== 'string' || typeof value.fileName !== 'string' || typeof value.pdfSha256 !== 'string') {
    throw new Error('추출 작업 응답 형식이 올바르지 않습니다.')
  }
  return { jobId: value.jobId as number, status: value.status, fileName: value.fileName, pdfSha256: value.pdfSha256,
    failure: parseReason(value.failure), retryReason: parseReason(value.retryReason), extraction: value.extraction }
}

const parseReason = (value: unknown): Reason | null => isRecord(value) && typeof value.code === 'string' && typeof value.message === 'string'
  ? { code: value.code, message: value.message } : null

function wait(ms: number, signal?: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason)
    const abort = () => { clearTimeout(timer); reject(signal?.reason) }
    const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve() }, ms)
    signal?.addEventListener('abort', abort, { once: true })
  })
}

/** 진행 중 작업에 이어 붙는다. 다른 PDF의 작업이면 그 결과를 이 PDF의 결과로 쓰지 않는다. */
async function joinActiveJob(id: string, sha256: string, signal?: AbortSignal): Promise<ExtractionJob | PdfExtraction> {
  const path = `/api/admin/announcements/${encodeURIComponent(id)}/pdf-extractions`
  const active = await requestManagementApi(`${path}/jobs/active`, 'GET', undefined, signal)
  if (active === null) {
    // 그사이 끝났다. 같은 PDF가 성공했으면 그 결과를 쓰고, 아니면 다시 추출하게 한다.
    const latest = await loadLatestExtraction(id, signal)
    if (latest?.pdfSha256 === sha256) return latest.extraction
    throw new Error('진행 중이던 추출이 방금 끝났습니다. 다시 추출해 주세요.')
  }
  const job = parseJob(active)
  if (job.pdfSha256 !== sha256) throw new Error(`다른 PDF(${job.fileName})의 추출이 진행 중입니다. 끝난 뒤 다시 추출해 주세요.`)
  return job
}

/**
 * 공고문 PDF 추출 작업을 넣고 끝날 때까지 기다린다. 추출은 AI 워커가 따로 한다.
 * 공고 첨부면 서버가 첨부를 받고, 관리자가 고른 파일이면 파일을 올린다. file은 진행 중 작업이 같은 PDF인지 비교하는 데 쓴다.
 * 이 공고에 같은 PDF의 진행 중 작업이 이미 있으면 새로 넣지 않고 그 작업을 이어서 기다린다.
 */
export async function extractAnnouncementPdf(id: string, file: Blob, fileName: string, signal?: AbortSignal, options: ExtractionOptions = {}): Promise<PdfExtraction> {
  const path = `/api/admin/announcements/${encodeURIComponent(id)}/pdf-extractions`
  let job: ExtractionJob
  try {
    job = parseJob(options.attachmentId
      ? await requestManagementApi(`${path}/attachments/${encodeURIComponent(options.attachmentId)}`, 'POST', undefined, signal)
      : await requestManagementApi(path, 'POST', upload(file, fileName), signal))
  } catch (error) {
    if (!(error instanceof ManagementError && error.code === 'ANNOUNCEMENT_PDF_EXTRACTION_IN_PROGRESS')) throw error
    const joined = await joinActiveJob(id, await pdfSha256(file), signal)
    if (!('jobId' in joined)) return joined
    job = joined
  }
  const waitStart = Date.now()
  const deadline = waitStart + JOB_WAIT_LIMIT_MS
  let notice: string | null = null
  while (job.status !== 'SUCCEEDED') {
    if (job.status === 'FAILED') throw new Error(job.failure?.message ?? '추출에 실패했습니다.')
    if (!pendingStatuses.has(job.status)) throw new Error('추출 작업 상태를 알 수 없습니다.')
    const nextNotice = waitingNotice(job, Date.now() - waitStart)
    if (nextNotice !== notice) { notice = nextNotice; options.onNotice?.(notice) }
    if (Date.now() >= deadline) throw new Error(WAIT_LIMIT_MESSAGE)
    await wait(JOB_POLL_MS, signal)
    job = parseJob(await requestBefore(deadline, `${path}/jobs/${job.jobId}`, signal))
  }
  // 공고 첨부는 서버가 따로 받으므로, 미리 본 뒤 첨부가 바뀌었으면 결과의 쪽·인용이 화면의 PDF와 맞지 않는다.
  if (job.pdfSha256 !== await pdfSha256(file)) throw new Error('추출한 첨부 PDF가 화면에서 미리 본 파일과 다릅니다. 그사이 첨부가 바뀌었을 수 있으니 PDF를 다시 고른 뒤 다시 추출해 주세요.')
  return parsePdfExtraction(job.extraction)
}

const WAIT_LIMIT_MESSAGE = '추출이 아직 끝나지 않았습니다. 잠시 뒤 다시 추출을 누르면 이어서 기다립니다.'

/**
 * 상태 조회가 응답 없이 멈춰도 기다리기 상한에서 끝나도록, 남은 시간이 지나면 요청을 끊고 상한 초과로 알린다.
 * 상한 직전에 기다리기를 시작한 마지막 조회도 결과를 받을 수 있게 조회 간격만큼은 기다린다.
 */
async function requestBefore(deadline: number, path: string, signal?: AbortSignal) {
  const controller = new AbortController()
  const expired = new Error(WAIT_LIMIT_MESSAGE)
  const abort = () => controller.abort(signal?.reason)
  if (signal?.aborted) abort()
  signal?.addEventListener('abort', abort, { once: true })
  const timer = setTimeout(() => controller.abort(expired), Math.max(deadline - Date.now(), JOB_POLL_MS))
  try {
    return await requestManagementApi(path, 'GET', undefined, controller.signal)
  } catch (error) {
    throw controller.signal.reason === expired ? expired : error
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
  }
}

function waitingNotice(job: ExtractionJob, waited: number) {
  if (job.status === 'RETRY' && job.retryReason) return `잠시 뒤 다시 시도합니다 · ${job.retryReason.message}`
  if (job.status === 'PENDING' && waited >= NOT_STARTED_NOTICE_MS) return NOT_STARTED_NOTICE
  return null
}

function upload(file: Blob, fileName: string) {
  const form = new FormData()
  form.append('file', file, fileName)
  return form
}

/** 공고에 가장 최근에 저장한 추출 결과. 없으면 null이다. 모델을 다시 부르지 않는다. */
export async function loadLatestExtraction(id: string, signal?: AbortSignal): Promise<SavedExtraction | null> {
  const value = await requestManagementApi(`/api/admin/announcements/${encodeURIComponent(id)}/pdf-extractions/latest`, 'GET', undefined, signal)
  if (value === null) return null
  if (!isRecord(value) || typeof value.fileName !== 'string' || typeof value.pdfSha256 !== 'string' || typeof value.createdAt !== 'string') {
    throw new Error('저장한 추출 결과 형식이 올바르지 않습니다.')
  }
  return { fileName: value.fileName, pdfSha256: value.pdfSha256, createdAt: value.createdAt, extraction: parsePdfExtraction(value.extraction) }
}

/** 서버와 같은 기준(파일 내용의 SHA-256, 소문자 16진수)으로 PDF를 식별한다. */
export async function pdfSha256(file: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
}

const isPdf = (attachment: Attachment) => /\.pdf(?:$|[?\s])/i.test(attachment.fileName)

/** 공고문 PDF를 먼저 고른다. 종류가 공고문인 첨부, 이름에 공고문·모집공고가 든 첨부, 첫 PDF 순서다. */
export function preferredPdf(attachments: Attachment[]): Attachment | undefined {
  const pdfs = attachments.filter(isPdf)
  return pdfs.find(item => item.fileType === 'ANNOUNCEMENT' || item.fileType === '공고문')
    ?? pdfs.find(item => /공고문|모집공고/.test(item.fileName))
    ?? pdfs[0]
}

/** 고른 파일이 PDF가 아니면 이유를 돌려준다. 파일 고르기와 끌어다 놓기가 같은 기준을 쓴다. */
export function pdfFileProblem(file: File) {
  return file.type === 'application/pdf' || /\.pdf$/i.test(file.name) ? '' : 'PDF 파일만 고를 수 있습니다.'
}
