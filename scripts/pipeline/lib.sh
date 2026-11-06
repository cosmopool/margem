# Shared helpers for the pipeline scripts. Sourced, never run.
#
# Every script runs from the repository it is called in (the main checkout or a
# candidate worktree), but the feature folder is always read from the main checkout:
# a worktree only has the .work/ files of its last checkpoint commit.

ROOT="$(git rev-parse --show-toplevel)"
MAIN_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
PIPELINE_SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# Configuration always comes from the main checkout, including in candidate worktrees.
export PIPELINE_CONFIG="${PIPELINE_CONFIG:-$MAIN_ROOT/.claude/pipeline/config.json}"
export PYTHONPATH="$PIPELINE_SCRIPTS${PYTHONPATH:+:$PYTHONPATH}"
config() { python3 "$PIPELINE_SCRIPTS/pipeline_config.py" "$@"; }
config get version >/dev/null
mapfile -t NON_CODE < <(config list paths.non_code | sed 's/^/:(exclude)/')
COMMIT_TYPES="$(config list git.commit_types | paste -sd '|')"

CHECKPOINT_PREFIX='chore(checkpoint): '

die() { echo "$(basename "$0"): $1" >&2; exit "${2:-2}"; }

work_dir() { echo "$MAIN_ROOT/.work/$1"; }

# Reads one top-level key from the feature's state.json, printing it as JSON.
state_get() { # slug key
  python3 - "$(work_dir "$1")/state.json" "$2" <<'PY'
import json, sys
state = json.load(open(sys.argv[1]))
print(json.dumps(state.get(sys.argv[2])))
PY
}

feature_base() { # slug
  local base
  base="$(state_get "$1" base | tr -d '"')"
  [ -n "$base" ] && [ "$base" != null ] || die "no base in $(work_dir "$1")/state.json"
  echo "$base"
}

# Takes the feature folder and the pipeline bundle back out of the index. Pathspec
# excludes cannot do this at `git add` time: naming an ignored path fails the add.
unstage_non_code() { git reset -q -- "${NON_CODE[@]#:(exclude)}" >/dev/null 2>&1 || true; }

# Makes untracked files visible to git diff without staging their content.
intent_to_add() { git add -A -N >/dev/null && unstage_non_code; }

# Feature tests and package boundaries are defined by the project configuration.
feature_tests() { # base
  intent_to_add
  local paths=()
  mapfile -t paths < <(config pathspec feature_tests)
  git diff --name-only --diff-filter=d "$1" -- "${paths[@]}"
}

run_tests() { # [flags...] -- files...
  local flags=() status=0
  while [ $# -gt 0 ] && [ "$1" != -- ]; do flags+=("$1"); shift; done
  [ "${1:-}" = -- ] && shift
  [ $# -gt 0 ] || return 0
  local package file
  local packages=()
  mapfile -t packages < <(for file in "$@"; do package_of "$file"; done | sort -u)
  for package in "${packages[@]}"; do
    local files=()
    for file in "$@"; do
      [ "$(package_of "$file")" = "$package" ] && files+=("${file#"$package"/}")
    done
    (cd "$package" && config run test "${flags[@]}" "${files[@]}") || status=1
  done
  return "$status"
}

package_of() { # path
  local dir marker
  marker="$(config get package_marker)"
  dir="$(dirname "$1")"
  if [ -z "$marker" ]; then echo .; return; fi
  while [ "$dir" != . ] && [ ! -f "$dir/$marker" ]; do dir="$(dirname "$dir")"; done
  echo "$dir"
}

setup_worktree() { # dir
  [ -n "$(config get commands.setup)" ] || return 0
  (cd "$1" && config run setup)
}

check_commit_message() { # message
  local subject
  subject="$(printf '%s\n' "$1" | head -n1)"
  printf '%s' "$subject" | grep -qE "^($COMMIT_TYPES)(\([^)]+\))?!?: [^ ]" \
    || die "subject must start with one of: ${COMMIT_TYPES//|/, } — got: $subject"
  if [ "$(config get git.allow_coauthors)" = false ] && printf '%s' "$1" | grep -qi '^co-authored-by:'; then
    die "commit messages in this repository carry no Co-Authored-By line"
  fi
}
