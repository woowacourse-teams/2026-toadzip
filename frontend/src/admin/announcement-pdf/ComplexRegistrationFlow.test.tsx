import { useState } from 'react'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { ComplexDecisions } from './ComplexDecision'
import { scenario } from './fixtures/workspace'
import { createReview, expectedHousingType, rebaseReview, assembleReview, type Review } from './review'
import type { MatchingHousingType } from '../ingest/supplyMatchingApi'

const api = vi.hoisted(() => ({ requestManagementApi: vi.fn(), getManagementDetail: vi.fn(), getManagementPage: vi.fn() }))
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(), ...api }))
const registration = vi.hoisted(() => ({ createHousingComplex: vi.fn() }))
vi.mock('../registration/api', async original => ({ ...await original<typeof import('../registration/api')>(), ...registration }))
const lookup = vi.hoisted(() => ({ lookupAddresses: vi.fn() }))
vi.mock('./addressLookupApi', () => lookup)

beforeEach(() => vi.resetAllMocks())

it('단지 등록 후 주택형 일부 생성에 실패해도 생성한 단지를 보존하고 다시 열어 남은 주택형만 연결한다', async () => {
  const { review: original, workspace } = scenario('hansville')
  workspace.supply.rows = []
  const first = original.extraction.supplyRows[0]
  const name = first.complexName!
  const pdfRow = (typeName: string, exclusiveArea: number, count: number) => ({ ...first, complexName: name, typeName, subtypes: [],
    values: { ...first.values, sourceHousingTypeName: typeName, exclusiveArea, totalSupplyHouseholdCount: count } })
  const extraction = { ...original.extraction, sharedCounts: [], notApplicable: [], supplyRows: [pdfRow('26A', 26.12, 10), pdfRow('26A(고령자)', 26.12, 5), pdfRow('36A', 36.5, 7)],
    evidence: [0, 1, 2].map(index => ({ path: `supplyRows[${index}]`, page: 4, quote: ['26A 26.12 10', '26A 26.12 5', '36A 36.5 7'][index] })), complexes: [{ name, address: '서울특별시 중구 세종대로 110', totalHouseholdCount: null, parkingCount: null }] }
  let latest = createReview(extraction, workspace, {})
  const types: MatchingHousingType[] = [], createdNames: string[] = []
  let version = 0, fail = true
  const detail = () => ({ ...workspace.detail, data: { ...workspace.detail.data, version, address: { pnu: '1114010100100010000' } } })
  api.getManagementDetail.mockImplementation(async () => detail())
  api.getManagementPage.mockResolvedValue({ items: [] })
  api.requestManagementApi.mockImplementation(async (_path: string, method: string, body?: { name: string; exclusiveArea: number; supplyArea: number | null; version: number }) => {
    if (method === 'GET') return structuredClone(types)
    if (body!.name === '36A' && fail) { fail = false; throw new Error('주택형 저장 실패') }
    if (types.some(type => type.exclusiveArea === body!.exclusiveArea)) throw new Error('같은 전용면적이 있습니다.')
    expect(body!.version).toBe(version)
    createdNames.push(body!.name)
    types.push({ id: 100 + types.length, name: body!.name, exclusiveArea: body!.exclusiveArea, supplyArea: body!.supplyArea })
    version += 1
    return detail()
  })
  lookup.lookupAddresses.mockResolvedValue([{ roadAddress: '서울특별시 중구 세종대로 110', jibunAddress: '', pnu: '1114010100100010000', legalDongCode: '1114010100', provinceCode: '11', cityCountyDistrictCode: '11140', latitude: 37.5, longitude: 127, matchesKeyword: true }])
  registration.createHousingComplex.mockResolvedValue({ housingComplexId: 99, name })
  function Test() {
    const [review, setReview] = useState(latest)
    return <ComplexDecisions review={review} workspace={workspace} onChange={next => { latest = next; setReview(next) }} />
  }
  const view = render(<Test />)
  // 같은 이름의 등록된 단지가 없으니 누르지 않아도 새 단지 등록이 열린다.
  expect(await screen.findByText('같은 이름의 등록된 단지가 없어 새 단지 등록을 열었습니다. 이름이 다른 기존 단지라면 기존 단지에서 찾으세요.')).toBeVisible()
  await waitFor(() => expect(screen.getByRole('button', { name: '단지 등록하고 연결' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '단지 등록하고 연결' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('주택형 저장 실패')
  expect(createdNames).toEqual(['26A'])
  expect(latest.rows.every(row => row.proposal.createdComplex?.id === 99)).toBe(true)
  expect(() => assembleReview(latest, workspace, { reason: '보정', sourceUrl: 'https://example.com', sourcePage: null })).toThrow('단지는 등록됐지만')
  expect(screen.queryByRole('button', { name: '단지 등록하고 연결' })).not.toBeInTheDocument()
  view.unmount()
  // 서버 초안을 복원하듯 JSON으로 보존한 검토 상태를 다시 연다.
  latest = rebaseReview(JSON.parse(JSON.stringify(latest)) as Review, { ...workspace, snapshotToken: 'updated' }, { 99: structuredClone(types) })
  expect(latest.rows.every(row => row.proposal.createdComplex?.id === 99)).toBe(true)
  render(<Test />)
  fireEvent.click(screen.getByRole('button', { name: '등록한 단지에 이어서 연결' }))
  await waitFor(() => expect(latest.rows.every(row => row.proposal.newComplex?.id === 99 && !row.proposal.createdComplex)).toBe(true))
  expect(createdNames).toEqual(['26A', '36A'])
  expect(registration.createHousingComplex).toHaveBeenCalledTimes(1)
  // 같은 면적의 26A·26A(고령자)는 공급행 하나다. 주택형은 저장할 때 서버가 전용면적으로 정하며, 화면은 같은 규칙으로 고를 주택형을 미리 보인다.
  expect(latest.rows.map(row => [row.proposal.destination, expectedHousingType(row)?.id])).toEqual([['new', 100], ['new', 101]])
  expect(within(screen.getByRole('group', { name: `PDF 단지 ${name}` })).getByText(/연결 단지:/)).toBeVisible()
})


it('공고에 다른 단지의 마이홈 개요만 있으면 새 단지의 주소·PNU·세대수로 빌려 쓰지 않는다', async () => {
  const { review: original, workspace } = scenario('hansville')
  workspace.supply.rows = []
  workspace.supply.sources = [{ category: 'MYHOME_ANNOUNCEMENT', sourceKey: 'other', rows: [{ raw: {
    hsmp_nm: '다른 단지', full_adres: '서울특별시 중구 세종대로 110', pnu: '1114010100100010000', tot_hshld_co: '350', heat_mthd_nm: '개별난방', house_ty_nm: '아파트',
  } }] }]
  const review = createReview({ ...original.extraction, complexes: [], supplyRows: [original.extraction.supplyRows[0]], sharedCounts: [] }, workspace, {})
  api.getManagementPage.mockResolvedValue({ items: [] })
  render(<ComplexDecisions review={review} workspace={workspace} onChange={vi.fn()} />)
  const form = await screen.findByRole('group', { name: '공급행 1 새 단지 등록' })
  expect(within(form).getByLabelText('주소 검색')).toHaveValue('')
  expect(within(form).getByLabelText('PNU')).toHaveValue('')
  expect(within(form).getByLabelText('전체 세대수')).toHaveValue(null)
  expect(lookup.lookupAddresses).not.toHaveBeenCalled()
})


it('같은 이름의 단지가 검색되면 등록을 대신 열지 않고, 기존 단지에서 찾기로 돌아오면 다시 열지 않는다', async () => {
  const { review: original, workspace } = scenario('hansville')
  workspace.supply.rows = []
  const review = createReview({ ...original.extraction, complexes: [], supplyRows: [original.extraction.supplyRows[0]], sharedCounts: [] }, workspace, {})
  api.getManagementPage.mockResolvedValueOnce({ items: [{ id: 5, name: '같은 이름 단지', subtitle: '서울특별시 중구 세종대로 1', provider: 'LH', rental: null }] })
  const first = render(<ComplexDecisions review={review} workspace={workspace} onChange={vi.fn()} />)
  expect(await screen.findByText('같은 이름 단지')).toBeVisible()
  expect(screen.queryByRole('group', { name: '공급행 1 새 단지 등록' })).not.toBeInTheDocument()
  first.unmount()

  api.getManagementPage.mockResolvedValue({ items: [] })
  render(<ComplexDecisions review={review} workspace={workspace} onChange={vi.fn()} />)
  await screen.findByRole('group', { name: '공급행 1 새 단지 등록' })
  fireEvent.click(screen.getByRole('button', { name: '기존 단지에서 찾기' }))
  await waitFor(() => expect(api.getManagementPage).toHaveBeenCalledTimes(3))
  await act(async () => {})
  expect(screen.queryByRole('group', { name: '공급행 1 새 단지 등록' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '찾는 단지가 없으면 새 단지로 등록' })).toBeVisible()
})
