import { getApiBaseUrl } from '../../api/apiBaseUrl'
export type DataPipelineType =
  | 'COMPLEX_COLLECTION'
  | 'COMPLEX_REFINEMENT'
  | 'ANNOUNCEMENT_COLLECTION'
  | 'ANNOUNCEMENT_REFINEMENT'
  | 'COMPLEX_SYNC'
  | 'ANNOUNCEMENT_SYNC'

export type DataPipelineExecutionStatus =
  | 'IDLE'
  | 'RUNNING'
  | 'COMPLETED'
  | 'COMPLETED_WARNINGS'
  | 'COMPLETED_WITH_SKIPS'
  | 'FAILED'
  | 'STOPPED'

export type DataPipelineSkippedStep = {
  stepName: string
  reason: string
  serverResponse: unknown
}

export type DataPipelineWarningStep = {
  step: string
  stepName: string
  report: unknown
}

export type DataPipelineFailure = {
  stepName: string | null
  message: string
  serverResponse: unknown
}

export type DataPipelineWorkProgress = {
  label: string
  unit: string
  completedCount: number
  totalCount: number
  startedAt: string
  updatedAt: string
}

export type DataPipelineExecution = {
  executionId: string | null
  type: DataPipelineType
  status: DataPipelineExecutionStatus
  currentStepName: string | null
  currentStepIndex: number
  totalStepCount: number
  completedSteps: readonly string[]
  skippedSteps: readonly DataPipelineSkippedStep[]
  partiallyFailedSteps: readonly DataPipelineWarningStep[]
  failure: DataPipelineFailure | null
  startedAt?: string | null
  finishedAt?: string | null
  stopRequested?: boolean
  externalRequestCount?: number
  lastRequestDescription?: string | null
  lastProgressAt?: string | null
  workProgress?: DataPipelineWorkProgress | null
  completedStepResults?: readonly DataPipelineWarningStep[]
}

export type LhQualityCoverage = { total: number; fulfilled: number }
export type LhQualityCollectionCoverage = {
  totalRequests: number
  collectedRequests: number
  latestCollectedAt: string | null
}
export type LhAnnouncementQuality = {
  observedAt: string
  connection: {
    total: number
    complexLinked: number
    housingTypeLinked: number
    unlinkedReasons: Record<string, number>
  }
  amounts: LhQualityCoverage
  schedules: { total: number; reviewed: number; withApplicationSchedule: number }
  supplyCollection: LhQualityCollectionCoverage
  detailCollection: LhQualityCollectionCoverage
  unlinkedLhLeaseCatalogCount: number
  unlinkedLhCandidates: readonly { panId: string; sourceKey: string; changedAt: string }[]
  preservedSourceRequestCount: number
  preservedReasons: Record<string, number>
  preservedAmountTargetCount: number
  preservedAmountReasons: Record<string, number>
  heldRequests: readonly {
    requestDescription: string
    reason: string
    lastOccurredAt: string
    proposedFingerprint: string | null
  }[]
}

export async function getLhAnnouncementQuality(): Promise<LhAnnouncementQuality> {
  const response = await fetch(`${apiBaseUrl}/api/admin/ingest/quality/lh-announcements`, {
    credentials: 'include',
  })
  const body = await readJson(response)
  if (!response.ok) throw apiError(response.status, body)
  if (!isLhAnnouncementQuality(body)) throw new Error('LH 데이터 품질 응답 형식이 올바르지 않습니다.')
  return body
}

export async function applyVerifiedLhSupplyReplacement(pblancId: string, request: {
  requestDescription: string
  proposedFingerprint: string
  evidenceUrl: string
  reason: string
}): Promise<void> {
  const csrfToken = await requestCsrfToken()
  const response = await fetch(
    `${apiBaseUrl}/api/admin/ingest/lh/announcements/supplies/${encodeURIComponent(pblancId)}/verified-replacement`,
    {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json', [csrfToken.headerName]: csrfToken.token },
      body: JSON.stringify(request),
    },
  )
  const body = await readJson(response)
  if (!response.ok) throw apiError(response.status, body)
}

function isLhAnnouncementQuality(value: unknown): value is LhAnnouncementQuality {
  if (!isRecord(value) || !isRecord(value.connection) || !isRecord(value.amounts)
    || !isRecord(value.schedules) || !isRecord(value.supplyCollection)
    || !isRecord(value.detailCollection) || !Array.isArray(value.heldRequests)
    || !Array.isArray(value.unlinkedLhCandidates)) return false
  const count = (input: unknown) => typeof input === 'number' && Number.isSafeInteger(input) && input >= 0
  const counts = (input: unknown) => isRecord(input) && Object.values(input).every(count)
  const collectionCoverage = (input: Record<string, unknown>) => count(input.totalRequests)
    && count(input.collectedRequests)
    && (input.latestCollectedAt === null || typeof input.latestCollectedAt === 'string')
  return typeof value.observedAt === 'string'
    && count(value.connection.total) && count(value.connection.complexLinked)
    && count(value.connection.housingTypeLinked) && counts(value.connection.unlinkedReasons)
    && count(value.amounts.total) && count(value.amounts.fulfilled)
    && count(value.schedules.total) && count(value.schedules.reviewed)
    && count(value.schedules.withApplicationSchedule)
    && collectionCoverage(value.supplyCollection) && collectionCoverage(value.detailCollection)
    && count(value.unlinkedLhLeaseCatalogCount) && count(value.preservedSourceRequestCount)
    && value.unlinkedLhCandidates.every((candidate) => isRecord(candidate)
      && typeof candidate.panId === 'string' && typeof candidate.sourceKey === 'string'
      && typeof candidate.changedAt === 'string')
    && counts(value.preservedReasons) && count(value.preservedAmountTargetCount)
    && counts(value.preservedAmountReasons)
    && value.heldRequests.every((held) => isRecord(held)
      && typeof held.requestDescription === 'string' && typeof held.reason === 'string'
      && typeof held.lastOccurredAt === 'string'
      && (held.proposedFingerprint === null || typeof held.proposedFingerprint === 'string'))
}

export type LocationSummaryImportReport = {
  sourceFileName: string
  textFileCount: number
  scannedRowCount: number
  targetRoadAddressCount: number
  matchedRoadAddressCount: number
  unmatchedRoadAddressCount: number
  storedLocationCount: number
  replacedRowCount: number
  invalidatedMappingCandidateCount: number
  provinceCodes: readonly string[]
}

type CsrfToken = {
  token: string
  headerName: string
}

const apiBaseUrl = getApiBaseUrl()

export class DataPipelineApiError extends Error {
  readonly status: number
  readonly serverResponse: unknown

  constructor(status: number, message: string, serverResponse: unknown = null) {
    super(message)
    this.name = 'DataPipelineApiError'
    this.status = status
    this.serverResponse = serverResponse
  }
}

export type LhRefreshSource = 'supplies' | 'details'
export type ExternalDataCollectionReport = {
  operation: string
  storedRowCount: number
  failedRequestCount: number
  externalApiCallCount: number
  skippedRequestCount: number
  rateLimitedRequestCount: number
  successfulRequestCount: number
  selectionFailedRequestCount: number
}

export async function refreshLhAnnouncement(
  source: LhRefreshSource, pblancId: string,
): Promise<ExternalDataCollectionReport> {
  const csrfToken = await requestCsrfToken()
  const response = await fetch(
    `${apiBaseUrl}/api/admin/ingest/lh/announcements/${source}/${encodeURIComponent(pblancId)}/refresh`,
    { method: 'POST', credentials: 'include', headers: { [csrfToken.headerName]: csrfToken.token } },
  )
  const body = await readJson(response)
  if (!response.ok) throw apiError(response.status, body)
  if (!isExternalDataCollectionReport(body)) throw new Error('LH 재조회 응답 형식이 올바르지 않습니다.')
  return body
}

export function isExternalDataCollectionReport(value: unknown): value is ExternalDataCollectionReport {
  return isRecord(value) && typeof value.operation === 'string' && value.operation.trim().length > 0
    && ['storedRowCount', 'failedRequestCount', 'externalApiCallCount', 'skippedRequestCount',
      'rateLimitedRequestCount', 'successfulRequestCount', 'selectionFailedRequestCount'].every(
      (key) => typeof value[key] === 'number' && Number.isSafeInteger(value[key]) && value[key] >= 0,
    )
}

export async function startDataPipeline(
  type: DataPipelineType,
  serviceKey?: string,
): Promise<DataPipelineExecution> {
  const executionKey = serviceKey?.trim()
  if (executionKey === '') throw new Error('공공데이터포털 API 키를 입력해 주세요.')
  const csrfToken = await requestCsrfToken()
  const response = await fetch(`${apiBaseUrl}${pipelinePath(type)}`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      [csrfToken.headerName]: csrfToken.token,
      ...(executionKey === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    ...(executionKey === undefined ? {} : { body: JSON.stringify({ serviceKey: executionKey }) }),
  })
  return readExecutionResponse(response)
}

export async function getDataPipelineStatus(
  type: DataPipelineType,
): Promise<DataPipelineExecution> {
  const response = await fetch(`${apiBaseUrl}${pipelinePath(type)}`, {
    credentials: 'include',
  })
  return readExecutionResponse(response)
}

export async function uploadLocationSummary(
  file: File,
): Promise<LocationSummaryImportReport> {
  const csrfToken = await requestCsrfToken()
  const formData = new FormData()
  formData.append('file', file)
  const response = await fetch(`${apiBaseUrl}/api/admin/ingest/juso/location-summaries`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      [csrfToken.headerName]: csrfToken.token,
    },
    body: formData,
  })
  const body = await readJson(response)
  if (!response.ok) {
    throw apiError(response.status, body)
  }
  if (!isLocationSummaryImportReport(body)) {
    throw new Error('위치정보요약DB 적재 응답 형식이 올바르지 않습니다.')
  }
  return body
}

async function requestCsrfToken(): Promise<CsrfToken> {
  const response = await fetch(`${apiBaseUrl}/api/admin/auth/csrf`, {
    credentials: 'include',
  })
  const body = await readJson(response)
  if (!response.ok) {
    throw apiError(response.status, body)
  }
  if (!isCsrfToken(body)) {
    throw new Error('CSRF 토큰 응답 형식이 올바르지 않습니다.')
  }
  return body
}

async function readExecutionResponse(response: Response): Promise<DataPipelineExecution> {
  const body = await readJson(response)
  if (!response.ok) {
    throw apiError(response.status, body)
  }
  if (!isDataPipelineExecution(body)) {
    throw new Error('데이터 수집·정제 상태 응답 형식이 올바르지 않습니다.')
  }
  return body
}

function apiError(status: number, body: unknown): DataPipelineApiError {
  const message = isRecord(body) && typeof body.message === 'string'
    ? body.message
    : '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  return new DataPipelineApiError(status, message, body)
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json() as unknown
  } catch {
    return null
  }
}

function isWorkProgress(value: unknown): value is DataPipelineWorkProgress {
  return isRecord(value)
    && typeof value.label === 'string' && typeof value.unit === 'string'
    && typeof value.completedCount === 'number' && Number.isSafeInteger(value.completedCount) && value.completedCount >= 0
    && typeof value.totalCount === 'number' && Number.isSafeInteger(value.totalCount) && value.totalCount >= -1
    && typeof value.startedAt === 'string' && Number.isFinite(Date.parse(value.startedAt))
    && typeof value.updatedAt === 'string' && Number.isFinite(Date.parse(value.updatedAt))
}

function isDataPipelineExecution(value: unknown): value is DataPipelineExecution {
  if (!isRecord(value) || !isDataPipelineType(value.type) || !isExecutionStatus(value.status)) {
    return false
  }
  if (!Array.isArray(value.completedSteps)
    || !value.completedSteps.every((step) => typeof step === 'string')) {
    return false
  }
  if (!Array.isArray(value.skippedSteps)
    || !value.skippedSteps.every(isPipelineSkippedStep)) {
    return false
  }
  if (!Array.isArray(value.partiallyFailedSteps)
    || !value.partiallyFailedSteps.every(isPipelineWarningStep)) {
    return false
  }
  return (typeof value.executionId === 'string' || value.executionId === null)
    && (typeof value.currentStepName === 'string' || value.currentStepName === null)
    && typeof value.currentStepIndex === 'number'
    && typeof value.totalStepCount === 'number'
    && (value.failure === null || isPipelineFailure(value.failure))
    && (value.stopRequested === undefined || typeof value.stopRequested === 'boolean')
    && (value.workProgress == null || isWorkProgress(value.workProgress))
    && (value.externalRequestCount === undefined
      || (typeof value.externalRequestCount === 'number' && value.externalRequestCount >= 0))
    && ['startedAt', 'finishedAt', 'lastProgressAt', 'lastRequestDescription'].every(
      (key) => value[key] === undefined || value[key] === null || typeof value[key] === 'string',
    )
    && (value.completedStepResults === undefined || (Array.isArray(value.completedStepResults)
      && value.completedStepResults.every(isPipelineWarningStep)))
}

function isPipelineSkippedStep(value: unknown): value is DataPipelineSkippedStep {
  return isRecord(value)
    && typeof value.stepName === 'string'
    && typeof value.reason === 'string'
    && 'serverResponse' in value
}

function isPipelineWarningStep(value: unknown): value is DataPipelineWarningStep {
  return isRecord(value)
    && typeof value.step === 'string'
    && typeof value.stepName === 'string'
    && 'report' in value
}

function isPipelineFailure(value: unknown): value is DataPipelineFailure {
  return isRecord(value)
    && (typeof value.stepName === 'string' || value.stepName === null)
    && typeof value.message === 'string'
    && 'serverResponse' in value
}

function isCsrfToken(value: unknown): value is CsrfToken {
  return isRecord(value)
    && typeof value.token === 'string'
    && typeof value.headerName === 'string'
}

function isLocationSummaryImportReport(value: unknown): value is LocationSummaryImportReport {
  if (!isRecord(value) || !Array.isArray(value.provinceCodes)) {
    return false
  }
  return typeof value.sourceFileName === 'string'
    && typeof value.textFileCount === 'number'
    && typeof value.scannedRowCount === 'number'
    && typeof value.targetRoadAddressCount === 'number'
    && typeof value.matchedRoadAddressCount === 'number'
    && typeof value.unmatchedRoadAddressCount === 'number'
    && typeof value.storedLocationCount === 'number'
    && typeof value.replacedRowCount === 'number'
    && typeof value.invalidatedMappingCandidateCount === 'number'
    && value.provinceCodes.every((provinceCode) => typeof provinceCode === 'string')
}

function isDataPipelineType(value: unknown): value is DataPipelineType {
  return value === 'COMPLEX_COLLECTION'
    || value === 'COMPLEX_REFINEMENT'
    || value === 'ANNOUNCEMENT_COLLECTION'
    || value === 'ANNOUNCEMENT_REFINEMENT'
    || value === 'COMPLEX_SYNC'
    || value === 'ANNOUNCEMENT_SYNC'
}

function isExecutionStatus(value: unknown): value is DataPipelineExecutionStatus {
  return value === 'IDLE'
    || value === 'RUNNING'
    || value === 'COMPLETED'
    || value === 'COMPLETED_WARNINGS'
    || value === 'COMPLETED_WITH_SKIPS'
    || value === 'FAILED'
    || value === 'STOPPED'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function pipelinePath(type: DataPipelineType): string {
  const paths: Record<DataPipelineType, string> = {
    COMPLEX_COLLECTION: 'complex-collection',
    COMPLEX_REFINEMENT: 'complex-refinement',
    ANNOUNCEMENT_COLLECTION: 'announcement-collection',
    ANNOUNCEMENT_REFINEMENT: 'announcement-refinement',
    COMPLEX_SYNC: 'complex-sync',
    ANNOUNCEMENT_SYNC: 'announcement-sync',
  }
  return `/api/admin/ingest/pipelines/${paths[type]}`
}


export async function stopDataPipeline(executionId: string): Promise<DataPipelineExecution> {
  const csrfToken = await requestCsrfToken()
  const response = await fetch(
    `${apiBaseUrl}/api/admin/ingest/pipelines/executions/${encodeURIComponent(executionId)}/stop`,
    {
      method: 'POST',
      credentials: 'include',
      headers: { [csrfToken.headerName]: csrfToken.token },
    },
  )
  return readExecutionResponse(response)
}

export const failureCategories = {
  collection: { label: '외부 API 수집', path: 'external-data-failures' },
  complex: { label: '단지 정제', path: 'myhome/complex-mappings/failures' },
  household: { label: 'LH 세대수 보강', path: 'lh/housing-type-households/failures' },
  announcement: { label: '공고 정제', path: 'myhome/announcement-mappings/failures' },
  enrichment: { label: 'LH 공고 보강', path: 'lh/announcement-enrichments/failures' },
} as const
export type FailureCategory = keyof typeof failureCategories
export type IngestFailure = {
  target: string
  sourceKey: string
  reason: string
  detail: string
  status: string
  occurredAt: string
  occurrenceCount: number
  executionId: string | null
  source: string | null
  raw: Record<string, unknown>
}

export async function getIngestFailures(
  category: FailureCategory, history: boolean, page: number,
): Promise<readonly IngestFailure[]> {
  const needsPageSuffix = !history && category !== 'collection' && category !== 'household'
  const suffix = history ? '/history' : needsPageSuffix ? '/page' : ''
  const response = await fetch(
    `${apiBaseUrl}/api/admin/ingest/${failureCategories[category].path}${suffix}?page=${page}&size=20`,
    { credentials: 'include' },
  )
  const body = await readJson(response)
  if (!response.ok) throw apiError(response.status, body)
  if (!Array.isArray(body)) throw new Error('실패 목록 응답 형식이 올바르지 않습니다.')
  return body.map(parseIngestFailure)
}

function parseIngestFailure(value: unknown): IngestFailure {
  if (!isRecord(value) || typeof value.reason !== 'string'
    || typeof value.status !== 'string' || typeof value.occurredAt !== 'string'
    || (typeof value.sourceKey !== 'string' && typeof value.requestDescription !== 'string')) {
    throw new Error('실패 항목 응답 형식이 올바르지 않습니다.')
  }
  const text = (key: string) => typeof value[key] === 'string' ? value[key] : null
  const target = text('complexName') ?? text('sourceComplexIdentifier')
    ?? text('sourceAnnouncementIdentifier') ?? text('requestDescription') ?? text('sourceKey') ?? '식별자 없음'
  const serial = typeof value.sourceHouseSerialNumber === 'number'
    ? ` · 공급행 ${value.sourceHouseSerialNumber}` : ''
  return {
    target: target + serial,
    sourceKey: text('sourceKey') ?? text('requestDescription') ?? '',
    reason: text('errorType') ?? value.reason,
    detail: text('detail') ?? value.reason,
    status: value.status,
    occurredAt: text('lastOccurredAt') ?? value.occurredAt,
    occurrenceCount: typeof value.occurrenceCount === 'number' ? value.occurrenceCount : 1,
    executionId: text('lastExecutionId'),
    source: text('source'),
    raw: value,
  }
}

export async function getPipelineHistory(page: number): Promise<DataPipelineExecution[]> {
  const response = await fetch(`${apiBaseUrl}/api/admin/ingest/pipelines/history?page=${page}&size=20`, {credentials:'include'})
  const body = await readJson(response)
  if (!response.ok) throw apiError(response.status,body)
  if (!Array.isArray(body) || !body.every(isDataPipelineExecution)) throw new Error('실행 이력 응답 형식이 올바르지 않습니다.')
  return body
}
