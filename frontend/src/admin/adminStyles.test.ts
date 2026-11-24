/// <reference types="node" />

import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

// 관리자 화면 기준(admin-system.css)이 지키려는 규칙을 CSS 원문으로 확인한다.
// jsdom은 cascade layer와 배치를 계산하지 않으므로, 넘침 같은 화면 결과는 브라우저에서 따로 확인해야 한다.

const adminRoot = resolve(process.cwd(), 'src/admin')

const adminStylesheets = (dir: string): string[] =>
  readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return adminStylesheets(path)
    return name.endsWith('.css') && name !== 'admin-system.css' ? [path] : []
  })

const read = (path: string) => readFileSync(path, 'utf8')

const findings = (pattern: RegExp, accept: (match: RegExpExecArray) => boolean = () => false) =>
  adminStylesheets(adminRoot).flatMap((path) => {
    const text = read(path)
    return [...text.matchAll(pattern)].filter((match) => !accept(match)).map((match) => `${relative(adminRoot, path)}: ${match[0]}`)
  })

describe('관리자 화면 기준', () => {
  it('글자 굵기는 400·500·600만 쓴다', () => {
    expect(findings(/font-weight:\s*(\d+)/g, (match) => ['400', '500', '600'].includes(match[1]))).toEqual([])
  })

  it('글자 크기는 기준값(var(--admin-text-*))으로 쓰고, 고정 px은 모바일 입력 확대를 막는 16px만 허용한다', () => {
    expect(findings(/font-size:\s*([\d.]+)(px|rem|em)\b/g, (match) => match[1] === '16' && match[2] === 'px')).toEqual([])
  })

  it('선택 상자에 background 묶음 속성을 쓰지 않아 기준의 화살표를 지우지 않는다', () => {
    const selectRulesWithBackgroundShorthand = /[^{}]*\bselect\b[^{}]*\{[^}]*?(?<![\w-])background:[^}]*\}/g
    expect(findings(selectRulesWithBackgroundShorthand)).toEqual([])
  })

  // 번들에서 layer 순서는 처음 나타나는 순서로 정해진다. index.css보다 앞에 오는 모듈 CSS가 layer를 쓰거나
  // 관리자 기준 파일을 다른 곳에서 먼저 불러오면 reset이 위로 올라가 기준 버튼의 굵기·크기를 덮는다.
  it('cascade layer는 index.css 맨 위 선언과 관리자 기준 파일에서만 쓰고, 기준 파일은 main.tsx에서만 불러온다', () => {
    const srcRoot = resolve(process.cwd(), 'src')
    const index = read(join(srcRoot, 'index.css'))
    const firstAtRule = index.match(/^\s*(?:\/\*[\s\S]*?\*\/\s*)*(@[^;{]+[;{])/)?.[1]
    expect(firstAtRule).toBe('@layer reset, admin-base, admin-components, admin-pages;')

    const files = (dir: string): string[] =>
      readdirSync(dir).flatMap((name) => {
        const path = join(dir, name)
        return statSync(path).isDirectory() ? files(path) : [path]
      })
    const all = files(srcRoot)
    const layerUsers = all.filter((path) => path.endsWith('.css') && /@layer\b/.test(read(path))).map((path) => relative(srcRoot, path)).sort()
    expect(layerUsers).toEqual(['admin/admin-pages.css', 'admin/admin-system.css', 'index.css'])
    const systemImporters = all
      .filter((path) => /\.(tsx?|css)$/.test(path) && !/\.test\.tsx?$/.test(path) && /admin-(system|pages)\.css['"]/.test(read(path)))
      .map((path) => relative(srcRoot, path))
    expect(systemImporters).toEqual(['main.tsx'])
  })
})
