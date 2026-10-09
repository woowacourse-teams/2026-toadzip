import { describe, expect, it } from 'vitest'
import { maskReplayAttribute, sanitizeReplayStylesheet } from './replayPrivacy'

describe('Replay layout privacy', () => {
  it('preserves blocked canvas/image geometry and dialog mode while rejecting arbitrary rr attributes', () => {
    expect(maskReplayAttribute('rr_width', '1440px')).toBe('1440px')
    expect(maskReplayAttribute('rr_height', '800.25px')).toBe('800.25px')
    expect(maskReplayAttribute('rr_top', '-12px')).toBe('-12px')
    expect(maskReplayAttribute('rr_position', 'absolute')).toBe('absolute')
    expect(maskReplayAttribute('rr_transform', 'matrix(1, 0, 0, 1, 12, 0)')).toBe('matrix(1, 0, 0, 1, 12, 0)')
    expect(maskReplayAttribute('rr_open_mode', 'modal')).toBe('modal')
    expect(maskReplayAttribute('rr_height', 'url(private)')).toBe('')
    expect(maskReplayAttribute('rr_position', 'private@example.test')).toBe('')
    expect(maskReplayAttribute('rr_dataURL', 'data:image/png,private')).toBe('')
    expect(maskReplayAttribute('rr_src', 'https://private.example/?token=secret')).toBe('')
  })

  it('keeps production stylesheet layout and responsive rules without resource URLs or generated text', () => {
    const sanitized = maskReplayAttribute('_cssText', `
      @import url('https://private.example/?token=secret');
      @font-face { font-family: private; src: url('https://private.example/font'); }
      :root { --panel-width: 360px; --resource: url('https://private.example/?token=secret'); }
      .panel { display: grid; width: var(--panel-width); background-image: url('https://private.example/?email=hidden'); }
      .panel::before { content: 'private@example.test'; position: absolute; }
      .panel::after { content: ''; width: 2px; }
      [data-email='private@example.test'] { display: block; }
      @media (max-width: 768px) { .panel { display: flex; width: 100%; } }
      @supports (display: grid) { .panel { gap: 12px; } }
      @keyframes spinner { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
    `)
    expect(sanitized).toContain('display:grid;')
    expect(sanitized).toContain('width:var(--panel-width);')
    expect(sanitized).toContain('@media (max-width: 768px)')
    expect(sanitized).toContain('display:flex;')
    expect(sanitized).toContain('@supports (display: grid)')
    expect(sanitized).toContain('content:"";')
    expect(sanitized).toContain('@keyframes spinner')
    expect(sanitized).not.toMatch(/private|hidden|secret|url\(|@import|@font-face|data-email/)
  })

  it('rejects escaped resource functions, attr labels and unknown at-rule payloads', () => {
    const sanitized = sanitizeReplayStylesheet(String.raw`
      .panel { background-image: u\72l('https://private.example'); content: attr(data-email); display: block; }
      .other { --unsafe: attr(data-email); width: 10px; }
      @font-face { font-family: 'private'; src: url('private'); }
    `)
    expect(sanitized).toContain('display:block;')
    expect(sanitized).toContain('width:10px;')
    expect(sanitized).not.toMatch(/private|data-email|attr\(|url\(|\\/)
  })
})
