import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { RegionFilterFields } from './RegionFilterFields'
import { districtRegionOptionsForProvince } from '../model/publicHousingRegion'
import { topicDraftFromForm } from './searchFilterForm'

const regions = [
  { regionCode: '11110', provinceName: '서울특별시', districtName: '종로구', displayName: '서울특별시 종로구' },
  { regionCode: '1111010100', provinceName: '서울특별시', districtName: '종로구', displayName: '서울특별시 종로구 청운동' },
  { regionCode: '11140', provinceName: '서울특별시', districtName: '중구', displayName: '서울특별시 중구' },
]

describe('읍면동 지역 선택', () => {
  it('저장된 읍면동을 복원하고 동을 제출하며 상위 구 변경 시 초기화한다', async () => {
    const { container } = render(<form><RegionFilterFields
      initialRegionCode="1111010100" messageId="region-message" loadingMessage="불러오는 중"
      repository={{ search: async () => regions }} styles={{ field: '', regionFields: '', regionError: '' }}
    /></form>)
    await screen.findByRole('option', { name: '청운동' })
    expect(screen.getByLabelText('시·군·구')).toHaveValue('11110')
    expect(screen.getByLabelText('읍·면·동')).toHaveValue('1111010100')
    expect(topicDraftFromForm('region', new FormData(container.querySelector('form')!)))
      .toEqual({ regionCode: '1111010100' })
    fireEvent.change(screen.getByLabelText('시·군·구'), { target: { value: '11140' } })
    expect(screen.getByLabelText('읍·면·동')).toHaveValue('')
    expect(topicDraftFromForm('region', new FormData(container.querySelector('form')!)))
      .toEqual({ regionCode: '11140' })
    fireEvent.change(screen.getByLabelText('시·도'), { target: { value: '' } })
    await waitFor(() => expect(screen.getByLabelText('읍·면·동')).toBeDisabled())
  })
})

const yongin = [
  { regionCode: '41460', provinceName: '경기도', districtName: '용인시', displayName: '경기도 용인시' },
  { regionCode: '41463', provinceName: '경기도', districtName: '용인시 기흥구', displayName: '경기도 용인시 기흥구' },
  { regionCode: '41465', provinceName: '경기도', districtName: '용인시 수지구', displayName: '경기도 용인시 수지구' },
  { regionCode: '4146510100', provinceName: '경기도', districtName: '용인시 수지구', displayName: '경기도 용인시 수지구 풍덕천동' },
]

it('공고에서 하위 구 전체를 선택하고 기존 단지 필터의 부모 도시 목록은 유지한다', async () => {
  expect(districtRegionOptionsForProvince(yongin, '41').map((region) => region.regionCode)).toEqual(['41460'])
  expect(districtRegionOptionsForProvince(yongin, '41', true).map((region) => region.regionCode)).toEqual(['41460', '41463', '41465'])
  const { container } = render(<form><RegionFilterFields includeSubdistricts initialRegionCode="41" messageId="region-message" loadingMessage="불러오는 중"
    repository={{ search: async () => yongin }} styles={{ field: '', regionFields: '', regionError: '' }} /></form>)
  await screen.findByRole('option', { name: '용인시 수지구' })
  fireEvent.change(screen.getByLabelText('시·군·구'), { target: { value: '41465' } })
  expect(screen.getByLabelText('읍·면·동')).toHaveValue('')
  expect(topicDraftFromForm('region', new FormData(container.querySelector('form')!))).toEqual({ regionCode: '41465' })
})
