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
  render(<NewComplexRegistration index={0} defaultName="인천부평" rentalType="HAPPY_HOUSING" agencyCode="LH" onSelectExisting={vi.fn()} onCreated={onCreated} />)
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
  render(<NewComplexRegistration index={0} defaultName="인천부평(도시재생뉴딜) 행복주택" rentalType="HAPPY_HOUSING" agencyCode="LH" onSelectExisting={vi.fn()} onCreated={onCreated}
    myhome={{ name: '인천부평(도시재생뉴딜)', address: '인천광역시 부평구 부평동 65-17', pnu: '2823710100100650017', households: 350, heating: '개별난방', building: '아파트' }} />)
  const form = screen.getByRole('group', { name: '공급행 1 새 단지 등록' })
  expect(within(form).getByLabelText('주소(도로명 또는 지번)')).toHaveValue('인천광역시 부평구 부평동 65-17')
  expect(within(form).getByLabelText('PNU')).toHaveValue('2823710100100650017')
  expect(within(form).getByLabelText('전체 세대수')).toHaveValue(350)
  expect(within(form).getByLabelText('주차대수')).toHaveValue(null)
  // 공고에 공급유형이 있으면 고르게 하지 않는다.
  expect(within(form).queryByLabelText('공급유형')).not.toBeInTheDocument()
  fireEvent.change(within(form).getByLabelText('위도'), { target: { value: '37.502996' } })
  fireEvent.change(within(form).getByLabelText('경도'), { target: { value: '126.719475' } })
  fireEvent.click(within(form).getByRole('button', { name: '단지 등록하고 연결' }))
  await waitFor(() => expect(onCreated).toHaveBeenCalled())
  expect(registration.createHousingComplex).toHaveBeenCalledWith(expect.objectContaining({ rentalType: 'HAPPY_HOUSING', agencyCode: 'LH', totalHouseholdCount: 350, totalParkingCount: null, heatingType: 'INDIVIDUAL', buildingType: 'APARTMENT' }))
})

it('지번을 모르면 PNU를 비워 두고 고른 주소 검색 결과의 행정구역 코드로 등록한다', async () => {
  lookup.lookupAddresses.mockResolvedValue([{ roadAddress: '경기도 화성시 봉담읍 동화길 10', jibunAddress: '', pnu: '',
    legalDongCode: '4159025021', provinceCode: '41', cityCountyDistrictCode: '41590', latitude: 37.2, longitude: 126.9 }])
  registration.createHousingComplex.mockResolvedValue({ housingComplexId: 3504, name: '인천부평' })
  const { form, onCreated } = renderForm()
  search(form, '봉담읍 동화길 10')
  fireEvent.click(await within(form).findByRole('button', { name: /동화길 10/ }))
  fireEvent.change(within(form).getByLabelText('전체 세대수'), { target: { value: '350' } })
  fireEvent.click(within(form).getByRole('button', { name: '단지 등록하고 연결' }))
  await waitFor(() => expect(onCreated).toHaveBeenCalledWith({ id: 3504, name: '인천부평' }))
  expect(registration.createHousingComplex).toHaveBeenCalledWith(expect.objectContaining({ address: expect.objectContaining({
    pnu: null, legalDongCode: '4159025021', provinceCode: '41', cityCountyDistrictCode: '41590' }) }))
})

it('주소를 직접 고치면 검색 결과의 행정구역 코드를 버리고, PNU도 없으면 등록을 막는다', async () => {
  lookup.lookupAddresses.mockResolvedValue([{ roadAddress: '경기도 화성시 봉담읍 동화길 10', jibunAddress: '', pnu: '',
    legalDongCode: '4159025021', provinceCode: '41', cityCountyDistrictCode: '41590', latitude: 37.2, longitude: 126.9 }])
  const { form } = renderForm()
  search(form, '봉담읍 동화길 10')
  fireEvent.click(await within(form).findByRole('button', { name: /동화길 10/ }))
  fireEvent.change(within(form).getByLabelText('전체 세대수'), { target: { value: '350' } })
  fireEvent.change(within(form).getByLabelText('주소(도로명 또는 지번)'), { target: { value: '경기도 화성시 봉담읍 동화길 12' } })
  expect(within(form).getByRole('button', { name: '단지 등록하고 연결' })).toBeDisabled()
  expect(within(form).getByText('행정구역 코드를 정하려면 주소 검색 결과를 고르거나 PNU를 입력하세요.')).toBeInTheDocument()
})

it('검색 결과를 고른 뒤 PNU를 고치면 행정구역 코드는 그 PNU 앞자리로 정한다', async () => {
  lookup.lookupAddresses.mockResolvedValue([{ roadAddress: '', jibunAddress: '인천광역시 부평구 부평동 65-17', pnu: '2823710100100650017',
    legalDongCode: '2823710100', provinceCode: '28', cityCountyDistrictCode: '28237', latitude: 37.502996, longitude: 126.719475 }])
  registration.createHousingComplex.mockResolvedValue({ housingComplexId: 3505, name: '인천부평' })
  const { form, onCreated } = renderForm()
  search(form, '부평동 65-17')
  fireEvent.click(await within(form).findByRole('button', { name: /부평동 65-17/ }))
  fireEvent.change(within(form).getByLabelText('PNU'), { target: { value: '4159025021100100000' } })
  fireEvent.change(within(form).getByLabelText('전체 세대수'), { target: { value: '350' } })
  fireEvent.click(within(form).getByRole('button', { name: '단지 등록하고 연결' }))
  await waitFor(() => expect(onCreated).toHaveBeenCalled())
  expect(registration.createHousingComplex).toHaveBeenCalledWith(expect.objectContaining({ address: expect.objectContaining({
    pnu: '4159025021100100000', legalDongCode: '4159025021', cityCountyDistrictCode: '41590' }) }))
})

const exactAddress = { roadAddress: '강원특별자치도 평창군 용평면 새이들길 23', jibunAddress: '강원특별자치도 평창군 용평면 장평리 317-2', pnu: '5176035027103170002',
  legalDongCode: '5176035027', provinceCode: '51', cityCountyDistrictCode: '51760', latitude: 37.582415, longitude: 128.41234, matchesKeyword: true }

it('서버가 추출 주소와 같다고 판정한 후보가 하나뿐이면 좌표를 자동으로 채우고 세대수 미확인 상태로 등록한다', async () => {
  lookup.lookupAddresses.mockResolvedValue([exactAddress])
  registration.createHousingComplex.mockResolvedValue({ housingComplexId: 12, name: '평창장평' })
  const onCreated = vi.fn()
  render(<NewComplexRegistration index={0} defaultName="평창장평" defaultAddress={exactAddress.roadAddress} rentalType="INTEGRATED_PUBLIC_RENTAL" agencyCode="LH" onSelectExisting={vi.fn()} onCreated={onCreated} />)
  const form = screen.getByRole('group', { name: '공급행 1 새 단지 등록' })
  const submit = within(form).getByRole('button', { name: '단지 등록하고 연결' })
  await waitFor(() => expect(submit).toBeEnabled())
  expect(within(form).getByLabelText('위도')).toHaveValue(37.582415)
  fireEvent.click(submit)
  await waitFor(() => expect(onCreated).toHaveBeenCalledWith({ id: 12, name: '평창장평' }))
  expect(registration.createHousingComplex).toHaveBeenCalledWith(expect.objectContaining({ totalHouseholdCount: null, address: expect.objectContaining({ pnu: exactAddress.pnu }) }))
})

it('주소 후보가 모호하면 자동 선택하지 않고 좌표 없는 새 후보에 이전 좌표를 쓰지 않는다', async () => {
  lookup.lookupAddresses.mockResolvedValue([exactAddress, { ...exactAddress, pnu: '5176035027103170003', latitude: null, longitude: null }])
  const onCreated = vi.fn()
  render(<NewComplexRegistration index={0} defaultName="평창장평" defaultAddress={exactAddress.roadAddress} rentalType="INTEGRATED_PUBLIC_RENTAL" agencyCode="LH" onSelectExisting={vi.fn()} onCreated={onCreated} />)
  const form = screen.getByRole('group', { name: '공급행 1 새 단지 등록' })
  const options = await within(form).findAllByRole('button', { name: /새이들길 23/ })
  expect(within(form).getByLabelText('위도')).toHaveValue(null)
  fireEvent.click(options[0])
  expect(within(form).getByLabelText('위도')).toHaveValue(37.582415)
  search(form, '새이들길')
  fireEvent.click(await within(form).findByRole('button', { name: /좌표 없음/ }))
  expect(within(form).getByLabelText('위도')).toHaveValue(null)
  expect(within(form).getByLabelText('경도')).toHaveValue(null)
  expect(within(form).getByRole('button', { name: '단지 등록하고 연결' })).toBeDisabled()
})

it('늦게 끝난 최초 주소 검색이 직접 고친 주소와 좌표를 덮어쓰지 않는다', async () => {
  let resolve!: (candidates: typeof exactAddress[]) => void
  lookup.lookupAddresses.mockReturnValue(new Promise<typeof exactAddress[]>(done => { resolve = done }))
  registration.createHousingComplex.mockResolvedValue({ housingComplexId: 13, name: '다른 단지' })
  const onCreated = vi.fn()
  render(<NewComplexRegistration index={0} defaultName="다른 단지" defaultAddress={exactAddress.roadAddress} rentalType="HAPPY_HOUSING" agencyCode="LH" onSelectExisting={vi.fn()} onCreated={onCreated} />)
  const form = screen.getByRole('group', { name: '공급행 1 새 단지 등록' })
  for (const [label, value] of [['주소(도로명 또는 지번)', '서울특별시 중구 세종대로 110'], ['PNU', '1114010100100010000'], ['위도', '37.5'], ['경도', '127']]) {
    fireEvent.change(within(form).getByLabelText(label), { target: { value } })
  }
  resolve([exactAddress])
  await waitFor(() => expect(lookup.lookupAddresses.mock.calls[0][1].aborted).toBe(true))
  fireEvent.click(within(form).getByRole('button', { name: '단지 등록하고 연결' }))
  await waitFor(() => expect(onCreated).toHaveBeenCalled())
  expect(registration.createHousingComplex).toHaveBeenCalledWith(expect.objectContaining({ address: expect.objectContaining({ roadAddress: '서울특별시 중구 세종대로 110', pnu: '1114010100100010000', latitude: 37.5, longitude: 127 }) }))
})

it('확인한 뒤 검색 주소를 바꾸면 이전 주소로 등록하지 못하게 하고 새 검색 결과로만 등록한다', async () => {
  lookup.lookupAddresses.mockResolvedValueOnce([exactAddress]).mockResolvedValueOnce([{ ...exactAddress, roadAddress: '서울특별시 중구 세종대로 110', pnu: '1114010100100010000', legalDongCode: '1114010100', provinceCode: '11', cityCountyDistrictCode: '11140', latitude: 37.5, longitude: 127 }])
  registration.createHousingComplex.mockResolvedValue({ housingComplexId: 14, name: '단지' })
  render(<NewComplexRegistration index={0} defaultName="단지" defaultAddress={exactAddress.roadAddress} rentalType="HAPPY_HOUSING" agencyCode="LH" onSelectExisting={vi.fn()} onCreated={vi.fn()} />)
  const form = screen.getByRole('group', { name: '공급행 1 새 단지 등록' })
  await waitFor(() => expect(within(form).getByRole('button', { name: '단지 등록하고 연결' })).toBeEnabled())
  fireEvent.change(within(form).getByLabelText('주소 검색'), { target: { value: '서울특별시 중구 세종대로 110' } })
  expect(within(form).getByRole('button', { name: '단지 등록하고 연결' })).toBeDisabled()
  expect(within(form).queryByRole('status')).not.toBeInTheDocument()
  fireEvent.click(within(form).getByRole('button', { name: '검색' }))
  await waitFor(() => expect(within(form).getByRole('button', { name: '단지 등록하고 연결' })).toBeEnabled())
  fireEvent.click(within(form).getByRole('button', { name: '단지 등록하고 연결' }))
  await waitFor(() => expect(registration.createHousingComplex).toHaveBeenCalledWith(expect.objectContaining({ address: expect.objectContaining({ roadAddress: '서울특별시 중구 세종대로 110', latitude: 37.5, longitude: 127 }) })))
})

it('공고문이 시·도를 줄여 써도 서버가 같은 주소로 판정하면 고르지 않아도 주소·좌표를 채운다', async () => {
  lookup.lookupAddresses.mockResolvedValue([{ ...exactAddress, roadAddress: '서울특별시 중구 세종대로 110', pnu: '1114010300100310000', legalDongCode: '1114010300', provinceCode: '11', cityCountyDistrictCode: '11140' },
    { ...exactAddress, roadAddress: '서울특별시 중구 세종대로 110-1', matchesKeyword: false }])
  render(<NewComplexRegistration index={0} defaultName="단지" defaultAddress="서울시 중구 세종대로 110" rentalType="HAPPY_HOUSING" agencyCode="LH" onSelectExisting={vi.fn()} onCreated={vi.fn()} />)
  const form = screen.getByRole('group', { name: '공급행 1 새 단지 등록' })
  expect(await within(form).findByRole('status')).toHaveTextContent('공고문 주소와 같은 주소를 찾아 채웠습니다.')
  expect(within(form).getByLabelText('주소(도로명 또는 지번)')).toHaveValue('서울특별시 중구 세종대로 110')
  expect(within(form).queryByRole('list', { name: '주소 검색 결과' })).not.toBeInTheDocument()
  expect(within(form).getByRole('button', { name: '단지 등록하고 연결' })).toBeEnabled()
})

it('같다고 판정한 후보에 좌표가 없으면 주소·PNU는 채우고 좌표만 직접 입력하라고 알린다', async () => {
  lookup.lookupAddresses.mockResolvedValue([{ ...exactAddress, latitude: null, longitude: null }])
  render(<NewComplexRegistration index={0} defaultName="평창장평" defaultAddress={exactAddress.roadAddress} rentalType="HAPPY_HOUSING" agencyCode="LH" onSelectExisting={vi.fn()} onCreated={vi.fn()} />)
  const form = screen.getByRole('group', { name: '공급행 1 새 단지 등록' })
  expect(await within(form).findByRole('status')).toHaveTextContent('좌표는 검색 결과에 없어 직접 입력해야 합니다.')
  expect(within(form).getByLabelText('PNU')).toHaveValue(exactAddress.pnu)
  expect(within(form).getByText('이 주소는 검색 결과에 좌표가 없습니다. 주소 상세에서 위도·경도를 입력하세요.')).toBeInTheDocument()
})

it.each([
  ['같은 법정동이면 원천행 필지를 유지한다', '5176035027100100000', '5176035027100100000'],
  ['다른 법정동이면 검색 결과 필지를 쓴다', '1114010300100310000', exactAddress.pnu],
])('주소를 정할 때 %s', async (_, sourcePnu, expected) => {
  lookup.lookupAddresses.mockResolvedValue([exactAddress])
  registration.createHousingComplex.mockResolvedValue({ housingComplexId: 15, name: '평창장평' })
  render(<NewComplexRegistration index={0} defaultName="평창장평" defaultAddress={exactAddress.roadAddress} defaultPnu={sourcePnu} rentalType="HAPPY_HOUSING" agencyCode="LH" onSelectExisting={vi.fn()} onCreated={vi.fn()} />)
  const form = screen.getByRole('group', { name: '공급행 1 새 단지 등록' })
  await within(form).findByRole('status')
  fireEvent.click(within(form).getByRole('button', { name: '단지 등록하고 연결' }))
  await waitFor(() => expect(registration.createHousingComplex).toHaveBeenCalledWith(expect.objectContaining({ address: expect.objectContaining({ pnu: expected, legalDongCode: expected.slice(0, 10) }) })))
})

it('서버가 알려 준 같은 곳의 등록 단지를 새 등록보다 먼저 보이고, 고르면 새로 만들지 않고 그 단지에 연결한다', async () => {
  lookup.lookupAddresses.mockResolvedValue([{ ...exactAddress, registeredComplexes: [
    { housingComplexId: 41, name: '평창장평 행복주택', roadAddress: '강원 평창군 용평면 새이들길 23', rentalType: 'HAPPY_HOUSING' }] }])
  const onSelectExisting = vi.fn()
  render(<NewComplexRegistration index={0} defaultName="평창장평" defaultAddress={exactAddress.roadAddress} rentalType="HAPPY_HOUSING" agencyCode="LH" onSelectExisting={onSelectExisting} onCreated={vi.fn()} />)
  const form = screen.getByRole('group', { name: '공급행 1 새 단지 등록' })
  const existing = await within(form).findByRole('group', { name: '같은 주소의 등록된 단지' })
  expect(existing).toHaveTextContent('평창장평 행복주택 · 행복주택 · 강원 평창군 용평면 새이들길 23')
  expect(within(form).getByRole('button', { name: '다른 단지라 새로 등록하고 연결' })).toBeEnabled()
  fireEvent.click(within(existing).getByRole('button', { name: '평창장평 행복주택에 연결' }))
  expect(onSelectExisting).toHaveBeenCalledWith({ id: 41, name: '평창장평 행복주택' })
  expect(registration.createHousingComplex).not.toHaveBeenCalled()
  // 다른 주소를 고르면 이전 주소의 등록 단지 안내는 사라진다.
  fireEvent.change(within(form).getByLabelText('주소 검색'), { target: { value: '다른 주소' } })
  expect(within(form).queryByRole('group', { name: '같은 주소의 등록된 단지' })).not.toBeInTheDocument()
})

it('원천 필지를 함께 보내 그 필지의 등록 단지도 서버가 찾게 한다', async () => {
  lookup.lookupAddresses.mockResolvedValue([])
  render(<NewComplexRegistration index={0} defaultName="평창장평" defaultAddress={exactAddress.roadAddress} defaultPnu="5176035027100100000" rentalType="HAPPY_HOUSING" agencyCode="LH" onSelectExisting={vi.fn()} onCreated={vi.fn()} />)
  await waitFor(() => expect(lookup.lookupAddresses).toHaveBeenCalledWith(exactAddress.roadAddress, expect.anything(), '5176035027100100000'))
})

it('공고에 공급유형이 없으면 고르기 전까지 등록을 막고, 고른 공급유형으로 등록한다', async () => {
  lookup.lookupAddresses.mockResolvedValue([{ roadAddress: '경기도 의정부시 용민로 10', jibunAddress: '경기도 의정부시 용현동 567', pnu: '4115010400105670000',
    legalDongCode: '4115010400', provinceCode: '41', cityCountyDistrictCode: '41150', latitude: 37.75, longitude: 127.08 }])
  registration.createHousingComplex.mockResolvedValue({ housingComplexId: 9001, name: '탑석 센트럴 자이' })
  const onCreated = vi.fn()
  render(<NewComplexRegistration index={0} defaultName="탑석 센트럴 자이" defaultHouseholds={70} rentalType="" agencyCode="GH" onSelectExisting={vi.fn()} onCreated={onCreated} />)
  const form = screen.getByRole('group', { name: '공급행 1 새 단지 등록' })
  search(form, '용민로 10')
  fireEvent.click(await within(form).findByRole('button', { name: /용민로 10/ }))
  expect(within(form).getByRole('button', { name: '단지 등록하고 연결' })).toBeDisabled()
  expect(within(form).getByText('공급유형을 고르세요.')).toBeInTheDocument()
  fireEvent.change(within(form).getByLabelText('공급유형'), { target: { value: 'LONG_TERM_JEONSE' } })
  fireEvent.click(within(form).getByRole('button', { name: '단지 등록하고 연결' }))
  await waitFor(() => expect(onCreated).toHaveBeenCalledWith({ id: 9001, name: '탑석 센트럴 자이' }))
  expect(registration.createHousingComplex).toHaveBeenCalledWith(expect.objectContaining({ rentalType: 'LONG_TERM_JEONSE', agencyCode: 'GH' }))
})

it('공고에 공급유형이 있으면(LH·GH) 고르게 하지 않고 그 공급유형으로 등록한다', () => {
  render(<NewComplexRegistration index={0} defaultName="탑석 센트럴 자이" rentalType="LONG_TERM_JEONSE" agencyCode="GH" onSelectExisting={vi.fn()} onCreated={vi.fn()} />)
  expect(within(screen.getByRole('group', { name: '공급행 1 새 단지 등록' })).queryByLabelText('공급유형')).not.toBeInTheDocument()
})
