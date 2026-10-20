#!/usr/bin/env python3
"""check_updater_script.py <rom.zip> — hard gate against TWRP "ERROR: 6".

The update-binary inside an OTA zip parses META-INF/com/google/android/updater-
script with the edify grammar (bootable/recovery/edify/{lexer.ll,parser.yy}).
If parsing fails the updater exits 6 and TWRP shows "Updater process ended
with ERROR: 6" — the zip is unflashable. This script re-implements that exact
grammar (lexer rules, precedence, bison's `expr ';'` reduction) plus the
parser-time FindFunction() check against the set of functions the AOSP 7.1
updater binary registers, and rejects the zip on any error. Used by
50-build-rom.sh so a parse-broken zip can never reach a release again.
"""
import re
import sys
import zipfile

UPDATER = "META-INF/com/google/android/updater-script"

# Every function name registered by the AOSP 7.1 updater:
# RegisterBuiltins (edify/expr.cpp) + RegisterInstallFunctions (updater/
# install.cpp) + RegisterBlockImageFunctions (updater/blockimg.cpp).
REGISTERED = {
    # RegisterBuiltins
    "ifelse", "abort", "assert", "concat", "is_substring", "stdout", "sleep",
    "less_than_int", "greater_than_int",
    # RegisterInstallFunctions
    "getprop", "file_getprop", "ui_print", "run_program", "delete",
    "delete_recursive", "package_extract_dir", "package_extract_file",
    "format", "mount", "unmount", "is_mounted", "show_progress", "set_progress",
    "symlink", "set_metadata", "set_metadata_recursive",
    "sha1_check", "apply_patch", "apply_patch_check", "install",
    "enable_low_power_mode", "bootradio_big_version", "write_boot_image",
    # RegisterBlockImageFunctions
    "block_image_update", "write_raw_image", "check_first_block",
    "image-range", "range", "blocks", "free_blocks", "compute_dtfile_hash",
    "getmark", "image_patch", "scan_partitions", "logical_and", "logical_or",
    "logical_not", "split_gs", "add", "subtract", "multiply", "divide",
    "blocks_sha256", "parse_ensure_index_range", "value_diff",
    "parse_part_targets", "debug_blockimg_diff",
}

KEYWORDS = {"if": "IF", "then": "THEN", "else": "ELSE", "endif": "ENDIF"}
WORD_RE = re.compile(r'[a-zA-Z0-9_:/.]+')   # exact class from lexer.ll


class LexError(Exception):
    pass


def lex(src):
    toks, i, line, col = [], 0, 1, 1
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"':                                   # quoted string
            i += 1; col += 1
            buf = []
            while True:
                if i >= n:
                    raise LexError(f"line {line} col {col}: unterminated string")
                ch = src[i]
                if ch == '"':
                    i += 1; col += 1
                    break
                if ch == '\\':
                    nxt = src[i + 1] if i + 1 < n else ''
                    if nxt == 'n': buf.append('\n'); i += 2; col += 2
                    elif nxt == 't': buf.append('\t'); i += 2; col += 2
                    elif nxt == '"': buf.append('"'); i += 2; col += 2
                    elif nxt == '\\': buf.append('\\'); i += 2; col += 2
                    elif nxt == 'x' and i + 3 < n and re.match(r'[0-9a-fA-F]{2}', src[i + 2:i + 4]):
                        buf.append(chr(int(src[i + 2:i + 4], 16))); i += 4; col += 4
                    else:
                        buf.append('\\'); i += 1; col += 1
                elif ch == '\n':
                    buf.append('\n'); i += 1; line += 1; col = 1
                else:
                    buf.append(ch); i += 1; col += 1
            toks.append(("STRING", ''.join(buf), line, col))
            continue
        if c == '#':                                    # comment (#.*)?\n — needs the newline
            j = src.find('\n', i)
            if j == -1:
                raise LexError(f"line {line} col {col}: '#' comment without trailing newline")
            i = j + 1; line += 1; col = 1
            continue
        if c == '\n':
            i += 1; line += 1; col = 1
            continue
        if c in ' \t':
            i += 1; col += 1
            continue
        for two, tok in (("&&", "AND"), ("||", "OR"), ("==", "EQ"), ("!=", "NE")):
            if src.startswith(two, i):
                toks.append((tok, None, line, col)); i += 2; col += 2
                break
        else:
            m = WORD_RE.match(src, i)
            if m:
                w = m.group(0)
                if w in KEYWORDS:                       # keyword rules precede identifiers
                    toks.append((KEYWORDS[w], w, line, col))
                else:
                    toks.append(("STRING", w, line, col))
                i = m.end(); col += len(w)
            elif c in '()+,!;':
                toks.append((c, c, line, col)); i += 1; col += 1
            else:
                raise LexError(f"line {line} col {col}: BAD character {c!r}")
        continue
    toks.append(("EOF", None, line, col))
    return toks


# tokens that cannot begin an expression -> bison reduces by `expr ';'`
EXPR_BOUNDARY = {"EOF", ")", ",", ";", "THEN", "ELSE", "ENDIF"}


class Parser:
    def __init__(self, toks):
        self.toks, self.pos, self.errors = toks, 0, 0
        self.funcs = set()

    def peek(self):
        return self.toks[min(self.pos, len(self.toks) - 1)]

    def err(self, tok, msg):
        print(f"line {tok[2]} col {tok[3]}: {msg}")
        self.errors += 1

    def parse_expr(self):
        self.parse_or()
        while self.peek()[0] == ';':
            self.pos += 1
            if self.peek()[0] in EXPR_BOUNDARY:
                break
            self.parse_or()

    def parse_or(self):
        self.parse_and()
        while self.peek()[0] == "OR":
            self.pos += 1
            self.parse_and()

    def parse_and(self):
        self.parse_eq()
        while self.peek()[0] == "AND":
            self.pos += 1
            self.parse_eq()

    def parse_eq(self):
        self.parse_add()
        while self.peek()[0] in ("EQ", "NE"):
            self.pos += 1
            self.parse_add()

    def parse_add(self):
        self.parse_unary()
        while self.peek()[0] == '+':
            self.pos += 1
            self.parse_unary()

    def parse_unary(self):
        if self.peek()[0] == '!':
            self.pos += 1
            self.parse_unary()
        else:
            self.parse_primary()

    def parse_primary(self):
        tok = self.peek()
        t = tok[0]
        if t == "(":
            self.pos += 1
            self.parse_expr()
            if self.peek()[0] != ")":
                self.err(self.peek(), "syntax error, expected ')'")
                while self.peek()[0] not in (")", ";", "EOF"):
                    self.pos += 1
                if self.peek()[0] == ")":
                    self.pos += 1
            else:
                self.pos += 1
        elif t == "IF":
            self.pos += 1
            self.parse_expr()
            if self.peek()[0] != "THEN":
                self.err(self.peek(), "syntax error, expected 'then' in if-expression")
                return
            self.pos += 1
            self.parse_expr()
            if self.peek()[0] == "ELSE":
                self.pos += 1
                self.parse_expr()
            if self.peek()[0] != "ENDIF":
                self.err(self.peek(), "syntax error, expected 'endif' in if-expression")
                return
            self.pos += 1
        elif t == "STRING":
            self.pos += 1
            if self.peek()[0] == "(":                  # function call
                name = tok[1]
                self.funcs.add(name)
                if name not in REGISTERED:
                    self.err(tok, f'unknown function "{name}"')
                self.pos += 1
                if self.peek()[0] != ")":
                    self.parse_expr()
                    while self.peek()[0] == ",":
                        self.pos += 1
                        self.parse_expr()
                if self.peek()[0] != ")":
                    self.err(self.peek(), "syntax error, expected ')' after arglist")
                    while self.peek()[0] != "EOF":
                        self.pos += 1
                else:
                    self.pos += 1
        else:
            self.err(tok, "syntax error, unexpected token")
            if t != "EOF":
                self.pos += 1


def main(path):
    try:
        zf = zipfile.ZipFile(path)
    except zipfile.BadZipFile:
        print(f"FATAL: {path}: not a zip file")
        return 1
    try:
        src = zf.read(UPDATER).decode("utf-8")
    except KeyError:
        print(f"FATAL: {UPDATER} not found in {path}")
        return 1

    try:
        toks = lex(src)
    except LexError as e:
        print(f"UPDATER-SCRIPT LEX ERROR: {e}")
        return 1

    p = Parser(toks)
    p.parse_expr()
    if p.peek()[0] != "EOF":
        p.err(p.peek(), "syntax error: trailing garbage after expression")

    if p.errors:
        print(f"UPDATER-SCRIPT FAILED: {p.errors} error(s) — "
              "the shipped updater would exit 6 (TWRP \"ERROR: 6\")")
        return 1
    unknown = p.funcs - REGISTERED
    if unknown:
        print(f"UPDATER-SCRIPT FAILED: unknown functions: {sorted(unknown)}")
        return 1
    print(f"updater-script OK ({len(p.funcs)} functions, edify parse clean)")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
