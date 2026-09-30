import { describe, expect, it, vi } from 'vitest'
import type { RegionBoundaryMetadata } from './regionBoundary.ts'
import { createRegionBoundaryRepository } from './regionBoundaryRepository.ts'

const VERSION = 'fixture-v1'
const REGION_CODE = '11110'
const OUTER_RING = [[126, 36], [128, 36], [128, 38], [126, 38], [126, 36]]
const HOLE = [[126.2, 36.2], [126.2, 36.4], [126.4, 36.4], [126.2, 36.2]]
const ISLAND = [[129, 36], [129.5, 36], [129.5, 36.5], [129, 36]]

describe('region boundary repository', () => {
  it('preserves both islands and a hole while fetching the metadata path', async () => {
    const fetcher = vi.fn().mockResolvedValue(jsonResponse(feature()))
    const repository = createRegionBoundaryRepository({
      version: VERSION,
      findMetadata: metadata,
      fetcher,
    })
    const controller = new AbortController()

    await expect(repository.find(REGION_CODE, controller.signal)).resolves.toEqual({
      regionCode: REGION_CODE,
      version: VERSION,
      polygons: [
        [
          [[126, 36], [128, 36], [128, 38], [126, 38], [126, 36]],
          [[126.2, 36.2], [126.2, 36.4], [126.4, 36.4], [126.2, 36.2]],
        ],
        [[[129, 36], [129.5, 36], [129.5, 36.5], [129, 36]]],
      ],
    })
    expect(fetcher).toHaveBeenCalledWith(
      '/region-boundaries/fixture-v1/11110.geojson',
      { headers: { Accept: 'application/geo+json' }, signal: controller.signal },
    )
  })

  it('normalizes a Polygon without an optional bbox to one polygon part', async () => {
    const repository = repositoryFor(feature({
      geometry: { type: 'Polygon', coordinates: [OUTER_RING, HOLE] },
    }))

    await expect(find(repository)).resolves.toMatchObject({
      polygons: [[
        [[126, 36], [128, 36], [128, 38], [126, 38], [126, 36]],
        [[126.2, 36.2], [126.2, 36.4], [126.4, 36.4], [126.2, 36.2]],
      ]],
    })
  })

  it('accepts a finite bbox containing every polygon part', async () => {
    const repository = repositoryFor(feature({ bbox: [126, 36, 129.5, 38] }))

    await expect(find(repository)).resolves.toMatchObject({ regionCode: REGION_CODE })
  })

  it.each([
    ['not an object', null],
    ['a FeatureCollection', { type: 'FeatureCollection', features: [] }],
    ['missing properties', feature({ properties: undefined })],
    ['wrong region code', feature({ properties: { regionCode: '11140', version: VERSION } })],
    ['wrong version', feature({ properties: { regionCode: REGION_CODE, version: 'old' } })],
    ['missing geometry', feature({ geometry: null })],
    ['unsupported geometry', feature({ geometry: { type: 'LineString', coordinates: OUTER_RING } })],
    ['empty MultiPolygon', feature({ geometry: { type: 'MultiPolygon', coordinates: [] } })],
    ['empty polygon', polygon([])],
    ['empty ring', polygon([[]])],
    ['too short ring', polygon([[[126, 36], [128, 36], [126, 36]]])],
    ['unclosed ring', polygon([[[126, 36], [128, 36], [128, 38], [126, 38]]])],
    ['missing coordinate', polygon([[[126], [128, 36], [128, 38], [126]]])],
    ['unexpected altitude', polygon([[[126, 36, 0], [128, 36], [128, 38], [126, 36, 0]]])],
    ['string coordinate', polygon([[['126', 36], [128, 36], [128, 38], ['126', 36]]])],
    ['non-finite coordinate', polygon([[[Infinity, 36], [128, 36], [128, 38], [Infinity, 36]]])],
    ['longitude out of range', polygon([[[181, 36], [128, 36], [128, 38], [181, 36]]])],
    ['latitude out of range', polygon([[[126, 91], [128, 36], [128, 38], [126, 91]]])],
    ['coordinates outside metadata bbox', polygon([[[125, 36], [128, 36], [128, 38], [125, 36]]])],
    ['non-array bbox', feature({ bbox: '126,36,129.5,38' })],
    ['incomplete bbox', feature({ bbox: [126, 36, 129.5] })],
    ['non-finite bbox', feature({ bbox: [126, 36, Infinity, 38] })],
    ['reversed bbox', feature({ bbox: [129.5, 36, 126, 38] })],
    ['out-of-range bbox', feature({ bbox: [126, 36, 181, 38] })],
    ['bbox excluding an island', feature({ bbox: [126, 36, 128, 38] })],
  ])('rejects %s', async (_description, body) => {
    await expect(find(repositoryFor(body))).rejects.toMatchObject({
      name: 'RegionBoundaryContractError',
    })
  })

  it('allows only sub-meter rounding differences at metadata bbox edges', async () => {
    const repository = createRegionBoundaryRepository({
      version: VERSION,
      findMetadata: (code) => ({
        ...metadata(code),
        bounds: {
          southWestLng: 126.0000001,
          southWestLat: 36.0000001,
          northEastLng: 129.4999999,
          northEastLat: 37.9999999,
        },
      }),
      fetcher: vi.fn().mockResolvedValue(jsonResponse(feature())),
    })

    await expect(find(repository)).resolves.toMatchObject({ regionCode: REGION_CODE })
  })

  it('rejects an unsupported code before making a network request', async () => {
    const fetcher = vi.fn()
    const repository = createRegionBoundaryRepository({
      version: VERSION,
      findMetadata: () => undefined,
      fetcher,
    })

    await expect(find(repository)).rejects.toBeInstanceOf(RangeError)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('rejects metadata for another code before making a network request', async () => {
    const fetcher = vi.fn()
    const repository = createRegionBoundaryRepository({
      version: VERSION,
      findMetadata: () => metadata('11140'),
      fetcher,
    })

    await expect(find(repository)).rejects.toMatchObject({ name: 'RegionBoundaryContractError' })
    expect(fetcher).not.toHaveBeenCalled()
  })

  it.each([
    ['HTTP failure', () => new Response('not found', { status: 404 })],
    ['invalid JSON', () => new Response('<html>SPA fallback</html>')],
    ['invalid geometry', () => jsonResponse(polygon([[]]))],
  ])('does not cache %s and allows a successful retry', async (_description, response) => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(response())
      .mockResolvedValueOnce(jsonResponse(feature()))
    const repository = createRegionBoundaryRepository({
      version: VERSION,
      findMetadata: metadata,
      fetcher,
    })

    await expect(find(repository)).rejects.toThrow()
    await expect(find(repository)).resolves.toMatchObject({ regionCode: REGION_CODE })
    await expect(find(repository)).resolves.toMatchObject({ regionCode: REGION_CODE })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('does not cache network failure and allows a retry', async () => {
    const fetcher = vi.fn()
      .mockRejectedValueOnce(new TypeError('Network unavailable'))
      .mockResolvedValueOnce(jsonResponse(feature()))
    const repository = createRegionBoundaryRepository({ version: VERSION, findMetadata: metadata, fetcher })

    await expect(find(repository)).rejects.toThrow('Network unavailable')
    await expect(find(repository)).resolves.toMatchObject({ regionCode: REGION_CODE })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('honors a pre-aborted signal before fetching or returning a cached boundary', async () => {
    const fetcher = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(feature())))
    const repository = createRegionBoundaryRepository({ version: VERSION, findMetadata: metadata, fetcher })
    const controller = new AbortController()
    controller.abort()

    await expect(repository.find(REGION_CODE, controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
    expect(fetcher).not.toHaveBeenCalled()
    await find(repository)
    await expect(repository.find(REGION_CODE, controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('does not cache an aborted fetch and lets a new signal retry', async () => {
    const fetcher = vi.fn()
      .mockRejectedValueOnce(new DOMException('Aborted', 'AbortError'))
      .mockResolvedValueOnce(jsonResponse(feature()))
    const repository = createRegionBoundaryRepository({ version: VERSION, findMetadata: metadata, fetcher })

    await expect(find(repository)).rejects.toMatchObject({ name: 'AbortError' })
    await expect(find(repository)).resolves.toMatchObject({ regionCode: REGION_CODE })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('does not cache a response if cancellation arrives while reading its body', async () => {
    const controller = new AbortController()
    const response = jsonResponse(feature())
    const originalJson = response.json.bind(response)
    vi.spyOn(response, 'json').mockImplementation(async () => {
      const body: unknown = await originalJson()
      controller.abort()
      return body
    })
    const fetcher = vi.fn()
      .mockResolvedValueOnce(response)
      .mockResolvedValueOnce(jsonResponse(feature()))
    const repository = createRegionBoundaryRepository({ version: VERSION, findMetadata: metadata, fetcher })

    await expect(repository.find(REGION_CODE, controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
    await expect(find(repository)).resolves.toMatchObject({ regionCode: REGION_CODE })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('keeps the eight most recently used successful boundaries', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (input) => {
      const code = String(input).split('/').at(-1)?.replace('.geojson', '')
      return jsonResponse(feature({ properties: { regionCode: code, version: VERSION } }))
    })
    const repository = createRegionBoundaryRepository({ version: VERSION, findMetadata: metadata, fetcher })
    for (const code of ['11110', '11140', '11170', '11200', '11215', '11230', '11260', '11290']) {
      await find(repository, code)
    }
    await find(repository, '11110')
    await find(repository, '11305')
    expect(fetcher).toHaveBeenCalledTimes(9)

    await expect(find(repository, '11110')).resolves.toMatchObject({ regionCode: '11110' })
    expect(fetcher).toHaveBeenCalledTimes(9)
    await expect(find(repository, '11140')).resolves.toMatchObject({ regionCode: '11140' })
    expect(fetcher).toHaveBeenCalledTimes(10)
  })
})

function metadata(regionCode: string): RegionBoundaryMetadata {
  return {
    regionCode,
    name: '테스트 경계',
    bounds: { southWestLng: 126, southWestLat: 36, northEastLng: 129.5, northEastLat: 38 },
    path: `/region-boundaries/${VERSION}/${regionCode}.geojson`,
  }
}

function feature(overrides: Record<string, unknown> = {}) {
  return {
    type: 'Feature',
    properties: { regionCode: REGION_CODE, version: VERSION },
    geometry: { type: 'MultiPolygon', coordinates: [[OUTER_RING, HOLE], [ISLAND]] },
    ...overrides,
  }
}

function polygon(coordinates: unknown) {
  return feature({ geometry: { type: 'Polygon', coordinates } })
}

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/geo+json' } })
}

function repositoryFor(body: unknown) {
  return createRegionBoundaryRepository({
    version: VERSION,
    findMetadata: metadata,
    fetcher: vi.fn().mockResolvedValue(jsonResponse(body)),
  })
}

function find(repository: ReturnType<typeof createRegionBoundaryRepository>, code = REGION_CODE) {
  return repository.find(code, new AbortController().signal)
}
