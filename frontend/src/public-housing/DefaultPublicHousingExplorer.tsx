import { type ReactNode, useEffect, useState } from 'react'
import {
  defaultPublicHousingRepository,
  loadLocalPublicHousingMock,
  localPublicHousingMockEnabled,
} from './api/defaultPublicHousingRepository.ts'
import type { PublicHousingRepository } from './api/publicHousingRepository.ts'
import { decodePublicHousingSnapshot } from './api/snapshotPublicHousingRepository.ts'
import { decodeLocalPublicHousingMapSnapshot } from './map/localPublicHousingMapSnapshot.ts'
import type { LocalMapSnapshot } from './map/localMapMarkerResolver.ts'
import { PublicHousingExplorer } from './PublicHousingExplorer.tsx'

type LocalSnapshotState =
  | { readonly status: 'loading' }
  | { readonly status: 'ready'; readonly snapshot: LocalMapSnapshot }
  | { readonly status: 'error' }

interface LocalPublicHousingExplorerProps {
  readonly loadSnapshot?: () => Promise<unknown>
  readonly repository?: PublicHousingRepository
}

export function DefaultPublicHousingExplorer() {
  if (!localPublicHousingMockEnabled) {
    return <PublicHousingExplorer repository={defaultPublicHousingRepository} />
  }
  return <LocalPublicHousingExplorer />
}

export function LocalPublicHousingExplorer({
  loadSnapshot = loadLocalPublicHousingMock,
  repository = defaultPublicHousingRepository,
}: LocalPublicHousingExplorerProps) {
  const [retryRevision, setRetryRevision] = useState(0)
  const [state, setState] = useState<LocalSnapshotState>({
    status: 'loading',
  })

  useEffect(() => {
    let active = true
    setState({ status: 'loading' })
    loadSnapshot()
      .then(decodeLocalRuntimeMapSnapshot)
      .then((snapshot) => {
        if (active) {
          setState({ status: 'ready', snapshot })
        }
      })
      .catch(() => {
        if (active) {
          setState({ status: 'error' })
        }
      })
    return () => {
      active = false
    }
  }, [loadSnapshot, retryRevision])

  if (state.status === 'ready') {
    return (
      <PublicHousingExplorer
        localMapSnapshot={state.snapshot}
        repository={repository}
      />
    )
  }

  if (state.status === 'error') {
    return (
      <LocalMockState role="alert">
        <strong>로컬 mock 데이터를 불러오지 못했습니다.</strong>
        <span>로컬 개발 데이터 파일과 실행 설정을 확인해 주세요.</span>
        <button
          type="button"
          onClick={() => setRetryRevision((current) => current + 1)}
        >
          다시 시도
        </button>
      </LocalMockState>
    )
  }

  return (
    <LocalMockState role="status">
      <strong>로컬 mock 데이터를 준비하고 있습니다.</strong>
    </LocalMockState>
  )
}

function decodeLocalRuntimeMapSnapshot(value: unknown) {
  decodePublicHousingSnapshot(value)
  return decodeLocalPublicHousingMapSnapshot(value)
}

function LocalMockState({
  children,
  role,
}: {
  readonly children: ReactNode
  readonly role: 'alert' | 'status'
}) {
  return (
    <div className="local-public-housing-state" role={role}>
      {children}
    </div>
  )
}
