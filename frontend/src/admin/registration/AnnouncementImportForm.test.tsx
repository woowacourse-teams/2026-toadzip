import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AnnouncementImportForm } from './AnnouncementImportForm'
import * as api from './api'

vi.mock('./api', async () => {
  const actual = await vi.importActual<typeof import('./api')>('./api')
  return {
    ...actual,
    validateAnnouncementImport: vi.fn(),
    createAnnouncementImport: vi.fn(),
  }
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('공고 JSON 가져오기', () => {
  it('문법 오류는 서버 호출 전에 표시한다', () => {
    render(<AnnouncementImportForm onSubmittingChange={vi.fn()} />)

    fireEvent.change(screen.getByLabelText('공고 JSON'), { target: { value: '{' } })
    fireEvent.click(screen.getByRole('button', { name: 'JSON 검증' }))

    expect(screen.getByRole('alert')).toHaveTextContent('JSON 문법을 확인해 주세요.')
    expect(api.validateAnnouncementImport).not.toHaveBeenCalled()
  })

  it('검증 후 자동 단지 후보를 표시하고 명시적으로 등록한다', async () => {
    vi.mocked(api.validateAnnouncementImport).mockResolvedValue(validationResponse())
    vi.mocked(api.createAnnouncementImport).mockResolvedValue({
      importId: 5,
      announcementId: 7,
      supplyRowCount: 1,
      scheduleCount: 0,
      attachmentCount: 0,
      supplyTargetCount: 0,
    })
    const onSubmittingChange = vi.fn()
    render(<AnnouncementImportForm onSubmittingChange={onSubmittingChange} />)

    const document = {
      schemaVersion: 'admin-announcement-import/v1',
      announcement: { name: '행복주택 입주자 모집' },
      schedules: [],
      attachments: [],
    }
    fireEvent.change(screen.getByLabelText('공고 JSON'), {
      target: { value: JSON.stringify(document) },
    })
    fireEvent.click(screen.getByRole('button', { name: 'JSON 검증' }))

    expect(await screen.findByText('공고명: 행복주택 입주자 모집')).toBeInTheDocument()
    expect(screen.getByLabelText('공급행 1 연결 단지')).toHaveValue('42')
    fireEvent.click(screen.getByRole('button', { name: '검토한 내용으로 등록' }))

    await waitFor(() => expect(api.createAnnouncementImport).toHaveBeenCalledWith(
      document,
      [{ supplyRowIndex: 0, housingComplexId: 42 }],
    ))
    expect(await screen.findByText(/공고 #7를 저장했습니다/)).toBeInTheDocument()
    expect(screen.getByLabelText('공고 JSON')).toHaveValue('')
    expect(onSubmittingChange).toHaveBeenNthCalledWith(1, true)
    expect(onSubmittingChange).toHaveBeenLastCalledWith(false)
  })

  it('JSON을 수정하면 이전 검증 결과를 폐기한다', async () => {
    vi.mocked(api.validateAnnouncementImport).mockResolvedValue(validationResponse())
    render(<AnnouncementImportForm onSubmittingChange={vi.fn()} />)
    const textarea = screen.getByLabelText('공고 JSON')

    fireEvent.change(textarea, { target: { value: '{"schemaVersion":"admin-announcement-import/v1"}' } })
    fireEvent.click(screen.getByRole('button', { name: 'JSON 검증' }))
    expect(await screen.findByRole('button', { name: '검토한 내용으로 등록' })).toBeEnabled()

    fireEvent.change(textarea, { target: { value: '{"schemaVersion":"changed"}' } })

    expect(screen.queryByRole('button', { name: '검토한 내용으로 등록' })).not.toBeInTheDocument()
  })
})

function validationResponse(): api.AnnouncementImportValidationResponse {
  return {
    schemaVersion: 'admin-announcement-import/v1',
    jsonHash: 'a'.repeat(64),
    registerable: true,
    duplicated: false,
    errors: [],
    warnings: [],
    unresolvedFields: [],
    supplyRows: [{
      supplyRowIndex: 0,
      sourceComplexName: '두꺼비 행복주택',
      pnu: '1114010100100010000',
      status: 'AUTO_SELECTED',
      suggestedHousingComplexId: 42,
      candidates: [{
        housingComplexId: 42,
        name: '두꺼비 행복주택',
        roadAddress: '서울시 중구 세종대로 1',
        rentalType: 'HAPPY_HOUSING',
        agencyCode: 'LH',
      }],
    }],
  }
}
