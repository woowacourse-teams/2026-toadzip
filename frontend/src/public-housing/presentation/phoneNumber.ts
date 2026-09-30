export function formatPhoneNumber(value: string | null): string | null {
  if (value === null) {
    return null
  }
  return value.replace(/(?<![\d+])(?:0\d{8,11}|1[568]\d{6})(?!\d)/g, (number) => {
    if (number.length === 8) {
      return `${number.slice(0, 4)}-${number.slice(4)}`
    }
    const prefixLength = number.startsWith('02') ? 2 : number.startsWith('050') && number.length === 12 ? 4 : 3
    const middleLength = number.length - prefixLength - 4
    if (middleLength !== 3 && middleLength !== 4) {
      return number
    }
    return `${number.slice(0, prefixLength)}-${number.slice(prefixLength, -4)}-${number.slice(-4)}`
  })
}
