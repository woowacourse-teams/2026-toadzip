import { useCallback, useEffect, useMemo, useRef, type RefObject } from 'react'
import { captureProductEvent, createAnalyticsId } from '../../analytics/productAnalytics'
import type { ComplexSearchFilters } from '../api/publicHousingRepository'
import { announcementFiltersFromForm, replaceTopic, topicDraftFromForm } from '../filters/searchFilterForm'
import type { FilterTopic } from '../filters/complexFilterTopics'

const FIELDS: Readonly<Record<string, string>> = {
  provinceCode: 'region', districtCode: 'region', neighborhoodCode: 'region', regionCode: 'region',
  rentalTypes: 'rental', applicationStatuses: 'status', agencyCodes: 'agency', recruitmentTypes: 'recruitment',
  minDeposit: 'deposit', maxDeposit: 'deposit', minMonthlyRent: 'rent', maxMonthlyRent: 'rent',
  minExclusiveArea: 'area', maxExclusiveArea: 'area', builtYearFrom: 'built_year', builtYearTo: 'built_year',
}
const TOPICS = new Set(['region', 'rentalType', 'applicationStatus', 'agency', 'recruitmentType', 'price', 'exclusiveArea', 'builtYear'])
type Method = 'select' | 'choice' | 'slider' | 'preset'
type CloseReason = 'apply' | 'reset' | 'escape' | 'outside' | 'toggle' | 'close_button' | 'breakpoint'
interface Edit {
  id: string; topic: string; layout: string; changes: number; applied: ComplexSearchFilters; draft: ComplexSearchFilters;
  methods: Set<Method>; reason: string
}

export function useFilterMeasurement(target: 'complex' | 'announcement', topic: string | null, layout: 'desktop' | 'mobile',
  root: RefObject<HTMLElement | null>, filters: ComplexSearchFilters) {
  const edit = useRef<Edit | null>(null)
  const options = useRef({ filters, layout })
  options.current = { filters, layout }
  const readDraft = useCallback((current: Edit, form: HTMLFormElement | null) => {
    if (!form) return
    const data = new FormData(form)
    if (target === 'announcement') current.draft = announcementFiltersFromForm(data)
    else if (TOPICS.has(current.topic)) current.draft = replaceTopic(current.applied, current.topic as FilterTopic, topicDraftFromForm(current.topic as FilterTopic, data))
  }, [target])
  const currentForm = useCallback(() => root.current?.querySelector<HTMLFormElement>('form') ?? null, [root])

  useEffect(() => {
    if (edit.current?.topic === topic && edit.current?.layout === layout) return
    const previous = edit.current
    if (previous) captureProductEvent('filter_closed', {
      filter_target: target, filter_edit_id: previous.id, topic: previous.topic, layout: previous.layout,
      had_changes: previous.changes > 0, unapplied_changes: signature(previous.draft) !== signature(previous.applied), reason: previous.reason,
    })
    edit.current = topic === null ? null : { id: createAnalyticsId(), topic, layout, changes: 0,
      applied: options.current.filters, draft: options.current.filters, methods: new Set(), reason: 'dismissed' }
    if (edit.current) captureProductEvent('filter_opened', { filter_target: target, filter_edit_id: edit.current.id, topic, layout })
  }, [topic, layout, target])

  useEffect(() => {
    const element = root.current
    if (!element) return
    function valueChanged(field: string, method: Method, presetId?: string) {
      const current = edit.current
      if (!current || !FIELDS[field]) return
      current.changes += 1
      current.methods.add(method)
      // Form controls and hidden range values commit before this microtask.
      queueMicrotask(() => { if (edit.current === current) readDraft(current, currentForm()) })
      captureProductEvent('filter_value_changed', {
        filter_target: target, filter_edit_id: current.id, topic: current.topic, layout: current.layout,
        field: FIELDS[field], method, ...(presetId ? { preset_id: presetId } : {}),
        ...(field === 'provinceCode' ? { region_level: 'province' } : field === 'districtCode' ? { region_level: 'district' }
          : field === 'neighborhoodCode' ? { region_level: 'neighborhood' } : {}),
      })
    }
    function change(event: Event) {
      const input = event.target
      if (input instanceof HTMLSelectElement) valueChanged(input.name, 'select')
    }
    function checkboxClicked(event: Event) {
      const input = event.target
      if (input instanceof HTMLInputElement && input.type === 'checkbox') valueChanged(input.name, 'choice')
    }
    function range(event: Event) {
      if (!(event instanceof CustomEvent)) return
      const detail: unknown = event.detail
      if (typeof detail !== 'object' || detail === null || !('field' in detail) || typeof detail.field !== 'string'
        || !('method' in detail) || (detail.method !== 'slider' && detail.method !== 'preset')) return
      const presetId = 'presetId' in detail && typeof detail.presetId === 'string' && /^preset_[0-9]+$/.test(detail.presetId) ? detail.presetId : undefined
      valueChanged(detail.field, detail.method, presetId)
    }
    // Checkbox React onChange is derived from click, before the native change event.
    element.addEventListener('click', checkboxClicked, true)
    element.addEventListener('change', change)
    element.addEventListener('toadzip:filter-range-changed', range)
    return () => { element.removeEventListener('click', checkboxClicked, true); element.removeEventListener('change', change); element.removeEventListener('toadzip:filter-range-changed', range) }
  }, [root, target, readDraft, currentForm])

  const reason = useCallback((value: CloseReason) => {
    const current = edit.current
    if (!current) return
    current.reason = value
    if (value === 'apply' || value === 'reset') current.draft = current.applied
    else readDraft(current, currentForm())
  }, [readDraft, currentForm])
  const reset = useCallback((scope: 'all' | 'topic', stateTarget: 'draft' | 'applied') => {
    const current = edit.current
    if (current && stateTarget === 'draft') {
      current.changes += 1
      queueMicrotask(() => { if (edit.current === current) readDraft(current, currentForm()) })
    }
    captureProductEvent('filter_reset_clicked', { filter_target: target, filter_edit_id: current?.id ?? createAnalyticsId(),
      topic: current?.topic ?? 'all', layout: options.current.layout, reset_scope: scope, state_target: stateTarget })
  }, [target, readDraft, currentForm])
  const apply = useCallback((next: ComplexSearchFilters, mode: 'immediate' | 'submit' | 'reset') => {
    const previous = options.current.filters
    const changes = Object.keys(FIELDS).filter(field => canonicalValue(previous[field as keyof ComplexSearchFilters]) !== canonicalValue(next[field as keyof ComplexSearchFilters]))
    const current = edit.current
    // Even equal re-application resolves a reverted draft without an extra apply event.
    if (current) { current.applied = next; current.draft = next }
    if (changes.length === 0) return
    captureProductEvent('apply_filter', { filter_target: target, filter_edit_id: current?.id ?? createAnalyticsId(),
      topic: current?.topic ?? 'all', layout: options.current.layout, apply_mode: mode, changed_fields: [...new Set(changes.map(field => FIELDS[field]))],
      methods_used: current ? [...current.methods] : [], operation: mode === 'reset' ? 'reset' : 'change' })
  }, [target])
  return useMemo(() => ({ reason, reset, apply }), [reason, reset, apply])
}
function canonicalValue(value: unknown) {
  if (Array.isArray(value)) return [...value].sort().join(',')
  return value == null ? '' : String(value)
}
function signature(filters: ComplexSearchFilters) {
  return Object.keys(FIELDS).map(field => `${field}:${canonicalValue(filters[field as keyof ComplexSearchFilters])}`).join('|')
}
