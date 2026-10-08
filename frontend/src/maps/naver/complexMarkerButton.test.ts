import { describe, expect, it } from 'vitest'
import { createComplexMarkerButton } from './complexMarkerButton.ts'
import { presentMapComplexMarker } from '../../public-housing/presentation/mapMarkerPresentation.ts'
import type { MapComplex } from '../../public-housing/model/publicHousing.ts'

function marker(applicationStatus: string | null = null) {
  const raw = {
    complexId: 17, name: '서울가람 행복주택', latitude: 37.5, longitude: 127,
    agency: { code: 'LH', name: '한국토지주택공사' }, rentalType: 'HAPPY_HOUSING',
    exclusiveAreaMin: 36.12, exclusiveAreaMax: 44.87,
    depositMin: 24000000, depositMax: null, monthlyRentMin: 180000, monthlyRentMax: null,
    applicationStatus,
  }
  const complex: MapComplex = { ...raw, complexId: '17', raw }
  return { ...presentMapComplexMarker(complex), id: '17', name: raw.name, latitude: 37.5, longitude: 127 }
}

describe('단지 마커 상세 정보', () => {
  it('기존 임대 정보와 함께 단지 이름과 전용면적을 제공한다', () => {
    const button = createComplexMarkerButton(marker())
    expect(button).toHaveTextContent('서울가람 행복주택')
    expect(button).toHaveTextContent('전용 36.12 ~ 44.87㎡')
    expect(button).toHaveTextContent('LH')
    expect(button).toHaveTextContent('행복')
    expect(button).toHaveTextContent('보2.4천~')
    expect(button).toHaveTextContent('월18만~')
    expect(button).toHaveAccessibleName(/전용면적 36.12 ~ 44.87㎡/)
  })

  it.each([['BEFORE_APPLICATION', '공고중'], ['APPLYING', '접수중']])(
    '%s인 단지는 펼쳐진 마커에 %s을 표시한다', (status, label) => {
      const button = createComplexMarkerButton(marker(status))
      expect(button).toHaveTextContent(label)
      expect(button).toHaveAccessibleName(expect.stringContaining(label))
    },
  )

  it.each([null, 'CLOSED', 'CANCELLED', 'CONDITIONAL', 'UNKNOWN'])(
    '%s 상태에는 공고중·접수중 배지를 표시하지 않는다', status => {
      const button = createComplexMarkerButton(marker(status))
      expect(button).not.toHaveTextContent(/공고중|접수중/)
    },
  )
})
