/*
 * Formatting utilities ported from handlebars-rendering.js and filters.js
 */

function formatWithExactlyOneFractionalDigit(value: number): string {
  return (Math.round(value * 10) / 10).toLocaleString(undefined, {
    minimumFractionDigits: 1,
  })
}

export function formatBytes(bytes: number): string {
  if (isNaN(parseFloat(String(bytes))) || !isFinite(bytes)) {
    return '-'
  }
  if (bytes === 0) {
    return '0'
  }
  const units = ['bytes', 'KB', 'MB', 'GB', 'TB', 'PB']
  const number = Math.floor(Math.log(bytes) / Math.log(1024))
  const num = bytes / Math.pow(1024, number)
  if (number === 0) {
    return num.toFixed(0) + ' bytes'
  } else {
    return num.toFixed(1) + ' ' + units[number]
  }
}

export function formatMillis(millis: number): string {
  if (Math.abs(millis) < 0.0000005) {
    return '0.0'
  }
  if (Math.abs(millis) < 0.000001) {
    return '0.000001'
  }
  if (Math.abs(millis) < 0.00001) {
    return millis.toPrecision(1)
  }
  if (Math.abs(millis) < 1) {
    return millis.toPrecision(2)
  }
  return formatWithExactlyOneFractionalDigit(millis)
}

export function formatCount(count: number | undefined): string {
  if (count === undefined) {
    return ''
  }
  if (Math.abs(count) < 0.1) {
    return count.toPrecision(1)
  }
  return formatWithExactlyOneFractionalDigit(count)
}

export function formatPercent(percent: number): string {
  if (percent === 100) {
    return '100'
  }
  if (percent > 99.9) {
    return '99.9'
  }
  if (percent === 0) {
    return '0'
  }
  if (percent < 0.1) {
    return '0.1'
  }
  return formatCount(percent)
}

export function formatDuration(input: number | undefined): string {
  if (input === undefined) {
    return ''
  }
  const totalMs = input
  const days = Math.floor(totalMs / (24 * 60 * 60 * 1000))
  const hours = Math.floor((totalMs % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000))
  const minutes = Math.floor((totalMs % (60 * 60 * 1000)) / (60 * 1000))
  const seconds = Math.floor((totalMs % (60 * 1000)) / 1000)

  const parts: string[] = []
  if (days >= 1) {
    parts.push(days + 'd')
  }
  if (parts.length || hours >= 1) {
    parts.push(hours + 'h')
  }
  if (parts.length || minutes >= 1) {
    parts.push(minutes + 'm')
  }
  if (parts.length || seconds >= 1) {
    parts.push(seconds + 's')
  }
  if (!parts.length) {
    return totalMs + 'ms'
  }
  return parts.join(' ')
}

export function formatGaugeValue(value: number): string {
  let nonScaledValue: number
  if (value < 1000000) {
    nonScaledValue = parseFloat(value.toPrecision(6))
  } else {
    nonScaledValue = Math.round(value)
  }
  return nonScaledValue.toLocaleString(undefined, { maximumSignificantDigits: 15 })
}
