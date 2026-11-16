// NAVER GL 지도는 땅·물·공원 같은 배경색을 래스터 타일로 그리고 도로·글자만 벡터로 그린다.
// 내부 Mapbox는 한 번 실패한 타일을 다시 요청하지 않아서, 요청이 일시적으로 실패하면
// 그 칸만 배경색 없이 흰색·회색으로 남는다. 실패한 래스터 타일만 골라 다시 요청한다.
// getMapbox()와 sourceCache는 공개 API가 아니므로 모양이 다르면 아무것도 하지 않는다.

const FIRST_RETRY_DELAY_MS = 2_000
const MAX_RETRY_DELAY_MS = 30_000
// 계속 실패하는 타일(서비스 범위 밖 등)을 무한히 요청하지 않도록 연속 재요청 횟수를 제한한다.
const MAX_CONSECUTIVE_RETRIES = 6

type TileLike = { state: string }

type SourceCacheLike = {
  _tiles: Record<string, TileLike>
  _reloadTile: (key: string, state: string) => void
}

type MapboxErrorEvent = { sourceId?: unknown }

type MapboxLike = {
  on: (type: 'error', listener: (event: MapboxErrorEvent) => void) => void
  off: (type: 'error', listener: (event: MapboxErrorEvent) => void) => void
  getSource: (id: string) => { type?: unknown } | undefined
  style?: { sourceCaches?: Record<string, unknown> }
}

type MapWithMapbox = { getMapbox?: () => unknown }

export function retryFailedRasterTiles(map: naver.maps.Map): () => void {
  const mapbox = readMapbox(map)
  if (!mapbox) return () => {}

  const retries = new Map<string, { delay: number; attempts: number; timer?: number }>()

  const retry = (sourceId: string) => {
    const entry = retries.get(sourceId)
    if (!entry) return
    entry.timer = undefined
    const sourceCache = readSourceCache(mapbox, sourceId)
    const failedKeys = sourceCache
      ? Object.keys(sourceCache._tiles).filter((key) => sourceCache._tiles[key]?.state === 'errored')
      : []
    if (!sourceCache || failedKeys.length === 0 || entry.attempts >= MAX_CONSECUTIVE_RETRIES) {
      retries.delete(sourceId)
      return
    }
    entry.attempts += 1
    for (const key of failedKeys) sourceCache._reloadTile(key, 'reloading')
    // 다음 확인에서 실패 타일이 없으면 간격을 처음으로 되돌리고, 남아 있으면 더 길게 기다려 다시 요청한다.
    entry.delay = Math.min(entry.delay * 2, MAX_RETRY_DELAY_MS)
    entry.timer = window.setTimeout(() => retry(sourceId), entry.delay)
  }

  const handleError = (event: MapboxErrorEvent) => {
    const { sourceId } = event
    if (typeof sourceId !== 'string' || mapbox.getSource(sourceId)?.type !== 'raster') return
    const entry = retries.get(sourceId) ?? { delay: FIRST_RETRY_DELAY_MS, attempts: 0 }
    retries.set(sourceId, entry)
    if (entry.timer !== undefined) return
    entry.timer = window.setTimeout(() => retry(sourceId), entry.delay)
  }

  mapbox.on('error', handleError)
  // 감시는 지도 init 뒤에 켜지므로, 첫 화면을 그리며 그 전에 실패한 타일도 같은 방식으로 다시 요청한다.
  for (const sourceId of Object.keys(mapbox.style?.sourceCaches ?? {})) handleError({ sourceId })
  return () => {
    mapbox.off('error', handleError)
    for (const { timer } of retries.values()) window.clearTimeout(timer)
    retries.clear()
  }
}

function readMapbox(map: naver.maps.Map): MapboxLike | null {
  try {
    const mapbox = (map as unknown as MapWithMapbox).getMapbox?.() as Partial<MapboxLike> | null | undefined
    if (typeof mapbox?.on !== 'function' || typeof mapbox.off !== 'function'
      || typeof mapbox.getSource !== 'function') return null
    return mapbox as MapboxLike
  } catch {
    return null
  }
}

function readSourceCache(mapbox: MapboxLike, sourceId: string): SourceCacheLike | null {
  const sourceCache = mapbox.style?.sourceCaches?.[sourceId] as Partial<SourceCacheLike> | undefined
  if (!sourceCache || typeof sourceCache._reloadTile !== 'function'
    || typeof sourceCache._tiles !== 'object' || sourceCache._tiles === null) return null
  return sourceCache as SourceCacheLike
}
