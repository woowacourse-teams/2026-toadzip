import { describe, expect, it } from 'vitest'
import type { MapComplex } from '../model/publicHousing.ts'
import {
  presentComplexDetailMarker,
  presentMapComplexMarker,
} from './mapMarkerPresentation.ts'

describe('map marker presentation', () => {
  it('지도 단지 응답을 공급기관·임대유형과 조건 범위로 표시한다', () => {
    expect(presentMapComplexMarker(mapComplex())).toEqual({
      agencyLabel: 'LH',
      areaLabel: '36.12~44.87㎡',
      monthlyRentLabel: '20만~30만 원',
      rentalTypeLabel: '행복주택',
    })
  })

  it('한 값만 있으면 범위 기호 없이 표시하고 기관명으로 대체한다', () => {
    const complex = mapComplex({
      agency: { code: '', name: '서울주택도시공사' },
      exclusiveAreaMax: null,
      monthlyRentMax: null,
    })

    expect(presentMapComplexMarker(complex)).toMatchObject({
      agencyLabel: '서울주택도시공사',
      areaLabel: '36.12㎡',
      monthlyRentLabel: '20만 원',
    })
  })

  it('표시할 값이 없으면 속성별 확인 중 문구를 사용한다', () => {
    const complex = mapComplex({
      agency: null,
      exclusiveAreaMax: null,
      exclusiveAreaMin: null,
      monthlyRentMax: null,
      monthlyRentMin: null,
      rentalType: null,
    })

    expect(presentMapComplexMarker(complex)).toEqual({
      agencyLabel: '기관 확인 중',
      areaLabel: '면적 확인 중',
      monthlyRentLabel: '정보 확인 중',
      rentalTypeLabel: '임대유형 확인 중',
    })
  })

  it('서로 다른 범위가 같은 표시값으로 반올림되면 단일 값으로 표시한다', () => {
    const complex = mapComplex({
      exclusiveAreaMax: 36.124,
      exclusiveAreaMin: 36.121,
      monthlyRentMax: 200_400,
      monthlyRentMin: 200_100,
    })

    expect(presentMapComplexMarker(complex)).toMatchObject({
      areaLabel: '36.12㎡',
      monthlyRentLabel: '20만 원',
    })
  })

  it('상세에서 유지하는 선택 마커는 주택형 전체의 조건 범위를 사용한다', () => {
    const detail = {
      agency: { code: 'SH', name: '서울주택도시공사' },
      housingTypes: [
        {
          currentSupplyConditions: [
            { monthlyRent: 180_000 },
            { monthlyRent: 260_000 },
          ],
          exclusiveArea: 29.7,
        },
        {
          currentSupplyConditions: [{ monthlyRent: 310_000 }],
          exclusiveArea: 46.8,
        },
      ],
      rentalType: 'NATIONAL_RENTAL',
    }

    expect(presentComplexDetailMarker(detail)).toEqual({
      agencyLabel: 'SH',
      areaLabel: '29.7~46.8㎡',
      monthlyRentLabel: '18만~31만 원',
      rentalTypeLabel: '국민임대',
    })
  })
})

function mapComplex(overrides: Partial<MapComplex> = {}): MapComplex {
  const raw = {
    agency: { code: 'LH', name: '한국토지주택공사' },
    complexId: 17,
    depositMax: 70_000_000,
    depositMin: 50_000_000,
    exclusiveAreaMax: 44.87,
    exclusiveAreaMin: 36.12,
    latitude: 37.56,
    longitude: 126.98,
    monthlyRentMax: 300_000,
    monthlyRentMin: 200_000,
    name: '서울가람 행복주택',
    rentalType: 'HAPPY_HOUSING',
  }
  return {
    ...raw,
    complexId: '17',
    raw,
    ...overrides,
  }
}
