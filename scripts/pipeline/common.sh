# Sourced by the pipeline scripts: what counts as production Kotlin in Margem.
# Tests, the pit-sql tool, build scripts and the pipeline bookkeeping are never counted.
PROD_PATHSPEC=(
  '--' '*.kt'
  ':(exclude)*/src/test/*' ':(exclude)*/src/commonTest/*' ':(exclude)*/src/jvmTest/*'
  ':(exclude)*/src/androidTest/*' ':(exclude)*/src/iosTest/*'
  ':(exclude)tools/*' ':(exclude).work/*'
)

need_base() { # name base
  [ -n "$2" ] || { echo "$1: --base <ref> is required" >&2; exit 2; }
  git rev-parse --verify --quiet "$2" >/dev/null || { echo "$1: not a valid ref: $2" >&2; exit 2; }
}

# Untracked files become visible to git diff without staging their content.
intent_to_add() { git add -A -N >/dev/null 2>&1 || true; }

# file:line of every production line added since <base>, one per line.
added_lines() { # base
  git diff -U0 "$1" "${PROD_PATHSPEC[@]}" | awk '
    /^\+\+\+ b\// { file = substr($0, 7); next }
    /^\+\+\+/     { file = ""; next }
    /^@@/ { match($0, /\+[0-9]+/); n = substr($0, RSTART + 1, RLENGTH - 1) + 0; next }
    /^\+/ { if (file != "") print file ":" n; n++ }'
}
