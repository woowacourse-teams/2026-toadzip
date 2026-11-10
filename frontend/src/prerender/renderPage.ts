// 빌드 시 프리렌더용 SSR 진입점. scripts/prerender.mjs가 이 모듈의 번들을 불러 쓴다.
import { StrictMode, createElement } from 'react'
import { renderToString } from 'react-dom/server'
import { StaticRouter } from 'react-router'
import App from '../App'
import type { PublicRoute } from './siteRoutes'

export { PUBLIC_ROUTES } from './siteRoutes'
export { findPrerenderProblems, injectIntoTemplate, prerenderedFilePath } from './prerenderedDocument'
export { applyPageHead } from './pageHead'
export { buildSitemap } from './sitemap'

// 브라우저가 처음 그리는 화면과 같은 마크업을 만든다. effect는 실행되지 않으므로 API 호출은 없다.
export function renderPage(route: PublicRoute) {
  return renderToString(
    createElement(StrictMode, null, createElement(StaticRouter, { location: route }, createElement(App))),
  )
}
