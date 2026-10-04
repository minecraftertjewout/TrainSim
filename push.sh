#!/bin/bash
set -e
set -o pipefail

# ---------------------------------------------------------------
# Settings
# ---------------------------------------------------------------
REMOTE_NAME="origin"
REMOTE_URL="git@github.com:minecraftertjewout/TrainSim.git"
BRANCH="main"

# ---------------------------------------------------------------
# Colors (disabled automatically when output isn't a terminal)
# ---------------------------------------------------------------
if [ -t 1 ]; then
    RESET=$'\033[0m'
    BOLD=$'\033[1m'
    RED=$'\033[1;31m'
    GREEN=$'\033[1;32m'
    YELLOW=$'\033[1;33m'
    BLUE=$'\033[1;34m'
    MAGENTA=$'\033[1;35m'
    CYAN=$'\033[1;36m'
else
    RESET="" BOLD="" RED="" GREEN="" YELLOW="" BLUE="" MAGENTA="" CYAN=""
fi

info() { echo "${BLUE}[INFO]${RESET} $*"; }
ok()   { echo "${GREEN}[ OK ]${RESET} $*"; }
warn() { echo "${YELLOW}[WARN]${RESET} $*"; }
fail() { echo "${RED}[FAIL]${RESET} $*" >&2; }
step() { echo "${CYAN}[STEP]${RESET} ${BOLD}$*${RESET}"; }

# Prefix git's own output: "hint:" lines get [HINT], everything else [GIT ]
format_git() {
    local line rest
    while IFS= read -r line; do
        case "$line" in
            hint:*)
                rest="${line#hint:}"
                echo "${YELLOW}[HINT]${RESET} ${rest# }"
                ;;
            *)
                echo "${BLUE}[GIT ]${RESET} $line"
                ;;
        esac
    done
}

# ---------------------------------------------------------------
# Functions
# ---------------------------------------------------------------

# Make sure the remote exists and points to the right URL
setup_remote() {
    local current_url
    if git remote get-url "$REMOTE_NAME" >/dev/null 2>&1; then
        current_url=$(git remote get-url "$REMOTE_NAME")
        if [ "$current_url" != "$REMOTE_URL" ]; then
            warn "Remote '$REMOTE_NAME' points elsewhere, updating to $REMOTE_URL"
            git remote set-url "$REMOTE_NAME" "$REMOTE_URL"
            ok "Remote updated"
        else
            ok "Remote '$REMOTE_NAME' is correct"
        fi
    else
        info "Adding remote '$REMOTE_NAME' -> $REMOTE_URL"
        git remote add "$REMOTE_NAME" "$REMOTE_URL"
        ok "Remote added"
    fi
}

# Make sure we are on the main branch (renames master -> main)
ensure_main_branch() {
    local current
    current=$(git symbolic-ref --short HEAD 2>/dev/null || true)

    if [ -z "$current" ]; then
        fail "Detached HEAD, check out '$BRANCH' first."
        exit 1
    elif [ "$current" = "$BRANCH" ]; then
        ok "Already on '$BRANCH'"
    elif [ "$current" = "master" ]; then
        warn "On 'master', renaming to '$BRANCH'"
        git branch -M "$BRANCH"
        ok "Branch renamed to '$BRANCH'"
    else
        fail "On branch '$current', not '$BRANCH'. Switch with: git checkout $BRANCH"
        exit 1
    fi
}

# Stage EVERYTHING: new, modified and deleted files, hidden files included
stage_everything() {
    git add -A 2>&1 | format_git
}

# Print every staged file on its own line, with its status
list_staged_files() {
    local status path count=0
    while IFS=$'\t' read -r status path; do
        case "${status:0:1}" in
            A) echo "${GREEN}[ NEW]${RESET} $path" ;;
            M) echo "${YELLOW}[EDIT]${RESET} $path" ;;
            D) echo "${RED}[ DEL]${RESET} $path" ;;
            T) echo "${MAGENTA}[TYPE]${RESET} $path" ;;
            *) echo "${BLUE}[ ?? ]${RESET} $path" ;;
        esac
        count=$((count + 1))
    done < <(git -c core.quotepath=false diff --cached --name-status --no-renames)
    info "$count file(s) staged"
}

# Double check that no file was left behind (untracked or unstaged)
verify_nothing_missed() {
    local leftover
    leftover=$(git -c core.quotepath=false status --porcelain -uall | grep -E '^(\?\?|.[^ ])' || true)
    if [ -n "$leftover" ]; then
        fail "These files were NOT staged:"
        echo "$leftover" | while IFS= read -r line; do
            echo "${RED}[MISS]${RESET} $line" >&2
        done
        exit 1
    fi
    ok "Verified: every changed file is staged"
}

# ---------------------------------------------------------------
# Main
# ---------------------------------------------------------------

# Must be run inside a git repository; always work from the repo root
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    fail "Not inside a git repository."
    exit 1
fi
cd "$(git rev-parse --show-toplevel)"

step "Checking remote"
setup_remote

step "Checking branch"
ensure_main_branch

step "Staging every file"
stage_everything

step "Verifying"
verify_nothing_missed

step "Files to commit"
nothing_staged=0
if git diff --cached --quiet; then
    warn "Nothing to commit, creating an empty commit."
    nothing_staged=1
else
    list_staged_files
fi

# Ask for a commit message (must not be empty)
commit_msg=""
while [ -z "$commit_msg" ]; do
    printf "%s[ASK ]%s Commit message: " "$MAGENTA" "$RESET"
    read -r commit_msg || { echo; fail "No input received."; exit 1; }
done

step "Committing"
commit_args=(-q -m "$commit_msg")
if [ "$nothing_staged" -eq 1 ]; then
    commit_args+=(--allow-empty)
fi
git commit "${commit_args[@]}" 2>&1 | format_git
ok "Committed: $commit_msg"

force_push=""
printf "%s[ASK ]%s Force push? (N/y): " "$MAGENTA" "$RESET"
read -r force_push || { echo; fail "No input received."; exit 1; }

push_args=("$REMOTE_NAME" "HEAD:$BRANCH" --progress)
if [[ "$force_push" = "y" || "$force_push" = "Y" ]]; then
    push_args+=(--force)
    step "Force pushing to $REMOTE_NAME/$BRANCH"
else
    step "Pushing to $REMOTE_NAME/$BRANCH"
fi
# Not piped through format_git: git's progress bar uses carriage returns,
# which a line-buffered pipe would swallow until the transfer finishes.
git push "${push_args[@]}"
ok "Done!"