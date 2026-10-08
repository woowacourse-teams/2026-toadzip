import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import { ComplexVerificationPanel } from './ComplexVerificationPanel'
import { ManagementError } from './api'
import type { ComplexVerification, ComplexReview } from './complexVerificationContract'

const mocks = vi.hoisted(() => ({ get: vi.fn(), save: vi.fn() }))
vi.mock('./complexVerificationApi', () => ({ getComplexVerification: mocks.get, saveComplexReview: mocks.save }))
const onEdit = vi.fn()
const onReviewed = vi.fn()
const onBusyChange = vi.fn()
function fixture(): ComplexVerification {
  return {
    version: 2, snapshotToken: 'a'.repeat(64), status: 'UNREVIEWED',
    currentValues: { NAME: '두꺼비 단지', ADDRESS: { roadAddress: '서울 중구 세종대로 110', pnu: '1114010100100010000',
      legalDongCode: '1114010100', provinceCode: '11', cityCountyDistrictCode: '11140' },
    LOCATION: [37.5665, 126.978], AGENCY: 'LH', RENTAL_TYPE: 'HAPPY_HOUSING', HOUSEHOLD_COUNT: 300 },
    sources: [{ sourceIdentifier: '12:HAPPY_HOUSING', name: '두꺼비 단지', roadAddress: '서울 중구 세종대로 110',
      pnu: '1114010100100010000', provider: '한국토지주택공사', rentalType: '행복주택', householdCount: 150,
      housingType: '26A', exclusiveArea: 26, collectedAt: '2026-10-08T00:00:00Z' }],
    latestReview: null, history: [],
  }
}
function review(): ComplexReview {
  return { id: 9, outcome: 'VERIFIED', fields: ['NAME'], checkedValues: { NAME: '지난 단지명' },
    evidenceUrl: 'https://example.com/complex', evidenceNote: '공식 안내 2쪽', actor: '담당자',
    reviewedAt: '2026-10-08T00:00:00Z' }
}
function show(deleted = false) {
  return render(<MemoryRouter><ComplexVerificationPanel id="7" version={2} deleted={deleted} announcements={[]}
    onEdit={onEdit} onReviewed={onReviewed} onBusyChange={onBusyChange} /></MemoryRouter>)
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.get.mockResolvedValue(fixture())
})

it('일치와 차이를 구분하고 실제 확인과 지도 대조 경로를 제공한다', async () => {
  show()
  expect(await screen.findByText('300세대')).toBeVisible()
  expect(screen.getByText('150세대')).toBeVisible()
  expect(screen.getByText('값 다름')).toBeVisible()
  expect(screen.getAllByText('원천 일치')).toHaveLength(4)
  expect(screen.getByText(/원천 일치는 실제 정보 확인 완료를 뜻하지 않습니다/)).toBeVisible()
  const coordinate = new URL(screen.getByRole('link', { name: '저장 좌표 보기 ↗' }).getAttribute('href')!)
  expect(coordinate.searchParams.get('query')).toBe('37.5665,126.978')
  expect(screen.getByRole('button', { name: '검토 저장' })).toBeDisabled()
})
it('직접 선택한 범위와 근거만 저장하고 이력을 표시한다', async () => {
  const savedReview = { ...review(), fields: ['NAME', 'HOUSEHOLD_COUNT'] as const,
    checkedValues: { NAME: '두꺼비 단지', HOUSEHOLD_COUNT: 300 } }
  mocks.save.mockResolvedValue({ ...fixture(), status: 'VERIFIED', latestReview: savedReview, history: [savedReview] })
  show()
  fireEvent.click(await screen.findByRole('checkbox', { name: '단지명 확인' }))
  fireEvent.click(screen.getByRole('checkbox', { name: '세대수 확인' }))
  fireEvent.change(screen.getByLabelText('확인 근거·메모'), { target: { value: '공식 안내 2쪽' } })
  fireEvent.change(screen.getByLabelText(/근거 URL/), { target: { value: 'https://example.com/complex' } })
  fireEvent.click(screen.getByRole('button', { name: '검토 저장' }))
  await waitFor(() => expect(onReviewed).toHaveBeenCalledOnce())
  expect(mocks.save).toHaveBeenCalledWith('7', expect.objectContaining({
    version: 2, reviewId: 0, snapshotToken: 'a'.repeat(64), fields: ['NAME', 'HOUSEHOLD_COUNT'],
    outcome: 'VERIFIED', evidenceNote: '공식 안내 2쪽', evidenceUrl: 'https://example.com/complex',
  }), expect.any(AbortSignal))
  expect(screen.getByText(/2\/6항목/)).toBeVisible()
  expect(screen.getByRole('checkbox', { name: '단지명 확인' })).not.toBeChecked()
  fireEvent.click(screen.getByText('검토 이력'))
  expect(screen.getByText('공식 안내 2쪽')).toBeVisible()
})
it('저장 충돌은 입력을 유지하고 새로 조회하기 전 재저장을 막는다', async () => {
  mocks.save.mockRejectedValue(new ManagementError('단지 정보가 변경되었습니다.', 409))
  show()
  fireEvent.click(await screen.findByRole('checkbox', { name: '단지명 확인' }))
  fireEvent.change(screen.getByLabelText('확인 근거·메모'), { target: { value: '입력한 근거' } })
  fireEvent.click(screen.getByRole('button', { name: '검토 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('단지 정보가 변경되었습니다.')
  expect(screen.getByLabelText('확인 근거·메모')).toHaveValue('입력한 근거')
  expect(screen.getByRole('button', { name: '검토 저장' })).toBeDisabled()
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
  fireEvent.click(screen.getByRole('button', { name: '새로 조회' }))
  await waitFor(() => expect(mocks.get).toHaveBeenCalledTimes(2))
  confirm.mockRestore()
})
it('보류 기록도 확인 범위와 사유를 요구한다', async () => {
  mocks.save.mockResolvedValue({ ...fixture(), status: 'ON_HOLD', latestReview: { ...review(), outcome: 'ON_HOLD' }, history: [] })
  show()
  fireEvent.click(await screen.findByRole('checkbox', { name: '세대수 확인' }))
  fireEvent.click(screen.getByRole('radio', { name: '확인 보류' }))
  expect(screen.getByRole('button', { name: '검토 저장' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('확인 근거·메모'), { target: { value: '전체 세대수인지 공급기관에 확인 필요' } })
  fireEvent.click(screen.getByRole('button', { name: '검토 저장' }))
  await waitFor(() => expect(mocks.save).toHaveBeenCalledWith('7', expect.objectContaining({
    fields: ['HOUSEHOLD_COUNT'], outcome: 'ON_HOLD',
  }), expect.any(AbortSignal)))
})
it('원천이 없으면 미확인을 표시하며 휴지통 검토는 저장할 수 없다', async () => {
  mocks.get.mockResolvedValue({ ...fixture(), sources: [] })
  show(true)
  expect(await screen.findByText(/연결된 수집 원천이 없습니다/)).toBeVisible()
  expect(screen.getByRole('checkbox', { name: '단지명 확인' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '검토 저장' })).toBeDisabled()
  expect(screen.queryByRole('button', { name: '등록 정보 수정' })).not.toBeInTheDocument()
})
it('이전 확인값과 변경된 현재값을 구분해 보여준다', async () => {
  mocks.get.mockResolvedValue({ ...fixture(), status: 'STALE', latestReview: review(), history: [review()] })
  show()
  expect(await screen.findByText(/지난 검토 이후 확인한 값이 바뀌었습니다/)).toBeVisible()
  expect(screen.getByText('지난 확인: 지난 단지명')).toBeVisible()
  expect(screen.getByText('재검토 필요')).toBeVisible()
})
it('여러 원천을 선택해 비교하며 세대수를 합산하지 않는다', async () => {
  const value = fixture()
  mocks.get.mockResolvedValue({ ...value, sources: [...value.sources, { ...value.sources[0], sourceIdentifier: '13:HAPPY_HOUSING', householdCount: 120 }] })
  show()
  const select = await screen.findByRole('combobox', { name: '비교 원천' })
  expect(screen.getByText('150세대')).toBeVisible()
  expect(screen.getByText('120세대')).toBeVisible()
  expect(screen.queryByText('270세대')).not.toBeInTheDocument()
  fireEvent.change(select, { target: { value: '13:HAPPY_HOUSING' } })
  expect(screen.queryByText('150세대')).not.toBeInTheDocument()
  expect(screen.getByText('120세대')).toBeVisible()
})
it('조회 실패를 빈 원천으로 위장하지 않고 재시도한다', async () => {
  mocks.get.mockRejectedValueOnce(new Error('원천 조회 실패'))
  show()
  expect(await screen.findByRole('alert')).toHaveTextContent('원천 조회 실패')
  expect(screen.queryByText(/연결된 수집 원천이 없습니다/)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '새로 조회' }))
  expect(await screen.findByText('300세대')).toBeVisible()
})
it('저장하지 않은 기록의 이동 취소를 보존한다', async () => {
  show()
  fireEvent.click(await screen.findByRole('checkbox', { name: '단지명 확인' }))
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  fireEvent.click(screen.getByRole('button', { name: '등록 정보 수정' }))
  expect(onEdit).not.toHaveBeenCalled()
  expect(screen.getByRole('checkbox', { name: '단지명 확인' })).toBeChecked()
  confirm.mockReturnValue(true)
  fireEvent.click(screen.getByRole('button', { name: '등록 정보 수정' }))
  expect(onEdit).toHaveBeenCalledOnce()
  confirm.mockRestore()
})
it('조회가 완료되기 전에 다른 단지로 이동하면 이전 응답을 표시하지 않는다', async () => {
  let resolve!: (value: ComplexVerification) => void
  mocks.get.mockImplementationOnce(() => new Promise<ComplexVerification>(done => { resolve = done }))
  const view = show()
  view.unmount()
  resolve(fixture())
  await waitFor(() => expect(mocks.get).toHaveBeenCalledOnce())
  expect(within(document.body).queryByText('300세대')).not.toBeInTheDocument()
})
