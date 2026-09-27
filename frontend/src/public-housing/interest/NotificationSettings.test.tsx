import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { NotificationInterestProvider } from './NotificationInterest'
import { NotificationSettings } from './NotificationSettings'
import type { IntegratedSearchRepository, SearchResultItem } from '../search/integratedSearchRepository'

beforeEach(() => localStorage.clear())

describe('독립 알림 설정', () => {
  it('지도 위치가 없어도 지역을 선택하고 최초 신청 의사를 확인한다', async () => {
    const record = vi.fn().mockResolvedValue(undefined)
    const region: SearchResultItem = {
      id: '11', regionCode: '11', type: 'REGION', title: '서울특별시', subtitle: null,
      latitude: null, longitude: null, applicationStatus: null, publishedAt: null,
    }
    const search = vi.fn<IntegratedSearchRepository['search']>().mockResolvedValue({
      regions: [region], complexes: [], announcements: [], failures: [], hasNext: false,
      page: 0, size: 5, totalCount: 1, query: '서울',
    })
    render(
      <NotificationInterestProvider repository={{ record }}>
        <NotificationSettings searchRepository={{ search }} />
      </NotificationInterestProvider>,
    )
    fireEvent.click(screen.getByRole('button', { name: '알림 설정' }))
    const panel = screen.getByRole('region', { name: '알림 설정' })
    const input = within(panel).getByRole('searchbox', { name: '지역, 단지 검색' })
    expect(input).toHaveFocus()
    fireEvent.change(input, { target: { value: '서울' } })
    fireEvent.click(await within(panel).findByRole('button', { name: '서울특별시' }))
    expect(search.mock.calls.map((call) => call[4])).toEqual(['REGION', 'COMPLEX'])
    fireEvent.click(within(panel).getByRole('button', { name: '서울특별시 알림 받기' }))
    expect(await screen.findByRole('dialog')).toHaveTextContent('이 지역에 대한 알림을 받으시겠습니까?')
    expect(record).toHaveBeenCalledWith(expect.objectContaining({ source: 'SETTING', targetType: 'REGION', targetId: '11' }))
    fireEvent.click(screen.getByRole('button', { name: '네, 받고 싶어요' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    fireEvent.keyDown(panel, { key: 'Escape' })
    expect(screen.queryByRole('region', { name: '알림 설정' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '알림 설정' })).toHaveFocus()
  })
})
