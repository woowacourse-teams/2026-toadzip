import { afterEach, describe, expect, it, vi } from 'vitest'

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('관리자 데이터 수집·정제 API', () => {
  it('동적 CSRF 헤더와 세션 쿠키를 포함해 수집 실행을 요청한다', async () => {
    const fetchMock = prepareFetch(execution('ANNOUNCEMENT_COLLECTION', 'RUNNING'))
    const { startDataPipeline } = await import('./api.ts')

    await expect(startDataPipeline('ANNOUNCEMENT_COLLECTION')).resolves.toMatchObject({
      type: 'ANNOUNCEMENT_COLLECTION',
      status: 'RUNNING',
    })

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      'http://localhost:8080/api/admin/auth/csrf',
      { credentials: 'include' },
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      'http://localhost:8080/api/admin/ingest/pipelines/announcement-collection',
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
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(execution('COMPLEX_REFINEMENT', 'COMPLETED')),
    )
    vi.stubGlobal('fetch', fetchMock)
    const { getDataPipelineStatus } = await import('./api.ts')

    await getDataPipelineStatus('COMPLEX_REFINEMENT')

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/admin/ingest/pipelines/complex-refinement',
      { credentials: 'include' },
    )
  })

  it('동적 CSRF 헤더와 세션 쿠키를 포함해 위치정보 ZIP을 업로드한다', async () => {
    vi.stubEnv('DEV', true)
    vi.stubEnv('VITE_API_BASE_URL', '')
    const report = locationSummaryReport()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token', headerName: 'X-CSRF' }))
      .mockResolvedValueOnce(jsonResponse(report))
    vi.stubGlobal('fetch', fetchMock)
    const { uploadLocationSummary } = await import('./api.ts')
    const file = new File(['zip-content'], 'summary.zip', { type: 'application/zip' })

    await expect(uploadLocationSummary(file)).resolves.toEqual(report)

    const uploadRequest = fetchMock.mock.calls[1]
    expect(uploadRequest?.[0]).toBe(
      'http://localhost:8080/api/admin/ingest/juso/location-summaries',
    )
    expect(uploadRequest?.[1]).toMatchObject({
      method: 'POST',
      credentials: 'include',
      headers: { 'X-CSRF': 'csrf-token' },
    })
    const body = uploadRequest?.[1]?.body
    expect(body).toBeInstanceOf(FormData)
    expect((body as FormData).get('file')).toBe(file)
  })

  it('위치정보 ZIP 업로드 실패 응답을 보존한다', async () => {
    vi.stubEnv('DEV', true)
    vi.stubEnv('VITE_API_BASE_URL', '')
    const errorBody = { code: 'INVALID_INGEST_REQUEST', message: '전국 월전체분이 아닙니다.' }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token', headerName: 'X-CSRF' }))
      .mockResolvedValueOnce(jsonResponse(errorBody, 400))
    vi.stubGlobal('fetch', fetchMock)
    const { DataPipelineApiError, uploadLocationSummary } = await import('./api.ts')

    const error = await uploadLocationSummary(new File(['bad'], 'partial.zip')).catch((caught) => caught)

    expect(error).toBeInstanceOf(DataPipelineApiError)
    expect(error).toMatchObject({ status: 400, message: '전국 월전체분이 아닙니다.' })
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

    const error = await startDataPipeline('COMPLEX_COLLECTION').catch((caught) => caught)

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

function execution(
  type: 'COMPLEX_COLLECTION' | 'COMPLEX_REFINEMENT' | 'ANNOUNCEMENT_COLLECTION',
  status: string,
) {
  return {
    executionId: '01991a11-65d2-7000-8000-000000000001',
    type,
    status,
    currentStepName: null,
    currentStepIndex: 0,
    totalStepCount: type === 'ANNOUNCEMENT_COLLECTION' ? 3 : 2,
    completedSteps: [],
    skippedSteps: [],
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

function locationSummaryReport() {
  return {
    sourceFileName: 'summary.zip',
    textFileCount: 16,
    scannedRowCount: 6_422_078,
    targetRoadAddressCount: 3_500,
    matchedRoadAddressCount: 3_420,
    unmatchedRoadAddressCount: 80,
    storedLocationCount: 3_600,
    replacedRowCount: 0,
    invalidatedMappingCandidateCount: 0,
    provinceCodes: ['11', '26'],
  }
}
