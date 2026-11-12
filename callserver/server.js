import http from 'node:http'
import { WebSocketServer } from 'ws'
import { Call } from './call.js'
import { validSignature, sendSms, esc } from './twilio.js'

const env = process.env
const cfg = {
  port: Number(env.PORT || 8080),
  geminiKey: env.GEMINI_API_KEY,
  sid: env.TWILIO_ACCOUNT_SID, token: env.TWILIO_AUTH_TOKEN, number: env.TWILIO_NUMBER,
  owner: env.OWNER_NUMBER, ownerName: env.OWNER_NAME || 'Sreeram',
  host: env.PUBLIC_HOST, // e.g. buddy-calls.fly.dev (no scheme)
  maxCalls: Number(env.MAX_CONCURRENT_CALLS || 5),
  allowList: (env.ALLOWED_CALLED_NUMBERS || '').split(',').map(s => s.trim()).filter(Boolean)
}
for (const k of ['geminiKey', 'sid', 'token', 'number', 'owner', 'host']) if (!cfg[k]) { console.error(`Missing config: ${k}`); process.exit(1) }

let active = 0
const log = (...a) => console.log(new Date().toISOString(), ...a)
const form = (req) => new Promise((res, rej) => { let b = ''; req.on('data', c => { b += c; if (b.length > 1e5) req.destroy() }); req.on('end', () => res(Object.fromEntries(new URLSearchParams(b)))); req.on('error', rej) })

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') return res.end('ok')
  if (req.method === 'POST' && req.url === '/voice') {
    const params = await form(req)
    if (!validSignature(cfg.token, `https://${cfg.host}/voice`, params, req.headers['x-twilio-signature'])) { res.statusCode = 403; return res.end('bad signature') }
    if (active >= cfg.maxCalls) { res.setHeader('Content-Type', 'text/xml'); return res.end('<Response><Say>Sorry, please try again later.</Say><Hangup/></Response>') }
    const from = params.From || ''
    res.setHeader('Content-Type', 'text/xml')
    return res.end(`<Response><Connect><Stream url="wss://${cfg.host}/media"><Parameter name="from" value="${esc(from)}"/></Stream></Connect></Response>`)
  }
  res.statusCode = 404; res.end()
})

const wss = new WebSocketServer({ server, path: '/media', maxPayload: 1 << 20 })
wss.on('connection', (ws) => {
  active++
  // The caller's number arrives in Twilio's "start" message; wait for it, then hand over to the call.
  ws.once('message', (first) => {
    let from = ''
    try { from = JSON.parse(first.toString()).start?.customParameters?.from ?? '' } catch {}
    const call = new Call({ twilio: ws, cfg, from, log, onDone: async (r) => { active--; await report(r) } })
    call.start()
    call.onTwilio({ event: 'start', start: JSON.parse(first.toString()).start ?? {} })
  })
})

async function report(r) {
  const who = r.name || r.from || 'Unknown caller'
  const body = r.message
    ? `${r.urgent ? 'URGENT call' : 'Call'} from ${who}${r.name && r.from ? ` (${r.from})` : ''}: ${r.message}`
    : r.transcript.length ? `Missed call from ${who}. They didn't leave a clear message. ${r.transcript.slice(0, 4).join(' / ')}`
    : `Missed call from ${who}. They hung up without speaking.`
  try { await sendSms({ sid: cfg.sid, token: cfg.token, from: cfg.number, to: cfg.owner, body }); log('message sent to owner') }
  catch (e) { log('could not text owner:', e.message) }
}

server.listen(cfg.port, () => log(`Buddy call server on :${cfg.port}`))
process.on('SIGTERM', () => { log('shutting down'); server.close(); setTimeout(() => process.exit(0), 5000) })
