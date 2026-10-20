export function failureReturnUrl(path: string, returnTo: string) {
  const url = new URL(path, 'https://local.invalid')
  url.searchParams.set('failureReturnTo', returnTo)
  return `${url.pathname}?${url.searchParams}${url.hash}`
}
