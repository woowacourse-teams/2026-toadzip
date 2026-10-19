import { isRecord } from '../management/managementContract'
import { agentPacket, parseReviewResult, type ReviewTarget } from './documentReview'
import type { ExtractedDocument } from './pdfExtraction'

export type ExchangeDirectory = { name: string; queryPermission?: (options: { mode: 'readwrite' }) => Promise<PermissionState>;
  requestPermission?: (options: { mode: 'readwrite' }) => Promise<PermissionState>; getFileHandle: (name: string, options?: { create?: boolean }) => Promise<{
  createWritable: () => Promise<{ write: (value: string) => Promise<void>; close: () => Promise<void>; abort: () => Promise<void> }>;
  getFile: () => Promise<File>;
}> }
export type ExchangeRequest = { id: string; requestFile: string; resultFile: string; errorFile: string }
export type ExchangeWorker = { workerId: string; command?: string; cancellation?: boolean }
const missing = (cause: unknown) => isRecord(cause) && cause.name === 'NotFoundError'
async function readStatus(directory: ExchangeDirectory, name: string): Promise<unknown> {
  try {
    const file = await (await directory.getFileHandle(name)).getFile()
    if (file.size > 4096) throw new Error('연결 상태 파일이 너무 큽니다.')
    try { return JSON.parse(await file.text()) as unknown }
    catch { throw new Error('연결 상태 파일 형식을 확인해 주세요.') }
  } catch (cause) { if (missing(cause)) return null; throw cause }
}
export async function readExchangeWorker(directory: ExchangeDirectory, now = Date.now()): Promise<ExchangeWorker | null> {
  const value = await readStatus(directory, '.review-worker.json')
  if (value === null) return null
  if (!isRecord(value) || value.schemaVersion !== 'document-exchange-worker/v1'
    || typeof value.workerId !== 'string' || !value.workerId || value.workerId.length > 100
    || typeof value.updatedAt !== 'string' || !Number.isFinite(Date.parse(value.updatedAt))) throw new Error('처리 도구의 연결 상태 형식을 확인해 주세요.')
  const age = now - Date.parse(value.updatedAt)
  if (age > 20000 || age < -5000) return null
  return { workerId: value.workerId, ...(value.cancellation === true ? { cancellation: true } : {}), ...(typeof value.command === 'string' && value.command.length < 2000 ? { command: value.command } : {}) }
}
export async function cancelExchangeRequest(directory: ExchangeDirectory, request: ExchangeRequest): Promise<void> {
  const writable = await (await directory.getFileHandle(`review-${request.id}.cancel.json`, { create: true })).createWritable()
  try { await writable.write(JSON.stringify({ schemaVersion: 'document-exchange/v1', requestId: request.id })); await writable.close() }
  catch (cause) { await writable.abort().catch(() => {}); throw cause }
}
export async function readExchangeProgress(directory: ExchangeDirectory, request: ExchangeRequest, worker: ExchangeWorker): Promise<'waiting' | 'processing' | 'interrupted'> {
  const value = await readStatus(directory, `review-${request.id}.status.json`)
  if (value === null) return 'waiting'
  if (!isRecord(value) || value.schemaVersion !== 'document-exchange-status/v1' || value.requestId !== request.id) throw new Error('다른 요청의 진행 상태입니다. 요청 ID를 확인해 주세요.')
  if (value.state !== 'processing' || typeof value.workerId !== 'string') throw new Error('요청 진행 상태 형식을 확인해 주세요.')
  return value.workerId === worker.workerId ? 'processing' : 'interrupted'
}
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
