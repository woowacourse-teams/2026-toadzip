import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { HousingTypeCreator } from './HousingTypeCreator'
import { ManagementError } from './api'

const mocks = vi.hoisted(() => ({ requestManagementApi: vi.fn(), parseManagementDetail: vi.fn((value: unknown) => value) }))
vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(), requestManagementApi: mocks.requestManagementApi }))
vi.mock('./managementContract', async original => ({ ...await original<typeof import('./managementContract')>(), parseManagementDetail: mocks.parseManagementDetail }))
beforeEach(() => vi.clearAllMocks())

function show(onSaved = vi.fn(), onClose = vi.fn()) {
  render(<HousingTypeCreator complexId="3503" version={4} onSaved={onSaved} onClose={onClose} onBusyChange={vi.fn()} />)
  return { onSaved, onClose }
}

it('이름과 전용면적을 넣어야 추가할 수 있고, 비운 선택 값은 null로 보낸다', async () => {
  mocks.requestManagementApi.mockResolvedValue({ saved: true })
  const { onSaved, onClose } = show()
  expect(screen.getByRole('button', { name: '주택형 추가' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('주택형 이름'), { target: { value: ' 16A ' } })
  fireEvent.change(screen.getByLabelText('전용면적 (㎡)'), { target: { value: '16.37' } })
  fireEvent.click(screen.getByRole('button', { name: '주택형 추가' }))
  await waitFor(() => expect(onSaved).toHaveBeenCalledWith({ saved: true }))
  expect(mocks.requestManagementApi).toHaveBeenCalledWith('/api/admin/housing-complexes/3503/housing-types', 'POST',
    { version: 4, name: '16A', exclusiveArea: 16.37, supplyArea: null, householdCount: null })
  expect(onClose).toHaveBeenCalled()
})

it('같은 이름 등으로 거절되면 입력을 유지하고 이유를 보여 준다', async () => {
  mocks.requestManagementApi.mockRejectedValue(new ManagementError('이 단지에 같은 이름의 주택형이 있습니다.', 409))
  const { onSaved } = show()
  fireEvent.change(screen.getByLabelText('주택형 이름'), { target: { value: '16A' } })
  fireEvent.change(screen.getByLabelText('전용면적 (㎡)'), { target: { value: '16.37' } })
  fireEvent.click(screen.getByRole('button', { name: '주택형 추가' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('같은 이름의 주택형이 있습니다.')
  expect(screen.getByLabelText('주택형 이름')).toHaveValue('16A')
  expect(onSaved).not.toHaveBeenCalled()
})
