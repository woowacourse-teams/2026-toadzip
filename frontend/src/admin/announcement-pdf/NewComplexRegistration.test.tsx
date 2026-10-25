import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { NewComplexRegistration } from './NewComplexRegistration'
import { ManagementError } from '../management/api'
import { AdminRegistrationApiError } from '../registration/api'
const lookup = vi.hoisted(() => ({ lookupAddresses: vi.fn() }))
vi.mock('./addressLookupApi', () => lookup)
const registration = vi.hoisted(() => ({ createHousingComplex: vi.fn() }))
vi.mock('../registration/api', async original => ({ ...await original<typeof import('../registration/api')>(), ...registration }))
beforeEach(() => vi.clearAllMocks())

function renderForm() {
  const onCreated = vi.fn()
  render(<NewComplexRegistration index={0} defaultName="인천부평" rentalType="HAPPY_HOUSING" agencyCode="LH" onCreated={onCreated} />)
  return { form: screen.getByRole('group', { name: '공급행 1 새 단지 등록' }), onCreated }
}
function search(form: HTMLElement, keyword: string) {
  fireEvent.change(within(form).getByLabelText('주소 검색'), { target: { value: keyword } })
  fireEvent.click(within(form).getByRole('button', { name: '검색' }))
}

it('주소 검색 후보를 고르면 주소·PNU·좌표를 채워 등록에 쓴다', async () => {
  lookup.lookupAddresses.mockResolvedValue([{ roadAddress: '', jibunAddress: '인천광역시 부평구 부평동 65-17', pnu: '2823710100100650017',
    legalDongCode: '2823710100', provinceCode: '28', cityCountyDistrictCode: '28237', latitude: 37.502996, longitude: 126.719475 }])
  registration.createHousingComplex.mockResolvedValue({ housingComplexId: 3503, name: '인천부평' })
  const { form, onCreated } = renderForm()
  search(form, '부평동 65-17')
  fireEvent.click(await within(form).findByRole('button', { name: /부평동 65-17/ }))
  expect(within(form).getByLabelText('주소(도로명 또는 지번)')).toHaveValue('인천광역시 부평구 부평동 65-17')
  expect(within(form).getByLabelText('PNU')).toHaveValue('2823710100100650017')
  expect(within(form).getByLabelText('위도')).toHaveValue(37.502996)
  expect(within(form).queryByRole('list', { name: '주소 검색 결과' })).not.toBeInTheDocument()
  fireEvent.change(within(form).getByLabelText('전체 세대수'), { target: { value: '350' } })
  fireEvent.change(within(form).getByLabelText('주차대수'), { target: { value: '112' } })
  fireEvent.click(within(form).getByRole('button', { name: '단지 등록하고 연결' }))
  await waitFor(() => expect(onCreated).toHaveBeenCalledWith({ id: 3503, name: '인천부평' }))
  expect(registration.createHousingComplex).toHaveBeenCalledWith(expect.objectContaining({ address: expect.objectContaining({
    roadAddress: '인천광역시 부평구 부평동 65-17', pnu: '2823710100100650017', cityCountyDistrictCode: '28237', longitude: 126.719475 }) }))
})

it('좌표가 없는 후보는 좌표 칸을 그대로 두고 직접 입력하게 한다', async () => {
  lookup.lookupAddresses.mockResolvedValue([{ roadAddress: '인천광역시 부평구 경원대로 1', jibunAddress: '인천광역시 부평구 부평동 1', pnu: '2823710100100010000',
    legalDongCode: '2823710100', provinceCode: '28', cityCountyDistrictCode: '28237', latitude: null, longitude: null }])
  const { form } = renderForm()
  search(form, '경원대로 1')
  fireEvent.click(await within(form).findByRole('button', { name: /좌표 없음/ }))
  expect(within(form).getByLabelText('주소(도로명 또는 지번)')).toHaveValue('인천광역시 부평구 경원대로 1')
  expect(within(form).getByLabelText('위도')).toHaveValue(null)
  expect(within(form).getByRole('button', { name: '단지 등록하고 연결' })).toBeDisabled()
})

it('주소 검색을 쓸 수 없으면 서버 문구와 함께 직접 입력을 안내한다', async () => {
  lookup.lookupAddresses.mockRejectedValue(new ManagementError('주소 검색을 사용할 수 없습니다.', 503, {}, 'ADDRESS_LOOKUP_UNAVAILABLE'))
  const { form } = renderForm()
  search(form, '부평동')
  expect(await within(form).findByRole('alert')).toHaveTextContent('주소 검색을 사용할 수 없습니다. 주소·PNU·좌표를 직접 입력하세요.')
  expect(within(form).getByLabelText('PNU')).toBeEnabled()
})

it('주소 검색 좌표(소수 여덟 자리)와 직접 입력한 좌표는 서버가 받는 여섯 자리로 맞춰 보낸다', async () => {
  lookup.lookupAddresses.mockResolvedValue([{ roadAddress: '인천광역시 부평구 부평대로 3', jibunAddress: '인천광역시 부평구 부평동 1', pnu: '2823710100100010000',
    legalDongCode: '2823710100', provinceCode: '28', cityCountyDistrictCode: '28237', latitude: 37.52022573, longitude: 126.7221496 }])
  registration.createHousingComplex.mockResolvedValue({ housingComplexId: 1, name: '인천부평' })
  const { form } = renderForm()
  search(form, '부평대로 3')
  fireEvent.click(await within(form).findByRole('button', { name: /부평대로 3/ }))
  expect(within(form).getByLabelText('위도')).toHaveValue(37.520226)
  fireEvent.change(within(form).getByLabelText('경도'), { target: { value: '126.7194780' } })
  fireEvent.change(within(form).getByLabelText('전체 세대수'), { target: { value: '350' } })
  fireEvent.change(within(form).getByLabelText('주차대수'), { target: { value: '112' } })
  fireEvent.click(within(form).getByRole('button', { name: '단지 등록하고 연결' }))
  await waitFor(() => expect(registration.createHousingComplex).toHaveBeenCalledWith(expect.objectContaining({
    address: expect.objectContaining({ latitude: 37.520226, longitude: 126.719478 }) })))
})

it('등록이 거절되면 서버가 알려 준 항목별 사유를 함께 보여 준다', async () => {
  registration.createHousingComplex.mockRejectedValue(new AdminRegistrationApiError(400, '요청값이 올바르지 않습니다.', { totalHouseholdCount: '0 이상이어야 합니다.' }))
  const { form } = renderForm()
  for (const [label, value] of [['주소(도로명 또는 지번)', '인천광역시 부평구 부평동 65-17'], ['PNU', '2823710100100650017'], ['위도', '37.502996'], ['경도', '126.719475'], ['전체 세대수', '350'], ['주차대수', '112']]) {
    fireEvent.change(within(form).getByLabelText(label), { target: { value } })
  }
  fireEvent.click(within(form).getByRole('button', { name: '단지 등록하고 연결' }))
  expect(await within(form).findByRole('alert')).toHaveTextContent('요청값이 올바르지 않습니다. 전체 세대수: 0 이상이어야 합니다.')
})

it('주차대수는 비워 둘 수 있고, 공고문에 없는 주소·PNU·세대수는 수집한 마이홈 공고 정보로 채워 난방·주택유형과 함께 보낸다', async () => {
  registration.createHousingComplex.mockResolvedValue({ housingComplexId: 9, name: '인천부평' })
  const onCreated = vi.fn()
  render(<NewComplexRegistration index={0} defaultName="인천부평(도시재생뉴딜) 행복주택" rentalType="HAPPY_HOUSING" agencyCode="LH" onCreated={onCreated}
    myhome={{ name: '인천부평(도시재생뉴딜)', address: '인천광역시 부평구 부평동 65-17', pnu: '2823710100100650017', households: 350, heating: '개별난방', building: '아파트' }} />)
  const form = screen.getByRole('group', { name: '공급행 1 새 단지 등록' })
  expect(within(form).getByLabelText('주소(도로명 또는 지번)')).toHaveValue('인천광역시 부평구 부평동 65-17')
  expect(within(form).getByLabelText('PNU')).toHaveValue('2823710100100650017')
  expect(within(form).getByLabelText('전체 세대수')).toHaveValue(350)
  expect(within(form).getByLabelText('주차대수')).toHaveValue(null)
  fireEvent.change(within(form).getByLabelText('위도'), { target: { value: '37.502996' } })
  fireEvent.change(within(form).getByLabelText('경도'), { target: { value: '126.719475' } })
  fireEvent.click(within(form).getByRole('button', { name: '단지 등록하고 연결' }))
  await waitFor(() => expect(onCreated).toHaveBeenCalled())
  expect(registration.createHousingComplex).toHaveBeenCalledWith(expect.objectContaining({ totalHouseholdCount: 350, totalParkingCount: null, heatingType: 'INDIVIDUAL', buildingType: 'APARTMENT' }))
})
