const workPaths = new Set(['/admin/failures', '/admin/feedback', '/admin/operations', '/admin/notification-deliveries', '/admin/history'])

export function workReturnTo(params: URLSearchParams): string | null {
  const nested = new URLSearchParams(params.get('returnTo')?.split('?')[1] ?? '')
  const value = params.get('workReturnTo') ?? params.get('failureReturnTo') ?? nested.get('workReturnTo') ?? nested.get('failureReturnTo')
  if (!value || !value.startsWith('/admin/') || /[\\\r\n]/.test(value)) return null
  const url = new URL(value, 'https://local.invalid')
  if (url.origin !== 'https://local.invalid' || !workPaths.has(url.pathname)) return null
  return `${url.pathname}${url.search}${url.hash}`
}

export function workDetailUrl(resource: 'COMPLEX' | 'ANNOUNCEMENT', id: number, origin: string, section = 'verification') {
  const comparison = resource === 'ANNOUNCEMENT' && section === 'verification'
  const search = new URLSearchParams({ section: comparison ? 'history' : section, workReturnTo: origin })
  if (comparison) search.set('comparison', 'source')
  return `/admin/${resource === 'COMPLEX' ? 'complexes' : 'announcements'}/${id}?${search}`
}
