// 공개 경로를 JS 없이 읽을 수 있는 HTML(경로별 제목·설명·대표 주소 포함)로 dist/prerendered/에 쓴다.
// 단지·공고·지역 페이지가 SSI로 끼울 공통 head를 dist/prerendered/shared-head.html에,
// 사이트맵 인덱스를 dist/sitemap.xml에, 공개 경로 사이트맵을 dist/sitemaps/static.xml에 쓴다.
// 제목·본문·내부 링크가 없는 페이지가 하나라도 있으면 빌드를 실패시킨다.
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'

const SITE_ORIGIN = 'https://bokduckbang.com'
const distDirectory = new URL('../dist/', import.meta.url)
const rendererUrl = new URL('../.prerender/renderPage.js', import.meta.url)

process.env.NODE_ENV = 'production'
const dom = installBrowserGlobals()
const { PUBLIC_ROUTES, STATIC_SITEMAP_PATH, applyPageHead, buildSharedHead, buildSitemap, buildSitemapIndex, findPrerenderProblems,
  injectIntoTemplate, prerenderedFilePath, renderPage } =
  await import(rendererUrl.href)

const template = await readFile(new URL('index.html', distDirectory), 'utf8')
const failures = []
for (const route of PUBLIC_ROUTES) {
  dom.reconfigure({ url: SITE_ORIGIN + route })
  const markup = renderPage(route)
  const problems = findPrerenderProblems(markup)
  if (problems.length > 0) {
    failures.push(`${route}: ${problems.join(', ')}`)
    continue
  }
  const file = new URL(prerenderedFilePath(route), distDirectory)
  await mkdir(new URL('.', file), { recursive: true })
  await writeFile(file, injectIntoTemplate(applyPageHead(template, route), markup))
  console.log(`prerendered ${route} -> dist/${prerenderedFilePath(route)}`)
}

if (failures.length > 0) {
  console.error(`프리렌더 검사 실패:\n${failures.map(failure => `- ${failure}`).join('\n')}`)
  process.exit(1)
}

await writeFile(new URL('prerendered/shared-head.html', distDirectory), buildSharedHead(template))
console.log('shared head -> dist/prerendered/shared-head.html')

const staticSitemap = new URL(`.${STATIC_SITEMAP_PATH}`, distDirectory)
await mkdir(new URL('.', staticSitemap), { recursive: true })
await writeFile(staticSitemap, buildSitemap())
await writeFile(new URL('sitemap.xml', distDirectory), buildSitemapIndex())
console.log(`sitemap index -> dist/sitemap.xml, static sitemap -> dist${STATIC_SITEMAP_PATH} (${PUBLIC_ROUTES.length} URLs)`)

// 첫 렌더에서 window·storage·matchMedia를 읽는 컴포넌트가 있어 브라우저 전역을 jsdom으로 제공한다.
// effect는 실행되지 않으므로 네트워크·SDK 호출은 일어나지 않는다.
function installBrowserGlobals() {
  const jsdom = new JSDOM('<!doctype html><html><body></body></html>', { url: SITE_ORIGIN, pretendToBeVisual: true })
  const { window } = jsdom
  window.matchMedia ??= query => ({
    matches: false, media: query, onchange: null,
    addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {},
    dispatchEvent: () => false,
  })
  const globals = ['document', 'navigator', 'localStorage', 'sessionStorage', 'location', 'history', 'matchMedia']
  Object.defineProperty(globalThis, 'window', { value: window, configurable: true, writable: true })
  for (const key of globals) {
    Object.defineProperty(globalThis, key, { value: window[key], configurable: true, writable: true })
  }
  return jsdom
}
