import { useState } from 'react'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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
  // 같은 이름의 등록된 단지가 없으니 누르지 않아도 새 단지 등록이 열린다.
  expect(await screen.findByText('같은 이름의 등록된 단지가 없어 새 단지 등록을 열었습니다. 이름이 다른 기존 단지라면 기존 단지에서 찾으세요.')).toBeVisible()
  await waitFor(() => expect(screen.getByRole('button', { name: '단지 등록하고 연결' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '단지 등록하고 연결' }))
  await waitFor(() => expect(latest.complexes[name]).toMatchObject({ housingComplexId: 99, supplyPnu: '1114010100100010000', category: 'NEW_SUPPLY', chosen: true }))
  expect(registration.createHousingComplex).toHaveBeenCalledTimes(1)
  expect(within(screen.getByRole('group', { name: `PDF 단지 ${name}` })).getByText(/연결 단지:/)).toBeVisible()
  const sheet = assembledSheet(latest, workspace)
  expect(sheet.housingTypes.map(type => [type.name, type.housingComplexId, type.housingTypeId, type.newHousingType?.name])).toEqual([['26A', 99, null, '26A'], ['26B', 99, null, '26B']])
})

it('같은 이름의 단지가 검색되면 등록을 대신 열지 않고, 기존 단지에서 찾기로 돌아오면 다시 열지 않는다', async () => {
  const { review, workspace } = scenario('hansville')
  api.getManagementPage.mockResolvedValueOnce({ items: [{ id: 5, name: '같은 이름 단지', subtitle: '서울특별시 중구 세종대로 1', provider: 'LH', rental: null }] })
  const first = render(<ComplexDecisions review={review} workspace={workspace} onChange={vi.fn()} />)
  expect(await screen.findByText('같은 이름 단지')).toBeVisible()
  expect(screen.queryByRole('group', { name: '주택형 1 새 단지 등록' })).not.toBeInTheDocument()
  first.unmount()

  api.getManagementPage.mockResolvedValue({ items: [] })
  render(<ComplexDecisions review={review} workspace={workspace} onChange={vi.fn()} />)
  await screen.findByRole('group', { name: '주택형 1 새 단지 등록' })
  fireEvent.click(screen.getByRole('button', { name: '기존 단지에서 찾기' }))
  await waitFor(() => expect(api.getManagementPage).toHaveBeenCalledTimes(3))
  await act(async () => {})
  expect(screen.queryByRole('group', { name: '주택형 1 새 단지 등록' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '찾는 단지가 없으면 새 단지로 등록' })).toBeVisible()
})
