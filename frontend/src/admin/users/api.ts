import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

export type UserProvider = 'GOOGLE' | 'KAKAO' | 'UNKNOWN'
export type AdminUser = { id: number; email: string | null; provider: UserProvider; createdAt: string }
export type UserPage = { items: AdminUser[]; page: number; hasNext: boolean; totalElements: number; totalPages: number }
export async function listUsers(params: URLSearchParams, signal?: AbortSignal): Promise<UserPage> {
  const value = await requestManagementApi(`/api/admin/users?${params}`, 'GET', undefined, signal)
  if (!isRecord(value) || !Array.isArray(value.items) || !isCount(value.page) || typeof value.hasNext !== 'boolean'
    || !isCount(value.totalElements) || !isCount(value.totalPages)) throw new Error('회원 목록 응답이 올바르지 않습니다.')
  return { items: value.items.map(parseUser), page: value.page, hasNext: value.hasNext,
    totalElements: value.totalElements, totalPages: value.totalPages }
}
export async function getUser(id: string, signal?: AbortSignal): Promise<AdminUser> {
  return parseUser(await requestManagementApi(`/api/admin/users/${encodeURIComponent(id)}`, 'GET', undefined, signal))
}
function parseUser(value: unknown): AdminUser {
  if (!isRecord(value) || !isCount(value.id) || value.id === 0 || (value.email !== null && typeof value.email !== 'string')
    || !isUserProvider(value.provider) || typeof value.createdAt !== 'string'
    || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?$/.test(value.createdAt)) {
    throw new Error('회원 정보 응답이 올바르지 않습니다.')
  }
  return { id: value.id, email: value.email, provider: value.provider, createdAt: value.createdAt }
}
export function isUserProvider(value: unknown): value is UserProvider {
  return value === 'GOOGLE' || value === 'KAKAO' || value === 'UNKNOWN'
}
function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}
