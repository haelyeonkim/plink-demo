---
name: ship-pr
description: plink-demo 변경을 작업 브랜치에서 PR까지 올린다 — 브랜치 생성·체크아웃, 주요 변경별 커밋, push, PR 초안을 사용자에게 보여주고 확인받은 뒤 PR 생성. "PR 올려줘", "브랜치 따서 커밋하고 PR", "ship", "/ship-pr" 같은 요청에 사용.
argument-hint: "[브랜치 이름] [--base <대상 브랜치>]"
---

# ship-pr: 브랜치 → 커밋 → push → PR 초안 확인 → PR

이 저장소의 필수 순서 **작업 브랜치 → 수정·검증 → PR → merge**(`AGENTS.md`) 중 PR 생성까지를 수행한다.
merge는 이 스킬에 포함하지 않는다. 사용자가 따로 요청할 때만 한다.

인자: `$ARGUMENTS` — 첫 번째 값은 브랜치 이름(선택), `--base <브랜치>`는 PR 대상 브랜치(기본 `main`).

## 절대 규칙

- `main`에서 직접 수정·커밋·push하지 않는다. force-push하지 않는다. 브랜치 보호를 우회하지 않는다.
- 사용자의 기존 변경을 버리지 않는다. `reset --hard`, `checkout -- .`, `clean`, `stash drop`을 쓰지 않는다.
- 이번 작업과 상관없는 변경, 비밀값(`.env`, 키, 토큰)은 커밋하지 않는다.
- **PR 초안을 사용자에게 보여주고 확인받기 전에는 `gh pr create`를 실행하지 않는다.**

## 1. 상태 확인

```bash
git status --short
git branch --show-current
git fetch origin
```

- 변경 파일 중 이번 작업에 속하는 것과 아닌 것을 나눠 둔다. 애매하면 사용자에게 묻는다.
- 아무 변경도 없고 올릴 커밋도 없으면 멈추고 알린다.

## 2. 브랜치 생성·체크아웃

상황별로 처리한다.

| 현재 상태 | 할 일 |
|---|---|
| 이미 이 작업의 브랜치에 있음 | 그대로 사용 |
| `main`에 있고 변경이 이번 작업 것뿐 | `git switch -c <브랜치>` — 변경이 그대로 따라온다. 이후 `origin/main`보다 뒤처졌으면 커밋 후 `git rebase origin/main` |
| `main`이고 변경 없음 | `git switch main && git pull --ff-only origin main && git switch -c <브랜치>` |
| 다른 작업 브랜치에 관련 없는 변경이 있음 | 그 브랜치를 건드리지 말고 worktree 사용: `git worktree add -b <브랜치> ../plink-demo-<짧은이름> origin/<base>` 후 그 디렉터리에서 작업 |

브랜치 이름: 인자로 받았으면 그대로, 없으면 변경 내용으로 정한다. 형식은 `종류/짧은-설명`
(`feat/…`, `fix/…`, `docs/…`, `refactor/…`, `chore/…`). 같은 이름이 로컬·원격에 있으면 다른 이름을 고른다.

## 3. 검증

PR 전에 변경 범위에 맞는 검증을 돌린다(`AGENTS.md`).

- Java 백엔드(`src/`, `pom.xml`) 변경: `./mvnw test`
- 프론트엔드(`frontend/`) 변경: `cd frontend && npm run build`
- 문서만 변경: 링크·경로·명령이 실제와 맞는지 확인

실패하면 커밋·PR로 넘어가지 말고 결과를 보여주고 멈춘다. 결과(통과 수, 건너뛴 것)는 PR 본문에 쓰므로 기록해 둔다.

## 4. 주요 변경별 커밋

1. `git diff`(필요하면 `git diff --staged`)로 변경 전체를 읽고, **논리적 단위**로 묶는다.
   예: 스키마 마이그레이션 / 백엔드 로직+테스트 / 프론트엔드 UI / 문서. 한 기능의 코드와 그 테스트는 같은 커밋에 둔다.
   각 커밋이 단독으로 빌드가 깨지지 않게 묶는다.
2. 단위마다 해당 경로만 stage한다: `git add <경로…>`. 한 파일에 여러 단위가 섞여 있으면 `git apply --cached`로 hunk 단위 패치를 stage한다(`git add -p` 같은 대화형 명령은 쓸 수 없다).
3. 커밋 전 `git diff --staged --stat`으로 범위를 확인한다.
4. 커밋 메시지 규칙(최근 커밋 스타일):
   - 제목: 짧은 영어 명령문 문장, 마침표 없음. 예: `Let a schedule run for a year or two, and show the event as on while it does`
   - 동작·보안이 바뀌면 본문에 이유를 쓴다.
   - 세션의 attribution 지시가 있으면 메시지 끝에 붙인다.
   - 메시지는 HEREDOC으로 넘긴다:
     ```bash
     git commit -F - <<'EOF'
     <제목>

     <본문>
     EOF
     ```
5. 모두 커밋한 뒤 `git status --short`로 이번 작업 변경이 남지 않았는지, 관련 없는 변경은 그대로인지 확인한다.

## 5. Push

```bash
git push -u origin <브랜치>
```

권한 오류면 인증 상태(`gh auth status`)를 알려주고 멈춘다. 거부되면 force-push로 해결하지 않는다.

## 6. PR 초안 작성 → 사용자 확인

`git log origin/<base>..HEAD`와 `git diff origin/<base>...HEAD --stat`을 근거로 초안을 쓴다.

- **제목**: 영어 명령문 한 문장(커밋이 하나면 그 제목).
- **본문**: 한국어, 아래 틀. 해당 없는 섹션은 "없음"으로 둔다.

```markdown
## 왜
<이 변경이 필요한 이유>

## 변경 내용
- <사용자에게 보이는 동작 위주로>

## 스키마 변경
<Flyway 마이그레이션 파일명 또는 "없음">

## 환경 변수·설정 변경
<.env, application*.yml 변경 또는 "없음">

## 검증
- `./mvnw test` — <결과>
- `cd frontend && npm run build` — <결과>
- <수동 확인, UI 변경이면 스크린샷>

<세션의 attribution 지시가 있으면 PR 본문 끝 줄>
```

초안을 사용자에게 **그대로** 보여준다: 대상 브랜치(base) ← 작업 브랜치(head), 제목, 본문 전문, 포함된 커밋 목록.
그리고 `AskUserQuestion`으로 묻는다: "이대로 올리기 / Draft PR로 올리기 / 수정 후 다시 보기".
수정 요청이 오면 반영해 다시 보여준다. 승인 전에는 다음 단계로 가지 않는다.

## 7. PR 생성

승인된 내용 그대로 만든다. 본문은 파일로 넘겨 줄바꿈·백틱이 깨지지 않게 한다.

```bash
gh pr create --base <base> --head <브랜치> --title "<제목>" --body-file <임시 파일>
# Draft를 고른 경우 --draft 추가
```

임시 파일은 세션 scratchpad 디렉터리에 만든다.

## 8. 마무리 보고

- PR URL, 커밋 목록, 검증 결과를 짧게 알린다.
- 남겨 둔 관련 없는 변경이 있으면 그 사실을 알린다.
- `main` 보호 설정상 merge하려면 리뷰 대화가 모두 resolve되어 있어야 한다는 점을 필요하면 알린다. merge는 사용자가 요청할 때만 한다.
