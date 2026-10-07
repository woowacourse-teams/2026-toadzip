import { MISSING_DATA_LABEL } from '../presentation/missingData.ts'
import type {
  IntegratedSearchRepository,
  SearchResultItem,
} from '../search/integratedSearchRepository.ts'
import { createSnapshotPublicHousingRegionRepository } from './snapshotPublicHousingRegionRepository.ts'
import type { PublicHousingSnapshotV1 } from './snapshotPublicHousingRepository.ts'

export function createSnapshotIntegratedSearchRepository(
  snapshot: PublicHousingSnapshotV1,
): IntegratedSearchRepository {
  const regionRepository = createSnapshotPublicHousingRegionRepository(snapshot)
  const mapItemsById = new Map(snapshot.mapComplexItems.map((item) => [
    item.complexId, item,
  ]))
  const complexes: readonly SearchResultItem[] = snapshot.complexListItems.map((item) => {
    const coordinates = mapItemsById.get(item.complexId)
    return {
      type: 'COMPLEX',
      id: String(item.complexId),
      title: item.name ?? MISSING_DATA_LABEL,
      subtitle: item.regionName,
      latitude: coordinates?.latitude ?? null,
      longitude: coordinates?.longitude ?? null,
      regionCode: snapshot.complexRegionCodes[String(item.complexId)] ?? null,
      applicationStatus: item.representativeAnnouncement?.applicationStatus ?? null,
      publishedAt: null,
    }
  })
  const announcements: readonly SearchResultItem[] = snapshot.announcementListItems.map((item) => ({
    type: 'ANNOUNCEMENT',
    id: String(item.announcementId),
    title: item.title ?? MISSING_DATA_LABEL,
    subtitle: item.regionNames.join(', '),
    latitude: null,
    longitude: null,
    regionCode: null,
    applicationStatus: item.applicationStatus,
    publishedAt: item.publishedAt,
  }))

  return {
    async search(query, _preview, page, signal, type) {
      signal.throwIfAborted()
      const normalizedQuery = query.trim().toLocaleLowerCase('ko-KR')
      const regions = await regionRepository.search(normalizedQuery, signal)
      signal.throwIfAborted()
      const matches = (item: SearchResultItem) => [item.title, item.subtitle ?? '']
        .some((text) => text.toLocaleLowerCase('ko-KR').includes(normalizedQuery))
      const allItems: readonly SearchResultItem[] = [
        ...regions.map((region): SearchResultItem => ({
          type: 'REGION',
          id: region.regionCode,
          title: region.displayName,
          subtitle: null,
          latitude: null,
          longitude: null,
          regionCode: region.regionCode,
          applicationStatus: null,
          publishedAt: null,
        })),
        ...announcements.filter(matches),
        ...complexes.filter(matches),
      ].filter((item) => type === undefined || item.type === type)
      const size = type === undefined ? 20 : 5
      const offset = page * size
      const items = allItems.slice(offset, offset + size)
      return {
        query,
        page,
        size,
        totalCount: allItems.length,
        hasNext: offset + items.length < allItems.length,
        announcements: items.filter((item) => item.type === 'ANNOUNCEMENT'),
        complexes: items.filter((item) => item.type === 'COMPLEX'),
        regions: items.filter((item) => item.type === 'REGION'),
        places: [],
        failures: [],
      }
    },
  }
}
