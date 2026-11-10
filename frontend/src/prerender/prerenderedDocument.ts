import type { PublicRoute } from './siteRoutes'

const EMPTY_ROOT = '<div id="root"></div>'
// 빈 셸·오류 화면을 걸러내기 위한 하한. 로딩 상태를 그리는 공개 화면도 통과해야 한다.
const MINIMUM_TEXT_LENGTH = 50

export const PRERENDERED_DIRECTORY = 'prerendered'

export function prerenderedFilePath(route: PublicRoute) {
  return route === '/' ? `${PRERENDERED_DIRECTORY}/index.html` : `${PRERENDERED_DIRECTORY}${route}/index.html`
}

export function injectIntoTemplate(template: string, markup: string) {
  if (template.split(EMPTY_ROOT).length !== 2) {
    throw new Error(`빌드 HTML에 빈 루트(${EMPTY_ROOT})가 정확히 하나 있어야 합니다.`)
  }
  return template.replace(EMPTY_ROOT, () => `<div id="root">${markup}</div>`)
}

export function findPrerenderProblems(markup: string) {
  const template = document.createElement('template')
  template.innerHTML = markup
  const content = template.content
  const problems: string[] = []

  const headings = content.querySelectorAll('h1').length
  if (headings !== 1) problems.push(`h1이 1개가 아님(${headings}개)`)

  const textLength = (content.textContent ?? '').replace(/\s+/g, ' ').trim().length
  if (textLength < MINIMUM_TEXT_LENGTH) problems.push(`본문이 너무 짧음(${textLength}자)`)

  const internalLink = Array.from(content.querySelectorAll('a[href]'))
    .some(link => /^\/(?!\/)/.test(link.getAttribute('href') ?? ''))
  if (!internalLink) problems.push('내부 링크 없음')

  return problems
}
