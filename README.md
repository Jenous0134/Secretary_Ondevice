# Secretary Ondevice (Sec_Ond)

개인 사업의 문서, 일정, 업무를 통합 관리하고 등록 자료에 관해 질문할 수 있는 Android 온디바이스 AI 비서입니다.

Google AI Edge Gallery의 LiteRT-LM 모델 실행·관리 기반을 활용하며, 사업 데이터와 문서 검색은 기기 내부에서 처리합니다.

## 주요 기능

- 사업 상태, 기간, 지원기관, 담당자, 참여자 및 메모 관리
- PDF, DOCX, HWPX, TXT 문서 텍스트 추출과 로컬 검색
- HWP 원본 등록 및 보관
- 일정과 D-7, D-3, D-1 로컬 알림
- 업무, 담당자, 우선순위, 산출물, 체크리스트, 진행률 관리
- 사업별 KPI 및 목표 관리
- 등록 문서를 근거로 하는 로컬 RAG 질의응답
- 모델이 없을 때도 동작하는 일정·업무·원문 검색 모드
- 근거 문서와 문단 표시
- 데이터베이스와 등록 문서 ZIP 백업·복원
- 온디바이스 모델 다운로드와 직접 가져오기

## 앱 정보

- 표시 이름: `Secretary Ondevice`
- 약칭: `Sec_Ond`
- Application ID: `com.jenous.secretary.ondevice`
- 최소 Android 버전: Android 12 (API 31)

## 빌드

Android Studio에서 `Android/src` 폴더를 프로젝트로 엽니다. 또는 다음 명령을 사용합니다.

```powershell
cd Android/src
./gradlew assembleDebug
```

Hugging Face 모델 다운로드를 사용하려면 [DEVELOPMENT.md](DEVELOPMENT.md)의 OAuth 설정을 적용해야 합니다. 로컬 모델 파일 가져오기는 별도의 OAuth 설정 없이 사용할 수 있습니다.

## 문서 지원 범위

| 형식 | 지원 방식 |
|---|---|
| PDF | 텍스트 추출 및 검색 |
| DOCX | 문단 텍스트 추출 및 검색 |
| HWPX | XML 기반 텍스트 추출 및 검색 |
| TXT/Markdown | 직접 읽기 및 검색 |
| HWP | 원본 보관; PDF 또는 HWPX 변환 권장 |

스캔 PDF는 OCR을 거치지 않으면 텍스트 검색이 제한됩니다.

## 라이선스

Apache License 2.0. Google AI Edge Gallery에서 유래한 소스의 기존 저작권 및 라이선스 고지를 유지합니다.
