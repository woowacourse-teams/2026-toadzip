import { render, screen, within } from '@testing-library/react'
import { expect, it } from 'vitest'
import { StoredDataTable } from './StoredDataTable'

it('중첩된 객체·배열의 항목 경로와 원천값을 모두 보존한다', () => {
  render(<StoredDataTable label="원천 데이터" data={{
    unknownField: '원천 그대로', supplyRows: [{ totalSupplyHouseholdCount: 0, enabled: false, nested: ['첫째', '둘째'] }],
  }} />)
  const table = screen.getByRole('table', { name: '원천 데이터' })
  const zero = within(table).getByRole('rowheader', { name: /supplyRows\[0\]\.totalSupplyHouseholdCount/ }).closest('tr')!
  expect(within(zero).getByRole('cell')).toHaveTextContent(/^0$/)
  const disabled = within(table).getByRole('rowheader', { name: 'supplyRows[0].enabled' }).closest('tr')!
  expect(within(disabled).getByRole('cell')).toHaveTextContent(/^false$/)
  expect(within(table).getByRole('row', { name: 'supplyRows[0].nested[1] 둘째' })).toBeVisible()
  expect(within(table).getByRole('row', { name: 'unknownField 원천 그대로' })).toBeVisible()
  expect(table.querySelector('pre')).toBeNull()
})

it('null, 미기록, 빈 문자열, 빈 배열과 빈 객체를 구분한다', () => {
  render(<StoredDataTable label="빈 값" data={{ missing: undefined, nullable: null, text: '', list: [], object: {} }} />)
  expect(screen.getByRole('cell', { name: '기록 없음' })).toBeVisible()
  expect(screen.getByRole('cell', { name: '값 없음 (null)' })).toBeVisible()
  expect(screen.getByRole('cell', { name: '빈 문자열' })).toBeVisible()
  expect(screen.getByRole('cell', { name: '항목 없는 배열' })).toBeVisible()
  expect(screen.getByRole('cell', { name: '항목 없는 객체' })).toBeVisible()
})

it('원천 URL을 안전하게 연결하고 원천 인증키 값을 숨긴다', () => {
  const keyValue = 'fixture-credential'
  render(<StoredDataTable label="출처" data={{
    originalUrl: `https://example.com/notice?id=7&serviceKey=${keyValue}`,
    callbackUrl: 'javascript:alert(1)', serviceKey: keyValue,
  }} />)
  expect(screen.getByRole('link')).toHaveAttribute('href', 'https://example.com/notice?id=7')
  expect(screen.getByRole('link')).toHaveTextContent('https://example.com/notice?id=7')
  expect(screen.getByRole('cell', { name: '비공개' })).toBeVisible()
  expect(screen.getByRole('cell', { name: 'URL 형식 확인 필요' })).toBeVisible()
  expect(screen.queryByText(keyValue)).not.toBeInTheDocument()
})
