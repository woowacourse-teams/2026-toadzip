import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { retryFailedRasterTiles } from './rasterTileRetry.ts'

type Listener = (event: { sourceId?: unknown }) => void

function createFakeMap(states: Record<string, Record<string, string>>) {
  const listeners = new Set<Listener>()
  const reloads: string[] = []
  const sourceCaches = Object.fromEntries(Object.entries(states).map(([sourceId, tiles]) => {
    const cache = {
      _tiles: Object.fromEntries(Object.entries(tiles).map(([key, state]) => [key, { state }])),
      _reloadTile: (key: string, state: string) => {
        reloads.push(`${sourceId}:${key}`)
        cache._tiles[key].state = state
      },
    }
    return [sourceId, cache]
  }))
  const mapbox = {
    on: (_type: string, listener: Listener) => { listeners.add(listener) },
    off: (_type: string, listener: Listener) => { listeners.delete(listener) },
    getSource: (id: string) => ({ background: { type: 'raster' }, roads: { type: 'vector' } })[id],
    style: { sourceCaches },
  }
  return {
    map: { getMapbox: () => mapbox } as unknown as naver.maps.Map,
    reloads,
    listeners,
    fail: (sourceId: string, key?: string) => {
      if (key) sourceCaches[sourceId]._tiles[key].state = 'errored'
      for (const listener of listeners) listener({ sourceId })
    },
    succeed: (sourceId: string, key: string) => { sourceCaches[sourceId]._tiles[key].state = 'loaded' },
  }
}

describe('retryFailedRasterTiles', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('배경 래스터 타일이 실패하면 잠시 뒤 실패한 타일만 다시 요청한다', () => {
    const fake = createFakeMap({ background: { a: 'errored', b: 'loaded', c: 'errored' } })
    retryFailedRasterTiles(fake.map)

    fake.fail('background')
    fake.fail('background')
    vi.advanceTimersByTime(1_999)
    expect(fake.reloads).toEqual([])

    vi.advanceTimersByTime(1)
    expect(fake.reloads).toEqual(['background:a', 'background:c'])
  })

  it('감시를 켜기 전에 이미 실패한 배경 타일도 잠시 뒤 다시 요청한다', () => {
    const fake = createFakeMap({ background: { a: 'errored', b: 'loaded' }, roads: { r: 'errored' } })
    retryFailedRasterTiles(fake.map)

    vi.advanceTimersByTime(2_000)
    expect(fake.reloads).toEqual(['background:a'])
  })

  it('다시 실패하면 간격을 늘려 재요청하고, 회복되면 멈춘 뒤 다음 실패는 처음 간격으로 재요청한다', () => {
    const fake = createFakeMap({ background: { a: 'errored' } })
    retryFailedRasterTiles(fake.map)

    fake.fail('background')
    vi.advanceTimersByTime(2_000)
    expect(fake.reloads).toEqual(['background:a'])

    fake.fail('background', 'a')
    vi.advanceTimersByTime(3_999)
    expect(fake.reloads).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(fake.reloads).toHaveLength(2)

    fake.succeed('background', 'a')
    vi.advanceTimersByTime(60_000)
    expect(fake.reloads).toHaveLength(2)

    fake.fail('background', 'a')
    vi.advanceTimersByTime(2_000)
    expect(fake.reloads).toHaveLength(3)
  })

  it('계속 실패해도 간격은 30초를 넘기지 않고 연속 6번 재요청한 뒤 멈춘다', () => {
    const fake = createFakeMap({ background: { a: 'errored' } })
    retryFailedRasterTiles(fake.map)

    fake.fail('background')
    for (const delay of [2_000, 4_000, 8_000, 16_000, 30_000, 30_000]) {
      const before = fake.reloads.length
      vi.advanceTimersByTime(delay - 1)
      expect(fake.reloads).toHaveLength(before)
      vi.advanceTimersByTime(1)
      expect(fake.reloads).toHaveLength(before + 1)
      fake.fail('background', 'a')
    }
    vi.advanceTimersByTime(120_000)
    expect(fake.reloads).toHaveLength(6)

    fake.fail('background', 'a')
    vi.advanceTimersByTime(2_000)
    expect(fake.reloads).toHaveLength(7)
  })

  it('벡터 소스 실패는 다시 요청하지 않는다', () => {
    const fake = createFakeMap({ roads: { a: 'errored' } })
    retryFailedRasterTiles(fake.map)

    fake.fail('roads')
    vi.advanceTimersByTime(60_000)
    expect(fake.reloads).toEqual([])
  })

  it('해제하면 리스너와 예약된 재요청을 모두 지운다', () => {
    const fake = createFakeMap({ background: { a: 'errored' } })
    const stop = retryFailedRasterTiles(fake.map)

    fake.fail('background')
    stop()
    vi.advanceTimersByTime(60_000)
    expect(fake.reloads).toEqual([])
    expect(fake.listeners.size).toBe(0)
  })

  it('SDK 내부 모양이 다르면 아무것도 하지 않는다', () => {
    expect(() => retryFailedRasterTiles({} as naver.maps.Map)()).not.toThrow()
    expect(() => retryFailedRasterTiles({ getMapbox: () => { throw new Error('x') } } as unknown as naver.maps.Map)()).not.toThrow()

    const fake = createFakeMap({ background: { a: 'errored' } })
    const mapbox = (fake.map as unknown as { getMapbox: () => { style: unknown } }).getMapbox()
    mapbox.style = {}
    retryFailedRasterTiles(fake.map)
    fake.fail('background')
    expect(() => vi.advanceTimersByTime(60_000)).not.toThrow()
    expect(fake.reloads).toEqual([])
  })
})
