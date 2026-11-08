import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { LocationPreparation } from './LocationPreparation'
import { getLocationPreparation, refreshInitialRegistration, supplementInitialRegistrationLocation,
  type LocationPreparationResponse } from './api'
import { lookupAddresses } from '../announcement-pdf/addressLookupApi'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(),
  getLocationPreparation: vi.fn(), refreshInitialRegistration: vi.fn(), supplementInitialRegistrationLocation: vi.fn(),
}))
vi.mock('../announcement-pdf/addressLookupApi', () => ({ lookupAddresses: vi.fn() }))
const response: LocationPreparationResponse = {
  counts: { READY: 2, INVALID_ADDRESS: 1, ADDRESS_NOT_FOUND: 1, COORDINATE_NOT_FOUND: 1, COORDINATE_CONVERSION_ERROR: 1 },
  targets: [{ sourceComplexIdentifier: '1:HAPPY_HOUSING', name: '주소 없는 단지', roadAddress: null,
    status: 'LOCATION_REQUIRED', detail: '주소 누락', housingComplexId: null, checkedAt: '2026-11-05T00:00:00Z',
    location: { address: null, latitude: null, longitude: null, origin: null, failure: 'INVALID_ADDRESS',
      checkedAt: '2026-11-05T00:00:00Z', checkedBy: null } }], totalElements: 4, page: 0, totalPages: 1,
}
const candidate = { roadAddress: '서울특별시 종로구 테스트로 1', jibunAddress: '', pnu: '1111010100100010000',
  legalDongCode: '1111010100', provinceCode: '11', cityCountyDistrictCode: '11110', latitude: 37.12345678, longitude: 127.12345678 }
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getLocationPreparation).mockResolvedValue(response)
  vi.mocked(supplementInitialRegistrationLocation).mockResolvedValue()
  vi.mocked(refreshInitialRegistration).mockResolvedValue()
  vi.mocked(lookupAddresses).mockResolvedValue([candidate])
})
async function edit() {
  fireEvent.click(await screen.findByRole('button', { name: '주소·좌표 보완' }))
  return screen.getByRole('form', { name: '주소·좌표 보완 입력' })
}
async function search() {
  fireEvent.change(screen.getByLabelText('주소 검색'), { target: { value: '서울 테스트로' } })
  fireEvent.click(screen.getByRole('button', { name: '주소 찾기' }))
  fireEvent.click(await screen.findByRole('button', { name: candidate.roadAddress }))
}
it('주소가 없는 단지도 실패 목록과 네 가지 사유별 집계에 포함한다', async () => {
  render(<LocationPreparation disabled={false} revision="" />)
  await screen.findByText('주소 없는 단지')
  expect(screen.getByText('원천 주소: 주소 미제공')).toBeVisible()
  const counts = screen.getByLabelText('주소·좌표 준비 집계')
  for (const label of ['주소 누락·형식 오류', '주소 불일치', '좌표 미제공', '좌표 변환 실패']) {
    expect(within(counts).getByText(label).nextSibling).toHaveTextContent('1곳')
  }
  expect(within(counts).getByText('좌표 준비 완료').nextSibling).toHaveTextContent('2곳')
})
it('기존 주소 검색으로 채우고 저장하면 ZIP 재업로드 없이 보완 목록과 다음 단계 상태를 갱신한다', async () => {
  const onChanged = vi.fn()
  render(<LocationPreparation disabled={false} revision="" onChanged={onChanged} />)
  await edit(); await search()
  vi.mocked(getLocationPreparation).mockResolvedValue({ ...response, targets: [], totalElements: 0, totalPages: 0 })
  fireEvent.click(screen.getByRole('button', { name: '보완값 저장' }))
  await waitFor(() => expect(supplementInitialRegistrationLocation).toHaveBeenCalledExactlyOnceWith(
    '1:HAPPY_HOUSING', candidate.roadAddress, candidate.latitude, candidate.longitude))
  await screen.findByText(/보완값을 저장했습니다/)
  const notice = screen.getByRole('status', { name: '주소·좌표 저장 완료' })
  expect(notice).toHaveTextContent('주소 없는 단지')
  await waitFor(() => expect(notice).toHaveFocus())
  expect(notice.compareDocumentPosition(screen.getByLabelText('주소·좌표 준비 집계')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(onChanged).toHaveBeenCalledOnce()
  await screen.findByRole('heading', { name: '보완할 단지 0곳' })
  expect(screen.queryByText('주소 없는 단지')).not.toBeInTheDocument()
})
it('주소를 바꾸면 검색한 좌표를 지우고 새 위도·경도를 입력하기 전에는 저장할 수 없다', async () => {
  render(<LocationPreparation disabled={false} revision="" />)
  await edit(); await search()
  fireEvent.change(screen.getByLabelText('보완 주소'), { target: { value: '서울특별시 종로구 다른로 2' } })
  expect(screen.getByLabelText('위도')).toHaveValue(null)
  expect(screen.getByLabelText('경도')).toHaveValue(null)
  expect(screen.getByRole('button', { name: '보완값 저장' })).toBeDisabled()
})
it('주소 검색 실패 후 직접 입력할 수 있고 저장 실패 시 입력을 유지해 재시도한다', async () => {
  vi.mocked(lookupAddresses).mockRejectedValue(new Error('주소 검색을 사용할 수 없습니다.'))
  vi.mocked(supplementInitialRegistrationLocation).mockRejectedValueOnce(new Error('저장 실패'))
  render(<LocationPreparation disabled={false} revision="" />)
  await edit()
  fireEvent.change(screen.getByLabelText('주소 검색'), { target: { value: '주소' } })
  fireEvent.click(screen.getByRole('button', { name: '주소 찾기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('주소와 좌표를 직접 입력하세요.')
  fireEvent.change(screen.getByLabelText('보완 주소'), { target: { value: candidate.roadAddress } })
  fireEvent.change(screen.getByLabelText('위도'), { target: { value: '37.5' } })
  fireEvent.change(screen.getByLabelText('경도'), { target: { value: '127' } })
  fireEvent.click(screen.getByRole('button', { name: '보완값 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('저장 실패')
  expect(screen.queryByRole('status', { name: '주소·좌표 저장 완료' })).not.toBeInTheDocument()
  expect(screen.getByLabelText('위도')).toHaveValue(37.5)
  fireEvent.click(screen.getByRole('button', { name: '보완값 저장' }))
  await screen.findByText(/보완값을 저장했습니다/)
})
it('저장 응답을 기다리는 동안 진행 상태를 표시하고 성공한 뒤에만 저장 완료를 알린다', async () => {
  let finishSave: () => void = () => {}
  vi.mocked(supplementInitialRegistrationLocation).mockReturnValue(new Promise<void>(resolve => { finishSave = resolve }))
  render(<LocationPreparation disabled={false} revision="" />)
  await edit(); await search()
  fireEvent.click(screen.getByRole('button', { name: '보완값 저장' }))
  expect(screen.getByRole('button', { name: '저장 중…' })).toBeDisabled()
  expect(screen.queryByRole('status', { name: '주소·좌표 저장 완료' })).not.toBeInTheDocument()
  await act(async () => finishSave())
  expect(await screen.findByRole('status', { name: '주소·좌표 저장 완료' })).toBeVisible()
})

it('저장은 성공하고 후속 목록 조회만 실패하면 저장 완료와 조회 오류를 구분해 알린다', async () => {
  render(<LocationPreparation disabled={false} revision="" />)
  await edit(); await search()
  vi.mocked(getLocationPreparation).mockRejectedValueOnce(new Error('목록 조회 실패'))
  fireEvent.click(screen.getByRole('button', { name: '보완값 저장' }))
  expect(await screen.findByRole('status', { name: '주소·좌표 저장 완료' })).toHaveTextContent('주소 없는 단지')
  expect(await screen.findByRole('alert')).toHaveTextContent('목록 조회 실패')
})

it('페이지 숫자 양옆의 아이콘 버튼으로 이동하고 첫 페이지와 마지막 페이지에서 이동을 막는다', async () => {
  vi.mocked(getLocationPreparation).mockImplementation(async page => ({ ...response, page, totalPages: 2 }))
  render(<LocationPreparation disabled={false} revision="" />)
  const pagination = await screen.findByRole('navigation', { name: '주소·좌표 보완 목록 페이지' })
  const previous = within(pagination).getByRole('button', { name: '이전 페이지' })
  const next = within(pagination).getByRole('button', { name: '다음 페이지' })
  expect(previous).toBeDisabled()
  expect(next).toBeEnabled()
  expect(previous).not.toHaveTextContent('이전')
  expect(next).not.toHaveTextContent('다음')
  expect(within(pagination).getByText('1 / 2')).toBeVisible()
  fireEvent.click(next)
  await waitFor(() => expect(within(screen.getByRole('navigation', { name: '주소·좌표 보완 목록 페이지' })).getByText('2 / 2')).toBeVisible())
  expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '이전 페이지' }))
  await waitFor(() => expect(getLocationPreparation).toHaveBeenLastCalledWith(0))
})
it('검색 중 검색어를 바꾸면 늦게 도착한 이전 후보를 표시하지 않는다', async () => {
  let resolve: (value: typeof candidate[]) => void = () => {}
  vi.mocked(lookupAddresses).mockReturnValue(new Promise(done => { resolve = done }))
  render(<LocationPreparation disabled={false} revision="" />)
  await edit()
  fireEvent.change(screen.getByLabelText('주소 검색'), { target: { value: '이전 검색어' } })
  fireEvent.click(screen.getByRole('button', { name: '주소 찾기' }))
  fireEvent.change(screen.getByLabelText('주소 검색'), { target: { value: '새 검색어' } })
  await act(async () => resolve([candidate]))
  expect(screen.queryByRole('list', { name: '주소 검색 결과' })).not.toBeInTheDocument()
})
it('조회 실패는 오류와 재조회로 안내한다', async () => {
  vi.mocked(getLocationPreparation).mockRejectedValueOnce(new Error('조회 실패'))
  render(<LocationPreparation disabled={false} revision="" />)
  expect(await screen.findByRole('alert')).toHaveTextContent('조회 실패')
  fireEvent.click(screen.getByRole('button', { name: '준비 현황 다시 조회' }))
  await screen.findByText('주소 없는 단지')
})
it('확인된 대상이 없으면 보완 완료로 단정하지 않고 자동 확인과 기존 원천 재확인을 안내한다', async () => {
  vi.mocked(getLocationPreparation).mockResolvedValue({
    counts: { READY: 0, INVALID_ADDRESS: 0, ADDRESS_NOT_FOUND: 0, COORDINATE_NOT_FOUND: 0, COORDINATE_CONVERSION_ERROR: 0 },
    targets: [], totalElements: 0, page: 0, totalPages: 0,
  })
  render(<LocationPreparation disabled={false} revision="" />)
  expect(await screen.findByText(/아직 주소·좌표를 확인한 신규 단지가 없습니다/)).toBeVisible()
  expect(screen.queryByRole('heading', { name: '보완할 단지 0곳' })).not.toBeInTheDocument()
  expect(screen.getByText(/원천 수집·ZIP 업로드 후에는 자동으로 확인합니다/)).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '주소·좌표 상태 확인' }))
  await waitFor(() => expect(refreshInitialRegistration).toHaveBeenCalledOnce())
})
