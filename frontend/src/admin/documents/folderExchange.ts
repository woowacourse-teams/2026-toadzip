import { isRecord } from '../management/managementContract'
import { agentPacket, parseReviewResult, type ReviewTarget } from './documentReview'
import type { ExtractedDocument } from './pdfExtraction'

export type ExchangeDirectory = { name: string; getFileHandle: (name: string, options?: { create?: boolean }) => Promise<{
  createWritable: () => Promise<{ write: (value: string) => Promise<void>; close: () => Promise<void>; abort: () => Promise<void> }>;
  getFile: () => Promise<File>;
}> }
export type ExchangeRequest = { id: string; requestFile: string; resultFile: string; errorFile: string }
const missing = (cause: unknown) => isRecord(cause) && cause.name === 'NotFoundError'
export async function pickExchangeDirectory(): Promise<ExchangeDirectory> {
  const picker = (window as Window & { showDirectoryPicker?: (options: { mode: 'readwrite' }) => Promise<ExchangeDirectory> }).showDirectoryPicker
  if (!picker) throw new Error('이 브라우저는 폴더 연결을 지원하지 않습니다. Chrome·Edge의 HTTPS 또는 localhost에서 사용하거나 기존 다운로드·가져오기를 이용하세요.')
  return picker.call(window, { mode: 'readwrite' })
}
export async function writeExchangeRequest(directory: ExchangeDirectory, target: ReviewTarget, document: ExtractedDocument, sourceUrl: string): Promise<ExchangeRequest> {
  const id = crypto.randomUUID()
  const request = { id, requestFile: `review-${id}.request.json`, resultFile: `review-${id}.result.json`, errorFile: `review-${id}.error.json` }
  const packet: unknown = JSON.parse(agentPacket(target, document, sourceUrl)) as unknown
  const content = JSON.stringify({ schemaVersion: 'document-exchange/v1', requestId: id, packet }, null, 2)
  if (new TextEncoder().encode(content).length > 5 * 1024 * 1024) throw new Error('에이전트 요청 자료는 5MB 이하여야 합니다. 페이지 범위를 줄여 주세요.')
  const handle = await directory.getFileHandle(request.requestFile, { create: true })
  const writable = await handle.createWritable()
  try { await writable.write(content); await writable.close() }
  catch (cause) { await writable.abort().catch(() => {}); throw cause }
  return request
}
export function parseExchangeResult(input: string, requestId: string, target: ReviewTarget, document: ExtractedDocument): string {
  if (new TextEncoder().encode(input).length > 1000000) throw new Error('결과 파일은 1MB 이하여야 합니다.')
  const envelope: unknown = JSON.parse(input) as unknown
  if (!isRecord(envelope) || envelope.schemaVersion !== 'document-exchange/v1' || envelope.requestId !== requestId) throw new Error('다른 요청의 결과입니다. 현재 요청 ID를 확인해 주세요.')
  const text = JSON.stringify(envelope.result)
  parseReviewResult(text, target, document)
  return text
}
export async function readExchangeResult(directory: ExchangeDirectory, request: ExchangeRequest, target: ReviewTarget,
  document: ExtractedDocument): Promise<string | null> {
  try {
    const file = await (await directory.getFileHandle(request.resultFile)).getFile()
    if (file.size > 1000000) throw new Error('결과 파일은 1MB 이하여야 합니다.')
    return parseExchangeResult(await file.text(), request.id, target, document)
  } catch (cause) { if (!missing(cause)) throw cause }
  try {
    const file = await (await directory.getFileHandle(request.errorFile)).getFile()
    if (file.size > 5000) throw new Error('연동 오류 응답이 너무 큽니다.')
    const value: unknown = JSON.parse(await file.text()) as unknown
    if (isRecord(value) && value.requestId === request.id && typeof value.message === 'string') throw new Error(value.message.slice(0, 500))
    throw new Error('연동 도구의 오류 파일을 확인해 주세요.')
  } catch (cause) { if (!missing(cause)) throw cause }
  return null
}
