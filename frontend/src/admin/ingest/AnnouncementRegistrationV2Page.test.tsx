import type { ComponentProps } from 'react'
import type { DataPipelineControl } from './DataPipelineControl'
import { PipelineExecutionSteps } from './PipelineExecutionSteps'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AnnouncementRegistrationV2Page } from './AnnouncementRegistrationV2Page'
import { getDataPipelineExecution, getDataPipelineStatus, startAnnouncementRegistrationUrl } from './api'
import type { DataPipelineExecution } from './api'

vi.mock('./api', () => ({
  getDataPipelineExecution: vi.fn(),
  getDataPipelineStatus: vi.fn(),
  startAnnouncementRegistrationUrl: vi.fn(),
}))
vi.mock('./DataPipelineControl', () => ({
  DataPipelineControl: ({ domain, registration }: NonNullable<ComponentProps<typeof DataPipelineControl>>) => <div>
    수집 실행
    {domain === 'announcement' && <>{registration?.action}{registration?.form}
      {registration?.execution && <PipelineExecutionSteps execution={registration.execution} />}</>}
  </div>,
}))
const url = 'https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=21026'
vi.mock('./PipelineHistory', () => ({ PipelineHistory: () => <div>실행 이력</div> }))
vi.mock('./IngestWorkspacePanel', () => ({ IngestWorkspacePanel: () => <div>보완 대상</div> }))

function execution(status: DataPipelineExecution['status']): DataPipelineExecution {
  return {
    executionId: 'registration-1', type: 'ANNOUNCEMENT_REGISTRATION', status,
    currentStepName: '마이홈 공고 수집', currentStepIndex: 1, totalStepCount: 4,
    completedSteps: [], skippedSteps: [], partiallyFailedSteps: [], failure: null,
    targetAnnouncementIdentifier: '21026',
  }
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getDataPipelineStatus).mockResolvedValue(execution('IDLE'))
})
afterEach(() => { cleanup(); vi.useRealTimers() })

async function openForm() {
  render(<MemoryRouter><AnnouncementRegistrationV2Page /></MemoryRouter>)
  await waitFor(() => expect(screen.getByRole('button', { name: '공고 등록하기' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '공고 등록하기' }))
}

describe('공고 단건 등록 v2', () => {
  it('완료된 실행은 새로고침 때 복원하지 않고 원천·보완 목록을 표시하지 않는다', async () => {
    vi.mocked(getDataPipelineStatus).mockResolvedValue(execution('COMPLETED'))
    await openForm()
    expect(screen.queryByRole('article', { name: '현재 작업 단계' })).not.toBeInTheDocument()
    expect(screen.queryByText('보완 대상')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('마이홈 공고 ID')).not.toBeInTheDocument()
  })

  it('공고가 기본 탭이고 단지 탭에서는 공고 등록 폼을 숨긴다', async () => {
    await openForm()
    expect(screen.getByRole('tab', { name: '공고' })).toHaveAttribute('aria-selected', 'true')
    fireEvent.click(screen.getByRole('tab', { name: '단지' }))
    expect(screen.getByRole('tab', { name: '단지' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByRole('form', { name: '공고 단건 등록' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: '공고' }))
    expect(screen.getByLabelText('마이홈 공고 URL')).toBeVisible()
  })
  it('빈 값은 제출하지 않으며 API 키 입력을 요구하지 않는다', async () => {
    await openForm()
    fireEvent.change(screen.getByLabelText('마이홈 공고 URL'), { target: { value: ' ' } })
    fireEvent.click(screen.getByRole('button', { name: '등록 실행' }))
    expect(screen.getByRole('alert')).toHaveTextContent('공고 URL을 입력')
    expect(startAnnouncementRegistrationUrl).not.toHaveBeenCalled()
    expect(screen.queryByLabelText(/API 키/)).not.toBeInTheDocument()
  })

  it('접수 중 중복 제출을 차단하고 공백을 제거한 URL를 전송한다', async () => {
    vi.mocked(startAnnouncementRegistrationUrl).mockReturnValue(new Promise(() => {}))
    await openForm()
    fireEvent.change(screen.getByLabelText('마이홈 공고 URL'), { target: { value: ` ${url} ` } })
    fireEvent.click(screen.getByRole('button', { name: '등록 실행' }))
    expect(startAnnouncementRegistrationUrl).toHaveBeenCalledWith(url)
    expect(screen.getByRole('button', { name: '등록 처리 중…' })).toBeDisabled()
    expect(screen.getByLabelText('마이홈 공고 URL')).toBeDisabled()
  })

  it('실행 ID로 완료를 확인하면 공고 관리 링크를 표시한다', async () => {
    vi.mocked(startAnnouncementRegistrationUrl).mockResolvedValue(execution('RUNNING'))
    vi.mocked(getDataPipelineExecution).mockResolvedValue(execution('COMPLETED'))
    await openForm()
    vi.useFakeTimers()
    fireEvent.change(screen.getByLabelText('마이홈 공고 URL'), { target: { value: url } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '등록 실행' })) })
    await act(async () => { await vi.advanceTimersByTimeAsync(1500) })
    expect(getDataPipelineExecution).toHaveBeenCalledWith('registration-1')
    expect(screen.getByRole('link', { name: '공고 관리로 이동' })).toHaveAttribute('href', '/admin/announcements')
  })

  it.each(['이미 등록된 공고입니다.', '공고를 찾을 수 없습니다.', '외부 API 호출 제한에 도달했습니다.',
    '마이홈 외부 API 수집에 실패했습니다.', '정제 실패: 공급행 매칭 실패'])(
    '서버의 실패 사유 %s를 표시하고 재시도를 허용한다', async message => {
    vi.mocked(startAnnouncementRegistrationUrl).mockResolvedValue({
      ...execution('FAILED'), failure: { stepName: null, message, serverResponse: null },
    })
    await openForm()
    fireEvent.change(screen.getByLabelText('마이홈 공고 URL'), { target: { value: url } })
    fireEvent.click(screen.getByRole('button', { name: '등록 실행' }))
    await waitFor(() => expect(screen.getByText(message)).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '등록 실행' })).toBeEnabled()
  })

  it('URL 검증 오류는 단계 실행 없이 안내한다', async () => {
    vi.mocked(startAnnouncementRegistrationUrl).mockRejectedValue(new Error('잘못된 공고 URL입니다.'))
    await openForm()
    fireEvent.change(screen.getByLabelText('마이홈 공고 URL'), { target: { value: 'https://apply.lh.or.kr' } })
    fireEvent.click(screen.getByRole('button', { name: '등록 실행' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('잘못된 공고 URL')
    expect(screen.queryByRole('article', { name: '현재 작업 단계' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '등록 실행' })).toBeEnabled()
  })

  it('새로고침 후에도 진행 중인 실행을 복구한다', async () => {
    vi.mocked(getDataPipelineStatus).mockResolvedValue(execution('RUNNING'))
    render(<MemoryRouter><AnnouncementRegistrationV2Page /></MemoryRouter>)
    await waitFor(() => expect(screen.getByRole('article', { name: '현재 작업 단계' })).toBeVisible())
    expect(screen.getByRole('button', { name: '공고 등록하기' })).toBeDisabled()
    expect(screen.queryByLabelText('마이홈 공고 URL')).not.toBeInTheDocument()
    expect(startAnnouncementRegistrationUrl).not.toHaveBeenCalled()
  })
})
