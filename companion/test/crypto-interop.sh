#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)";trap 'rm -rf "$TMP"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$TMP" "$ROOT/tests/jvm/android/util/Base64.java" "$ROOT/tests/jvm/CryptoDriver.java" "$ROOT/astlink/src/main/java/com/astos/link/LinkCrypto.java" "$ROOT/astlink/src/main/java/com/astos/link/LinkJson.java"
CP="$TMP";case "${OSTYPE:-}" in msys*|cygwin*) CP="$(cygpath -w "$TMP")";;esac
export CRYPTO_CP="$CP"
node - <<'JS'
const {execFileSync}=require('node:child_process'),assert=require('node:assert/strict'),{SecureLink}=require('./src/secure-link');
const java=process.env.JAVA_HOME+'/bin/java',cp=process.env.CRYPTO_CP;
const frame='Hello ♫ secure Wi-Fi';const sealed=execFileSync(java,['-Dfile.encoding=UTF-8','-cp',cp,'CryptoDriver','seal'],{input:frame+'\n',encoding:'utf8'}).trim();assert.equal(new SecureLink('token','challenge','shared').open(sealed),frame);
const envelope=new SecureLink('token','challenge','shared').seal(frame);assert.equal(execFileSync(java,['-Dfile.encoding=UTF-8','-cp',cp,'CryptoDriver','open'],{input:envelope+'\n',encoding:'utf8'}).trim(),frame);
console.log('JAVA/NODE CRYPTO PASS: identical key derivation, nonce/tag encoding and authenticated Unicode frames');
JS
