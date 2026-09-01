import { describe, expect, it } from 'vitest'
import type {
  RawAnnouncementDetail,
  RawAnnouncementListItem,
  RawComplexDetail,
  RawComplexListItem,
  RawMapComplex,
} from '../model/publicHousing.ts'
import { MINIMAL_PUBLIC_HOUSING_SNAPSHOT } from '../testing/minimalPublicHousingSnapshot.ts'
import { PublicHousingContractError } from './publicHousingContract.ts'
import { PublicHousingHttpError } from './publicHousingRepository.ts'
import {
  createSnapshotPublicHousingRepository,
  type PublicHousingSnapshotV1,
} from './snapshotPublicHousingRepository.ts'

const BOUNDS = {
  southWestLat: 37.4,
  southWestLng: 126.8,
  northEastLat: 37.7,
  northEastLng: 127.1,
}

describe('local public housing snapshot repository', () => {
  it('serves all five repository methods through the production contract', async () => {
    const repository = createSnapshotPublicHousingRepository(
      MINIMAL_PUBLIC_HOUSING_SNAPSHOT,
    )
    const signal = new AbortController().signal

    const mapItems = await repository.findMapComplexes(BOUNDS, signal)
    const complexPage = await repository.findComplexPage(
      BOUNDS,
      null,
      20,
      signal,
    )
    const complexDetail = await repository.findComplexDetail('17', signal)
    const announcementPage = await repository.findAnnouncementPage(
      null,
      20,
      signal,
    )
    const announcementDetail = await repository.findAnnouncementDetail(
      '201',
      signal,
    )

    expect(mapItems).toHaveLength(1)
    expect(mapItems[0]).toMatchObject({
      complexId: '17',
      depositMin: 0,
      depositMax: null,
    })
    expect(mapItems[0]?.raw).toEqual(
      MINIMAL_PUBLIC_HOUSING_SNAPSHOT.mapComplexItems[0],
    )
    expect(complexPage).toMatchObject({
      hasNext: false,
      nextCursor: null,
      items: [{ complexId: '17', exclusiveAreaMin: 0, depositMax: null }],
    })
    expect(complexPage.raw.items[0]).toEqual(
      MINIMAL_PUBLIC_HOUSING_SNAPSHOT.complexListItems[0],
    )
    expect(complexDetail).toMatchObject({
      complexId: '17',
      completionDate: null,
      hasElevator: false,
      moveOutCountLastYear: 0,
      totalParkingCount: 0,
      housingTypes: [
        {
          housingTypeId: '301',
          exclusiveArea: 0,
          maintenanceFee: 0,
          currentSupplyConditions: [{ deposit: 0, monthlyRent: null }],
        },
      ],
    })
    expect(complexDetail.raw).toEqual(
      MINIMAL_PUBLIC_HOUSING_SNAPSHOT.complexDetails[0],
    )
    expect(announcementPage).toMatchObject({
      hasNext: false,
      nextCursor: null,
      items: [
        {
          announcementId: '201',
          publishedAt: null,
          viewCount: 0,
          supplyHouseholdCount: 0,
          predictedCompetitionRate: null,
        },
      ],
    })
    expect(announcementPage.raw.items[0]).toEqual(
      MINIMAL_PUBLIC_HOUSING_SNAPSHOT.announcementListItems[0],
    )
    expect(announcementDetail).toMatchObject({
      announcementId: '201',
      publishedAt: null,
      winnerAnnouncementAt: null,
      viewCount: 0,
      supplyHouseholdCount: 0,
      schedules: [{ scheduleId: '501', name: null, endAt: null }],
      supplyRows: [
        {
          supplyRowId: '401',
          totalSupplyHouseholdCount: 0,
          targets: [
            {
              supplyTargetId: '601',
              supplyHouseholdCount: 0,
              deposit: 0,
              monthlyRent: null,
            },
          ],
        },
      ],
      competition: { actualRate: 0, predictedRate: null },
    })
    expect(announcementDetail.raw).toEqual(
      MINIMAL_PUBLIC_HOUSING_SNAPSHOT.announcementDetails[0],
    )
  })

  it('filters map and complex pages by bounds and keeps cursors opaque', async () => {
    const repository = createSnapshotPublicHousingRepository(
      snapshotWithSecondScenario(),
    )
    const signal = new AbortController().signal

    const firstComplexPage = await repository.findComplexPage(
      BOUNDS,
      null,
      1,
      signal,
    )
    const firstAnnouncementPage = await repository.findAnnouncementPage(
      null,
      1,
      signal,
    )

    expect(firstComplexPage.items.map((item) => item.complexId)).toEqual(['17'])
    expect(firstComplexPage.hasNext).toBe(true)
    expect(firstComplexPage.nextCursor).not.toBeNull()
    expect(firstAnnouncementPage.items.map((item) => item.announcementId))
      .toEqual(['201'])
    expect(firstAnnouncementPage.hasNext).toBe(true)
    expect(firstAnnouncementPage.nextCursor).not.toBeNull()

    const secondComplexPage = await repository.findComplexPage(
      BOUNDS,
      firstComplexPage.nextCursor,
      1,
      signal,
    )
    const secondAnnouncementPage = await repository.findAnnouncementPage(
      firstAnnouncementPage.nextCursor,
      1,
      signal,
    )

    expect(secondComplexPage.items.map((item) => item.complexId)).toEqual(['18'])
    expect(secondComplexPage.hasNext).toBe(false)
    expect(secondAnnouncementPage.items.map((item) => item.announcementId))
      .toEqual(['202'])
    expect(secondAnnouncementPage.hasNext).toBe(false)

    await expect(repository.findMapComplexes({
      southWestLat: 35,
      southWestLng: 128,
      northEastLat: 36,
      northEastLng: 129,
    }, signal)).resolves.toEqual([])
  })

  it('applies the same filters to local snapshot maps, complexes and announcements', async () => {
    const repository = createSnapshotPublicHousingRepository(
      snapshotWithSecondScenario(),
    )
    const signal = new AbortController().signal
    const complexFilters = {
      agencyCodes: ['GH'],
      applicationStatuses: ['BEFORE_APPLICATION'],
      builtYearFrom: 2018,
      builtYearTo: 2018,
      maxDeposit: 25_000_000,
      maxExclusiveArea: 55,
      maxMonthlyRent: 230_000,
      minDeposit: 15_000_000,
      minExclusiveArea: 45,
      minMonthlyRent: 210_000,
      recruitmentTypes: ['WAITLIST'],
      regionCode: '41135',
      rentalTypes: ['NATIONAL_RENTAL'],
    } as const

    const [mapItems, complexPage, announcementPage] = await Promise.all([
      repository.findMapComplexes(BOUNDS, signal, complexFilters),
      repository.findComplexPage(BOUNDS, null, 20, signal, complexFilters),
      repository.findAnnouncementPage(null, 20, signal, {
        agencyCodes: ['GH'],
        applicationStatuses: ['BEFORE_APPLICATION'],
        recruitmentTypes: ['WAITLIST'],
        regionCode: '41135',
        rentalTypes: ['NATIONAL_RENTAL'],
      }),
    ])

    expect(mapItems.map((item) => item.complexId)).toEqual(['18'])
    expect(complexPage.items.map((item) => item.complexId)).toEqual(['18'])
    expect(announcementPage.items.map((item) => item.announcementId))
      .toEqual(['202'])

    await expect(repository.findComplexPage(BOUNDS, null, 20, signal, {
      regionCode: '41110',
    })).resolves.toMatchObject({ items: [] })
    await expect(repository.findComplexPage(BOUNDS, null, 20, signal, {
      minExclusiveArea: 52,
      maxExclusiveArea: 55,
    })).resolves.toMatchObject({ items: [] })
    await expect(repository.findComplexPage(BOUNDS, null, 20, signal, {
      minDeposit: 90_000_000,
      minMonthlyRent: 900_000,
    })).resolves.toMatchObject({ items: [] })
  })

  it('includes child districts when a snapshot parent city is selected', async () => {
    const snapshot = {
      ...snapshotWithSecondScenario(),
      regionCodeDescendants: {
        41130: ['41131', '41133', '41135', '41137', '41139'],
      },
    } as unknown as PublicHousingSnapshotV1
    const repository = createSnapshotPublicHousingRepository(snapshot)
    const signal = new AbortController().signal

    const [mapItems, complexPage, announcementPage] = await Promise.all([
      repository.findMapComplexes(BOUNDS, signal, { regionCode: '41130' }),
      repository.findComplexPage(BOUNDS, null, 20, signal, {
        regionCode: '41130',
      }),
      repository.findAnnouncementPage(null, 20, signal, {
        regionCode: '41130',
      }),
    ])

    expect(mapItems.map((item) => item.complexId)).toEqual(['18'])
    expect(complexPage.items.map((item) => item.complexId)).toEqual(['18'])
    expect(announcementPage.items.map((item) => item.announcementId))
      .toEqual(['202'])
  })

  it('includes legacy district codes when a snapshot province is selected', async () => {
    const baseSnapshot = snapshotWithSecondScenario()
    const snapshot = {
      ...baseSnapshot,
      complexRegionCodes: {
        ...baseSnapshot.complexRegionCodes,
        18: '29110',
      },
      regionCodeDescendants: {
        ...baseSnapshot.regionCodeDescendants,
        12: ['12110', '12210', '29110', '46110'],
      },
    } as PublicHousingSnapshotV1
    const repository = createSnapshotPublicHousingRepository(snapshot)
    const signal = new AbortController().signal

    const [mapItems, complexPage] = await Promise.all([
      repository.findMapComplexes(BOUNDS, signal, { regionCode: '12' }),
      repository.findComplexPage(BOUNDS, null, 20, signal, {
        regionCode: '12',
      }),
    ])

    expect(mapItems.map((item) => item.complexId)).toEqual(['18'])
    expect(complexPage.items.map((item) => item.complexId)).toEqual(['18'])
  })

  it('returns the same 404 contract as the HTTP repository', async () => {
    const repository = createSnapshotPublicHousingRepository(
      MINIMAL_PUBLIC_HOUSING_SNAPSHOT,
    )
    const signal = new AbortController().signal

    const complexError = await repository.findComplexDetail('999', signal)
      .catch((error: unknown) => error)
    const announcementError = await repository
      .findAnnouncementDetail('999', signal)
      .catch((error: unknown) => error)

    expect(complexError).toBeInstanceOf(PublicHousingHttpError)
    expect(complexError).toMatchObject({
      status: 404,
      code: 'COMPLEX_NOT_FOUND',
    })
    expect(announcementError).toBeInstanceOf(PublicHousingHttpError)
    expect(announcementError).toMatchObject({
      status: 404,
      code: 'ANNOUNCEMENT_NOT_FOUND',
    })
  })

  it('preserves pre-abort and abort while an async snapshot is loading', async () => {
    const preAbortedController = new AbortController()
    preAbortedController.abort()
    let preAbortLoadCount = 0
    const repository = createSnapshotPublicHousingRepository(
      () => {
        preAbortLoadCount += 1
        return Promise.resolve(MINIMAL_PUBLIC_HOUSING_SNAPSHOT)
      },
    )

    await expect(repository.findMapComplexes(
      BOUNDS,
      preAbortedController.signal,
    )).rejects.toMatchObject({ name: 'AbortError' })
    expect(preAbortLoadCount).toBe(0)

    const source = deferred<unknown>()
    const loadingRepository = createSnapshotPublicHousingRepository(
      () => source.promise,
    )
    const loadingController = new AbortController()
    const request = loadingRepository.findAnnouncementPage(
      null,
      20,
      loadingController.signal,
    )
    loadingController.abort()

    await expect(request).rejects.toMatchObject({ name: 'AbortError' })
    source.resolve(MINIMAL_PUBLIC_HOUSING_SNAPSHOT)
  })

  it('validates local data with the production response decoders', async () => {
    const invalidSnapshot = {
      ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT,
      mapComplexItems: [
        {
          ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT.mapComplexItems[0],
          longitude: null,
        },
      ],
    }
    const repository = createSnapshotPublicHousingRepository(
      () => Promise.resolve(invalidSnapshot),
    )

    const error = await repository
      .findMapComplexes(BOUNDS, new AbortController().signal)
      .catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(PublicHousingContractError)
    expect(error).toMatchObject({ path: '$.data.items[0].longitude' })
  })
})

function snapshotWithSecondScenario(): PublicHousingSnapshotV1 {
  const complexListItem = MINIMAL_PUBLIC_HOUSING_SNAPSHOT.complexListItems[0]
  const mapComplexItem = MINIMAL_PUBLIC_HOUSING_SNAPSHOT.mapComplexItems[0]
  const complexDetail = MINIMAL_PUBLIC_HOUSING_SNAPSHOT.complexDetails[0]
  const announcementListItem =
    MINIMAL_PUBLIC_HOUSING_SNAPSHOT.announcementListItems[0]
  const announcementDetail =
    MINIMAL_PUBLIC_HOUSING_SNAPSHOT.announcementDetails[0]

  return {
    ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT,
    complexRegionCodes: {
      17: '11140',
      18: '41135',
    },
    announcementRegionCodes: {
      201: ['11140'],
      202: ['41135'],
    },
    complexListItems: [
      complexListItem,
      {
        ...complexListItem,
        complexId: 18,
        name: '두 번째 단지',
        regionName: '경기도 성남시',
        rentalType: 'NATIONAL_RENTAL',
        agency: { code: 'GH', name: '경기주택도시공사' },
        exclusiveAreaMin: 46,
        exclusiveAreaMax: 59,
        depositMin: 20_000_000,
        depositMax: 30_000_000,
        monthlyRentMin: 220_000,
        monthlyRentMax: 320_000,
        representativeAnnouncement: {
          ...complexListItem.representativeAnnouncement,
          announcementId: 202,
          applicationStatus: 'BEFORE_APPLICATION',
        },
      },
    ] as readonly RawComplexListItem[],
    mapComplexItems: [
      mapComplexItem,
      {
        ...mapComplexItem,
        complexId: 18,
        name: '두 번째 단지',
        latitude: 37.57,
        longitude: 126.99,
        rentalType: 'NATIONAL_RENTAL',
        agency: { code: 'GH', name: '경기주택도시공사' },
        exclusiveAreaMin: 46,
        exclusiveAreaMax: 59,
        depositMin: 20_000_000,
        depositMax: 30_000_000,
        monthlyRentMin: 220_000,
        monthlyRentMax: 320_000,
      },
    ] as readonly RawMapComplex[],
    complexDetails: [
      complexDetail,
      {
        ...complexDetail,
        complexId: 18,
        name: '두 번째 단지',
        rentalType: 'NATIONAL_RENTAL',
        agency: { code: 'GH', name: '경기주택도시공사' },
        address: {
          ...complexDetail.address,
          regionName: '경기도 성남시',
        },
        completionDate: '2018-03-01',
        housingTypes: [{
          ...complexDetail.housingTypes[0],
          exclusiveArea: 50,
          currentSupplyConditions: [{
            ...complexDetail.housingTypes[0].currentSupplyConditions[0],
            deposit: 90_000_000,
            monthlyRent: 900_000,
          }],
        }],
      },
    ] as readonly RawComplexDetail[],
    announcementListItems: [
      announcementListItem,
      {
        ...announcementListItem,
        announcementId: 202,
        title: '두 번째 공고',
        applicationStatus: 'BEFORE_APPLICATION',
        rentalType: 'NATIONAL_RENTAL',
        recruitmentType: 'WAITLIST',
        regionNames: ['경기도 성남시'],
        agency: { code: 'GH', name: '경기주택도시공사' },
      },
    ] as readonly RawAnnouncementListItem[],
    announcementDetails: [
      announcementDetail,
      {
        ...announcementDetail,
        announcementId: 202,
        title: '두 번째 공고',
        supplyRows: [{
          ...announcementDetail.supplyRows[0],
          supplyRowId: 402,
          complex: {
            ...announcementDetail.supplyRows[0].complex,
            complexId: 18,
            name: '두 번째 단지',
            address: '경기도 성남시 분당구 두꺼비로 1',
          },
          housingType: {
            ...announcementDetail.supplyRows[0].housingType,
            housingTypeId: 302,
            name: '50A',
            exclusiveArea: 50,
          },
          targets: [{
            ...announcementDetail.supplyRows[0].targets[0],
            supplyTargetId: 602,
            deposit: 20_000_000,
            monthlyRent: 220_000,
          }],
        }],
      },
    ] as readonly RawAnnouncementDetail[],
  }
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}
