import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { applyPageHead, PAGE_METADATA } from './pageHead'
import { PUBLIC_ROUTES } from './siteRoutes'

const template = readFileSync(resolve('index.html'), 'utf8')

function head(route: (typeof PUBLIC_ROUTES)[number]) {
  return new DOMParser().parseFromString(applyPageHead(template, route), 'text/html').head
}

function content(element: HTMLHeadElement, selector: string) {
  return element.querySelector(selector)?.getAttribute('content')
}

describe('프리렌더 문서의 head', () => {
  it.each(PUBLIC_ROUTES)('%s는 자기 제목·설명과 https 대표 주소를 가진다', (route) => {
    const { title, description } = PAGE_METADATA[route]
    const url = `https://bokduckbang.com${route}`
    const element = head(route)

    expect(element.querySelectorAll('title')).toHaveLength(1)
    expect(element.querySelector('title')?.textContent).toBe(title)
    expect(content(element, 'meta[name="description"]')).toBe(description)
    expect(content(element, 'meta[property="og:title"]')).toBe(title)
    expect(content(element, 'meta[property="og:description"]')).toBe(description)
    expect(content(element, 'meta[name="twitter:title"]')).toBe(title)
    expect(content(element, 'meta[name="twitter:description"]')).toBe(description)
    expect(content(element, 'meta[property="og:url"]')).toBe(url)
    expect(Array.from(element.querySelectorAll('link[rel="canonical"]')).map(link => link.getAttribute('href'))).toEqual([url])
  })

  it('경로마다 제목과 설명이 다르다', () => {
    const pages = PUBLIC_ROUTES.map(route => PAGE_METADATA[route])
    expect(new Set(pages.map(page => page.title)).size).toBe(pages.length)
    expect(new Set(pages.map(page => page.description)).size).toBe(pages.length)
  })

  it('홈에만 사이트와 운영 주체 구조화 데이터를 넣는다', () => {
    const script = head('/').querySelector('script[type="application/ld+json"]')
    const graph = JSON.parse(script?.textContent ?? '{}')['@graph'] as { '@type': string; url: string; name: string }[]

    expect(graph.map(item => [item['@type'], item.url, item.name])).toEqual([
      ['WebSite', 'https://bokduckbang.com/', '공공주택 복덕방'],
      ['Organization', 'https://bokduckbang.com/', '공공주택 복덕방'],
    ])
    expect(head('/explore').querySelector('script[type="application/ld+json"]')).toBeNull()
  })

  it('바꿀 태그가 템플릿에 없으면 실패한다', () => {
    expect(() => applyPageHead('<html><head><title>x</title></head></html>', '/')).toThrow('description')
  })
})

describe('빈 SPA 셸(index.html)', () => {
  it('공유 주소와 이미지를 https로 알리고 대표 주소는 넣지 않는다', () => {
    const element = new DOMParser().parseFromString(template, 'text/html').head

    expect(content(element, 'meta[property="og:url"]')).toBe('https://bokduckbang.com/')
    expect(content(element, 'meta[property="og:image"]')).toMatch(/^https:\/\/bokduckbang\.com\//)
    expect(content(element, 'meta[name="twitter:image"]')).toMatch(/^https:\/\/bokduckbang\.com\//)
    // 셸은 로그인·관리·없는 주소에도 쓰이므로 canonical을 넣으면 그 주소들이 홈의 사본으로 선언된다.
    expect(element.querySelector('link[rel="canonical"]')).toBeNull()
  })
})
