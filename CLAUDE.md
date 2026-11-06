# Claude repository instructions

Read and follow [AGENTS.md](AGENTS.md) before starting any work in this repository. It is the shared source of project conventions, verification requirements, and the mandatory branch/review/merge workflow for both Claude and Codex.

For every change, including documentation:

- Check the working tree and current branch before editing. Preserve existing user changes.
- Create or use a dedicated task branch; never edit, commit, or push directly on `main`.
- Implement and verify the change, then commit and push the task branch and open a PR targeting the intended destination branch.
- Human **Approve** is not required, including for PRs targeting `main`. After verification and required checks pass, agents may merge PRs as part of the authorized task without separate merge authorization.
- Changes to `main` must go through a PR. Never push directly to `main` or bypass protection.

**Task branch → changes and verification → PR → merge** is mandatory for changes to `main`. See [AGENTS.md](AGENTS.md) for the complete rules.
