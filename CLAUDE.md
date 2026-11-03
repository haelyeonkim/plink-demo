# Claude repository instructions

Read and follow [AGENTS.md](AGENTS.md) before starting any work in this repository. It is the shared source of project conventions, verification requirements, and the mandatory branch/review/merge workflow for both Claude and Codex.

For every change, including documentation:

- Check the working tree and current branch before editing. Preserve existing user changes.
- Create or use a dedicated task branch; never edit, commit, or push directly on `main`.
- Implement and verify the change, then commit and push the task branch and open a PR targeting `main`.
- Wait for a human **Approve** of the current changes and successful required checks.
- Merge only when the user explicitly authorizes merging that PR. Do not self-approve, bypass protection, or use auto-merge to skip review.

**Task branch → PR → human Approve → authorized merge** is mandatory. See [AGENTS.md](AGENTS.md) for the complete rules.
