import { SITE_ORIGIN, type PublicRoute } from './siteRoutes'

const SITE_NAME = '공공주택 복덕방'

// 색인 대상 경로의 검색 결과 제목과 설명. 경로를 추가하면 여기에도 추가해야 빌드된다.
export const PAGE_METADATA: Record<PublicRoute, { readonly title: string; readonly description: string }> = {
  '/': {
    title: `${SITE_NAME} | 공공임대주택 지도와 모집공고를 한곳에서`,
    description: '지역과 임대 조건부터 모집공고와 신청 일정까지, 내게 맞는 공공주택을 복덕방에서 찾아보세요.',
  },
  '/explore': {
    title: `공공임대주택 지도·모집공고 찾기 | ${SITE_NAME}`,
    description: '지역, 임대유형, 가격, 전용면적으로 공공임대주택 단지와 모집 중인 공고를 지도에서 찾아보세요.',
  },
  '/privacy': {
    title: `개인정보처리방침 | ${SITE_NAME}`,
    description: `${SITE_NAME}의 개인정보처리방침입니다.`,
  },
}

// 빌드 HTML의 공통 head를 경로별 값으로 바꾸고 대표 주소를 넣는다.
// 크롤러가 JS 실행 전후에 같은 값을 보도록 앱은 이 태그들을 런타임에 바꾸지 않는다.
export function applyPageHead(template: string, route: PublicRoute) {
  const { title, description } = PAGE_METADATA[route]
  const url = SITE_ORIGIN + route
  let html = replaceOnce(template, /<title>[^<]*<\/title>/, `<title>${escapeHtml(title)}</title>`, 'title')
  for (const [key, value] of [
    ['description', description], ['og:title', title], ['og:description', description], ['og:url', url],
    ['twitter:title', title], ['twitter:description', description],
  ]) {
    html = replaceOnce(html, new RegExp(`(<meta\\s+(?:name|property)="${key}"\\s+content=")[^"]*(")`),
      (_, before: string, after: string) => before + escapeHtml(value) + after, key)
  }
  const additions = [`<link rel="canonical" href="${escapeHtml(url)}" />`]
  if (route === '/') {
    // `<`를 이스케이프해 값에 `</script>`가 들어와도 스크립트가 끊기지 않게 한다.
    additions.push(`<script type="application/ld+json">${JSON.stringify(siteStructuredData()).replace(/</g, '\\u003c')}</script>`)
  }
  return replaceOnce(html, /<\/head>/, `${additions.join('\n    ')}\n  </head>`, '</head>')
}

function siteStructuredData() {
  const url = `${SITE_ORIGIN}/`
  return {
    '@context': 'https://schema.org',
    '@graph': [
      {
        '@type': 'WebSite', '@id': `${url}#website`, url, name: SITE_NAME, inLanguage: 'ko-KR',
        description: PAGE_METADATA['/'].description, publisher: { '@id': `${url}#organization` },
      },
      { '@type': 'Organization', '@id': `${url}#organization`, url, name: SITE_NAME, logo: `${SITE_ORIGIN}/brand/favicon-mark.png` },
    ],
  }
}

function replaceOnce(html: string, pattern: RegExp, replacement: string | ((...match: string[]) => string), name: string) {
  const count = html.match(new RegExp(pattern.source, 'g'))?.length ?? 0
  if (count !== 1) throw new Error(`빌드 HTML에 ${name} 태그가 정확히 하나 있어야 합니다(${count}개).`)
  return typeof replacement === 'string' ? html.replace(pattern, () => replacement) : html.replace(pattern, replacement)
}

function escapeHtml(value: string) {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}
