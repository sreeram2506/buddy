import test from 'node:test'
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import { mulawToPcm, pcmToMulaw, up2, down3 } from '../audio.js'
import { validSignature } from '../twilio.js'

test('mu-law round trip stays close', () => {
  const pcm = Int16Array.from([0, 100, -100, 1000, -1000, 8000, -8000, 30000])
  const back = mulawToPcm(pcmToMulaw(pcm))
  pcm.forEach((v, i) => assert.ok(Math.abs(back[i] - v) <= Math.max(64, Math.abs(v) * 0.07), `${v} -> ${back[i]}`))
})
test('resampling sizes', () => {
  assert.equal(up2(new Int16Array(160)).length, 320)
  assert.equal(down3(new Int16Array(480)).length, 160)
})
test('twilio signature', () => {
  const params = { From: '+15551234567', CallSid: 'CA1' }
  const sig = crypto.createHmac('sha1', 'secret').update('https://x.test/voiceCallSidCA1From+15551234567').digest('base64')
  assert.ok(validSignature('secret', 'https://x.test/voice', params, sig))
  assert.ok(!validSignature('secret', 'https://x.test/voice', params, 'nope'))
  assert.ok(!validSignature('secret', 'https://x.test/voice', params, undefined))
})
