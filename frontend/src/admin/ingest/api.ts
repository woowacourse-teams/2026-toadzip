export type DataPipelineType = 'COLLECTION' | 'REFINEMENT'

export type DataPipelineExecutionStatus = 'IDLE' | 'RUNNING' | 'COMPLETED' | 'FAILED'

export type DataPipelineFailure = {
  stepName: string | null
  message: string
  serverResponse: unknown
}

export type DataPipelineExecution = {
  executionId: string | null
  type: DataPipelineType
  status: DataPipelineExecutionStatus
  currentStepName: string | null
  currentStepIndex: number
  totalStepCount: number
  completedSteps: readonly string[]
  failure: DataPipelineFailure | null
}

type CsrfToken = {
  token: string
  headerName: string
}

const apiBaseUrl = resolveApiBaseUrl()

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

export async function startDataPipeline(
  type: DataPipelineType,
): Promise<DataPipelineExecution> {
  const csrfToken = await requestCsrfToken()
  const response = await fetch(`${apiBaseUrl}${pipelinePath(type)}`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      [csrfToken.headerName]: csrfToken.token,
    },
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

function isDataPipelineExecution(value: unknown): value is DataPipelineExecution {
  if (!isRecord(value) || !isDataPipelineType(value.type) || !isExecutionStatus(value.status)) {
    return false
  }
  if (!Array.isArray(value.completedSteps)
    || !value.completedSteps.every((step) => typeof step === 'string')) {
    return false
  }
  return (typeof value.executionId === 'string' || value.executionId === null)
    && (typeof value.currentStepName === 'string' || value.currentStepName === null)
    && typeof value.currentStepIndex === 'number'
    && typeof value.totalStepCount === 'number'
    && (value.failure === null || isPipelineFailure(value.failure))
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

function isDataPipelineType(value: unknown): value is DataPipelineType {
  return value === 'COLLECTION' || value === 'REFINEMENT'
}

function isExecutionStatus(value: unknown): value is DataPipelineExecutionStatus {
  return value === 'IDLE' || value === 'RUNNING' || value === 'COMPLETED' || value === 'FAILED'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function pipelinePath(type: DataPipelineType): string {
  if (type === 'COLLECTION') {
    return '/api/admin/ingest/pipelines/collection'
  }
  return '/api/admin/ingest/pipelines/refinement'
}

function resolveApiBaseUrl(): string {
  const configuredApiBaseUrl = import.meta.env.VITE_API_BASE_URL
  if (configuredApiBaseUrl) {
    return configuredApiBaseUrl
  }
  if (import.meta.env.DEV) {
    return 'http://localhost:8080'
  }
  return ''
}
