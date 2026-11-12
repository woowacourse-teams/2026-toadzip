import { afterEach, expect, it, vi } from 'vitest'
import { getUser, listUsers, setUserAdmin } from './api'

vi.mock('../../api/apiBaseUrl', () => ({ getApiBaseUrl: () => 'http://localhost:8080' }))

const user = { id: 7, email: null, provider: 'UNKNOWN', createdAt: '2026-10-04T09:12:34.123456', admin: false }
function respond(value: unknown, status = 200) {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}
afterEach(() => vi.unstubAllGlobals())

it('회원 페이지를 검증하고 인증 쿠키와 취소 신호를 전달한다', async () => {
  const fetchMock = respond({ data: { items: [user], page: 0, hasNext: false, totalElements: 1, totalPages: 1 } })
  const params = new URLSearchParams({ keyword: 'member+tag@example.test', provider: 'UNKNOWN', page: '0', size: '20' })
  const controller = new AbortController()
  expect(await listUsers(params, controller.signal)).toEqual({ items: [user], page: 0, hasNext: false, totalElements: 1, totalPages: 1 })
  expect(fetchMock).toHaveBeenCalledWith(`http://localhost:8080/api/admin/users?${params}`, expect.objectContaining({
    credentials: 'include', method: 'GET', signal: controller.signal,
  }))
})

it('상세는 허용한 기본 필드만 반환하며 미제공 이메일과 미확인 로그인 방식을 보존한다', async () => {
  respond({ data: { ...user, loginIdentifier: 'opaque-subject-not-for-ui', accessToken: 'not-for-ui' } })
  expect(await getUser('7')).toEqual(user)
})

it.each([
  { id: -1 }, { id: 1.5 }, { id: Number.MAX_SAFE_INTEGER + 1 }, { email: 7 },
  { provider: 'FACEBOOK' }, { createdAt: null }, { createdAt: '2026-10-04T09:12:34Z' }, { admin: 'true' }, { admin: undefined },
])('회원 정보의 잘못된 필드를 거부한다: %o', async fields => {
  respond({ data: { ...user, ...fields } })
  await expect(getUser('7')).rejects.toThrow('회원 정보 응답이 올바르지 않습니다.')
})

it.each([
  { page: -1 }, { totalElements: '1' }, { totalPages: 1.5 }, { hasNext: 'false' }, { items: null },
])('잘못된 목록 메타데이터를 거부한다: %o', async fields => {
  respond({ data: { items: [user], page: 0, hasNext: false, totalElements: 1, totalPages: 1, ...fields } })
  await expect(listUsers(new URLSearchParams())).rejects.toThrow('회원 목록 응답이 올바르지 않습니다.')
})

it('접근 거부와 없는 회원 응답을 빈 성공 데이터로 바꾸지 않는다', async () => {
  respond({ message: '관리자 권한이 필요합니다.' }, 403)
  await expect(listUsers(new URLSearchParams())).rejects.toMatchObject({ status: 403, message: '관리자 권한이 필요합니다.' })
  respond({ message: '회원을 찾을 수 없습니다.' }, 404)
  await expect(getUser('7')).rejects.toMatchObject({ status: 404, message: '회원을 찾을 수 없습니다.' })
})

it.each([
  { admin: true, method: 'PUT' }, { admin: false, method: 'DELETE' },
])('관리자 권한 변경은 CSRF 토큰을 붙여 $method로 보내고 바뀐 회원을 돌려준다', async ({ admin, method }) => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 'csrf' }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ data: { ...user, admin } }), { status: 200 }))
  vi.stubGlobal('fetch', fetchMock)
  expect(await setUserAdmin(7, admin)).toEqual({ ...user, admin })
  expect(fetchMock).toHaveBeenLastCalledWith('http://localhost:8080/api/admin/users/7/admin', expect.objectContaining({
    method, credentials: 'include', headers: { 'X-XSRF-TOKEN': 'csrf' },
  }))
})
