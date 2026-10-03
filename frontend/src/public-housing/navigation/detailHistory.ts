import type { parseDetailLocation } from './detailLocation.ts'

const DETAIL_RETURN_FOCUS_STACK_KEY = 'toadzipDetailReturnFocusStack'
const LEGACY_DETAIL_ENTRY_KEY = 'toadzipDetailEntry'

export interface DetailReturnFocus {
  readonly actionKey: string
  readonly id: string
  readonly kind: 'announcement' | 'complex'
}


export function withDetailHistoryState(
  state: unknown,
  returnFocus: DetailReturnFocus | null,
) {
  const currentState = isRecord(state) ? state : {}
  const currentStack = readDetailReturnFocusStack(state)
  const nextStack = returnFocus === null
    ? currentStack
    : [...currentStack, returnFocus]
  return {
    ...currentState,
    [DETAIL_RETURN_FOCUS_STACK_KEY]: nextStack,
  }
}

export function clearDetailHistoryState(state: unknown): Record<string, unknown> {
  const nextState = isRecord(state) ? { ...state } : {}
  delete nextState[LEGACY_DETAIL_ENTRY_KEY]
  delete nextState[DETAIL_RETURN_FOCUS_STACK_KEY]
  return nextState
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function toDetailReturnFocusLocation(
  detail: ReturnType<typeof parseDetailLocation>,
): Omit<DetailReturnFocus, 'actionKey'> | null {
  if (detail.kind === 'complex') {
    return { id: detail.complexId, kind: detail.kind }
  }
  if (detail.kind === 'announcement') {
    return { id: detail.announcementId, kind: detail.kind }
  }
  return null
}

export function sameDetailLocation(
  left: Omit<DetailReturnFocus, 'actionKey'>,
  right: Omit<DetailReturnFocus, 'actionKey'>,
) {
  return left.kind === right.kind && left.id === right.id
}

export function readDetailReturnFocusStack(state: unknown): readonly DetailReturnFocus[] {
  if (!isRecord(state)) {
    return []
  }
  const value = state[DETAIL_RETURN_FOCUS_STACK_KEY]
  if (!Array.isArray(value)) {
    return []
  }
  return value.filter(isDetailReturnFocus)
}

function isDetailReturnFocus(value: unknown): value is DetailReturnFocus {
  if (!isRecord(value)) {
    return false
  }
  const validKind = value.kind === 'announcement' || value.kind === 'complex'
  return validKind
    && typeof value.id === 'string'
    && typeof value.actionKey === 'string'
}

export function popDetailHistoryState(state: unknown): Record<string, unknown> {
  return {
    ...clearDetailHistoryState(state),
    [DETAIL_RETURN_FOCUS_STACK_KEY]: readDetailReturnFocusStack(state).slice(0, -1),
  }
}
