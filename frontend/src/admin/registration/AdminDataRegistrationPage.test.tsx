import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AdminDataRegistrationPage } from './AdminDataRegistrationPage'

vi.mock('../ingest/DataPipelineControl', () => ({ DataPipelineControl: () => null }))
vi.mock('../ingest/LocationSummaryUpload', () => ({ LocationSummaryUpload: () => null }))
vi.mock('./HousingComplexRegistrationForm', () => ({
  HousingComplexRegistrationForm: ({ disabled }: { disabled: boolean }) => (
    <button disabled={disabled}>단지 저장</button>
  ),
}))
vi.mock('./AnnouncementRegistrationForm', () => ({
  AnnouncementRegistrationForm: ({ onSubmittingChange }: {
    onSubmittingChange: (isSubmitting: boolean) => void
  }) => (
    <div>
      <button onClick={() => onSubmittingChange(true)}>직접 입력 시작</button>
      <button onClick={() => onSubmittingChange(false)}>직접 입력 완료</button>
    </div>
  ),
}))
vi.mock('./AnnouncementImportForm', () => ({
  AnnouncementImportForm: ({ onSubmittingChange }: {
    onSubmittingChange: (isSubmitting: boolean) => void
  }) => (
    <div>
      <button onClick={() => onSubmittingChange(true)}>JSON 등록 시작</button>
      <button onClick={() => onSubmittingChange(false)}>JSON 등록 완료</button>
    </div>
  ),
}))

describe('관리자 데이터 등록 페이지', () => {
  it('두 공고 등록이 겹치면 하나가 끝나도 단지 등록을 잠근다', () => {
    render(<AdminDataRegistrationPage />)
    const housingSubmit = screen.getByRole('button', { name: '단지 저장' })

    fireEvent.click(screen.getByRole('button', { name: '직접 입력 시작' }))
    fireEvent.click(screen.getByRole('button', { name: 'JSON 등록 시작' }))
    expect(housingSubmit).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'JSON 등록 완료' }))
    expect(housingSubmit).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: '직접 입력 완료' }))
    expect(housingSubmit).toBeEnabled()
  })
})
