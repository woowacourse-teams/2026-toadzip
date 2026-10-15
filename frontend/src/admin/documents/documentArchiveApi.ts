import { getApiBaseUrl } from '../../api/apiBaseUrl'
import { requestManagementApi } from '../management/api'
import { isRecord, type ManagementResource } from '../management/managementContract'

export type ArchivedDocument = { sha256: string; fileName: string; sourceUrl: string; byteCount: number; actor: string; createdAt: string }
export function documentArchivePath(resource: ManagementResource, id: number) {
  return `/api/admin/quality/${resource === 'complexes' ? 'COMPLEX' : 'ANNOUNCEMENT'}/${id}/documents`
}
export function documentDownloadUrl(resource: ManagementResource, id: number, sha256: string) {
  return getApiBaseUrl() + documentArchivePath(resource, id) + '/' + sha256
}
export async function listArchivedDocuments(resource: ManagementResource, id: number, page: number, signal: AbortSignal): Promise<ArchivedDocument[]> {
  const response = await requestManagementApi(`${documentArchivePath(resource, id)}?page=${page}`, 'GET', undefined, signal)
  if (!Array.isArray(response) || !response.every(item => isRecord(item) && typeof item.sha256 === 'string' && /^[a-f0-9]{64}$/.test(item.sha256)
    && typeof item.fileName === 'string' && typeof item.sourceUrl === 'string' && Number.isSafeInteger(item.byteCount)
    && typeof item.actor === 'string' && typeof item.createdAt === 'string' && Number.isFinite(Date.parse(item.createdAt)))) throw new Error('원본 보관 목록 응답을 확인해 주세요.')
  return response as ArchivedDocument[]
}
export async function archiveDocument(resource: ManagementResource, id: number, file: File, sourceUrl: string, signal: AbortSignal): Promise<string> {
  const csrf = await requestManagementApi('/api/admin/auth/csrf', 'GET', undefined, signal)
  if (!isRecord(csrf) || typeof csrf.headerName !== 'string' || typeof csrf.token !== 'string') throw new Error('관리자 인증 정보를 확인해 주세요.')
  const body = new FormData(); body.set('file', file); body.set('sourceUrl', sourceUrl)
  const response = await fetch(getApiBaseUrl() + documentArchivePath(resource, id), {
    method: 'POST', credentials: 'include', headers: { [csrf.headerName]: csrf.token }, body, signal,
  })
  const result: unknown = await response.json().catch(() => null)
  if (!response.ok) throw new Error(isRecord(result) && typeof result.message === 'string' ? result.message : '원본을 보관하지 못했습니다.')
  if (!isRecord(result) || !isRecord(result.data) || typeof result.data.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(result.data.sha256)) throw new Error('원본 보관 응답을 확인해 주세요.')
  return result.data.sha256
}
