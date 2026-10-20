import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { QualityComparisonPanel } from './QualityComparisonPanel'
import type { ManagementDetailData } from '../management/managementContract'
import { ManagementError } from '../management/api'

const mocks = vi.hoisted(() => ({ load: vi.fn(), request: vi.fn() }))
vi.mock('./qualityComparisonApi', async original => ({ ...await original<typeof import('./qualityComparisonApi')>(), loadQualityComparison: mocks.load, parseQualityComparison: (value: unknown) => value }))
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(), requestManagementApi: mocks.request }))
const detail: ManagementDetailData = { summary: { id: 7, name: '단지', subtitle: '서울', provider: 'LH', rental: 'HAPPY_HOUSING', deleted: false, modified: false, reviewRequired: false, updatedAt: null },
  sourceIdentifier: 'manual:7', data: { version: 2 }, housingTypes: [{ id: 3, name: '26A', exclusiveArea: 26.1234, householdCount: 10 }], announcements: [], supplyRows: [], supplyTargets: [], scheduleReviewed: false, schedules: [] }
const snapshot = { snapshotToken: 'a'.repeat(64), target: 'type:3', detail, sources: [{ category: 'LH_LEASE_CATALOG', sourceKey: 'key', automatic: false, evidenceNote: '동일 주택형', rows: [{ id: 9, name: '단지', collectedAt: null, raw: { total_unit_count: '0', exclusive_area: '26.1234' } }] }] }
const changed = vi.fn()
beforeEach(() => { vi.clearAllMocks(); mocks.load.mockResolvedValue(snapshot); mocks.request.mockResolvedValue(snapshot) })
function show() { render(<MemoryRouter><QualityComparisonPanel resource="complexes" id="7" detail={detail} onChanged={changed} onBusyChange={vi.fn()} /></MemoryRouter>) }

it('주택형의 원천값 0만 선택해 근거와 저장하고 단지 전체 값은 보내지 않는다', async () => {
  show()
  const row = await screen.findByRole('article', { name: '주택형 세대수 비교' })
  fireEvent.click(within(row).getByRole('button', { name: '이 원천값 사용' }))
  expect(screen.getByLabelText('주택형 세대수 수정')).toHaveValue(0)
  expect(screen.getByRole('button', { name: '선택 수정 저장 · 1개' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('수정·원천 확인 근거'), { target: { value: '동일 주택형 세대수 확인' } })
  fireEvent.click(screen.getByRole('button', { name: '선택 수정 저장 · 1개' }))
  await waitFor(() => expect(changed).toHaveBeenCalledOnce())
  expect(mocks.request).toHaveBeenCalledWith('/api/admin/quality/COMPLEX/7/comparison', 'PUT', {
    target: 'type:3', snapshotToken: snapshot.snapshotToken, values: { householdCount: 0 }, evidenceNote: '동일 주택형 세대수 확인',
  }, expect.any(AbortSignal))
})
it('충돌하면 수정값과 근거를 보존하고 추가 저장을 막는다', async () => {
  mocks.request.mockRejectedValue(new ManagementError('원천이 변경되었습니다.', 409))
  show()
  fireEvent.change(await screen.findByLabelText('주택형 세대수 수정'), { target: { value: '12' } })
  fireEvent.change(screen.getByLabelText('수정·원천 확인 근거'), { target: { value: '보존할 근거' } })
  fireEvent.click(screen.getByRole('button', { name: '선택 수정 저장 · 1개' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('원천이 변경되었습니다.')
  expect(screen.getByLabelText('주택형 세대수 수정')).toHaveValue(12)
  expect(screen.getByLabelText('수정·원천 확인 근거')).toHaveValue('보존할 근거')
  expect(screen.getByRole('button', { name: '선택 수정 저장 · 1개' })).toBeDisabled()
})

it('공고의 공급행은 원천 비교와 원천 확인만 남기고 값 수정은 공급정보 탭으로 보낸다', async () => {
  const announcement: ManagementDetailData = { ...detail, housingTypes: [], supplyRows: [{ id: 4, housingComplexId: 1, housingComplexName: '한스빌아파트', housingTypeId: null, modified: false, data: { sourceComplexName: '한스빌', sourceHousingTypeName: '아파트', supplyHouseholdCount: 170 } }] }
  const rowSnapshot = { ...snapshot, target: 'row:4', detail: announcement, sources: [{ category: 'MYHOME_ANNOUNCEMENT', sourceKey: 'key', automatic: true, evidenceNote: '', rows: [{ id: 9, name: '한스빌', collectedAt: null, raw: { hshld_co: '170' } }] }] }
  mocks.load.mockResolvedValue({ ...rowSnapshot, target: 'basic' })
  render(<MemoryRouter initialEntries={['/admin/announcements/7?section=verification&provider=LH']}><QualityComparisonPanel resource="announcements" id="7" detail={announcement} onChanged={changed} onBusyChange={vi.fn()} /></MemoryRouter>)
  expect(await screen.findByRole('button', { name: /선택 수정 저장/ })).toBeInTheDocument()
  mocks.load.mockResolvedValue(rowSnapshot)
  fireEvent.change(screen.getByLabelText('비교·수정 대상'), { target: { value: 'row:4' } })
  await waitFor(() => expect(screen.queryByRole('button', { name: /선택 수정 저장/ })).not.toBeInTheDocument())
  expect(screen.queryByRole('button', { name: '이 원천값 사용' })).not.toBeInTheDocument()
  expect(screen.queryByLabelText(/수정$/)).not.toBeInTheDocument()
  const link = screen.getByRole('link', { name: '공급정보·단지 연결 탭에서 수정' })
  const target = new URL(link.getAttribute('href')!, 'http://localhost')
  expect(target.searchParams.get('section')).toBe('relations')
  expect(target.searchParams.get('provider')).toBe('LH')
  mocks.request.mockResolvedValue(rowSnapshot)
  fireEvent.change(screen.getByLabelText('원천 확인 근거'), { target: { value: '공고문 3쪽과 원천 일치' } })
  fireEvent.click(screen.getByRole('button', { name: '현재 원천 확인 기록' }))
  await waitFor(() => expect(mocks.request).toHaveBeenCalledWith('/api/admin/quality/ANNOUNCEMENT/7/comparison/checks', 'POST',
    expect.objectContaining({ target: 'row:4', evidenceNote: '공고문 3쪽과 원천 일치' }), expect.any(AbortSignal)))
  expect(await screen.findByText(/현재 원천 확인을 기록했습니다/)).toBeVisible()
})
