import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { ComplexComparisonEditor } from './ComplexComparisonEditor'
import { ManagementError } from './api'

const mocks = vi.hoisted(() => ({ get: vi.fn(), save: vi.fn(), link: vi.fn(), unlink: vi.fn(), search: vi.fn() }))
vi.mock('./complexComparisonApi', () => ({ getComparison: mocks.get, saveComparison: mocks.save, linkComparisonSource: mocks.link, unlinkComparisonSource: mocks.unlink }))
vi.mock('../ingest/sourceApi', async importOriginal => ({ ...await importOriginal<object>(), getSourceData: mocks.search }))
const current = { version: 2, name: '테스트 단지', agencyCode: 'LH', rentalType: 'HAPPY_HOUSING', totalHouseholdCount: 10, totalParkingCount: 0, completionDate: null, heatingType: null, buildingType: null, corridorType: null, hasElevator: false, moveOutCountLastYear: null, overviewImageUrl: null,
  address: { roadAddress: '서울특별시 중구 세종대로 110', pnu: '1114010100100010000', legalDongCode: '1114010100', provinceCode: '11', cityCountyDistrictCode: '11140', latitude: 37.5665, longitude: 126.978 } }
const source = { id: 9, sourceKey: 'myhome-9', name: '테스트 단지', sourceUrl: 'https://example.com', originalUrl: null, collectedAt: '2026-10-08T00:00:00Z', sourceUpdatedAt: null, raw: { hsmp_nm: '테스트 단지', hshld_co: 120, parkng_co: 0, elvtr_instl_at_nm: '미설치' } }
const snapshot = { snapshotToken: 'a'.repeat(64), current, sources: [{ category: 'MYHOME_COMPLEX', sourceKey: source.sourceKey, automatic: false, evidenceNote: '같은 단지 확인', actor: 'admin', linkedAt: source.collectedAt, data: source }] }
const changed = vi.fn()
function show() { render(<MemoryRouter><ComplexComparisonEditor id="7" version={2} deleted={false} onChanged={changed} onBusyChange={vi.fn()} /></MemoryRouter>) }
beforeEach(() => { vi.clearAllMocks(); mocks.get.mockResolvedValue(snapshot); mocks.save.mockResolvedValue({ ...snapshot, current: { ...current, version: 3, totalHouseholdCount: 120 } }) })

it('파일 없이 DB 원천과 현재값을 보여주고 선택한 원천값만 수정한다', async () => {
  show()
  const row = await screen.findByRole('group', { name: '전체 세대수 비교' })
  expect(within(row).getByText('120')).toBeVisible()
  fireEvent.click(within(row).getByRole('button', { name: '이 원천값 사용' }))
  expect(screen.getByRole('spinbutton', { name: '전체 세대수 수정값' })).toHaveValue(120)
  expect(screen.getByRole('button', { name: /수정사항 저장/ })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('수정 근거·메모'), { target: { value: '전체 세대수 범위 확인' } })
  fireEvent.click(screen.getByRole('button', { name: /수정사항 저장/ }))
  await waitFor(() => expect(changed).toHaveBeenCalledOnce())
  expect(mocks.save).toHaveBeenCalledWith('7', expect.objectContaining({ snapshotToken: snapshot.snapshotToken, current: { ...current, totalHouseholdCount: 120 }, evidenceNote: '전체 세대수 범위 확인' }), expect.any(AbortSignal))
})

it('DB 원천을 검색해 직접 확인한 연결만 저장한다', async () => {
  mocks.get.mockResolvedValue({ ...snapshot, sources: [] }); mocks.search.mockResolvedValue({ items: [source], page: 0, totalElements: 1, totalPages: 1, hasNext: false }); mocks.link.mockResolvedValue(snapshot)
  show()
  fireEvent.click(await screen.findByText('원천 검색·연결'))
  fireEvent.click(screen.getByRole('button', { name: '원천 검색' }))
  await screen.findByRole('button', { name: '이 원천 연결' })
  expect(screen.getByRole('button', { name: '이 원천 연결' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('연결 근거'), { target: { value: '주소와 공급 범위가 같은 단지 확인' } })
  fireEvent.click(screen.getByRole('button', { name: '이 원천 연결' }))
  await waitFor(() => expect(mocks.link).toHaveBeenCalledWith('7', expect.objectContaining({ category: 'MYHOME_COMPLEX', sourceKey: source.sourceKey, evidenceNote: '주소와 공급 범위가 같은 단지 확인' }), expect.any(AbortSignal)))
})

it('충돌하면 수정값과 근거를 보존하고 재저장을 막는다', async () => {
  mocks.save.mockRejectedValue(new ManagementError('원천이 변경되었습니다. 다시 조회해 주세요.', 409)); show()
  fireEvent.click(await screen.findByRole('button', { name: '전체 세대수 수정' }))
  fireEvent.change(screen.getByRole('spinbutton', { name: '전체 세대수 수정값' }), { target: { value: '121' } })
  fireEvent.change(screen.getByLabelText('수정 근거·메모'), { target: { value: '보존할 근거' } })
  fireEvent.click(screen.getByRole('button', { name: /수정사항 저장/ }))
  expect(await screen.findByRole('alert')).toHaveTextContent('원천이 변경되었습니다.')
  expect(screen.getByRole('spinbutton', { name: '전체 세대수 수정값' })).toHaveValue(121)
  expect(screen.getByLabelText('수정 근거·메모')).toHaveValue('보존할 근거')
  expect(screen.getByRole('button', { name: /수정사항 저장/ })).toBeDisabled()
})

it('미확인 원천과 false·0을 구분하며 임의로 자동 채우지 않는다', async () => {
  show()
  const parking = await screen.findByRole('group', { name: '주차대수 비교' })
  expect(within(parking).getAllByText('0')).toHaveLength(2)
  expect(within(parking).queryByRole('button', { name: '이 원천값 사용' })).not.toBeInTheDocument()
  expect(within(screen.getByRole('group', { name: '엘리베이터 비교' })).getAllByText('미설치')).toHaveLength(2)
  expect(within(screen.getByRole('group', { name: '준공일 비교' })).getByText('원천에 없음')).toBeVisible()
})
