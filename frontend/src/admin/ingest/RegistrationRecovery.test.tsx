import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { RegistrationInformationEditor } from './RegistrationInformationEditor'
import { RegistrationFailureHistory } from './RegistrationFailureHistory'
import { getRegistrationInformation, supplementRegistrationInformation, getRegistrationFailures } from './api'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(),
  getRegistrationInformation: vi.fn(), supplementRegistrationInformation: vi.fn(), getRegistrationFailures: vi.fn(),
}))
const information = { fingerprint: 'a'.repeat(64), complexName: null,
  housingTypes: [{ sourceKey: 'row-1', name: null, exclusiveArea: 26 }], supplement: null }
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getRegistrationInformation).mockResolvedValue(information)
  vi.mocked(supplementRegistrationInformation).mockResolvedValue()
})

it('누락된 이름과 근거를 현재 원천에 묶어 저장하고 상태 재조회를 요청한다', async () => {
  const onSaved = vi.fn(), onBusyChange = vi.fn()
  render(<RegistrationInformationEditor identifier="1:HAPPY_HOUSING" disabled={false} onSaved={onSaved} onBusyChange={onBusyChange} onCancel={vi.fn()} />)
  fireEvent.change(await screen.findByLabelText('보완 단지명'), { target: { value: '확인 단지' } })
  fireEvent.change(screen.getByLabelText(/보완 주택형명 1/), { target: { value: '26A' } })
  expect(screen.getByRole('button', { name: '보완값 저장·검증' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('보완 근거'), { target: { value: '공식 공고문 3쪽' } })
  fireEvent.click(screen.getByRole('button', { name: '보완값 저장·검증' }))
  await waitFor(() => expect(onSaved).toHaveBeenCalledOnce())
  expect(supplementRegistrationInformation).toHaveBeenCalledExactlyOnceWith({ sourceComplexIdentifier: '1:HAPPY_HOUSING',
    fingerprint: 'a'.repeat(64), complexName: '확인 단지', housingTypeNames: { 'row-1': '26A' }, evidence: '공식 공고문 3쪽' })
  expect(onBusyChange).toHaveBeenLastCalledWith(false)
})

it('오래된 원천 저장은 오류로 알리고 현재 정보를 다시 읽을 수 있다', async () => {
  vi.mocked(supplementRegistrationInformation).mockRejectedValue(new Error('원천이 변경되었습니다.'))
  const onSaved = vi.fn()
  render(<RegistrationInformationEditor identifier="1:HAPPY_HOUSING" disabled={false} onSaved={onSaved} onBusyChange={vi.fn()} onCancel={vi.fn()} />)
  fireEvent.change(await screen.findByLabelText('보완 근거'), { target: { value: '공고문' } })
  fireEvent.click(screen.getByRole('button', { name: '보완값 저장·검증' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('원천이 변경')
  expect(onSaved).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '현재 정보 다시 조회' }))
  await waitFor(() => expect(getRegistrationInformation).toHaveBeenCalledTimes(2))
})

it('이미 이름이 있는 원천은 보완 입력으로 제공하지 않는다', async () => {
  vi.mocked(getRegistrationInformation).mockResolvedValue({ ...information, complexName: '원천 단지',
    housingTypes: [{ sourceKey: 'row-1', name: '26A', exclusiveArea: 26 }] })
  render(<RegistrationInformationEditor identifier="1:HAPPY_HOUSING" disabled={false} onSaved={vi.fn()} onBusyChange={vi.fn()} onCancel={vi.fn()} />)
  await screen.findByLabelText('보완 근거')
  expect(screen.queryByLabelText('보완 단지명')).not.toBeInTheDocument()
  expect(screen.queryByLabelText(/보완 주택형명/)).not.toBeInTheDocument()
})

it('완료한 단지의 이전 실패도 조회하고 조회 오류를 빈 이력으로 숨기지 않는다', async () => {
  vi.mocked(getRegistrationFailures).mockRejectedValueOnce(new Error('조회 실패')).mockResolvedValue({
    failures: [{ detail: '저장 실패', failedAt: '2026-11-10T00:00:00Z', actor: 'admin', executionId: 'run-1' }], totalElements: 1, totalPages: 1,
  })
  render(<RegistrationFailureHistory identifier="1:HAPPY_HOUSING" />)
  expect(await screen.findByRole('alert')).toHaveTextContent('조회 실패')
  expect(screen.queryByText(/기록된 보류·실패 이력이 없습니다/)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '실패 이력 다시 조회' }))
  expect(await screen.findByText('저장 실패')).toBeVisible()
})
