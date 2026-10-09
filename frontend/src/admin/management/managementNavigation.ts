import type { ManagementResource } from './managementContract'

const filterKeys = ['keyword', 'region', 'provider', 'rental', 'deleted', 'review', 'complexId', 'page']

export function managementListParams(params: URLSearchParams, resource: ManagementResource) {
  const returnTo = params.get('returnTo')
  const source = returnTo?.startsWith(`/admin/${resource}?`)
    ? new URLSearchParams(returnTo.slice(returnTo.indexOf('?') + 1)) : params
  const result = new URLSearchParams()
  for (const key of filterKeys) {
    const value = source.get(key)
    // During announcement registration complexId is a selection, not a list filter.
    if (key === 'complexId' && params.has('mode')) continue
    if (value) result.set(key, value)
  }
  return result
}

export function managementUrl(resource: ManagementResource, params: URLSearchParams, id = '') {
  const search = params.toString()
  return `/admin/${resource}${id ? `/${id}` : ''}${search ? `?${search}` : ''}`
}
