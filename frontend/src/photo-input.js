import { detectQuestionType, detectSubject, SUBJECTS } from './domain.js';
import { drawPhoto, isSupportedPhoto, PHOTO_INPUT_LIMIT, PHOTO_PIXEL_LIMIT, recognizePhoto, splitPhotoQuestion } from './ocr.js';

export const photoInputMarkup = `
  <div class="photo-entry" aria-label="사진으로 문제 입력">
    <button type="button" class="button secondary" data-photo-pick="camera">사진 찍기</button>
    <button type="button" class="button secondary" data-photo-pick="gallery">사진 선택</button>
    <input type="file" data-photo-file="camera" accept="image/*" capture="environment" hidden>
    <input type="file" data-photo-file="gallery" accept="image/jpeg,image/png,image/webp" hidden>
  </div>
  <dialog class="photo-dialog" aria-labelledby="photo-title">
    <h2 id="photo-title">사진에서 문제 읽기</h2>
    <p>문제 한 개가 보이게 맞춰 주세요. 사진은 브라우저에서 처리해요.</p>
    <div class="photo-columns">
      <div>
        <canvas class="photo-preview" role="img" aria-label="문제 사진 미리보기" hidden></canvas>
        <div class="photo-actions">
          <button type="button" class="button secondary" data-photo-control="rotate" disabled>90° 회전</button>
          <button type="button" class="button secondary" data-photo-pick="camera">다시 촬영</button>
          <button type="button" class="button secondary" data-photo-pick="gallery">다른 사진</button>
        </div>
        <details class="photo-crop">
          <summary>사진 가장자리 자르기</summary>
          <div class="photo-crop-grid">
            <label>왼쪽 <input type="range" data-photo-edge="left" min="0" max="45" value="0" disabled></label>
            <label>오른쪽 <input type="range" data-photo-edge="right" min="0" max="45" value="0" disabled></label>
            <label>위쪽 <input type="range" data-photo-edge="top" min="0" max="45" value="0" disabled></label>
            <label>아래쪽 <input type="range" data-photo-edge="bottom" min="0" max="45" value="0" disabled></label>
          </div>
        </details>
        <button type="button" class="button" data-photo-control="read" disabled>글자 추출</button>
      </div>
      <div>
        <label for="photo-text">인식 텍스트</label>
        <textarea id="photo-text" rows="8" placeholder="추출한 글자를 사진과 대조해 수정할 수 있어요."></textarea>
        <p class="helper">‘옳지 않은’, 숫자, 조문 번호와 선지 ①~⑤를 확인해 주세요.</p>
        <p data-photo-suggestion class="helper" hidden></p>
      </div>
    </div>
    <p role="status" data-photo-status></p>
    <div class="dialog-actions">
      <button type="button" class="button secondary" data-photo-control="close">닫기</button>
      <button type="button" class="button" data-photo-control="apply" disabled>입력란에 반영</button>
    </div>
  </dialog>`;

export function mountPhotoInput(host, { hasDraft, onApply }) {
  const dialog = host.querySelector('.photo-dialog');
  const preview = dialog.querySelector('canvas');
  const raw = dialog.querySelector('#photo-text');
  const status = dialog.querySelector('[data-photo-status]');
  const suggestion = dialog.querySelector('[data-photo-suggestion]');
  const control = name => dialog.querySelector(`[data-photo-control="${name}"]`);
  const edges = [...dialog.querySelectorAll('[data-photo-edge]')];
  let bitmap;
  let rotation = 0;
  let loadVersion = 0;
  let task;
  let confirmReplace = false;

  const crop = () => Object.fromEntries(edges.map(input => [input.dataset.photoEdge, Number(input.value)]));
  const render = () => { if (bitmap) drawPhoto(preview, bitmap, rotation, crop(), 850); };
  function setImageControls(enabled) {
    preview.hidden = !enabled;
    control('rotate').disabled = !enabled;
    control('read').disabled = !enabled;
    for (const edge of edges) edge.disabled = !enabled;
  }
  function cancelTask() {
    task?.abort();
    task = null;
    control('read').textContent = '글자 추출';
    raw.readOnly = false;
  }
  function resetPhoto() {
    loadVersion++;
    cancelTask();
    bitmap?.close();
    bitmap = null;
    rotation = 0;
    preview.width = preview.height = 1;
    for (const edge of edges) edge.value = '0';
    raw.value = '';
    status.textContent = '';
    suggestion.hidden = true;
    confirmReplace = false;
    control('apply').textContent = '입력란에 반영';
    control('apply').disabled = true;
    setImageControls(false);
  }
  function updateText() {
    confirmReplace = false;
    control('apply').textContent = '입력란에 반영';
    control('apply').disabled = !raw.value.trim();
    const parsed = splitPhotoQuestion(raw.value);
    const subject = parsed && detectSubject(raw.value);
    const type = parsed && detectQuestionType(parsed.stem);
    const typeLabel = type && (type === 'SELECT_INCORRECT' ? '틀린 설명 고르기' : '옳은 설명 고르기');
    suggestion.textContent = [subject && SUBJECTS[subject], typeLabel].filter(Boolean).join(' · ');
    suggestion.hidden = !suggestion.textContent;
  }
  async function select(file) {
    resetPhoto();
    if (!dialog.open) dialog.showModal();
    const current = loadVersion;
    status.textContent = '사진을 여는 중이에요.';
    try {
      if (file.size > PHOTO_INPUT_LIMIT) {
        status.textContent = '20MB 이하 사진을 선택해 주세요.';
        return;
      }
      if (!isSupportedPhoto(new Uint8Array(await file.slice(0, 12).arrayBuffer()))) {
        if (current === loadVersion) status.textContent = 'JPEG, PNG 또는 WebP 사진을 선택해 주세요.';
        return;
      }
      if (current !== loadVersion) return;
      const image = await createImageBitmap(file);
      if (current !== loadVersion) {
        image.close();
        return;
      }
      if (image.width * image.height > PHOTO_PIXEL_LIMIT) {
        image.close();
        status.textContent = '3,200만 픽셀 이하 사진을 선택해 주세요.';
        return;
      }
      bitmap = image;
      setImageControls(true);
      render();
      status.textContent = '사진을 확인한 뒤 글자 추출을 눌러 주세요.';
    } catch {
      if (current === loadVersion) status.textContent = '사진을 열지 못했어요. 다시 촬영하거나 다른 파일을 선택해 주세요.';
    }
  }
  function changeCrop() {
    render();
    if (task || raw.value.trim()) {
      cancelTask();
      raw.value = '';
      updateText();
      status.textContent = '사진 범위가 바뀌었어요. 글자를 다시 추출해 주세요.';
    }
  }
  async function read() {
    if (task) {
      cancelTask();
      updateText();
      status.textContent = '글자 추출을 취소했어요.';
      return;
    }
    if (!bitmap) return;
    const operation = new AbortController();
    task = operation;
    control('read').textContent = '추출 취소';
    control('apply').disabled = true;
    raw.readOnly = true;
    status.textContent = '사진에서 글자를 읽고 있어요.';
    try {
      const canvas = document.createElement('canvas');
      drawPhoto(canvas, bitmap, rotation, crop(), 2600);
      const text = await recognizePhoto(canvas, { signal: operation.signal });
      if (task !== operation) return;
      raw.value = text.trim();
      status.textContent = raw.value ? '인식한 글자를 사진과 대조해 주세요.' : '읽을 글자를 찾지 못했어요. 사진 범위를 조정해 보세요.';
    } catch {
      if (task === operation) status.textContent = '글자 추출을 완료하지 못했어요. 다시 시도하거나 직접 입력해 주세요.';
    } finally {
      if (task === operation) {
        cancelTask();
        updateText();
      }
    }
  }

  for (const button of host.querySelectorAll('[data-photo-pick]')) {
    button.onclick = () => host.querySelector(`[data-photo-file="${button.dataset.photoPick}"]`).click();
  }
  for (const input of host.querySelectorAll('[data-photo-file]')) {
    input.onchange = () => {
      const file = input.files[0];
      input.value = '';
      if (file) void select(file);
    };
  }
  control('close').onclick = () => dialog.close();
  dialog.onclose = resetPhoto;
  control('rotate').onclick = () => {
    rotation = (rotation + 90) % 360;
    for (const edge of edges) edge.value = '0';
    changeCrop();
  };
  for (const edge of edges) edge.oninput = changeCrop;
  control('read').onclick = () => void read();
  raw.oninput = updateText;
  control('apply').onclick = () => {
    const text = raw.value.trim();
    const parsed = splitPhotoQuestion(text);
    if (!parsed) {
      status.textContent = '본문과 선지 1~5가 한 문제씩 나뉘도록 원문을 고치거나 입력란에 직접 입력해 주세요.';
      raw.focus();
      return;
    }
    if (hasDraft() && !confirmReplace) {
      confirmReplace = true;
      status.textContent = '기존 본문과 선지를 교체해요. 제공 정답과 기준일은 다음 화면에서 다시 확인해 주세요.';
      control('apply').textContent = '본문과 선지 교체';
      return;
    }
    onApply(parsed, text);
  };
  return resetPhoto;
}
