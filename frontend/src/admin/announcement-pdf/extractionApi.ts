import type { JsonWorkspace } from '../announcement-json/api'
import { isRecord } from '../management/managementContract'
import { requestManagementApi } from '../management/api'

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

export type ExtractionState = { status: 'idle' } | { status: 'running'; startedAt: number }
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

export async function extractAnnouncementPdf(id: string, file: Blob, fileName: string, signal?: AbortSignal): Promise<PdfExtraction> {
  const form = new FormData()
  form.append('file', file, fileName)
  return parsePdfExtraction(await requestManagementApi(`/api/admin/announcements/${encodeURIComponent(id)}/pdf-extractions`, 'POST', form, signal))
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
