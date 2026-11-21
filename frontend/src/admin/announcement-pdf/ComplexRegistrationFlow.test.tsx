import { useState } from 'react'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { ComplexDecisions } from './ComplexDecision'
import { scenario } from './fixtures/workspace'
import { assembledSheet, createReview } from './review'

const api = vi.hoisted(() => ({ requestManagementApi: vi.fn(), getManagementDetail: vi.fn(), getManagementPage: vi.fn() }))
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(), ...api }))
const registration = vi.hoisted(() => ({ createHousingComplex: vi.fn() }))
vi.mock('../registration/api', async original => ({ ...await original<typeof import('../registration/api')>(), ...registration }))
const lookup = vi.hoisted(() => ({ lookupAddresses: vi.fn() }))
vi.mock('./addressLookupApi', () => lookup)

beforeEach(() => vi.resetAllMocks())

it('같은 이름의 단지가 없어 새 단지를 등록하면 PDF 단지의 주택형 모두를 그 단지에 연결하고, 주택타입은 저장할 때 새로 만들게 한다', async () => {
  const { review: original, workspace } = scenario('hansville')
  const name = original.extraction.sheet.housingTypes[0].complexName!
  const extraction = { ...original.extraction, complexes: [{ name, address: '서울특별시 중구 세종대로 110', totalHouseholdCount: null, parkingCount: null }] }
  let latest = createReview(extraction, workspace, {})
  api.getManagementDetail.mockResolvedValue({ ...workspace.detail, data: { ...workspace.detail.data, address: { pnu: '1114010100100010000' } } })
  api.getManagementPage.mockResolvedValue({ items: [] })
  api.requestManagementApi.mockImplementation(async (_path: string, method: string) => { if (method === 'GET') return []; throw new Error('주택타입을 화면에서 만들지 않습니다.') })
  lookup.lookupAddresses.mockResolvedValue([{ roadAddress: '서울특별시 중구 세종대로 110', jibunAddress: '', pnu: '1114010100100010000', legalDongCode: '1114010100', provinceCode: '11', cityCountyDistrictCode: '11140', latitude: 37.5, longitude: 127, matchesKeyword: true }])
  registration.createHousingComplex.mockResolvedValue({ housingComplexId: 99, name })
  function Test() {
    const [review, setReview] = useState(latest)
    return <ComplexDecisions review={review} workspace={workspace} onChange={next => { latest = next; setReview(next) }} />
  }
  render(<Test />)
  // 같은 주소·같은 이름의 등록된 단지가 없으니 누르지 않아도 이름 검색 아래에 새 단지 등록이 열린다.
  expect(await screen.findByRole('group', { name: '주택형 1 새 단지 등록' })).toBeVisible()
  expect(screen.getByRole('button', { name: '단지 검색' })).toBeVisible()
  await waitFor(() => expect(screen.getByRole('button', { name: '단지 등록하고 연결' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '단지 등록하고 연결' }))
  await waitFor(() => expect(latest.complexes[name]).toMatchObject({ housingComplexId: 99, supplyPnu: '1114010100100010000', category: 'NEW_SUPPLY', chosen: true }))
  expect(registration.createHousingComplex).toHaveBeenCalledTimes(1)
  expect(within(screen.getByRole('group', { name: `PDF 단지 ${name}` })).getByText(/연결 단지:/)).toBeVisible()
  const sheet = assembledSheet(latest, workspace)
  expect(sheet.housingTypes.map(type => [type.name, type.housingComplexId, type.housingTypeId, type.newHousingType?.name])).toEqual([['26A', 99, null, '26A'], ['26B', 99, null, '26B']])
})

it('같은 이름의 단지가 검색되면 등록을 대신 열지 않고, 검색 결과가 없으면 검색을 그대로 둔 채 등록을 그 아래에 연다', async () => {
  const { review, workspace } = scenario('hansville')
  api.getManagementPage.mockResolvedValueOnce({ items: [{ id: 5, name: '같은 이름 단지', subtitle: '서울특별시 중구 세종대로 1', provider: 'LH', rental: null }] })
  const first = render(<ComplexDecisions review={review} workspace={workspace} onChange={vi.fn()} />)
  expect(await screen.findByText('같은 이름 단지')).toBeVisible()
  expect(screen.queryByRole('group', { name: '주택형 1 새 단지 등록' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '찾는 단지가 없으면 새 단지 등록' })).toBeVisible()
  first.unmount()

  api.getManagementPage.mockResolvedValue({ items: [] })
  render(<ComplexDecisions review={review} workspace={workspace} onChange={vi.fn()} />)
  await screen.findByRole('group', { name: '주택형 1 새 단지 등록' })
  // 되돌아가는 단계 없이 검색이 등록 위에 남고, 등록을 여는 버튼은 사라진다.
  expect(screen.getByRole('button', { name: '단지 검색' })).toBeVisible()
  expect(screen.queryByRole('button', { name: '기존 단지에서 찾기' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '찾는 단지가 없으면 새 단지 등록' })).not.toBeInTheDocument()
})

/** PDF 단지 개요 주소가 기관 원천의 공고 단지 주소와 같아 그 공고 단지에 이어지는 한스빌 검토. */
function withSourceComplex() {
  const { review: original, workspace: base } = scenario('hansville')
  const name = original.extraction.sheet.housingTypes[0].complexName!
  const address = '서울특별시 중구 세종대로 110'
  const workspace = { ...base, supply: { ...base.supply, announcementComplexes: [{ id: 3, name: '한스빌 공고 단지명', roadAddress: address, housingComplexId: null, housingComplexName: null, linkMethod: null, rowCount: 2 }] } }
  const extraction = { ...original.extraction, complexes: [{ name, address, totalHouseholdCount: null, parkingCount: null }] }
  return { name, address, workspace, review: createReview(extraction, workspace, {}) }
}
const nearbyApi = (found: unknown[]) => api.requestManagementApi.mockImplementation(async (path: string) => path.includes('/same-address-complexes') ? found : [])

it('공고 단지와 주소가 같은 기존 단지가 하나면 고르지 않아도 연결하고 이름을 공고 단지명으로 바꿀 것을 알린다', async () => {
  const { name, address, workspace, review: initial } = withSourceComplex()
  let latest = initial
  api.getManagementDetail.mockResolvedValue({ ...workspace.detail, data: { ...workspace.detail.data, address: { pnu: '1114010100100010000' } } })
  api.getManagementPage.mockResolvedValue({ items: [] })
  nearbyApi([{ housingComplexId: 41, name: '세종 옛 이름', roadAddress: '서울 중구 세종대로 110', renamable: true }])
  function Test() {
    const [review, setReview] = useState(latest)
    return <ComplexDecisions review={review} workspace={workspace} rentalType="HAPPY_HOUSING" onChange={next => { latest = next; setReview(next) }} />
  }
  render(<Test />)
  const group = await screen.findByRole('group', { name: `PDF 단지 ${name}` })
  expect(await within(group).findByText('연결 단지: 세종 옛 이름 · 공고 단지와 주소가 같아 자동으로 연결했습니다.')).toBeVisible()
  expect(within(group).getByText("적용하면 '세종 옛 이름'의 이름이 공고 단지명 '한스빌 공고 단지명'(으)로 바뀔 수 있습니다. 적용 미리보기에서 확인하세요.")).toBeVisible()
  expect(latest.complexes[name]).toMatchObject({ housingComplexId: 41, announcementComplexId: 3, chosen: true, category: 'RESUPPLY' })
  // 공고문 개요 주소와 원천 도로명주소를 함께 보낸다.
  const [path] = api.requestManagementApi.mock.calls.find(([value]) => String(value).includes('/same-address-complexes'))!
  const query = new URLSearchParams(String(path).split('?')[1])
  expect(query.getAll('address')).toEqual([address, address])
  // 관리자가 이 화면에서 고른 공급유형으로 찾는다.
  expect(query.get('rentalType')).toBe('HAPPY_HOUSING')
  // 자동으로 정했으니 검색·등록은 '다른 단지로 바꾸기'를 눌러야 보인다.
  expect(within(group).queryByRole('button', { name: '단지 검색' })).not.toBeInTheDocument()
  expect(within(group).queryByRole('group', { name: '주택형 1 새 단지 등록' })).not.toBeInTheDocument()
  fireEvent.click(within(group).getByRole('button', { name: '다른 단지로 바꾸기' }))
  expect(within(group).getByText('연결됨')).toBeVisible()
  fireEvent.click(within(group).getByRole('button', { name: '지금 단지 유지' }))
  expect(within(group).queryByRole('button', { name: '단지 검색' })).not.toBeInTheDocument()
  fireEvent.click(within(group).getByRole('button', { name: '다른 단지로 바꾸기' }))
  expect(await within(group).findByRole('button', { name: '단지 검색' })).toBeVisible()
})

it('관리자가 고친 단지면 이름을 바꾼다고 알리지 않는다', async () => {
  const { name, workspace, review } = withSourceComplex()
  api.getManagementDetail.mockResolvedValue({ ...workspace.detail, data: { ...workspace.detail.data, address: {} } })
  api.getManagementPage.mockResolvedValue({ items: [] })
  nearbyApi([{ housingComplexId: 41, name: '세종 옛 이름', roadAddress: null, renamable: false }])
  function Test() {
    const [current, setCurrent] = useState(review)
    return <ComplexDecisions review={current} workspace={workspace} onChange={setCurrent} />
  }
  render(<Test />)
  const group = await screen.findByRole('group', { name: `PDF 단지 ${name}` })
  expect(await within(group).findByText(/연결 단지: 세종 옛 이름/)).toBeVisible()
  expect(within(group).queryByText(/공고 단지명 .*바뀔 수 있습니다/)).not.toBeInTheDocument()
})

it('같은 주소의 단지가 여럿이면 대신 고르지 않고 그 단지들을 먼저 보이며 이름 검색 결과가 없어도 등록을 열지 않는다', async () => {
  const { name, workspace, review } = withSourceComplex()
  const onChange = vi.fn()
  api.getManagementPage.mockResolvedValue({ items: [] })
  nearbyApi([{ housingComplexId: 41, name: '세종 1동', roadAddress: null, renamable: true }, { housingComplexId: 42, name: '세종 2동', roadAddress: null, renamable: true }])
  render(<ComplexDecisions review={review} workspace={workspace} onChange={onChange} />)
  const group = await screen.findByRole('group', { name: `PDF 단지 ${name}` })
  const nearby = await within(group).findByRole('group', { name: '공고 단지와 주소가 같은 단지' })
  expect(within(nearby).getAllByRole('button').map(button => button.textContent)).toEqual(['세종 1동에 연결', '세종 2동에 연결'])
  expect(await within(group).findByText('일치하는 단지가 없습니다.')).toBeVisible()
  expect(within(group).queryByRole('group', { name: '주택형 1 새 단지 등록' })).not.toBeInTheDocument()
  expect(within(group).getByRole('button', { name: '찾는 단지가 없으면 새 단지 등록' })).toBeVisible()
  expect(onChange).not.toHaveBeenCalled()
})

it('공급유형이 바뀌어 자동으로 이은 단지가 같은 곳 단지가 아니면 연결을 비우고 다시 고르게 한다', async () => {
  const { name, workspace, review: initial } = withSourceComplex()
  let latest = initial
  api.getManagementDetail.mockResolvedValue({ ...workspace.detail, data: { ...workspace.detail.data, address: {} } })
  api.getManagementPage.mockResolvedValue({ items: [] })
  api.requestManagementApi.mockImplementation(async (path: string) => !path.includes('/same-address-complexes') ? []
    : path.includes('rentalType=HAPPY_HOUSING') ? [] : [{ housingComplexId: 41, name: '세종 국민임대', roadAddress: null, renamable: true }])
  lookup.lookupAddresses.mockResolvedValue([])
  function Test({ rentalType }: { rentalType: string }) {
    const [review, setReview] = useState(latest)
    return <ComplexDecisions review={review} workspace={workspace} rentalType={rentalType} onChange={next => { latest = next; setReview(next) }} />
  }
  const { rerender } = render(<Test rentalType="NATIONAL_RENTAL" />)
  const group = await screen.findByRole('group', { name: `PDF 단지 ${name}` })
  await within(group).findByText(/연결 단지: 세종 국민임대/)
  rerender(<Test rentalType="HAPPY_HOUSING" />)
  expect(await within(group).findByText('공급유형이 바뀌어 같은 주소로 이었던 단지 연결을 풀었습니다. 단지를 다시 고르세요.')).toBeVisible()
  expect(latest.complexes[name]).toMatchObject({ housingComplexId: null, chosen: false })
  expect(within(group).queryByText(/연결 단지:/)).not.toBeInTheDocument()
})
