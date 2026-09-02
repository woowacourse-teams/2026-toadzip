import { type CSSProperties, useEffect, useRef, useState } from 'react'
import styles from './DualRangeFilter.module.css'

const RANGE_TOUCH_TARGET_SIZE = 44

export interface DualRangeFilterPreset {
  readonly label: string
  readonly minimum: number | null
  readonly maximum: number | null
}

export interface DualRangeFilterProps {
  readonly legend: string
  readonly minimumName: string
  readonly maximumName: string
  readonly minimum: number
  readonly maximum: number
  readonly step: number
  readonly majorStep: number
  readonly initialMinimum: number | null
  readonly initialMaximum: number | null
  readonly formatValue: (value: number) => string
  readonly formatTick: (value: number) => string
  readonly presets: readonly DualRangeFilterPreset[]
  readonly preserveInitialValuesUntilChange?: boolean
}

export function DualRangeFilter({
  legend,
  minimumName,
  maximumName,
  minimum,
  maximum,
  step,
  majorStep,
  initialMinimum,
  initialMaximum,
  formatValue,
  formatTick,
  presets,
  preserveInitialValuesUntilChange = false,
}: DualRangeFilterProps) {
  validateDomain(minimum, maximum, step, majorStep)
  const fieldsetRef = useRef<HTMLFieldSetElement>(null)
  const [normalizedInitialMinimum, normalizedInitialMaximum] = normalizeRange(
    initialMinimum,
    initialMaximum,
    minimum,
    maximum,
    step,
  )
  const [selectedMinimum, setSelectedMinimum] = useState(
    normalizedInitialMinimum,
  )
  const [selectedMaximum, setSelectedMaximum] = useState(
    normalizedInitialMaximum,
  )
  const [minimumChanged, setMinimumChanged] = useState(false)
  const [maximumChanged, setMaximumChanged] = useState(false)
  const rangeText = selectedMinimum === minimum
    ? selectedMaximum === maximum
      ? '전체'
      : `${formatValue(selectedMaximum)} 이하`
    : selectedMaximum === maximum
      ? `${formatValue(selectedMinimum)} 이상`
      : `${formatValue(selectedMinimum)}~${formatValue(selectedMaximum)}`
  const startPercentage = rangePercentage(selectedMinimum, minimum, maximum)
  const endPercentage = rangePercentage(selectedMaximum, minimum, maximum)
  const rangeStyle = {
    '--range-start': `${startPercentage}%`,
    '--range-end': `${endPercentage}%`,
    '--minimum-input-compensation': `${roundToDecimalPlaces(
      RANGE_TOUCH_TARGET_SIZE * (1 - endPercentage / 100),
      10,
    )}px`,
    '--maximum-input-compensation': `${roundToDecimalPlaces(
      RANGE_TOUCH_TARGET_SIZE * (startPercentage / 100),
      10,
    )}px`,
  } as CSSProperties
  const majorTicks = createMajorTicks(minimum, maximum, majorStep)

  useEffect(() => {
    const form = fieldsetRef.current?.form
    if (form === null || form === undefined) {
      return
    }
    const reset = () => {
      setSelectedMinimum(normalizedInitialMinimum)
      setSelectedMaximum(normalizedInitialMaximum)
      setMinimumChanged(false)
      setMaximumChanged(false)
    }
    form.addEventListener('reset', reset)
    return () => form.removeEventListener('reset', reset)
  }, [normalizedInitialMaximum, normalizedInitialMinimum])

  return (
    <fieldset ref={fieldsetRef} className={styles.filter} style={rangeStyle}>
      <legend className={styles.legend}>{legend}</legend>
      <div className={styles.header}>
        <span className={styles.label} aria-hidden="true">
          {legend}
        </span>
        <output
          className={styles.output}
          role="status"
          aria-atomic="true"
          aria-label={`${legend} 선택 범위`}
        >
          {rangeText}
        </output>
      </div>

      {presets.length > 0 && (
        <div
          className={styles.presets}
          role="group"
          aria-label={`${legend} 빠른 선택`}
        >
          {presets.map((preset) => {
            const [presetMinimum, presetMaximum] = normalizeRange(
              preset.minimum,
              preset.maximum,
              minimum,
              maximum,
              step,
            )
            const selected = (
              selectedMinimum === presetMinimum
              && selectedMaximum === presetMaximum
            )

            return (
              <button
                key={preset.label}
                className={styles.preset}
                type="button"
                aria-pressed={selected}
                onClick={() => {
                  setSelectedMinimum(presetMinimum)
                  setSelectedMaximum(presetMaximum)
                  setMinimumChanged(true)
                  setMaximumChanged(true)
                }}
              >
                {preset.label}
              </button>
            )
          })}
        </div>
      )}

      <div className={styles.slider}>
        <div className={styles.track} aria-hidden="true">
          <span className={styles.selectedTrack} />
        </div>
        <input
          className={`${styles.rangeInput} ${styles.minimumInput}${
            selectedMinimum === maximum ? ` ${styles.minimumOnTop}` : ''
          }`}
          type="range"
          aria-label={`${legend} 최솟값`}
          aria-valuetext={formatValue(selectedMinimum)}
          aria-valuemin={minimum}
          aria-valuemax={selectedMaximum}
          min={minimum}
          max={selectedMaximum}
          step={step}
          value={selectedMinimum}
          onChange={(event) => {
            const nextValue = normalizeValue(
              Number(event.currentTarget.value),
              minimum,
              maximum,
              step,
            )
            setSelectedMinimum(Math.min(nextValue, selectedMaximum))
            setMinimumChanged(true)
          }}
        />
        <input
          className={`${styles.rangeInput} ${styles.maximumInput}`}
          type="range"
          aria-label={`${legend} 최댓값`}
          aria-valuetext={formatValue(selectedMaximum)}
          aria-valuemin={selectedMinimum}
          aria-valuemax={maximum}
          min={selectedMinimum}
          max={maximum}
          step={step}
          value={selectedMaximum}
          onChange={(event) => {
            const nextValue = normalizeValue(
              Number(event.currentTarget.value),
              minimum,
              maximum,
              step,
            )
            setSelectedMaximum(Math.max(nextValue, selectedMinimum))
            setMaximumChanged(true)
          }}
        />
      </div>

      <ol className={styles.ticks} aria-label={`${legend} 주요 눈금`}>
        {majorTicks.map((tick) => (
          <li
            key={tick}
            className={styles.tick}
            style={{ left: `${rangePercentage(tick, minimum, maximum)}%` }}
          >
            {formatTick(tick)}
          </li>
        ))}
      </ol>

      <input
        type="hidden"
        name={minimumName}
        value={preservedSubmissionValue(
          initialMinimum,
          selectedMinimum,
          minimum,
          maximum,
          minimum,
          preserveInitialValuesUntilChange,
          minimumChanged,
          maximumChanged,
          initialMinimum === null || initialMinimum <= selectedMaximum,
        )}
      />
      <input
        type="hidden"
        name={maximumName}
        value={preservedSubmissionValue(
          initialMaximum,
          selectedMaximum,
          minimum,
          maximum,
          maximum,
          preserveInitialValuesUntilChange,
          maximumChanged,
          minimumChanged,
          initialMaximum === null || selectedMinimum <= initialMaximum,
        )}
      />
    </fieldset>
  )
}

function preservedSubmissionValue(
  initialValue: number | null,
  selectedValue: number,
  minimum: number,
  maximum: number,
  domainBoundary: number,
  preserveInitialValuesUntilChange: boolean,
  changed: boolean,
  oppositeChanged: boolean,
  compatibleWithOppositeValue: boolean,
) {
  if (
    preserveInitialValuesUntilChange
    && !changed
    && (
      !oppositeChanged
      || isCompatibleInitialValue(
        initialValue,
        minimum,
        maximum,
        compatibleWithOppositeValue,
      )
    )
  ) {
    return initialValue ?? ''
  }
  return selectedValue === domainBoundary ? '' : selectedValue
}

function isCompatibleInitialValue(
  initialValue: number | null,
  minimum: number,
  maximum: number,
  compatibleWithOppositeValue: boolean,
) {
  return initialValue === null || (
    initialValue >= minimum
    && initialValue <= maximum
    && compatibleWithOppositeValue
  )
}

function createMajorTicks(minimum: number, maximum: number, majorStep: number) {
  if (!(majorStep > 0) || !(maximum > minimum)) {
    return [minimum, maximum].filter(
      (tick, index, ticks) => index === 0 || tick !== ticks[index - 1],
    )
  }
  const precision = decimalPlacesFor(minimum, maximum, majorStep)
  const count = Math.floor(
    roundToDecimalPlaces((maximum - minimum) / majorStep, 10),
  )
  const ticks = Array.from(
    { length: count + 1 },
    (_, index) => roundToDecimalPlaces(
      minimum + index * majorStep,
      precision,
    ),
  )
  if (ticks.at(-1) !== maximum) {
    ticks.push(maximum)
  }
  return ticks
}

function normalizeRange(
  selectedMinimum: number | null,
  selectedMaximum: number | null,
  minimum: number,
  maximum: number,
  step: number,
) {
  const normalizedMinimum = normalizeValue(
    selectedMinimum ?? minimum,
    minimum,
    maximum,
    step,
  )
  const normalizedMaximum = normalizeValue(
    selectedMaximum ?? maximum,
    minimum,
    maximum,
    step,
  )
  return [
    Math.min(normalizedMinimum, normalizedMaximum),
    Math.max(normalizedMinimum, normalizedMaximum),
  ] as const
}

function normalizeValue(
  value: number,
  minimum: number,
  maximum: number,
  step: number,
) {
  const clamped = Math.min(maximum, Math.max(minimum, value))
  if (clamped === minimum || clamped === maximum) {
    return clamped
  }
  const stepIndex = Math.round((clamped - minimum) / step)
  const snapped = minimum + stepIndex * step
  return Math.min(
    maximum,
    Math.max(
      minimum,
      roundToDecimalPlaces(
        snapped,
        decimalPlacesFor(minimum, maximum, step),
      ),
    ),
  )
}

function rangePercentage(value: number, minimum: number, maximum: number) {
  if (maximum === minimum) {
    return 0
  }
  return roundToDecimalPlaces(
    ((value - minimum) / (maximum - minimum)) * 100,
    10,
  )
}

function validateDomain(
  minimum: number,
  maximum: number,
  step: number,
  majorStep: number,
) {
  if (!Number.isFinite(minimum) || !Number.isFinite(maximum) || minimum >= maximum) {
    throw new Error('DualRangeFilter minimum과 maximum은 유효한 증가 구간이어야 합니다.')
  }
  if (!Number.isFinite(step) || step <= 0) {
    throw new Error('DualRangeFilter step은 0보다 큰 유한수여야 합니다.')
  }
  if (!isStepAligned(maximum, minimum, step)) {
    throw new Error('DualRangeFilter maximum은 minimum부터 step 단위에 맞아야 합니다.')
  }
  if (!Number.isFinite(majorStep) || majorStep <= 0) {
    throw new Error('DualRangeFilter majorStep은 0보다 큰 유한수여야 합니다.')
  }
}

function isStepAligned(value: number, minimum: number, step: number) {
  const steps = (value - minimum) / step
  const tolerance = Number.EPSILON * Math.max(1, Math.abs(steps)) * 8
  return Math.abs(steps - Math.round(steps)) <= tolerance
}

function decimalPlacesFor(...values: readonly number[]) {
  return Math.min(15, Math.max(...values.map(decimalPlaces)))
}

function decimalPlaces(value: number) {
  const [coefficient, exponentText] = Math.abs(value)
    .toString()
    .toLowerCase()
    .split('e')
  const fractionLength = coefficient.split('.')[1]?.length ?? 0
  const exponent = Number(exponentText ?? 0)
  return Math.max(0, fractionLength - exponent)
}

function roundToDecimalPlaces(value: number, places: number) {
  return Number(value.toFixed(places))
}
