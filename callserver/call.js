import WebSocket from 'ws'
import { mulawToPcm, pcmToMulaw, up2, down3, pcmBytes, bytesPcm } from './audio.js'
import { prompt, tools } from './prompt.js'

const MAX_CALL_MS = 4 * 60 * 1000

// One phone call: Twilio's audio stream on one side, a Gemini Live conversation on the other.
export class Call {
  constructor({ twilio, cfg, from, onDone, log = console.log }) {
    this.twilio = twilio; this.cfg = cfg; this.from = from; this.onDone = onDone; this.log = log
    this.streamSid = null
    this.message = null; this.callerName = ''; this.urgent = false
    this.transcript = []
    this.userBuf = ''; this.buddyBuf = ''
    this.endAfterTurn = false; this.closed = false
    this.startedAt = Date.now()
  }

  start() {
    const url = `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${this.cfg.geminiKey}`
    this.gemini = new WebSocket(url)
    this.gemini.on('open', () => this.gemini.send(JSON.stringify(this.setup())))
    this.gemini.on('message', (d) => { try { this.onGemini(JSON.parse(d.toString())) } catch (e) { this.log('gemini parse', e.message) } })
    this.gemini.on('close', () => this.finish('gemini closed'))
    this.gemini.on('error', (e) => { this.log('gemini error', e.message); this.finish('gemini error') })
    this.timer = setTimeout(() => this.finish('time limit'), MAX_CALL_MS)

    this.twilio.on('message', (d) => { try { this.onTwilio(JSON.parse(d.toString())) } catch (e) { this.log('twilio parse', e.message) } })
    this.twilio.on('close', () => this.finish('caller hung up'))
    this.twilio.on('error', () => this.finish('twilio error'))
  }

  setup() {
    return { setup: {
      model: 'models/gemini-3.8-live',
      generationConfig: { responseModalities: ['AUDIO'], speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: 'Puck' } } } },
      systemInstruction: { parts: [{ text: prompt(this.cfg.ownerName) }] },
      tools,
      realtimeInputConfig: { automaticActivityDetection: { startOfSpeechSensitivity: 'START_SENSITIVITY_HIGH', endOfSpeechSensitivity: 'END_SENSITIVITY_LOW', prefixPaddingMs: 200, silenceDurationMs: 900 } },
      inputAudioTranscription: {}, outputAudioTranscription: {}
    } }
  }

  onTwilio(m) {
    if (m.event === 'start') { this.streamSid = m.start.streamSid; this.log('call started', this.from) }
    else if (m.event === 'media' && this.ready) {
      const pcm16 = up2(mulawToPcm(Buffer.from(m.media.payload, 'base64')))
      this.sendGemini({ realtimeInput: { audio: { data: pcmBytes(pcm16).toString('base64'), mimeType: 'audio/pcm;rate=16000' } } })
    } else if (m.event === 'stop') this.finish('caller hung up')
  }

  sendGemini(o) { if (this.gemini.readyState === WebSocket.OPEN) this.gemini.send(JSON.stringify(o)) }
  sendTwilio(o) { if (this.twilio.readyState === WebSocket.OPEN) this.twilio.send(JSON.stringify(o)) }

  onGemini(m) {
    if (m.setupComplete) {
      this.ready = true
      this.sendGemini({ clientContent: { turns: [{ role: 'user', parts: [{ text: '[The caller has just picked up and the call is connected.] Say your opening line now.' }] }], turnComplete: true } })
      return
    }
    if (m.goAway) { this.finish('gemini time limit'); return }
    if (m.toolCall) return this.runTools(m.toolCall)
    const sc = m.serverContent
    if (!sc) return
    if (sc.inputTranscription?.text) this.userBuf += sc.inputTranscription.text
    if (sc.outputTranscription?.text) { this.flush('Caller', 'userBuf'); this.buddyBuf += sc.outputTranscription.text }
    for (const p of sc.modelTurn?.parts ?? []) {
      if (!p.inlineData?.data) continue
      this.flush('Caller', 'userBuf')
      const pcm24 = bytesPcm(Buffer.from(p.inlineData.data, 'base64'))
      this.sendTwilio({ event: 'media', streamSid: this.streamSid, media: { payload: pcmToMulaw(down3(pcm24)).toString('base64') } })
    }
    if (sc.interrupted) { this.sendTwilio({ event: 'clear', streamSid: this.streamSid }); this.flush('Buddy', 'buddyBuf') }
    if (sc.turnComplete) {
      this.flush('Buddy', 'buddyBuf')
      if (this.endAfterTurn) setTimeout(() => this.finish('finished'), 1500) // let the goodbye audio play out
    }
  }

  flush(who, buf) {
    const t = this[buf].trim(); this[buf] = ''
    if (t) { this.transcript.push(`${who}: ${t}`); this.log(who, t) }
  }

  runTools(tc) {
    const out = (tc.functionCalls ?? []).map(c => {
      let result = 'ok'
      if (c.name === 'take_message') {
        this.message = String(c.args?.message ?? ''); this.urgent = !!c.args?.urgent
        if (c.args?.caller_name) this.callerName = String(c.args.caller_name)
        result = 'Saved. Now say goodbye and call end_call.'
      } else if (c.name === 'end_call') { this.endAfterTurn = true; result = 'Ending after you finish speaking.' }
      else result = 'Not available on a call.'
      return { id: c.id, name: c.name, response: { result } }
    })
    this.sendGemini({ toolResponse: { functionResponses: out } })
  }

  finish(reason) {
    if (this.closed) return
    this.closed = true
    clearTimeout(this.timer)
    this.flush('Caller', 'userBuf'); this.flush('Buddy', 'buddyBuf')
    try { this.gemini.close() } catch {}
    try { this.twilio.close() } catch {}
    this.log('call ended', reason)
    this.onDone?.({ from: this.from, name: this.callerName, message: this.message, urgent: this.urgent, transcript: this.transcript, reason, seconds: Math.round((Date.now() - this.startedAt) / 1000) })
  }
}
