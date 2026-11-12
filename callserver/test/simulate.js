// Plays a fake caller against a locally running server: node test/simulate.js <port> <mulaw-file>
import WebSocket from 'ws'
import fs from 'node:fs'
import { mulawToPcm } from '../audio.js'
const [port, file] = process.argv.slice(2)
const speech = fs.readFileSync(file)
const ws = new WebSocket(`ws://localhost:${port}/media`)
let got = 0, chunks = []
ws.on('open', () => {
  ws.send(JSON.stringify({ event: 'start', start: { streamSid: 'MZtest', customParameters: { from: '+15550001111' } } }))
  let i = 0, silence = Buffer.alloc(160, 0xff), t0 = Date.now()
  const speakAt = 9000 // let Buddy finish its opening first
  const timer = setInterval(() => {
    const elapsed = Date.now() - t0
    let frame = silence
    if (elapsed > speakAt && i * 160 < speech.length) { frame = speech.subarray(i * 160, i * 160 + 160); i++ }
    ws.send(JSON.stringify({ event: 'media', streamSid: 'MZtest', media: { payload: Buffer.from(frame).toString('base64') } }))
    if (elapsed > 40000) { clearInterval(timer); ws.send(JSON.stringify({ event: 'stop' })) }
  }, 20)
})
ws.on('message', (d) => { const m = JSON.parse(d.toString()); if (m.event === 'media') { got += 160; chunks.push(Buffer.from(m.media.payload, 'base64')) } })
ws.on('close', () => { console.log(`received ${(got / 8000).toFixed(1)}s of Buddy audio`); fs.writeFileSync('/tmp/buddy_out.ulaw', Buffer.concat(chunks)); process.exit(0) })
