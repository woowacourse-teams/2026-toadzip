import { requestManagementApi } from '../management/api'
import { isRecord, type ManagementResource } from '../management/managementContract'
import { documentArchivePath, documentDownloadUrl } from './documentArchiveApi'
import { safeDocumentUrl } from './documentReview'

export type SharedDraft = { target: string; revision: number; documentVersion: number; sha256: string;
  sourceUrl: string; pages: string; resultJson: string; note: string; actor: string; updatedAt: string }
export type SharedDraftInput = Pick<SharedDraft, 'target' | 'documentVersion' | 'sha256' | 'sourceUrl' | 'pages' | 'resultJson' | 'note'>
const root = (resource: ManagementResource, id: number) => documentArchivePath(resource, id).replace(/\/documents$/, '/draft')
function parse(value: unknown): SharedDraft {
  if (!isRecord(value) || typeof value.target !== 'string' || !/^(basic|(?:type|row|target):[1-9]\d{0,17})$/.test(value.target)
    || !Number.isSafeInteger(value.revision) || Number(value.revision) < 1
    || !Number.isSafeInteger(value.documentVersion) || Number(value.documentVersion) < 0
    || typeof value.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(value.sha256)
    || typeof value.sourceUrl !== 'string' || !safeDocumentUrl(value.sourceUrl)
    || typeof value.pages !== 'string' || !value.pages.length || value.pages.length > 500
    || typeof value.resultJson !== 'string' || value.resultJson.length > 1000000
    || typeof value.note !== 'string' || value.note.length > 2000 || typeof value.actor !== 'string'
    || typeof value.updatedAt !== 'string' || !Number.isFinite(Date.parse(value.updatedAt))) throw new Error('공유 초안 응답을 확인해 주세요.')
  return value as SharedDraft
}
export async function loadSharedDraft(resource: ManagementResource, id: number, target: string, signal: AbortSignal): Promise<SharedDraft | null> {
  const result = await requestManagementApi(`${root(resource, id)}?${new URLSearchParams({ target })}`, 'GET', undefined, signal)
  if (result === null) return null
  const draft = parse(result)
  if (draft.target !== target) throw new Error('다른 대상의 공유 초안입니다.')
  return draft
}
export async function saveSharedDraft(resource: ManagementResource, id: number, input: SharedDraftInput, expectedRevision: number, signal: AbortSignal): Promise<SharedDraft> {
  const draft = parse(await requestManagementApi(root(resource, id), 'PUT', { ...input, expectedRevision }, signal))
  if (draft.target !== input.target || draft.sha256 !== input.sha256 || draft.revision !== expectedRevision + 1) throw new Error('저장한 초안의 대상·원본·revision을 확인해 주세요.')
  return draft
}
export async function sharedDraftFile(resource: ManagementResource, id: number, draft: SharedDraft, signal: AbortSignal): Promise<File> {
  const response = await fetch(documentDownloadUrl(resource, id, draft.sha256), { credentials: 'include', signal })
  if (!response.ok) throw new Error('공유 초안의 PDF 원본을 가져오지 못했습니다.')
  const blob = await response.blob()
  if (blob.size > 25 * 1024 * 1024) throw new Error('공유 PDF가 25MB 한도를 초과했습니다.')
  return new File([blob], '공유 검토 원본.pdf', { type: 'application/pdf' })
}
