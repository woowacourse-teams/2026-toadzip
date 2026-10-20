const workPaths = new Set(['/admin/quality', '/admin/failures', '/admin/feedback', '/admin/complex-coverage', '/admin/operations', '/admin/notification-deliveries', '/admin/history'])

export function workReturnTo(params: URLSearchParams): string | null {
  const nested = new URLSearchParams(params.get('returnTo')?.split('?')[1] ?? '')
  const value = params.get('workReturnTo') ?? params.get('failureReturnTo') ?? nested.get('workReturnTo') ?? nested.get('failureReturnTo')
  if (!value || !value.startsWith('/admin/') || /[\\\r\n]/.test(value)) return null
  const url = new URL(value, 'https://local.invalid')
  if (url.origin !== 'https://local.invalid' || !workPaths.has(url.pathname)) return null
  return `${url.pathname}${url.search}${url.hash}`
}

export function workDetailUrl(resource: 'COMPLEX' | 'ANNOUNCEMENT', id: number, origin: string, section = 'verification') {
  const search = new URLSearchParams({ section, workReturnTo: origin })
  return `/admin/${resource === 'COMPLEX' ? 'complexes' : 'announcements'}/${id}?${search}`
}
