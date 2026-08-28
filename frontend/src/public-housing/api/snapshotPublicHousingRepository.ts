import type {
  MapBounds,
  RawAnnouncementDetail,
  RawAnnouncementListItem,
  RawComplexDetail,
  RawComplexListItem,
  RawMapComplex,
} from '../model/publicHousing.ts'
import {
  decodeAnnouncementDetailEnvelope,
  decodeAnnouncementPageEnvelope,
  decodeComplexDetailEnvelope,
  decodeComplexPageEnvelope,
  decodeMapComplexEnvelope,
  PublicHousingContractError,
} from './publicHousingContract.ts'
import {
  createHttpPublicHousingRepository,
  type PublicHousingRepository,
} from './publicHousingRepository.ts'

const SNAPSHOT_ORIGIN = 'http://local-public-housing-snapshot.test'
const COMPLEX_CURSOR_PREFIX = 'snapshot-complex:'
const ANNOUNCEMENT_CURSOR_PREFIX = 'snapshot-announcement:'

export interface PublicHousingSnapshotV1 {
  readonly version: 1
  readonly complexListItems: readonly RawComplexListItem[]
  readonly mapComplexItems: readonly RawMapComplex[]
  readonly complexDetails: readonly RawComplexDetail[]
  readonly announcementListItems: readonly RawAnnouncementListItem[]
  readonly announcementDetails: readonly RawAnnouncementDetail[]
}

type SnapshotSource =
  | PublicHousingSnapshotV1
  | (() => Promise<unknown>)

interface SnapshotPage<T> {
  readonly items: readonly T[]
  readonly nextCursor: string | null
  readonly hasNext: boolean
}

interface CursorResult {
  readonly valid: boolean
  readonly offset: number
}

export function createSnapshotPublicHousingRepository(
  source: SnapshotSource,
): PublicHousingRepository {
  const loadSnapshot = createSnapshotLoader(source)
  const fetcher = createSnapshotFetcher(loadSnapshot)
  return createHttpPublicHousingRepository({ apiBaseUrl: '', fetcher })
}

function createSnapshotLoader(source: SnapshotSource) {
  let snapshotPromise: Promise<PublicHousingSnapshotV1> | null = null

  return () => {
    if (snapshotPromise !== null) {
      return snapshotPromise
    }

    snapshotPromise = Promise.resolve()
      .then(() => typeof source === 'function' ? source() : source)
      .then(decodePublicHousingSnapshot)
      .catch((error: unknown) => {
        snapshotPromise = null
        throw error
      })
    return snapshotPromise
  }
}

function createSnapshotFetcher(
  loadSnapshot: () => Promise<PublicHousingSnapshotV1>,
): typeof globalThis.fetch {
  return async (input, init) => {
    const signal = requestSignal(input, init)
    throwIfAborted(signal)
    const snapshot = await waitForSnapshot(loadSnapshot(), signal)
    throwIfAborted(signal)
    return routeSnapshotRequest(snapshot, requestUrl(input))
  }
}

function routeSnapshotRequest(
  snapshot: PublicHousingSnapshotV1,
  url: URL,
): Response {
  if (url.pathname === '/api/v1/complexes/map') {
    return mapResponse(snapshot, url)
  }
  if (url.pathname === '/api/v1/complexes') {
    return complexPageResponse(snapshot, url)
  }
  if (url.pathname === '/api/v1/announcements') {
    return announcementPageResponse(snapshot, url)
  }

  const complexId = pathId(url.pathname, '/api/v1/complexes/')
  if (complexId !== null) {
    return complexDetailResponse(snapshot, complexId)
  }
  const announcementId = pathId(url.pathname, '/api/v1/announcements/')
  if (announcementId !== null) {
    return announcementDetailResponse(snapshot, announcementId)
  }
  return errorResponse(404, 'NOT_FOUND', '로컬 snapshot 경로를 찾을 수 없습니다.')
}

function mapResponse(snapshot: PublicHousingSnapshotV1, url: URL): Response {
  const bounds = boundsFrom(url)
  if (bounds === null) {
    return invalidBoundsResponse()
  }

  const items = snapshot.mapComplexItems.filter((item) =>
    isInsideBounds(item, bounds),
  )
  return successResponse({ items })
}

function complexPageResponse(
  snapshot: PublicHousingSnapshotV1,
  url: URL,
): Response {
  const bounds = boundsFrom(url)
  if (bounds === null) {
    return invalidBoundsResponse()
  }
  const cursor = cursorOffset(
    url.searchParams.get('cursor'),
    COMPLEX_CURSOR_PREFIX,
  )
  if (!cursor.valid) {
    return invalidCursorResponse()
  }

  const visibleIds = new Set(
    snapshot.mapComplexItems
      .filter((item) => isInsideBounds(item, bounds))
      .map((item) => item.complexId),
  )
  const items = snapshot.complexListItems.filter((item) =>
    visibleIds.has(item.complexId),
  )
  return successResponse(page(
    items,
    cursor.offset,
    pageSize(url),
    COMPLEX_CURSOR_PREFIX,
  ))
}

function announcementPageResponse(
  snapshot: PublicHousingSnapshotV1,
  url: URL,
): Response {
  const cursor = cursorOffset(
    url.searchParams.get('cursor'),
    ANNOUNCEMENT_CURSOR_PREFIX,
  )
  if (!cursor.valid) {
    return invalidCursorResponse()
  }

  return successResponse(page(
    snapshot.announcementListItems,
    cursor.offset,
    pageSize(url),
    ANNOUNCEMENT_CURSOR_PREFIX,
  ))
}

function complexDetailResponse(
  snapshot: PublicHousingSnapshotV1,
  complexId: string,
): Response {
  const detail = snapshot.complexDetails.find(
    (candidate) => String(candidate.complexId) === complexId,
  )
  if (detail === undefined) {
    return errorResponse(
      404,
      'COMPLEX_NOT_FOUND',
      '주택 단지를 찾을 수 없습니다.',
    )
  }
  return successResponse(detail)
}

function announcementDetailResponse(
  snapshot: PublicHousingSnapshotV1,
  announcementId: string,
): Response {
  const detail = snapshot.announcementDetails.find(
    (candidate) => String(candidate.announcementId) === announcementId,
  )
  if (detail === undefined) {
    return errorResponse(
      404,
      'ANNOUNCEMENT_NOT_FOUND',
      '모집 공고를 찾을 수 없습니다.',
    )
  }
  return successResponse(detail)
}

function page<T>(
  allItems: readonly T[],
  offset: number,
  size: number,
  cursorPrefix: string,
): SnapshotPage<T> {
  const items = allItems.slice(offset, offset + size)
  const nextOffset = offset + items.length
  const hasNext = nextOffset < allItems.length
  return {
    items,
    nextCursor: hasNext ? `${cursorPrefix}${nextOffset}` : null,
    hasNext,
  }
}

function cursorOffset(value: string | null, prefix: string): CursorResult {
  if (value === null) {
    return { valid: true, offset: 0 }
  }
  if (!value.startsWith(prefix)) {
    return { valid: false, offset: 0 }
  }

  const offsetText = value.slice(prefix.length)
  if (!/^(0|[1-9]\d*)$/.test(offsetText)) {
    return { valid: false, offset: 0 }
  }
  const offset = Number(offsetText)
  if (!Number.isSafeInteger(offset)) {
    return { valid: false, offset: 0 }
  }
  return { valid: true, offset }
}

function pageSize(url: URL): number {
  return Number(url.searchParams.get('size'))
}

function boundsFrom(url: URL): MapBounds | null {
  const bounds = {
    southWestLat: Number(url.searchParams.get('southWestLat')),
    southWestLng: Number(url.searchParams.get('southWestLng')),
    northEastLat: Number(url.searchParams.get('northEastLat')),
    northEastLng: Number(url.searchParams.get('northEastLng')),
  }
  if (!isValidBounds(bounds)) {
    return null
  }
  return bounds
}

function isValidBounds(bounds: MapBounds): boolean {
  return (
    Number.isFinite(bounds.southWestLat)
    && Number.isFinite(bounds.southWestLng)
    && Number.isFinite(bounds.northEastLat)
    && Number.isFinite(bounds.northEastLng)
    && bounds.southWestLat >= -90
    && bounds.northEastLat <= 90
    && bounds.southWestLng >= -180
    && bounds.northEastLng <= 180
    && bounds.southWestLat < bounds.northEastLat
    && bounds.southWestLng < bounds.northEastLng
  )
}

function isInsideBounds(item: RawMapComplex, bounds: MapBounds): boolean {
  return (
    item.latitude >= bounds.southWestLat
    && item.latitude <= bounds.northEastLat
    && item.longitude >= bounds.southWestLng
    && item.longitude <= bounds.northEastLng
  )
}

export function decodePublicHousingSnapshot(
  value: unknown,
): PublicHousingSnapshotV1 {
  const snapshot = recordAt(value, '$')
  if (snapshot.version !== 1) {
    throw new PublicHousingContractError('$.version')
  }

  return {
    version: 1,
    complexListItems: decodeComplexPageEnvelope({
      data: {
        items: arrayField(snapshot, 'complexListItems'),
        nextCursor: null,
        hasNext: false,
      },
    }).items,
    mapComplexItems: decodeMapComplexEnvelope({
      data: { items: arrayField(snapshot, 'mapComplexItems') },
    }).items,
    complexDetails: arrayField(snapshot, 'complexDetails').map((detail) =>
      decodeComplexDetailEnvelope({ data: detail }),
    ),
    announcementListItems: decodeAnnouncementPageEnvelope({
      data: {
        items: arrayField(snapshot, 'announcementListItems'),
        nextCursor: null,
        hasNext: false,
      },
    }).items,
    announcementDetails: arrayField(snapshot, 'announcementDetails').map(
      (detail) => decodeAnnouncementDetailEnvelope({ data: detail }),
    ),
  }
}

function recordAt(value: unknown, path: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new PublicHousingContractError(path)
  }
  return value as Record<string, unknown>
}

function arrayField(
  record: Record<string, unknown>,
  field: string,
): readonly unknown[] {
  const value = record[field]
  if (!Object.hasOwn(record, field) || !Array.isArray(value)) {
    throw new PublicHousingContractError(`$.${field}`)
  }
  return value
}

function requestSignal(
  input: RequestInfo | URL,
  init: RequestInit | undefined,
): AbortSignal | null {
  if (init?.signal) {
    return init.signal
  }
  if (input instanceof Request) {
    return input.signal
  }
  return null
}

function requestUrl(input: RequestInfo | URL): URL {
  if (input instanceof Request) {
    return new URL(input.url)
  }
  return new URL(input.toString(), SNAPSHOT_ORIGIN)
}

function pathId(pathname: string, prefix: string): string | null {
  if (!pathname.startsWith(prefix)) {
    return null
  }
  const id = pathname.slice(prefix.length)
  return id.length > 0 && !id.includes('/') ? id : null
}

async function waitForSnapshot<T>(
  promise: Promise<T>,
  signal: AbortSignal | null,
): Promise<T> {
  throwIfAborted(signal)
  if (signal === null) {
    return promise
  }

  return new Promise<T>((resolve, reject) => {
    const handleAbort = () => reject(abortReason(signal))
    signal.addEventListener('abort', handleAbort, { once: true })
    promise.then(resolve, reject).finally(() => {
      signal.removeEventListener('abort', handleAbort)
    })
  })
}

function throwIfAborted(signal: AbortSignal | null) {
  signal?.throwIfAborted()
}

function abortReason(signal: AbortSignal): unknown {
  return signal.reason ?? new DOMException('요청이 취소되었습니다.', 'AbortError')
}

function successResponse(data: unknown): Response {
  return jsonResponse({ data }, 200)
}

function invalidBoundsResponse(): Response {
  return errorResponse(400, 'INVALID_BOUNDS', '지도 범위를 확인해 주세요.')
}

function invalidCursorResponse(): Response {
  return errorResponse(400, 'INVALID_CURSOR', '커서 값을 확인해 주세요.')
}

function errorResponse(status: number, code: string, message: string): Response {
  return jsonResponse({
    code,
    message,
    traceId: 'local-public-housing-snapshot',
  }, status)
}

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
