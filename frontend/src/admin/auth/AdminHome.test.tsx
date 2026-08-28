import { act, fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  AdminRegistrationApiError,
  type AnnouncementCreateResponse,
  type HousingComplexCreateResponse,
} from '../registration/api'
import { AdminHome } from './AdminHome'

const apiMocks = vi.hoisted(() => ({
  createAnnouncement: vi.fn(),
  createHousingComplex: vi.fn(),
}))

vi.mock('../registration/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../registration/api')>()),
  ...apiMocks,
}))

beforeEach(() => {
  apiMocks.createAnnouncement.mockReset()
  apiMocks.createHousingComplex.mockReset()
})

describe('AdminHome', () => {
  it('단지와 공고 등록 폼을 표시하고 단지가 없으면 공고 저장을 막는다', () => {
    render(<AdminHome />)

    expect(screen.getByRole('heading', { name: '단지 등록' })).toBeVisible()
    expect(screen.getByRole('heading', { name: '공고 등록' })).toBeVisible()
    expect(screen.getByRole('button', { name: '단지 저장' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '공고 저장' })).toBeDisabled()
  })

  it('단지 저장 중 버튼을 잠그고 성공한 단지를 공고 폼에 자동 선택한다', async () => {
    const response = deferred<HousingComplexCreateResponse>()
    apiMocks.createHousingComplex.mockReturnValue(response.promise)
    render(<AdminHome />)
    fillHousingForm()

    submitWithButton('단지 저장')

    expect(screen.getByRole('button', { name: '단지 저장 중…' })).toBeDisabled()
    expect(apiMocks.createHousingComplex).toHaveBeenCalledWith(
      expect.objectContaining({
        name: '두꺼비 행복주택',
        address: expect.objectContaining({
          roadAddress: '서울시 중구 세종대로 1',
          latitude: 37.5665,
          longitude: 126.978,
        }),
        totalHouseholdCount: 100,
      }),
    )

    await act(async () => {
      response.resolve(housingResponse())
    })

    expect(screen.getByText(/선택 단지:/)).toHaveTextContent(
      '선택 단지: 두꺼비 행복주택 · 서울시 중구 세종대로 1',
    )
    expect(screen.getByRole('button', { name: '공고 저장' })).toBeEnabled()
    expect(screen.getByText('두꺼비 행복주택 단지를 저장했습니다.')).toHaveAttribute(
      'role',
      'status',
    )
    expect(screen.getByLabelText('단지명')).toHaveValue('')
  })

  it('단지 저장 실패 시 입력값과 필드 오류를 유지한다', async () => {
    apiMocks.createHousingComplex.mockRejectedValue(
      new AdminRegistrationApiError(400, '요청값이 올바르지 않습니다.', {
        name: '필수 값입니다.',
      }),
    )
    render(<AdminHome />)
    fillHousingForm()

    submitWithButton('단지 저장')

    expect(await screen.findByRole('alert')).toHaveTextContent('요청값이 올바르지 않습니다.')
    expect(screen.getByLabelText('단지명')).toHaveValue('두꺼비 행복주택')
    expect(screen.getByLabelText('단지명')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('필수 값입니다.')).toBeVisible()
  })

  it('선택된 단지 ID와 단일 공급행으로 공고를 저장하고 성공할 때만 초기화한다', async () => {
    apiMocks.createHousingComplex.mockResolvedValue(housingResponse())
    const announcementResponse = deferred<AnnouncementCreateResponse>()
    apiMocks.createAnnouncement.mockReturnValue(announcementResponse.promise)
    render(<AdminHome />)
    fillHousingForm()
    submitWithButton('단지 저장')
    await screen.findByText(/선택 단지:/)
    fillAnnouncementForm()

    submitWithButton('공고 저장')

    expect(screen.getByRole('button', { name: '공고 저장 중…' })).toBeDisabled()
    expect(apiMocks.createAnnouncement).toHaveBeenCalledWith({
      housingComplexId: 42,
      name: '2026년 두꺼비 행복주택 입주자 모집',
      rentalType: 'HAPPY_HOUSING',
      recruitmentType: 'NEW',
      agencyCode: 'LH',
      postedDate: '2026-08-01',
      applicationStartDate: '2026-08-10',
      applicationEndDate: '2026-08-14',
      winnerAnnouncementDate: '2026-09-01',
      originalUrl: 'https://example.com/announcement',
      receptionPlace: {
        name: 'LH 청약센터',
        method: 'ONLINE',
        address: null,
        contact: '1600-1004',
        url: null,
      },
      supplyRow: {
        sourceComplexName: '원문 두꺼비 단지',
        sourceHousingTypeName: '36A',
        supplyPnu: '1114010100100010000',
        expectedMoveInMonth: '2027-03',
        supplyCategory: 'NEW_SUPPLY',
        totalSupplyHouseholdCount: 20,
      },
    })

    await act(async () => {
      announcementResponse.resolve({
        announcementId: 7,
        supplyRowId: 9,
        housingComplexId: 42,
        name: '2026년 두꺼비 행복주택 입주자 모집',
      })
    })

    expect(screen.getByText('2026년 두꺼비 행복주택 입주자 모집 공고를 저장했습니다.')).toHaveAttribute(
      'role',
      'status',
    )
    expect(screen.getByLabelText('공고명')).toHaveValue('')
  })

  it('공고 저장 중에는 선택 단지를 바꿀 수 없다', async () => {
    apiMocks.createHousingComplex.mockResolvedValue(housingResponse())
    const announcementResponse = deferred<AnnouncementCreateResponse>()
    apiMocks.createAnnouncement.mockReturnValue(announcementResponse.promise)
    render(<AdminHome />)
    fillHousingForm()
    submitWithButton('단지 저장')
    await screen.findByText(/선택 단지:/)
    fillAnnouncementForm()

    submitWithButton('공고 저장')

    expect(screen.getByRole('button', { name: '단지 저장' })).toBeDisabled()
    expect(screen.getByLabelText('단지명')).toBeDisabled()

    await act(async () => {
      announcementResponse.resolve({
        announcementId: 7,
        supplyRowId: 9,
        housingComplexId: 42,
        name: '2026년 두께비 행복주택 입주자 모집',
      })
    })

    expect(screen.getByRole('button', { name: '단지 저장' })).toBeEnabled()
  })

  it('공고 저장 실패 시 입력값을 유지한다', async () => {
    apiMocks.createHousingComplex.mockResolvedValue(housingResponse())
    apiMocks.createAnnouncement.mockRejectedValue(
      new AdminRegistrationApiError(400, '접수 기간이 올바르지 않습니다.'),
    )
    render(<AdminHome />)
    fillHousingForm()
    submitWithButton('단지 저장')
    await screen.findByText(/선택 단지:/)
    fillAnnouncementForm()

    submitWithButton('공고 저장')

    expect(await screen.findByRole('alert')).toHaveTextContent('접수 기간이 올바르지 않습니다.')
    expect(screen.getByLabelText('공고명')).toHaveValue(
      '2026년 두꺼비 행복주택 입주자 모집',
    )
  })
})

function fillHousingForm() {
  change('단지명', '두꺼비 행복주택')
  change('준공일', '2026-01-01')
  change('도로명주소', '서울시 중구 세종대로 1')
  change('PNU', '1114010100100010000')
  change('법정동 코드', '11140101')
  change('시·도 코드', '11')
  change('시·군·구 코드', '140')
  change('위도', '37.566500')
  change('경도', '126.978000')
  change('전체 세대수', '100')
  change('주차대수', '80')
  change('최근 1년 퇴거자 수', '5')
}

function fillAnnouncementForm() {
  change('공고명', '2026년 두꺼비 행복주택 입주자 모집')
  change('게시일', '2026-08-01')
  change('접수 시작일', '2026-08-10')
  change('접수 종료일', '2026-08-14')
  change('당첨자 발표일', '2026-09-01')
  change('공식 원문 URL', 'https://example.com/announcement')
  change('접수처명', 'LH 청약센터')
  change('접수처 연락처', '1600-1004')
  change('원문 단지명', '원문 두꺼비 단지')
  change('원문 주택형명', '36A')
  change('공급 PNU', '1114010100100010000')
  change('입주 예정 연월', '2027-03')
  change('공급세대수', '20')
}

function change(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

function submitWithButton(name: string) {
  const form = screen.getByRole('button', { name }).closest('form')
  if (!form) {
    throw new Error(`${name} 버튼의 폼을 찾을 수 없습니다.`)
  }
  fireEvent.submit(form)
}

function housingResponse(): HousingComplexCreateResponse {
  return {
    housingComplexId: 42,
    name: '두꺼비 행복주택',
    roadAddress: '서울시 중구 세종대로 1',
  }
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolvePromise: (value: T) => void
  const promise = new Promise<T>((resolve) => {
    resolvePromise = resolve
  })
  return { promise, resolve: resolvePromise! }
}
