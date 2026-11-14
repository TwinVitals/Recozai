#!/bin/sh
set -eu

license_key="${PRIMEUI_LICENSE_KEY:-}"
encoded_license="$(printf '%s' "$license_key" | base64 | tr -d '\n')"
printf 'window.__RECOZAI_RUNTIME_CONFIG__ = { primeUiLicenseBase64: "%s" };\n' \
  "$encoded_license" > /tmp/runtime-config.js
