import type { DataPipelineType } from './api'
import { afterEach, describe, expect, it, vi } from 'vitest'

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('관리자 데이터 수집·정제 API', () => {
  it('선택 재처리는 식별자 목록을 관리자 세션·CSRF와 함께 비동기로 접수한다', async () => {
    const result = { ...execution('COMPLEX_REGISTRATION', 'RUNNING'), executionId: 'retry-1' }
    const fetchMock = prepareFetch(result, 202)
    const { retryInitialRegistration } = await import('./api.ts')
    await expect(retryInitialRegistration(['1:HAPPY_HOUSING'])).resolves.toEqual(result)
    expect(fetchMock).toHaveBeenLastCalledWith('http://localhost:8080/api/admin/ingest/myhome/initial-registration/retry', {
      method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json', 'X-CUSTOM-CSRF': 'csrf-token' },
      body: JSON.stringify({ sourceComplexIdentifiers: ['1:HAPPY_HOUSING'] }),
    })
  })
  it('정보 보완에는 현재 원천 지문과 근거를 함께 전송한다', async () => {
    const fetchMock = prepareFetch(null, 204)
    const request = { sourceComplexIdentifier: '1:HAPPY_HOUSING', fingerprint: 'a'.repeat(64), complexName: null,
      housingTypeNames: { row: '26A' }, evidence: '공식 공고문 3쪽' }
    const { supplementRegistrationInformation } = await import('./api.ts')
    await supplementRegistrationInformation(request)
    expect(fetchMock).toHaveBeenLastCalledWith('http://localhost:8080/api/admin/ingest/myhome/initial-registration/information', {
      method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json', 'X-CUSTOM-CSRF': 'csrf-token' },
      body: JSON.stringify(request),
    })
  })
  it('불완전한 보완 정보와 실패 이력 응답을 성공으로 처리하지 않는다', async () => {
    const fetchMock = prepareFetch(null)
    fetchMock.mockReset().mockResolvedValue(jsonResponse({ fingerprint: 'a'.repeat(64), housingTypes: [] }))
    const { getRegistrationInformation, getRegistrationFailures } = await import('./api.ts')
    await expect(getRegistrationInformation('1:HAPPY_HOUSING')).rejects.toThrow('응답 형식')
    await expect(getRegistrationFailures('1:HAPPY_HOUSING', 0)).rejects.toThrow('응답 형식')
  })
  it('주소·좌표 보완은 원천 식별자와 새 주소·좌표를 CSRF와 함께 전달한다', async () => {
    const fetchMock = prepareFetch(null, 204)
    const { supplementInitialRegistrationLocation } = await import('./api.ts')
    await supplementInitialRegistrationLocation('1:HAPPY_HOUSING', '서울특별시 종로구 테스트로 1', 37.5, 127)
    expect(fetchMock).toHaveBeenLastCalledWith('http://localhost:8080/api/admin/ingest/myhome/initial-registration/locations', {
      method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json', 'X-CUSTOM-CSRF': 'csrf-token' },
      body: JSON.stringify({ sourceComplexIdentifier: '1:HAPPY_HOUSING', address: '서울특별시 종로구 테스트로 1', latitude: 37.5, longitude: 127 }),
    })
  })
  it('주소·좌표 실패 목록을 페이지와 세션으로 조회한다', async () => {
    const result = { counts: { READY: 1, INVALID_ADDRESS: 1, ADDRESS_NOT_FOUND: 0, COORDINATE_NOT_FOUND: 0, COORDINATE_CONVERSION_ERROR: 0 },
      targets: [], totalElements: 0, page: 1, totalPages: 2 }
    const fetchMock = prepareFetch(result)
    fetchMock.mockReset().mockResolvedValue(jsonResponse(result))
    const { getLocationPreparation } = await import('./api.ts')
    await expect(getLocationPreparation(1)).resolves.toEqual(result)
    expect(fetchMock).toHaveBeenLastCalledWith('http://localhost:8080/api/admin/ingest/myhome/initial-registration/locations?page=1&size=20', { credentials: 'include' })
  })
  it('주소·좌표 사유별 집계가 불완전하면 성공으로 처리하지 않는다', async () => {
    const fetchMock = prepareFetch(null)
    fetchMock.mockReset().mockResolvedValue(jsonResponse({ counts: { READY: 1 }, targets: [], totalElements: 0, page: 0, totalPages: 0 }))
    const { getLocationPreparation } = await import('./api.ts')
    await expect(getLocationPreparation(0)).rejects.toThrow('주소·좌표 준비 응답 형식')
  })
  it.each([10, 20, 50, 100])('최초 등록 상태 필터와 페이지를 %d건씩 세션으로 조회한다', async size => {
    const result = { counts: { EXISTING: 0, LOCATION_REQUIRED: 10, INFORMATION_REQUIRED: 0, READY: 0, FAILED: 0, COMPLETED: 90 },
      targets: [], totalElements: 10, page: 1, totalPages: 2 }
    const fetchMock = prepareFetch(result)
    fetchMock.mockReset().mockResolvedValue(jsonResponse(result))
    const { getInitialRegistration } = await import('./api.ts')
    await expect(getInitialRegistration('LOCATION_REQUIRED', 1, size)).resolves.toEqual(result)
    expect(fetchMock).toHaveBeenCalledWith(
      `http://localhost:8080/api/admin/ingest/myhome/initial-registration?page=1&size=${size}&status=LOCATION_REQUIRED`,
      { credentials: 'include' },
    )
  })
  it('최초 등록 상태 재확인은 CSRF를 전달하며 204 빈 응답을 허용한다', async () => {
    const fetchMock = prepareFetch(null, 204)
    const { refreshInitialRegistration } = await import('./api.ts')
    await expect(refreshInitialRegistration()).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenLastCalledWith(
      'http://localhost:8080/api/admin/ingest/myhome/initial-registration/refresh',
      { method: 'POST', credentials: 'include', headers: { 'X-CUSTOM-CSRF': 'csrf-token' } },
    )
  })
  it('불완전하거나 음수인 상태 집계는 성공으로 읽지 않는다', async () => {
    const fetchMock = prepareFetch(null)
    fetchMock.mockReset().mockResolvedValue(jsonResponse({ counts: { COMPLETED: -1 }, targets: [],
      totalElements: 0, page: 0, totalPages: 0 }))
    const { getInitialRegistration } = await import('./api.ts')
    await expect(getInitialRegistration('', 0, 20)).rejects.toThrow('단지 등록 현황 응답 형식')
  })
  it('실행 ID로 접수한 실행의 상태를 조회한다', async () => {
    const fetchMock = prepareFetch(execution('ANNOUNCEMENT_REGISTRATION', 'RUNNING'))
    fetchMock.mockReset().mockResolvedValue(jsonResponse(execution('ANNOUNCEMENT_REGISTRATION', 'RUNNING')))
    const { getDataPipelineExecution } = await import('./api.ts')
    await getDataPipelineExecution('run 1')
    expect(fetchMock).toHaveBeenLastCalledWith(
      'http://localhost:8080/api/admin/ingest/pipelines/executions/run%201', { credentials: 'include' },
    )
  })
  it.each(['supplies', 'details'] as const)('%s 강제 재조회는 공고 ID, 세션과 CSRF를 전달한다', async (source) => {
    const report = refreshReport()
    const fetchMock = prepareFetch(report)
    const { refreshLhAnnouncement } = await import('./api.ts')
    await expect(refreshLhAnnouncement(source, 'myhome 1')).resolves.toEqual(report)
    expect(fetchMock).toHaveBeenLastCalledWith(
      `http://localhost:8080/api/admin/ingest/lh/announcements/${source}/myhome%201/refresh`,
      { method: 'POST', credentials: 'include', headers: { 'X-CUSTOM-CSRF': 'csrf-token' } },
    )
  })

  it('강제 재조회 부분 실패 보고서를 오류 응답에 보존한다', async () => {
    const report = { ...refreshReport(), failedRequestCount: 1 }
    prepareFetch(report, 502)
    const { refreshLhAnnouncement, DataPipelineApiError } = await import('./api.ts')
    const error = await refreshLhAnnouncement('supplies', 'myhome-1').catch((cause) => cause)
    expect(error).toBeInstanceOf(DataPipelineApiError)
    expect(error).toMatchObject({ status: 502, serverResponse: report })
  })

  it('강제 재조회 결과의 잘못된 수치를 성공으로 반환하지 않는다', async () => {
    prepareFetch({ ...refreshReport(), externalApiCallCount: -1 })
    const { refreshLhAnnouncement } = await import('./api.ts')
    await expect(refreshLhAnnouncement('details', 'myhome-1')).rejects.toThrow('재조회 응답 형식')
  })
  it.each([
    ['COMPLEX_SYNC', 'complex-sync'], ['ANNOUNCEMENT_SYNC', 'announcement-sync'],
    ['SH_ANNOUNCEMENT_COLLECTION', 'sh-announcement-collection'],
    ['GH_ANNOUNCEMENT_COLLECTION', 'gh-announcement-collection'],
  ] as const)('%s 실행은 기존 세션과 CSRF 계약으로 시작한다', async (type, path) => {
    const fetchMock = prepareFetch(execution(type, 'RUNNING'))
    const { startDataPipeline } = await import('./api.ts')
    await expect(startDataPipeline(type)).resolves.toMatchObject({ type, status: 'RUNNING' })
    expect(fetchMock).toHaveBeenLastCalledWith(`http://localhost:8080/api/admin/ingest/pipelines/${path}`, {
      method: 'POST', credentials: 'include', headers: { 'X-CUSTOM-CSRF': 'csrf-token' },
    })
  })

  it('중지는 실행 ID와 CSRF 헤더를 포함하고 서버의 대기 상태를 보존한다', async () => {
    const fetchMock = prepareFetch({ ...execution('COMPLEX_COLLECTION', 'RUNNING'), stopRequested: true })
    const { stopDataPipeline } = await import('./api.ts')
    await expect(stopDataPipeline('run-1')).resolves.toMatchObject({ status: 'RUNNING', stopRequested: true })
    expect(fetchMock).toHaveBeenLastCalledWith(
      'http://localhost:8080/api/admin/ingest/pipelines/executions/run-1/stop',
      { method: 'POST', credentials: 'include', headers: { 'X-CUSTOM-CSRF': 'csrf-token' } },
    )
  })

  it('실패 목록은 페이징 경로로 요청하고 잘못된 행 응답을 거절한다', async () => {
    vi.stubEnv('DEV', true)
    vi.stubEnv('VITE_API_BASE_URL', '')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([{ status: 'PENDING' }]))
    vi.stubGlobal('fetch', fetchMock)
    const { getIngestFailures } = await import('./api.ts')
    await expect(getIngestFailures('complex', false, 2)).rejects.toThrow('실패 항목 응답 형식')
    expect(fetchMock).toHaveBeenLastCalledWith(
      'http://localhost:8080/api/admin/ingest/myhome/complex-mappings/failures/page?page=2&size=20',
      { credentials: 'include' },
    )
  })

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

  it.each([
    ['COMPLEX_COLLECTION', 'complex-collection'], ['ANNOUNCEMENT_COLLECTION', 'announcement-collection'],
    ['ANNOUNCEMENT_SYNC', 'announcement-sync'],
  ] as const)('%s 수집은 키를 보내지 않고 본문 없이 시작한다', async (type, path) => {
    const fetchMock = prepareFetch(execution(type, 'RUNNING'))
    const { startDataPipeline } = await import('./api.ts')
    await startDataPipeline(type)
    expect(fetchMock).toHaveBeenLastCalledWith(
      `http://localhost:8080/api/admin/ingest/pipelines/${path}`,
      { method: 'POST', credentials: 'include', headers: { 'X-CUSTOM-CSRF': 'csrf-token' } },
    )
  })

  it.each([true, false])('서버 수집 키 설정 여부(%s)를 읽는다', async (configured) => {
    vi.stubEnv('DEV', true)
    vi.stubEnv('VITE_API_BASE_URL', '')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ serviceKeyConfigured: configured }))
    vi.stubGlobal('fetch', fetchMock)
    const { getDataPipelineReadiness } = await import('./api.ts')

    await expect(getDataPipelineReadiness()).resolves.toEqual({ serviceKeyConfigured: configured })
    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/admin/ingest/pipelines/readiness', { credentials: 'include' },
    )
  })

  it('준비 상태 응답에 설정 여부가 없으면 형식 오류로 본다', async () => {
    vi.stubEnv('DEV', true)
    vi.stubEnv('VITE_API_BASE_URL', '')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ serviceKeyConfigured: 'yes' })))
    const { getDataPipelineReadiness } = await import('./api.ts')

    await expect(getDataPipelineReadiness()).rejects.toThrow('수집 준비 상태 응답 형식이 올바르지 않습니다.')
  })

  it('행별 누락을 담은 완료·주의 응답을 읽는다', async () => {
    const report = {
      ...execution('COMPLEX_REFINEMENT', 'COMPLETED_WARNINGS'),
      partiallyFailedSteps: [{
        step: 'MAP_MYHOME_COMPLEXES',
        stepName: '마이홈 단지 정제',
        report: { failedSourceRowCount: 3 },
      }],
    }
    const fetchMock = prepareFetch(report)
    const { startDataPipeline } = await import('./api.ts')

    await expect(startDataPipeline('COMPLEX_REFINEMENT')).resolves.toMatchObject(report)
    expect(fetchMock).toHaveBeenLastCalledWith(
      'http://localhost:8080/api/admin/ingest/pipelines/complex-refinement',
      { method: 'POST', credentials: 'include', headers: { 'X-CUSTOM-CSRF': 'csrf-token' } },
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

  it('확인된 LH 공급 정정에는 CSRF와 정확한 요청 지문을 보낸다', async () => {
    const fetchMock = prepareFetch({ operation: 'lh-announcement-supply', failedRequestCount: 0 })
    const { applyVerifiedLhSupplyReplacement } = await import('./api.ts')
    const request = {
      requestDescription: 'PAN_ID=pan-1', proposedFingerprint: 'a'.repeat(64),
      evidenceUrl: 'https://apply.lh.or.kr/notice', reason: '철회 확인',
    }

    await applyVerifiedLhSupplyReplacement('myhome-1', request)

    expect(fetchMock).toHaveBeenLastCalledWith(
      'http://localhost:8080/api/admin/ingest/lh/announcements/supplies/myhome-1/verified-replacement',
      {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-CUSTOM-CSRF': 'csrf-token' },
        body: JSON.stringify(request),
      },
    )
  })
})

it('준비된 신규 단지 등록은 CSRF를 보내고 즉시 실행 접수를 읽는다', async () => {
  const data = { executionId: 'async-1', type: 'COMPLEX_REGISTRATION', status: 'RUNNING', currentStepName: null,
    currentStepIndex: 0, totalStepCount: 1, completedSteps: [], skippedSteps: [], partiallyFailedSteps: [], failure: null }
  const fetchMock = prepareFetch(data, 202)
  const { registerReadyComplexes } = await import('./api.ts')
  await expect(registerReadyComplexes()).resolves.toEqual(data)
  expect(fetchMock).toHaveBeenLastCalledWith('http://localhost:8080/api/admin/ingest/myhome/initial-registration/register', {
    method: 'POST', credentials: 'include', headers: { 'X-CUSTOM-CSRF': 'csrf-token' },
  })
})

it('실행 접수 정보가 빠지면 성공으로 표시하지 않는다', async () => {
  prepareFetch({ counts: { COMPLETED: 2 }, targetCount: 2, uniqueAddressCount: 1 }, 202)
  const { registerReadyComplexes } = await import('./api.ts')
  await expect(registerReadyComplexes()).rejects.toThrow()
})

function refreshReport() {
  return { operation: 'lh-announcement-supply', storedRowCount: 2, failedRequestCount: 0,
    externalApiCallCount: 1, skippedRequestCount: 0, rateLimitedRequestCount: 0,
    successfulRequestCount: 1, selectionFailedRequestCount: 0 }
}

function prepareFetch(data: unknown, status = 202) {
  vi.stubEnv('DEV', true)
  vi.stubEnv('VITE_API_BASE_URL', '')
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(
      jsonResponse({ token: 'csrf-token', headerName: 'X-CUSTOM-CSRF' }),
    )
    .mockResolvedValueOnce(jsonResponse(data, status))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function execution(
  type: DataPipelineType,
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
    partiallyFailedSteps: [],
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
    preparedRoadAddressCount: 3_300,
    provinceCodes: ['11', '26'],
  }
}
