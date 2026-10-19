import { fireEvent, render, screen, within } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { SupplyImportPanel } from './SupplyImportPanel'
import { blankRow, blankTarget } from './supplyCorrectionApi'

it('보정 JSON 안내에서 조건 ID를 확인하고 기존 조건에만 선택 적용한다', () => {
  const onMerge = vi.fn()
  const row = { ...blankRow(0), id: 7, key: 'row:7', complexName: '한스빌', typeName: '26A',
    targets: [{ ...blankTarget(0), id: 12, key: 'target:12', target: '청년', rent: '100000' }] }
  render(<SupplyImportPanel rows={[row]} disabled={false} onMerge={onMerge} onDirtyChange={vi.fn()} />)
  fireEvent.click(screen.getByText('구조화한 공급정보를 변경안으로 가져오기', { selector: 'summary' }))
  fireEvent.click(screen.getByText('공급정보 보정 JSON 작성 방법과 예제', { selector: 'summary' }))
  fireEvent.click(screen.getByText('이 공고의 기존 조건 ID 확인', { selector: 'summary' }))
  expect(within(screen.getByRole('table', { name: 'JSON에서 수정할 수 있는 기존 조건' })).getByText('12')).toBeVisible()
  expect((screen.getByLabelText('공급정보 보정 JSON 작성 방법과 예제 Codex 요청문') as HTMLTextAreaElement).value).toContain('"targetId": 12')
  fireEvent.change(screen.getByLabelText('공급정보 JSON'), { target: { value: '{"areaUnit":"m2","moneyUnit":"KRW","rows":[{"targets":[{"id":12,"rent":"200000"}]}]}' } })
  fireEvent.click(screen.getByRole('button', { name: '입력행 확인' }))
  expect(onMerge).not.toHaveBeenCalled()
  fireEvent.change(screen.getByLabelText('입력 1의 적용 대상'), { target: { value: 'row:7' } })
  fireEvent.click(screen.getByRole('button', { name: '선택한 행을 변경안에 추가' }))
  expect(onMerge).toHaveBeenCalledWith([expect.objectContaining({ id: 7, targets: [expect.objectContaining({ id: 12, rent: '200000' })] })])
})
