import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ConditionInputDialog, type ConditionInputDialogProps } from './ConditionInputDialog'
import { LATER, emptyDraft, type ConditionDraft } from './conditionDraft'

const filled: ConditionDraft = {
  answers: {
    BIRTH_DATE: '1996-03-02', RESIDENCE_REGION: '11140', CURRENT_REGION_RESIDENCE_MONTHS: 24, CURRENT_LEASE_END_DATE: '2027-05-01', IS_UNIVERSITY_STUDENT: false, IS_JOB_SEEKER: false,
    APPLICANT_OWNS_HOUSE: false, APPLICANT_OWNS_CAR: false, APPLICANT_ASSETS: LATER, APPLICANT_MONTHLY_INCOME: 2_300_000,
    FAMILY_TYPE: 'UNMARRIED', IS_HOUSEHOLD_SEPARATED: true, REGISTERED_FAMILY_COUNT: 1,
  },
  familyDetails: 'SKIPPED',
  provinceCode: '11',
}

function renderDialog(props: Partial<ConditionInputDialogProps> = {}) {
  const handlers = { onDraftChange: vi.fn(), onSubmit: vi.fn(), onClose: vi.fn() }
  render(<ConditionInputDialog request={{ mode: 'full', step: 1, origin: 'detail' }} initialDraft={emptyDraft()} editing={false} member={false}
    initialMaxStep={1} today="2026-11-06" saving={false} {...handlers} {...props} />)
  return handlers
}

const next = () => fireEvent.click(screen.getByRole('button', { name: '다음' }))

describe('ConditionInputDialog full mode', () => {
  it('blocks moving forward until the current step is answered', () => {
    renderDialog()
    next()
    expect(screen.getByText('1 / 3 단계')).toBeInTheDocument()
    expect(screen.getAllByRole('alert').map((alert) => alert.textContent)).toEqual(['날짜를 입력해 주세요.', '시·군·구까지 골라 주세요.', '답을 골라 주세요.', '날짜를 입력해 주세요.', '답을 골라 주세요.', '답을 골라 주세요.'])
  })

  it('keeps the specific date error when moving on with an invalid date', () => {
    renderDialog()
    fireEvent.change(screen.getByRole('textbox', { name: '생년월일을 알려주세요' }), { target: { value: '20100101' } })
    next()
    expect(screen.getAllByRole('alert')[0]).toHaveTextContent('만 19세 이상부터 이용할 수 있어요.')
  })

  it('tells that conditions are kept on this device', () => {
    renderDialog({ request: { mode: 'full', step: 4, origin: 'detail' }, initialDraft: filled, member: true, initialMaxStep: 4 })
    expect(screen.getByText(/내 계정에 보관돼요/)).toBeInTheDocument()
  })

  it('marks steps already visited after going back and lets the user jump ahead again', () => {
    renderDialog({ initialDraft: filled })
    next()
    expect(screen.getByText('2 / 3 단계')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '이전 단계' }))
    expect(screen.getByText('1 / 3 단계')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '소득·자산 단계로 이동' }))
    expect(screen.getByText('2 / 3 단계')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '본인 정보 단계로 돌아가기' })).toBeInTheDocument()
  })

  it('starts with every step reachable when editing a saved profile', () => {
    renderDialog({ initialDraft: filled, editing: true, member: true, initialMaxStep: 4 })
    expect(screen.getByRole('heading', { name: '내 조건 수정' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '가족·세대 단계로 이동' })).toBeInTheDocument()
  })

  it('labels the final button by membership and origin', () => {
    const { unmount } = render(<ConditionInputDialog request={{ mode: 'full', step: 4, origin: 'detail' }} initialDraft={filled} editing={false} member={false}
      initialMaxStep={4} today="2026-11-06" saving={false} onDraftChange={vi.fn()} onSubmit={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByRole('button', { name: '로그인하고 이 공고와 비교하기' })).toBeInTheDocument()
    expect(screen.getByText('카카오·Google 간편 로그인 후 바로 보여 드려요. 입력한 내용도 함께 저장돼요.')).toBeInTheDocument()
    unmount()
    const handlers = renderDialog({ request: { mode: 'full', step: 4, origin: 'list' }, initialDraft: filled, member: true, initialMaxStep: 4 })
    fireEvent.click(screen.getByRole('button', { name: '지원 가능한 공고 확인하기' }))
    expect(handlers.onSubmit).toHaveBeenCalledWith(filled)
  })

  it('says 저장하기 when editing or starting from My Page', () => {
    renderDialog({ request: { mode: 'full', step: 4, origin: 'mypage' }, initialDraft: filled, member: true, initialMaxStep: 4 })
    expect(screen.getByRole('button', { name: '저장하기' })).toBeInTheDocument()
  })

  it('shows missing answers as 미입력 in the done summary', () => {
    renderDialog({ request: { mode: 'full', step: 4, origin: 'detail' }, initialDraft: filled, member: true, initialMaxStep: 4 })
    expect(screen.getByRole('heading', { name: '입력을 마쳤어요' })).toBeInTheDocument()
    expect(screen.getByText('미입력 5개는 나중에 채울 수 있어요')).toBeInTheDocument()
    const money = screen.getByRole('region', { name: '2. 소득·자산' })
    expect(within(money).getByText('본인 자산').nextElementSibling).toHaveTextContent('미입력')
    expect(within(money).getByText('월 230만 원')).toBeInTheDocument()
    fireEvent.click(within(money).getByRole('button', { name: '수정' }))
    expect(screen.getByText('2 / 3 단계')).toBeInTheDocument()
  })

  it('shows autosave only for a member entering a new profile', () => {
    renderDialog({ initialDraft: filled, member: true })
    expect(screen.getByText('자동 저장됨')).toBeInTheDocument()
  })

  it('asks before a guest leaves with typed answers', () => {
    const handlers = renderDialog({ initialDraft: filled })
    fireEvent.click(screen.getByRole('button', { name: '닫기' }))
    const confirm = screen.getByRole('alertdialog', { name: '입력을 그만둘까요?' })
    fireEvent.click(within(confirm).getByRole('button', { name: '계속 입력하기' }))
    expect(handlers.onClose).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '닫기' }))
    fireEvent.click(screen.getByRole('button', { name: '그만두기' }))
    expect(handlers.onClose).toHaveBeenCalled()
  })

  it('asks to save when closing an edited profile', () => {
    const handlers = renderDialog({ initialDraft: filled, editing: true, member: true, initialMaxStep: 4 })
    fireEvent.click(within(screen.getByRole('radiogroup', { name: '대학생인가요?' })).getByRole('radio', { name: '예' }))
    fireEvent.click(screen.getByRole('button', { name: '닫기' }))
    fireEvent.click(within(screen.getByRole('alertdialog', { name: '수정한 내용을 저장할까요?' })).getByRole('button', { name: '저장하기' }))
    expect(handlers.onSubmit).toHaveBeenCalledWith(expect.objectContaining({ answers: expect.objectContaining({ IS_UNIVERSITY_STUDENT: true }) }))
  })
})

describe('ConditionInputDialog partial mode', () => {
  it('renders only the requested fields and requires a value', () => {
    const handlers = renderDialog({ request: { mode: 'partial', fields: ['APPLICANT_ASSETS'], origin: 'detail' }, initialDraft: filled, member: true })
    expect(screen.getByRole('heading', { name: '본인 자산 입력' })).toBeInTheDocument()
    expect(screen.getByText('저장하면 이 공고와 바로 다시 비교해요.')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: '나중에 입력할게요' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '저장하고 다시 비교' }))
    expect(screen.getByRole('alert')).toHaveTextContent('값을 입력해 주세요.')
    fireEvent.change(screen.getByRole('textbox', { name: '본인 자산은 모두 얼마인가요?' }), { target: { value: '3000' } })
    fireEvent.click(screen.getByRole('button', { name: '저장하고 다시 비교' }))
    expect(handlers.onSubmit).toHaveBeenCalledWith(expect.objectContaining({ answers: expect.objectContaining({ APPLICANT_ASSETS: 30_000_000 }) }))
  })

  it('opens family details when a family field is requested', () => {
    const handlers = renderDialog({ request: { mode: 'partial', fields: ['FAMILY_OWNS_HOUSE', 'FAMILY_TOTAL_ASSETS'], origin: 'mypage' }, initialDraft: filled, member: true })
    expect(screen.getByRole('heading', { name: '추가 정보 입력' })).toBeInTheDocument()
    fireEvent.click(within(screen.getByRole('radiogroup', { name: '가족 중 주택을 가진 사람이 있나요?' })).getByRole('radio', { name: '아니요' }))
    fireEvent.change(screen.getByRole('textbox', { name: '가족의 총자산은 얼마인가요?' }), { target: { value: '5000' } })
    fireEvent.click(screen.getByRole('button', { name: '저장하기' }))
    expect(handlers.onSubmit).toHaveBeenCalledWith(expect.objectContaining({ familyDetails: 'PROVIDED' }))
  })

  it('cancels without confirmation', () => {
    const handlers = renderDialog({ request: { mode: 'partial', fields: ['APPLICANT_ASSETS'], origin: 'detail' }, initialDraft: filled, member: true })
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(handlers.onClose).toHaveBeenCalled()
  })
})
