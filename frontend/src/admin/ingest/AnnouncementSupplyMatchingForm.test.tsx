import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AnnouncementSupplyMatchingForm } from './AnnouncementSupplyMatchingForm'
import { getManagementDetail } from '../management/api'
import { getSupplyMatches, searchMatchingComplexes, getMatchingHousingTypes, refineSupplyMatches } from './supplyMatchingApi'

vi.mock('./supplyMatchingApi', () => ({ getSupplyMatches: vi.fn(), searchMatchingComplexes: vi.fn(),
  getMatchingHousingTypes: vi.fn(), refineSupplyMatches: vi.fn() }))
vi.mock('../management/api', () => ({ getManagementDetail: vi.fn() }))

const row = { rowIdentifier: '21395:1', token: 'version', sourceComplexName: '익산한스빌',
  sourceHousingTypeName: '26A,B', pnu: '123', exclusiveArea: 26.5195, supplyArea: null,
  complexId: 1, housingTypeId: null, failure: '주택형 하나를 확정할 수 없습니다.' }
function renderForm(disabled = false) {
  return render(<MemoryRouter><AnnouncementSupplyMatchingForm identifier="21395" disabled={disabled} /></MemoryRouter>)
}

describe('선택 적용 후 공고 정제', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(getManagementDetail).mockResolvedValue({
      summary: { id: 1, name: '한스빌아파트', subtitle: '익산시 목천로 30-14', provider: 'SH',
        rental: 'NATIONAL_RENTAL', deleted: false, modified: false, reviewRequired: false, updatedAt: null },
      sourceIdentifier: 'complex-1', data: { version: 1 }, housingTypes: [], announcements: [],
      supplyRows: [], scheduleReviewed: false, schedules: [],
    })
    vi.mocked(getSupplyMatches).mockResolvedValue([row])
    vi.mocked(searchMatchingComplexes).mockResolvedValue([{ id: 1, name: '한스빌아파트',
      roadAddress: '익산시 목천로 30-14', supplyType: 'NATIONAL_RENTAL' }])
    vi.mocked(getMatchingHousingTypes).mockResolvedValue([{ id: 10, name: '26', exclusiveArea: 26.5195, supplyArea: null }])
    vi.mocked(refineSupplyMatches).mockResolvedValue(undefined)
  })

  it('선택을 적용해 정제하는 버튼은 연결 저장 버튼을 대체한다', async () => {
    renderForm()
    await screen.findByRole('option', { name: /26 · 전용/ })
    expect(screen.getByRole('button', { name: '선택 적용·정제 실행' })).toBeVisible()
    expect(screen.queryByRole('button', { name: '연결 저장' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '수동 연결 해제' })).not.toBeInTheDocument()
  })

  it('주택형을 선택해 바로 정제하고 완료 후 공고 관리 링크를 보여준다', async () => {
    renderForm()
    expect(await screen.findByText('26A,B')).toBeVisible()
    await screen.findByRole('option', { name: /26 · 전용/ })
    fireEvent.change(screen.getByLabelText('매칭 주택형'), { target: { value: '10' } })
    expect(refineSupplyMatches).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '선택 적용·정제 실행' }))
    await waitFor(() => expect(refineSupplyMatches).toHaveBeenCalledWith('21395',
      [{ rowIdentifier: '21395:1', token: 'version', complexId: 1, housingTypeId: 10 }]))
    expect(await screen.findByRole('status')).toHaveTextContent('정제·저장을 완료했습니다')
    expect(screen.getByRole('link', { name: '공고 관리로 이동' })).toHaveAttribute('href', '/admin/announcements')
    expect(screen.queryByRole('button', { name: '등록 재시도' })).not.toBeInTheDocument()
  })

  it('여러 행의 선택을 모아 요청을 한 번만 보낸다', async () => {
    vi.mocked(getSupplyMatches).mockResolvedValue([row, { ...row, rowIdentifier: '21395:2', token: 'second' }])
    renderForm()
    await screen.findAllByRole('option', { name: /26 · 전용/ })
    screen.getAllByLabelText('매칭 주택형').forEach(input =>
      fireEvent.change(input, { target: { value: '10' } }))
    fireEvent.click(screen.getByRole('button', { name: '선택 적용·정제 실행' }))
    await waitFor(() => expect(refineSupplyMatches).toHaveBeenCalledExactlyOnceWith('21395', [
      { rowIdentifier: '21395:1', token: 'version', complexId: 1, housingTypeId: 10 },
      { rowIdentifier: '21395:2', token: 'second', complexId: 1, housingTypeId: 10 },
    ]))
  })

  it('실행 중 입력과 중복 실행을 막는다', async () => {
    let complete: (() => void) | undefined
    vi.mocked(refineSupplyMatches).mockImplementation(() => new Promise<void>(resolve => { complete = resolve }))
    renderForm()
    await screen.findByRole('option', { name: /26 · 전용/ })
    fireEvent.change(screen.getByLabelText('매칭 주택형'), { target: { value: '10' } })
    fireEvent.click(screen.getByRole('button', { name: '선택 적용·정제 실행' }))
    expect(await screen.findByRole('button', { name: '정제·저장 중…' })).toBeDisabled()
    expect(screen.getByLabelText('매칭 주택형')).toBeDisabled()
    expect(screen.getByRole('button', { name: '매칭 정보 새로고침' })).toBeDisabled()
    expect(refineSupplyMatches).toHaveBeenCalledOnce()
    complete?.()
    await screen.findByRole('link', { name: '공고 관리로 이동' })
  })

  it('실패하면 입력을 유지하고 수정 후 재시도할 수 있다', async () => {
    vi.mocked(refineSupplyMatches).mockRejectedValueOnce(new Error('원천이 변경되었습니다. 다시 조회해 주세요.'))
    renderForm()
    await screen.findByRole('option', { name: /26 · 전용/ })
    fireEvent.change(screen.getByLabelText('매칭 주택형'), { target: { value: '10' } })
    fireEvent.click(screen.getByRole('button', { name: '선택 적용·정제 실행' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('원천이 변경되었습니다')
    expect(screen.getByLabelText('매칭 주택형')).toHaveValue('10')
    fireEvent.click(screen.getByRole('button', { name: '선택 적용·정제 실행' }))
    await screen.findByRole('link', { name: '공고 관리로 이동' })
  })

  it('단지를 바꾸면 이전 주택형을 지우고 새 단지의 주택형을 가져온다', async () => {
    vi.mocked(searchMatchingComplexes).mockResolvedValue([{ id: 2, name: '다른 단지', roadAddress: '다른 주소',
      supplyType: 'NATIONAL_RENTAL' }])
    renderForm()
    await screen.findByRole('option', { name: /26 · 전용/ })
    fireEvent.change(screen.getByLabelText('매칭 주택형'), { target: { value: '10' } })
    fireEvent.click(screen.getByRole('button', { name: '단지 다시 선택하기' }))
    fireEvent.change(screen.getByLabelText('단지 검색'), { target: { value: '다른' } })
    fireEvent.click(screen.getByRole('button', { name: '검색' }))
    fireEvent.click(await screen.findByRole('button', { name: /다른 단지/ }))
    await waitFor(() => expect(getMatchingHousingTypes).toHaveBeenCalledWith(2, expect.any(AbortSignal)))
    expect(screen.getByLabelText('매칭 주택형')).toHaveValue('')
    expect(screen.queryByLabelText('단지 검색')).not.toBeInTheDocument()
  })

  it('찾은 단지는 검색을 숨기고 다시 선택할 때만 검색을 연다', async () => {
    renderForm()
    await screen.findByRole('option', { name: /26 · 전용/ })
    expect(screen.queryByLabelText('단지 검색')).not.toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: '한스빌아파트' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '단지 다시 선택하기' }))
    expect(screen.getByLabelText('단지 검색')).toBeVisible()
  })

  it('선택한 주택형 면적을 보여주고 선택만으로 요청하지 않는다', async () => {
    renderForm()
    await screen.findByRole('option', { name: /26 · 전용/ })
    fireEvent.change(screen.getByLabelText('매칭 주택형'), { target: { value: '10' } })
    const target = screen.getByRole('region', { name: '연결 대상' })
    expect(within(target).getAllByRole('definition')).toHaveLength(2)
    expect(within(target).getByText('26.5195㎡')).toBeVisible()
    expect(refineSupplyMatches).not.toHaveBeenCalled()
  })

  it('단지를 못 찾으면 검색을 표시하고 정제 버튼을 비활성화한다', async () => {
    vi.mocked(getSupplyMatches).mockResolvedValue([{ ...row, complexId: null }])
    renderForm()
    expect(await screen.findByLabelText('단지 검색')).toBeVisible()
    expect(screen.getByRole('button', { name: '선택 적용·정제 실행' })).toBeDisabled()
    expect(getMatchingHousingTypes).not.toHaveBeenCalled()
  })

  it('단지 상세 조회 실패를 안내하고 주택형 선택은 유지한다', async () => {
    vi.mocked(getManagementDetail).mockRejectedValue(new Error('상세 조회 실패'))
    renderForm()
    expect(await screen.findByText('단지 정보를 불러오지 못했습니다. 단지 상세에서 확인해 주세요.')).toBeVisible()
    await screen.findByRole('option', { name: /26 · 전용/ })
    fireEvent.change(screen.getByLabelText('매칭 주택형'), { target: { value: '10' } })
    expect(screen.getByLabelText('매칭 주택형')).toHaveValue('10')
  })

  it('조회 실패를 안내하고 실행 버튼을 표시하지 않는다', async () => {
    vi.mocked(getSupplyMatches).mockRejectedValue(new Error('원천 조회 실패'))
    renderForm()
    expect(await screen.findByRole('alert')).toHaveTextContent('원천 조회 실패')
    expect(screen.queryByRole('button', { name: '선택 적용·정제 실행' })).not.toBeInTheDocument()
  })
})
