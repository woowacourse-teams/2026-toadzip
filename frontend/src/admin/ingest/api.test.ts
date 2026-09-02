import { afterEach, describe, expect, it, vi } from 'vitest'

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('관리자 데이터 수집·정제 API', () => {
  it('동적 CSRF 헤더와 세션 쿠키를 포함해 수집 실행을 요청한다', async () => {
    const fetchMock = prepareFetch(execution('COLLECTION', 'RUNNING'))
    const { startDataPipeline } = await import('./api.ts')

    await expect(startDataPipeline('COLLECTION')).resolves.toMatchObject({
      type: 'COLLECTION',
      status: 'RUNNING',
    })

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      'http://localhost:8080/api/admin/auth/csrf',
      { credentials: 'include' },
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      'http://localhost:8080/api/admin/ingest/pipelines/collection',
      {
        method: 'POST',
        credentials: 'include',
        headers: { 'X-CUSTOM-CSRF': 'csrf-token' },
      },
    )
  })

  it('세션 쿠키를 포함해 정제 진행 상태를 조회한다', async () => {
    vi.stubEnv('DEV', true)
    vi.stubEnv('VITE_API_BASE_URL', '')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(execution('REFINEMENT', 'COMPLETED')))
    vi.stubGlobal('fetch', fetchMock)
    const { getDataPipelineStatus } = await import('./api.ts')

    await getDataPipelineStatus('REFINEMENT')

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/admin/ingest/pipelines/refinement',
      { credentials: 'include' },
    )
  })

  it('중복 실행 오류의 서버 응답을 보존한다', async () => {
    vi.stubEnv('DEV', true)
    vi.stubEnv('VITE_API_BASE_URL', '')
    const errorBody = {
      code: 'INGEST_ALREADY_RUNNING',
      message: '데이터 수집·정제 작업이 이미 실행 중입니다.',
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token', headerName: 'X-CSRF' }))
      .mockResolvedValueOnce(jsonResponse(errorBody, 409))
    vi.stubGlobal('fetch', fetchMock)
    const { DataPipelineApiError, startDataPipeline } = await import('./api.ts')

    const error = await startDataPipeline('COLLECTION').catch((caught) => caught)

    expect(error).toBeInstanceOf(DataPipelineApiError)
    expect(error).toMatchObject({ status: 409, serverResponse: errorBody })
  })
})

function prepareFetch(data: unknown) {
  vi.stubEnv('DEV', true)
  vi.stubEnv('VITE_API_BASE_URL', '')
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(
      jsonResponse({ token: 'csrf-token', headerName: 'X-CUSTOM-CSRF' }),
    )
    .mockResolvedValueOnce(jsonResponse(data, 202))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function execution(type: 'COLLECTION' | 'REFINEMENT', status: string) {
  return {
    executionId: '01991a11-65d2-7000-8000-000000000001',
    type,
    status,
    currentStepName: null,
    currentStepIndex: 0,
    totalStepCount: type === 'COLLECTION' ? 5 : 4,
    completedSteps: [],
    failure: null,
  }
}

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response
}
