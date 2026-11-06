"""Shared configuration access for shell gates and their Python checks."""
import json
import os
import re
from pathlib import Path
import subprocess
import sys


def read():
    path = os.environ.get('PIPELINE_CONFIG')
    if not path:
        root = Path(subprocess.check_output(['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'], text=True).strip()).parent
        path = root / '.claude/pipeline/config.json'
    try:
        config = json.loads(Path(path).read_text())
        if config.get('version') != 1:
            raise ValueError('expected configuration version 1')
        validate(config)
        return config
    except (OSError, ValueError, KeyError, TypeError, re.error) as exc:
        raise SystemExit(f'pipeline configuration {path}: {exc}')


def validate(config):
    """Reject incomplete profiles before any gate changes the Git index."""
    def require(mapping, key, kind):
        if key not in mapping or not isinstance(mapping[key], kind):
            raise ValueError(f'{key} must be {kind.__name__}')
        return mapping[key]

    for key in ('git', 'paths', 'commands', 'coverage', 'budgets', 'localization', 'patterns', 'rules', 'workflow'):
        require(config, key, dict)
    require(config, 'package_marker', str)
    for key in ('remote', 'base', 'issue_pattern'):
        require(config['git'], key, str)
    require(config['git'], 'allow_coauthors', bool)
    require(config['git'], 'commit_types', list)
    if not config['git']['commit_types'] or not all(re.fullmatch(r'[A-Za-z][A-Za-z0-9_-]*', t) for t in config['git']['commit_types']):
        raise ValueError('git.commit_types must contain conventional commit type names')
    for key in ('production', 'tests', 'feature_tests', 'test_exclude', 'generated', 'non_code', 'dependencies'):
        values = require(config['paths'], key, list)
        if not all(isinstance(p, str) and p and '\n' not in p for p in values):
            raise ValueError(f'paths.{key} must contain nonempty single-line patterns')
    if not config['paths']['production'] or not config['paths']['feature_tests']:
        raise ValueError('production and feature_tests patterns must not be empty')
    for key in ('test', 'coverage', 'analyze', 'analyzer_report', 'setup', 'dependencies', 'full_test'):
        value = require(config['commands'], key, str)
        if key != 'setup' and not value.strip():
            raise ValueError(f'commands.{key} is required; use true for an explicit no-op')
    require(config['coverage'], 'lcov', str)
    require(config['localization'], 'files', dict)
    require(config['localization'], 'metadata_prefix', str)
    for key in ('compile_error', 'test_description', 'comment', 'symbol_exclude', 'boundary_import', 'analyzer_finding', 'loop'):
        re.compile(require(config['patterns'], key, str))
    for key in ('rules', 'defensive_patterns', 'kinds'):
        for name, pattern in require(config['patterns'], key, list):
            re.compile(pattern)
    for pattern in require(config['patterns'], 'public_symbols', list):
        re.compile(pattern)
    for key in ('performance_context', 'performance_loop_kind', 'repro_file'):
        require(config['patterns'], key, str)
    require(config['patterns'], 'mechanical_codes', list)
    for rule in require(config, 'generate', list):
        require(rule, 'name', str)
        require(rule, 'paths', list)
        require(rule, 'command', str)
        if rule.get('content_pattern'):
            re.compile(rule['content_pattern'])
    for key in ('candidates', 'review_rounds', 'verify_rounds', 'question_rounds', 'small_fix_lines', 'cluster_size', 'max_proposals', 'metrics_min_features'):
        if require(config['workflow'], key, int) < 1:
            raise ValueError(f'workflow.{key} must be positive')
    require(config['workflow'], 'lenses', list)
    require(config['workflow'], 'fix_matrix', dict)


CONFIG = read()


def get(key):
    value = CONFIG
    for part in key.split('.'):
        value = value[part]
    return value


def pathspec(kind):
    paths = CONFIG['paths']
    if kind == 'production':
        include = paths['production']
        exclude = paths['tests'] + paths['generated']
    elif kind == 'feature_tests':
        include = paths['feature_tests']
        exclude = paths['test_exclude']
    elif kind == 'candidate':
        include, exclude = ['.'], paths['generated']
    else:
        include, exclude = paths[kind], []
    return include + [f':(exclude){p}' for p in exclude + paths['non_code']]


def command(name, args):
    value = get('commands.' + name)
    if not value:
        raise SystemExit(f'configure commands.{name} before running this step')
    # Commands are trusted repository configuration. Arguments retain their boundaries.
    return subprocess.call(['bash', '-c', value + ' "$@"', 'pipeline', *args])


if __name__ == '__main__':
    action, *args = sys.argv[1:]
    if action == 'get':
        value = get(args[0])
        print(value if isinstance(value, str) else json.dumps(value))
    elif action == 'list':
        print('\n'.join(get(args[0])))
    elif action == 'pathspec':
        print('\n'.join(pathspec(args[0])))
    elif action == 'run':
        sys.exit(command(args[0], args[1:]))
    elif action == 'symbols':
        import re
        patterns = [re.compile(p) for p in get('patterns.public_symbols')]
        exclude = re.compile(get('patterns.symbol_exclude'))
        comment = re.compile(get('patterns.comment'))
        count = 0
        for line in sys.stdin:
            if not line.startswith('+') or line.startswith('+++'):
                continue
            code = line[1:].strip()
            if not comment.search(code) and not exclude.search(code) and any(p.search(code) for p in patterns):
                count += 1
        print(count)
    elif action == 'generate':
        import fnmatch
        for rule in CONFIG['generate']:
            selected = [p for p in args if any(fnmatch.fnmatch(p, pattern) for pattern in rule['paths'])]
            pattern = rule.get('content_pattern')
            if selected and (not pattern or any(Path(p).is_file() and __import__('re').search(pattern, Path(p).read_text(), __import__('re').M) for p in selected)):
                result = subprocess.call(['bash', '-c', rule['command']])
                if result:
                    sys.exit(result)
