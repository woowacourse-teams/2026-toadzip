import { useMobileViewport } from '../components/useMobileViewport'
import { useFilterMeasurement } from '../analytics/useFilterMeasurement'
import { Button } from '../../design-system/components/Button'
import { IconButton } from '../../design-system/components/IconButton'
import { type FormEvent, type ReactNode, useEffect, useRef, useState } from 'react'
import {
  type PublicHousingRegionRepository,
  publicHousingRegionRepository,
} from '../api/publicHousingRegionRepository.ts'
import type { AnnouncementSearchFilters } from '../api/publicHousingRepository.ts'
import {
  RENTAL_TYPE_OPTIONS, ANNOUNCEMENT_STATUS_OPTIONS, AGENCY_OPTIONS, RECRUITMENT_TYPE_OPTIONS,
} from './searchFilterOptions.ts'
import { RegionFilterFields } from './RegionFilterFields.tsx'
import { announcementFiltersFromForm } from './searchFilterForm.ts'
import { searchFiltersSignature } from './searchFilterLocation.ts'
import styles from './AnnouncementFilterPanel.module.css'

interface AnnouncementFilterPanelProps {
  readonly filters: AnnouncementSearchFilters
  readonly onApply: (filters: AnnouncementSearchFilters) => void
  readonly regionRepository?: PublicHousingRegionRepository
  readonly resultSummary?: ReactNode
}

export function AnnouncementFilterPanel({
  filters,
  onApply,
  regionRepository = publicHousingRegionRepository,
  resultSummary,
}: AnnouncementFilterPanelProps) {
  const mobile = useMobileViewport()
  const [open, setOpen] = useState(false)
  const [formFilters, setFormFilters] = useState(filters)
  const [formRevision, setFormRevision] = useState(0)
  const pendingSignaturesRef = useRef<string[]>([])
  const rootRef = useRef<HTMLElement>(null)
  const measurement = useFilterMeasurement('announcement', open ? 'all' : null, mobile ? 'mobile' : 'desktop', rootRef, filters)
  const formRef = useRef<HTMLFormElement>(null)
  const toggleRef = useRef<HTMLButtonElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const restoreFocusRef = useRef(false)
  const panelId = 'announcement-search-filter-panel'
  const summaryId = `${panelId}-summary`
  const sortDescriptionId = `${panelId}-sort-description`
  const sort = filters.sort ?? 'DEADLINE'
  const deadlineSelected = sort === 'DEADLINE' || sort === 'DEADLINE_DESC'
  const appliedCount = appliedFilterCount(filters)
  const filtersKey = searchFiltersSignature(filters)
  const formKey = `${formRevision}:${searchFiltersSignature(formFilters)}`
  const previousFiltersKeyRef = useRef(filtersKey)

  useEffect(() => {
    if (previousFiltersKeyRef.current === filtersKey) return
    previousFiltersKeyRef.current = filtersKey
    const appliedIndex = pendingSignaturesRef.current.indexOf(filtersKey)
    if (appliedIndex >= 0) {
      pendingSignaturesRef.current.splice(0, appliedIndex + 1)
      return
    }
    pendingSignaturesRef.current = []
    setFormFilters(filters)
    // History can return to the initial defaults while the live inputs have changed.
    setFormRevision((revision) => revision + 1)
  }, [filters, filtersKey])

  useEffect(() => {
    if (open) {
      closeRef.current?.focus()
    } else if (restoreFocusRef.current) {
      toggleRef.current?.focus()
      restoreFocusRef.current = false
    }
  }, [open, formKey])

  function close(reason: 'close_button' | 'escape' | 'toggle' = 'close_button') {
    measurement.reason(reason)
    restoreFocusRef.current = true
    setOpen(false)
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (mobile) return
    const nextFilters = preserveSort(announcementFiltersFromForm(new FormData(event.currentTarget)))
    measurement.apply(nextFilters, 'submit')
    onApply(nextFilters)
  }

  function changeFilter(event: FormEvent<HTMLFormElement>) {
    const target = event.target
    if (!(target instanceof HTMLInputElement || target instanceof HTMLSelectElement)) return
    const data = new FormData(event.currentTarget)
    // Descendant region controls reset after this bubbling change handler.
    if (target.name === 'provinceCode') data.set('districtCode', '')
    if (target.name === 'provinceCode' || target.name === 'districtCode') data.set('neighborhoodCode', '')
    const next = preserveSort(announcementFiltersFromForm(data))
    const nextSignature = searchFiltersSignature(next)
    if (nextSignature === (pendingSignaturesRef.current.at(-1) ?? filtersKey)) return
    pendingSignaturesRef.current.push(nextSignature)
    measurement.apply(next, 'immediate')
    onApply(next)
  }

  function reset() {
    const next = preserveSort({})
    measurement.reset('all', 'applied')
    measurement.apply(next, 'reset')
    pendingSignaturesRef.current.push(searchFiltersSignature(next))
    formRef.current?.reset()
    setFormFilters(next)
    closeRef.current?.focus()
    onApply(next)
  }

  function preserveSort(next: AnnouncementSearchFilters): AnnouncementSearchFilters {
    return filters.sort ? { ...next, sort: filters.sort } : next
  }

  function changeSort(value: 'DEADLINE' | 'LATEST') {
    const nextSort = value === 'DEADLINE'
      ? (sort === 'DEADLINE' ? 'DEADLINE_DESC' : 'DEADLINE')
      : (sort === 'LATEST' ? 'LATEST_ASC' : 'LATEST')
    const next: AnnouncementSearchFilters = { ...filters, sort: nextSort }
    // A sort change must not discard the desktop filter form's unapplied inputs.
    pendingSignaturesRef.current.push(searchFiltersSignature(next))
    onApply(next)
  }

  return (
    <section ref={rootRef} className={styles.panel} aria-label="공고 검색 필터">
      <div className={styles.toolbar} role="group" aria-label="공고 목록 도구">
        {resultSummary && <div className={styles.resultSummary}>{resultSummary}</div>}
        <div className={styles.toolbarControls}>
          <div className={styles.sortOptions} role="group" aria-label="공고 정렬">
            <button className={styles.sort} type="button" aria-pressed={deadlineSelected}
              aria-describedby={`${sortDescriptionId}-deadline`} onClick={() => changeSort('DEADLINE')}>
              마감순
              <svg className={styles.sortArrow} data-direction={sort === 'DEADLINE_DESC' ? 'descending' : 'ascending'}
                viewBox="0 0 16 16" fill="none" stroke="currentColor"
                strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
                <path d="M8 13V3m-4 4 4-4 4 4" />
              </svg>
            </button>
            <button className={styles.sort} type="button" aria-pressed={!deadlineSelected}
              aria-describedby={`${sortDescriptionId}-latest`} onClick={() => changeSort('LATEST')}>
              최신순
              <svg className={styles.sortArrow} data-direction={sort === 'LATEST_ASC' ? 'ascending' : 'descending'}
                viewBox="0 0 16 16" fill="none" stroke="currentColor"
                strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
                <path d="M8 13V3m-4 4 4-4 4 4" />
              </svg>
            </button>
            <span id={`${sortDescriptionId}-deadline`} className={styles.visuallyHidden}>
              {sort === 'DEADLINE_DESC'
                ? '마감일 내림차순으로, 마감이 먼 공고부터 보여드려요. 접수 예정은 그 뒤에 시작일 내림차순으로 보여드려요.'
                : '마감일 오름차순으로, 마감이 가까운 공고부터 보여드려요. 접수 예정은 그 뒤에 시작일 오름차순으로 보여드려요.'}
            </span>
            <span id={`${sortDescriptionId}-latest`} className={styles.visuallyHidden}>
              {sort === 'LATEST_ASC'
                ? '공고일 오름차순으로, 오래된 공고부터 보여드려요.'
                : '공고일 내림차순으로, 최근에 올라온 공고부터 보여드려요.'}
            </span>
          </div>
          <button
            ref={toggleRef}
            className={`${styles.toggle} ${appliedCount > 0 ? styles.toggleActive : ''}`}
            type="button"
            aria-label={`공고 필터 ${open ? '접기' : '열기'}`}
            aria-controls={panelId}
            aria-describedby={summaryId}
            aria-expanded={open}
            onClick={() => {
              if (open) close('toggle')
              else {
                setFormFilters(filters)
                setOpen(true)
              }
            }}
          >
            <span>공고 필터</span>
            {appliedCount > 0 && <span className={styles.count} aria-hidden="true">{appliedCount}</span>}
            <span id={summaryId} className={styles.visuallyHidden}>
              {appliedCount > 0 ? `${appliedCount}개 적용` : '조건 선택'}
            </span>
            <svg className={styles.chevron} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
              <path d="m3 6 5 5 5-5" />
            </svg>
          </button>
        </div>
      </div>

      {open && (
        <form
          ref={formRef}
          key={formKey}
          id={panelId}
          className={styles.form}
          onSubmit={submit}
          onChange={mobile ? changeFilter : undefined}
          onKeyDown={(event) => {
            if (event.key === 'Escape') {
              event.preventDefault()
              close('escape')
            }
          }}
        >
          <div className={styles.header}>
            <button className={styles.reset} type="button" onClick={reset}>
              <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
                <path d="M16.4 8a6.5 6.5 0 1 0-.3 4.8M16.5 3.5V8H12" />
              </svg>
              초기화
            </button>
            <h2>공고 필터</h2>
            <IconButton ref={closeRef} className={styles.close} type="button" label="공고 필터 닫기" onClick={() => close()}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true" focusable="false">
                <path d="m5 5 14 14M19 5 5 19" />
              </svg>
            </IconButton>
          </div>
          <div className={styles.fields}>
            <div className={styles.grid}>
              <RegionFilterFields
                initialRegionCode={formFilters.regionCode ?? ''}
                messageId="announcement-region-district-load-error"
                loadingMessage="시·군·구 목록을 불러오는 중이에요."
                styles={{
                  regionFields: styles.regionFields,
                  field: styles.field,
                  regionError: styles.regionError,
                  regionMessage: styles.regionMessage,
                }}
                repository={regionRepository}
              />
              <FilterCheckboxGroup
                label="임대유형"
                name="rentalTypes"
                defaultValues={formFilters.rentalTypes}
                options={RENTAL_TYPE_OPTIONS}
                appearance="grid"
              />
              <FilterCheckboxGroup
                label="모집상태"
                name="applicationStatuses"
                defaultValues={formFilters.applicationStatuses}
                options={ANNOUNCEMENT_STATUS_OPTIONS}
              />
              <FilterCheckboxGroup
                label="공급기관"
                name="agencyCodes"
                defaultValues={formFilters.agencyCodes}
                options={AGENCY_OPTIONS}
              />
              <FilterCheckboxGroup
                label="모집유형"
                name="recruitmentTypes"
                defaultValues={formFilters.recruitmentTypes}
                options={RECRUITMENT_TYPE_OPTIONS}
              />
            </div>
          </div>

          {!mobile && <div className={styles.actions}>
            <Button className={styles.apply} type="submit">
              공고 필터 적용
            </Button>
          </div>}
        </form>
      )}
    </section>
  )
}

function FilterCheckboxGroup({
  defaultValues = [],
  label,
  name,
  options,
  appearance = 'pill',
}: {
  /** grid: 임대유형처럼 칸을 맞춘 회색 칸, pill: 나머지 선택지는 알약 칩 */
  readonly appearance?: 'grid' | 'pill'
  readonly defaultValues?: readonly string[]
  readonly label: string
  readonly name: string
  readonly options: readonly (readonly [string, string])[]
}) {
  const selected = new Set(defaultValues)
  return (
    <fieldset className={styles.choiceGroup}>
      <legend>{label}</legend>
      <div className={styles.choiceOptions} data-appearance={appearance}>
        {options.map(([value, optionLabel]) => (
          <label key={value} className={styles.choice}>
            <input
              type="checkbox"
              name={name}
              value={value}
              defaultChecked={selected.has(value)}
            />
            <span>{optionLabel}</span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}

function appliedFilterCount(filters: AnnouncementSearchFilters) {
  return [
    filters.regionCode,
    filters.rentalTypes?.length,
    filters.applicationStatuses?.length,
    filters.agencyCodes?.length,
    filters.recruitmentTypes?.length,
  ].filter(Boolean).length
}
