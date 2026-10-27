import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, it } from 'vitest'

it('설치 manifest의 192px·512px 아이콘이 실제 PNG 크기와 일치한다', () => {
  const publicDirectory = resolve('public')
  const manifest = JSON.parse(readFileSync(resolve(publicDirectory, 'manifest.webmanifest'), 'utf8'))
  for (const size of [192, 512]) {
    const icon = manifest.icons.find((item: { sizes: string }) => item.sizes === `${size}x${size}`)
    expect(icon).toBeDefined()
    expect(icon.type).toBe('image/png')
    const png = readFileSync(resolve(publicDirectory, icon.src.slice(1)))
    expect(png.subarray(1, 4).toString()).toBe('PNG')
    expect(png.readUInt32BE(16)).toBe(size)
    expect(png.readUInt32BE(20)).toBe(size)
  }
})
