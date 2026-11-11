import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { buildSitemap, buildSitemapIndex } from './sitemap'
import { NOINDEX_ROUTE_PREFIXES, PRIVATE_ROUTE_PREFIXES, PUBLIC_ROUTES, SITE_ORIGIN } from './siteRoutes'

function robotsLines(field: string) {
  return readFileSync(resolve('public/robots.txt'), 'utf8').split('\n')
    .map(line => new RegExp(`^${field}:\\s*(\\S+)`).exec(line.trim())?.[1])
    .filter((value): value is string => !!value)
}

function isDisallowed(path: string) {
  return robotsLines('Disallow').some(prefix => path === prefix || path.startsWith(`${prefix}/`))
}

// 항목 자체와 하위 경로 예시. `/`로 끝나는 항목은 하위 경로만 뜻한다.
function samplePaths(prefix: string) {
  return prefix.endsWith('/') ? [`${prefix}PRIVACY_POLICY/v1`] : [prefix, `${prefix}/sample`]
}

// nginx/app.conf에서 X-Robots-Tag를 붙이는 location 정규식.
function nginxNoindexPattern() {
  const config = readFileSync(resolve('nginx/app.conf'), 'utf8')
  const block = /location ~ "([^"]+)" \{[^}]*X-Robots-Tag "noindex"[^}]*\}/.exec(config)
  if (!block) throw new Error('nginx/app.conf에서 noindex location을 찾지 못했습니다.')
  return new RegExp(block[1])
}

describe('robots.txt', () => {
  it('비공개 경로만 크롤링을 막는다', () => {
    expect(robotsLines('Disallow')).toEqual([...PRIVATE_ROUTE_PREFIXES])
  })

  it('색인 대상과 noindex 화면은 막지 않는다', () => {
    const crawlable = [...PUBLIC_ROUTES, ...NOINDEX_ROUTE_PREFIXES.flatMap(samplePaths)]
    expect(crawlable.filter(isDisallowed)).toEqual([])
  })

  it('운영 도메인의 sitemap을 알린다', () => {
    expect(robotsLines('Sitemap')).toEqual([`${SITE_ORIGIN}/sitemap.xml`])
  })
})

describe('sitemap.xml', () => {
  it('색인 대상 경로만 운영 도메인 주소로 담는다', () => {
    const locations = [...buildSitemap().matchAll(/<loc>([^<]+)<\/loc>/g)].map(match => match[1])
    expect(locations).toEqual(['https://bokduckbang.com/', 'https://bokduckbang.com/explore', 'https://bokduckbang.com/privacy'])
  })

  it('XML 선언과 sitemap 네임스페이스를 가진다', () => {
    const sitemap = buildSitemap()
    expect(sitemap.startsWith('<?xml version="1.0" encoding="UTF-8"?>')).toBe(true)
    expect(sitemap).toContain('<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">')
  })
})

describe('사이트맵 인덱스', () => {
  it('공개 경로 사이트맵과 단지·공고·지역 사이트맵을 운영 도메인 주소로 묶는다', () => {
    const index = buildSitemapIndex()
    const locations = [...index.matchAll(/<loc>([^<]+)<\/loc>/g)].map(match => match[1])
    expect(index).toContain('<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">')
    expect(locations).toEqual(['static', 'complexes', 'announcements', 'regions']
      .map(name => `https://bokduckbang.com/sitemaps/${name}.xml`))
  })
})

describe('nginx noindex 규칙', () => {
  it('비공개·noindex 화면과 하위 경로에 헤더를 붙인다', () => {
    const paths = [...PRIVATE_ROUTE_PREFIXES, ...NOINDEX_ROUTE_PREFIXES].flatMap(samplePaths)
    expect(paths.filter(path => !nginxNoindexPattern().test(path))).toEqual([])
  })

  it('색인 대상과 비슷한 이름의 경로에는 붙이지 않는다', () => {
    const paths = [...PUBLIC_ROUTES, '/administrator', '/feedbacks', '/loginx']
    expect(paths.filter(path => nginxNoindexPattern().test(path))).toEqual([])
  })
})
