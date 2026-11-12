import { DYNAMIC_SITEMAPS, PUBLIC_ROUTES, SITE_ORIGIN } from './siteRoutes'

// lastmod는 넣지 않는다. 빌드 환경(Docker)에 Git 이력이 없어 실제 수정일을 알 수 없고,
// 부정확한 lastmod는 검색엔진이 무시하거나 신뢰를 낮춘다.
export function buildSitemap() {
  const urls = PUBLIC_ROUTES.map(route => `  <url><loc>${SITE_ORIGIN}${route}</loc></url>`)
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    ...urls,
    '</urlset>',
    '',
  ].join('\n')
}

// /sitemap.xml. 공개 경로 사이트맵(빌드 파일)과 단지·공고·지역 사이트맵(백엔드가 응답)을 묶는다.
export function buildSitemapIndex() {
  const sitemaps = [STATIC_SITEMAP_PATH, ...DYNAMIC_SITEMAPS.map(name => `/sitemaps/${name}.xml`)]
    .map(path => `  <sitemap><loc>${SITE_ORIGIN}${path}</loc></sitemap>`)
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    ...sitemaps,
    '</sitemapindex>',
    '',
  ].join('\n')
}

export const STATIC_SITEMAP_PATH = '/sitemaps/static.xml'
