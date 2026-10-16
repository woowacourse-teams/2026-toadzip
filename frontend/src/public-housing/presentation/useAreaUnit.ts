import { useSyncExternalStore } from 'react'
import type { AreaUnit } from './housingArea'

const STORAGE_KEY = 'toadzip.area-unit'
const listeners = new Set<() => void>()
let lastSelectedUnit: AreaUnit = 'sqm'
let hasUnsavedSelection = false

function getAreaUnit(): AreaUnit {
  if (hasUnsavedSelection) return lastSelectedUnit
  try {
    const stored: unknown = window.localStorage.getItem(STORAGE_KEY)
    return stored === 'sqm' || stored === 'pyeong' ? stored : 'sqm'
  } catch {
    return lastSelectedUnit
  }
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

function toggleAreaUnit() {
  lastSelectedUnit = getAreaUnit() === 'sqm' ? 'pyeong' : 'sqm'
  try {
    window.localStorage.setItem(STORAGE_KEY, lastSelectedUnit)
    hasUnsavedSelection = false
  } catch {
    // Keep the current choice across detail navigation even when storage is unavailable.
    hasUnsavedSelection = true
  }
  listeners.forEach((listener) => listener())
}

export function useAreaUnit() {
  const areaUnit = useSyncExternalStore(subscribe, getAreaUnit)
  return { areaUnit, toggleAreaUnit }
}
