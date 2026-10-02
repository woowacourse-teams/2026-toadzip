import { Button } from '../../design-system/components/Button'
import { IconButton } from '../../design-system/components/IconButton'
import { type FormEvent, type ReactNode, useEffect, useRef, useState } from 'react'
import {
  type PublicHousingRegionRepository,
  publicHousingRegionRepository,
} from '../api/publicHousingRegionRepository.ts'
import type {
  AgencyCodeFilter,
  ApplicationStatusFilter,
  AnnouncementSearchFilters,
  RecruitmentTypeFilter,
  RentalTypeFilter,
} from '../api/publicHousingRepository.ts'
import { PUBLIC_HOUSING_PROVINCE_OPTIONS } from '../model/publicHousingRegion.ts'
import {
  RENTAL_TYPE_OPTIONS, ANNOUNCEMENT_STATUS_OPTIONS, AGENCY_OPTIONS, RECRUITMENT_TYPE_OPTIONS,
} from './searchFilterOptions.ts'
import { useRegionSelection } from './useRegionSelection.ts'
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
  const [open, setOpen] = useState(false)
  const formRef = useRef<HTMLFormElement>(null)
  const toggleRef = useRef<HTMLButtonElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const restoreFocusRef = useRef(false)
  const panelId = 'announcement-search-filter-panel'
  const summaryId = `${panelId}-summary`
  const appliedCount = appliedFilterCount(filters)

  useEffect(() => {
    if (open) {
      closeRef.current?.focus()
    } else if (restoreFocusRef.current) {
      toggleRef.current?.focus()
      restoreFocusRef.current = false
    }
  }, [open])

  function close() {
    restoreFocusRef.current = true
    setOpen(false)
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const nextFilters = filtersFromForm(event.currentTarget)
    onApply(nextFilters)
  }

  function reset() {
    formRef.current?.reset()
    onApply({})
  }

  return (
    <section className={styles.panel} aria-label="공고 검색 필터">
      <div className={styles.toolbar} role="group" aria-label="공고 목록 도구">
        {resultSummary && <div className={styles.resultSummary}>{resultSummary}</div>}
        <button
          ref={toggleRef}
          className={`${styles.toggle} ${appliedCount > 0 ? styles.toggleActive : ''}`}
          type="button"
          aria-label={`공고 필터 ${open ? '접기' : '열기'}`}
          aria-controls={panelId}
          aria-describedby={summaryId}
          aria-expanded={open}
          onClick={() => open ? close() : setOpen(true)}
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

      {open && (
        <form
          ref={formRef}
          key={searchFiltersSignature(filters)}
          id={panelId}
          className={styles.form}
          onSubmit={submit}
          onKeyDown={(event) => {
            if (event.key === 'Escape') {
              event.preventDefault()
              close()
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
            <IconButton ref={closeRef} className={styles.close} type="button" label="공고 필터 닫기" onClick={close}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true" focusable="false">
                <path d="m5 5 14 14M19 5 5 19" />
              </svg>
            </IconButton>
          </div>
          <div className={styles.fields}>
            <div className={styles.grid}>
              <RegionSelect
                defaultValue={filters.regionCode ?? ''}
                messageId="announcement-region-district-load-error"
                repository={regionRepository}
              />
              <FilterCheckboxGroup
                label="임대유형"
                name="rentalTypes"
                defaultValues={filters.rentalTypes}
                options={RENTAL_TYPE_OPTIONS}
              />
              <FilterCheckboxGroup
                label="모집상태"
                name="applicationStatuses"
                defaultValues={filters.applicationStatuses}
                options={ANNOUNCEMENT_STATUS_OPTIONS}
              />
              <FilterCheckboxGroup
                label="공급기관"
                name="agencyCodes"
                defaultValues={filters.agencyCodes}
                options={AGENCY_OPTIONS}
              />
              <FilterCheckboxGroup
                label="모집유형"
                name="recruitmentTypes"
                defaultValues={filters.recruitmentTypes}
                options={RECRUITMENT_TYPE_OPTIONS}
              />
            </div>
          </div>

          <div className={styles.actions}>
            <Button className={styles.apply} type="submit">
              공고 필터 적용
            </Button>
          </div>
        </form>
      )}
    </section>
  )
}

function RegionSelect({ defaultValue, messageId, repository }: {
  readonly defaultValue: string
  readonly messageId: string
  readonly repository: PublicHousingRegionRepository
}) {
  const {
    provinceCode, districtCode, districtOptions, loadStatus, provinceSelectRef,
    selectProvince, selectDistrict,
  } = useRegionSelection(defaultValue, repository)

  return (
    <div className={styles.regionFields}>
      <label className={styles.field}>
        <span>시·도</span>
        <select
          ref={provinceSelectRef}
          name="provinceCode"
          value={provinceCode}
          onChange={(event) => selectProvince(event.currentTarget.value)}
        >
          <option value="">전체</option>
          {PUBLIC_HOUSING_PROVINCE_OPTIONS.map(([value, optionLabel]) => (
            <option key={value} value={value}>{optionLabel}</option>
          ))}
        </select>
      </label>

      <label className={styles.field}>
        <span>시·군·구</span>
        <select
          name="districtCode"
          value={districtCode}
          disabled={provinceCode === ''}
          aria-describedby={loadStatus === 'error'
            ? messageId
            : undefined}
          onChange={(event) => selectDistrict(event.currentTarget.value)}
        >
          <option value="">
            {provinceCode === '' ? '시·도를 먼저 선택' : '전체'}
          </option>
          {districtOptions.map(({ districtName, regionCode }) => (
            <option key={regionCode} value={regionCode}>
              {districtName ?? regionCode}
            </option>
          ))}
        </select>
      </label>

      {loadStatus === 'loading' && (
        <small className={styles.regionMessage} role="status">
          시·군·구 목록을 불러오는 중입니다.
        </small>
      )}
      {loadStatus === 'error' && (
        <small
          className={styles.regionError}
          id={messageId}
          role="alert"
        >
          시·군·구를 불러오지 못했습니다. 시·도만 적용할 수 있습니다.
        </small>
      )}
    </div>
  )
}

function FilterCheckboxGroup({
  defaultValues = [],
  label,
  name,
  options,
}: {
  readonly defaultValues?: readonly string[]
  readonly label: string
  readonly name: string
  readonly options: readonly (readonly [string, string])[]
}) {
  const selected = new Set(defaultValues)
  return (
    <fieldset className={styles.choiceGroup}>
      <legend>{label}</legend>
      <div className={styles.choiceOptions}>
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

function filtersFromForm(
  form: HTMLFormElement,
): AnnouncementSearchFilters {
  const data = new FormData(form)
  const provinceCode = textValue(data, 'provinceCode')
  const districtCode = textValue(data, 'districtCode')
  const regionCode = districtCode || provinceCode
  const rentalTypes = formValues<RentalTypeFilter>(data, 'rentalTypes')
  const applicationStatuses = formValues<ApplicationStatusFilter>(
    data,
    'applicationStatuses',
  )
  const agencyCodes = formValues<AgencyCodeFilter>(data, 'agencyCodes')
  const recruitmentTypes = formValues<RecruitmentTypeFilter>(
    data,
    'recruitmentTypes',
  )
  const shared: AnnouncementSearchFilters = {
    ...(regionCode === '' ? {} : { regionCode }),
    ...(rentalTypes.length === 0 ? {} : { rentalTypes }),
    ...(applicationStatuses.length === 0 ? {} : { applicationStatuses }),
    ...(agencyCodes.length === 0 ? {} : { agencyCodes }),
    ...(recruitmentTypes.length === 0 ? {} : { recruitmentTypes }),
  }
  return shared
}

function appliedFilterCount(filters: AnnouncementSearchFilters) {
  const common = [
    filters.regionCode,
    filters.rentalTypes?.length,
    filters.applicationStatuses?.length,
    filters.agencyCodes?.length,
    filters.recruitmentTypes?.length,
  ].filter(Boolean).length
  return common
}

function textValue(data: FormData, name: string) {
  const value = data.get(name)
  return typeof value === 'string' ? value.trim() : ''
}

function formValues<Value extends string>(data: FormData, name: string) {
  return data.getAll(name).filter(
    (value): value is Value => typeof value === 'string',
  )
}
