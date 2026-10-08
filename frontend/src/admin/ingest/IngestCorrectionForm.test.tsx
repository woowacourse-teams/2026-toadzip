import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { IngestCorrectionForm } from './IngestCorrectionForm'
import { getCorrection, saveCorrection, type CorrectionDetail } from './workspaceApi'
import { ManagementError } from '../management/api'

vi.mock('./workspaceApi', () => ({ getCorrection: vi.fn(), saveCorrection: vi.fn() }))
vi.mock('../management/ManagementDetail', () => ({ ManagementDetail: () => <p>기존 데이터 관리</p> }))

const detail: CorrectionDetail = {
  domain: 'complex', identifier: '338:NATIONAL_RENTAL', token: 'first-token', productId: null,
  managementOnly: false, editableFields: ['hsmpNm', 'parkngCo', 'competDe'],
  rows: [{ sourceKey: 'source-338', original: { hsmpNm: null, parkngCo: 0, competDe: null },
    values: { hsmpNm: null, parkngCo: 0, competDe: null } }], latitude: null, longitude: null, changes: [],
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getCorrection).mockResolvedValue(detail)
})
afterEach(cleanup)

function open(disabled = false, onSaved = vi.fn()) {
  render(<MemoryRouter><IngestCorrectionForm domain="complex" identifier={detail.identifier}
    disabled={disabled} onSaved={onSaved} onClose={vi.fn()} /></MemoryRouter>)
  return onSaved
}

describe('원천 데이터 보완', () => {
  it('필수 누락을 표시하되 0과 선택 값은 누락으로 표시하지 않는다', async () => {
    open()
    const name = await screen.findByLabelText('단지명 · 행 1')
    expect(name.parentElement).toHaveTextContent('필수 값 누락')
    expect(screen.getByLabelText('주차대수 · 행 1').parentElement).not.toHaveTextContent('필수 값 누락')
    expect(screen.getByLabelText('준공일 · 행 1').parentElement).not.toHaveTextContent('필수 값 누락')
    expect(screen.getByLabelText('주차대수 · 행 1')).toHaveValue(0)
  })

  it('원천 식별자는 유지하고 변경한 항목만 저장한다', async () => {
    const saved = open()
    vi.mocked(saveCorrection).mockResolvedValue(338)
    fireEvent.change(await screen.findByLabelText('단지명 · 행 1'), { target: { value: ' 보완 단지 ' } })
    fireEvent.click(screen.getByRole('button', { name: '보완 후 정제·저장' }))
    await waitFor(() => expect(saved).toHaveBeenCalledWith(338))
    expect(saveCorrection).toHaveBeenCalledWith(detail,
      [{ sourceKey: 'source-338', changes: { hsmpNm: '보완 단지' } }], null, null)
  })

  it('다른 작업이 실행 중이면 보완 저장과 입력을 비활성화한다', async () => {
    open(true)
    expect(await screen.findByLabelText('단지명 · 행 1')).toBeDisabled()
    expect(screen.getByRole('button', { name: '보완 후 정제·저장' })).toBeDisabled()
    expect(saveCorrection).not.toHaveBeenCalled()
  })

  it('정제 실패 후 입력을 유지하고 갱신한 버전으로 재시도한다', async () => {
    open()
    vi.mocked(saveCorrection).mockRejectedValueOnce(new Error('주택형 매칭 실패')).mockResolvedValueOnce(338)
    fireEvent.change(await screen.findByLabelText('단지명 · 행 1'), { target: { value: '보완 단지' } })
    vi.mocked(getCorrection).mockResolvedValue({ ...detail, token: 'retry-token' })
    fireEvent.click(screen.getByRole('button', { name: '보완 후 정제·저장' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('주택형 매칭 실패')
    await waitFor(() => expect(screen.getByRole('button', { name: '보완 후 정제·저장' })).toBeEnabled())
    expect(screen.getByLabelText('단지명 · 행 1')).toHaveValue('보완 단지')
    fireEvent.click(screen.getByRole('button', { name: '보완 후 정제·저장' }))
    await waitFor(() => expect(saveCorrection).toHaveBeenCalledTimes(2))
    expect(vi.mocked(saveCorrection).mock.calls[1][0].token).toBe('retry-token')
  })

  it('관리자 수정값은 원천으로 덮어쓰지 않고 기존 수정 화면을 사용한다', async () => {
    vi.mocked(getCorrection).mockResolvedValue({ ...detail, managementOnly: true, productId: 338 })
    open()
    expect(await screen.findByText('기존 데이터 관리')).toBeVisible()
    expect(screen.queryByRole('form', { name: '데이터 보완 저장' })).not.toBeInTheDocument()
  })

  it('동시 수정 충돌은 버전을 자동 갱신하지 않고 다시 조회하도록 한다', async () => {
    open()
    vi.mocked(saveCorrection).mockRejectedValue(new ManagementError('다시 조회해 주세요.', 409))
    fireEvent.change(await screen.findByLabelText('단지명 · 행 1'), { target: { value: '내 보완값' } })
    fireEvent.click(screen.getByRole('button', { name: '보완 후 정제·저장' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('다시 조회해 주세요.')
    await waitFor(() => expect(screen.getByRole('button', { name: '보완 후 정제·저장' })).toBeEnabled())
    expect(getCorrection).toHaveBeenCalledTimes(1)
    expect(screen.getByLabelText('단지명 · 행 1')).toHaveValue('내 보완값')
  })
})
