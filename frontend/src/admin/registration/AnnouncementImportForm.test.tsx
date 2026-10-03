import { MemoryRouter } from 'react-router'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
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
  it('JSON 파일의 원문을 검증하고 검토 후 등록하며 입력을 초기화한다', async () => {
    const rawJson = '{"announcement":{"name":"파일 공고"},"rentalDeposit":9007199254740993}'
    vi.mocked(api.validateAnnouncementImport).mockResolvedValue(validationResponse())
    let finishRegistration!: (response: api.AnnouncementImportCreateResponse) => void
    vi.mocked(api.createAnnouncementImport).mockReturnValue(new Promise(resolve => { finishRegistration = resolve }))
    render(<AnnouncementImportForm onSubmittingChange={vi.fn()} />, {wrapper: MemoryRouter})

    const input = screen.getByLabelText('공고 JSON 파일')
    selectFile(jsonFile('공고.JSON', rawJson))
    await waitFor(() => expect(screen.getByLabelText('공고 JSON')).toHaveValue(rawJson))
    expect(screen.getByRole('status')).toHaveTextContent('선택한 파일: 공고.JSON')
    expect(api.validateAnnouncementImport).not.toHaveBeenCalled()
    expect(api.createAnnouncementImport).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'JSON 검증' }))
    expect(await screen.findByText('공고명: 파일 공고')).toBeInTheDocument()
    expect(api.validateAnnouncementImport).toHaveBeenCalledWith(rawJson)
    fireEvent.click(screen.getByRole('button', { name: '검토한 내용으로 등록' }))

    expect(api.createAnnouncementImport).toHaveBeenCalledWith(rawJson, [{ supplyRowIndex: 0, housingComplexId: 42 }])
    expect(input).toBeDisabled()
    expect(screen.getByLabelText('공고 JSON')).toBeDisabled()
    await act(async () => finishRegistration({
      importId: 5, announcementId: 7, supplyRowCount: 1, scheduleCount: 0, attachmentCount: 0, supplyTargetCount: 0,
    }))
    expect(screen.getByRole('status')).toHaveTextContent('공고 #7를 저장했습니다')
    expect(screen.getByLabelText('공고 JSON')).toHaveValue('')
    expect(input).toHaveValue('')
    expect(input).toBeEnabled()
    expect(screen.queryByText(/선택한 파일:/)).not.toBeInTheDocument()
  })

  it('UTF-8 BOM을 제거하고 같은 파일을 다시 가져올 수 있다', async () => {
    render(<AnnouncementImportForm onSubmittingChange={vi.fn()} />, {wrapper: MemoryRouter})
    const file = jsonFile('공고.json', '\uFEFF{"announcement":{"name":"한글 공고"}}')
    selectFile(file)
    const textarea = screen.getByLabelText('공고 JSON')
    await waitFor(() => expect(textarea).toHaveValue('{"announcement":{"name":"한글 공고"}}'))

    fireEvent.change(textarea, { target: { value: '{"수정":true}' } })
    selectFile(file)
    await waitFor(() => expect(textarea).toHaveValue('{"announcement":{"name":"한글 공고"}}'))
  })

  it.each([
    ['공고.txt', '{}', '.json 파일을 선택해 주세요.'],
    ['공고.json', ' \n ', '파일이 비어 있습니다.'],
  ])('잘못된 파일 %s를 가져오면 이전 검증 결과로 등록할 수 없다', async (name, content, message) => {
    vi.mocked(api.validateAnnouncementImport).mockResolvedValue(validationResponse())
    render(<AnnouncementImportForm onSubmittingChange={vi.fn()} />, {wrapper: MemoryRouter})
    fireEvent.change(screen.getByLabelText('공고 JSON'), { target: { value: '{}' } })
    fireEvent.click(screen.getByRole('button', { name: 'JSON 검증' }))
    expect(await screen.findByRole('button', { name: '검토한 내용으로 등록' })).toBeEnabled()

    selectFile(jsonFile(name, content))
    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.queryByRole('button', { name: '검토한 내용으로 등록' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'JSON 검증' })).toBeDisabled()
    expect(api.createAnnouncementImport).not.toHaveBeenCalled()
  })

  it.each([
    ['{', 'JSON 문법을 확인해 주세요.'],
    ['[]', '최상위 값은 JSON 객체여야 합니다.'],
    ['null', '최상위 값은 JSON 객체여야 합니다.'],
  ])('파일 내용 %s는 서버에 보내기 전에 검증한다', async (content, message) => {
    render(<AnnouncementImportForm onSubmittingChange={vi.fn()} />, {wrapper: MemoryRouter})
    selectFile(jsonFile('공고.json', content))
    await waitFor(() => expect(screen.getByLabelText('공고 JSON')).toHaveValue(content))
    fireEvent.click(screen.getByRole('button', { name: 'JSON 검증' }))
    expect(screen.getByRole('alert')).toHaveTextContent(message)
    expect(api.validateAnnouncementImport).not.toHaveBeenCalled()
  })

  it('읽기 실패를 안내하고 파일을 다시 선택해 복구한다', async () => {
    render(<AnnouncementImportForm onSubmittingChange={vi.fn()} />, {wrapper: MemoryRouter})
    const file = jsonFile('공고.json', '{}')
    vi.mocked(file.text).mockRejectedValueOnce(new Error('읽기 실패'))
    selectFile(file)
    expect(await screen.findByRole('alert')).toHaveTextContent('JSON 파일을 읽지 못했습니다.')
    expect(screen.getByRole('button', { name: 'JSON 검증' })).toBeDisabled()

    selectFile(file)
    await waitFor(() => expect(screen.getByLabelText('공고 JSON')).toHaveValue('{}'))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'JSON 검증' })).toBeEnabled()
  })

  it('읽기 중 파일을 바꾸면 늦게 읽은 이전 파일을 무시한다', async () => {
    let finishReading!: (text: string) => void
    const oldFile = jsonFile('이전.json', '{}')
    vi.mocked(oldFile.text).mockReturnValue(new Promise(resolve => { finishReading = resolve }))
    render(<AnnouncementImportForm onSubmittingChange={vi.fn()} />, {wrapper: MemoryRouter})
    selectFile(oldFile)
    expect(screen.getByRole('status')).toHaveTextContent('파일 읽는 중… 이전.json')
    expect(screen.getByRole('button', { name: 'JSON 검증' })).toBeDisabled()

    selectFile(jsonFile('최신.json', '{"새파일":true}'))
    await waitFor(() => expect(screen.getByLabelText('공고 JSON')).toHaveValue('{"새파일":true}'))
    await act(async () => finishReading('{"이전파일":true}'))
    expect(screen.getByLabelText('공고 JSON')).toHaveValue('{"새파일":true}')
    expect(screen.getByRole('status')).toHaveTextContent('선택한 파일: 최신.json')
  })

  it.each(['성공', '실패'])('읽기 중 직접 입력하면 이전 파일 읽기의 %s 결과를 무시한다', async (result) => {
    let finishReading!: (text: string) => void
    let failReading!: (error: Error) => void
    const file = jsonFile('이전.json', '{}')
    vi.mocked(file.text).mockReturnValue(new Promise((resolve, reject) => { finishReading = resolve; failReading = reject }))
    render(<AnnouncementImportForm onSubmittingChange={vi.fn()} />, {wrapper: MemoryRouter})
    selectFile(file)
    fireEvent.change(screen.getByLabelText('공고 JSON'), { target: { value: '{"직접입력":true}' } })
    await act(async () => {
      if (result === '성공') finishReading('{}')
      else failReading(new Error('읽기 실패'))
    })
    expect(screen.getByLabelText('공고 JSON')).toHaveValue('{"직접입력":true}')
    expect(screen.getByRole('button', { name: 'JSON 검증' })).toBeEnabled()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('파일을 선택하면 진행 중인 이전 서버 검증 결과도 폐기한다', async () => {
    let finishValidation!: (response: api.AnnouncementImportValidationResponse) => void
    vi.mocked(api.validateAnnouncementImport).mockReturnValue(new Promise(resolve => { finishValidation = resolve }))
    render(<AnnouncementImportForm onSubmittingChange={vi.fn()} />, {wrapper: MemoryRouter})
    fireEvent.change(screen.getByLabelText('공고 JSON'), { target: { value: '{}' } })
    fireEvent.click(screen.getByRole('button', { name: 'JSON 검증' }))
    selectFile(jsonFile('새공고.json', '{"announcement":{"name":"새 공고"}}'))
    await waitFor(() => expect(screen.getByLabelText('공고 JSON')).toHaveValue('{"announcement":{"name":"새 공고"}}'))
    await act(async () => finishValidation(validationResponse()))
    expect(screen.queryByRole('button', { name: '검토한 내용으로 등록' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'JSON 검증' })).toBeEnabled()
  })

  it('문법 오류는 서버 호출 전에 표시한다', () => {
    render(<AnnouncementImportForm onSubmittingChange={vi.fn()} />, {wrapper: MemoryRouter})

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
    render(<AnnouncementImportForm onSubmittingChange={onSubmittingChange} />, {wrapper: MemoryRouter})

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
      JSON.stringify(document),
      [{ supplyRowIndex: 0, housingComplexId: 42 }],
    ))
    expect(await screen.findByText(/공고 #7를 저장했습니다/)).toBeInTheDocument()
    expect(screen.getByLabelText('공고 JSON')).toHaveValue('')
    expect(onSubmittingChange).toHaveBeenNthCalledWith(1, true)
    expect(onSubmittingChange).toHaveBeenLastCalledWith(false)
  })

  it('등록 전에 원문 URL, 기간, 공급 금액과 일정·첨부를 확인할 수 있다', async () => {
    vi.mocked(api.validateAnnouncementImport).mockResolvedValue(validationResponse())
    render(<AnnouncementImportForm onSubmittingChange={vi.fn()} />, {wrapper: MemoryRouter})
    const rawJson = `{
      "source":{"originalUrl":"https://example.com/notice","sourceDocumentId":"NOTICE-1"},
      "announcement":{"name":"행복주택 모집","applicationStartDate":"2026-09-10","applicationEndDate":"2026-09-12"},
      "supplyRows":[{"complexReference":{"sourceComplexName":"두꺼비 행복주택"},
        "sourceHousingTypeName":"36A","targets":[{"target":"청년","rentalDeposit":9007199254740993}]}],
      "schedules":[{"name":"인터넷 접수","startAt":"2026-09-10T10:00:00"}],
      "attachments":[{"fileName":"공고문.pdf","fileUrl":"https://example.com/notice.pdf"}]
    }`

    fireEvent.change(screen.getByLabelText('공고 JSON'), { target: { value: rawJson } })
    fireEvent.click(screen.getByRole('button', { name: 'JSON 검증' }))

    const review = await screen.findByLabelText('등록할 공고 내용')
    expect(review).toHaveTextContent('https://example.com/notice')
    expect(review).toHaveTextContent('2026-09-12')
    expect(review).toHaveTextContent('36A')
    expect(review).toHaveTextContent('인터넷 접수')
    expect(review).toHaveTextContent('공고문.pdf')
    expect(review).toHaveTextContent('원본 JSON에서 정확한 숫자를 확인해 주세요.')
    expect(review.querySelector('pre')).toHaveTextContent('9007199254740993')
  })

  it('단지를 직접 선택하고 선택을 해제하면 등록 가능 상태가 바뀐다', async () => {
    const response = validationResponse()
    response.supplyRows[0].status = 'SELECTION_REQUIRED'
    response.supplyRows[0].suggestedHousingComplexId = null
    vi.mocked(api.validateAnnouncementImport).mockResolvedValue(response)
    render(<AnnouncementImportForm onSubmittingChange={vi.fn()} />, {wrapper: MemoryRouter})

    fireEvent.change(screen.getByLabelText('공고 JSON'), {
      target: { value: '{"schemaVersion":"admin-announcement-import/v1"}' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'JSON 검증' }))

    const select = await screen.findByRole('combobox', { name: '공급행 1 연결 단지' })
    const submit = screen.getByRole('button', { name: '검토한 내용으로 등록' })
    expect(select).toHaveValue('')
    expect(submit).toBeDisabled()

    fireEvent.change(select, { target: { value: '42' } })
    expect(select).toHaveValue('42')
    expect(submit).toBeEnabled()

    fireEvent.change(select, { target: { value: '' } })
    expect(select).toHaveValue('')
    expect(submit).toBeDisabled()
  })

  it('JSON을 수정하면 이전 검증 결과를 폐기한다', async () => {
    vi.mocked(api.validateAnnouncementImport).mockResolvedValue(validationResponse())
    render(<AnnouncementImportForm onSubmittingChange={vi.fn()} />, {wrapper: MemoryRouter})
    const textarea = screen.getByLabelText('공고 JSON')

    fireEvent.change(textarea, { target: { value: '{"schemaVersion":"admin-announcement-import/v1"}' } })
    fireEvent.click(screen.getByRole('button', { name: 'JSON 검증' }))
    expect(await screen.findByRole('button', { name: '검토한 내용으로 등록' })).toBeEnabled()

    fireEvent.change(textarea, { target: { value: '{"schemaVersion":"changed"}' } })

    expect(screen.queryByRole('button', { name: '검토한 내용으로 등록' })).not.toBeInTheDocument()
  })

  it('재검증 중에는 이전 검증 결과로 등록할 수 없다', async () => {
    let resolveRevalidation!: (response: api.AnnouncementImportValidationResponse) => void
    vi.mocked(api.validateAnnouncementImport)
      .mockResolvedValueOnce(validationResponse())
      .mockReturnValueOnce(new Promise((resolve) => { resolveRevalidation = resolve }))
    render(<AnnouncementImportForm onSubmittingChange={vi.fn()} />, {wrapper: MemoryRouter})

    fireEvent.change(screen.getByLabelText('공고 JSON'), {
      target: { value: '{"schemaVersion":"admin-announcement-import/v1"}' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'JSON 검증' }))
    const submit = await screen.findByRole('button', { name: '검토한 내용으로 등록' })
    expect(submit).toBeEnabled()

    fireEvent.click(screen.getByRole('button', { name: 'JSON 검증' }))
    expect(submit).toBeDisabled()
    await act(async () => resolveRevalidation(validationResponse()))
    expect(submit).toBeEnabled()
  })

  it('검증 중 JSON을 수정하면 늦게 도착한 이전 결과를 무시한다', async () => {
    let resolveFirst!: (response: api.AnnouncementImportValidationResponse) => void
    let resolveSecond!: (response: api.AnnouncementImportValidationResponse) => void
    vi.mocked(api.validateAnnouncementImport)
      .mockReturnValueOnce(new Promise((resolve) => { resolveFirst = resolve }))
      .mockReturnValueOnce(new Promise((resolve) => { resolveSecond = resolve }))
    render(<AnnouncementImportForm onSubmittingChange={vi.fn()} />, {wrapper: MemoryRouter})
    const textarea = screen.getByLabelText('공고 JSON')
    const newDocument = { schemaVersion: 'admin-announcement-import/v1', announcement: { name: '새 공고' } }

    fireEvent.change(textarea, { target: { value: '{"schemaVersion":"admin-announcement-import/v1"}' } })
    fireEvent.click(screen.getByRole('button', { name: 'JSON 검증' }))
    fireEvent.change(textarea, { target: { value: JSON.stringify(newDocument) } })
    fireEvent.click(screen.getByRole('button', { name: 'JSON 검증' }))
    await act(async () => resolveFirst(validationResponse()))

    expect(textarea).toHaveValue(JSON.stringify(newDocument))
    expect(screen.queryByRole('button', { name: '검토한 내용으로 등록' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '검증 중…' })).toBeDisabled()

    await act(async () => resolveSecond(validationResponse()))

    expect(screen.getByText('공고명: 새 공고')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'JSON 검증' })).toBeEnabled()
  })
})

// jsdom File에는 Blob.text가 없어 파일 읽기 경계만 대체한다.
function jsonFile(name: string, content: string): File {
  const file = new File([content], name, { type: 'application/json' })
  Object.defineProperty(file, 'text', { value: vi.fn().mockResolvedValue(content) })
  return file
}

function selectFile(file: File) {
  fireEvent.change(screen.getByLabelText('공고 JSON 파일'), { target: { files: [file] } })
}

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
