import { expect, it, vi } from 'vitest'
import { previewJsonUpdate } from './api'
import { requestManagementApi } from '../management/api'
import { assembleReview } from '../announcement-pdf/review'
import { scenario } from '../announcement-pdf/fixtures/workspace'
vi.mock('../management/api', () => ({ requestManagementApi: vi.fn() }))

it('이전 서버가 새 공통 모집 묶음을 무시하면 적용 가능한 미리보기로 취급하지 않는다', async () => {
  const { review, workspace } = scenario('hansville')
  const input = assembleReview(review, workspace, { reason: 'PDF 대조', sourceUrl: 'https://example.com/notice', sourcePage: '4' })
  const response = { confirmationToken: 'all', fields: [], schedules: [], attachments: [], warnings: [], supply: { confirmationToken: 'supply', before: workspace.supply.rows, after: input.correction.rows, warnings: [] } }
  vi.mocked(requestManagementApi).mockResolvedValue(response)
  await expect(previewJsonUpdate('29', input)).rejects.toThrow('서버가 공통 모집 묶음을 확인하지 못했습니다.')
  vi.mocked(requestManagementApi).mockResolvedValue({ ...response, supply: { ...response.supply, newGroups: input.correction.newGroups } })
  await expect(previewJsonUpdate('29', input)).resolves.toMatchObject({ supply: { newGroups: input.correction.newGroups } })
})
