const paths = {
  building: 'M4 21V3h12v18M16 9h4v12M8 7h4M8 11h4M8 15h4M8 21v-3h4v3',
  document: 'M6 3h9l4 4v14H6zM14 3v5h5M9 12h7M9 16h7',
  users: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8M22 21v-2a4 4 0 0 0-3-3.87M16 3a4 4 0 0 1 0 8',
  bell: 'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4',
  refresh: 'M20 7V3l-3 3a8 8 0 1 0 3 9M20 3h-5',
  info: 'M12 11v6M12 7h.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  alert: 'M12 3 2 21h20L12 3zM12 9v5M12 17h.01',
  message: 'M21 15a3 3 0 0 1-3 3H8l-5 4V6a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3zM7 8h10M7 12h6',
}
export function OperationsIcon({ name }: { name: keyof typeof paths }) {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]} /></svg>
}
