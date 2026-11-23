# 브라우저 OCR 정적 자산

LawGround는 사진 입력을 선택한 뒤 이 자산을 같은 출처에서 로드한다. Tesseract.js의 기본 LSTM 코어를 사용한다. 한국어 인쇄문을 읽기 위한 경량 모델을 포함한다. Gradle이 다른 프런트 정적 파일과 함께 JAR에 패키징한다.

| 파일 | 원본 npm 패키지와 경로 |
|---|---|
| `tesseract.esm.min.js`, `worker.min.js`, `worker.min.js.LICENSE.txt` | `tesseract.js@7.0.0`의 `dist/` |
| `tesseract-core-lstm.wasm.js` | `tesseract.js-core@7.0.0` |
| `kor.traineddata.gz` | `@tesseract.js-data/kor@1.0.0`의 `4.0.0_best_int/` |

[Tesseract.js 라이선스](https://github.com/naptha/tesseract.js/blob/v7.0.0/LICENSE.md) · [코어 라이선스](https://github.com/naptha/tesseract.js-core/blob/v7.0.0/LICENSE) · [한국어 모델 라이선스](https://github.com/tesseract-ocr/tessdata_best/blob/main/LICENSE)
