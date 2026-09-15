#!/usr/bin/env python3
"""Detect drift between the Swift and Kotlin cores that Android ports by hand.

Three checks, each returning a list of human-readable failure lines (empty when the
platforms agree):

  prompts        apps/ios/Core/TeachingPolicy.swift vs .../core/TeachingPolicy.kt: the actual
                 instruction text sent to the model, compared with string interpolation
                 collapsed to a placeholder so wording (not syntax) is what is checked.
  constants      A fixed table of shared numeric thresholds, each extracted from both
                 platforms with a small regex and compared for equality.
  archive-fields Stored, non-optional properties of the Codable structs that make up the
                 backup archive, which must all exist on the matching Kotlin data class
                 (and, where Models.kt validates them explicitly, in its `fields(...)`
                 required-field lists). Swift's synthesized Decodable requires every
                 non-optional key at decode time; a field added only in Swift makes the
                 iPhone reject an Android-exported backup. Optional Swift properties
                 (`Type?`) are exempt: Swift omits a nil optional from encoded JSON, so
                 Kotlin need not carry it as a required field either.

Every failure line reads:
  <check>: <detail>. Update <kotlin file>:<line> to match <swift file>:<line>.
"""
import argparse
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]


def format_failure(check, detail, kotlin_path, kotlin_line, swift_path, swift_line):
    return f"{check}: {detail}. Update {kotlin_path}:{kotlin_line} to match {swift_path}:{swift_line}."


def line_of(text, pos):
    return text.count('\n', 0, pos) + 1


# --------------------------------------------------------------------------------------
# prompts

def _extract_string_literal(text, pos):
    """`text[pos]` is the opening `"` of a literal; return (raw content, index after it,
    whether it was triple-quoted)."""
    if text[pos:pos + 3] == '"""':
        end = text.index('"""', pos + 3)
        return text[pos + 3:end], end + 3, True
    j = pos + 1
    while text[j] != '"':
        j += 2 if text[j] == '\\' else 1
    return text[pos + 1:j], j + 1, False


def _kotlin_trim_indent(content):
    """Mirror Kotlin's `String.trimIndent()`: strip the common leading whitespace of all
    non-blank lines, then drop a first or last line that is left blank."""
    lines = content.split('\n')
    non_blank = [line for line in lines if line.strip() != '']
    indent = min((len(line) - len(line.lstrip(' \t')) for line in non_blank), default=0)
    lines = [line[indent:] for line in lines]
    if lines and lines[0].strip() == '':
        lines = lines[1:]
    if lines and lines[-1].strip() == '':
        lines = lines[:-1]
    return '\n'.join(lines)


def _collapse_swift_interp(s):
    """Replace each Swift `\\(expr)` with a `{}` placeholder. Delegates to
    `_swift_paren_end` so a nested string literal inside `expr` (which may itself
    contain parens) is skipped as one opaque unit rather than confusing the count."""
    out, i, n = [], 0, len(s)
    while i < n:
        if s[i:i + 2] == '\\(':
            i = _swift_paren_end(s, i + 2)
            out.append('{}')
        else:
            out.append(s[i]); i += 1
    return ''.join(out)


def _collapse_kotlin_interp(s):
    """Replace each Kotlin `${expr}` (balanced braces) or `$name` with a `{}` placeholder."""
    out, i, n = [], 0, len(s)
    while i < n:
        if s[i:i + 2] == '${':
            depth, i = 1, i + 2
            while i < n and depth:
                depth += {'{': 1, '}': -1}.get(s[i], 0)
                i += 1
            out.append('{}')
        elif s[i] == '$' and i + 1 < n and (s[i + 1].isalpha() or s[i + 1] == '_'):
            j = i + 1
            while j < n and (s[j].isalnum() or s[j] == '_'):
                j += 1
            out.append('{}'); i = j
        else:
            out.append(s[i]); i += 1
    return ''.join(out)


def _dedent_swift_triple(content):
    """Mirror the Swift compiler's multi-line string dedent: strip the closing
    delimiter's leading whitespace from every line, and drop the newline that follows
    the opening `\"\"\"` and precedes the closing one."""
    lines = content.split('\n')
    if lines and lines[0] == '':
        lines = lines[1:]
    indent = ''
    if lines and lines[-1].strip() == '':
        indent, lines = lines[-1], lines[:-1]
    return '\n'.join(line[len(indent):] if line.startswith(indent) else line for line in lines)


def normalize_swift_prompt(literal):
    if literal.startswith('"""'):
        return _collapse_swift_interp(_dedent_swift_triple(literal[3:-3]))
    return _collapse_swift_interp(literal[1:-1])


def normalize_kotlin_prompt(expr):
    """Normalize a Kotlin expression-bodied prompt: a `\"\"\"...\"\"\"` literal (optionally
    `.trimIndent()`-ed), or a `"..."  + expr + "..."` concatenation chain. `+`-joined
    literals are merged into one string with `{}` between them; a bracket-depth counter
    keeps a string nested inside a hole (e.g. `theme?.situation ?: "fallback"`) from
    being mistaken for another top-level literal."""
    out, i, n, depth, pending_hole = [], 0, len(expr), 0, False
    while i < n:
        c = expr[i]
        if depth == 0 and c == '"':
            if pending_hole:
                out.append('{}'); pending_hole = False
            content, i, is_triple = _extract_string_literal(expr, i)
            if is_triple:
                content = _kotlin_trim_indent(content)
            out.append(_collapse_kotlin_interp(content))
            continue
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        elif depth == 0 and not c.isspace():
            pending_hole = True
        i += 1
    return ''.join(out)


def swift_function_bodies(text):
    """dict name -> (return type, body text) for each `public static func` in `text`."""
    result = {}
    for match in re.finditer(r'public static func (\w+)\(.*?\)\s*(?:->\s*(\w+)\s*)?\{', text):
        depth, i = 1, match.end()
        start = i
        while depth:
            depth += {'{': 1, '}': -1}.get(text[i], 0)
            i += 1
        result[match.group(1)] = (match.group(2) or 'Void', text[start:i - 1])
    return result


def _swift_literal_end(text, pos):
    """`text[pos]` is a literal's opening quote; return the index just after its
    matching closing quote. A `\\(expr)` interpolation is skipped as a balanced-paren
    unit, so a nested string inside it (e.g. `\\(a ?? "fallback")`) is never mistaken
    for the outer literal's own closing quote."""
    if text[pos:pos + 3] == '"""':
        pos += 3
        while text[pos:pos + 3] != '"""':
            pos = _swift_paren_end(text, pos + 2) if text[pos:pos + 2] == '\\(' else pos + 1
        return pos + 3
    pos += 1
    while text[pos] != '"':
        if text[pos:pos + 2] == '\\(':
            pos = _swift_paren_end(text, pos + 2)
        else:
            pos += 2 if text[pos] == '\\' else 1
    return pos + 1


def _swift_paren_end(text, pos):
    """`text[pos]` is just after an interpolation's opening `(`; return the index just
    after its matching `)`, treating any nested string literal as opaque."""
    depth = 1
    while depth:
        if text[pos] == '"':
            pos = _swift_literal_end(text, pos)
            continue
        depth += {'(': 1, ')': -1}.get(text[pos], 0)
        pos += 1
    return pos


def swift_prompts(text):
    """dict function name -> normalized prompt text, for functions whose entire body is
    a single string literal (excludes boolean predicates and multi-statement helpers)."""
    result = {}
    for name, (return_type, body) in swift_function_bodies(text).items():
        stripped = body.strip()
        if return_type == 'String' and stripped.startswith('"') and \
                _swift_literal_end(stripped, 0) == len(stripped):
            result[name] = normalize_swift_prompt(stripped)
    return result


def kotlin_functions(text):
    """dict name -> (kind, text) for each `fun` in a Kotlin object; kind is 'expr' for a
    `fun NAME(...) = EXPR` body or 'block' for a `fun NAME(...) { ... }` one."""
    result = {}
    for match in re.finditer(r'\n[ \t]+fun (\w+)\([^\n]*?\)(?:\s*:\s*[\w<>,.? ]+)?\s*(=|\{)', text):
        name, marker = match.group(1), match.group(2)
        if marker == '{':
            depth, i = 1, match.end()
            start = i
            while depth:
                depth += {'{': 1, '}': -1}.get(text[i], 0)
                i += 1
            result[name] = ('block', text[start:i - 1])
        else:
            start = match.end()
            boundary = re.search(r'\n[ \t]*(?:fun |\})', text[start:])
            end = start + boundary.start() if boundary else len(text)
            result[name] = ('expr', text[start:end].strip())
    return result


def kotlin_prompts(text):
    """dict function name -> normalized prompt text, for expression-bodied functions
    whose value is a string (excludes boolean predicates and block-bodied helpers)."""
    return {name: normalize_kotlin_prompt(body) for name, (kind, body) in kotlin_functions(text).items()
            if kind == 'expr' and body.startswith('"')}


def check_prompts(swift_path, kotlin_path):
    swift_text, kotlin_text = swift_path.read_text(), kotlin_path.read_text()
    swift, kotlin = swift_prompts(swift_text), kotlin_prompts(kotlin_text)
    failures = []
    for name in sorted(set(swift) | set(kotlin)):
        swift_match = re.search(r'public static func ' + name + r'\(', swift_text)
        kotlin_match = re.search(r'\bfun ' + name + r'\(', kotlin_text)
        swift_line = line_of(swift_text, swift_match.start()) if swift_match else 1
        kotlin_line = line_of(kotlin_text, kotlin_match.start()) if kotlin_match else 1
        if name not in kotlin:
            detail = f'{name}() exists in Swift but has no Kotlin counterpart'
        elif name not in swift:
            detail = f'{name}() exists in Kotlin but has no Swift counterpart'
        elif swift[name] != kotlin[name]:
            detail = f'{name}() wording differs between platforms'
        else:
            continue
        failures.append(format_failure('prompts', detail, kotlin_path, kotlin_line, swift_path, swift_line))
    return failures


# --------------------------------------------------------------------------------------
# constants

# name -> ((swift relpath, swift regex), (kotlin relpath, kotlin regex), kind)
# `kind` is 'scalar' for a single number or 'list' for a bracketed, comma-separated list.
# Only constants extractable with one small, unambiguous regex per side are listed here.
CONSTANTS = [
    ('transcript_gap_ms', 'scalar',
     ('apps/ios/Core/Models.swift', r'fragment\.startMS - result\[i\]\.endMS <= (\d[\d_]*)'),
     ('apps/android/app/src/main/java/chat/mural/core/Models.kt', r'f\.startMS - p\.endMS <= (\d[\d_]*)')),
    ('redirect_confidence', 'scalar',
     ('apps/ios/Core/TeachingPolicy.swift', r'confidence > (\d+\.\d+)'),
     ('apps/android/app/src/main/java/chat/mural/core/TeachingPolicy.kt', r'confidence>(\d+\.\d+)')),
    ('max_words', 'scalar',
     ('apps/ios/Core/LearningEngine.swift', r'proposal\.words\.count <= (\d[\d_]*)'),
     ('apps/android/app/src/main/java/chat/mural/core/LearningEngine.kt', r'proposal\.words\.size>(\d[\d_]*)')),
    ('next_goal_prefix', 'scalar',
     ('apps/ios/Core/LearningEngine.swift', r'nextGoal\.prefix\((\d[\d_]*)\)'),
     ('apps/android/app/src/main/java/chat/mural/core/LearningEngine.kt', r'nextGoal\.take\((\d[\d_]*)\)')),
    ('capability_prefix', 'scalar',
     ('apps/ios/Core/LearningEngine.swift', r'capability\.prefix\((\d[\d_]*)\)'),
     ('apps/android/app/src/main/java/chat/mural/core/LearningEngine.kt', r'capability\.take\((\d[\d_]*)\)')),
    ('min_word_confidence', 'scalar',
     ('apps/ios/Core/LearningEngine.swift', r'word\.confidence >= (\d+\.\d+)'),
     ('apps/android/app/src/main/java/chat/mural/core/LearningEngine.kt', r'word\.confidence !in (\d+\.\d+)\.\.')),
    ('imitation_window_ms', 'scalar',
     ('apps/ios/Core/LearningEngine.swift', r'\$0\.endMS < (\d[\d_]*)'),
     ('apps/android/app/src/main/java/chat/mural/core/LearningEngine.kt', r'passage\.startMS-p\.endMS<(\d[\d_]*)')),
    ('review_intervals_days', 'list',
     ('apps/ios/Core/LearningEngine.swift', r'\[([\d., ]+)\]\[bars\] \* 86400'),
     ('apps/android/app/src/main/java/chat/mural/core/LearningEngine.kt', r'listOf\(([\d., ]+)\)\[bars\]\*86400')),
    ('steady_window_days', 'scalar',
     ('apps/ios/Core/LearningEngine.swift', r'>= (\d[\d_]*) \* 86400'),
     ('apps/android/app/src/main/java/chat/mural/core/LearningEngine.kt', r'>=(\d[\d_]*)\*86400')),
    ('steady_min_days', 'scalar',
     ('apps/ios/Core/LearningEngine.swift', r'days >= (\d[\d_]*) &&'),
     ('apps/android/app/src/main/java/chat/mural/core/LearningEngine.kt', r'days>=(\d[\d_]*) &&')),
    ('steady_min_contexts', 'scalar',
     ('apps/ios/Core/LearningEngine.swift', r'contexts >= (\d[\d_]*) &&'),
     ('apps/android/app/src/main/java/chat/mural/core/LearningEngine.kt', r'contexts>=(\d[\d_]*) &&')),
    ('capability_evidence_min', 'scalar',
     ('apps/ios/Core/LearningEngine.swift', r'value\.count >= (\d[\d_]*)'),
     ('apps/android/app/src/main/java/chat/mural/core/LearningEngine.kt', r'\.size>=(\d[\d_]*) \}')),
    ('idle_voice_s', 'scalar',
     ('apps/ios/App/ConversationCoordinator.swift', r'lastActivity\) > (\d[\d_]*)'),
     ('apps/android/app/src/main/java/chat/mural/core/SessionLimits.kt', r'idleSeconds > (\d[\d_]*)')),
]


def _parse_number(raw):
    return float(raw.replace('_', ''))


def _parse_value(raw, kind):
    if kind == 'list':
        return [_parse_number(x) for x in raw.split(',')]
    return _parse_number(raw)


def _find(root, relpath, pattern):
    """(path, line, raw value) for the first match, or an error message."""
    path = root / relpath
    if not path.exists():
        return f'{relpath} not found'
    text = path.read_text()
    match = re.search(pattern, text)
    if not match:
        return f'pattern not found in {relpath}'
    value = next(g for g in match.groups() if g is not None)
    return path, line_of(text, match.start()), value


def check_constants(root, constants=CONSTANTS):
    failures = []
    for name, kind, (swift_relpath, swift_pattern), (kotlin_relpath, kotlin_pattern) in constants:
        swift_hit = _find(root, swift_relpath, swift_pattern)
        kotlin_hit = _find(root, kotlin_relpath, kotlin_pattern)
        errors = [hit for hit in (swift_hit, kotlin_hit) if isinstance(hit, str)]
        if errors:
            failures.append(f"constants: {name}: {'; '.join(errors)}. Restore the constant or update CONSTANTS in scripts/check_cross_platform.py.")
            continue
        swift_path, swift_line, swift_raw = swift_hit
        kotlin_path, kotlin_line, kotlin_raw = kotlin_hit
        if _parse_value(swift_raw, kind) != _parse_value(kotlin_raw, kind):
            failures.append(format_failure(
                'constants', f'{name} is {swift_raw} in Swift but {kotlin_raw} in Kotlin',
                kotlin_path, kotlin_line, swift_path, swift_line))
    return failures


# --------------------------------------------------------------------------------------
# archive-fields

ARCHIVE_STRUCTS = ['Archive', 'SessionRecord', 'Fragment', 'Preferences', 'Assessment',
                   'WordProposal', 'TopicBrief', 'SourceLink']

# `Models.kt`'s `requireFields()` validates each JSON object under a differently named
# local variable; this maps a struct to that variable so its `fields(...)` call can be
# matched unambiguously. `SourceLink` is intentionally absent: `requireFields()` does
# not walk into `topics[].sources[]`, so there is no call to compare against.
FIELDS_CALL_VARIABLE = {
    'Archive': 'root', 'Preferences': 'preferences', 'SessionRecord': 's',
    'Fragment': 'fragment.jsonObject', 'Assessment': 'assessment.jsonObject',
    'WordProposal': 'word.jsonObject', 'TopicBrief': 'topic.jsonObject',
}


def _parse_stored_property(line):
    """Return (name, type annotation or None, default or None) for a
    `public var/let NAME[: TYPE][ = DEFAULT]` declaration, or None if `line` is not one
    (a method, a computed property's `{ ... }` body, or an unrelated line). The type is
    scanned with bracket depth tracking so a dictionary type such as `[String: String]`
    -- which contains a space -- is not mistaken for the end of the annotation."""
    match = re.match(r'public (?:var|let) (\w+)\s*', line)
    if not match:
        return None
    name, rest = match.group(1), line[match.end():]
    type_ann = None
    if rest.startswith(':'):
        rest, depth, i = rest[1:].lstrip(), 0, 0
        while i < len(rest):
            c = rest[i]
            if c in '[(<':
                depth += 1
            elif c in '])>':
                depth -= 1
            elif c in '={' and depth == 0:
                break
            i += 1
        type_ann, rest = rest[:i].strip(), rest[i:]
    rest, default = rest.strip(), None
    if rest.startswith('='):
        default = rest[1:].strip()
    elif rest:
        return None  # trailing junk, e.g. a computed property's `{ ... }` body
    return name, type_ann, default


def swift_struct_required_fields(text, struct_name):
    """[(field name, line number), ...] for `struct_name`'s non-optional stored
    properties, in declaration order, or None if the struct isn't found. A stored
    property is `public var`/`public let`; a trailing `?` type (or a `= nil` default)
    marks it optional, since Swift's synthesized encoder omits a nil optional key
    entirely rather than encoding `null` -- so Kotlin need not require it either."""
    match = re.search(r'public struct ' + struct_name + r'\b[^\n]*\{', text)
    if not match:
        return None
    depth, i = 1, match.end()
    start = i
    while depth:
        depth += {'{': 1, '}': -1}.get(text[i], 0)
        i += 1
    body = text[start:i - 1]
    base_line = line_of(text, start)
    fields = []
    for offset, line in enumerate(body.split('\n')):
        parsed = _parse_stored_property(line.strip())
        if not parsed:
            continue
        name, type_ann, default = parsed
        if (type_ann and type_ann.endswith('?')) or default == 'nil':
            continue
        fields.append((name, base_line + offset))
    return fields


def swift_struct_field_names(text, struct_name):
    """Names of every stored property of `struct_name`, optional or not."""
    match = re.search(r'public struct ' + struct_name + r'\b[^\n]*\{', text)
    if not match:
        return set()
    depth, i = 1, match.end()
    start = i
    while depth:
        depth += {'{': 1, '}': -1}.get(text[i], 0)
        i += 1
    return {parsed[0] for parsed in map(_parse_stored_property, (l.strip() for l in text[start:i - 1].split('\n'))) if parsed}


def kotlin_parameters_without_default(text, struct_name):
    """Constructor properties of `data class struct_name(...)` that have no default value."""
    match = re.search(r'data class ' + struct_name + r'\(', text)
    if not match:
        return []
    depth, i, start, params = 1, match.end(), match.end(), []
    while depth:
        c = text[i]
        if c in '([{<':
            depth += 1
        elif c in ')]}>':
            depth -= 1
        if (c == ',' and depth == 1) or depth == 0:
            params.append(text[start:i]); start = i + 1
        i += 1
    names = []
    for param in params:
        declared = re.match(r'\s*(?:val|var)\s+(\w+)\s*:', param)
        if declared and '=' not in param:
            names.append(declared.group(1))
    return names


def kotlin_data_class_fields(text, struct_name):
    """Property names of `data class struct_name(...)`, or None if not found."""
    match = re.search(r'data class ' + struct_name + r'\(', text)
    if not match:
        return None
    depth, i = 1, match.end()
    start = i
    while depth:
        depth += {'(': 1, ')': -1}.get(text[i], 0)
        i += 1
    return re.findall(r'\b(?:val|var)\s+(\w+)\s*:', text[start:i - 1])


def check_archive_fields(swift_path, kotlin_path):
    swift_text, kotlin_text = swift_path.read_text(), kotlin_path.read_text()
    failures = []
    for struct_name in ARCHIVE_STRUCTS:
        required = swift_struct_required_fields(swift_text, struct_name)
        if required is None:
            if re.search(r'data class ' + struct_name + r'\(', kotlin_text):
                failures.append(f'archive-fields: {struct_name} is listed in ARCHIVE_STRUCTS but was not found in {swift_path}.')
            continue
        swift_names = swift_struct_field_names(swift_text, struct_name)
        required_names = {name for name, _ in required}
        kotlin_fields = kotlin_data_class_fields(kotlin_text, struct_name)
        if kotlin_fields is None:
            struct_line = line_of(swift_text, re.search(r'public struct ' + struct_name + r'\b', swift_text).start())
            failures.append(format_failure(
                'archive-fields', f'{struct_name} has no matching Kotlin data class',
                kotlin_path, 1, swift_path, struct_line))
            continue
        kotlin_line = line_of(kotlin_text, re.search(r'data class ' + struct_name + r'\(', kotlin_text).start())
        kotlin_set = set(kotlin_fields)
        struct_line = line_of(swift_text, re.search(r'public struct ' + struct_name + r'\b', swift_text).start())
        for field_name in kotlin_parameters_without_default(kotlin_text, struct_name):
            if field_name not in swift_names:
                failures.append(format_failure(
                    'archive-fields', f'{struct_name}.{field_name} has no default in Kotlin but does not exist in Swift',
                    kotlin_path, kotlin_line, swift_path, struct_line))
        # Optional values can be absent in an archive, but must survive when present.
        for field_name in sorted(swift_names):
            if field_name not in kotlin_set:
                failures.append(format_failure(
                    'archive-fields', f'{struct_name}.{field_name} is stored in Swift but missing from the Kotlin data class',
                    kotlin_path, kotlin_line, swift_path, struct_line))

        variable = FIELDS_CALL_VARIABLE.get(struct_name)
        if not variable:
            continue
        call_match = re.search(r'fields\(' + re.escape(variable) + r',\s*"([^"]*)"\)', kotlin_text)
        if not call_match:
            if re.search(r'\bfun fields\(', kotlin_text):
                failures.append(format_failure(
                    'archive-fields', f'{struct_name} has no fields({variable}, ...) required-key check in Kotlin, so archives Swift rejects would decode with defaults',
                    kotlin_path, kotlin_line, swift_path, struct_line))
            continue
        call_line = line_of(kotlin_text, call_match.start())
        call_fields = set(call_match.group(1).split())
        for field_name in sorted(call_fields - required_names):
            state = 'optional' if field_name in swift_names else 'missing'
            failures.append(format_failure(
                'archive-fields', f'{struct_name}.{field_name} is required by the Kotlin fields() check but is {state} in Swift',
                kotlin_path, call_line, swift_path, struct_line))
        for field_name, swift_line in required:
            if field_name not in call_fields:
                failures.append(format_failure(
                    'archive-fields', f'{struct_name}.{field_name} is required in Swift but missing from the Kotlin fields() check',
                    kotlin_path, call_line, swift_path, swift_line))
    return failures


# --------------------------------------------------------------------------------------

def run_checks(root):
    failures = []
    failures += check_prompts(root / 'apps/ios/Core/TeachingPolicy.swift',
                               root / 'apps/android/app/src/main/java/chat/mural/core/TeachingPolicy.kt')
    failures += check_constants(root)
    failures += check_archive_fields(root / 'apps/ios/Core/Models.swift',
                                      root / 'apps/android/app/src/main/java/chat/mural/core/Models.kt')
    return failures


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=pathlib.Path, default=ROOT)
    args = parser.parse_args(argv)
    failures = run_checks(args.root)
    prefix = f"{args.root.resolve()}/"
    for failure in failures:
        print(failure.replace(prefix, ''))
    return 1 if failures else 0


if __name__ == '__main__':
    raise SystemExit(main())
