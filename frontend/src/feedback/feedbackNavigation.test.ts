import { expect, it } from 'vitest'
import { feedbackBackground } from './feedbackNavigation'

const background = (pathname: string) => ({ feedbackBackground: { pathname, search: '?mapZoom=12', hash: '', key: 'k', state: null } })

it.each(['/explore', '/complexes/17', '/announcements/201', '/regions/28200', '/mypage/bookmarks'])('%s 위에서는 의견 보내기를 창으로 연다', (pathname) => {
  expect(feedbackBackground(background(pathname))?.pathname).toBe(pathname)
})

it.each(['/admin', '/regions/2820', '/complexes'])('%s는 배경으로 쓰지 않는다', (pathname) => {
  expect(feedbackBackground(background(pathname))).toBeNull()
})
