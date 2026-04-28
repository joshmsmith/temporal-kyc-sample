#!/usr/bin/env bash
set -euo pipefail

echo "Registering custom search attributes..."

temporal operator search-attribute create --name ApplicationStep --type Keyword
temporal operator search-attribute create --name KycStatus       --type Keyword
temporal operator search-attribute create --name ReviewDeadline  --type Keyword

echo "Done."
