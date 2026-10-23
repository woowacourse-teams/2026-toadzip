import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { RerunAnnouncement } from './RerunAnnouncement'
import { scenario } from './fixtures/workspace'

const api = vi.hoisted(() => ({ rerunAnnouncement: vi.fn() }))
vi.mock('../ingest/supplyMatchingApi', () => api)
const base = scenario('hansville').workspace
const withSource = { ...base, supply: { ...base.supply, rowSources: [{ rowKey: base.supply.rows[0].key, category: 'MYHOME_ANNOUNCEMENT', sourceKey: '5:213951:1' }] } }
// beforeEach가 함수를 반환하면 정리 단계에서 다시 호출되므로 반환값을 남기지 않는다.
beforeEach(() => { api.rerunAnnouncement.mockReset() })
function renderWith(workspace = withSource) {
  render(<MemoryRouter><RerunAnnouncement workspace={workspace} failureReturn="/admin/failures?domain=announcement&keyword=x" /></MemoryRouter>)
}

it('마이홈 원천이 없는 공고에는 다시 정제를 보여 주지 않는다', () => {
  renderWith({ ...base, supply: { ...base.supply, rowSources: [] } })
  expect(screen.queryByRole('button', { name: '이 공고만 다시 정제' })).not.toBeInTheDocument()
})

it('거절되면 서버 사유와 오류 목록이 바뀌지 않았다는 안내를 보여 준다', async () => {
  api.rerunAnnouncement.mockImplementation(async () => { throw new Error('정제 실패: LH 보강 실패: COMPLEX_NOT_FOUND - LH 공급 원본과 일치하는 기존 공급 단지가 없습니다.') })
  renderWith()
  fireEvent.click(screen.getByRole('button', { name: '이 공고만 다시 정제' }))
  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent('정제 실패: LH 보강 실패: COMPLEX_NOT_FOUND')
  expect(alert).toHaveTextContent('오류 목록은 바뀌지 않았습니다.')
  expect(screen.getByRole('button', { name: '이 공고만 다시 정제' })).toBeEnabled()
})

it('성공하면 오류가 해결됐다고 알리고 실행 중에는 다시 누를 수 없다', async () => {
  let finish = () => {}
  api.rerunAnnouncement.mockReturnValue(new Promise<void>(resolve => { finish = resolve }))
  renderWith()
  fireEvent.click(screen.getByRole('button', { name: '이 공고만 다시 정제' }))
  expect(screen.getByRole('button', { name: '정제하는 중…' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '정제하는 중…' }))
  expect(api.rerunAnnouncement).toHaveBeenCalledTimes(1)
  expect(api.rerunAnnouncement).toHaveBeenCalledWith(base.detail.sourceIdentifier)
  finish()
  expect(await screen.findByRole('status')).toHaveTextContent('이 공고의 오류는 해결됨으로 바뀌었습니다.')
  expect(screen.getByRole('link', { name: '오류 목록에서 확인' })).toHaveAttribute('href', '/admin/failures?domain=announcement&keyword=x')
})

