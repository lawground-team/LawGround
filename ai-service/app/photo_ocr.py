"""Optional loopback OCR process for the LawGround local preview."""

import io
import os
import re
import secrets
from contextlib import asynccontextmanager
from pathlib import Path
from threading import Lock

from fastapi import FastAPI, HTTPException, Request, Response
from starlette.concurrency import run_in_threadpool


MAX_BYTES = 8 * 1024 * 1024
MAX_SIDE = 2600
CHOICE = re.compile(r"^([①②③④⑤]|[1-5](?![0-9]))[.)．）]?\s*")
RECOGNIZER = "korean_PP-OCRv5_mobile_rec"
DETECTOR = "PP-OCRv5_mobile_det"
OCR_LOCK = Lock()


def format_lines(texts, boxes, image_width: int) -> str:
    lines = [text.strip() for text in texts]
    if not lines:
        return ""
    left_margin = boxes[0][0] + image_width * 0.035
    markers = []
    for index, (text, box) in enumerate(zip(lines, boxes)):
        if index == 0 or box[0] > left_margin:
            continue
        match = CHOICE.match(text)
        if match:
            number = "①②③④⑤".index(match[1]) + 1 if match[1] in "①②③④⑤" else int(match[1])
            markers.append((index, number, match.end()))
    # Only reinterpret bare digits when the complete, ordered sequence was detected in the margin.
    if [number for _, number, _ in markers] == [1, 2, 3, 4, 5]:
        for index, number, end in markers:
            lines[index] = f"{number}. {lines[index][end:]}"
    return "\n".join(lines)


@asynccontextmanager
async def lifespan(app):
    token = os.environ.get("LAWGROUND_OCR_TOKEN", "")
    if not token:
        raise RuntimeError("LAWGROUND_OCR_TOKEN is required")
    os.environ["PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK"] = "True"
    from paddleocr import PaddleOCR

    models = Path(os.environ["LAWGROUND_OCR_MODEL_DIR"]).resolve(strict=True)
    for model in (DETECTOR, RECOGNIZER):
        for name in ("inference.json", "inference.pdiparams", "inference.yml"):
            if not (models / model / name).is_file():
                raise RuntimeError(f"Missing OCR model file: {model}/{name}")
    app.state.token = token
    app.state.ocr = PaddleOCR(
        text_detection_model_name=DETECTOR,
        text_detection_model_dir=str(models / DETECTOR),
        text_recognition_model_name=RECOGNIZER,
        text_recognition_model_dir=str(models / RECOGNIZER),
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        device="cpu",
        cpu_threads=4,
        enable_mkldnn=False,
    )
    yield


app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


def recognize(ocr, image_bytes) -> str:
    import cv2
    import numpy as np
    from PIL import Image

    if not OCR_LOCK.acquire(blocking=False):
        raise HTTPException(429, "OCR busy")
    try:
        try:
            with Image.open(io.BytesIO(image_bytes)) as image:
                width, height = image.size
                if image.format != "PNG" or max(width, height) > MAX_SIDE:
                    raise HTTPException(422, "Invalid image format or dimensions")
                rgb = np.asarray(image.convert("RGB"))
        except (OSError, ValueError, Image.DecompressionBombError) as exc:
            raise HTTPException(422, "Invalid PNG image") from exc

        pixels = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
        if width < 750:
            scale = min(3, 780 / width, MAX_SIDE / max(width, height))
            if scale > 1:
                pixels = cv2.resize(pixels, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
        result = next(iter(ocr.predict(pixels)))
        return format_lines(result["rec_texts"], result["rec_boxes"], pixels.shape[1])[:22_000]
    finally:
        OCR_LOCK.release()


@app.post("/recognize")
async def recognize_photo(request: Request, response: Response):
    provided = request.headers.get("x-ocr-token", "").encode("utf8")
    if not secrets.compare_digest(provided, request.app.state.token.encode("utf8")):
        raise HTTPException(401, "Invalid OCR token")
    if request.headers.get("content-type") != "image/png":
        raise HTTPException(415, "PNG input required")
    image = bytearray()
    async for chunk in request.stream():
        if len(image) + len(chunk) > MAX_BYTES:
            raise HTTPException(413, "Image too large")
        image.extend(chunk)
    text = await run_in_threadpool(recognize, request.app.state.ocr, image)
    response.headers["Cache-Control"] = "no-store"
    return {"text": text}
