#!/usr/bin/env bash
set -euo pipefail

# The project requires Java 21; pin the toolchain so results are comparable
# across shells that otherwise select a different Homebrew JDK.
if [[ "$(uname -s)" == "Darwin" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# Reproduce the Issue #130 baseline on a clean checkout.  Surefire's derived
# TSV is read from target/; command logs use an external output directory.
# Keep logs outside target because clean test/package remove target/.
output_dir="${1:-/private/tmp/llm-wiki-km-issue-130-baseline}"
mkdir -p "$output_dir"

run_timed() {
  local name="$1"
  shift
  {
    printf 'command='
    printf '%q ' "$@"
    printf '\n'
    /usr/bin/time -p "$@"
  } >"$output_dir/$name.log" 2>&1
}

run_timed mvn-test mvn test
run_timed mvn-clean-test mvn clean test
run_timed mvn-clean-package mvn clean package
run_timed generate-sources-warm mvn generate-sources
run_timed compile-skip-tests mvn -DskipTests compile
run_timed test-compile-skip-tests mvn -DskipTests test-compile
run_timed package-skip-tests mvn -DskipTests package

# Surefire's XML is the source of truth for class-level test duration.  Ruby's
# standard XML parser keeps this helper dependency-free on macOS and CI images.
ruby -rrexml/document -e '
  Dir["target/surefire-reports/TEST-*.xml"].each do |file|
    suite = REXML::Document.new(File.read(file)).root
    puts [suite.attributes["time"], suite.attributes["tests"], suite.attributes["failures"], suite.attributes["errors"], suite.attributes["skipped"], suite.attributes["name"]].join("\t")
  end
' | sort -nr >"$output_dir/surefire-class-times.tsv"

printf 'Baseline logs written to %s\n' "$output_dir"
printf 'Top 20 classes:\n'
head -20 "$output_dir/surefire-class-times.tsv"
