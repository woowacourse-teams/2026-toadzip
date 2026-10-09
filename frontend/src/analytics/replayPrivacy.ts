const DIMENSION_ATTRIBUTES = new Set(['rr_width', 'rr_height', 'rr_left', 'rr_top', 'rr_scrollleft', 'rr_scrolltop'])
const GENERATED_ENUMS: Record<string, ReadonlySet<string>> = {
  rr_position: new Set(['static', 'relative', 'absolute', 'fixed', 'sticky']),
  rr_display: new Set(['none', 'block', 'inline', 'inline-block', 'flex', 'inline-flex', 'grid', 'inline-grid', 'contents', 'table', 'table-row', 'table-cell']),
  rr_open_mode: new Set(['modal', 'non-modal']),
  rr_mediastate: new Set(['played', 'paused']),
}
const stylesheetCache = new Map<string, string>()
const GROUP_RULE_NAMES: Record<string, string> = { CSSMediaRule: 'media', CSSSupportsRule: 'supports', CSSContainerRule: 'container' }
const MAX_STYLESHEET_LENGTH = 1_000_000

function safeDeclarations(style: CSSStyleDeclaration): string {
  const declarations: string[] = []
  for (let index = 0; index < style.length; index += 1) {
    const property = style.item(index)
    const value = style.getPropertyValue(property).trim()
    if (!/^(?:--)?[a-zA-Z][a-zA-Z0-9-]*$/.test(property)) continue
    if (property === 'content') {
      // Empty pseudo-elements are decorative layout, never user text.
      if (value === '""' || value === "''") declarations.push('content:"";')
      continue
    }
    // A recording needs layout, not fetched assets, generated labels or data
    // read back out of DOM attributes. CSSOM removes comments before this test.
    if (value.length > 1500 || /[\\@"']|\b(?:url|image-set|attr|element|expression)\s*\(/i.test(value)) continue
    const priority = style.getPropertyPriority(property) === 'important' ? '!important' : ''
    declarations.push(`${property}:${value}${priority};`)
  }
  return declarations.join('')
}

function safeRules(rules: CSSRuleList): string {
  const output: string[] = []
  for (const rule of Array.from(rules)) {
    if (rule instanceof CSSStyleRule) {
      // Attribute values and escaped selectors can contain text. Classes, IDs,
      // combinators, pseudo selectors and value-free [disabled] remain useful.
      if (!/^[a-zA-Z0-9_#.:[\]()\s>+~*,|^$%&-]+$/.test(rule.selectorText)) continue
      output.push(`${rule.selectorText}{${safeDeclarations(rule.style)}}`)
      continue
    }
    const grouped = rule as CSSRule & { cssRules?: CSSRuleList; conditionText?: string; name?: string }
    if (!grouped.cssRules) continue
    const condition = grouped.conditionText
    const kind = rule.constructor.name
    if (condition && /^[a-zA-Z0-9_\s():.,%+*/<>=&|-]+$/.test(condition)) {
      const prefix = GROUP_RULE_NAMES[kind]
      if (prefix) output.push(`@${prefix} ${condition}{${safeRules(grouped.cssRules)}}`)
    } else if (kind === 'CSSLayerBlockRule' && /^[a-zA-Z0-9_.-]*$/.test(grouped.name ?? '')) {
      output.push(`@layer ${grouped.name ?? ''}{${safeRules(grouped.cssRules)}}`)
    } else if (kind === 'CSSKeyframesRule' && /^[a-zA-Z0-9_-]+$/.test(grouped.name ?? '')) {
      const frames = Array.from(grouped.cssRules).flatMap(frame => {
        const keyframe = frame as CSSKeyframeRule
        return /^(?:from|to|[\d.%\s,]+)$/.test(keyframe.keyText)
          ? [`${keyframe.keyText}{${safeDeclarations(keyframe.style)}}`] : []
      })
      output.push(`@keyframes ${grouped.name}{${frames.join('')}}`)
    }
    // Imports, font faces and unknown at-rules are intentionally not serialized.
  }
  return output.join('')
}

/** Static application CSS only; user-generated styles are not a supported input. */
export function sanitizeReplayStylesheet(value: string): string {
  if (value.length > MAX_STYLESHEET_LENGTH) return ''
  const cached = stylesheetCache.get(value)
  if (cached !== undefined) return cached
  try {
    const sheet = new CSSStyleSheet()
    sheet.replaceSync(value)
    const sanitized = safeRules(sheet.cssRules)
    if (stylesheetCache.size >= 4) stylesheetCache.delete(stylesheetCache.keys().next().value ?? '')
    stylesheetCache.set(value, sanitized)
    return sanitized
  } catch {
    // Old browsers without constructable stylesheets fail closed.
    return ''
  }
}

export function maskReplayAttribute(name: string, value: string): string {
  if (name === '_cssText') return sanitizeReplayStylesheet(value)
  if (DIMENSION_ATTRIBUTES.has(name)) return /^-?\d{1,7}(?:\.\d{1,6})?(?:px)?$/.test(value) ? value : ''
  if (name in GENERATED_ENUMS) return GENERATED_ENUMS[name].has(value) ? value : ''
  if (name === 'rr_transform') return value === 'none' || /^matrix(?:3d)?\([-\d.e+,\s]+\)$/.test(value) ? value : ''
  if (['class', 'id', 'role', 'type'].includes(name) && /^[a-zA-Z0-9_ :.-]{0,500}$/.test(value)) return value
  if (['width', 'height', 'tabindex', 'colspan', 'rowspan'].includes(name) && /^\d{1,5}$/.test(value)) return value
  if (['disabled', 'open', 'hidden', 'inert', 'aria-hidden'].includes(name)) return value === 'false' ? 'false' : ''
  if (name === 'style' && !/url\s*\(|@|content\s*:/i.test(value)) return value
  return ''
}
