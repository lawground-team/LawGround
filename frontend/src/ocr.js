import { splitPastedQuestion } from './domain.js';

const choiceMarks = '①②③④⑤';
export const PHOTO_INPUT_LIMIT = 20 * 1024 * 1024;
export const PHOTO_PIXEL_LIMIT = 32_000_000;
export const OCR_TIMEOUT = 45_000;
const OCR_REQUEST_LIMIT = 8 * 1024 * 1024;

export function isSupportedPhoto(bytes) {
  const prefix = (...values) => values.every((value, index) => bytes[index] === value);
  return prefix(0xff, 0xd8, 0xff) ||
    prefix(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ||
    (prefix(0x52, 0x49, 0x46, 0x46) && [0x57, 0x45, 0x42, 0x50].every((value, index) => bytes[index + 8] === value));
}

function inspectPhotoLines(raw) {
  const lines = raw.replace(/\r\n?/g, '\n').split('\n');
  const firstLine = lines.findIndex(line => line.trim());
  const markers = [];
  let secondQuestion = false;
  const normalized = lines.map((line, index) => {
    if (index === firstLine) return line;
    const marker = /^\s*(?:([①②③④⑤])\s*|[(（]\s*([1-5])\s*[)）]\s*|([1-5])\s*[)）.．]\s+)/.exec(line);
    if (!marker) {
      if (markers.length && /^\s*(?:문\s*)?\d{1,3}\s*[.)번]\s+.*[?？]\s*$/.test(line)) secondQuestion = true;
      return line;
    }
    const number = marker[1] ? choiceMarks.indexOf(marker[1]) + 1 : Number(marker[2] || marker[3]);
    markers.push({ index, number });
    return choiceMarks[number - 1] + ' ' + line.slice(marker[0].length);
  });
  return { lines, markers, normalized, secondQuestion };
}

export function splitPhotoQuestion(raw) {
  const { markers, normalized, secondQuestion } = inspectPhotoLines(raw);
  if (secondQuestion || markers.map(mark => mark.number).join(',') !== '1,2,3,4,5') return null;
  if (normalized.join('\n').match(/[①②③④⑤]/g)?.length !== 5) return null;
  const parsed = splitPastedQuestion(normalized.join('\n'));
  if (!parsed?.stem || parsed.choices.some(text => !text)) return null;
  if ([...parsed.stem].length > 10000 || parsed.choices.some(text => [...text].length > 2000)) return null;
  return parsed;
}

export function missingPhotoChoice(raw) {
  const { lines, markers, secondQuestion } = inspectPhotoLines(raw);
  const expected = [1, 2, 3, 4, 5];
  if (secondQuestion || markers.length !== 4) return null;
  const found = markers.map(mark => mark.number);
  const number = expected.find(choice => !found.includes(choice));
  if (expected.filter(choice => choice !== number).some((choice, index) => choice !== found[index])) return null;

  const before = markers.find(mark => mark.number === number - 1);
  const after = markers.find(mark => mark.number === number + 1);
  if (!before || !after) return { number };

  let candidate;
  for (let index = before.index + 1; index < after.index; index++) {
    const token = lines[index].trim();
    // Short, isolated OCR noise followed by a text line is reviewable as a marker.
    if (!/^[\x21-\x7e]{1,3}$/.test(token) || !/[^A-Za-z]/.test(token) || /\d/.test(token)) continue;
    const next = lines.slice(index + 1, after.index).find(line => line.trim())?.trim();
    if (!/[가-힣]/.test(next ?? '')) continue;
    if (candidate) return { number };
    candidate = { index, next };
  }
  if (!candidate) return { number };

  const { index, next } = candidate;
  lines[index] = choiceMarks[number - 1];
  const repaired = lines.join('\n');
  return splitPhotoQuestion(repaired) ? { number, next, repaired } : { number };
}

export function drawPhoto(canvas, image, rotation, edges, maxSide) {
  const swapped = rotation % 180 !== 0;
  const width = swapped ? image.height : image.width;
  const height = swapped ? image.width : image.height;
  const { left, top, right, bottom } = edges;
  const cropWidth = width * (100 - left - right) / 100;
  const cropHeight = height * (100 - top - bottom) / 100;
  const scale = Math.min(1, maxSide / Math.max(cropWidth, cropHeight));
  canvas.width = Math.max(1, Math.round(cropWidth * scale));
  canvas.height = Math.max(1, Math.round(cropHeight * scale));
  const context = canvas.getContext('2d', { alpha: false });
  context.fillStyle = '#fff';
  context.fillRect(0, 0, canvas.width, canvas.height);
  context.scale(canvas.width / cropWidth, canvas.height / cropHeight);
  context.translate(width * (.5 - left / 100), height * (.5 - top / 100));
  context.rotate(rotation * Math.PI / 180);
  context.drawImage(image, -image.width / 2, -image.height / 2);
}

export async function recognizePhotoOnServer(canvas, { signal, api }) {
  const png = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
  signal.throwIfAborted();
  if (!png || png.size > OCR_REQUEST_LIMIT) {
    const error = new Error('OCR image too large');
    error.status = 413;
    throw error;
  }
  const csrf = await api.get('/auth/csrf', { signal });
  const result = await api.request('/photo-ocr', {
    method: 'POST',
    rawBody: png,
    headers: { 'Content-Type': 'image/png', [csrf.headerName]: csrf.token },
    signal,
    timeoutMs: OCR_TIMEOUT,
  });
  if (typeof result?.text !== 'string') throw new Error('Invalid OCR response');
  return result.text;
}

export async function recognizePhoto(canvas, { signal }) {
  let worker;
  let timer;
  let abort;
  const cancelled = new Promise((_, reject) => {
    abort = () => reject(new DOMException('OCR cancelled', 'AbortError'));
    signal.addEventListener('abort', abort, { once: true });
    timer = setTimeout(() => reject(new Error('OCR timeout')), OCR_TIMEOUT);
  });
  try {
    const work = async () => {
      signal.throwIfAborted();
      const { default: tesseract } = await import('./ocr-assets/tesseract.esm.min.js');
      signal.throwIfAborted();
      const assets = new URL('./ocr-assets/', import.meta.url);
      const initializing = tesseract.createWorker('kor', 1, {
        workerPath: new URL('worker.min.js', assets).href,
        corePath: new URL('tesseract-core-lstm.wasm.js', assets).href,
        langPath: assets.href.replace(/\/$/, ''),
        workerBlobURL: false,
        errorHandler: () => {},
      });
      worker = await initializing;
      if (signal.aborted || !timer) {
        await worker.terminate();
        worker = null;
        return '';
      }
      const { data } = await worker.recognize(canvas);
      return data.text;
    };
    return await Promise.race([work(), cancelled]);
  } finally {
    clearTimeout(timer);
    timer = null;
    signal.removeEventListener('abort', abort);
    if (worker) await worker.terminate();
  }
}
