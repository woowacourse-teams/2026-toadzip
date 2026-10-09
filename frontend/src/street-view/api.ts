import { getApiBaseUrl } from '../api/apiBaseUrl'
import { decodeHttpErrorBody } from '../public-housing/api/httpErrorBody'
import { isRecord, isStreetViewInitialization, type StreetViewConfiguration } from './types'

const CONFIGURATION_TIMEOUT_MS = 10_000

export class StreetViewApiError extends Error {
  readonly status: number | null
  readonly code: string | null
  readonly traceId: string | null

  constructor(message: string, status: number | null = null, code: string | null = null, traceId: string | null = null) {
    super(message)
    this.name = 'StreetViewApiError'
    this.status = status
    this.code = code
    this.traceId = traceId
  }
}

export async function getStreetViewConfiguration(
  complexId: number,
  signal?: AbortSignal,
): Promise<StreetViewConfiguration> {
  if (!Number.isSafeInteger(complexId) || complexId <= 0) {
    throw new StreetViewApiError('단지 정보를 확인할 수 없습니다.')
  }
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(new DOMException('Configuration timed out', 'TimeoutError')), CONFIGURATION_TIMEOUT_MS)
  const boundedSignal = signal ? AbortSignal.any([signal, controller.signal]) : controller.signal
  try {
    boundedSignal.throwIfAborted()
    const response = await fetch(`${getApiBaseUrl()}/api/v1/complexes/${complexId}/street-view`, {
      credentials: 'include', cache: 'no-store', signal: boundedSignal,
    })
    if (!response.ok) {
      const error = await decodeHttpErrorBody(response)
      throw new StreetViewApiError('거리뷰 이용 정보를 불러오지 못했습니다. 다시 확인해 주세요.', response.status, error.code, error.traceId)
    }
    const body: unknown = await response.json()
    boundedSignal.throwIfAborted()
    if (!isRecord(body) || !isConfiguration(body.data)) {
      throw new StreetViewApiError('거리뷰 이용 정보를 확인할 수 없습니다. 다시 확인해 주세요.')
    }
    return body.data
  } catch (error) {
    signal?.throwIfAborted()
    if (controller.signal.aborted) {
      throw new StreetViewApiError('거리뷰 이용 정보 확인 시간이 초과되었습니다. 다시 확인해 주세요.')
    }
    if (error instanceof StreetViewApiError) throw error
    throw new StreetViewApiError('거리뷰 이용 정보를 불러오지 못했습니다. 다시 확인해 주세요.')
  } finally {
    window.clearTimeout(timeout)
  }
}

function isConfiguration(value: unknown): value is StreetViewConfiguration {
  if (!isRecord(value) || !Number.isSafeInteger(value.complexId) || typeof value.complexId !== 'number' || value.complexId <= 0
    || value.provider !== 'NAVER' || !Number.isSafeInteger(value.policyRevision)
    || typeof value.policyRevision !== 'number' || value.policyRevision < 0) return false
  if (value.enabled === true) {
    return value.disabledReason === null && isStreetViewInitialization(value.initialization)
  }
  return value.enabled === false && value.initialization === null
    && (value.disabledReason === 'POLICY_DISABLED' || value.disabledReason === 'INVALID_COORDINATES')
}
