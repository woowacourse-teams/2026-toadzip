import { readFileSync } from 'node:fs'
import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { AnnouncementImportGuide } from './AnnouncementImportGuide'
import example from './announcementImportExample.json'

it('화면의 전체 예제는 백엔드 계약 검증용 예제와 같고 복사해 사용할 수 있다', async () => {
  const canonical = JSON.parse(readFileSync('../docs/fixtures/announcement-import-v1-valid.json', 'utf8'))
  expect(example).toEqual(canonical)
  const writeText = vi.fn().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
  render(<AnnouncementImportGuide />)
  fireEvent.click(screen.getByText('신규 공고 JSON 작성 방법과 전체 예제', { selector: 'summary' }))
  const field = screen.getByLabelText('신규 공고 JSON 작성 방법과 전체 예제 JSON 예제')
  expect(JSON.parse((field as HTMLTextAreaElement).value)).toEqual(canonical)
  fireEvent.click(screen.getByRole('button', { name: 'JSON 예제 복사' }))
  expect(await screen.findByRole('status')).toHaveTextContent('JSON 예제 복사를 완료했습니다.')
  expect(JSON.parse(writeText.mock.calls[0][0])).toEqual(canonical)
})
