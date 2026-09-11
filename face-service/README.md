# plink-face

입장권 서비스의 **얼굴 특징 추출·라이브니스** 전담 서비스입니다.

모델과 벡터 연산을 티켓 서비스 밖에 두는 것이 이 서비스의 존재 이유입니다. 티켓 서비스가 침해되어도 생체 데이터에 닿지 못하고, 이 서비스는 **이미지를 저장하지 않고 DB도 없으므로** 침해되어도 정지 상태의 데이터가 없습니다.

## 실행

```bash
bash fetch-models.sh          # 모델 내려받기 (약 38MB, 저장소에 커밋하지 않음)
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
PORT=10001 ./run.sh           # 기본 127.0.0.1:10001
```

티켓 서비스에서는 `.env`에 다음을 넣으면 연결됩니다.

```properties
plink.ticket.face.service-url=http://127.0.0.1:10001
plink.ticket.face.service-token=<FACE_SERVICE_TOKEN와 동일한 값>
```

## API

| 엔드포인트 | 응답 |
|---|---|
| `GET /health` | 백엔드 종류, 알고리즘 버전, **라이브니스 설정 여부**, 인증 필요 여부 |
| `POST /embed` | `{vector, quality, algoVersion}` 또는 `{vector: [], reason}` |
| `POST /liveness` | `{score, basis, configured}` |

요청 본문은 `{"frames": ["<base64 JPEG>", ...], "challenge": "..."}` 이며 프레임은 최대 5장, 장당 400KB입니다. 여러 장을 보내면 **품질 점수가 가장 높은 프레임**을 골라 사용합니다 — 사람에게 가만히 있으라고 하는 것보다 훨씬 싸고, 등록 품질이 이후 인식 정확도의 대부분을 결정합니다.

## 모델

기본값은 **OpenCV Zoo의 YuNet(검출) + SFace(인식)** 입니다. 둘 다 Apache-2.0이라 상업 배포에 쓸 수 있습니다. 다른 선택지도 설정으로 바꿀 수 있습니다.

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `FACE_BACKEND` | `opencv` | `opencv` 또는 `onnx`(ArcFace 계열 일반 ONNX) |
| `FACE_RECOGNITION_MODEL` | `models/face_recognition_sface.onnx` | 인식 모델 경로 |
| `FACE_DETECTION_MODEL` | `models/face_detection_yunet.onnx` | 검출 모델 경로 |
| `FACE_LIVENESS_MODE` | `disabled` | `disabled` 또는 `onnx` |
| `FACE_LIVENESS_MODEL` | — | 패시브 안티스푸핑 ONNX 경로 |
| `FACE_SERVICE_TOKEN` | — | 설정하면 `Authorization: Bearer` 필수 |
| `FACE_MIN_PIXELS` | `112` | 이보다 작은 얼굴은 거절 |
| `FACE_MIN_DETECTION_SCORE` | `0.85` | 검출 신뢰도 하한 |

**InsightFace(ArcFace, `buffalo_l`)** 는 정확도가 가장 좋지만 **사전학습 가중치가 비상업 연구용**입니다. 유료 서비스에 쓰려면 라이선스를 먼저 확인하세요. 라이선스는 바뀔 수 있으니 도입 전에 직접 확인하는 것이 안전합니다.

## 라이브니스 — 솔직한 한계

**인증(iBeta 등)을 받은 오픈 라이브니스 모델은 없습니다.** `FACE_LIVENESS_MODE=disabled`(기본)이면 모든 캡처가 통과하며, 서비스는 이를 숨기지 않습니다 — 기동 시 경고하고, `/health`의 `livenessConfigured: false`와 응답의 `basis: "not-configured"`로 매번 알립니다.

이 상태로 운영한다면 **유인 게이트가 전제**입니다. 스태프가 보고 있는 레인에서 사진을 들이대는 행위는 그 자체로 눈에 띕니다. 무인 레인을 둘 계획이라면 상용 PAD 단말이 필요합니다.

`onnx` 모드에서는 패시브 모델 점수에 더해, 프레임 간 움직임이 사실상 0이면 점수를 낮춥니다 — 가장 게으른 공격인 "정지 사진 들이대기"에 대한 최소한의 방어입니다.

## 운영 시 지킬 것

- **localhost에만 바인딩**하세요. 티켓 서비스가 유일한 클라이언트입니다. 외부에 노출해야 한다면 `FACE_SERVICE_TOKEN`을 반드시 설정하세요.
- 이 서비스는 프레임을 **로그에도 남기지 않습니다**. 이 성질을 깨는 로깅을 추가하지 마세요.
- 모델 파일은 저장소에 커밋하지 않습니다. 라이선스는 배포 주체의 책임입니다.
