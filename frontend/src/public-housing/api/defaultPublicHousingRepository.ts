import {
  publicHousingRepository,
  type PublicHousingRepository,
} from './publicHousingRepository.ts'
import { decodeLocalPublicHousingMapSnapshot } from '../map/localPublicHousingMapSnapshot.ts'
import {
  createSnapshotPublicHousingRepository,
  decodePublicHousingSnapshot,
} from './snapshotPublicHousingRepository.ts'

export const LOCAL_PUBLIC_HOUSING_SNAPSHOT_ENDPOINT =
  '/__toadzip-local-public-housing/snapshot'

type SnapshotFetcher = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>

export class LocalPublicHousingMockLoadError extends Error {
  readonly status: number

  constructor(status: number) {
    super('로컬 공공주택 mock 데이터를 불러오지 못했습니다.')
    this.name = 'LocalPublicHousingMockLoadError'
    this.status = status
  }
}

export function createLocalPublicHousingMockLoader(
  fetcher: SnapshotFetcher = globalThis.fetch,
) {
  let snapshotPromise: Promise<unknown> | null = null

  return () => {
    if (snapshotPromise !== null) {
      return snapshotPromise
    }
    snapshotPromise = fetcher(LOCAL_PUBLIC_HOUSING_SNAPSHOT_ENDPOINT, {
      cache: 'no-store',
      headers: { Accept: 'application/json' },
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new LocalPublicHousingMockLoadError(response.status)
        }
        const snapshot = (await response.json()) as unknown
        validateLocalPublicHousingSnapshot(snapshot)
        return snapshot
      })
      .catch((error: unknown) => {
        snapshotPromise = null
        throw error
      })
    return snapshotPromise
  }
}

function validateLocalPublicHousingSnapshot(value: unknown) {
  decodePublicHousingSnapshot(value)
  decodeLocalPublicHousingMapSnapshot(value)
}

export const localPublicHousingMockEnabled = import.meta.env.DEV
  && import.meta.env.MODE !== 'test'
  && import.meta.env.VITE_PUBLIC_HOUSING_LOCAL_MOCK === 'true'

export function shouldEnableLocalPublicHousingMock({
  development,
  flag,
  mode,
}: {
  readonly development: boolean
  readonly flag: string | undefined
  readonly mode: string
}) {
  return development && mode !== 'test' && flag === 'true'
}

let localPublicHousingMockLoader: (() => Promise<unknown>) | null = null

export function loadLocalPublicHousingMock() {
  if (localPublicHousingMockLoader === null) {
    localPublicHousingMockLoader = createLocalPublicHousingMockLoader()
  }
  return localPublicHousingMockLoader()
}

export const defaultPublicHousingRepository: PublicHousingRepository =
  localPublicHousingMockEnabled
    ? createSnapshotPublicHousingRepository(loadLocalPublicHousingMock)
    : publicHousingRepository
