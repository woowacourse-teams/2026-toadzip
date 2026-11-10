import { describe, expect, it } from 'vitest'
import { findPrerenderProblems } from './prerenderedDocument'
import { PUBLIC_ROUTES } from './siteRoutes'
import { renderPage } from './renderPage'

function heading(markup: string) {
  const template = document.createElement('template')
  template.innerHTML = markup
  return template.content.querySelector('h1')?.textContent?.trim()
}

describe('공개 페이지 프리렌더', () => {
  it.each(PUBLIC_ROUTES)('%s는 JS 없이 제목·본문·내부 링크를 제공한다', (route) => {
    const markup = renderPage(route)

    expect(findPrerenderProblems(markup)).toEqual([])
    expect(heading(markup)).not.toBe('페이지를 찾을 수 없어요.')
  })
})
