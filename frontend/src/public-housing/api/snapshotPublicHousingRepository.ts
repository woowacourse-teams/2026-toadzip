import type {
  MapBounds,
  RawAnnouncementDetail,
  RawAnnouncementListItem,
  RawComplexDetail,
  RawComplexListItem,
  RawMapComplex,
} from '../model/publicHousing.ts'
import { provinceNameForRegionCode } from '../model/publicHousingRegion.ts'
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
  readonly complexRegionCodes: Readonly<Record<string, string>>
  readonly announcementRegionCodes: Readonly<
    Record<string, readonly string[]>
  >
  readonly regionCodeDescendants: Readonly<
    Record<string, readonly string[]>
  >
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

  const matchingIds = complexIdsMatchingFilters(snapshot, url)
  const filterRequested = hasComplexSearchFilters(url)
  const items = snapshot.mapComplexItems.filter((item) => (
    isInsideBounds(item, bounds)
    && (!filterRequested || matchingIds.has(item.complexId))
  ))
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
  const items = snapshot.complexListItems.filter((item) => (
    visibleIds.has(item.complexId)
    && complexMatchesFilters(snapshot, item, url)
  ))
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

  const items = snapshot.announcementListItems.filter((item) => (
    announcementMatchesFilters(snapshot, item, url)
  ))
  return successResponse(page(
    items,
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

const COMPLEX_SEARCH_FILTER_KEYS = [
  'regionCode',
  'rentalTypes',
  'applicationStatuses',
  'agencyCodes',
  'recruitmentTypes',
  'minDeposit',
  'maxDeposit',
  'minMonthlyRent',
  'maxMonthlyRent',
  'minExclusiveArea',
  'maxExclusiveArea',
  'builtYearFrom',
  'builtYearTo',
] as const

function hasComplexSearchFilters(url: URL) {
  return COMPLEX_SEARCH_FILTER_KEYS.some((key) => url.searchParams.has(key))
}

function complexIdsMatchingFilters(
  snapshot: PublicHousingSnapshotV1,
  url: URL,
) {
  return new Set(snapshot.complexListItems
    .filter((item) => complexMatchesFilters(snapshot, item, url))
    .map((item) => item.complexId))
}

function complexMatchesFilters(
  snapshot: PublicHousingSnapshotV1,
  item: RawComplexListItem,
  url: URL,
) {
  const representative = item.representativeAnnouncement
  const announcement = representative === null
    ? undefined
    : snapshot.announcementListItems.find(
        (candidate) => candidate.announcementId
          === representative.announcementId,
      )
  const announcementDetail = representative === null
    ? undefined
    : snapshot.announcementDetails.find(
        (candidate) => candidate.announcementId
          === representative.announcementId,
      )
  const detail = snapshot.complexDetails.find(
    (candidate) => candidate.complexId === item.complexId,
  )
  const completionYear = detail?.completionDate === null
    || detail?.completionDate === undefined
    ? null
    : Number(detail.completionDate.slice(0, 4))
  const search = url.searchParams

  const regionCode = snapshot.complexRegionCodes[String(item.complexId)]
  return matchesRegion(
    search.get('regionCode'),
    [item.regionName],
    regionCode === undefined ? [] : [regionCode],
    snapshot.regionCodeDescendants,
  )
    && matchesRepeated(search, 'rentalTypes', item.rentalType)
    && matchesRepeated(
      search,
      'applicationStatuses',
      representative?.applicationStatus ?? null,
    )
    && matchesRepeated(search, 'agencyCodes', item.agency?.code ?? null)
    && matchesRepeated(
      search,
      'recruitmentTypes',
      announcement?.recruitmentType ?? null,
    )
    && matchesHousingFilters(
      search,
      item.complexId,
      detail,
      announcementDetail,
    )
    && matchesYearRange(search, completionYear)
}

function announcementMatchesFilters(
  snapshot: PublicHousingSnapshotV1,
  item: RawAnnouncementListItem,
  url: URL,
) {
  const search = url.searchParams
  return matchesRegion(
    search.get('regionCode'),
    item.regionNames,
    snapshot.announcementRegionCodes[String(item.announcementId)] ?? [],
    snapshot.regionCodeDescendants,
  )
    && matchesRepeated(search, 'rentalTypes', item.rentalType)
    && matchesRepeated(
      search,
      'applicationStatuses',
      item.applicationStatus,
    )
    && matchesRepeated(search, 'agencyCodes', item.agency?.code ?? null)
    && matchesRepeated(search, 'recruitmentTypes', item.recruitmentType)
}

function matchesRegion(
  regionCode: string | null,
  regionNames: readonly (string | null)[],
  itemRegionCodes: readonly string[],
  regionCodeDescendants: Readonly<Record<string, readonly string[]>>,
) {
  if (regionCode === null) {
    return true
  }
  const matchingCodes = [
    regionCode,
    ...(regionCodeDescendants[regionCode] ?? []),
  ]
  const matchesRegionCode = matchingCodes.some((matchingCode) =>
    itemRegionCodes.some((itemRegionCode) => matchingCode.length === 2
      ? itemRegionCode.startsWith(matchingCode)
      : itemRegionCode === matchingCode),
  )
  if (matchesRegionCode || regionCode.length === 5) {
    return matchesRegionCode
  }
  const provinceName = provinceNameForRegionCode(regionCode)
  return provinceName !== null && regionNames.some(
      (name) => name?.startsWith(provinceName) ?? false,
    )
}

function matchesRepeated(
  search: URLSearchParams,
  key: string,
  value: string | null,
) {
  const expected = search.getAll(key)
  return expected.length === 0 || (value !== null && expected.includes(value))
}

function matchesHousingFilters(
  search: URLSearchParams,
  complexId: number,
  detail: RawComplexDetail | undefined,
  representativeDetail: RawAnnouncementDetail | undefined,
) {
  const areaRequested = rangeRequested(
    search,
    'minExclusiveArea',
    'maxExclusiveArea',
  )
  const depositRequested = rangeRequested(
    search,
    'minDeposit',
    'maxDeposit',
  )
  const monthlyRentRequested = rangeRequested(
    search,
    'minMonthlyRent',
    'maxMonthlyRent',
  )
  if (!areaRequested && !depositRequested && !monthlyRentRequested) {
    return true
  }
  if (!depositRequested && !monthlyRentRequested) {
    return detail?.housingTypes.some((housingType) => matchesValueRange(
      search,
      'minExclusiveArea',
      'maxExclusiveArea',
      housingType.exclusiveArea,
    )) ?? false
  }
  if (representativeDetail === undefined) {
    return false
  }
  return representativeDetail.supplyRows.some((supplyRow) => (
    supplyRow.complex?.complexId === complexId
    && matchesValueRange(
      search,
      'minExclusiveArea',
      'maxExclusiveArea',
      supplyRow.housingType?.exclusiveArea ?? null,
    )
    && supplyRow.targets.some((target) => (
      matchesValueRange(
        search,
        'minDeposit',
        'maxDeposit',
        target.deposit,
      )
      && matchesValueRange(
        search,
        'minMonthlyRent',
        'maxMonthlyRent',
        target.monthlyRent,
      )
    ))
  ))
}

function rangeRequested(
  search: URLSearchParams,
  minimumKey: string,
  maximumKey: string,
) {
  return search.has(minimumKey) || search.has(maximumKey)
}

function matchesValueRange(
  search: URLSearchParams,
  minimumKey: string,
  maximumKey: string,
  value: number | null,
) {
  const expectedMinimum = searchNumber(search, minimumKey)
  const expectedMaximum = searchNumber(search, maximumKey)
  if (expectedMinimum === null && expectedMaximum === null) {
    return true
  }
  return value !== null
    && (expectedMinimum === null || value >= expectedMinimum)
    && (expectedMaximum === null || value <= expectedMaximum)
}

function matchesYearRange(
  search: URLSearchParams,
  completionYear: number | null,
) {
  const from = searchNumber(search, 'builtYearFrom')
  const to = searchNumber(search, 'builtYearTo')
  if (from === null && to === null) {
    return true
  }
  return completionYear !== null
    && (from === null || completionYear >= from)
    && (to === null || completionYear <= to)
}

function searchNumber(search: URLSearchParams, key: string) {
  const value = search.get(key)
  if (value === null) {
    return null
  }
  const number = Number(value)
  return Number.isFinite(number) ? number : null
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
    complexRegionCodes: optionalRegionCodeRecord(
      snapshot,
      'complexRegionCodes',
    ),
    announcementRegionCodes: optionalRegionCodeArrayRecord(
      snapshot,
      'announcementRegionCodes',
    ),
    regionCodeDescendants: optionalRegionCodeArrayRecord(
      snapshot,
      'regionCodeDescendants',
    ),
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

function optionalRegionCodeRecord(
  record: Record<string, unknown>,
  field: string,
): Readonly<Record<string, string>> {
  if (!Object.hasOwn(record, field)) {
    return {}
  }

  const source = recordAt(record[field], `$.${field}`)
  return Object.fromEntries(Object.entries(source).map(([key, value]) => {
    if (!isRegionCode(value)) {
      throw new PublicHousingContractError(`$.${field}.${key}`)
    }
    return [key, value]
  }))
}

function optionalRegionCodeArrayRecord(
  record: Record<string, unknown>,
  field: string,
): Readonly<Record<string, readonly string[]>> {
  if (!Object.hasOwn(record, field)) {
    return {}
  }

  const source = recordAt(record[field], `$.${field}`)
  return Object.fromEntries(Object.entries(source).map(([key, value]) => {
    if (!Array.isArray(value) || !value.every(isRegionCode)) {
      throw new PublicHousingContractError(`$.${field}.${key}`)
    }
    return [key, value]
  }))
}

function isRegionCode(value: unknown): value is string {
  return typeof value === 'string' && /^\d{5}$/.test(value)
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
