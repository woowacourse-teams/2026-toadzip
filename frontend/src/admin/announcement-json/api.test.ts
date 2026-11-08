import { expect, it, vi } from 'vitest'
import { previewJsonUpdate } from './api'
import { requestManagementApi } from '../management/api'
import { assembleReview } from '../announcement-pdf/review'
import { scenario, separateHansville } from '../announcement-pdf/fixtures/workspace'
vi.mock('../management/api', () => ({ requestManagementApi: vi.fn() }))

it('미리보기 요청은 서버가 모르는 필드로 거절하는 옛 저장 방식(saveMode·lhSourceRowIdentifier·newGroups)을 보내지 않는다', async () => {
  // 한스빌은 공고문이 두 주택형의 모집수를 함께 적은 공고라, 예전에는 새 공통 모집 묶음(newGroups)을 보냈다.
  const { review, workspace } = scenario('hansville', separateHansville())
  const input = assembleReview(review, workspace, { reason: 'PDF 대조', sourceUrl: 'https://example.com/notice', sourcePage: '4' })
  vi.mocked(requestManagementApi).mockResolvedValue({ confirmationToken: 'all', fields: [], schedules: [], attachments: [], warnings: [],
    supply: { confirmationToken: 'supply', before: workspace.supply.rows, after: input.correction.rows, warnings: [] } })
  await expect(previewJsonUpdate('29', input)).resolves.toMatchObject({ supply: { confirmationToken: 'supply' } })
  const body = JSON.stringify(vi.mocked(requestManagementApi).mock.calls[0][2])
  for (const field of ['saveMode', 'lhSourceRowIdentifier', 'newGroups']) expect(body).not.toContain(`"${field}"`)
})
