import type { JsonWorkspace } from '../announcement-json/api'
import { isRecord } from '../management/managementContract'
import { requestManagementApi } from '../management/api'

type Attachment = NonNullable<JsonWorkspace['detail']['attachments']>[number]

/** 서버가 공고문 PDF에서 뽑은 보정용 JSON. 저장되지 않으며 검토 화면의 입력으로만 쓴다. */
export type PdfExtraction = {
  json: string
  /** 모델에 보낸 원본 쪽 번호(1부터) */
  pages: number[]
  totalPages: number
  warnings: string[]
  usage: { promptTokens: number | null; outputTokens: number | null; thinkingTokens: number | null }
}

export type ExtractionState = { status: 'idle' } | { status: 'running'; startedAt: number } | { status: 'done'; result: PdfExtraction } | { status: 'failed'; message: string }

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
