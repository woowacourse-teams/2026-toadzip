import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const summary = {
  id: 7, name: '두꺼비 단지', subtitle: '서울 중구', provider: 'LH', rental: 'HAPPY_HOUSING',
  deleted: false, modified: true, reviewRequired: false, updatedAt: null,
}

beforeEach(() => {
  vi.stubEnv('DEV', true)
  vi.stubEnv('VITE_API_BASE_URL', '')
})

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('관리 데이터 API', () => {
  it('목록 조회에 조건·쿠키·취소 신호를 전달하고 응답 봉투를 읽는다', async () => {
    const page = { items: [summary], page: 2, hasNext: false, totalElements: 41, totalPages: 3 }
    const fetchMock = mockFetch({ data: page })
    const { getManagementPage } = await import('./api')
    const signal = new AbortController().signal
    const params = new URLSearchParams({ keyword: '두꺼비', page: '2' })

    await expect(getManagementPage('complexes', params, signal)).resolves.toEqual(page)

    expect(fetchMock).toHaveBeenCalledOnce()
    expect(fetchMock).toHaveBeenCalledWith(`http://localhost:8080/api/admin/housing-complexes?${params}`, {
      method: 'GET', credentials: 'include', headers: {}, body: undefined, signal,
    })
  })

  it('상세 조회의 ID를 URL 경로로 인코딩하고 생략 가능한 연결 목록은 빈 목록으로 읽는다', async () => {
    const fetchMock = mockFetch({ data: { summary, sourceIdentifier: 'MANUAL-7', data: { version: 1, name: '두꺼비' } } })
    const { getManagementDetail } = await import('./api')

    await expect(getManagementDetail('announcements', '7/8')).resolves.toMatchObject({
      summary, housingTypes: [], announcements: [], supplyRows: [], schedules: [], scheduleReviewed: false,
    })
    expect(fetchMock.mock.calls[0]?.[0]).toBe('http://localhost:8080/api/admin/announcements/7%2F8')
  })

  it.each([
    { totalElements: -1 }, { totalPages: 1.5 }, { items: [{ ...summary, deleted: 'false' }] },
  ])('잘못된 목록 계약을 거절한다: %j', async (invalid) => {
    mockFetch({ items: [summary], page: 0, hasNext: false, totalElements: 1, totalPages: 1, ...invalid })
    const { getManagementPage } = await import('./api')
    await expect(getManagementPage('complexes', new URLSearchParams())).rejects.toThrow('목록 응답이 올바르지 않습니다.')
  })

  it('수정 요청은 동적 CSRF 헤더와 JSON 본문을 보내고 서버의 수정값을 반환한다', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ token: 'test-token', headerName: 'X-CUSTOM-CSRF' }))
      .mockResolvedValueOnce(Response.json({ data: { version: 3 } }))
    vi.stubGlobal('fetch', fetchMock)
    const { requestManagementApi } = await import('./api')
    const signal = new AbortController().signal

    await expect(requestManagementApi('/api/admin/housing-complexes/7', 'PUT', { version: 2 }, signal))
      .resolves.toEqual({ version: 3 })
    expect(fetchMock).toHaveBeenNthCalledWith(1, 'http://localhost:8080/api/admin/auth/csrf', {
      credentials: 'include', signal,
    })
    expect(fetchMock).toHaveBeenNthCalledWith(2, 'http://localhost:8080/api/admin/housing-complexes/7', {
      method: 'PUT', credentials: 'include', signal,
      headers: { 'X-CUSTOM-CSRF': 'test-token', 'Content-Type': 'application/json' },
      body: JSON.stringify({ version: 2 }),
    })
  })

  it('204 삭제 응답은 JSON 해석 없이 처리하고 본문 없는 요청에 Content-Type을 추가하지 않는다', async () => {
    const response = new Response(null, { status: 204 })
    const json = vi.spyOn(response, 'json')
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ token: 'test-token', headerName: 'X-CSRF' }))
      .mockResolvedValueOnce(response)
    vi.stubGlobal('fetch', fetchMock)
    const { requestManagementApi } = await import('./api')

    await expect(requestManagementApi('/api/admin/announcements/7?version=1', 'DELETE')).resolves.toBeNull()
    expect(json).not.toHaveBeenCalled()
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ headers: { 'X-CSRF': 'test-token' }, body: undefined })
    expect(fetchMock.mock.calls[1]?.[1].headers).not.toHaveProperty('Content-Type')
  })

  it('CSRF 조회가 실패하면 변경 요청을 보내지 않는다', async () => {
    const fetchMock = mockFetch({}, 401)
    const { requestManagementApi, ManagementError } = await import('./api')
    await expect(requestManagementApi('/api/admin/announcements/7', 'DELETE')).rejects.toBeInstanceOf(ManagementError)
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it.each([null, { token: 3, headerName: 'X-CSRF' }, { token: 'test-token' }])(
    'CSRF 응답 형식이 잘못되면 변경 요청을 보내지 않는다: %j', async (body) => {
      const fetchMock = mockFetch(body)
      const { requestManagementApi } = await import('./api')
      await expect(requestManagementApi('/api/admin/announcements/7', 'PUT', {})).rejects.toThrow('인증 응답이 올바르지 않습니다.')
      expect(fetchMock).toHaveBeenCalledOnce()
    },
  )

  it('오류 응답의 상태·메시지·필드 오류를 보존하고 잘못된 오류 항목은 제외한다', async () => {
    mockFetch({ message: '다른 작업에서 변경했습니다.', errors: [
      { field: 'name', reason: '필수 값입니다.' }, { field: 'version', reason: 1 }, null,
    ] }, 409)
    const { requestManagementApi } = await import('./api')
    await expect(requestManagementApi('/api/admin/announcements/7')).rejects.toMatchObject({
      status: 409, message: '다른 작업에서 변경했습니다.', fields: { name: '필수 값입니다.' },
    })
  })

  it('JSON이 아닌 실패 응답에도 상태와 기본 오류 메시지를 제공한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('gateway unavailable', { status: 502 })))
    const { requestManagementApi } = await import('./api')
    await expect(requestManagementApi('/api/admin/announcements')).rejects.toMatchObject({
      status: 502, message: '요청을 처리하지 못했습니다. 다시 시도해 주세요.',
    })
  })

  it('취소 오류를 보존한다', async () => {
    const error = new DOMException('취소됨', 'AbortError')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(error))
    const { getManagementPage } = await import('./api')
    await expect(getManagementPage('complexes', new URLSearchParams())).rejects.toBe(error)
  })

  it('수정 이력의 페이지를 요청하고 원문 변경 내용을 보존한다', async () => {
    const change = { id: 1, action: 'UPDATE', actor: 'admin', occurredAt: '2026-10-01T00:00:00Z', beforeValue: '{}', afterValue: '{"name":"변경"}' }
    const fetchMock = mockFetch([change])
    const { getManagementHistory } = await import('./api')
    await expect(getManagementHistory('complexes', '7', 2)).resolves.toEqual([change])
    expect(fetchMock.mock.calls[0]?.[0]).toBe('http://localhost:8080/api/admin/housing-complexes/7/changes?page=2')
  })

  it('잘못된 이력 항목을 빈 성공으로 바꾸지 않는다', async () => {
    mockFetch([{ id: 1 }])
    const { getManagementHistory } = await import('./api')
    await expect(getManagementHistory('announcements', '7', 0)).rejects.toThrow('수정 이력 응답이 올바르지 않습니다.')
  })
})

function mockFetch(body: unknown, status = 200) {
  const fetchMock = vi.fn().mockImplementation(async () => Response.json(body, { status }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}
