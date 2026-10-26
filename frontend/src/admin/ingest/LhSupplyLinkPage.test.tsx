import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { LhSupplyLinkPage } from './LhSupplyLinkPage'

const api = vi.hoisted(() => ({ getLhSupplyLinks: vi.fn(), saveLhSupplyLinks: vi.fn(), releaseLhSupplyLink: vi.fn(), rerunAnnouncement: vi.fn() }))
vi.mock('./supplyMatchingApi', () => api)

const lh = (order: number, complexLabel: string, typeName: string, linkedRowIdentifier: string | null = null) => ({
  rowIdentifier: `LH:P:SUPPLY:${order}`, complexLabel, typeName, exclusiveArea: typeName, supplyArea: null,
  suppliedUnitCount: '100', linkedRowIdentifier, savedLink: null })
const myhome = (key: string, complexName: string) => ({ rowIdentifier: key, complexName, housingTypeName: '아파트', supplyHouseholdCount: 0, splitCorrected: false })
const view = {
  token: 't1', announcement: { identifier: '20935', name: '군산 공고', sourceUrl: 'https://apply.lh.or.kr/notice', panId: 'P' },
  myhomeRows: [myhome('M1', '경암부향'), myhome('M2', '산북부향1차'), myhome('M3', '신풍부향')],
  lhRows: [lh(0, '군산경암', '39'), lh(1, '군산부향1차', '27'), lh(2, '군산부향1차', '39'), lh(3, '군산신풍부향', '35', 'M3')],
  failures: [0, 1, 2].map(order => ({ lhRowIdentifier: `LH:P:SUPPLY:${order}`, reason: 'COMPLEX_NOT_FOUND', detail: '', candidates: [] })),
}

beforeEach(() => { Object.values(api).forEach(fn => fn.mockReset()); api.getLhSupplyLinks.mockResolvedValue(view) })
function renderPage() {
  render(<MemoryRouter initialEntries={['/admin/failures/lh-links/20935']}><Routes>
    <Route path="/admin/failures/lh-links/:identifier" element={<LhSupplyLinkPage />} /></Routes></MemoryRouter>)
}
const rowOf = (index: number) => within(screen.getAllByRole('row')[index + 1])

it('지역 접두어를 뺀 단지명으로 단지를 제안하고, 같은 단지의 두 번째 LH 행은 공급행 추가로 알린다', async () => {
  renderPage()
  expect(await screen.findByText('연결 필요 3 / 4')).toBeVisible()
  expect(rowOf(0).getByRole('combobox')).toHaveValue('M1')
  expect(rowOf(1).getByRole('combobox')).toHaveValue('M2')
  expect(rowOf(1).getByText('제안')).toBeVisible()
  expect(rowOf(1).getByText('기존 공급행에 연결')).toBeVisible()
  expect(rowOf(2).getByText('공급행 추가')).toBeVisible()
  expect(rowOf(3).getByText('자동 연결')).toBeVisible()
  expect(screen.getByRole('button', { name: '연결 3개 저장하고 다시 정제' })).toBeEnabled()
})

it('고른 단지별로 묶어 저장한 뒤 이 공고만 다시 정제한다', async () => {
  api.saveLhSupplyLinks.mockImplementation(async (_id, current) => ({ ...current, token: `${current.token}+` }))
  api.rerunAnnouncement.mockResolvedValue(undefined)
  renderPage()
  fireEvent.click(await screen.findByRole('button', { name: '연결 3개 저장하고 다시 정제' }))
  expect(await screen.findByRole('status')).toHaveTextContent('이 공고의 오류가 해결됐습니다.')
  expect(api.saveLhSupplyLinks.mock.calls.map(call => [call[2], call[3]])).toEqual([
    ['M1', ['LH:P:SUPPLY:0']], ['M2', ['LH:P:SUPPLY:1', 'LH:P:SUPPLY:2']]])
  expect(api.saveLhSupplyLinks.mock.calls[1][1].token).toBe('t1+')
  expect(api.rerunAnnouncement).toHaveBeenCalledWith('20935')
})

it('다시 정제가 거절되면 연결은 저장됐다고 알리고 서버 사유를 보여 준다', async () => {
  api.saveLhSupplyLinks.mockImplementation(async (_id, current) => current)
  api.rerunAnnouncement.mockRejectedValue(new Error('정제 실패: LH 보강 실패: HOUSING_TYPE_NOT_FOUND - 없음. 저장은 되돌리고 오류 목록에 기록했습니다.'))
  renderPage()
  fireEvent.click(await screen.findByRole('button', { name: '연결 3개 저장하고 다시 정제' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('연결은 저장했습니다. 정제 실패: LH 보강 실패')
  await waitFor(() => expect(api.getLhSupplyLinks).toHaveBeenCalledTimes(2))
})

it('제안이 없는 행은 단지를 골라야 하고 근거 URL이 없으면 저장할 수 없다', async () => {
  api.getLhSupplyLinks.mockResolvedValue({ ...view, announcement: { ...view.announcement, sourceUrl: null },
    lhRows: [lh(0, '다른이름', '39')], failures: [] })
  renderPage()
  expect(await screen.findByText('단지 선택 필요')).toBeVisible()
  fireEvent.change(screen.getByRole('combobox'), { target: { value: 'M2' } })
  expect(screen.getByText('기존 공급행에 연결')).toBeVisible()
  expect(screen.getByRole('button', { name: '연결 1개 저장하고 다시 정제' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('공식 근거 URL'), { target: { value: 'https://example.com/notice.pdf' } })
  expect(screen.getByRole('button', { name: '연결 1개 저장하고 다시 정제' })).toBeEnabled()
})

it('LH 행 구성이 바뀌어 해제한 저장 매칭을 정제 결과와 함께 알린다', async () => {
  api.saveLhSupplyLinks.mockImplementation(async (_id, current, target) => ({ ...current, releasedSupplyMatches: target === 'M1' ? ['M1'] : [] }))
  api.rerunAnnouncement.mockResolvedValue(undefined)
  renderPage()
  fireEvent.click(await screen.findByRole('button', { name: '연결 3개 저장하고 다시 정제' }))
  expect(await screen.findByRole('status')).toHaveTextContent('원천행의 저장 매칭 1개를 해제했습니다. PDF 보정에서 펼쳐진 공급행의 주택형을 확인하세요.')
})
