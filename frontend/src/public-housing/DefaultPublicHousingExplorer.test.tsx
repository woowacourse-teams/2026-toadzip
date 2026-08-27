import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { PublicHousingRepository } from './api/publicHousingRepository.ts'
import { MINIMAL_PUBLIC_HOUSING_SNAPSHOT } from './testing/minimalPublicHousingSnapshot.ts'
import { LocalPublicHousingExplorer } from './DefaultPublicHousingExplorer.tsx'

vi.mock('../maps/naver/NaverMap.tsx', () => ({
  default: () => <section aria-label="공공임대주택 지도" />,
}))

const SNAPSHOT = {
  ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT,
  mapRegions: [
    {
      regionCode: '11140',
      name: '서울 중구',
      anchor: { latitude: 37.5636, longitude: 126.9976 },
      complexIds: [17],
    },
  ],
}

describe('LocalPublicHousingExplorer', () => {
  it('snapshot 검증이 끝나기 전에는 Explorer를 열지 않는다', async () => {
    let resolveSnapshot: (value: unknown) => void = () => undefined
    const loadSnapshot = vi.fn(() => new Promise<unknown>((resolve) => {
      resolveSnapshot = resolve
    }))
    renderLocalExplorer(loadSnapshot)

    expect(screen.getByRole('status')).toHaveTextContent(
      '로컬 mock 데이터를 준비하고 있습니다.',
    )
    expect(screen.queryByRole('heading', { name: '공공임대주택' }))
      .not.toBeInTheDocument()

    await act(async () => resolveSnapshot(SNAPSHOT))
    expect(await screen.findByRole('heading', { name: '공공임대주택' }))
      .toBeVisible()
    expect(screen.getByText('로컬 mock')).toBeVisible()
  })

  it('파일 오류를 안내하고 사용자가 다시 불러올 수 있다', async () => {
    const loadSnapshot = vi.fn()
      .mockRejectedValueOnce(new Error('broken local file'))
      .mockResolvedValueOnce(SNAPSHOT)
    renderLocalExplorer(loadSnapshot)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '로컬 mock 데이터를 불러오지 못했습니다.',
    )
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByRole('heading', { name: '공공임대주택' }))
      .toBeVisible()
    expect(loadSnapshot).toHaveBeenCalledTimes(2)
  })

  it('지도 이외의 API DTO 오류도 Explorer를 열기 전에 차단한다', async () => {
    const loadSnapshot = vi.fn().mockResolvedValue({
      ...SNAPSHOT,
      announcementListItems: [{
        ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT.announcementListItems[0],
        announcementId: null,
      }],
    })
    renderLocalExplorer(loadSnapshot)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '로컬 mock 데이터를 불러오지 못했습니다.',
    )
    expect(screen.queryByRole('heading', { name: '공공임대주택' }))
      .not.toBeInTheDocument()
  })
})

function renderLocalExplorer(loadSnapshot: () => Promise<unknown>) {
  return render(
    <MemoryRouter>
      <LocalPublicHousingExplorer
        loadSnapshot={loadSnapshot}
        repository={emptyRepository()}
      />
    </MemoryRouter>,
  )
}

function emptyRepository(): PublicHousingRepository {
  return {
    findAnnouncementDetail: vi.fn(),
    findAnnouncementPage: vi.fn(),
    findComplexDetail: vi.fn(),
    findComplexPage: vi.fn(),
    findMapComplexes: vi.fn(),
  }
}
