import crypto from 'node:crypto'

// Twilio signs every webhook: base64(HMAC-SHA1(authToken, url + each POST param name+value, sorted by name)).
export function validSignature(authToken, url, params, signature) {
  if (!signature) return false
  const data = url + Object.keys(params).sort().map(k => k + params[k]).join('')
  const expected = crypto.createHmac('sha1', authToken).update(data).digest('base64')
  const a = Buffer.from(expected), b = Buffer.from(signature)
  return a.length === b.length && crypto.timingSafeEqual(a, b)
}

export async function sendSms({ sid, token, from, to, body }) {
  const res = await fetch(`https://api.twilio.com/2010-04-01/Accounts/${sid}/Messages.json`, {
    method: 'POST',
    headers: { Authorization: 'Basic ' + Buffer.from(`${sid}:${token}`).toString('base64'), 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ From: from, To: to, Body: body.slice(0, 1500) })
  })
  if (!res.ok) throw new Error(`SMS failed: ${res.status} ${await res.text()}`)
}

export const esc = (s) => String(s).replace(/[<>&"']/g, c => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' }[c]))
