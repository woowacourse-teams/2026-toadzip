/// <reference types="node" />

import { fireEvent, render, screen, within } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  DualRangeFilter,
  type DualRangeFilterPreset,
} from './DualRangeFilter'

const PRESETS = [
  { label: '전체', minimum: null, maximum: null },
  { label: '5천만 원 이하', minimum: null, maximum: 50 },
  { label: '3천만~7천만 원', minimum: 30, maximum: 70 },
  { label: '5천만 원 이상', minimum: 50, maximum: null },
] as const satisfies readonly DualRangeFilterPreset[]

function formatValue(value: number) {
  return `${value * 100}만 원`
}

function renderFilter({
  initialMinimum = null,
  initialMaximum = null,
  presets = PRESETS,
  preserveInitialValuesUntilChange = false,
}: {
  readonly initialMinimum?: number | null
  readonly initialMaximum?: number | null
  readonly presets?: readonly DualRangeFilterPreset[]
  readonly preserveInitialValuesUntilChange?: boolean
} = {}) {
  const result = render(
    <form aria-label="검색 조건">
      <DualRangeFilter
        legend="임대보증금"
        minimumName="minDeposit"
        maximumName="maxDeposit"
        minimum={0}
        maximum={100}
        step={10}
        majorStep={25}
        initialMinimum={initialMinimum}
        initialMaximum={initialMaximum}
        formatValue={formatValue}
        formatTick={(value) => `${value}`}
        presets={presets}
        preserveInitialValuesUntilChange={preserveInitialValuesUntilChange}
      />
      <button type="reset">초기화</button>
    </form>,
  )

  const form = screen.getByRole('form', {
    name: '검색 조건',
  }) as HTMLFormElement
  const minimumInput = screen.getByRole('slider', {
    name: '임대보증금 최솟값',
  }) as HTMLInputElement
  const maximumInput = screen.getByRole('slider', {
    name: '임대보증금 최댓값',
  }) as HTMLInputElement
  const hiddenMinimum = form.elements.namedItem(
    'minDeposit',
  ) as HTMLInputElement
  const hiddenMaximum = form.elements.namedItem(
    'maxDeposit',
  ) as HTMLInputElement
  const output = screen.getByRole('status', {
    name: '임대보증금 선택 범위',
  })

  return {
    ...result,
    form,
    hiddenMaximum,
    hiddenMinimum,
    maximumInput,
    minimumInput,
    output,
  }
}

describe('DualRangeFilter', () => {
  it('null 양끝값을 전체 범위로 표시하고 API용 hidden 값은 비운다', () => {
    const {
      hiddenMaximum,
      hiddenMinimum,
      maximumInput,
      minimumInput,
      output,
    } = renderFilter()

    expect(minimumInput).toHaveAttribute('type', 'range')
    expect(minimumInput).toHaveAttribute('min', '0')
    expect(minimumInput).toHaveAttribute('max', '100')
    expect(minimumInput).toHaveAttribute('step', '10')
    expect(minimumInput).toHaveValue('0')
    expect(minimumInput).toHaveAttribute('aria-valuetext', '0만 원')

    expect(maximumInput).toHaveAttribute('type', 'range')
    expect(maximumInput).toHaveAttribute('min', '0')
    expect(maximumInput).toHaveAttribute('max', '100')
    expect(maximumInput).toHaveAttribute('step', '10')
    expect(maximumInput).toHaveValue('100')
    expect(maximumInput).toHaveAttribute('aria-valuetext', '10000만 원')

    expect(output).toHaveTextContent('전체')
    expect(hiddenMinimum).toHaveAttribute('type', 'hidden')
    expect(hiddenMinimum).toHaveValue('')
    expect(hiddenMaximum).toHaveAttribute('type', 'hidden')
    expect(hiddenMaximum).toHaveValue('')
  })

  it.each([
    {
      label: '상한만 선택하면 이하로 표시한다',
      initialMinimum: null,
      initialMaximum: 60,
      expectedOutput: '6000만 원 이하',
      expectedMinimum: '',
      expectedMaximum: '60',
    },
    {
      label: '하한만 선택하면 이상으로 표시한다',
      initialMinimum: 20,
      initialMaximum: null,
      expectedOutput: '2000만 원 이상',
      expectedMinimum: '20',
      expectedMaximum: '',
    },
    {
      label: '양끝을 선택하면 구간으로 표시한다',
      initialMinimum: 20,
      initialMaximum: 60,
      expectedOutput: '2000만 원~6000만 원',
      expectedMinimum: '20',
      expectedMaximum: '60',
    },
  ])(
    '$label',
    ({
      initialMinimum,
      initialMaximum,
      expectedOutput,
      expectedMinimum,
      expectedMaximum,
    }) => {
      const { hiddenMaximum, hiddenMinimum, output } = renderFilter({
        initialMinimum,
        initialMaximum,
      })

      expect(output).toHaveTextContent(expectedOutput)
      expect(hiddenMinimum).toHaveValue(expectedMinimum)
      expect(hiddenMaximum).toHaveValue(expectedMaximum)
    },
  )

  it('최솟값 손잡이는 선택값을 갱신하되 최댓값을 넘어가지 않는다', () => {
    const { hiddenMinimum, minimumInput, output } = renderFilter({
      initialMinimum: 20,
      initialMaximum: 60,
    })

    fireEvent.change(minimumInput, { target: { value: '50' } })

    expect(minimumInput).toHaveValue('50')
    expect(minimumInput).toHaveAttribute('aria-valuetext', '5000만 원')
    expect(minimumInput).toHaveAttribute('max', '60')
    expect(minimumInput).toHaveAttribute('aria-valuemax', '60')
    expect(hiddenMinimum).toHaveValue('50')
    expect(output).toHaveTextContent('5000만 원~6000만 원')

    fireEvent.change(minimumInput, { target: { value: '90' } })

    expect(minimumInput).toHaveValue('60')
    expect(hiddenMinimum).toHaveValue('60')
    expect(output).toHaveTextContent('6000만 원~6000만 원')
  })

  it('최댓값 손잡이는 선택값을 갱신하되 최솟값 아래로 내려가지 않는다', () => {
    const { hiddenMaximum, maximumInput, output } = renderFilter({
      initialMinimum: 20,
      initialMaximum: 60,
    })

    fireEvent.change(maximumInput, { target: { value: '40' } })

    expect(maximumInput).toHaveValue('40')
    expect(maximumInput).toHaveAttribute('aria-valuetext', '4000만 원')
    expect(maximumInput).toHaveAttribute('min', '20')
    expect(maximumInput).toHaveAttribute('aria-valuemin', '20')
    expect(hiddenMaximum).toHaveValue('40')
    expect(output).toHaveTextContent('2000만 원~4000만 원')

    fireEvent.change(maximumInput, { target: { value: '10' } })

    expect(maximumInput).toHaveValue('20')
    expect(hiddenMaximum).toHaveValue('20')
    expect(output).toHaveTextContent('2000만 원~2000만 원')
  })

  it('한 손잡이를 조작하면 같은 범위의 양끝을 정규화해 제출한다', () => {
    const {
      hiddenMaximum,
      hiddenMinimum,
      minimumInput,
    } = renderFilter({
      initialMinimum: 15,
      initialMaximum: 25,
      preserveInitialValuesUntilChange: true,
    })

    expect(hiddenMinimum).toHaveValue('15')
    expect(hiddenMaximum).toHaveValue('25')

    fireEvent.change(minimumInput, { target: { value: '30' } })

    expect(hiddenMinimum).toHaveValue('30')
    expect(hiddenMaximum).toHaveValue('30')
  })

  it('최솟값만 바꾸면 호환되는 off-step 초기 최댓값을 그대로 제출한다', () => {
    const { hiddenMaximum, hiddenMinimum, minimumInput } = renderFilter({
      initialMinimum: 15,
      initialMaximum: 55,
      preserveInitialValuesUntilChange: true,
    })

    fireEvent.change(minimumInput, { target: { value: '30' } })

    expect(hiddenMinimum).toHaveValue('30')
    expect(hiddenMaximum).toHaveValue('55')
  })

  it('최댓값만 바꾸면 호환되는 off-step 초기 최솟값을 그대로 제출한다', () => {
    const { hiddenMaximum, hiddenMinimum, maximumInput } = renderFilter({
      initialMinimum: 15,
      initialMaximum: 55,
      preserveInitialValuesUntilChange: true,
    })

    fireEvent.change(maximumInput, { target: { value: '80' } })

    expect(hiddenMinimum).toHaveValue('15')
    expect(hiddenMaximum).toHaveValue('80')
  })

  it('최댓값만 바꾸면 명시적인 domain 최솟값을 그대로 제출한다', () => {
    const { hiddenMaximum, hiddenMinimum, maximumInput } = renderFilter({
      initialMinimum: 0,
      initialMaximum: 60,
      preserveInitialValuesUntilChange: true,
    })

    fireEvent.change(maximumInput, { target: { value: '80' } })

    expect(hiddenMinimum).toHaveValue('0')
    expect(hiddenMaximum).toHaveValue('80')
  })

  it('최솟값이 원래 최댓값을 넘으면 정규화된 최댓값을 제출한다', () => {
    const result = render(
      <form aria-label="고액 검색 조건">
        <DualRangeFilter
          legend="임대보증금"
          minimumName="minDeposit"
          maximumName="maxDeposit"
          minimum={0}
          maximum={200}
          step={10}
          majorStep={25}
          initialMinimum={140}
          initialMaximum={155}
          formatValue={formatValue}
          formatTick={String}
          presets={[]}
          preserveInitialValuesUntilChange
        />
      </form>,
    )
    const form = screen.getByRole('form', {
      name: '고액 검색 조건',
    }) as HTMLFormElement
    const minimumInput = screen.getByRole('slider', {
      name: '임대보증금 최솟값',
    })
    const hiddenMaximum = form.elements.namedItem(
      'maxDeposit',
    ) as HTMLInputElement

    fireEvent.change(minimumInput, { target: { value: '150' } })

    expect(hiddenMaximum).toHaveValue('155')

    fireEvent.change(minimumInput, { target: { value: '160' } })

    expect(hiddenMaximum).toHaveValue('160')
    result.unmount()
  })

  it('한 손잡이를 조작하면 반대쪽 초과값도 endpoint 의미로 정규화한다', () => {
    const {
      hiddenMaximum,
      hiddenMinimum,
      minimumInput,
    } = renderFilter({
      initialMinimum: 0,
      initialMaximum: 120,
      preserveInitialValuesUntilChange: true,
    })

    expect(hiddenMinimum).toHaveValue('0')
    expect(hiddenMaximum).toHaveValue('120')

    fireEvent.change(minimumInput, { target: { value: '20' } })

    expect(hiddenMinimum).toHaveValue('20')
    expect(hiddenMaximum).toHaveValue('')
  })

  it('preset의 null을 domain 끝으로 해석하고 현재 preset을 aria-pressed로 알린다', () => {
    const {
      hiddenMaximum,
      hiddenMinimum,
      maximumInput,
      minimumInput,
      output,
    } = renderFilter({ initialMinimum: 30, initialMaximum: 70 })
    const presets = screen.getByRole('group', {
      name: '임대보증금 빠른 선택',
    })
    const intervalPreset = within(presets).getByRole('button', {
      name: '3천만~7천만 원',
    })
    const maximumPreset = within(presets).getByRole('button', {
      name: '5천만 원 이하',
    })
    const minimumPreset = within(presets).getByRole('button', {
      name: '5천만 원 이상',
    })
    const allPreset = within(presets).getByRole('button', { name: '전체' })

    expect(intervalPreset).toHaveAttribute('aria-pressed', 'true')
    expect(allPreset).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(maximumPreset)

    expect(maximumPreset).toHaveAttribute('aria-pressed', 'true')
    expect(minimumInput).toHaveValue('0')
    expect(maximumInput).toHaveValue('50')
    expect(hiddenMinimum).toHaveValue('')
    expect(hiddenMaximum).toHaveValue('50')
    expect(output).toHaveTextContent('5000만 원 이하')

    fireEvent.click(minimumPreset)

    expect(minimumPreset).toHaveAttribute('aria-pressed', 'true')
    expect(minimumInput).toHaveValue('50')
    expect(maximumInput).toHaveValue('100')
    expect(hiddenMinimum).toHaveValue('50')
    expect(hiddenMaximum).toHaveValue('')
    expect(output).toHaveTextContent('5000만 원 이상')

    fireEvent.click(allPreset)

    expect(allPreset).toHaveAttribute('aria-pressed', 'true')
    expect(hiddenMinimum).toHaveValue('')
    expect(hiddenMaximum).toHaveValue('')
    expect(output).toHaveTextContent('전체')
  })

  it('부모 form의 reset 이벤트가 초기 선택 범위를 복원한다', () => {
    const {
      hiddenMaximum,
      hiddenMinimum,
      maximumInput,
      minimumInput,
      output,
    } = renderFilter({ initialMinimum: 20, initialMaximum: 60 })

    fireEvent.change(minimumInput, { target: { value: '50' } })
    fireEvent.change(maximumInput, { target: { value: '80' } })
    expect(output).toHaveTextContent('5000만 원~8000만 원')

    fireEvent.click(screen.getByRole('button', { name: '초기화' }))

    expect(minimumInput).toHaveValue('20')
    expect(maximumInput).toHaveValue('60')
    expect(hiddenMinimum).toHaveValue('20')
    expect(hiddenMaximum).toHaveValue('60')
    expect(output).toHaveTextContent('2000만 원~6000만 원')
  })

  it('majorStep 눈금 라벨과 선택 구간 track 위치를 계산한다', () => {
    renderFilter({ initialMinimum: 20, initialMaximum: 60 })
    const filter = screen.getByRole('group', { name: '임대보증금' })
    const ticks = screen.getByRole('list', {
      name: '임대보증금 주요 눈금',
    })

    expect(
      within(ticks).getAllByRole('listitem').map((tick) => tick.textContent),
    ).toEqual(['0', '25', '50', '75', '100'])
    expect(filter.style.getPropertyValue('--range-start')).toBe('20%')
    expect(filter.style.getPropertyValue('--range-end')).toBe('60%')
    expect(
      filter.style.getPropertyValue('--minimum-input-compensation'),
    ).toBe('17.6px')
    expect(
      filter.style.getPropertyValue('--maximum-input-compensation'),
    ).toBe('8.8px')
  })

  it('domain 밖 초기값을 양끝으로 clamp해 표시값과 제출값을 일치시킨다', () => {
    const {
      hiddenMaximum,
      hiddenMinimum,
      maximumInput,
      minimumInput,
      output,
    } = renderFilter({ initialMinimum: -20, initialMaximum: 120 })

    expect(minimumInput).toHaveValue('0')
    expect(maximumInput).toHaveValue('100')
    expect(hiddenMinimum).toHaveValue('')
    expect(hiddenMaximum).toHaveValue('')
    expect(output).toHaveTextContent('전체')
  })

  it('소수 domain과 majorStep을 부동소수점 잡음 없이 표시하고 제출한다', () => {
    render(
      <DualRangeFilter
        legend="전용면적"
        minimumName="minArea"
        maximumName="maxArea"
        minimum={0.1}
        maximum={3.3}
        step={0.1}
        majorStep={0.8}
        initialMinimum={0.3}
        initialMaximum={2.7}
        formatValue={(value) => `${value}㎡`}
        formatTick={(value) => String(value)}
        presets={[]}
      />,
    )
    const filter = screen.getByRole('group', { name: '전용면적' })
    const minimumInput = screen.getByRole('slider', {
      name: '전용면적 최솟값',
    }) as HTMLInputElement
    const hiddenMinimum = document.querySelector(
      'input[name="minArea"]',
    ) as HTMLInputElement
    const ticks = screen.getByRole('list', { name: '전용면적 주요 눈금' })

    expect(
      within(ticks).getAllByRole('listitem').map((tick) => tick.textContent),
    ).toEqual(['0.1', '0.9', '1.7', '2.5', '3.3'])
    expect(filter.style.getPropertyValue('--range-start')).toBe('6.25%')
    expect(filter.style.getPropertyValue('--range-end')).toBe('81.25%')

    fireEvent.change(minimumInput, {
      target: { value: '0.30000000000000004' },
    })

    expect(minimumInput).toHaveValue('0.3')
    expect(hiddenMinimum).toHaveValue('0.3')
  })

  it('off-step 초기값과 preset을 가까운 유효 step으로 맞춰 form validity를 보존한다', () => {
    const offStepPreset = [
      { label: '엇갈린 눈금', minimum: 15, maximum: 85 },
    ] as const satisfies readonly DualRangeFilterPreset[]
    const {
      form,
      hiddenMaximum,
      hiddenMinimum,
      maximumInput,
      minimumInput,
    } = renderFilter({
      initialMinimum: 25,
      initialMaximum: 75,
      presets: offStepPreset,
    })

    expect(minimumInput).toHaveValue('30')
    expect(maximumInput).toHaveValue('80')
    expect(hiddenMinimum).toHaveValue('30')
    expect(hiddenMaximum).toHaveValue('80')
    expect(form.checkValidity()).toBe(true)

    fireEvent.click(screen.getByRole('button', { name: '엇갈린 눈금' }))

    expect(minimumInput).toHaveValue('20')
    expect(maximumInput).toHaveValue('90')
    expect(hiddenMinimum).toHaveValue('20')
    expect(hiddenMaximum).toHaveValue('90')
    expect(form.checkValidity()).toBe(true)
  })

  it('유효하지 않은 domain step을 명시적으로 거절한다', () => {
    expect(() => render(
      <DualRangeFilter
        legend="잘못된 범위"
        minimumName="minimum"
        maximumName="maximum"
        minimum={0}
        maximum={100}
        step={0}
        majorStep={10}
        initialMinimum={null}
        initialMaximum={null}
        formatValue={String}
        formatTick={String}
        presets={[]}
      />,
    )).toThrow(/step/)
  })

  it('두 range를 한 track으로 겹치고 손잡이와 preset에 44px 터치 목표를 둔다', () => {
    const filterStyles = readFileSync(
      resolve(
        process.cwd(),
        'src/public-housing/filters/DualRangeFilter.module.css',
      ),
      'utf8',
    )

    expect(filterStyles).toMatch(
      /\.rangeInput\s*\{[\s\S]*?height:\s*44px;[\s\S]*?pointer-events:\s*none;/,
    )
    expect(filterStyles).toMatch(
      /\.rangeInput::-webkit-slider-thumb\s*\{[\s\S]*?width:\s*44px;[\s\S]*?height:\s*44px;/,
    )
    expect(filterStyles).toMatch(
      /\.rangeInput::-moz-range-thumb\s*\{[\s\S]*?width:\s*44px;[\s\S]*?height:\s*44px;/,
    )
    expect(filterStyles).toMatch(
      /\.preset\s*\{[\s\S]*?min-width:\s*44px;[\s\S]*?min-height:\s*44px;/,
    )
    expect(filterStyles).toMatch(
      /\.selectedTrack\s*\{[\s\S]*?left:\s*var\(--range-start\);[\s\S]*?right:\s*calc\(100% - var\(--range-end\)\);/,
    )
    expect(filterStyles).toMatch(
      /\.minimumInput\s*\{[\s\S]*?width:\s*calc\(var\(--range-end\) \+ var\(--minimum-input-compensation\)\);/,
    )
    expect(filterStyles).toMatch(
      /\.maximumInput\s*\{[\s\S]*?left:\s*calc\(var\(--range-start\) - var\(--maximum-input-compensation\)\);/,
    )
    expect(filterStyles).toMatch(
      /\.rangeInput:focus-visible::-webkit-slider-thumb\s*\{[\s\S]*?outline:/,
    )
    expect(filterStyles).toMatch(
      /\.rangeInput:focus-visible::-moz-range-thumb\s*\{[\s\S]*?outline:/,
    )
    expect(filterStyles).toMatch(
      /\.rangeInput:focus-visible\s*\{[\s\S]*?z-index:\s*5;/,
    )
    const forcedColors = filterStyles.slice(
      filterStyles.indexOf('@media (forced-colors: active)'),
    )
    expect(forcedColors).toMatch(
      /\.rangeInput::-webkit-slider-thumb\s*\{[\s\S]*?forced-color-adjust:\s*auto;/,
    )
    expect(forcedColors).toMatch(
      /\.rangeInput::-moz-range-thumb\s*\{[\s\S]*?forced-color-adjust:\s*auto;/,
    )
    expect(forcedColors).toMatch(
      /\.rangeInput:focus-visible::-webkit-slider-thumb\s*\{[\s\S]*?outline:\s*3px solid Highlight;/,
    )
    expect(forcedColors).toMatch(
      /\.rangeInput:focus-visible::-moz-range-thumb\s*\{[\s\S]*?outline:\s*3px solid Highlight;/,
    )
    expect(forcedColors).toMatch(
      /\.preset\[aria-pressed='true'\]::before\s*\{[\s\S]*?border-color:\s*Highlight;[\s\S]*?background:\s*Highlight;/,
    )
    expect(forcedColors).toMatch(
      /\.preset\[aria-pressed='true'\]\s*\{[\s\S]*?color:\s*HighlightText;/,
    )
    expect(forcedColors).toMatch(
      /\.preset:focus-visible::before\s*\{[\s\S]*?outline:\s*3px solid Highlight;/,
    )
  })

  it('빠른 선택을 한 줄로 유지하고 44px 터치 영역 안에 36px 둥근 사각형을 보인다', () => {
    const filterStyles = readFileSync(
      resolve(
        process.cwd(),
        'src/public-housing/filters/DualRangeFilter.module.css',
      ),
      'utf8',
    )

    expect(filterStyles).toMatch(
      /\.presets\s*\{[\s\S]*?flex-wrap:\s*nowrap;[\s\S]*?overflow-x:\s*auto;[\s\S]*?overflow-y:\s*hidden;/,
    )
    expect(filterStyles).toMatch(
      /\.preset\s*\{[\s\S]*?flex:\s*0 0 auto;[\s\S]*?min-width:\s*44px;[\s\S]*?min-height:\s*44px;[\s\S]*?white-space:\s*nowrap;/,
    )
    expect(filterStyles).toMatch(
      /\.preset::before\s*\{[\s\S]*?height:\s*36px;[\s\S]*?border-radius:\s*9px;/,
    )
    expect(filterStyles).toMatch(
      /\.preset:focus-visible::before\s*\{[\s\S]*?outline:\s*3px solid/,
    )
  })

  it('두 값이 domain maximum에서 겹치면 최솟값 손잡이를 위로 올린다', () => {
    const { minimumInput } = renderFilter({
      initialMinimum: 20,
      initialMaximum: null,
    })

    fireEvent.change(minimumInput, { target: { value: '100' } })

    expect(minimumInput.className).toMatch(/minimumOnTop/)

    const filterStyles = readFileSync(
      resolve(
        process.cwd(),
        'src/public-housing/filters/DualRangeFilter.module.css',
      ),
      'utf8',
    )
    expect(filterStyles).toMatch(
      /\.minimumOnTop\s*\{[\s\S]*?z-index:\s*4;/,
    )

    fireEvent.change(minimumInput, { target: { value: '90' } })
    expect(minimumInput.className).not.toMatch(/minimumOnTop/)
  })
})
