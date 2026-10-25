import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { blankRow } from '../supply/supplyCorrectionApi'
import { ChangeHistory } from './ChangeHistory'
import { getManagementHistory } from './api'

vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), getManagementHistory: vi.fn() }))
beforeEach(() => vi.mocked(getManagementHistory).mockReset())

it('원천 비교 수정은 변경값과 당시 원천 근거를 분리해 표시한다', async () => {
  vi.mocked(getManagementHistory).mockResolvedValue([{
    id: 2, action: 'UPDATE_COMPARED', actor: '운영자', occurredAt: '2026-10-15T00:00:00Z',
    beforeValue: JSON.stringify({ totalHouseholdCount: 10, version: 1 }),
    afterValue: JSON.stringify({ totalHouseholdCount: 120, version: 2, comparisonEvidence: { note: '단지 전체 범위 확인', sources: [
      { category: 'MYHOME_COMPLEX', data: { name: '두꺼비 원천', collectedAt: '2026-10-08T00:00:00Z', raw: { hshld_co: 120 } } },
    ] } }),
  }])
  render(<ChangeHistory resource="complexes" id="7" version={2} />)
  fireEvent.click(await screen.findByRole('button', { name: /원천 비교 후 수정 · 운영자/ }))
  expect(screen.getByRole('table', { name: '수정한 항목' })).not.toHaveTextContent('comparisonEvidence')
  const evidence = screen.getByRole('region', { name: '확인한 DB 원천 근거' })
  expect(evidence).toHaveTextContent('단지 전체 범위 확인')
  expect(evidence).toHaveTextContent('두꺼비 원천')
  fireEvent.click(within(evidence).getByText('수정 당시 원천 값'))
  expect(within(evidence).getByRole('table')).toHaveTextContent('120')
})

it('공고 이력은 바뀐 값만 보여주고 전체 기록을 나열하지 않는다', async () => {
  vi.mocked(getManagementHistory).mockResolvedValue([{
    id: 1, action: 'UPDATE', actor: '운영자', occurredAt: '2026-10-01T00:00:00Z',
    beforeValue: '{"name":"기존 이름","supplyRows":[{"amount":0,"enabled":false}]}',
    afterValue: '{"name":"새 이름","originalUrl":"https://example.com/notice"}',
  }])
  const { container } = render(<ChangeHistory resource="announcements" id="7" version={2} />)
  fireEvent.click(await screen.findByRole('button', { name: /정보 수정 · 운영자/ }))
  expect(screen.getByRole('table', { name: '수정한 항목' })).toHaveTextContent('새 이름')
  expect(screen.queryByText('전체 기록 보기')).not.toBeInTheDocument()
  expect(container.querySelector('pre')).toBeNull()
})

it('단지 이력은 전체 기록을 경로·값 표로 펼쳐 볼 수 있다', async () => {
  vi.mocked(getManagementHistory).mockResolvedValue([{
    id: 1, action: 'UPDATE', actor: '운영자', occurredAt: '2026-10-01T00:00:00Z',
    beforeValue: '{"name":"기존 이름","supplyRows":[{"amount":0,"enabled":false}]}',
    afterValue: '{"name":"새 이름","originalUrl":"https://example.com/notice"}',
  }])
  render(<ChangeHistory resource="complexes" id="7" version={2} />)
  fireEvent.click(await screen.findByRole('button', { name: /정보 수정 · 운영자/ }))
  fireEvent.click(screen.getByText('전체 기록 보기'))
  const before = screen.getByRole('table', { name: '변경 전' })
  expect(within(before).getByRole('row', { name: 'supplyRows[0].amount 0' })).toBeVisible()
  expect(within(screen.getByRole('table', { name: '변경 후' })).getByRole('link', { name: 'https://example.com/notice' })).toBeVisible()
})

it('바뀐 값만 먼저 보여주고 PDF 근거를 읽기 쉬운 이름으로 표시한다', async () => {
  vi.mocked(getManagementHistory).mockResolvedValue([{
    id: 1, action: 'UPDATE', actor: '운영자', occurredAt: '2026-10-01T00:00:00Z',
    beforeValue: JSON.stringify({ name: '같은 이름', totalHouseholdCount: 80, version: 1 }),
    afterValue: JSON.stringify({ name: '같은 이름', totalHouseholdCount: 120, version: 2,
      documentEvidence: { note: '원문 확인', citations: [{ field: 'totalHouseholdCount', page: 1, quote: '총 120세대' }] } }),
  }])
  render(<ChangeHistory resource="complexes" id="7" version={2} />)
  fireEvent.click(await screen.findByRole('button', { name: /정보 수정 · 운영자/ }))
  const changes = screen.getByRole('table', { name: '수정한 항목' })
  expect(changes).toHaveTextContent('전체 세대수')
  expect(changes).toHaveTextContent('80')
  expect(changes).toHaveTextContent('120')
  expect(changes).not.toHaveTextContent('같은 이름')
  expect(within(changes).getAllByRole('row')).toHaveLength(2)
  const evidence = screen.getByRole('table', { name: '확인한 PDF 근거' })
  expect(evidence).toHaveTextContent('원문 확인')
  expect(evidence).toHaveTextContent('총 120세대')
  expect(evidence).not.toHaveTextContent('citations[0]')
  expect(screen.getByText('전체 기록 보기').closest('details')).not.toHaveAttribute('open')
})

it('객체가 아닌 저장 이력도 값을 그대로 표시한다', async () => {
  vi.mocked(getManagementHistory).mockResolvedValue([{
    id: 1, action: 'UPDATE', actor: '운영자', occurredAt: '2026-10-01T00:00:00Z',
    beforeValue: '기존 이력 텍스트', afterValue: 'null',
  }])
  render(<ChangeHistory resource="complexes" id="7" version={2} />)
  fireEvent.click(await screen.findByRole('button', { name: /정보 수정 · 운영자/ }))
  fireEvent.click(screen.getByText('전체 기록 보기'))
  expect(screen.getByRole('table', { name: '변경 전' })).toHaveTextContent('기존 이력 텍스트')
  expect(screen.getByRole('table', { name: '변경 후' })).toHaveTextContent('값 없음 (null)')
})

it('공급정보 일괄 보정 이력은 원천 전체 대신 바뀐 값과 공식 근거를 먼저 보여준다', async () => {
  const previous = { ...blankRow(0), id: 1, key: 'row:1', complexName: '한스빌', typeName: '26', householdCount: 10 }
  vi.mocked(getManagementHistory).mockResolvedValue([{ id: 4, action: 'CORRECT_SUPPLY', actor: '운영자', occurredAt: '2026-10-19T00:00:00Z',
    beforeValue: JSON.stringify({ rows: [previous] }),
    afterValue: JSON.stringify({ rows: [{ ...previous, householdCount: null }], reason: '모집수 미확정', sourceUrl: 'https://example.com/notice.pdf', sourcePage: '3쪽', operationId: '작업번호', sources: [{ raw: '긴 원천 자료' }] }),
  }])
  render(<ChangeHistory resource="announcements" id="7" version={2} />)
  fireEvent.click(await screen.findByRole('button', { name: /공급정보 일괄 보정 · 운영자/ }))
  const record = screen.getByRole('region', { name: '저장한 공급정보 보정' })
  expect(within(record).getByRole('heading', { name: '저장한 변경 1개' })).toBeVisible()
  expect(record).toHaveTextContent('모집수 미확정')
  expect(within(record).getByRole('link', { name: '공식 근거 ↗' })).toHaveAttribute('href', 'https://example.com/notice.pdf')
  expect(within(record).getByRole('table', { name: '보정 작업' })).not.toBeVisible()
})

it('PDF 보정 이력은 한 건에 주택형 표기·접수처·일정 변경을 읽을 수 있게 보여준다', async () => {
  const previous = { ...blankRow(0), id: 164, key: 'row:164', complexName: '사천벌리1', typeName: '37.1300' }
  const event = { id: 82, complexName: null, data: { scheduleType: 'APPLICATION', name: '접수', startAt: '2026-10-28T00:00:00', endAt: '2026-10-30T00:00:00' } }
  vi.mocked(getManagementHistory).mockResolvedValue([{ id: 5, action: 'CORRECT_FROM_PDF', actor: '운영자', occurredAt: '2026-10-22T00:00:00Z',
    beforeValue: JSON.stringify({ rows: [previous], detail: { data: { receptionPlace: { method: 'VISIT', contact: null } } }, events: [event] }),
    afterValue: JSON.stringify({ rows: [{ ...previous, typeName: '26' }], reason: '공고문 PDF와 대조', sourceUrl: 'https://example.com/notice', operationId: '작업번호', sources: [],
      detail: { data: { receptionPlace: { method: 'VISIT', contact: '1600-1004' } } },
      events: [{ ...event, data: { ...event.data, name: '현장 신청접수', startAt: '2026-10-28T09:00:00' } }] }),
  }])
  render(<ChangeHistory resource="announcements" id="47" version={3} />)
  fireEvent.click(await screen.findByRole('button', { name: /PDF로 공고 보정 · 운영자/ }))
  const supply = screen.getByRole('region', { name: '저장한 공급정보 보정' })
  expect(within(supply).getByRole('row', { name: /이 공고의 주택형 표기 37.1300 26/ })).toBeInTheDocument()
  const other = screen.getByRole('region', { name: '저장한 기본정보·일정 보정' })
  expect(within(other).getByRole('heading', { name: '기본정보·접수처·일정 3개' })).toBeVisible()
  expect(within(other).getByRole('row', { name: /접수처 연락처 .* 1600-1004/ })).toBeInTheDocument()
  expect(within(other).getByRole('row', { name: '일정 #82 일정명 접수 현장 신청접수' })).toBeInTheDocument()
})

it('PDF 보정 이력은 공급행 변경이 없으면 빈 표를 두지 않고 새 일정의 이전 값은 없음으로 보여준다', async () => {
  const row = { ...blankRow(0), id: 90, key: 'row:90', complexName: '익산한스빌', typeName: '26A' }
  vi.mocked(getManagementHistory).mockResolvedValue([{ id: 6, action: 'CORRECT_FROM_PDF', actor: '운영자', occurredAt: '2026-10-22T00:00:00Z',
    beforeValue: JSON.stringify({ rows: [row], detail: { data: {} }, events: [] }),
    afterValue: JSON.stringify({ rows: [row], reason: '공고문 PDF와 대조', sources: [], detail: { data: {} },
      events: [{ id: 258, complexName: null, data: { scheduleType: 'DOCUMENT_SUBMISSION', name: '현장 신청 시 서류제출', startAt: '2026-10-21T10:00:00', endAt: '2026-10-22T16:00:00' } }] }),
  }])
  render(<ChangeHistory resource="announcements" id="29" version={4} />)
  fireEvent.click(await screen.findByRole('button', { name: /PDF로 공고 보정 · 운영자/ }))
  const supply = screen.getByRole('region', { name: '저장한 공급정보 보정' })
  expect(within(supply).getByRole('heading', { name: '공급행 변경 없음' })).toBeVisible()
  expect(within(supply).queryByRole('region', { name: '변경 전후 비교' })).not.toBeInTheDocument()
  expect(screen.getByRole('row', { name: '일정 #258 (추가) 일정 유형 없음 서류 제출' })).toBeInTheDocument()
})

it('이전 JSON 갱신 이력도 변수명 대신 화면 이름으로 바뀐 값만 보여준다', async () => {
  const previous = { ...blankRow(0), id: 164, key: 'row:164', complexName: '사천벌리1', typeName: '37.1300' }
  vi.mocked(getManagementHistory).mockResolvedValue([{ id: 2, action: 'UPDATE_FROM_JSON', actor: 'root', occurredAt: '2026-10-22T00:00:00Z',
    beforeValue: JSON.stringify({ rows: [previous], detail: { summary: { modified: false }, data: { version: 1, receptionPlace: { name: '행정복지센터', contact: null } } }, events: [] }),
    afterValue: JSON.stringify({ rows: [{ ...previous, typeName: '26', protectedValues: true }], reason: 'PDF 대조', sourceUrl: 'https://example.com/notice', operationId: '작업', sources: [],
      detail: { summary: { modified: true }, data: { version: 3, receptionPlace: { name: '주민센터', contact: '1600-1004' } } }, events: [] }),
  }])
  render(<ChangeHistory resource="announcements" id="47" version={3} />)
  fireEvent.click(await screen.findByRole('button', { name: /JSON으로 공고 갱신 · root/ }))
  const other = screen.getByRole('region', { name: '저장한 기본정보·일정 보정' })
  expect(within(other).getAllByRole('row').slice(1).map(row => row.textContent)).toEqual(['접수처접수처명행정복지센터주민센터', '접수처연락처미확인1600-1004'])
  expect(screen.getByRole('row', { name: /이 공고의 주택형 표기 37.1300 26/ })).toBeInTheDocument()
  expect(screen.queryByText(/detail\.summary|rows\[0\]/)).not.toBeInTheDocument()
})
