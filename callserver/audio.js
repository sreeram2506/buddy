// Phone audio is 8 kHz mu-law; Gemini Live takes 16 kHz PCM and returns 24 kHz PCM. These convert between them.
const BIAS = 0x84, CLIP = 32635

const decodeTable = new Int16Array(256)
for (let i = 0; i < 256; i++) {
  const u = ~i & 0xff
  const sign = u & 0x80, exp = (u >> 4) & 7, mant = u & 15
  const v = (((mant << 3) + BIAS) << exp) - BIAS
  decodeTable[i] = sign ? -v : v
}

export function mulawToPcm(buf) {
  const out = new Int16Array(buf.length)
  for (let i = 0; i < buf.length; i++) out[i] = decodeTable[buf[i]]
  return out
}

export function pcmToMulaw(pcm) {
  const out = Buffer.alloc(pcm.length)
  for (let i = 0; i < pcm.length; i++) {
    let s = pcm[i], sign = 0
    if (s < 0) { s = -s; sign = 0x80 }
    if (s > CLIP) s = CLIP
    s += BIAS
    let exp = 7
    for (let mask = 0x4000; (s & mask) === 0 && exp > 0; mask >>= 1) exp--
    out[i] = ~(sign | (exp << 4) | ((s >> (exp + 3)) & 15)) & 0xff
  }
  return out
}

// 8 kHz -> 16 kHz by linear interpolation.
export function up2(pcm) {
  const out = new Int16Array(pcm.length * 2)
  for (let i = 0; i < pcm.length; i++) {
    const a = pcm[i], b = i + 1 < pcm.length ? pcm[i + 1] : a
    out[2 * i] = a
    out[2 * i + 1] = (a + b) >> 1
  }
  return out
}

// 24 kHz -> 8 kHz by averaging each group of three samples (a cheap low-pass before dropping samples).
export function down3(pcm) {
  const n = Math.floor(pcm.length / 3)
  const out = new Int16Array(n)
  for (let i = 0; i < n; i++) out[i] = (pcm[3 * i] + pcm[3 * i + 1] + pcm[3 * i + 2]) / 3
  return out
}

export const pcmBytes = (pcm) => Buffer.from(pcm.buffer, pcm.byteOffset, pcm.byteLength)
export const bytesPcm = (buf) => new Int16Array(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.length - (buf.length % 2)))
