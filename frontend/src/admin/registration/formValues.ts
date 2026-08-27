import { AdminRegistrationApiError } from './api'

export function registrationFailure(error: unknown, fallback: string) {
  if (error instanceof AdminRegistrationApiError) {
    return { message: error.message, fieldErrors: error.fieldErrors }
  }
  if (error instanceof Error) {
    return { message: error.message, fieldErrors: {} }
  }
  return { message: fallback, fieldErrors: {} }
}

export function stringValue(formData: FormData, name: string): string {
  return String(formData.get(name) ?? '')
}

export function optionalStringValue(formData: FormData, name: string): string | null {
  const value = stringValue(formData, name)
  return value === '' ? null : value
}

export function numberValue(formData: FormData, name: string): number {
  return Number(stringValue(formData, name))
}

export function options(values: ReadonlyArray<string>) {
  return values.map((value) => ({ label: value, value }))
}
