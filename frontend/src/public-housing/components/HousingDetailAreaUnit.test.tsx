import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createSnapshotPublicHousingRepositories } from '../api/snapshotPublicHousingRepository'
import { toHousingAnnouncementDetailData } from '../presentation/announcementDetailPresentation'
import { toHousingComplexDetailData } from '../presentation/complexDetailPresentation'
import { MINIMAL_PUBLIC_HOUSING_SNAPSHOT } from '../testing/minimalPublicHousingSnapshot'

const STORAGE_KEY = 'toadzip.area-unit'
const { repository } = createSnapshotPublicHousingRepositories(MINIMAL_PUBLIC_HOUSING_SNAPSHOT)
const signal = new AbortController().signal
const complexDetail = toHousingComplexDetailData(await repository.findComplexDetail('17', signal))
const announcementDetail = toHousingAnnouncementDetailData(await repository.findAnnouncementDetail('201', signal))
let HousingComplexDetailPanel: typeof import('./HousingComplexDetailPanel').HousingComplexDetailPanel
let HousingAnnouncementDetailPanel: typeof import('./HousingAnnouncementDetailPanel').HousingAnnouncementDetailPanel

beforeEach(async () => {
  localStorage.clear()
  vi.resetModules()
  ;({ HousingComplexDetailPanel } = await import('./HousingComplexDetailPanel'))
  ;({ HousingAnnouncementDetailPanel } = await import('./HousingAnnouncementDetailPanel'))
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  localStorage.clear()
})

function openComplex(complexId = '17') {
  return render(<HousingComplexDetailPanel detail={{ ...complexDetail, complexId }} onClose={() => {}} />)
}

function openAnnouncement(announcementId = '201') {
  return render(<HousingAnnouncementDetailPanel detail={{ ...announcementDetail, announcementId }} onClose={() => {}} />)
}

describe('상세 면적 단위 설정', () => {
  it('단지에서 선택한 평을 공고와 다른 단지에서도 유지하고 ㎡로 되돌린 선택도 저장한다', () => {
    const firstComplex = openComplex()
    fireEvent.click(screen.getAllByRole('button', { name: '평 전환' })[0])
    firstComplex.unmount()

    const announcement = openAnnouncement()
    expect(screen.getAllByRole('button', { name: '㎡ 전환' })).toHaveLength(2)
    expect(within(screen.getByRole('tabpanel')).getByText('0평', { selector: 'dd' })).toBeVisible()
    announcement.unmount()

    const otherComplex = openComplex('18')
    expect(screen.getAllByRole('button', { name: '㎡ 전환' })).toHaveLength(2)
    expect(screen.getAllByText('0평')).toHaveLength(2)
    otherComplex.unmount()

    const otherAnnouncement = openAnnouncement('202')
    fireEvent.click(screen.getAllByRole('button', { name: '㎡ 전환' })[1])
    expect(localStorage.getItem(STORAGE_KEY)).toBe('sqm')
    otherAnnouncement.unmount()

    openComplex()
    expect(screen.getAllByRole('button', { name: '평 전환' })).toHaveLength(2)
    expect(screen.getAllByText('0㎡')).toHaveLength(2)
  })

  it.each([
    { stored: 'sqm', button: '평 전환', area: '0㎡' },
    { stored: 'pyeong', button: '㎡ 전환', area: '0평' },
  ])('새로 연 상세에서 브라우저에 저장된 $stored 단위를 복원한다', ({ stored, button, area }) => {
    localStorage.setItem(STORAGE_KEY, stored)
    const complex = openComplex()
    expect(screen.getAllByRole('button', { name: button })).toHaveLength(2)
    expect(screen.getAllByText(area)).toHaveLength(2)
    complex.unmount()

    openAnnouncement()
    expect(screen.getAllByRole('button', { name: button })).toHaveLength(2)
    expect(within(screen.getByRole('tabpanel')).getByText(area, { selector: 'dd' })).toBeVisible()
  })

  it.each(['', 'unknown', '"pyeong"'])('잘못 저장된 단위 %s는 ㎡로 표시하고 다음 선택은 유효한 값으로 저장한다', (stored) => {
    localStorage.setItem(STORAGE_KEY, stored)
    openComplex()
    expect(screen.getAllByText('0㎡')).toHaveLength(2)
    fireEvent.click(screen.getAllByRole('button', { name: '평 전환' })[0])
    expect(screen.getAllByText('0평')).toHaveLength(2)
    expect(localStorage.getItem(STORAGE_KEY)).toBe('pyeong')
  })

  it('저장소 접근이 차단되어도 단위를 전환하고 다음 상세에 선택을 유지한다', () => {
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new DOMException('Storage is blocked', 'SecurityError')
    })
    const complex = openComplex()
    fireEvent.click(screen.getAllByRole('button', { name: '평 전환' })[0])
    expect(screen.getAllByText('0평')).toHaveLength(2)
    complex.unmount()

    openAnnouncement()
    expect(within(screen.getByRole('tabpanel')).getByText('0평', { selector: 'dd' })).toBeVisible()
    fireEvent.click(screen.getAllByRole('button', { name: '㎡ 전환' })[0])
    expect(within(screen.getByRole('tabpanel')).getByText('0㎡', { selector: 'dd' })).toBeVisible()
  })

  it('저장에 실패해도 이전 저장값 대신 방금 선택한 단위를 다음 상세에 적용한다', () => {
    localStorage.setItem(STORAGE_KEY, 'sqm')
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Storage is full', 'QuotaExceededError')
    })
    const announcement = openAnnouncement()
    fireEvent.click(screen.getAllByRole('button', { name: '평 전환' })[0])
    expect(within(screen.getByRole('tabpanel')).getByText('0평', { selector: 'dd' })).toBeVisible()
    announcement.unmount()

    openComplex()
    expect(screen.getAllByText('0평')).toHaveLength(2)
    fireEvent.click(screen.getAllByRole('button', { name: '㎡ 전환' })[0])
    expect(screen.getAllByText('0㎡')).toHaveLength(2)
  })
})
